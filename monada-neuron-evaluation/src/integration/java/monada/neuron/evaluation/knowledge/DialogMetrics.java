package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DecisionMetrics.Verdict;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.DialogTrace.Arm;
import monada.neuron.evaluation.knowledge.DialogTrace.Change;
import monada.neuron.evaluation.knowledge.DialogTrace.TurnRow;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Expected;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Gold;
import monada.neuron.evaluation.knowledge.HtmlDialogGold.TurnGold;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The scorer of the multi-turn suite (Issue #113) and, with the runner, the only reader of {@link HtmlDialogGold}.
 * Every decision row is judged by the #112 scorer ({@link DecisionMetrics#score(DecisionTrace.Row,
 * HtmlDecisionCases.DecisionCase, Gold)}) against its turn's label; the no-context control is judged against
 * {@link TurnGold#withoutContextGold()}, so an elliptical turn is correct without context only as an abstention.
 *
 * <p>On top, per row: whether the change from the previous turn matches the label's {@link HtmlDialogGold.Change}
 * (scored for {@code revise} and {@code qualify}; {@code keep} and {@code any} require no change and are credited by
 * correctness alone), and, for the evidence-removed control, whether the answer abstained, stayed unsupported by any
 * gold span, or claimed gold support. Per arm: accuracy, conditional and revision accuracy, constraint adherence,
 * evidence and citation coverage, unjustified certainty, abstention precision and recall, and clarification
 * precision (of the abstentions that ask a question, how many are on turns whose label abstains); across arms the
 * context and evidence ablation deltas. Multi-turn correctness is reported apart from single-turn retrieval coverage
 * (arm A). Cost: linear in the rows.
 */
final class DialogMetrics {

    /** What the evidence-removed control did. */
    enum Support {
        /** It abstained. */
        ABSTAINED,
        /** It answered, but no citation overlaps a gold span: the answer is marked unsupported. */
        UNSUPPORTED,
        /** It answered citing a gold span although the decision-topic evidence was removed. */
        CLAIMED_SUPPORT,
        /** Not the evidence-removed control. */
        NOT_APPLICABLE
    }

    /** The arms that decide with the full carried context, whose revisions are scored. */
    static final List<Arm> REVISION_ARMS = List.of(Arm.STORE_RULES, Arm.NEURON_RULES);

    /**
     * One scored row.
     *
     * @param row the trace row
     * @param label the turn's label
     * @param decision the #112 judgment against the label the arm is judged by
     * @param taskCorrect whether the row is correct against the turn's own label; differs from {@link #correct()}
     *     only for the no-context control, whose correct abstention on an under-specified turn does not answer it
     * @param revisionCorrect whether the change matches a {@code revise} or {@code qualify} label, when scored
     * @param support what the evidence-removed control did
     */
    record Scored(TurnRow row, TurnGold label, DecisionMetrics.Scored decision, boolean taskCorrect,
            Optional<Boolean> revisionCorrect, Support support) {

        Scored {
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(revisionCorrect, "revisionCorrect must not be null");
            Objects.requireNonNull(support, "support must not be null");
        }

        boolean correct() {
            return DecisionMetrics.CORRECT.contains(decision.verdict());
        }
    }

    /**
     * Totals of one arm.
     *
     * @param arm the arm
     * @param rows its rows
     * @param judged rows with a verdict
     * @param correct rows with a correct verdict
     * @param verdicts the count per verdict
     * @param counts the named counts, in report order
     * @param metrics the mean ratios over the rows where defined
     */
    record ArmAggregate(Arm arm, int rows, int judged, int correct, Map<Verdict, Integer> verdicts,
            Map<String, Integer> counts, Map<String, OptionalDouble> metrics) {
    }

    /**
     * The scores of a run.
     *
     * @param scored every row, in trace order
     * @param aggregates the totals per arm
     * @param changes for B+R, the observed change per expected change label
     * @param deltas the ablation deltas in rows correct against the turn's own label
     */
    record Scores(List<Scored> scored, List<ArmAggregate> aggregates,
            Map<HtmlDialogGold.Change, Map<Change, Integer>> changes, Map<String, Integer> deltas) {

        Scores {
            scored = List.copyOf(scored);
            aggregates = List.copyOf(aggregates);
        }
    }

    /** The named counts of an aggregate, in report order. */
    static final List<String> COUNTS = List.of("taskCorrect", "conditionalCorrect", "conditionalTurns",
            "revisionCorrect", "revisionTurns", "noChangeRequiredCorrect", "noChangeRequiredTurns",
            "unrequiredRevisions", "constraintsSatisfied", "answered", "unjustifiedCertainty", "abstained",
            "abstainedWhereGoldAbstains", "goldAbstains", "clarificationRequests", "clarifiedWhereGoldAbstains",
            "supportAbstained", "supportUnsupported", "supportClaimed");

    /** The ratio metrics, in report order. */
    static final List<String> METRICS = List.of("turnAccuracy", "conditionalAccuracy", "revisionAccuracy",
            "constraintAdherence", "evidenceCoverage", "citationFidelity", "citationGoldOverlap",
            "unsupportedClaimRate", "abstentionPrecision", "abstentionRecall", "clarificationPrecision");

    private DialogMetrics() {
    }

    /**
     * Requires exactly one row per turn and arm.
     *
     * @throws IllegalArgumentException naming the first missing or extra row
     */
    static void requireComplete(List<TurnRow> rows, HtmlDialogCases dialogs) {
        var seen = new HashSet<String>();
        var turns = new HashSet<String>(dialogs.turns().stream().map(HtmlDialogCases.Turn::turnId).toList());
        for (var row : rows) {
            if (!turns.contains(row.turnId())) {
                throw new IllegalArgumentException("row for unknown turn " + row.turnId());
            }
            if (!seen.add(row.turnId() + " " + row.arm())) {
                throw new IllegalArgumentException("repeated row " + row.turnId() + " " + row.arm().label());
            }
        }
        for (var turnId : dialogs.turns().stream().map(HtmlDialogCases.Turn::turnId).toList()) {
            for (var arm : Arm.values()) {
                if (!seen.contains(turnId + " " + arm)) {
                    throw new IllegalArgumentException("missing row " + turnId + " " + arm.label());
                }
            }
        }
    }

    /** Scores {@code rows} against {@code gold}. */
    static Scores score(List<TurnRow> rows, HtmlDialogCases dialogs, HtmlDialogGold gold) {
        requireComplete(rows, dialogs);
        var scored = new ArrayList<Scored>(rows.size());
        for (var row : rows) {
            scored.add(score(row, gold.of(row.turnId())));
        }
        var aggregates = new ArrayList<ArmAggregate>();
        for (var arm : Arm.values()) {
            aggregates.add(aggregate(arm, scored.stream().filter(value -> value.row().arm() == arm).toList()));
        }
        var changes = new EnumMap<HtmlDialogGold.Change, Map<Change, Integer>>(HtmlDialogGold.Change.class);
        scored.stream().filter(value -> value.row().arm() == Arm.NEURON_RULES).forEach(value -> changes
                .computeIfAbsent(value.label().change(), key -> new EnumMap<>(Change.class))
                .merge(value.row().change(), 1, Integer::sum));
        var correct = new EnumMap<Arm, Integer>(Arm.class);
        aggregates.forEach(aggregate -> correct.put(aggregate.arm(), aggregate.counts().get("taskCorrect")));
        var deltas = new LinkedHashMap<String, Integer>();
        deltas.put("B+R minus B+R:no-context", correct.get(Arm.NEURON_RULES) - correct.get(Arm.NO_CONTEXT));
        deltas.put("B+R minus B+R:evidence-removed", correct.get(Arm.NEURON_RULES)
                - correct.get(Arm.EVIDENCE_REMOVED));
        deltas.put("B+R minus A+R", correct.get(Arm.NEURON_RULES) - correct.get(Arm.STORE_RULES));
        return new Scores(scored, aggregates, changes, deltas);
    }

    /** Scores one row against its turn's label. */
    static Scored score(TurnRow row, TurnGold label) {
        var judgedAgainst = row.arm() == Arm.NO_CONTEXT ? label.withoutContextGold() : label.gold();
        var decision = DecisionMetrics.score(row.row(), HtmlDialogGold.caseOf(row.turnId(), row.assembled()),
                judgedAgainst);
        var correct = DecisionMetrics.CORRECT.contains(decision.verdict());
        var taskCorrect = row.arm() != Arm.NO_CONTEXT ? correct : DecisionMetrics.CORRECT.contains(DecisionMetrics
                .score(row.row(), HtmlDialogGold.caseOf(row.turnId(), row.assembled()), label.gold()).verdict());
        Optional<Boolean> revision = Optional.empty();
        if (REVISION_ARMS.contains(row.arm()) && decision.verdict() != Verdict.NOT_SCORED) {
            revision = switch (label.change()) {
                case REVISE -> Optional.of(correct && row.change() == Change.REVISED);
                case QUALIFY -> Optional.of(correct && row.change() == Change.QUALIFIED);
                case FIRST, KEEP, ANY -> Optional.empty();
            };
        }
        var support = Support.NOT_APPLICABLE;
        if (row.arm() == Arm.EVIDENCE_REMOVED) {
            support = row.row().outcome() == Outcome.ABSTAINED ? Support.ABSTAINED
                    : decision.metrics().get("citationGoldOverlap").orElse(0.0) > 0 ? Support.CLAIMED_SUPPORT
                    : Support.UNSUPPORTED;
        }
        return new Scored(row, label, decision, taskCorrect, revision, support);
    }

    private static ArmAggregate aggregate(Arm arm, List<Scored> rows) {
        var verdicts = new EnumMap<Verdict, Integer>(Verdict.class);
        rows.forEach(value -> verdicts.merge(value.decision().verdict(), 1, Integer::sum));
        var judged = (int) rows.stream().filter(value -> value.decision().verdict() != Verdict.NOT_SCORED).count();
        var correct = (int) rows.stream().filter(Scored::correct).count();
        var counts = new LinkedHashMap<String, Integer>();
        COUNTS.forEach(name -> counts.put(name, 0));
        for (var value : rows) {
            if (value.decision().verdict() == Verdict.NOT_SCORED) {
                continue;
            }
            var gold = value.decision().gold();
            var flags = value.decision().flags();
            var abstained = value.row().row().outcome() == Outcome.ABSTAINED;
            counts.merge("taskCorrect", value.taskCorrect() ? 1 : 0, Integer::sum);
            if (gold.expected() == Expected.CONDITIONAL) {
                counts.merge("conditionalTurns", 1, Integer::sum);
                counts.merge("conditionalCorrect", value.correct() ? 1 : 0, Integer::sum);
            }
            value.revisionCorrect().ifPresent(ok -> {
                counts.merge("revisionTurns", 1, Integer::sum);
                counts.merge("revisionCorrect", ok ? 1 : 0, Integer::sum);
            });
            var change = value.label().change();
            if (REVISION_ARMS.contains(arm) && (change == HtmlDialogGold.Change.KEEP
                    || change == HtmlDialogGold.Change.ANY)) {
                counts.merge("noChangeRequiredTurns", 1, Integer::sum);
                counts.merge("noChangeRequiredCorrect", value.correct() ? 1 : 0, Integer::sum);
                if (change == HtmlDialogGold.Change.KEEP && value.row().change() == Change.REVISED) {
                    counts.merge("unrequiredRevisions", 1, Integer::sum);
                }
            }
            if (!abstained) {
                counts.merge("answered", 1, Integer::sum);
                counts.merge("constraintsSatisfied", flags.getOrDefault("constraintsSatisfied", false) ? 1 : 0,
                        Integer::sum);
            }
            if (flags.getOrDefault("noEvidenceCertainty", false)
                    || value.decision().verdict() == Verdict.OVERCONFIDENT) {
                counts.merge("unjustifiedCertainty", 1, Integer::sum);
            }
            if (abstained) {
                counts.merge("abstained", 1, Integer::sum);
                counts.merge("abstainedWhereGoldAbstains", gold.expected() == Expected.ABSTAIN ? 1 : 0,
                        Integer::sum);
                if (value.row().row().recommendation().orElse(null) instanceof Abstained asked
                        && !asked.clarifyingQuestion().equals("-")) {
                    counts.merge("clarificationRequests", 1, Integer::sum);
                    counts.merge("clarifiedWhereGoldAbstains", gold.expected() == Expected.ABSTAIN ? 1 : 0,
                            Integer::sum);
                }
            }
            if (gold.expected() == Expected.ABSTAIN) {
                counts.merge("goldAbstains", 1, Integer::sum);
            }
            switch (value.support()) {
                case ABSTAINED -> counts.merge("supportAbstained", 1, Integer::sum);
                case UNSUPPORTED -> counts.merge("supportUnsupported", 1, Integer::sum);
                case CLAIMED_SUPPORT -> counts.merge("supportClaimed", 1, Integer::sum);
                case NOT_APPLICABLE -> {
                }
            }
        }
        var metrics = new LinkedHashMap<String, OptionalDouble>();
        metrics.put("turnAccuracy", ratio(correct, judged));
        metrics.put("conditionalAccuracy", ratio(counts.get("conditionalCorrect"), counts.get("conditionalTurns")));
        metrics.put("revisionAccuracy", ratio(counts.get("revisionCorrect"), counts.get("revisionTurns")));
        metrics.put("constraintAdherence", ratio(counts.get("constraintsSatisfied"), counts.get("answered")));
        for (var name : List.of("evidenceCoverage", "citationFidelity", "citationGoldOverlap",
                "unsupportedClaimRate")) {
            metrics.put(name, rows.stream().map(value -> value.decision().metrics().get(name))
                    .filter(OptionalDouble::isPresent).mapToDouble(OptionalDouble::getAsDouble).average());
        }
        metrics.put("abstentionPrecision", ratio(counts.get("abstainedWhereGoldAbstains"), counts.get("abstained")));
        metrics.put("abstentionRecall", ratio(counts.get("abstainedWhereGoldAbstains"), counts.get("goldAbstains")));
        // Only an abstention that asks counts as a clarification request; the labels do not mark which abstaining
        // turns require a question (a budget overrun or an unsupported requirement abstains without one), so no
        // clarification recall is defined.
        metrics.put("clarificationPrecision", ratio(counts.get("clarifiedWhereGoldAbstains"),
                counts.get("clarificationRequests")));
        return new ArmAggregate(arm, rows.size(), judged, correct, verdicts, counts, metrics);
    }

    private static OptionalDouble ratio(long numerator, long denominator) {
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) numerator / denominator);
    }
}
