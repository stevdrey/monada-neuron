package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DialogContextAssembler.Assembled;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Held;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Ready;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Family;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Expected;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Gold;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Permissions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The gold labels {@value #VERSION} of the multi-turn dialog set (Issue #113), read only by {@link DialogMetrics} and
 * the runner. Each turn either reuses the adjudicated {@code html-decision-v1} label of the case its resolved
 * constraints equal ({@code goldRef}) or carries an inline label in the #112 column syntax; plus the expected change
 * from the previous turn and the expected outcome without the carried context.
 *
 * <p>Load-time invariants, checked against a reference assembly of every turn with its full carried context: one row
 * per turn; a {@code goldRef} turn resolves to exactly that case's family, constraints and candidates and leaves its
 * label columns {@code -}; an inline label passes the #112 label validation; {@code change} equals the change the
 * labels imply; and an elliptical turn (no family of its own) must abstain without context. A violation throws
 * {@link CorpusValidationException}.
 */
final class HtmlDialogGold {

    /** Version of the dialog labels. */
    static final String VERSION = "html-dialog-v1-gold";

    /** Header row of {@code gold.tsv}. */
    static final String HEADER = "dialogId\tturn\tgoldRef\tchange\twithoutContext\texpected\tacceptable\tineligible"
            + "\tassumptions\tpermissions\trationale\tevidenceSpans\treviewer\tdraftedBy";

    private static final String FILE = "gold.tsv";
    private static final String RESOURCE = "/" + HtmlDialogCases.VERSION + "/" + FILE;
    private static final int LABEL_COLUMN = 5;

    /** The change a turn's label implies relative to the previous turn's label. */
    enum Change {
        /** The first turn, or a turn that names a family the previous turn did not resolve to. */
        FIRST,
        /** No previously acceptable answer stays acceptable, or the answer moves to or from an abstention. */
        REVISE,
        /** The same acceptable choices with other options or permissions: a caveat, not a reversal. */
        QUALIFY,
        /** The same label: no change is required. */
        KEEP,
        /** Overlapping but different acceptable choices: no particular change is required. */
        ANY;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the turn alone, without carried context, should produce. */
    enum WithoutContext {
        /** The turn is self-contained; it is judged against its own label. */
        SAME,
        /** The turn is under-specified alone; only an abstention is correct. */
        ABSTAIN
    }

    /**
     * The label of one turn.
     *
     * @param turnId the turn
     * @param goldRef the reused html-decision-v1 case, or empty for an inline label
     * @param gold the label
     * @param change the expected change from the previous turn
     * @param withoutContext the expected outcome without carried context
     */
    record TurnGold(String turnId, Optional<String> goldRef, Gold gold, Change change, WithoutContext withoutContext) {

        TurnGold {
            Objects.requireNonNull(turnId, "turnId must not be null");
            Objects.requireNonNull(goldRef, "goldRef must not be null");
            Objects.requireNonNull(gold, "gold must not be null");
            Objects.requireNonNull(change, "change must not be null");
            Objects.requireNonNull(withoutContext, "withoutContext must not be null");
        }

        /** Returns the label the no-context control is judged against. */
        Gold withoutContextGold() {
            return withoutContext == WithoutContext.SAME ? gold : new Gold(turnId, Expected.ABSTAIN, Set.of(),
                    Set.of(), List.of(), Permissions.NONE, "Without the carried context the turn is under-specified;"
                            + " the answer is to ask.", gold.evidence(), gold.reviewer(), gold.draftedBy());
        }
    }

    private final Map<String, TurnGold> byTurn;
    private final String hash;
    private final String decisionGoldVersion;

    private HtmlDialogGold(Map<String, TurnGold> byTurn, String hash, String decisionGoldVersion) {
        this.byTurn = byTurn;
        this.hash = hash;
        this.decisionGoldVersion = decisionGoldVersion;
    }

    /**
     * Loads and validates the bundled labels.
     *
     * @param dialogs the dialog set
     * @param cases the html-decision-v1 cases a {@code goldRef} names
     * @param decisionGold the active html-decision-v1 labels a {@code goldRef} reuses
     * @param corpus the corpus the spans point into
     * @param reference the full-context assembly of every turn, by turn id
     */
    static HtmlDialogGold bundled(HtmlDialogCases dialogs, HtmlDecisionCases cases, HtmlDecisionGold decisionGold,
            HtmlKnowledgeCorpus corpus, Map<String, Assembled> reference) {
        return parse(HtmlKnowledgeCorpus.bundledTable(HtmlDialogCases.VERSION, FILE), RESOURCE, dialogs, cases,
                decisionGold, corpus, reference);
    }

    /** Parses and validates a label table, as the loader does. */
    static HtmlDialogGold parse(String text, String origin, HtmlDialogCases dialogs, HtmlDecisionCases cases,
            HtmlDecisionGold decisionGold, HtmlKnowledgeCorpus corpus, Map<String, Assembled> reference) {
        var byTurn = new LinkedHashMap<String, TurnGold>();
        for (var row : HtmlKnowledgeCorpus.rows(text, HEADER, origin)) {
            var turnId = HtmlDialogCases.turnId(row.required(0, "dialogId"), row.integer(1, "turn"));
            if (!dialogs.contains(turnId)) {
                throw row.reject("gold label for unknown turn " + turnId);
            }
            var turn = dialogs.turn(turnId);
            var assembled = Objects.requireNonNull(reference.get(turnId), "no reference assembly for " + turnId);
            var goldRef = row.optional(2);
            var labelCells = row.cells().subList(LABEL_COLUMN, row.cells().size());
            Gold gold;
            if (goldRef.isPresent()) {
                if (labelCells.stream().anyMatch(cell -> !cell.strip().equals("-"))) {
                    throw row.reject("a goldRef turn leaves every label column '-'");
                }
                if (!cases.contains(goldRef.get())) {
                    throw row.reject("goldRef " + goldRef.get() + " is not a case of " + cases.version());
                }
                var referenced = cases.byId(goldRef.get());
                if (!(assembled instanceof Ready ready) || ready.decisionCase().family() != referenced.family()
                        || !ready.decisionCase().constraints().equals(referenced.constraints())
                        || !ready.decisionCase().candidates().equals(referenced.candidates())) {
                    throw row.reject(turnId + " does not resolve to the family, constraints and candidates of "
                            + goldRef.get());
                }
                gold = decisionGold.of(goldRef.get());
            } else {
                var cells = new ArrayList<String>();
                cells.add(turnId);
                cells.addAll(labelCells);
                var labelRow = new HtmlKnowledgeCorpus.Row(row.origin(), cells);
                gold = HtmlDecisionGold.goldOf(labelRow);
                HtmlDecisionGold.validate(labelRow, gold, caseOf(turnId, assembled), corpus);
            }
            var change = enumOf(row, Change.values(), row.required(3, "change"));
            var withoutContext = enumOf(row, WithoutContext.values(), row.required(4, "withoutContext"));
            if (turn.family().isEmpty() && withoutContext != WithoutContext.ABSTAIN) {
                throw row.reject("an elliptical turn must abstain without context");
            }
            if (byTurn.put(turnId, new TurnGold(turnId, goldRef, gold, change, withoutContext)) != null) {
                throw row.reject("repeated gold label for " + turnId);
            }
        }
        var missing = dialogs.turns().stream().map(HtmlDialogCases.Turn::turnId)
                .filter(turnId -> !byTurn.containsKey(turnId)).toList();
        if (!missing.isEmpty()) {
            throw new CorpusValidationException(origin, "turns without a gold label: " + missing);
        }
        for (var dialog : dialogs.dialogs()) {
            for (var index = 0; index < dialog.turns().size(); index++) {
                var turn = dialog.turns().get(index);
                var label = byTurn.get(turn.turnId());
                var implied = index == 0 ? Change.FIRST : implied(turn, reference.get(dialog.turns().get(index - 1)
                        .turnId()), byTurn.get(dialog.turns().get(index - 1).turnId()).gold(), label.gold());
                if (label.change() != implied) {
                    throw new CorpusValidationException(origin + " " + turn.turnId(), "change is "
                            + label.change().label() + " but the labels imply " + implied.label());
                }
            }
        }
        return new HtmlDialogGold(Collections.unmodifiableMap(byTurn), CorpusDigests.sha256(text),
                decisionGold.version());
    }

    /** The change the labels of two consecutive turns imply; see {@link Change}. */
    static Change implied(HtmlDialogCases.Turn turn, Assembled previous, Gold before, Gold now) {
        if (turn.family().isPresent() && !turn.family().equals(familyOf(previous))) {
            return Change.FIRST;
        }
        if (DecisionMetrics.goldSignature(before).equals(DecisionMetrics.goldSignature(now))) {
            return Change.KEEP;
        }
        if (before.expected() == Expected.ABSTAIN || now.expected() == Expected.ABSTAIN
                || before.acceptable().stream().noneMatch(now.acceptable()::contains)) {
            return Change.REVISE;
        }
        if (before.expected() == now.expected() && before.acceptable().equals(now.acceptable())) {
            return Change.QUALIFY;
        }
        return Change.ANY;
    }

    /** Returns the label of {@code turnId}. */
    TurnGold of(String turnId) {
        return Objects.requireNonNull(byTurn.get(turnId), "no gold label for " + turnId);
    }

    /** Returns every label in file order. */
    List<TurnGold> labels() {
        return List.copyOf(byTurn.values());
    }

    String version() {
        return VERSION;
    }

    /** Returns the version of the html-decision-v1 labels a {@code goldRef} reuses. */
    String decisionGoldVersion() {
        return decisionGoldVersion;
    }

    /** Returns the SHA-256 of the normalized label file. */
    String hash() {
        return hash;
    }

    /** Returns the inline labels' drafters, for the independence limit. */
    Set<String> inlineDrafters() {
        return new TreeSet<>(byTurn.values().stream().filter(label -> label.goldRef().isEmpty())
                .map(label -> label.gold().draftedBy()).toList());
    }

    /**
     * The case a label of {@code turnId} is validated or scored against: the resolved case of a ready turn, else one
     * whose candidates are the family's choices (none when even the family is unknown), so a held turn's abstention
     * names nothing outside them.
     */
    static DecisionCase caseOf(String turnId, Assembled assembled) {
        return switch (assembled) {
            case Ready ready -> ready.decisionCase();
            case Held held -> new DecisionCase(turnId, held.family().orElse(Family.SCRIPT), List.of(), "-",
                    new EnumMap<>(Constraint.class),
                    held.family().map(Family::choices).orElse(List.of()));
        };
    }

    private static Optional<Family> familyOf(Assembled assembled) {
        return switch (assembled) {
            case Ready ready -> Optional.of(ready.decisionCase().family());
            case Held held -> held.family();
        };
    }

    private static <E extends Enum<E>> E enumOf(HtmlKnowledgeCorpus.Row row, E[] values, String label) {
        return Arrays.stream(values).filter(value -> value.name().toLowerCase(Locale.ROOT).equals(label)).findFirst()
                .orElseThrow(() -> row.reject("'" + label + "' is not one of " + Arrays.stream(values)
                        .map(value -> value.name().toLowerCase(Locale.ROOT)).toList()));
    }
}
