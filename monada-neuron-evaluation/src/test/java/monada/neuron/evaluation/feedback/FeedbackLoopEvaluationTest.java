package monada.neuron.evaluation.feedback;

import monada.neuron.evaluation.feedback.FeedbackLoopEvaluation.Check;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static Map<String, String> digests(FeedbackLoopEvaluation.Outcome outcome) {
        var digests = new java.util.TreeMap<String, String>();
        outcome.results().forEach(result ->
                digests.put(result.benchmarkName(), result.diagnostics().get("stateDigest")));
        return digests;
    }
}
