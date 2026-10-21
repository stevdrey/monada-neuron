package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerMetrics.ClaimVerdict;
import monada.neuron.evaluation.knowledge.AnswerMetrics.Edge;
import monada.neuron.evaluation.knowledge.AnswerMetrics.GroupAggregate;
import monada.neuron.evaluation.knowledge.AnswerMetrics.Judged;
import monada.neuron.evaluation.knowledge.AnswerMetrics.ScoredAnswer;
import monada.neuron.evaluation.knowledge.AnswerMetrics.Scores;
import monada.neuron.evaluation.knowledge.AnswerMetrics.Status;
import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.aggregateJson;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.format;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.number;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.strings;

/**
 * Renders the scored grounded-answer experiment (Issue #109) as the {@code answers} payload of the JSON report and as
 * Markdown. Every query row carries its claim-level artifacts (claims, citations, verdicts with their source, every
 * candidate edge, both matchings), so a different human judgment can never hide behind identical counts. Undefined
 * values are JSON {@code null}.
 */
final class HtmlKnowledgeAnswerReport {

    /** What this experiment does not show; rendered in both formats. */
    static final List<String> LIMITS = List.of(
            "B3 and B3c are lexical extractive floors: they quote one segment of the first retrieved chunk. They do not"
                    + " understand the question, combine chunks or generate fluent text.",
            "B3c's coverage rule was chosen after seeing B3 on html-kq-v1, so every B3c result here is in-sample"
                    + " (tuned); there is no held-out query set.",
            "Retrieval success (every required chunk in E(q)) and answer faithfulness are reported separately; a good"
                    + " answer status needs both.",
            "generator-test-double is a TEST_DOUBLE behind the generator seam: it is never judged and its rows are not"
                    + " evidence of model quality. No model, provider or network is used.",
            "A supported claim is grounded in the cited Store text; that does not make it true, and a true claim"
                    + " without an eligible citation is never credited.",
            "Stored text is external memory: nothing here trains model weights, and no learning is claimed.",
            "Human verdicts were drafted by Claude and reviewed and approved by the reviewer of record; the judgments"
                    + " file is frozen by its hash.");

    private final Scores scores;
    private final HtmlKnowledgeCorpus corpus;
    private final List<AnswerComponent> components;

    HtmlKnowledgeAnswerReport(Scores scores, HtmlKnowledgeCorpus corpus, List<AnswerComponent> components) {
        this.scores = Objects.requireNonNull(scores, "scores must not be null");
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.components = List.copyOf(components);
    }

    String toJson() {
        var json = new StringBuilder("{ \"limits\": ").append(strings(LIMITS));
        json.append(", \"judgments\": { \"version\": ").append(string(AnswerJudgments.VERSION))
                .append(", \"hash\": ").append(string(scores.judgmentsHash()))
                .append(", \"missing\": ").append(scores.missing().size())
                .append(", \"stale\": ").append(strings(scores.staleJudgments())).append(" }");
        json.append(", \"components\": [ ").append(components.stream()
                .map(component -> "{ \"name\": " + string(component.name()) + ", \"kind\": "
                        + string(component.kind().name()) + ", \"description\": " + string(component.description())
                        + ", \"tuning\": " + string(component.tuning()) + " }")
                .collect(Collectors.joining(", "))).append(" ]");
        json.append(", \"aggregates\": [ ").append(scores.aggregates().stream().map(this::aggregateRow)
                .collect(Collectors.joining(", "))).append(" ]");
        json.append(", \"answers\": [ ").append(scores.answers().stream().map(this::answerJson)
                .collect(Collectors.joining(", "))).append(" ] }");
        return json.toString();
    }

    private String aggregateRow(GroupAggregate aggregate) {
        return "{ \"root\": " + string(aggregate.root()) + ", \"layer\": " + string(aggregate.layer())
                + ", \"answerK\": " + aggregate.answerK() + ", \"component\": " + string(aggregate.component())
                + ", \"kind\": " + string(aggregate.kind().name()) + ", \"scored\": " + aggregate.scored()
                + ", \"unscored\": " + aggregate.unscored() + ", \"unjudged\": " + aggregate.unjudged()
                + ", \"statuses\": { " + aggregate.statuses().entrySet().stream()
                        .map(entry -> string(entry.getKey().name()) + ": " + entry.getValue())
                        .collect(Collectors.joining(", ")) + " }"
                + ", \"metrics\": { " + aggregate.metrics().entrySet().stream()
                        .map(entry -> string(entry.getKey()) + ": " + aggregateJson(entry.getValue()))
                        .collect(Collectors.joining(", ")) + " }"
                + ", \"abstentionPrecision\": " + number(aggregate.abstentionPrecision())
                + ", \"abstentionRecall\": " + number(aggregate.abstentionRecall())
                + ", \"retrievalComplete\": " + aggregate.retrievalComplete()
                + ", \"answerable\": " + aggregate.answerable()
                + ", \"reviewerOverrides\": " + aggregate.overrides() + " }";
    }

