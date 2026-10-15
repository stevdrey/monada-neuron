package monada.neuron.evaluation.routing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingReplayEvaluationTest {

    private static List<RoutingReplayEvaluation.Check> checks;

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @BeforeAll
    static void verifyOnce() {
        checks = new RoutingReplayEvaluation(true).verify();
    }

    @Test
    void everySemanticCheckPasses() {
        var failures = checks.stream().filter(c -> !c.passed()).map(c -> c.name() + ": " + c.detail()).toList();
        assertEquals(List.of(), failures);
    }

    @Test
    void coversEveryRequiredGate() {
        var names = checks.stream().map(RoutingReplayEvaluation.Check::name).toList();
        assertTrue(names.containsAll(List.of("replay.deterministic", "leakage.future-outcomes",
                "leakage.late-correction", "leakage.snapshot-watermark", "control.derived-not-consumed-equals-static",
                "control.static-equals-cold-start-baseline", "control.neutral-feedback-leaves-state-unchanged",
                "eligibility.violations-zero", "state.incremental-equals-rebuild", "cases.cheap-invalid",
                "cases.repaired-accepted", "cases.unknown-usage", "cases.incomparable-context",
                "cases.currencies-not-summed", "cases.abstention-and-no-eligible-route",
                "cases.missing-counterfactual", "adaptation.improvement-and-degradation-visible",
                "bounds.respected")), names.toString());
    }

    @Test
    void verdictsAndMetadataAreStableAcrossRuns() {
        var again = new RoutingReplayEvaluation(true).verify();
        assertEquals(checks, again);
    }

    @Test
    void determinismCheckRejectsEmptyAndDifferingFingerprints() {
        var replayed = new TreeMap<>(Map.of("a.samples", "3", "report", "x"));
        var differing = new TreeMap<>(Map.of("a.samples", "3", "report", "y"));
        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.determinismCheck(replayed, replayed).passed()),
                // identical but nothing replayed
                () -> assertFalse(RoutingReplayEvaluation.determinismCheck(Map.of("a.samples", "0"),
                        Map.of("a.samples", "0")).passed()),
                () -> assertFalse(RoutingReplayEvaluation.determinismCheck(replayed, differing).passed()));
    }

    @Test
    void theDerivedNotConsumedControlIsVacuousWithoutDerivedFeedback() {
        var control = runs(fixtures.improvement(8), ReplayArm.STATIC);
        // a static run in both positions: identical decisions, but no feedback was ever derived
        var vacuous = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        vacuous.put(ReplayArm.STATIC, control.get(ReplayArm.STATIC));
        vacuous.put(ReplayArm.DERIVED_NOT_CONSUMED, control.get(ReplayArm.STATIC));
        var real = runs(fixtures.improvement(24), ReplayArm.STATIC, ReplayArm.DERIVED_NOT_CONSUMED);
        // the adaptive arm in the derived-only position: it does change decisions
        var consumed = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        consumed.put(ReplayArm.STATIC, real.get(ReplayArm.STATIC));
        consumed.put(ReplayArm.DERIVED_NOT_CONSUMED, runs(fixtures.improvement(24), ReplayArm.ADAPTIVE)
                .get(ReplayArm.ADAPTIVE));

        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(real)).passed()),
                () -> assertFalse(RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(vacuous)).passed()),
                () -> assertFalse(RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(consumed)).passed()));
    }

    @Test
    void theLeakageCheckFailsWhenAMutationCannotChangeAnything() {
        var improvement = fixtures.improvement(24);
        var original = runs(improvement, ReplayArm.values());
        long cutoff = fixtures.cutoff(RoutingReplayEvaluation.LEAKAGE_TASK);
        var truncated = runs(fixtures.truncated(improvement, cutoff), ReplayArm.values());

        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.futureLeakageCheck(original,
                        runs(fixtures.futureFlipped(improvement, cutoff), ReplayArm.values()), truncated, cutoff)
                        .passed()),
                // the original in place of the mutation: earlier decisions are trivially equal
                () -> assertFalse(RoutingReplayEvaluation.futureLeakageCheck(original, original, truncated, cutoff)
                        .passed()));
    }

    @Test
    void theVisibilityCheckDoesNotPassOnANullEffect() {
        var flat = ReplaySummary.of(runs(fixtures.neutral(16), ReplayArm.STATIC).get(ReplayArm.STATIC));
        var improvement = runs(fixtures.improvement(24), ReplayArm.STATIC, ReplayArm.ADAPTIVE);
        var degradation = runs(fixtures.degradation(24), ReplayArm.STATIC, ReplayArm.ADAPTIVE);

        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.visibilityCheck(
                        ReplaySummary.of(improvement.get(ReplayArm.STATIC)),
                        ReplaySummary.of(improvement.get(ReplayArm.ADAPTIVE)),
                        ReplaySummary.of(degradation.get(ReplayArm.STATIC)),
                        ReplaySummary.of(degradation.get(ReplayArm.ADAPTIVE))).passed()),
                () -> assertFalse(RoutingReplayEvaluation.visibilityCheck(flat, flat, flat, flat).passed()));
    }

    @Test
    void reportRendersVerdictsBeforeMeasurementsAndTheStoreArmIsNeverASilentPass() {
        var report = RoutingReplayRunner.run(true);
        var markdown = report.toMarkdown();

        assertAll(
                () -> assertTrue(report.allPassed()),
                () -> assertTrue(markdown.indexOf("## Semantic Checks") < markdown.indexOf("## Run Configuration")),
                () -> assertTrue(report.toJson().contains("\"allChecksPassed\": true")),
                () -> assertTrue(report.metadata().containsKey("fixture.workload.digest")),
                () -> assertTrue(RoutingReplayRunner.storeArmUnavailable().startsWith("UNAVAILABLE")),
                () -> assertEquals(3, RoutingReplayRunner.UNAVAILABLE));
    }

    @Test
    void theReportedRunConfigurationIsTheOneTheEvaluationActuallyUses() {
        var quick = new RoutingReplayEvaluation(true).runConfiguration();
        var full = new RoutingReplayEvaluation(false).runConfiguration();

        assertAll(
                () -> assertTrue(quick.quickMode()),
                () -> assertEquals(1, quick.defaultWarmupIterations()),
                () -> assertEquals(3, quick.defaultMeasurementIterations()),
                () -> assertEquals(3, full.defaultWarmupIterations()),
                () -> assertEquals(10, full.defaultMeasurementIterations()));
    }

    private static Map<ReplayArm, ReplayRun> runs(ReplayFixture fixture, ReplayArm... arms) {
        var replay = new TemporalReplay(fixture);
        var runs = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        for (ReplayArm arm : arms) {
            runs.put(arm, replay.run(arm));
        }
        return runs;
    }
}
