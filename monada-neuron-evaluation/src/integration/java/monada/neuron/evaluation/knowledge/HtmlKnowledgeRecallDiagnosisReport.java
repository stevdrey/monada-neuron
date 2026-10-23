package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallDiagnosis.PointDiagnosis;
import monada.neuron.evaluation.knowledge.MergeCounterfactuals.Rule;
import monada.neuron.evaluation.knowledge.RecallDiagnosisLadder.Rung;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.CandidateCeiling;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.ChunkFate;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.Displacer;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.Reason;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.Regression;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.SystemScore;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.TokenRow;
import monada.neuron.evaluation.knowledge.SignalHitCapture.Hit;
import monada.neuron.evaluation.knowledge.SignalHitCapture.QueryCapture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.format;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.number;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.strings;

/**
 * Renders the B2 recall diagnosis (Issue #135): the machine-readable per-query, per-Signal and per-hit attribution as
 * JSON, and a concise Markdown summary. Every merge alternative is labelled research-only and in-sample exploratory.
 */
final class HtmlKnowledgeRecallDiagnosisReport {

    /** The label every alternative carries. */
    static final String RESEARCH_ONLY = "research-only, in-sample exploratory over the #111 curve cohort; never"
            + " substituted for B2";

    private final HtmlKnowledgeQuerySet querySet;
    private final List<JudgedQuery> cohort;
    private final long seed;
    private final List<PointDiagnosis> points;

    HtmlKnowledgeRecallDiagnosisReport(HtmlKnowledgeQuerySet querySet, List<JudgedQuery> cohort, long seed,
            List<PointDiagnosis> points) {
        this.querySet = Objects.requireNonNull(querySet, "querySet must not be null");
        this.cohort = List.copyOf(cohort);
        this.seed = seed;
        this.points = List.copyOf(points);
    }

    /** Renders the whole diagnosis as JSON. */
    String toJson() {
        var json = new StringBuilder("{\n");
        json.append("  \"study\": ").append(string(HtmlKnowledgeRecallDiagnosisRunner.STUDY)).append(",\n");
        json.append("  \"queryVersion\": ").append(string(querySet.version())).append(",\n");
        json.append("  \"queryHash\": ").append(string(querySet.queryHash())).append(",\n");
        json.append("  \"generatorVersion\": ").append(string(ScalingDistractorGenerator.VERSION)).append(",\n");
        json.append("  \"seed\": ").append(seed).append(",\n");
        json.append("  \"threshold\": ").append(number(HtmlKnowledgeRecall.THRESHOLD)).append(",\n");
        json.append("  \"k\": [1, 3, 5],\n");
        json.append("  \"pools\": { \"production\": \"k\", \"expanded\": ").append(SignalHitCapture.EXPANDED_POOL)
                .append(" },\n");
        json.append("  \"codec\": ").append(string(TokenPackingSignalCodec.describe())).append(",\n");
        json.append("  \"rules\": [")
                .append(Arrays.stream(Rule.values()).map(rule -> "{ \"label\": " + string(rule.label())
                        + ", \"description\": " + string(rule.description()) + " }")
                        .collect(Collectors.joining(", ")))
                .append("],\n");
        json.append("  \"alternatives\": ").append(string(RESEARCH_ONLY)).append(",\n");
        json.append("  \"cohort\": ").append(strings(cohort.stream().map(JudgedQuery::queryId).toList()))
                .append(",\n");
        json.append("  \"studies\": { \"baseGrowth\": ").append(strings(labels(RecallDiagnosisLadder.BASE_GROWTH)))
                .append(", \"distractorGrowth\": ").append(strings(labels(RecallDiagnosisLadder.DISTRACTOR_GROWTH)))
                .append(" },\n");
        json.append("  \"points\": [\n");
        for (var p = 0; p < points.size(); p++) {
            json.append(pointJson(points.get(p))).append(p < points.size() - 1 ? ",\n" : "\n");
        }
        return json.append("  ]\n}").toString();
    }

    private static List<String> labels(List<Rung> rungs) {
        return rungs.stream().map(Rung::label).toList();
    }

