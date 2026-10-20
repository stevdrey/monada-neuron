package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.EvidenceTrace.Arm;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Contrast;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Row;
import monada.neuron.evaluation.knowledge.EvidenceTraceMetrics.ArmAggregate;
import monada.neuron.evaluation.knowledge.EvidenceTraceMetrics.Count;
import monada.neuron.evaluation.knowledge.EvidenceTraceMetrics.QueryScore;
import monada.neuron.evaluation.knowledge.EvidenceTraceMetrics.Scores;
import monada.neuron.evaluation.knowledge.RecallMetrics.Aggregate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.format;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.number;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.strings;

/**
 * Renders the scored evidence-trace experiment (Issue #108) as the {@code trace} payload of the JSON report and as
 * Markdown. Undefined values are JSON {@code null}. The limits are part of the payload, so they travel with every
 * number.
 */
final class HtmlKnowledgeEvidenceTraceReport {

    /** What this experiment does not show; rendered in both formats. */
    static final List<String> LIMITS = List.of(
            "L3 shows that recalled evidence is carried through the cycle; it is not a measure of reasoning quality.",
            "A correct retrieval, and a SUPPORTED decision, is not a language-generation result: no answer text is"
                    + " produced, and no claim is checked against the evidence (that is L4, #109).",
            "Stored text is external memory: nothing here trains model weights, and no learning is claimed.",
            "SUPPORTED means only that the cycle carried resolvable recalled evidence. With t = 0.0 the Store returns"
                    + " results for every non-blank query, so most queries without any relevant chunk are SUPPORTED"
                    + " too; see unsupportedClaims.",
            "\"Used\" means carried (passed through or attached by the documented evaluation-only consumer), not that"
                    + " it influenced anything; influence is what the memory-empty contrast probes.",
            "The reasoning stage is an evaluation-only consumer with one fixed proposition, not a production semantic"
                    + " stage; the evaluation stage and policy are the production ones.",
            "The query set is in-sample and has no held-out set; nothing was tuned on it.");

    private final Scores scores;

    HtmlKnowledgeEvidenceTraceReport(Scores scores) {
        this.scores = Objects.requireNonNull(scores, "scores must not be null");
    }

    String toJson() {
        var json = new StringBuilder("{ \"limits\": ").append(strings(LIMITS)).append(", \"arms\": { ");
        json.append(scores.aggregates().entrySet().stream()
                .map(entry -> string(entry.getKey().name()) + ": " + armJson(entry.getValue()))
                .collect(Collectors.joining(", ")));
        json.append(" }, \"memoryEmptyContrast\": { \"answerable\": ").append(countJson(scores.contrastAnswerable()))
                .append(", \"unanswerable\": ").append(countJson(scores.contrastUnanswerable())).append(" }");
        json.append(", \"removedDocument\": ").append(string(HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT))
                .append(", \"removedTargets\": ").append(strings(scores.removedTargets()));
        json.append(", \"queries\": [ ");
        var memory = scores.byArm().get(Arm.MEMORY);
        for (var index = 0; index < memory.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            var query = memory.get(index).query();
            json.append("{ \"queryId\": ").append(string(query.queryId())).append(", \"category\": ")
                    .append(string(query.category().label())).append(", \"answerability\": ")
                    .append(string(query.category().answerability().name())).append(", \"relevant\": ")
                    .append(strings(EvidenceTraceMetrics.relevant(query))).append(", \"contrast\": ")
                    .append(contrastJson(memory.get(index).row().contrast().orElseThrow())).append(", \"arms\": { ");
            var finalIndex = index;
            json.append(scores.byArm().entrySet().stream()
                    .map(entry -> string(entry.getKey().name()) + ": " + queryJson(entry.getValue().get(finalIndex)))
                    .collect(Collectors.joining(", ")));
            json.append(" } }");
        }
        return json.append(" ] }").toString();
    }

