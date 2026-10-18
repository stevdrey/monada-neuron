package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import monada.neuron.evaluation.knowledge.RecallMetrics.Aggregate;
import monada.neuron.evaluation.knowledge.RecallMetrics.LayerAggregate;
import monada.neuron.evaluation.knowledge.RecallMetrics.Outcome;
import monada.neuron.evaluation.knowledge.RecallMetrics.QueryScore;
import monada.neuron.evaluation.knowledge.RecallMetrics.Ranked;
import monada.neuron.evaluation.knowledge.RecallMetrics.Scores;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Renders the scored judged recall as machine-readable JSON and a concise Markdown summary (protocol section "Report
 * shape"): aggregates per layer, {@code k} and category, outcome counts, the set-level abstention metrics, the B1
 * versus B2c codec effect, and one row per query with expected and returned chunk ids, ranks, scores,
 * {@code tieAtCutoff}, outcome and miss diagnostics. Undefined values are JSON {@code null} and {@code n/a} in
 * Markdown.
 */
final class HtmlKnowledgeRecallReport {

    private final HtmlKnowledgeQuerySet querySet;
    private final Scores scores;

    HtmlKnowledgeRecallReport(HtmlKnowledgeQuerySet querySet, Scores scores) {
        this.querySet = Objects.requireNonNull(querySet, "querySet must not be null");
        this.scores = Objects.requireNonNull(scores, "scores must not be null");
    }

    /** Renders every aggregate and every query row as JSON. */
    String toJson() {
        var json = new StringBuilder("{\n");
        json.append("  \"queryVersion\": ").append(string(querySet.version())).append(",\n");
        json.append("  \"queryHash\": ").append(string(querySet.queryHash())).append(",\n");
        json.append("  \"k\": [1, 3, 5],\n");
        json.append("  \"threshold\": ").append(HtmlKnowledgeRecall.THRESHOLD).append(",\n");
        json.append("  \"layers\": [\n");
        var layers = List.of(Layer.values());
        for (var l = 0; l < layers.size(); l++) {
            var layer = layers.get(l);
            var abstention = scores.abstentions().stream().filter(value -> value.layer() == layer).findFirst()
                    .orElseThrow();
            json.append("    { \"layer\": ").append(string(layer.label()))
                    .append(", \"AbstentionPrecision\": ").append(number(abstention.abstentionPrecision()))
                    .append(", \"AbstentionRecall\": ").append(number(abstention.abstentionRecall()))
                    .append(", \"unscoredQueries\": ").append(abstention.unscored())
                    .append(",\n      \"byK\": [\n");
            var aggregates = aggregates(layer);
            for (var a = 0; a < aggregates.size(); a++) {
                var aggregate = aggregates.get(a);
                json.append("        { \"k\": ").append(aggregate.k())
                        .append(", \"nA\": ").append(aggregate.scoredA())
                        .append(", \"nU\": ").append(aggregate.scoredU())
                        .append(", \"NoAnswerFalsePositiveRate@").append(aggregate.k()).append("\": ")
                        .append(number(aggregate.noAnswerFalsePositiveRate()))
                        .append(",\n          \"answerable\": {");
                var first = true;
                for (var metric : aggregate.answerable().entrySet()) {
                    json.append(first ? " " : ", ").append(string(metric.getKey())).append(": ")
                            .append(aggregateJson(metric.getValue()));
                    first = false;
                }
                json.append(", \"FullEvidence@").append(aggregate.k()).append("\": ")
                        .append(aggregateJson(aggregate.fullEvidence())).append(" },\n");
                json.append("          \"outcomesByCategory\": {");
                first = true;
                for (var category : Category.values()) {
                    var counts = aggregate.outcomes().getOrDefault(category, Map.of());
                    json.append(first ? " " : ", ").append(string(category.label())).append(": {");
                    var firstOutcome = true;
                    for (var outcome : Outcome.values()) {
                        if (counts.containsKey(outcome)) {
                            json.append(firstOutcome ? " " : ", ").append(string(outcome.name())).append(": ")
                                    .append(counts.get(outcome));
                            firstOutcome = false;
                        }
                    }
                    json.append(" }");
                    first = false;
                }
                json.append(" } }").append(a < aggregates.size() - 1 ? ",\n" : "\n");
            }
            json.append("      ] }").append(l < layers.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"queries\": [\n");
        var queries = querySet.queries();
        for (var q = 0; q < queries.size(); q++) {
            var query = queries.get(q);
            json.append("    { \"queryId\": ").append(string(query.queryId()))
                    .append(", \"category\": ").append(string(query.category().label()))
                    .append(", \"answerability\": ").append(string(query.answerability().name()))
                    .append(", \"text\": ").append(string(query.text()))
                    .append(", \"required\": ").append(strings(new TreeSet<>(query.required())))
                    .append(", \"supporting\": ").append(strings(new TreeSet<>(query.supporting())))
                    .append(",\n      \"layers\": {\n");
            for (var l = 0; l < layers.size(); l++) {
                json.append("        ").append(string(layers.get(l).label())).append(": ")
                        .append(queryLayerJson(score(query, layers.get(l))))
                        .append(l < layers.size() - 1 ? ",\n" : "\n");
            }
            json.append("      } }").append(q < queries.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"codecEffect\": [\n");
        for (var q = 0; q < queries.size(); q++) {
            var query = queries.get(q);
            var b1 = ids(score(query, Layer.B1));
            var b2c = ids(score(query, Layer.B2C));
            json.append("    { \"queryId\": ").append(string(query.queryId()))
                    .append(", \"B1\": ").append(strings(b1)).append(", \"B2c\": ").append(strings(b2c))
                    .append(", \"equalChunkOrder\": ").append(b1.equals(b2c)).append(" }")
                    .append(q < queries.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"followUpCandidates\": ").append(strings(followUps())).append("\n}\n");
        return json.toString();
    }

    /** Renders the aggregates, the abstention metrics, the codec effect and a per-query table at {@code k = 5}. */
    String toMarkdown() {
        var md = new StringBuilder("## Judged Recall (`").append(querySet.version()).append("`)\n\n");
        md.append("Query set ").append(querySet.queries().size()).append(" queries, queryHash `")
                .append(querySet.queryHash()).append("`, threshold t = ").append(HtmlKnowledgeRecall.THRESHOLD)
                .append(". Means over answerable queries `A`; `n/a` is undefined. Layers are compared side by side,"
                        + " never combined.\n\n");
        md.append("| Layer | k | nA | Recall | Precision | HitPrecision | MRR | Coverage | FullEvidence | nU |"
                + " NoAnswerFPR |\n| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (var aggregate : scores.aggregates()) {
            var k = aggregate.k();
            var metrics = aggregate.answerable();
            md.append("| ").append(aggregate.layer().label()).append(" | ").append(k).append(" | ")
                    .append(aggregate.scoredA()).append(" | ")
                    .append(cell(metrics.get("Recall@" + k))).append(" | ")
                    .append(cell(metrics.get("Precision@" + k))).append(" | ")
                    .append(cell(metrics.get("HitPrecision@" + k))).append(" | ")
                    .append(cell(metrics.get("MRR@" + k))).append(" | ")
                    .append(cell(metrics.get("Coverage@" + k))).append(" | ")
                    .append(cell(aggregate.fullEvidence())).append(" | ")
                    .append(aggregate.scoredU()).append(" | ")
                    .append(format(aggregate.noAnswerFalsePositiveRate())).append(" |\n");
        }
        md.append("\n| Layer | AbstentionPrecision | AbstentionRecall | Unscored (failed) |\n"
                + "| :--- | ---: | ---: | ---: |\n");
        for (var abstention : scores.abstentions()) {
            md.append("| ").append(abstention.layer().label()).append(" | ")
                    .append(format(abstention.abstentionPrecision())).append(" | ")
                    .append(format(abstention.abstentionRecall())).append(" | ").append(abstention.unscored())
                    .append(" |\n");
        }
        md.append("\n### Outcomes at k = 5 by category\n\n| Category | ");
        md.append(List.of(Layer.values()).stream().map(Layer::label).collect(Collectors.joining(" | ")));
        md.append(" |\n| :--- |").append(" :--- |".repeat(Layer.values().length)).append('\n');
        for (var category : Category.values()) {
            md.append("| ").append(category.label()).append(" |");
            for (var layer : Layer.values()) {
                var counts = aggregates(layer).getLast().outcomes().getOrDefault(category, Map.of());
                md.append(' ').append(counts.isEmpty() ? "-" : List.of(Outcome.values()).stream()
                        .filter(counts::containsKey).map(outcome -> outcome.name() + " " + counts.get(outcome))
                        .collect(Collectors.joining(", "))).append(" |");
            }
            md.append('\n');
        }
        var differing = querySet.queries().stream()
                .filter(query -> !ids(score(query, Layer.B1)).equals(ids(score(query, Layer.B2C)))).count();
        md.append("\n### Codec effect\n\nB1 (full query text) and B2c (one Store query per token, merged) differ in"
                + " their top-5 chunk order for ").append(differing).append(" of ").append(querySet.queries().size())
                .append(" queries. This is a codec effect, reported without claiming parity; B2 equals B2c by the"
                        + " `neuron.b2EqualsB2c` check.\n\n");
        md.append("### Queries at k = 5\n\n| Query | Category | Relevant | B0 | B1 | B2 |\n"
                + "| :--- | :--- | :--- | :--- | :--- | :--- |\n");
        for (var query : querySet.queries()) {
            md.append("| `").append(query.queryId()).append("` | ").append(query.category().label()).append(" | ")
                    .append(query.relevant().isEmpty() ? "-" : String.join(", ", new TreeSet<>(query.relevant())))
                    .append(" | ").append(queryCell(score(query, Layer.B0))).append(" | ")
                    .append(queryCell(score(query, Layer.B1))).append(" | ")
                    .append(queryCell(score(query, Layer.B2))).append(" |\n");
        }
        var followUps = followUps();
        md.append("\n### Follow-up candidates\n\nQueries not `HIT` (answerable) or not `CORRECT_ABSTENTION`"
                + " (no relevant chunk) at k = 5 in B1 or B2. They are listed, never relabeled or tuned away.\n\n");
        if (followUps.isEmpty()) {
            md.append("None.\n");
        }
        followUps.forEach(value -> md.append("- ").append(value).append('\n'));
        return md.toString();
    }

    /** Returns {@code layer outcome queryId} for every B1/B2 query whose k = 5 outcome is not the desired one. */
    List<String> followUps() {
        return scores.queries().stream()
                .filter(score -> score.layer() == Layer.B1 || score.layer() == Layer.B2)
                .filter(score -> !score.completed() || switch (score.byK().get(5).outcome()) {
                    case HIT, CORRECT_ABSTENTION -> false;
                    default -> true;
                })
                .map(score -> score.layer().label() + " " + (score.completed() ? score.byK().get(5).outcome()
                        : "FAILED") + " " + score.query().queryId() + diagnostics(score))
                .toList();
    }

    private String diagnostics(QueryScore score) {
        if (!score.completed() || score.query().relevant().isEmpty()) {
            return "";
        }
        var returned = ids(score);
        var missing = score.query().required().stream().filter(chunkId -> !returned.contains(chunkId)).sorted()
                .toList();
        return missing.isEmpty() ? "" : " (missing required " + String.join(", ", missing) + ")";
    }

    private String queryLayerJson(QueryScore score) {
        if (!score.completed()) {
            return "{ \"completed\": false }";
        }
        var json = new StringBuilder("{ \"completed\": true, \"returned\": [");
        var returned = score.returned().orElseThrow();
        for (var i = 0; i < returned.size(); i++) {
            var ranked = returned.get(i);
            json.append(i == 0 ? "" : ", ").append("{ \"rank\": ").append(i + 1).append(", \"chunkId\": ")
                    .append(string(ranked.chunkId())).append(", \"score\": ").append(ranked.score()).append(" }");
        }
        json.append("], \"byK\": {");
        var first = true;
        for (var k : RecallMetrics.KS) {
            var kScore = score.byK().get(k);
            json.append(first ? " " : ", ").append(string(Integer.toString(k))).append(": { \"outcome\": ")
                    .append(string(kScore.outcome().name())).append(", \"tieAtCutoff\": ")
                    .append(kScore.tieAtCutoff());
            for (var metric : kScore.metrics().entrySet()) {
                json.append(", ").append(string(metric.getKey())).append(": ").append(number(metric.getValue()));
            }
            json.append(" }");
            first = false;
        }
        var diagnostics = diagnostics(score);
        json.append(" }, \"missDiagnostics\": ")
                .append(diagnostics.isEmpty() ? "null" : string(diagnostics.strip())).append(" }");
        return json.toString();
    }

    private String queryCell(QueryScore score) {
        if (!score.completed()) {
            return "FAILED";
        }
        var kScore = score.byK().get(5);
        var returned = score.returned().orElseThrow().stream().map(Ranked::chunkId).toList();
        return kScore.outcome().name() + (kScore.tieAtCutoff() ? " (tie at cutoff)" : "")
                + (returned.isEmpty() ? "" : ": " + String.join(", ", returned));
    }

    private QueryScore score(JudgedQuery query, Layer layer) {
        return scores.queries().stream().filter(score -> score.layer() == layer && score.query().equals(query))
                .findFirst().orElseThrow();
    }

    private List<LayerAggregate> aggregates(Layer layer) {
        return scores.aggregates().stream().filter(aggregate -> aggregate.layer() == layer).toList();
    }

    private static List<String> ids(QueryScore score) {
        return score.returned().map(returned -> returned.stream().map(Ranked::chunkId).toList()).orElse(List.of());
    }

    private static String aggregateJson(Aggregate aggregate) {
        return "{ \"value\": " + number(aggregate.value()) + ", \"nDefined\": " + aggregate.nDefined()
                + ", \"nUndefined\": " + aggregate.nUndefined() + " }";
    }

    private static String cell(Aggregate aggregate) {
        return format(aggregate.value());
    }

    private static String format(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.3f", value.getAsDouble()) : "n/a";
    }

    private static String number(OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.getAsDouble()) : "null";
    }

    private static String strings(Collection<String> values) {
        return values.stream().map(HtmlKnowledgeRecallReport::string).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String string(String value) {
        var json = new StringBuilder(value.length() + 2).append('"');
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '\\' -> json.append("\\\\");
                case '"' -> json.append("\\\"");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
