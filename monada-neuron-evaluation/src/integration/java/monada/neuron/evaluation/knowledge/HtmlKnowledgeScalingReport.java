package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScaling.AnswerCounts;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScaling.PointResult;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScaling.Sampling;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScalingCorpus.Family;
import monada.neuron.evaluation.knowledge.RecallMetrics.LayerAggregate;
import monada.neuron.evaluation.knowledge.RecallMetrics.Outcome;
import monada.neuron.evaluation.metrics.AllocationMetrics.AllocationSource;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.aggregateJson;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.format;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.number;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.strings;

/**
 * Renders the scaling profile (Issue #111) as machine-readable JSON and a concise Markdown summary: the family's
 * provenance, then per point its corpus identity, removed queries, persisted footprint, recall quality over the curve
 * population and over the point's own eligible set, B3 answer counts, every measured row normalized per operation, the
 * B2 minus B2c wrapper overhead and every unavailable row. The Markdown ends with the manual follow-up triage template.
 * Undefined values are JSON {@code null} and {@code n/a} in Markdown; nothing missing is ever written as zero.
 */
final class HtmlKnowledgeScalingReport {

    /** The manual triage template appended to every Markdown report. Findings are never filed automatically. */
    static final String TRIAGE_TEMPLATE = """
            ## Follow-up triage template

            Copy one block per finding into a new, narrowly scoped issue by hand; this run opens nothing. A finding \
            names an observation and a recommended experiment change or bug, never a speculative fix. Latency \
            values are exploratory: compare points only within one scaling family and one environment (see \
            the protocol's comparison rules).

            ```text
            Title:              [<repository>] <one-line observation>
            Observed:           <value, row and size point, e.g. Recall@5 of B1 over the curve population at N=500>
            Expected:           <baseline: the same metric at N=25, the protocol rule, or the documented contract>
            Reproduction:       ./gradlew :monada-neuron-evaluation:runHtmlKnowledgeScaling \\
                                  -PbenchmarkArgs="--sizes <N,...> --seed <seed>"
                                neuron.commit, store.checkoutCommit, corpus.base.hash, nN.corpus.hash,
                                query.hash, generator.version (local paths redacted)
            Affected layer:     corpus/chunking | Store persistence | Store ranking | Neuron codec/port |
                                reasoning | grounded answer
            Severity:           correctness (a semantic check failed) | quality (a metric moves along the
                                curve) | resource (latency, allocation or footprint trend)
            Evidence:           html-knowledge-scaling.json paths (checks[], scaling.points[].quality,
                                scaling.points[].rows[]) and the attached report
            Repository:         monada-resonance-store | monada-neuron
            Recommendation:     <experiment change, or bug with the narrowest reproduction>
            ```
            """;

    private final Family family;
    private final List<PointResult> results;
    private final Sampling sampling;

    HtmlKnowledgeScalingReport(Family family, List<PointResult> results, Sampling sampling) {
        this.family = Objects.requireNonNull(family, "family must not be null");
        this.results = List.copyOf(results);
        this.sampling = Objects.requireNonNull(sampling, "sampling must not be null");
    }

