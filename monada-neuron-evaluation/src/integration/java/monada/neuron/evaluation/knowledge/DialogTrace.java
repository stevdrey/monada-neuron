package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DecisionTrace.Row;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Assembled;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Held;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Ready;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The trace of the multi-turn suite (Issue #113): one row per turn and arm, recording the question, the carried
 * context the host supplied, how the host assembled it, the real Store and Neuron evidence, the recommendation and how
 * it changed from the same arm's previous turn. It holds no gold label; {@link DialogMetrics} scores it.
 */
final class DialogTrace {

    private DialogTrace() {
    }

    /** The evaluated arms, in run order per turn. */
    enum Arm {
        /** Knowledge retrieval only, for the assembled query; no decision. */
        STORE("A", DecisionTrace.Arm.STORE, "Store retrieval only for the assembled turn query; no decision"),
        /** The host rules over the raw Store evidence with the full carried context. */
        STORE_RULES("A+R", DecisionTrace.Arm.STORE_RULES, "host rules over the raw Store top-k with the full carried"
                + " context (matched reference for B+R)"),
        /** The host rules over the evidence a Neuron cycle selected, with the full carried context. */
        NEURON_RULES("B+R", DecisionTrace.Arm.NEURON_RULES, "Neuron MEMORY_RECALL -> REASONING -> EVALUATION cycle"
                + " per turn through the production memory port, host rules over a SUPPORTED decision's evidence,"
                + " full carried context"),
        /** Control 1: the current turn alone. */
        NO_CONTEXT("B+R:no-context", DecisionTrace.Arm.NEURON_RULES, "control: B+R with the current turn alone and"
                + " no carried context; an elliptical turn must not be understood"),
        /** Control 2: the full context with the decision-topic evidence removed. */
        EVIDENCE_REMOVED("B+R:evidence-removed", DecisionTrace.Arm.NEURON_RULES, "control: B+R with the full context"
                + " but every chunk of the decision topics removed from the evidence; must not claim support");

        private final String label;
        private final DecisionTrace.Arm base;
        private final String description;

        Arm(String label, DecisionTrace.Arm base, String description) {
            this.label = label;
            this.base = base;
            this.description = description;
        }

        String label() {
            return label;
        }

        /** Returns the #112 arm whose row layout this arm uses. */
        DecisionTrace.Arm base() {
            return base;
        }

        String description() {
            return description;
        }
    }

    /** How a recommendation relates to the same arm's previous turn. */
    enum Change {
        /** The first turn of the dialog, or a turn that names a new family. */
        FIRST,
        /** The same outcome, stated choices, options and permissions. */
        UNCHANGED,
        /** The same outcome and stated choices with other options, assumptions or permissions. */
        QUALIFIED,
        /** Another outcome or other stated choices. */
        REVISED,
        /** No recommendation: arm A, or a failed recall. */
        NOT_APPLICABLE
    }

    /**
     * One row.
     *
     * @param dialogId the dialog
     * @param turn the turn number
     * @param arm the arm
     * @param question the current utterance
     * @param context the carried context as supplied, rendered by {@link DialogContext#render()}
     * @param assembled the assembler's result
     * @param row the decision row: evidence, Neuron trace, recommendation and citations; its case id is the turn id
     * @param change the change from the same arm's previous turn
     */
    record TurnRow(String dialogId, int turn, Arm arm, String question, String context, Assembled assembled, Row row,
            Change change) {

        TurnRow {
            Objects.requireNonNull(dialogId, "dialogId must not be null");
            Objects.requireNonNull(arm, "arm must not be null");
            Objects.requireNonNull(question, "question must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(assembled, "assembled must not be null");
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(change, "change must not be null");
            if (row.arm() != arm.base()) {
                throw new IllegalArgumentException(row.caseId() + ": a " + arm.label() + " row needs base arm "
                        + arm.base());
            }
        }

        String turnId() {
            return HtmlDialogCases.turnId(dialogId, turn);
        }

        /** Returns {@code READY} or the hold reason. */
        String assembly() {
            return switch (assembled) {
                case Ready _ -> "READY";
                case Held held -> held.hold().name();
            };
        }
    }

    /** Classifies {@code current} against the same arm's previous row. */
    static Change change(Optional<Row> previous, Row current, boolean first) {
        if (current.recommendation().isEmpty()) {
            return Change.NOT_APPLICABLE;
        }
        if (first || previous.isEmpty() || previous.get().recommendation().isEmpty()) {
            return Change.FIRST;
        }
        if (DecisionTrace.signature(previous.get()).equals(DecisionTrace.signature(current))) {
            return Change.UNCHANGED;
        }
        var before = previous.get().recommendation().get();
        var now = current.recommendation().get();
        var sameChoices = new TreeSet<>(DecisionTrace.stated(before)).equals(new TreeSet<>(DecisionTrace.stated(now)));
        return previous.get().outcome() == current.outcome() && sameChoices ? Change.QUALIFIED : Change.REVISED;
    }

    /** Returns the advice the host records for a row: its outcome, stated choices and rule. */
    static DialogContext.Advice advice(Row row) {
        return new DialogContext.Advice(row.outcome().name(), row.recommendation().map(DecisionTrace::stated)
                .orElse(List.of()), row.recommendation().map(DecisionRecommendation::ruleId).orElse("-"));
    }

    /** Header of the rendered trace: the turn columns, then the #112 decision columns. */
    static final String HEADER = "turnId\tdialogArm\tquestion\tcarried\tused\tassembly\tresolved\tprovenance\tquery"
            + "\tchange\t" + DecisionTrace.HEADER;

    /** Renders rows as TSV, one line per row, for inspection and for the run's semantic digest. */
    static String toTsv(String dialogHash, String corpusHash, List<TurnRow> rows) {
        var tsv = new StringBuilder("# dialogHash ").append(dialogHash).append("\n# corpusHash ").append(corpusHash)
                .append('\n').append(HEADER).append('\n');
        for (var row : rows) {
            var assembled = row.assembled();
            var resolved = assembled instanceof Ready ready ? ready.decisionCase().family().label() + ":"
                    + ready.decisionCase().renderConstraints() : "-";
            var provenance = assembled instanceof Ready ready ? DecisionTrace.join(ready.provenance()) : "-";
            tsv.append(String.join("\t", row.turnId(), row.arm().label(), QueryText.escape(row.question()),
                    QueryText.escape(row.context()), DecisionTrace.join(assembled.used().stream().map(String::valueOf)
                            .toList()), row.assembly(), resolved, provenance, assembled.query(), row.change().name(),
                    DecisionTrace.line(row.row()))).append('\n');
        }
        return tsv.append("end\t").append(rows.size()).append('\n').toString();
    }
}
