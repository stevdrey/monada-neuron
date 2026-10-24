package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DecisionMetrics.Verdict;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.DecisionTrace.Hit;
import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Held;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Ready;
import monada.neuron.evaluation.knowledge.DialogMetrics.ArmAggregate;
import monada.neuron.evaluation.knowledge.DialogMetrics.Scored;
import monada.neuron.evaluation.knowledge.DialogMetrics.Scores;
import monada.neuron.evaluation.knowledge.DialogTrace.Arm;
import monada.neuron.evaluation.knowledge.DialogTrace.Change;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.format;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.number;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.strings;

/**
 * Renders the scored multi-turn suite (Issue #113) as the {@code dialogs} payload of the JSON report and as Markdown.
 * Both state who holds conversational state (the evaluation host, never Neuron), print the host context rules
 * verbatim, keep single-turn retrieval apart from multi-turn correctness, and carry for every turn its question,
 * carried context, assembly, evidence, answer, change, verdict and diagnostics. Nothing here depends on the clock.
 */
final class HtmlDialogReport {

    /** Issue links offered for a row that is not correct, by the kind of miss. */
    static final String RECALL_FOLLOW_UP = "https://github.com/stevdrey/monada-neuron/issues/135";
    static final String DECISION_FOLLOW_UP = "https://github.com/stevdrey/monada-neuron/issues/112";
    static final String ADJUDICATION_FOLLOW_UP = "https://github.com/stevdrey/monada-neuron/issues/152";

    /** What this suite does not show; rendered in both formats. */
    static List<String> limits(HtmlDialogGold gold) {
        return List.of(
            "Conversational state is held by the evaluation host: each turn receives an explicit, immutable "
                    + DialogContext.VERSION + " context built from the fixture's typed statements, and "
                    + DialogContextAssembler.VERSION + " resolves it by hand-written rules. Neuron runs one"
                    + " request-scoped cycle per turn and keeps nothing between turns; nothing here is a chat session,"
                    + " conversation memory or learning from the dialog.",
            "The assembler reads typed statements the dialog author wrote, not the utterance text. Carrying a"
                    + " constraint forward is context stuffing by a host rule; the no-context control shows how much"
                    + " of every result comes from that rule rather than from Neuron.",
            "Decisions come from the unchanged " + DecisionRuleBaseline.VERSION + " host rules of #112, with the same"
                    + " lexical cues and the same limits: a correct turn is not evidence of a reasoning capability.",
            "Labels reusing a #112 case (goldRef) inherit " + gold.decisionGoldVersion() + " and its independent"
                    + " adjudication. Inline labels were drafted by " + String.join(", ", gold.inlineDrafters())
                    + ", the author of the assembler, and are NOT INDEPENDENTLY ADJUDICATED; no result on an inline"
                    + " turn is independent validation (follow-up: an adjudication like " + ADJUDICATION_FOLLOW_UP
                    + ").",
            "Abstention precision and recall count every abstention; only an abstention that asks a question is a"
                    + " clarification request. The labels do not mark which abstaining turns require a question, so"
                    + " clarification precision is reported and clarification recall is not.",
            "Revision is scored only where the labels require one (revise, qualify); where they do not (keep, any) a"
                    + " turn is credited by correctness alone, so a stable answer and a still-acceptable change both"
                    + " pass.",
            "The evidence-removed control drops every chunk of the decision topics from the Neuron evidence; an"
                    + " answer that still cites something is marked unsupported unless a citation overlaps a gold"
                    + " span.",
            "The dialogs are few, short and authored over a small authored corpus; results do not transfer, and no"
                    + " accuracy threshold gates CI.");
    }

    private final Scores scores;
    private final HtmlDialogCases dialogs;
    private final HtmlDialogGold gold;
    private final DecisionSelector selector;