    /** Renders the family and every point as JSON. */
    String toJson() {
        var json = new StringBuilder("{\n");
        json.append("  \"extension\": ").append(string(HtmlKnowledgeScalingRunner.EXTENSION)).append(",\n");
        json.append("  \"baseCorpusVersion\": ").append(string(family.base().version())).append(",\n");
        json.append("  \"baseCorpusHash\": ").append(string(family.base().corpusHash())).append(",\n");
        json.append("  \"queryVersion\": ").append(string(family.querySet().version())).append(",\n");
        json.append("  \"queryHash\": ").append(string(family.querySet().queryHash())).append(",\n");
        json.append("  \"generatorVersion\": ").append(string(ScalingDistractorGenerator.VERSION)).append(",\n");
        json.append("  \"seed\": ").append(family.seed()).append(",\n");
        json.append("  \"k\": [1, 3, 5],\n");
        json.append("  \"sampling\": { \"warmups\": ").append(sampling.warmups()).append(", \"iterations\": ")
                .append(sampling.iterations()).append(", \"coldIterations\": ").append(sampling.coldIterations())
                .append(", \"ingestWarmups\": ").append(sampling.ingestWarmups()).append(" },\n");
        json.append("  \"curvePopulation\": ").append(strings(family.curve().stream().map(JudgedQuery::queryId)
                .toList())).append(",\n");
        json.append("  \"excludedFromCurve\": ").append(strings(family.excludedFromCurve())).append(",\n");
        json.append("  \"points\": [\n");
        for (var p = 0; p < results.size(); p++) {
            json.append(pointJson(results.get(p))).append(p < results.size() - 1 ? ",\n" : "\n");
        }
        return json.append("  ]\n}").toString();
    }

    private String pointJson(PointResult result) {
        var point = result.point();
        var json = new StringBuilder("    {\n");
        json.append("      \"size\": ").append(point.size())
                .append(", \"corpusVersion\": ").append(string(point.corpus().version()))
                .append(", \"corpusHash\": ").append(string(point.corpus().corpusHash()))
                .append(", \"baseChunks\": ").append(point.baseChunks())
                .append(", \"generatedChunks\": ").append(point.generatedChunks())
                .append(", \"documents\": ").append(point.corpus().documents().size()).append(",\n");
        json.append("      \"eligibleQueries\": ").append(point.eligible().size())
                .append(", \"removedQueries\": ").append(strings(point.removed())).append(",\n");
        json.append("      \"footprint\": { \"persistedAtoms\": ").append(result.footprint().persistedAtoms())
                .append(", \"rootBytes\": ").append(result.footprint().rootBytes())
                .append(", \"rootFiles\": ").append(result.footprint().rootFiles())
                .append(", \"heapRetainedBytes\": null },\n");
        json.append("      \"storeCallsPerB2Recall\": ").append(number(result.storeCallsPerRecall())).append(",\n");
        json.append("      \"quality\": {\n");
        var populations = List.of(HtmlKnowledgeScaling.CURVE, HtmlKnowledgeScaling.POINT);
        for (var p = 0; p < populations.size(); p++) {
            var population = populations.get(p);
            json.append("        ").append(string(population)).append(": [\n");
            var entries = HtmlKnowledgeScaling.SCORED_LAYERS.stream().flatMap(layer -> HtmlKnowledgeScaling.KS
                    .stream().map(k -> qualityJson(result, population, layer, k))).toList();
            json.append(String.join(",\n", entries)).append("\n        ]")
                    .append(p < populations.size() - 1 ? ",\n" : "\n");
        }
        json.append("      },\n      \"answers\": [\n");
        var answers = HtmlKnowledgeScaling.SCORED_LAYERS.stream().flatMap(layer -> HtmlKnowledgeScaling.KS.stream()
                .map(k -> answerJson(layer, k, result.answers().get(layer.label() + "/" + k)))).toList();
        json.append(String.join(",\n", answers)).append("\n      ],\n      \"rows\": [\n");
        var rows = result.rows().stream().map(HtmlKnowledgeScalingReport::rowJson).toList();
        json.append(String.join(",\n", rows)).append("\n      ],\n      \"unavailable\": {");
        var first = true;
        for (var entry : result.unavailable().entrySet()) {
            json.append(first ? " " : ", ").append(string(entry.getKey())).append(": ").append(string(entry.getValue()));
            first = false;
        }
        json.append(first ? "" : " ").append("},\n      \"wrapperOverhead\": [");
        first = true;
        for (var k : HtmlKnowledgeScaling.KS) {
            json.append(first ? " " : ", ").append("{ \"k\": ").append(k)
                    .append(", \"b2MinusB2cMedianNanosPerQuery\": ")
                    .append(result.overhead().get(k).map(HtmlKnowledgeRecallReport::number).orElse("null"))
                    .append(" }");
            first = false;
        }
        return json.append(" ]\n    }").toString();
    }