    private String pointJson(PointDiagnosis diagnosis) {
        var point = diagnosis.point().point();
        var scores = diagnosis.scores();
        var json = new StringBuilder("    {\n");
        json.append("      \"point\": ").append(string(diagnosis.point().rung().label()))
                .append(", \"size\": ").append(point.size())
                .append(", \"corpusVersion\": ").append(string(point.corpus().version()))
                .append(", \"corpusHash\": ").append(string(point.corpus().corpusHash()))
                .append(", \"baseChunks\": ").append(point.baseChunks())
                .append(", \"generatedChunks\": ").append(point.generatedChunks()).append(",\n");
        json.append("      \"systems\": [\n").append(systemsJson(scores.systems(), "        ")).append("\n      ],\n");
        json.append("      \"groups\": {\n").append(scores.groups().entrySet().stream()
                .map(entry -> "        " + string(entry.getKey()) + ": [\n" + systemsJson(entry.getValue(),
                        "          ") + "\n        ]").collect(Collectors.joining(",\n"))).append("\n      },\n");
        json.append("      \"candidateCeilings\": [").append(scores.ceilings().stream()
                .map(ceiling -> "{ \"k\": " + ceiling.k() + ", \"pool\": " + ceiling.pool() + ", \"unionRecall\": "
                        + number(ceiling.unionRecall().value()) + ", \"nDefined\": " + ceiling.unionRecall().nDefined()
                        + " }").collect(Collectors.joining(", "))).append("],\n");
        json.append("      \"reasons\": {").append(scores.reasons().entrySet().stream()
                .map(entry -> " " + string(Integer.toString(entry.getKey())) + ": {" + Arrays
                        .stream(Reason.values()).map(reason -> " " + string(reason.name()) + ": "
                                + entry.getValue().getOrDefault(reason, 0)).collect(Collectors.joining(",")) + " }")
                .collect(Collectors.joining(","))).append(" },\n");
        json.append("      \"fates\": [\n").append(scores.fates().stream().map(fate -> "        " + fateJson(fate))
                .collect(Collectors.joining(",\n"))).append("\n      ],\n");
        json.append("      \"displacers\": [\n").append(scores.displacers().stream()
                .map(displacer -> "        " + displacerJson(displacer)).collect(Collectors.joining(",\n")))
                .append("\n      ],\n");
        json.append("      \"tokens\": [\n").append(scores.tokens().stream().map(row -> "        " + tokenJson(row))
                .collect(Collectors.joining(",\n"))).append("\n      ],\n");
        var c = scores.comparability();
        json.append("      \"comparability\": { \"pairs\": ").append(c.pairs()).append(", \"spearmanDfTop1\": ")
                .append(number(c.spearmanDfTop1())).append(", \"medianDf\": ").append(number(c.medianDf()))
                .append(", \"slots\": ").append(c.slots()).append(", \"nonRelevantSlots\": ")
                .append(c.nonRelevantSlots()).append(", \"distractorSlots\": ").append(c.distractorSlots())
                .append(", \"aboveMedianDfSlots\": ").append(c.aboveMedianDfSlots()).append(" },\n");
        json.append("      \"regressions\": [\n").append(scores.regressions().stream()
                .map(regression -> "        " + regressionJson(regression)).collect(Collectors.joining(",\n")))
                .append("\n      ],\n");
        var cost = scores.cost();
        json.append("      \"cost\": { \"b1StoreCallsPerQuery\": 1, \"b2StoreCallsPerQuery\": ")
                .append(number(cost.storeCallsPerQuery())).append(", \"hitsPerQuery\": {")
                .append(cost.hitsPerQuery().entrySet().stream().map(entry -> " " + string(entry.getKey().toString())
                        + ": " + number(entry.getValue())).collect(Collectors.joining(",")))
                .append(" }, \"distinctPerQuery\": {")
                .append(cost.distinctPerQuery().entrySet().stream().map(entry -> " "
                        + string(entry.getKey().toString()) + ": " + number(entry.getValue()))
                        .collect(Collectors.joining(",")))
                .append(" }, \"latency\": null },\n");
        json.append("      \"failedQueries\": ").append(strings(scores.failedQueries())).append(",\n");
        json.append("      \"negativeQueries\": ").append(strings(scores.negativeQueries())).append(",\n");
        json.append("      \"captures\": [\n").append(diagnosis.captures().stream()
                .map(capture -> "        " + captureJson(capture)).collect(Collectors.joining(",\n")))
                .append("\n      ]\n");
        return json.append("    }").toString();
    }