    String toMarkdown() {
        var md = new StringBuilder("## Evidence Trace (L3, l3-evidence-v1)\n\n### Limits\n\n");
        LIMITS.forEach(limit -> md.append("- ").append(limit).append('\n'));
        md.append("\n### Arms\n\n| Arm | AdmittedFraction | UsedFraction | RelevantEvidenceUsed (A) |"
                + " memory-supported relevant (A) | SUPPORTED on U | SUPPORTED | UNSUPPORTED | RECALL_FAILED |"
                + " INCOMPLETE | FAILED_CLOSED |\n| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
                + " ---: |\n");
        scores.aggregates().forEach((arm, aggregate) -> md.append("| ").append(arm.name()).append(" | ")
                .append(cell(aggregate.admittedFraction())).append(" | ").append(cell(aggregate.usedFraction()))
                .append(" | ").append(cell(aggregate.relevantEvidenceUsed())).append(" | ")
                .append(countCell(aggregate.memorySupportedRelevant())).append(" | ")
                .append(countCell(aggregate.unsupportedClaims())).append(" | ")
                .append(aggregate.decisions().values().stream().map(String::valueOf)
                        .collect(Collectors.joining(" | "))).append(" |\n"));
        md.append("\nMemory-empty contrast (`differs`): answerable ").append(countCell(scores.contrastAnswerable()))
                .append(", unanswerable ").append(countCell(scores.contrastUnanswerable())).append(".\n");
        md.append("\nRemoved document `").append(HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT)
                .append("`; queries whose every relevant chunk was removed: ")
                .append(String.join(", ", scores.removedTargets())).append(".\n");
        md.append("\n### MEMORY arm by category\n\n| Category | RelevantEvidenceUsed | SUPPORTED | UNSUPPORTED |\n"
                + "| :--- | ---: | ---: | ---: |\n");
        scores.aggregates().get(Arm.MEMORY).byCategory().forEach((category, aggregate) -> md.append("| ")
                .append(category.label()).append(" | ").append(cell(aggregate.relevantEvidenceUsed())).append(" | ")
                .append(aggregate.decisions().get(EvidenceDecision.SUPPORTED)).append(" | ")
                .append(aggregate.decisions().get(EvidenceDecision.UNSUPPORTED)).append(" |\n"));
        md.append("\n### Per query\n\n| Query | Relevant | MEMORY admitted | MEMORY request = codec, recall = B2 |"
                + " MEMORY relevant used | MEMORY decision | SWAPPED relevant used | REMOVED relevant used |"
                + " contrast differs |\n| :--- | :--- | :--- | :---: | :---: | :--- | :---: | :---: | :---: |\n");
        var memory = scores.byArm().get(Arm.MEMORY);
        for (var index = 0; index < memory.size(); index++) {
            var score = memory.get(index);
            md.append("| ").append(score.query().queryId()).append(" | ")
                    .append(String.join(", ", EvidenceTraceMetrics.relevant(score.query()))).append(" | ")
                    .append(String.join(", ", score.row().admitted())).append(" | ")
                    .append(score.row().requestMatchesCodec()).append(", ")
                    .append(score.row().recalledEqualsB2().map(String::valueOf).orElse("n/a")).append(" | ")
                    .append(flag(score)).append(" | ").append(score.row().decision()).append(" | ")
                    .append(flag(scores.byArm().get(Arm.SWAPPED).get(index))).append(" | ")
                    .append(flag(scores.byArm().get(Arm.REMOVED).get(index))).append(" | ")
                    .append(score.row().contrast().orElseThrow().differs()).append(" |\n");
        }
        return md.toString();
    }

    private static String armJson(ArmAggregate aggregate) {
        return "{ \"AdmittedFraction\": " + aggregateJson(aggregate.admittedFraction()) + ", \"UsedFraction\": "
                + aggregateJson(aggregate.usedFraction()) + ", \"RelevantEvidenceUsed\": "
                + aggregateJson(aggregate.relevantEvidenceUsed()) + ", \"memorySupportedRelevant\": "
                + countJson(aggregate.memorySupportedRelevant()) + ", \"unsupportedClaims\": "
                + countJson(aggregate.unsupportedClaims()) + ", \"decisions\": " + decisionsJson(aggregate.decisions())
                + ", \"byCategory\": { " + aggregate.byCategory().entrySet().stream()
                        .map(entry -> string(entry.getKey().label()) + ": { \"decisions\": "
                                + decisionsJson(entry.getValue().decisions()) + ", \"RelevantEvidenceUsed\": "
                                + aggregateJson(entry.getValue().relevantEvidenceUsed()) + " }")
                        .collect(Collectors.joining(", ")) + " } }";
    }

