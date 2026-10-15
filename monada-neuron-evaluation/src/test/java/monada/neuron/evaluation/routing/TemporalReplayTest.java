package monada.neuron.evaluation.routing;

import monada.neuron.routing.Basis;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.adaptation.RebuildResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalReplayTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @Test
    void anOutcomeReachesOnlyDecisionsAfterItBecomesVisible() {
        // task i's outcome is visible one slot later, between decisions i + 1 and i + 2
        var run = new TemporalReplay(fixtures.improvement(8)).run(ReplayArm.ADAPTIVE);
        var samples = run.samples();

        assertAll(
                () -> assertEquals(0L, samples.get(0).stateCutoff()),
                () -> assertEquals(0L, samples.get(1).stateCutoff()),
                // decision 2 sees exactly task 0's outcome
                () -> assertEquals(run.delivered().getFirst().observation().ledgerSequence(),
                        samples.get(2).stateCutoff()),
                () -> assertTrue(samples.stream().allMatch(s -> s.stateCutoff() < s.cutoff())));
    }

    @Test
    void anOutcomeAtExactlyALaterCutoffReachesThatDecisionBecauseTheCutoffIsInclusive() {
        var fixture = fixtures.improvement(6);
        long atDecisionTwo = fixtures.cutoff(2);
        var shifted = new ArrayList<ReplayTask>();
        for (ReplayTask task : fixture.tasks()) {
            var known = (OutcomeOracle.KnownForEveryRoute) task.oracle();
            if (task.request().requestOrdinal() != 0) {
                shifted.add(task);
                continue;
            }
            var outcomes = new TreeMap<RouteKey, ScriptedOutcome>();
            known.outcomes().forEach((route, outcome) -> outcomes.put(route, outcome.observedAt(atDecisionTwo)));
            shifted.add(new ReplayTask(task.request(), task.catalog(), new OutcomeOracle.KnownForEveryRoute(outcomes)));
        }
        var samples = new TemporalReplay(new ReplayFixture(fixture.fixtureId(), fixture.version(), fixture.definition(),
                fixture.evidencePolicy(), fixture.policy(), fixture.partition(), shifted)).run(ReplayArm.ADAPTIVE)
                .samples();

        assertAll(
                () -> assertEquals(0L, samples.get(1).stateCutoff()),
                // visible at sequence c, so the decision at cutoff c has examined it
                () -> assertEquals(atDecisionTwo, samples.get(2).stateCutoff()));
    }

    @Test
    void theDecisionTraceIsTheSameRecordedOrNotAndSeparatesDifferentDecisions() {
        var replay = new TemporalReplay(fixtures.improvement(24));

        assertAll(
                () -> assertEquals(replay.run(ReplayArm.ADAPTIVE).decisions(),
                        replay.run(ReplayArm.ADAPTIVE, false).decisions()),
                () -> assertEquals(replay.run(ReplayArm.STATIC).decisions(),
                        replay.run(ReplayArm.DERIVED_NOT_CONSUMED, false).decisions()),
                () -> assertEquals(-1, replay.run(ReplayArm.STATIC).decisions()
                        .firstDifference(replay.run(ReplayArm.STATIC, false).decisions())),
                // decision 2 is the first whose adaptive snapshot has examined an outcome (static stays at 0)
                () -> assertEquals(2, replay.run(ReplayArm.STATIC, false).decisions()
                        .firstDifference(replay.run(ReplayArm.ADAPTIVE, false).decisions())));
    }

    @Test
    void aFinishedDecisionTraceCannotBeChanged() {
        var trace = new TemporalReplay(fixtures.improvement(8)).run(ReplayArm.ADAPTIVE).decisions();
        var copy = new DecisionTrace(trace.routes(), trace.codes(), trace.watermarks());
        String digest = new ReplayDigests().decisions(trace);

        trace.routes()[0] = new RouteKey("tampered", 1);
        trace.codes()[0] = -1;
        trace.watermarks()[0] = -1;

        assertAll(
                () -> assertEquals(copy, trace),
                () -> assertEquals(-1, copy.firstDifference(trace)),
                () -> assertEquals(digest, new ReplayDigests().decisions(trace)));
    }

    @Test
    void routeIdsWithTheSameStringHashAreDifferentDecisions() {
        // "Aa" and "BB" share String.hashCode(), so a hash-folded checksum could not tell them apart
        var aa = new RouteKey("Aa", 1);
        var bb = new RouteKey("BB", 1);
        var first = new DecisionTrace(new RouteKey[] {aa}, new int[] {0x1000000}, new long[] {0});
        var second = new DecisionTrace(new RouteKey[] {bb}, new int[] {0x1000000}, new long[] {0});

        assertAll(
                () -> assertEquals("Aa".hashCode(), "BB".hashCode()),
                () -> assertNotEquals(first, second),
                () -> assertEquals(0, first.firstDifference(second)),
                () -> assertNotEquals(new ReplayDigests().decisions(first), new ReplayDigests().decisions(second)));
    }

    @Test
    void theAdaptiveStateEndsAtTheFixtureHorizonNotAtItsLastObservation() {
        var base = fixtures.improvement(8);
        // an export examined beyond its last observation, as a Store export with an unfinished execution is
        var fixture = new ReplayFixture(base.fixtureId(), base.version(), base.definition(), base.evidencePolicy(),
                base.policy(), base.partition(), base.tasks(), base.horizon() + 1000);
        var replay = new TemporalReplay(fixture);
        var adaptive = replay.run(ReplayArm.ADAPTIVE);
        long lastDelivered = adaptive.delivered().stream().mapToLong(d -> d.observation().ledgerSequence()).max()
                .orElseThrow();

        assertAll(
                () -> assertTrue(fixture.horizon() > lastDelivered),
                () -> assertEquals(lastDelivered, new TemporalReplay(base).run(ReplayArm.ADAPTIVE).state()
                        .orElseThrow().processedCutoff()),
                () -> assertEquals(fixture.horizon(), adaptive.state().orElseThrow().processedCutoff()),
                // deriving without consuming examines nothing
                () -> assertEquals(0L, replay.run(ReplayArm.DERIVED_NOT_CONSUMED).state().orElseThrow()
                        .processedCutoff()));
    }

    @Test
    void theStaticArmKeepsNoStateAndDeliversNothing() {
        var run = new TemporalReplay(fixtures.improvement(8)).run(ReplayArm.STATIC);

        assertAll(
                () -> assertTrue(run.state().isEmpty()),
                () -> assertEquals(List.of(), run.delivered()),
                () -> assertEquals(List.of(), run.feedback()),
                () -> assertEquals(8, run.samples().size()));
    }

    @Test
    void theAdaptiveArmLearnsOnlyOnceEveryEligibleRouteHasEnoughSupport() {
        var samples = new TemporalReplay(fixtures.improvement(24)).run(ReplayArm.ADAPTIVE).samples();

        assertAll(
                // four outage tasks then sub-a until its three failures are visible: the #62 baseline
                () -> assertTrue(samples.subList(0, 8).stream().allMatch(s -> s.basis().orElseThrow()
                        == Basis.COLD_START)),
                () -> assertEquals(Basis.LEARNED_PREFERENCE, samples.get(8).basis().orElseThrow()),
                () -> assertEquals(SyntheticRoutingFixtures.SUB_B, samples.get(8).route().orElseThrow()));
    }

    @Test
    void loggedOutcomesAreDeliveredWhateverTheReplayAdvises() {
        var fixture = fixtures.chosenRouteOnly(16);
        var adaptive = new TemporalReplay(fixture).run(ReplayArm.ADAPTIVE);
        long exported = fixture.tasks().stream()
                .mapToLong(t -> ((OutcomeOracle.ChosenRouteOnly) t.oracle()).observed().size()).sum();

        assertAll(
                () -> assertEquals(exported, adaptive.delivered().size()),
                () -> assertTrue(adaptive.feedback().stream().noneMatch(FeedbackEvent::replayed)),
                () -> assertTrue(adaptive.samples().stream()
                        .anyMatch(s -> s.coverage() == ReplaySample.Coverage.MISSING_COUNTERFACTUAL)));
    }

    @Test
    void aCorrectionIsRebuiltAndTheResultEqualsARebuildFromScratch() {
        var replay = new TemporalReplay(fixtures.cases());
        var run = replay.run(ReplayArm.ADAPTIVE);
        var digests = new ReplayDigests();
        var scratch = assertInstanceOf(RebuildResult.Rebuilt.class, replay.rebuildFromScratch(run));

        assertAll(
                () -> assertTrue(run.feedback().stream().anyMatch(e -> e.update() == FeedbackEvent.Update.REBUILT)),
                () -> assertEquals(digests.state(run.state().orElseThrow()), digests.state(scratch.store())));
    }

    @Test
    void anUnrecordedRunBuildsNoSamplesButReachesTheSameState() {
        var replay = new TemporalReplay(fixtures.workload(120));
        var digests = new ReplayDigests();
        var recorded = replay.run(ReplayArm.ADAPTIVE);
        var bare = replay.run(ReplayArm.ADAPTIVE, false);

        assertAll(
                () -> assertEquals(List.of(), bare.samples()),
                () -> assertEquals(List.of(), bare.feedback()),
                () -> assertEquals(digests.state(recorded.state().orElseThrow()),
                        digests.state(bare.state().orElseThrow())),
                () -> assertFalse(recorded.samples().isEmpty()));
    }
}