    private static String systemsJson(List<SystemScore> systems, String indent) {
        return systems.stream().map(score -> indent + "{ \"system\": " + string(score.system()) + ", \"pool\": "
                + string(score.pool()) + ", \"k\": " + score.k() + ", " + score.means().entrySet().stream()
                .map(entry -> string(entry.getKey()) + ": " + number(entry.getValue().value()))
                .collect(Collectors.joining(", ")) + ", \"hits\": " + score.hits() + ", \"answerable\": "
                + score.answerable() + ", \"falseEvidence\": " + score.falseEvidence() + ", \"unanswerable\": "
                + score.unanswerable() + ", \"failed\": " + score.failed() + " }")
                .collect(Collectors.joining(",\n"));
    }

    private static String fateJson(ChunkFate fate) {
        return "{ \"queryId\": " + string(fate.queryId()) + ", \"k\": " + fate.k() + ", \"chunkId\": "
                + string(fate.chunkId()) + ", \"required\": " + fate.required() + ", \"reason\": "
                + string(fate.reason().name()) + ", \"inB1\": " + fate.inB1() + ", \"signalsAtPoolK\": "
                + fate.signalsAtPoolK() + ", \"bestRankExpanded\": " + integer(fate.bestRankExpanded())
                + ", \"bestToken\": " + fate.bestToken().map(HtmlKnowledgeRecallReport::string).orElse("null")
                + ", \"bestScore\": " + number(fate.bestScore()) + ", \"cutoffScore\": " + number(fate.cutoffScore())
                + " }";
    }

    private static String displacerJson(Displacer displacer) {
        return "{ \"queryId\": " + string(displacer.queryId()) + ", \"rank\": " + displacer.rank() + ", \"chunkId\": "
                + string(displacer.chunkId()) + ", \"distractor\": " + displacer.distractor() + ", \"token\": "
                + string(displacer.token()) + ", \"tokenDf\": " + displacer.tokenDf() + ", \"score\": "
                + number(displacer.score()) + " }";
    }

    private static String tokenJson(TokenRow row) {
        return "{ \"queryId\": " + string(row.queryId()) + ", \"signal\": " + row.signalIndex() + ", \"token\": "
                + string(row.token()) + ", \"df\": " + row.df() + ", \"hitsAtPool5\": " + row.hits() + ", \"top1\": "
                + number(row.top1()) + ", \"relevantHits\": " + row.relevantHits() + " }";
    }

    private static String regressionJson(Regression regression) {
        return "{ \"queryId\": " + string(regression.queryId()) + ", \"category\": " + string(regression.category())
                + ", \"tokens\": " + strings(regression.tokens()) + ", \"b1Recall5\": " + number(regression.b1Recall())
                + ", \"b2Recall5\": " + number(regression.b2Recall()) + " }";
    }

    private static String captureJson(QueryCapture capture) {
        var json = new StringBuilder("{ \"queryId\": ").append(string(capture.queryId())).append(", \"tokens\": ")
                .append(strings(capture.tokens())).append(", \"failure\": ")
                .append(capture.failure().map(HtmlKnowledgeRecallReport::string).orElse("null"))
                .append(", \"hits\": {");
        json.append(capture.lists().entrySet().stream().map(entry -> " " + string(entry.getKey().toString()) + ": ["
                + entry.getValue().stream().map(hits -> "[" + hits.stream()
                        .map(HtmlKnowledgeRecallDiagnosisReport::hitJson).collect(Collectors.joining(", ")) + "]")
                        .collect(Collectors.joining(", ")) + "]").collect(Collectors.joining(",")));
        return json.append(" } }").toString();
    }