    private static String qualityJson(PointResult result, String population, Layer layer, int k) {
        var aggregate = result.quality().get(population + "/" + layer.label() + "/" + k);
        var abstention = result.abstention().get(population + "/" + layer.label());
        var json = new StringBuilder("          { \"layer\": ").append(string(layer.label()))
                .append(", \"k\": ").append(k)
                .append(", \"nA\": ").append(aggregate.scoredA())
                .append(", \"nU\": ").append(aggregate.scoredU());
        for (var metric : aggregate.answerable().entrySet()) {
            json.append(", ").append(string(metric.getKey())).append(": ").append(aggregateJson(metric.getValue()));
        }
        json.append(", \"FullEvidence@").append(k).append("\": ").append(aggregateJson(aggregate.fullEvidence()))
                .append(", \"NoAnswerFalsePositiveRate@").append(k).append("\": ")
                .append(number(aggregate.noAnswerFalsePositiveRate()))
                .append(", \"AbstentionPrecision\": ").append(number(abstention.abstentionPrecision()))
                .append(", \"AbstentionRecall\": ").append(number(abstention.abstentionRecall()))
                .append(", \"unscoredQueries\": ").append(abstention.unscored())
                .append(", \"outcomes\": {");
        var totals = outcomes(aggregate);
        var first = true;
        for (var outcome : Outcome.values()) {
            json.append(first ? " " : ", ").append(string(outcome.name())).append(": ").append(totals[outcome.ordinal()]);
            first = false;
        }
        return json.append(" } }").toString();
    }

    private static String answerJson(Layer layer, int k, AnswerCounts counts) {
        return "        { \"evidenceLayer\": " + string(layer.label()) + ", \"answerK\": " + k
                + ", \"answeredA\": " + counts.answeredA() + ", \"abstainedA\": " + counts.abstainedA()
                + ", \"answeredU\": " + counts.answeredU() + ", \"abstainedU\": " + counts.abstainedU()
                + ", \"citesRelevant\": " + counts.citesRelevant() + ", \"citations\": " + counts.citations()
                + ", \"ineligibleCitations\": " + counts.ineligible() + ", \"exactQuotes\": " + counts.exactQuotes()
                + ", \"failedEvidence\": " + counts.failed() + " }";
    }

    private static String rowJson(BenchmarkRunResult row) {
        var operations = operations(row);
        var latency = row.latency();
        return "        { \"row\": " + string(name(row)) + ", \"k\": "
                + Optional.ofNullable(row.diagnostics().get("k")).orElse("null")
                + ", \"operationsPerIteration\": " + operations
                + ", \"warmupIterations\": " + row.diagnostics().get("warmupIterations")
                + ", \"measurementIterations\": " + row.iterations()
                + ", \"medianNanos\": " + number(latency.medianNanos())
                + ", \"p95Nanos\": " + number(latency.p95Nanos())
                + ", \"medianNanosPerOperation\": " + number(latency.medianNanos() / operations)
                + ", \"p95NanosPerOperation\": " + number(latency.p95Nanos() / operations)
                + ", \"throughputOpsPerSec\": " + number(latency.throughputOpsPerSec())
                + ", \"allocatedBytesPerOperation\": " + (row.allocation().source() == AllocationSource.THREAD_MX_BEAN
                        ? number(row.allocation().bytesPerOp()) : "null")
                + ", \"gcCountDelta\": " + row.allocation().gcCountDelta() + " }";
    }

