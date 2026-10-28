package monada.neuron.evaluation.routing;

import monada.neuron.routing.Basis;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.feedback.ScoreRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalReplayTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @Test
    void anOutcomeReachesOnlyDecisionsAfterItBecomesVisible() {
        // task i's outcome is visible one slot later, between decisions i + 1 and i + 2
        var run = new TemporalReplay(fixtures.improvement(8)).run(ReplayArm.ADAPTIVE);
        int[] visible = run.decisions().visible();

        assertAll(
                () -> assertEquals(0, visible[0]),
                () -> assertEquals(0, visible[1]),
                // decision 2 sees exactly task 0's outcome, decision 3 also task 1's
                () -> assertEquals(1, visible[2]),
                () -> assertEquals(2, visible[3]),
                () -> assertTrue(run.delivered().stream().limit(1).allMatch(d -> d.observation().ledgerSequence()
                        > fixtures.cutoff(1) && d.observation().ledgerSequence() <= fixtures.cutoff(2))),
                // the state is the state as of each cutoff
                () -> assertTrue(run.samples().stream().allMatch(s -> s.stateCutoff() == s.cutoff())));
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
        var run = new TemporalReplay(new ReplayFixture(fixture.fixtureId(), fixture.version(), fixture.definition(),
                fixture.evidencePolicy(), fixture.policy(), fixture.partition(), shifted)).run(ReplayArm.ADAPTIVE);
        int[] visible = run.decisions().visible();

        assertAll(
                () -> assertEquals(0, visible[1]),
                // visible at sequence c, so the decision at cutoff c has examined it
                () -> assertEquals(1, visible[2]),
                () -> assertEquals(atDecisionTwo, run.delivered().getFirst().observation().ledgerSequence()),
                () -> assertEquals(atDecisionTwo, run.samples().get(2).stateCutoff()));
    }

    @Test
    void theDecisionTraceIsTheSameRecordedOrNotAndSeparatesDifferentDecisions() {
        var replay = new TemporalReplay(fixtures.improvement(24));

        assertAll(
                () -> assertEquals(replay.run(ReplayArm.ADAPTIVE).decisions(),
                        replay.run(ReplayArm.ADAPTIVE, false).decisions()),
                // derived-not-consumed sees deliveries but decides exactly as static
                () -> assertArrayEquals(replay.run(ReplayArm.STATIC).decisions().routes(),
                        replay.run(ReplayArm.DERIVED_NOT_CONSUMED, false).decisions().routes()),
                () -> assertArrayEquals(replay.run(ReplayArm.STATIC).decisions().codes(),
                        replay.run(ReplayArm.DERIVED_NOT_CONSUMED, false).decisions().codes()),
                () -> assertEquals(-1, replay.run(ReplayArm.STATIC).decisions()
                        .firstDifference(replay.run(ReplayArm.STATIC, false).decisions())),
                // the adaptive snapshot is the state as of each cutoff from the first decision on; static stays at 0
                () -> assertEquals(0, replay.run(ReplayArm.STATIC, false).decisions()
                        .firstDifference(replay.run(ReplayArm.ADAPTIVE, false).decisions())),
                () -> assertEquals(fixtures.cutoff(0), replay.run(ReplayArm.ADAPTIVE, false).decisions()
                        .watermarks()[0]));
    }

    @Test
    void aFinishedDecisionTraceCannotBeChanged() {
        var trace = new TemporalReplay(fixtures.improvement(8)).run(ReplayArm.ADAPTIVE).decisions();
        var copy = new DecisionTrace(trace.routes(), trace.codes(), trace.watermarks(), trace.visible());
        String digest = new ReplayDigests().decisions(trace);

        trace.routes()[0] = new RouteKey("tampered", 1);
        trace.codes()[0] = -1;
        trace.watermarks()[0] = -1;
        trace.visible()[0] = -1;

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
        var first = new DecisionTrace(new RouteKey[] {aa}, new int[] {0x1000000}, new long[] {0}, new int[] {0});
        var second = new DecisionTrace(new RouteKey[] {bb}, new int[] {0x1000000}, new long[] {0}, new int[] {0});

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

    @Test
    void onlyAnAdaptiveRunCanBeRebuiltFromScratch() {
        var replay = new TemporalReplay(fixtures.cases());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> replay.rebuildFromScratch(replay.run(ReplayArm.STATIC))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> replay.rebuildFromScratch(replay.run(ReplayArm.DERIVED_NOT_CONSUMED))));
    }

    @Test
    void everyDeliveryIsCountedRecordedOrNot() {
        var replay = new TemporalReplay(fixtures.workload(120));
        var recorded = replay.run(ReplayArm.DERIVED_NOT_CONSUMED);
        var bare = replay.run(ReplayArm.DERIVED_NOT_CONSUMED, false);
        var byRule = new EnumMap<ScoreRule, Integer>(ScoreRule.class);
        recorded.feedback().forEach(e -> e.rule().ifPresent(r -> byRule.merge(r, 1, Integer::sum)));

        assertAll(
                () -> assertEquals(recorded.feedback().size(), recorded.derivations().total()),
                () -> assertEquals(byRule, recorded.derivations().rules()),
                () -> assertEquals(recorded.derivations(), bare.derivations()),
                () -> assertEquals(Derivations.none(), replay.run(ReplayArm.STATIC).derivations()));
    }

    @Test
    void reusedObservationsEqualFreshlyGeneratedOnes() {
        var fixture = fixtures.workload(120);
        var reused = new TemporalReplay(fixture);
        reused.run(ReplayArm.ADAPTIVE);
        reused.run(ReplayArm.STATIC, false);
        var digests = new ReplayDigests();

        for (ReplayArm arm : ReplayArm.values()) {
            var again = reused.run(arm);
            var fresh = new TemporalReplay(fixture).run(arm);
            assertAll(
                    () -> assertEquals(fresh.delivered(), again.delivered()),
                    () -> assertEquals(fresh.samples(), again.samples()),
                    () -> assertEquals(digests.feedback(fresh), digests.feedback(again)));
        }
    }

    @Test
    void checkpointedRebuildsReplayLessAndReachTheSameDecisionsAndState() {
        var replay = new TemporalReplay(fixtures.workload(240));
        var digests = new ReplayDigests();
        var scratch = replay.run(ReplayArm.ADAPTIVE);
        var checkpointed = replay.run(ReplayArm.ADAPTIVE, RebuildStrategy.CHECKPOINT);
        var state = checkpointed.state().orElseThrow();
        var restored = assertInstanceOf(RebuildResult.Rebuilt.class, replay.rebuildFromCheckpoint(checkpointed));

        RoutingReplayEvaluation.requireSameReplay(checkpointed, scratch, digests);
        assertAll(
                () -> assertEquals(digests.decisions(scratch), digests.decisions(checkpointed)),
                () -> assertEquals(digests.feedback(scratch), digests.feedback(checkpointed)),
                () -> assertEquals(digests.state(state), digests.state(restored.store())),
                () -> assertEquals(scratch.rebuilds().rebuilds(), checkpointed.rebuilds().rebuilds()),
                () -> assertTrue(checkpointed.rebuilds().fromCheckpoint() > 0),
                // every checkpoint kept went through routing-checkpoint/1 first
                () -> assertEquals(checkpointed.rebuilds().checkpoints(), checkpointed.rebuilds().codecRoundTrips()),
                () -> assertTrue(checkpointed.rebuilds().compactedEntries() > 0),
                () -> assertTrue(state.ledger().size() < state.ledgerDigest().entries()),
                () -> assertTrue(checkpointed.rebuilds().replayedObservations()
                        < scratch.rebuilds().replayedObservations()),
                // the memory model counts the checkpoints actually kept, with their cohorts and retained markers
                () -> assertTrue(checkpointed.rebuilds().keptCheckpoints() > 0
                        && checkpointed.rebuilds().keptCheckpoints() <= RebuildStrategy.RETAINED_CHECKPOINTS),
                () -> assertEquals(checkpointed.rebuilds().keptCheckpoints() * state.cohortCount(),
                        checkpointed.rebuilds().keptCheckpointCohorts()),
                () -> assertEquals(0, scratch.rebuilds().keptCheckpoints()));
    }

    @Test
    void aRunThatWasNeverCompactedCannotBeRebuiltFromACheckpoint() {
        var replay = new TemporalReplay(fixtures.workload(240));
        var scratch = replay.run(ReplayArm.ADAPTIVE);
        assertThrows(IllegalArgumentException.class, () -> replay.rebuildFromCheckpoint(scratch));
        assertThrows(IllegalArgumentException.class,
                () -> replay.rebuildFromCheckpoint(replay.run(ReplayArm.STATIC)));
    }
}