    private static String hitJson(Hit hit) {
        return "[" + (hit.rank() + 1) + ", " + string(hit.chunkId()) + ", " + string(hit.reference()) + ", "
                + number(hit.score()) + "]";
    }

    private static String integer(OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "null";
    }

    /** Renders the concise Markdown summary. */
    String toMarkdown() {
        var md = new StringBuilder("## B2 Recall Diagnosis (Issue #135)\n\n");
        md.append("Cohort: the ").append(cohort.size()).append(" queries of the #111 curve population, scored at every"
                + " point. Merge alternatives are ").append(RESEARCH_ONLY).append(". Pool `k` is the adapter's own"
                + " per-Signal `topK(k)`; pool ").append(SignalHitCapture.EXPANDED_POOL)
                .append(" is a separately labelled candidate-recall change.\n\n");

        md.append("### Points\n\n| Point | N | Base chunks | Distractors | Corpus hash | Failed queries |\n")
                .append("| --- | ---: | ---: | ---: | --- | --- |\n");
        for (var diagnosis : points) {
            var point = diagnosis.point().point();
            md.append("| ").append(diagnosis.point().rung().label()).append(" | ").append(point.size()).append(" | ")
                    .append(point.baseChunks()).append(" | ").append(point.generatedChunks()).append(" | `")
                    .append(point.corpus().corpusHash(), 0, 12).append("` | ")
                    .append(diagnosis.scores().failedQueries().isEmpty() ? "none"
                            : String.join(", ", diagnosis.scores().failedQueries())).append(" |\n");
        }

        md.append("\n### B1 vs B2 Recall@k (answerable cohort queries)\n\n| Point | B1 R@1 | B2 R@1 | B1 R@3 | B2 R@3"
                + " | B1 R@5 | B2 R@5 | B1 MRR@5 | B2 MRR@5 | B1 HIT@5 | B2 HIT@5 |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (var diagnosis : points) {
            var systems = diagnosis.scores().systems();
            md.append("| ").append(diagnosis.point().rung().label());
            for (var k : RecallMetrics.KS) {
                md.append(" | ").append(metric(systems, "B1", "full-text", k, "Recall"))
                        .append(" | ").append(metric(systems, "B2", "k", k, "Recall"));
            }
            md.append(" | ").append(metric(systems, "B1", "full-text", 5, "MRR")).append(" | ")
                    .append(metric(systems, "B2", "k", 5, "MRR")).append(" | ")
                    .append(find(systems, "B1", "full-text", 5).map(score -> score.hits() + "/" + score.answerable())
                            .orElse("n/a")).append(" | ")
                    .append(find(systems, "B2", "k", 5).map(score -> score.hits() + "/" + score.answerable())
                            .orElse("n/a")).append(" |\n");
        }

        md.append("\n### Where relevant chunks are lost (k = 5)\n\nCeiling = mean fraction of relevant chunks in the"
                + " union of the query's Signal lists, the most any merge of those candidates can recall.\n\n")
                .append("| Point | Ceiling pool 5 | Ceiling pool ").append(SignalHitCapture.EXPANDED_POOL)
                .append(" | B2 R@5 | ").append(Arrays.stream(Reason.values()).map(Reason::name)
                        .collect(Collectors.joining(" | "))).append(" |\n| --- | ---: | ---: | ---: |")
                .append(" ---: |".repeat(Reason.values().length)).append("\n");
        for (var diagnosis : points) {
            var scores = diagnosis.scores();
            md.append("| ").append(diagnosis.point().rung().label()).append(" | ")
                    .append(ceiling(scores.ceilings(), 5, 5)).append(" | ")
                    .append(ceiling(scores.ceilings(), 5, SignalHitCapture.EXPANDED_POOL)).append(" | ")
                    .append(metric(scores.systems(), "B2", "k", 5, "Recall"));
            for (var reason : Reason.values()) {
                md.append(" | ").append(scores.reasons().get(5).getOrDefault(reason, 0));
            }
            md.append(" |\n");
        }

        md.append("\n### Merge counterfactuals over identical candidates (Recall@5 / MRR@5 / HIT@5 / false evidence)"
                + "\n\n").append(RESEARCH_ONLY).append(".\n\n| Rule | Pool |");
        points.forEach(diagnosis -> md.append(' ').append(diagnosis.point().rung().label()).append(" |"));
        md.append("\n| --- | --- |").append(" --- |".repeat(points.size())).append('\n');
        var rows = new ArrayList<String[]>();
        rows.add(new String[] {"B1 (full text)", "full-text"});
        for (var pool : List.of("k", Integer.toString(SignalHitCapture.EXPANDED_POOL))) {
            for (var rule : Rule.values()) {
                rows.add(new String[] {rule.label(), pool});
            }
        }
        for (var row : rows) {
            var system = row[0].startsWith("B1") ? "B1" : row[0];
            md.append("| ").append(row[0]).append(" | ").append(row[1]).append(" |");
            for (var diagnosis : points) {
                md.append(' ').append(find(diagnosis.scores().systems(), system, row[1], 5)
                        .map(HtmlKnowledgeRecallDiagnosisReport::cell).orElse("n/a")).append(" |");
            }
            md.append('\n');
        }

        md.append("\n### Controlled studies (Recall@5)\n\n| Study | From | To | B1 Δ | B2 Δ |\n| --- | --- | --- |"
                + " ---: | ---: |\n");
        study(md, "base growth (no distractors)", RecallDiagnosisLadder.BASE_GROWTH);
        study(md, "distractor growth (full base)", RecallDiagnosisLadder.DISTRACTOR_GROWTH);

        md.append("\n### Breakdown at k = 5 (B1 / B2 Recall@5, false evidence)\n\n| Group |");
        points.forEach(diagnosis -> md.append(' ').append(diagnosis.point().rung().label()).append(" |"));
        md.append("\n| --- |").append(" --- |".repeat(points.size())).append('\n');
        var groupNames = points.stream().flatMap(diagnosis -> diagnosis.scores().groups().keySet().stream())
                .distinct().toList();
        for (var group : groupNames) {
            md.append("| ").append(group).append(" |");
            for (var diagnosis : points) {
                var scores = diagnosis.scores().groups().get(group);
                md.append(' ').append(scores == null ? "n/a" : breakdown(scores)).append(" |");
            }
            md.append('\n');
        }

        md.append("\n### Cross-token score comparability and cost (k = 5)\n\n| Point | (query, token) pairs |"
                + " Spearman ρ(df, top-1 score) | Median df | Non-relevant B2 slots | of which distractors | won via"
                + " df > median | B2 Store calls/query | Hits/query pool 5 | Distinct pool 5 | Hits/query pool "
                + SignalHitCapture.EXPANDED_POOL + " |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
                + " ---: | ---: | ---: |\n");
        for (var diagnosis : points) {
            var c = diagnosis.scores().comparability();
            var cost = diagnosis.scores().cost();
            md.append("| ").append(diagnosis.point().rung().label()).append(" | ").append(c.pairs()).append(" | ")
                    .append(format(c.spearmanDfTop1())).append(" | ").append(fixed(c.medianDf())).append(" | ")
                    .append(c.nonRelevantSlots()).append('/').append(c.slots()).append(" | ")
                    .append(c.distractorSlots()).append(" | ").append(c.aboveMedianDfSlots()).append(" | ")
                    .append(fixed(cost.storeCallsPerQuery())).append(" | ").append(fixed(cost.hitsPerQuery().get(5)))
                    .append(" | ").append(fixed(cost.distinctPerQuery().get(5))).append(" | ")
                    .append(fixed(cost.hitsPerQuery().get(SignalHitCapture.EXPANDED_POOL))).append(" |\n");
        }
        md.append("\nB1 makes one Store call per query. Latency and allocation are not measured here; see the #111"
                + " scaling rows.\n");

        md.append("\n### Worst regressions (B1 Recall@5 − B2 Recall@5)\n");
        for (var diagnosis : points) {
            var regressions = diagnosis.scores().regressions();
            md.append("\n**").append(diagnosis.point().rung().label()).append("**")
                    .append(regressions.isEmpty() ? ": none\n" : "\n\n");
            for (var regression : regressions) {
                md.append("- `").append(regression.queryId()).append("` (").append(regression.category())
                        .append(", ").append(regression.tokens().size()).append(" tokens) B1 ")
                        .append(fixed(regression.b1Recall())).append(" → B2 ").append(fixed(regression.b2Recall()))
                        .append(": ").append(regression.fates().stream().map(fate -> "`" + fate.chunkId() + "` "
                                + fate.reason() + fate.bestToken().map(token -> " (best token `" + token + "` rank "
                                + fate.bestRankExpanded().getAsInt() + ")").orElse(""))
                                .collect(Collectors.joining("; ")))
                        .append(regression.displacers().isEmpty() ? "" : "; displaced by " + regression.displacers()
                                .stream().map(displacer -> "`" + displacer.chunkId() + "` via `" + displacer.token()
                                        + "` (df " + displacer.tokenDf() + ")").collect(Collectors.joining(", ")))
                        .append('\n');
            }
        }
        return md.toString();
    }