    private static String queryJson(QueryScore score) {
        Row row = score.row();
        return "{ \"inputQueryId\": " + string(row.inputQueryId()) + ", \"inputSignals\": " + row.inputSignals()
                + ", \"requestMatchesCodec\": " + row.requestMatchesCodec() + ", \"recalledEqualsB2\": "
                + row.recalledEqualsB2().map(String::valueOf).orElse("null") + ", \"recordedStatus\": "
                + string(row.recordedStatus()) + ", \"recorded\": [" + row.recorded().stream()
                        .map(hit -> "{ \"chunkId\": " + string(hit.chunkId()) + ", \"score\": " + number(hit.score())
                                + " }").collect(Collectors.joining(", ")) + "], \"memoryStatus\": "
                + string(row.memoryStatus()) + ", \"admitted\": " + strings(row.admitted()) + ", \"usedBySignal\": "
                + strings(row.usedBySignal()) + ", \"attached\": " + strings(row.attached()) + ", \"selected\": "
                + strings(row.selected()) + ", \"traceAmbiguous\": " + row.traceAmbiguous() + ", \"stages\": "
                + strings(row.stages()) + ", \"termination\": " + string(row.termination())
                + ", \"signalBudgetExhausted\": " + row.signalBudgetExhausted() + ", \"outputSignals\": "
                + row.outputSignals() + ", \"decision\": " + string(row.decision().name()) + ", \"AdmittedFraction\": "
                + number(score.admittedFraction()) + ", \"UsedFraction\": " + number(score.usedFraction())
                + ", \"RelevantEvidenceUsed\": " + number(score.relevantEvidenceUsed())
                + ", \"memorySupportedRelevant\": " + score.memorySupportedRelevant() + " }";
    }

    private static String contrastJson(Contrast contrast) {
        return "{ \"differs\": " + contrast.differs() + ", \"differsInSignals\": " + contrast.differsInSignals()
                + ", \"differsInArtifacts\": " + contrast.differsInArtifacts() + ", \"firstDifferingStage\": "
                + string(contrast.firstDifferingStage()) + ", \"realOutputSignals\": " + contrast.realOutputSignals()
                + ", \"emptyOutputSignals\": " + contrast.emptyOutputSignals() + ", \"terminationDiffers\": "
                + contrast.terminationDiffers() + " }";
    }

    private static String decisionsJson(Map<EvidenceDecision, Integer> decisions) {
        return decisions.entrySet().stream().map(entry -> string(entry.getKey().name()) + ": " + entry.getValue())
                .collect(Collectors.joining(", ", "{ ", " }"));
    }

    private static String aggregateJson(Aggregate aggregate) {
        return "{ \"value\": " + number(aggregate.value()) + ", \"nDefined\": " + aggregate.nDefined()
                + ", \"nUndefined\": " + aggregate.nUndefined() + " }";
    }

    private static String countJson(Count count) {
        return "{ \"count\": " + count.count() + ", \"of\": " + count.of() + ", \"fraction\": "
                + number(count.fraction()) + " }";
    }

    private static String cell(Aggregate aggregate) {
        return format(aggregate.value()) + " (n=" + aggregate.nDefined() + ")";
    }

    private static String countCell(Count count) {
        return count.count() + "/" + count.of();
    }

    private static String flag(QueryScore score) {
        return score.relevantEvidenceUsed().isPresent()
                ? (score.relevantEvidenceUsed().getAsDouble() == 1.0 ? "yes" : "no") : "n/a";
    }
}