    /** Renders the summary tables and the triage template. */
    String toMarkdown() {
        var md = new StringBuilder("# Scaling profile (").append(HtmlKnowledgeScalingRunner.EXTENSION).append(")\n\n");
        md.append("Family: base `").append(family.base().version()).append("` `")
                .append(family.base().corpusHash(), 0, 12).append("…`, queries `").append(family.querySet().version())
                .append("` `").append(family.querySet().queryHash(), 0, 12).append("…`, generator `")
                .append(ScalingDistractorGenerator.VERSION).append("` seed `").append(family.seed())
                .append("`. Curve population: ").append(family.curve().size()).append(" queries; excluded: ")
                .append(family.excludedFromCurve().isEmpty() ? "none" : String.join(", ", family.excludedFromCurve()))
                .append(".\n\n");

        md.append("## Points\n\n| N | corpus | hash | base + generated | eligible queries | persisted atoms |"
                + " root bytes | root files | Store calls per B2 recall |\n"
                + "| ---: | :--- | :--- | :--- | ---: | ---: | ---: | ---: | ---: |\n");
        for (var result : results) {
            var point = result.point();
            md.append("| ").append(point.size()).append(" | `").append(point.corpus().version()).append("` | `")
                    .append(point.corpus().corpusHash(), 0, 12).append("…` | ").append(point.baseChunks())
                    .append(" + ").append(point.generatedChunks()).append(" | ").append(point.eligible().size())
                    .append(" | ").append(result.footprint().persistedAtoms()).append(" | ")
                    .append(result.footprint().rootBytes()).append(" | ").append(result.footprint().rootFiles())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", result.storeCallsPerRecall()))
                    .append(" |\n");
        }
        md.append("\nHeap retained footprint: unavailable (not measured). Process-cold open: unavailable (not"
                + " measured).\n\n");

        md.append("## Recall over the curve population\n\nSame queries at every point; only the corpus size"
                + " changes.\n\n| layer | k | metric |");
        results.forEach(result -> md.append(" N=").append(result.point().size()).append(" |"));
        md.append("\n| :--- | ---: | :--- |");
        results.forEach(result -> md.append(" ---: |"));
        md.append('\n');
        for (var layer : HtmlKnowledgeScaling.SCORED_LAYERS) {
            for (var k : HtmlKnowledgeScaling.KS) {
                for (var metric : List.of("Recall@" + k, "MRR@" + k, "NoAnswerFalsePositiveRate@" + k, "HIT")) {
                    md.append("| ").append(layer.label()).append(" | ").append(k).append(" | ").append(metric)
                            .append(" |");
                    for (var result : results) {
                        md.append(' ').append(cell(result, HtmlKnowledgeScaling.CURVE, layer, k, metric))
                                .append(" |");
                    }
                    md.append('\n');
                }
            }
        }

        md.append("\n## B3 answers over the point population\n\nCounts `answeredA/abstainedA/answeredU/abstainedU`,"
                + " answered answerable queries citing a relevant chunk, and ineligible citations.\n\n"
                + "| evidence | answerK |");
        results.forEach(result -> md.append(" N=").append(result.point().size()).append(" |"));
        md.append("\n| :--- | ---: |");
        results.forEach(result -> md.append(" :--- |"));
        md.append('\n');
        for (var layer : HtmlKnowledgeScaling.SCORED_LAYERS) {
            for (var k : HtmlKnowledgeScaling.KS) {
                md.append("| ").append(layer.label()).append(" | ").append(k).append(" |");
                for (var result : results) {
                    var counts = result.answers().get(layer.label() + "/" + k);
                    md.append(' ').append(counts.answeredA()).append('/').append(counts.abstainedA()).append('/')
                            .append(counts.answeredU()).append('/').append(counts.abstainedU()).append(", relevant ")
                            .append(counts.citesRelevant()).append(", ineligible ").append(counts.ineligible())
                            .append(" |");
                }
                md.append('\n');
            }
        }

        md.append("\n## Latency per operation (median / p95) and allocation per operation\n\n"
                + "An operation is one query for warm rows, one chunk for `ingest.remember` and one sample otherwise."
                + " Exploratory, never a verdict.\n\n| row | k |");
        results.forEach(result -> md.append(" N=").append(result.point().size()).append(" |"));
        md.append("\n| :--- | ---: |");
        results.forEach(result -> md.append(" :--- |"));
        md.append('\n');
        var names = new LinkedHashSet<String>();
        results.forEach(result -> result.rows().forEach(row -> names.add(name(row) + "|"
                + Optional.ofNullable(row.diagnostics().get("k")).orElse("-"))));
        results.forEach(result -> result.unavailable().keySet().forEach(label -> names.add(label + "|-")));
        for (var key : names) {
            var name = key.substring(0, key.indexOf('|'));
            var k = key.substring(key.indexOf('|') + 1);
            md.append("| `").append(name).append("` | ").append(k).append(" |");
            for (var result : results) {
                md.append(' ').append(rowCell(result, name, k)).append(" |");
            }
            md.append('\n');
        }

        md.append("\n## Neuron wrapper overhead\n\n`warm.b2` minus `warm.b2c`, median nanoseconds per query: the"
                + " adapter's own work (request handling, decoding, merge and reference minting) with the Store calls"
                + " it must make removed. Never computed as B2 minus B1.\n\n| k |");
        results.forEach(result -> md.append(" N=").append(result.point().size()).append(" |"));
        md.append("\n| ---: |");
        results.forEach(result -> md.append(" ---: |"));
        md.append('\n');
        for (var k : HtmlKnowledgeScaling.KS) {
            md.append("| ").append(k).append(" |");
            for (var result : results) {
                md.append(' ').append(result.overhead().get(k).map(value -> nanos(value)).orElse("n/a")).append(" |");
            }
            md.append('\n');
        }
        return md.append('\n').append(TRIAGE_TEMPLATE).toString();
    }