    HtmlDialogReport(Scores scores, HtmlDialogCases dialogs, HtmlDialogGold gold, DecisionSelector selector) {
        this.scores = Objects.requireNonNull(scores, "scores must not be null");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs must not be null");
        this.gold = Objects.requireNonNull(gold, "gold must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    String toJson() {
        var json = new StringBuilder("{ \"extension\": ").append(string(HtmlDialogExperiment.EXTENSION));
        json.append(", \"limits\": ").append(strings(limits(gold)));
        json.append(", \"state\": { \"conversation\": ").append(string("evaluation host: explicit, immutable "
                + DialogContext.VERSION + " per turn")).append(", \"neuron\": ")
                .append(string("one request-scoped cycle per turn; nothing kept between turns"))
                .append(", \"store\": ").append(string("read-only knowledge retrieval; nothing written by the suite"))
                .append(", \"decision\": ").append(string(selector.kind() + " " + selector.name() + ": "
                        + selector.description())).append(" }");
        json.append(", \"contextRules\": ").append(strings(DialogContextAssembler.RULES));
        json.append(", \"bounds\": { \"maxTurns\": ").append(DialogContextAssembler.MAX_TURNS)
                .append(", \"maxContextBytes\": ").append(DialogContextAssembler.MAX_CONTEXT_BYTES)
                .append(", \"k\": ").append(HtmlDecisionExperiment.K).append(" }");
        json.append(", \"dialogs\": { \"version\": ").append(string(dialogs.version())).append(", \"hash\": ")
                .append(string(dialogs.dialogHash())).append(", \"count\": ").append(dialogs.dialogs().size())
                .append(", \"turns\": ").append(dialogs.turns().size()).append(" }");
        json.append(", \"gold\": { \"version\": ").append(string(gold.version())).append(", \"hash\": ")
                .append(string(gold.hash())).append(", \"reused\": ").append(string(gold.decisionGoldVersion()))
                .append(", \"inline\": ").append(gold.labels().stream().filter(label -> label.goldRef().isEmpty())
                        .count()).append(" }");
        json.append(", \"aggregates\": [ ").append(scores.aggregates().stream().map(this::aggregateJson)
                .collect(Collectors.joining(", "))).append(" ]");
        json.append(", \"deltas\": { ").append(scores.deltas().entrySet().stream().map(entry -> string(entry.getKey())
                + ": " + entry.getValue()).collect(Collectors.joining(", "))).append(" }");
        json.append(", \"changes\": { ").append(scores.changes().entrySet().stream().map(entry ->
                string(entry.getKey().label()) + ": { " + entry.getValue().entrySet().stream().map(observed ->
                        string(observed.getKey().name()) + ": " + observed.getValue())
                        .collect(Collectors.joining(", ")) + " }").collect(Collectors.joining(", "))).append(" }");
        json.append(", \"rows\": [ ").append(scores.scored().stream().map(this::rowJson)
                .collect(Collectors.joining(", "))).append(" ] }");
        return json.toString();
    }

    private String aggregateJson(ArmAggregate aggregate) {
        return "{ \"arm\": " + string(aggregate.arm().label()) + ", \"rows\": " + aggregate.rows() + ", \"judged\": "
                + aggregate.judged() + ", \"correct\": " + aggregate.correct() + ", \"verdicts\": { "
                + aggregate.verdicts().entrySet().stream().map(entry -> string(entry.getKey().name()) + ": "
                        + entry.getValue()).collect(Collectors.joining(", ")) + " }, \"counts\": { "
                + aggregate.counts().entrySet().stream().map(entry -> string(entry.getKey()) + ": "
                        + entry.getValue()).collect(Collectors.joining(", ")) + " }, \"metrics\": { "
                + aggregate.metrics().entrySet().stream().map(entry -> string(entry.getKey()) + ": "
                        + number(entry.getValue())).collect(Collectors.joining(", ")) + " } }";
    }

    private String rowJson(Scored scored) {
        var row = scored.row();
        var decision = row.row();
        var label = scored.label();
        var judged = scored.decision().gold();
        return "{ \"turnId\": " + string(row.turnId()) + ", \"arm\": " + string(row.arm().label())
                + ", \"question\": " + string(row.question()) + ", \"context\": " + string(row.context())
                + ", \"used\": " + strings(row.assembled().used().stream().map(String::valueOf).toList())
                + ", \"assembly\": " + string(row.assembly())
                + ", \"resolved\": " + (row.assembled() instanceof Ready ready ? string(ready.decisionCase().family()
                        .label() + ":" + ready.decisionCase().renderConstraints()) : "null")
                + ", \"provenance\": " + (row.assembled() instanceof Ready ready ? strings(ready.provenance()) : "[]")
                + ", \"hold\": " + (row.assembled() instanceof Held held ? string(held.hold() + ": " + held.detail())
                        : "null")
                + ", \"query\": " + string(row.assembled().query())
                + ", \"evidence\": " + strings(decision.evidence().stream().map(Hit::chunkId).toList())
                + ", \"neuron\": " + decision.neuron().map(trace -> string(trace.render())).orElse("null")
                + ", \"outcome\": " + string(decision.outcome().name())
                + ", \"answer\": " + decision.recommendation().map(value -> string(answer(value))).orElse("null")
                + ", \"citations\": " + strings(decision.citations().stream().map(span -> span.citation().render()
                        + (span.eligible() ? "" : ":ineligible")).toList())
                + ", \"change\": " + string(row.change().name())
                + ", \"detail\": " + string(decision.detail())
                + ", \"gold\": { \"ref\": " + label.goldRef().map(HtmlKnowledgeRecallReport::string).orElse("null")
                        + ", \"expected\": " + string(judged.expected().name()) + ", \"acceptable\": "
                        + strings(judged.acceptable().stream().sorted().toList()) + ", \"change\": "
                        + string(label.change().label()) + ", \"withoutContext\": "
                        + string(label.withoutContext().name()) + " }"
                + ", \"verdict\": " + string(scored.decision().verdict().name())
                + ", \"revisionCorrect\": " + scored.revisionCorrect().map(String::valueOf).orElse("null")
                + ", \"support\": " + string(scored.support().name())
                + ", \"flags\": { " + scored.decision().flags().entrySet().stream().map(entry -> string(entry.getKey())
                        + ": " + entry.getValue()).collect(Collectors.joining(", ")) + " }"
                + ", \"diagnostics\": " + strings(diagnostics(scored)) + " }";
    }

    String toMarkdown() {
        var md = new StringBuilder("## HTML multi-turn follow-ups (" + HtmlDialogExperiment.EXTENSION + ")\n\n");
        md.append("Dialogs `").append(dialogs.version()).append("` (").append(dialogs.dialogs().size())
                .append(" dialogs, ").append(dialogs.turns().size()).append(" turns, hash `")
                .append(dialogs.dialogHash()).append("`); gold `").append(gold.version()).append("` (hash `")
                .append(gold.hash()).append("`, ").append(gold.labels().stream().filter(label -> label.goldRef()
                        .isPresent()).count()).append(" turns reuse `").append(gold.decisionGoldVersion())
                .append("`, ").append(gold.labels().stream().filter(label -> label.goldRef().isEmpty()).count())
                .append(" inline, not independently adjudicated).\n\n");

        md.append("### Who holds conversational state\n\n| Layer | Owner | Here |\n| :--- | :--- | :--- |\n")
                .append("| Conversation context | **the evaluation host** | an explicit, immutable `")
                .append(DialogContext.VERSION).append("` per turn: carried user statements and the advice given |\n")
                .append("| Context resolution | **the evaluation host** | `").append(DialogContextAssembler.VERSION)
                .append("`, printed below |\n")
                .append("| Knowledge retrieval | Monada Resonance Store | the reopened root is queried every turn |\n")
                .append("| Evidence use | Monada Neuron | one request-scoped cycle per turn; nothing kept between"
                        + " turns |\n")
                .append("| Decision | the test host | `").append(selector.name()).append("`: ")
                .append(selector.description()).append(" |\n\n");

        md.append("### Host context rules (evaluation-only)\n\n");
        DialogContextAssembler.RULES.forEach(rule -> md.append("- `").append(rule.replace("`", "'")).append("`\n"));
        md.append("\nBounds: at most ").append(DialogContextAssembler.MAX_TURNS).append(" turns, ")
                .append(DialogContextAssembler.MAX_CONTEXT_BYTES).append(" context bytes, k = ")
                .append(HtmlDecisionExperiment.K).append(".\n");

        md.append("\n### Single-turn retrieval (arm A, scored apart)\n\n| Turn | Store top-")
                .append(HtmlDecisionExperiment.K).append(" | Gold chunks retrieved |\n| :--- | :--- | ---: |\n");
        rows(Arm.STORE).forEach(scored -> md.append("| ").append(scored.row().turnId()).append(" | ")
                .append(scored.row().row().evidence().isEmpty() ? "none" : scored.row().row().evidence().stream()
                        .map(Hit::chunkId).collect(Collectors.joining(", "))).append(" | ")
                .append(format(scored.decision().metrics().get("evidenceCoverage"))).append(" |\n"));

        md.append("\n### Multi-turn results per arm\n\n| Arm | Judged | Correct | Accuracy | Conditional | Revision |"
                + " No change required | Unrequired revisions | Constraint adherence | Unjustified certainty |"
                + " Abstention precision | Abstention recall | Clarifications asked | Clarification precision |"
                + " Citation fidelity | Evidence coverage |\n");
        md.append("| :--- |").append(" ---: |".repeat(15)).append('\n');
        for (var aggregate : scores.aggregates()) {
            if (aggregate.arm() == Arm.STORE) {
                continue;
            }
            var c = aggregate.counts();
            var m = aggregate.metrics();
            md.append("| ").append(aggregate.arm().label()).append(" | ").append(aggregate.judged()).append(" | ")
                    .append(aggregate.correct()).append(" | ").append(format(m.get("turnAccuracy"))).append(" | ")
                    .append(c.get("conditionalCorrect")).append('/').append(c.get("conditionalTurns")).append(" | ")
                    .append(DialogMetrics.REVISION_ARMS.contains(aggregate.arm()) ? c.get("revisionCorrect") + "/"
                            + c.get("revisionTurns") : "n/a").append(" | ")
                    .append(DialogMetrics.REVISION_ARMS.contains(aggregate.arm()) ? c.get("noChangeRequiredCorrect")
                            + "/" + c.get("noChangeRequiredTurns") : "n/a").append(" | ")
                    .append(c.get("unrequiredRevisions")).append(" | ")
                    .append(format(m.get("constraintAdherence"))).append(" | ")
                    .append(c.get("unjustifiedCertainty")).append(" | ")
                    .append(format(m.get("abstentionPrecision"))).append(" | ")
                    .append(format(m.get("abstentionRecall"))).append(" | ")
                    .append(c.get("clarificationRequests")).append(" | ")
                    .append(format(m.get("clarificationPrecision"))).append(" | ")
                    .append(format(m.get("citationFidelity"))).append(" | ")
                    .append(format(m.get("evidenceCoverage"))).append(" |\n");
        }

        md.append("\n### Ablations\n\nTurns correct against their own label (the no-context control is judged above"
                + " against its control label, where abstaining on an under-specified turn is correct; here it must"
                + " answer the dialog's question):");
        scores.aggregates().stream().filter(aggregate -> aggregate.arm() != Arm.STORE).forEach(aggregate -> md
                .append(' ').append(aggregate.arm().label()).append(' ').append(aggregate.counts().get("taskCorrect"))
                .append('/').append(aggregate.judged()).append(';'));
        md.setLength(md.length() - 1);
        md.append(".\n\nDeltas:\n\n");
        scores.deltas().forEach((name, delta) -> md.append("- ").append(name).append(": ")
                .append(delta > 0 ? "+" : "").append(delta).append('\n'));
        var removed = aggregate(Arm.EVIDENCE_REMOVED).counts();
        md.append("\nEvidence-removed control: ").append(removed.get("supportAbstained")).append(" abstained, ")
                .append(removed.get("supportUnsupported")).append(" answered without gold support (marked"
                        + " unsupported), ").append(removed.get("supportClaimed")).append(" claimed gold support.\n");

        md.append("\n### Change sensitivity (B+R)\n\nExpected change per label against the observed change from the"
                + " previous turn.\n\n| Expected | ").append(String.join(" | ", List.of(Change.values()).stream()
                        .map(Change::name).toList())).append(" |\n| :--- |")
                .append(" ---: |".repeat(Change.values().length)).append('\n');
        scores.changes().forEach((expected, observed) -> {
            md.append("| ").append(expected.label());
            for (var change : Change.values()) {
                md.append(" | ").append(observed.getOrDefault(change, 0));
            }
            md.append(" |\n");
        });

        md.append("\n### Turns\n\n| Turn | Question | Carried | Assembly | Gold | B+R answer | Change | B+R |"
                + " A+R | No context | Evidence removed |\n")
                .append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
        for (var turn : dialogs.turns()) {
            var b = find(turn.turnId(), Arm.NEURON_RULES);
            var label = b.label();
            md.append("| ").append(turn.turnId()).append(" | ").append(cell(turn.utterance())).append(" | ")
                    .append(b.row().assembled().used().isEmpty() ? "-" : b.row().assembled().used().stream()
                            .map(String::valueOf).collect(Collectors.joining(", "))).append(" | ")
                    .append(cell(b.row().row().recommendation().orElse(null) instanceof Abstained
                            && b.row().assembled() instanceof Held held ? held.hold() + ": " + held.detail()
                            : b.row().assembly())).append(" | ")
                    .append(label.gold().expected()).append(label.gold().acceptable().isEmpty() ? "" : " "
                            + String.join("/", label.gold().acceptable().stream().sorted().toList()))
                    .append(", ").append(label.change().label()).append(label.goldRef().map(ref -> " (" + ref + ")")
                            .orElse(" (inline)")).append(" | ")
                    .append(cell(b.row().row().recommendation().map(HtmlDialogReport::answer)
                            .orElse(b.row().row().outcome().name()))).append(" | ")
                    .append(b.row().change()).append(" | ").append(verdict(b)).append(" | ")
                    .append(verdict(find(turn.turnId(), Arm.STORE_RULES))).append(" | ")
                    .append(verdict(find(turn.turnId(), Arm.NO_CONTEXT))).append(" | ")
                    .append(find(turn.turnId(), Arm.EVIDENCE_REMOVED).support()).append(" |\n");
        }

        md.append("\n### Errors\n\n");
        var errors = scores.scored().stream().filter(scored -> scored.row().row().outcome() == Outcome.RECALL_FAILED
                || scored.row().assembled() instanceof Held held
                        && held.hold() == DialogContextAssembler.Hold.REJECTED).toList();
        if (errors.isEmpty()) {
            md.append("None: every recall completed and no context was rejected.\n");
        }
        errors.forEach(scored -> md.append("- ").append(scored.row().turnId()).append(' ')
                .append(scored.row().arm().label()).append(": ").append(cell(scored.row().row().detail()))
                .append('\n'));

        md.append("\n### Possible follow-ups\n\nB+R turns that are not correct, with the scorer's diagnostics.\n\n");
        var misses = rows(Arm.NEURON_RULES).stream().filter(scored -> !scored.correct()).toList();
        if (misses.isEmpty()) {
            md.append("None.\n");
        }
        misses.forEach(scored -> md.append("- ").append(scored.row().turnId()).append(" (")
                .append(scored.decision().verdict()).append("): ").append(cell(String.join("; ", diagnostics(scored))))
                .append(" — see ").append(followUp(scored)).append('\n'));

        md.append("\n### Limits\n\n");
        limits(gold).forEach(limit -> md.append("- ").append(limit).append('\n'));
        return md.toString();
    }

    /** The scorer's diagnostics of a row, then the revision miss when one was scored. */
    private static List<String> diagnostics(Scored scored) {
        var all = new ArrayList<>(scored.decision().diagnostics());
        scored.revisionCorrect().filter(ok -> !ok).ifPresent(ok -> all.add("expected change "
                + scored.label().change().label() + ", observed " + scored.row().change()));
        if (scored.support() == DialogMetrics.Support.CLAIMED_SUPPORT) {
            all.add("claimed gold support with the decision-topic evidence removed");
        }
        return all;
    }

    private static String followUp(Scored scored) {
        var verdict = scored.decision().verdict();
        if (verdict == Verdict.EVIDENCE_MISS_ABSTENTION) {
            return RECALL_FOLLOW_UP + " (recall miss)";
        }
        return scored.label().goldRef().isEmpty() ? ADJUDICATION_FOLLOW_UP + " (inline label; check the label and"
                + " the rules)" : DECISION_FOLLOW_UP + " (same verdict as the single-turn case "
                + scored.label().goldRef().get() + ")";
    }

    private static String verdict(Scored scored) {
        return scored.decision().verdict().name();
    }

    private static String answer(DecisionRecommendation recommendation) {
        return switch (recommendation) {
            case Decided decided -> decided.ruleId() + ": " + decided.choice()
                    + (decided.alsoValid().isEmpty() ? "" : " (or " + String.join(", ", decided.alsoValid()) + ")")
                    + (decided.permissions().isEmpty() ? "" : " grants " + String.join(", ", decided.permissions()));
            case Conditional conditional -> conditional.ruleId() + ": " + conditional.options().stream()
                    .map(option -> option.choice() + " if " + option.assumption()).collect(Collectors.joining("; "));
            case Abstained abstained -> abstained.ruleId() + ": abstain"
                    + (abstained.clarifyingQuestion().equals("-") ? "" : "; ask: " + abstained.clarifyingQuestion());
        };
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }

    private List<Scored> rows(Arm arm) {
        return scores.scored().stream().filter(scored -> scored.row().arm() == arm).toList();
    }

    private ArmAggregate aggregate(Arm arm) {
        return scores.aggregates().stream().filter(value -> value.arm() == arm).findFirst().orElseThrow();
    }

    private Scored find(String turnId, Arm arm) {
        return scores.scored().stream().filter(scored -> scored.row().turnId().equals(turnId)
                && scored.row().arm() == arm).findFirst().orElseThrow();
    }
}
