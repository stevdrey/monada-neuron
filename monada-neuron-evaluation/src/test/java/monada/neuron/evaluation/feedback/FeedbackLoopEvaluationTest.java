package monada.neuron.evaluation.feedback;

import monada.neuron.action.ActionStatus;
import monada.neuron.evaluation.feedback.FeedbackLoopEvaluation.Check;
import monada.neuron.evaluation.feedback.FeedbackLoopEvaluation.Run;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;
import monada.neuron.evolution.OutcomeFeedback;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackLoopEvaluationTest {

    private static final long SEED = 42L;

    @Test
    void everySemanticCheckPassesInQuickMode() {
        var outcome = new FeedbackLoopEvaluation(SEED, true).run();

        var failed = outcome.checks().stream().filter(check -> !check.passed()).map(Check::name).toList();
        assertAll(
                () -> assertTrue(failed.isEmpty(), "failed checks: " + failed),
                () -> assertTrue(outcome.allPassed()),
                () -> assertTrue(outcome.checks().stream().map(Check::name).toList().containsAll(List.of(
                        "cycles.complete-through-action",
                        "ab.derived-not-consumed-matches-control",
                        "ab.consumed-diverges-after-first-consumption",
                        "replay.consumed-is-deterministic",
                        "neutral.environmental-outcome-matches-control",
                        "direction.reward-raises-and-penalty-lowers-amplitude",
                        "feedback.bounded",
                        "history.bounded"))));
    }

    @Test
    void reportsOneMeasuredRowPerArmWithRetainedStateDiagnostics() {
        var outcome = new FeedbackLoopEvaluation(SEED, true).run();

        var names = outcome.results().stream().map(result -> result.benchmarkName()).toList();
        var consumed = outcome.results().stream()
                .filter(result -> result.benchmarkName().equals("FeedbackLoop.consumed"))
                .findFirst().orElseThrow();
        assertAll(
                () -> assertEquals(
                        List.of("FeedbackLoop.control", "FeedbackLoop.derived-not-consumed", "FeedbackLoop.consumed"),
                        names),
                () -> assertTrue(consumed.diagnostics().containsKey("stateDigest")),
                () -> assertTrue(consumed.diagnostics().containsKey("maxNodeHistorySize")),
                () -> assertTrue(consumed.diagnostics().containsKey("modeledFeedbackBytesPerCycle")));
    }

    @Test
    void stateDigestsAreStableAcrossRunsAndSeparateConsumedFromUnconsumedArms() {
        Map<String, String> first = digests(new FeedbackLoopEvaluation(SEED, true).run());
        Map<String, String> second = digests(new FeedbackLoopEvaluation(SEED, true).run());

        assertAll(
                () -> assertEquals(first, second),
                () -> assertEquals(first.get("FeedbackLoop.control"), first.get("FeedbackLoop.derived-not-consumed")),
                () -> assertTrue(!first.get("FeedbackLoop.control").equals(first.get("FeedbackLoop.consumed"))));
    }

    @Test
    void reportRendersVerdictsBeforeMeasurements() {
        var report = FeedbackLoopRunner.run(SEED, true);

        var markdown = report.toMarkdown();
        var json = report.toJson();
        assertAll(
                () -> assertTrue(report.allPassed()),
                () -> assertTrue(markdown.indexOf("## Semantic Checks") < markdown.indexOf("## Run Configuration")),
                () -> assertTrue(markdown.contains("ab.derived-not-consumed-matches-control")),
                () -> assertTrue(json.contains("\"allChecksPassed\": true")),
                () -> assertTrue(json.contains("\"title\": \"" + FeedbackLoopReport.TITLE + "\"")));
    }

    // The checks must not pass vacuously: identical untouched states prove nothing unless the feedback was
    // really derived (and, for the consumed arm, handed to the next cycle).

    private static final int CYCLES = 3;
    private static final UUID MONAD = new UUID(0L, 7L);
    private static final double[] UNCHANGED = {1.0, 2.0, 3.0, 4.0, 0.0};

    private static Run run(List<OutcomeFeedback> derived, boolean reachedAction) {
        var fingerprints = new ArrayList<double[]>();
        for (var i = 0; i < CYCLES; i++) {
            fingerprints.add(UNCHANGED.clone());
        }
        return new Run(fingerprints, derived, 0, reachedAction, 1.0, UNCHANGED.clone());
    }

    private static List<OutcomeFeedback> neutral(ActionStatus status, int count) {
        var derived = new ArrayList<OutcomeFeedback>();
        for (var i = 0; i < count; i++) {
            derived.add(OutcomeFeedback.neutral(MONAD, i, status, List.of()));
        }
        return derived;
    }

    private static List<OutcomeFeedback> reinforcing(int count, int entries) {
        var derived = new ArrayList<OutcomeFeedback>();
        for (var i = 0; i < count; i++) {
            var list = new ArrayList<FeedbackEntry>();
            for (var e = 0; e < entries; e++) {
                list.add(FeedbackEntry.of(new UUID(0L, e + 1L), 1.0));
            }
            derived.add(new OutcomeFeedback(
                    MONAD, i, ActionStatus.SUCCEEDED, 0, FeedbackDisposition.REINFORCE, list, List.of()));
        }
        return derived;
    }

    @Test
    void neutralCheckPassesOnlyWhenNeutralFeedbackWasDerivedForEveryCycle() {
        var control = run(List.of(), true);
        var consumed = run(neutral(ActionStatus.TIMED_OUT, CYCLES), true);

        assertAll(
                () -> assertTrue(FeedbackLoopEvaluation.neutralCheck(control, consumed, ActionStatus.TIMED_OUT, CYCLES).passed()),
                // no feedback derived at all: identical states, but nothing was proven
                () -> assertFalse(FeedbackLoopEvaluation.neutralCheck(
                        control, run(List.of(), true), ActionStatus.TIMED_OUT, CYCLES).passed()),
                // derived for fewer cycles than were run
                () -> assertFalse(FeedbackLoopEvaluation.neutralCheck(
                        control, run(neutral(ActionStatus.TIMED_OUT, CYCLES - 1), true),
                        ActionStatus.TIMED_OUT, CYCLES).passed()),
                // derived feedback that is not neutral
                () -> assertFalse(FeedbackLoopEvaluation.neutralCheck(
                        control, run(reinforcing(CYCLES, 1), true), ActionStatus.TIMED_OUT, CYCLES).passed()),
                // derived from a different status than the one under test
                () -> assertFalse(FeedbackLoopEvaluation.neutralCheck(
                        control, run(neutral(ActionStatus.UNAVAILABLE, CYCLES), true),
                        ActionStatus.TIMED_OUT, CYCLES).passed()),
                // the neutral runs never reached ACTION
                () -> assertFalse(FeedbackLoopEvaluation.neutralCheck(
                        control, run(neutral(ActionStatus.TIMED_OUT, CYCLES), false),
                        ActionStatus.TIMED_OUT, CYCLES).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.neutralCheck(
                        run(List.of(), false), consumed, ActionStatus.TIMED_OUT, CYCLES).passed()));
    }

    @Test
    void neutralCheckFailsWhenConsumingNeutralFeedbackMovesAState() {
        var control = run(List.of(), true);
        var moved = new double[] {9.0, 2.0, 3.0, 4.0, 1.0};
        var fingerprints = new ArrayList<double[]>(List.of(UNCHANGED.clone(), moved, moved));
        var consumed = new Run(fingerprints, neutral(ActionStatus.TIMED_OUT, CYCLES), 1, true, 1.0, moved);

        assertFalse(FeedbackLoopEvaluation.neutralCheck(control, consumed, ActionStatus.TIMED_OUT, CYCLES).passed());
    }

    @Test
    void replayCheckRequiresFeedbackToHaveBeenDerivedForEveryCycle() {
        var derived = reinforcing(CYCLES, 2);

        assertAll(
                () -> assertTrue(FeedbackLoopEvaluation.replayCheck(
                        run(derived, true), run(derived, true), CYCLES).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.replayCheck(
                        run(List.of(), true), run(List.of(), true), CYCLES).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.replayCheck(
                        run(derived, true), run(reinforcing(CYCLES, 1), true), CYCLES).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.replayCheck(
                        run(derived, true), run(derived, false), CYCLES).passed()));
    }

    @Test
    void boundedCheckRequiresRealFeedbackWithinTheBounds() {
        assertAll(
                () -> assertTrue(FeedbackLoopEvaluation.feedbackBoundedCheck(
                        run(reinforcing(CYCLES, 2), true), 2, CYCLES).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.feedbackBoundedCheck(
                        run(List.of(), true), 2, CYCLES).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.feedbackBoundedCheck(
                        run(neutral(ActionStatus.TIMED_OUT, CYCLES), true), 2, CYCLES).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.feedbackBoundedCheck(
                        run(reinforcing(CYCLES, 3), true), 2, CYCLES).passed()));
    }

    @Test
    void completionCheckCoversEveryRunItIsGiven() {
        assertAll(
                () -> assertTrue(FeedbackLoopEvaluation.completionCheck(
                        List.of(run(List.of(), true), run(List.of(), true))).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.completionCheck(
                        List.of(run(List.of(), true), run(List.of(), false))).passed()),
                () -> assertFalse(FeedbackLoopEvaluation.completionCheck(List.of()).passed()));
    }

    private static Map<String, String> digests(FeedbackLoopEvaluation.Outcome outcome) {
        var digests = new java.util.TreeMap<String, String>();
        outcome.results().forEach(result ->
                digests.put(result.benchmarkName(), result.diagnostics().get("stateDigest")));
        return digests;
    }
}