    private void study(StringBuilder md, String name, List<Rung> rungs) {
        for (var i = 1; i < rungs.size(); i++) {
            var from = point(rungs.get(i - 1));
            var to = point(rungs.get(i));
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }
            md.append("| ").append(name).append(" | ").append(rungs.get(i - 1).label()).append(" | ")
                    .append(rungs.get(i).label()).append(" | ").append(delta(from.get(), to.get(), "B1", "full-text"))
                    .append(" | ").append(delta(from.get(), to.get(), "B2", "k")).append(" |\n");
        }
    }

    private Optional<PointDiagnosis> point(Rung rung) {
        return points.stream().filter(diagnosis -> diagnosis.point().rung() == rung).findFirst();
    }

    private static String delta(PointDiagnosis from, PointDiagnosis to, String system, String pool) {
        var a = find(from.scores().systems(), system, pool, 5).map(score -> score.means().get("Recall@5").value());
        var b = find(to.scores().systems(), system, pool, 5).map(score -> score.means().get("Recall@5").value());
        if (a.isEmpty() || b.isEmpty() || a.get().isEmpty() || b.get().isEmpty()) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%+.3f", b.get().getAsDouble() - a.get().getAsDouble());
    }

    private static String breakdown(List<SystemScore> scores) {
        var b1 = find(scores, "B1", "full-text", 5);
        var b2 = find(scores, "B2", "k", 5);
        if (b1.isEmpty() || b2.isEmpty()) {
            return "n/a";
        }
        var answerable = b1.get().answerable() > 0;
        return answerable ? format(b1.get().means().get("Recall@5").value()) + " / "
                + format(b2.get().means().get("Recall@5").value())
                : "FE " + b1.get().falseEvidence() + "/" + b1.get().unanswerable() + " / " + b2.get().falseEvidence()
                        + "/" + b2.get().unanswerable();
    }

    private static String cell(SystemScore score) {
        return format(score.means().get("Recall@5").value()) + " / " + format(score.means().get("MRR@5").value())
                + " / " + score.hits() + " / " + score.falseEvidence();
    }

    private static Optional<SystemScore> find(List<SystemScore> systems, String system, String pool, int k) {
        return systems.stream().filter(score -> score.system().equals(system) && score.pool().equals(pool)
                && score.k() == k).findFirst();
    }

    private static String metric(List<SystemScore> systems, String system, String pool, int k, String name) {
        return find(systems, system, pool, k).map(score -> format(score.means().get(name + "@" + k).value()))
                .orElse("n/a");
    }

    private static String ceiling(List<CandidateCeiling> ceilings, int k, int pool) {
        return ceilings.stream().filter(ceiling -> ceiling.k() == k && ceiling.pool() == pool).findFirst()
                .map(ceiling -> format(ceiling.unionRecall().value())).orElse("n/a");
    }

    private static String fixed(double value) {
        return format(OptionalDouble.of(value));
    }
}