    private static String cell(PointResult result, String population, Layer layer, int k, String metric) {
        var aggregate = result.quality().get(population + "/" + layer.label() + "/" + k);
        if (metric.equals("HIT")) {
            return Integer.toString(outcomes(aggregate)[Outcome.HIT.ordinal()]) + "/" + aggregate.scoredA();
        }
        if (metric.startsWith("NoAnswer")) {
            return format(aggregate.noAnswerFalsePositiveRate());
        }
        return format(aggregate.answerable().get(metric).value());
    }

    private static String rowCell(PointResult result, String name, String k) {
        var row = result.rows().stream().filter(candidate -> name(candidate).equals(name)
                && Optional.ofNullable(candidate.diagnostics().get("k")).orElse("-").equals(k)).findFirst();
        if (row.isEmpty()) {
            var reason = result.unavailable().get(k.equals("-") ? name : name + ".k" + k);
            return reason == null ? "n/a" : "unavailable";
        }
        var operations = operations(row.get());
        var allocation = row.get().allocation().source() == AllocationSource.THREAD_MX_BEAN
                ? bytes(row.get().allocation().bytesPerOp()) : "n/a";
        return nanos(row.get().latency().medianNanos() / operations) + " / "
                + nanos(row.get().latency().p95Nanos() / operations) + ", " + allocation;
    }

    /** Sums the outcome counts of every category of {@code aggregate}, indexed by {@link Outcome#ordinal()}. */
    private static int[] outcomes(LayerAggregate aggregate) {
        var totals = new int[Outcome.values().length];
        aggregate.outcomes().values().forEach(byOutcome -> byOutcome.forEach((outcome, count) ->
                totals[outcome.ordinal()] += count));
        return totals;
    }

    private static String name(BenchmarkRunResult row) {
        return row.benchmarkName().substring("scaling.".length());
    }

    private static int operations(BenchmarkRunResult row) {
        return Integer.parseInt(row.diagnostics().get("operationsPerIteration"));
    }

    private static String nanos(double value) {
        if (Math.abs(value) < 1_000.0) {
            return String.format(Locale.ROOT, "%.0f ns", value);
        }
        if (Math.abs(value) < 1_000_000.0) {
            return String.format(Locale.ROOT, "%.1f µs", value / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.2f ms", value / 1_000_000.0);
    }

    private static String bytes(double value) {
        return value < 1024.0 ? String.format(Locale.ROOT, "%.0f B", value)
                : String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
    }
}