    private String answerJson(ScoredAnswer answer) {
        var row = answer.row();
        var query = answer.query();
        return "{ \"root\": " + string(row.root()) + ", \"layer\": " + string(row.layer()) + ", \"answerK\": "
                + row.answerK() + ", \"queryId\": " + string(row.queryId()) + ", \"category\": "
                + string(query.category().label()) + ", \"answerability\": " + string(query.answerability().name())
                + ", \"component\": " + string(row.component()) + ", \"kind\": " + string(row.kind().name())
                + ", \"required\": " + strings(query.required().stream().sorted().toList())
                + ", \"evidence\": " + strings(row.evidence())
                + ", \"retrievalComplete\": " + answer.retrievalComplete()
                + ", \"outcome\": " + string(row.outcome().name()) + ", \"detail\": " + string(row.detail())
                + ", \"status\": " + answer.status().map(status -> string(status.name())).orElse("null")
                + ", \"flags\": { " + answer.flags().entrySet().stream()
                        .map(entry -> string(entry.getKey()) + ": " + entry.getValue())
                        .collect(Collectors.joining(", ")) + " }"
                + ", \"claims\": [ " + (answer.claims().isEmpty()
                        ? row.claims().stream().map(claim -> "{ \"text\": " + string(claim.text()) + ", \"citations\": "
                                + citations(claim.citations()) + " }").collect(Collectors.joining(", "))
                        : answer.claims().stream().map(this::claimJson).collect(Collectors.joining(", "))) + " ]"
                + ", \"edges\": [ " + answer.edges().stream().map(this::edgeJson).collect(Collectors.joining(", "))
                + " ], \"matcherMatching\": " + answer.matcherMatching()
                + ", \"finalMatching\": " + answer.finalMatching()
                + ", \"metrics\": { " + answer.metrics().entrySet().stream()
                        .map(entry -> string(entry.getKey()) + ": " + number(entry.getValue()))
                        .collect(Collectors.joining(", ")) + " }"
                + ", \"mergedCitations\": " + answer.mergedCitations()
                + ", \"missingJudgments\": " + strings(answer.missing()) + " }";
    }

    private String claimJson(ClaimVerdict claim) {
        return "{ \"index\": " + claim.index() + ", \"text\": " + string(claim.text()) + ", \"citations\": "
                + citations(claim.citations()) + ", \"supported\": " + judged(claim.supported())
                + ", \"contradicted\": " + judged(claim.contradicted()) + " }";
    }

    private String citations(List<CitedSpan> spans) {
        return spans.stream().map(span -> "{ \"chunkId\": " + string(span.citation().chunkId()) + ", \"start\": "
                        + span.citation().start() + ", \"end\": " + span.citation().end() + ", \"chunkSha256\": "
                        + corpus.chunk(span.citation().chunkId()).map(chunk -> string(chunk.sha256())).orElse("null")
                        + ", \"eligible\": " + span.eligible() + ", \"exactQuote\": " + span.exactQuote() + " }")
                .collect(Collectors.joining(", ", "[ ", " ]"));
    }

    private String edgeJson(Edge edge) {
        return "{ \"keyClaim\": " + edge.keyClaim() + ", \"claim\": " + edge.claim() + ", \"matcher\": "
                + (edge.matcher() ? "\"accept\"" : "\"reject\"") + ", \"final\": "
                + (edge.reviewer().value() ? "\"accept\"" : "\"reject\"") + ", \"source\": "
                + string(edge.reviewer().source()) + " }";
    }

    private static String judged(Judged judged) {
        return "{ \"value\": " + judged.value() + ", \"source\": " + string(judged.source()) + " }";
    }

    String toMarkdown() {
        var md = new StringBuilder("## Grounded answers (L4)\n\n");
        md.append("Judgments: `").append(AnswerJudgments.VERSION).append("`, hash `").append(scores.judgmentsHash())
                .append("`; ").append(scores.missing().size()).append(" items missing, ")
                .append(scores.staleJudgments().size()).append(" stale.\n\n");
        md.append("| Component | Kind | Tuning |\n| :--- | :--- | :--- |\n");
        components.forEach(component -> md.append("| ").append(component.name()).append(" | ")
                .append(component.kind()).append(" | ").append(component.tuning()).append(" |\n"));
        md.append("\n| Root | Layer | k | Component | Scored | Retrieval complete | Correct | Partial | Off target |"
                + " Unsupported/contradicted | Answered unanswerable | Correct abstention | Miss abstention |"
                + " Wrong abstention | ClaimSupport | KeyClaimCoverage | CitationRecall | AbstentionPrecision |"
                + " AbstentionRecall |\n");
        md.append("| :--- | :--- | ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
                + " ---: | ---: | ---: | ---: | ---: |\n");
        for (var aggregate : scores.aggregates()) {
            var s = aggregate.statuses();
            md.append("| ").append(aggregate.root()).append(" | ").append(aggregate.layer()).append(" | ")
                    .append(aggregate.answerK()).append(" | ").append(aggregate.component()).append(" | ");
            if (aggregate.kind() == AnswerComponent.Kind.TEST_DOUBLE) {
                var cells = new ArrayList<>(Collections.nCopies(15, ""));
                cells.set(0, "TEST_DOUBLE: not judged");
                cells.set(14, "CitationFidelity " + format(aggregate.metrics().get("CitationFidelity").value()));
                md.append(String.join(" | ", cells)).append(" |\n");
                continue;
            }
            md.append(aggregate.scored()).append(" | ").append(aggregate.retrievalComplete()).append("/")
                    .append(aggregate.answerable()).append(" | ")
                    .append(count(s, Status.SUPPORTED_CORRECT, Status.FALSE_PREMISE_CORRECTED, Status.CONFLICT_SURFACED))
                    .append(" | ").append(count(s, Status.SUPPORTED_PARTIAL)).append(" | ")
                    .append(count(s, Status.SUPPORTED_OFF_TARGET, Status.FALSE_PREMISE_UNADDRESSED,
                            Status.CONFLICT_HIDDEN, Status.CONFLICT_EVIDENCE_MISSING)).append(" | ")
                    .append(count(s, Status.UNSUPPORTED, Status.CONTRADICTED, Status.FALSE_PREMISE_ACCEPTED))
                    .append(" | ").append(count(s, Status.ANSWERED_UNANSWERABLE)).append(" | ")
                    .append(count(s, Status.CORRECT_ABSTENTION)).append(" | ")
                    .append(count(s, Status.EVIDENCE_MISS_ABSTENTION)).append(" | ")
                    .append(count(s, Status.WRONG_ABSTENTION)).append(" | ")
                    .append(format(aggregate.metrics().get("ClaimSupport").value())).append(" | ")
                    .append(format(aggregate.metrics().get("KeyClaimCoverage").value())).append(" | ")
                    .append(format(aggregate.metrics().get("CitationRecall").value())).append(" | ")
                    .append(format(aggregate.abstentionPrecision())).append(" | ")
                    .append(format(aggregate.abstentionRecall())).append(" |\n");
        }
        md.append("\n### Answers on the full root, B1, k = 5\n\n");
        md.append("| Query | Component | Retrieval complete | Status | Answer |\n| :--- | :--- | :--- | :--- | :--- |\n");
        scores.answers().stream().filter(answer -> answer.row().root().equals(HtmlKnowledgeAnswerPhase.FULL)
                        && answer.row().layer().equals("B1")
                        && answer.row().answerK() == HtmlKnowledgeRecall.MAX_LIMIT && answer.judged())
                .forEach(answer -> md.append("| ").append(answer.row().queryId()).append(" | ")
                        .append(answer.row().component()).append(" | ").append(answer.retrievalComplete())
                        .append(" | ").append(answer.status().map(Enum::name).orElse("unjudged")).append(" | ")
                        .append(answer.row().claims().isEmpty() ? "abstained" : cell(answer)).append(" |\n"));
        md.append("\n### Limits\n\n");
        LIMITS.forEach(limit -> md.append("- ").append(limit).append('\n'));
        return md.toString();
    }

    private static String cell(ScoredAnswer answer) {
        var claim = answer.row().claims().getFirst();
        var text = claim.text().replace('\n', ' ').replace("|", "\\|");
        var shortened = text.length() > 90 ? text.substring(0, 87) + "..." : text;
        return shortened + " [" + claim.citations().stream().map(span -> span.citation().render())
                .collect(Collectors.joining(", ")) + "]";
    }

    private static int count(Map<Status, Integer> statuses, Status... wanted) {
        var total = 0;
        for (var status : wanted) {
            total += statuses.getOrDefault(status, 0);
        }
        return total;
    }
}
