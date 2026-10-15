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
    void theDecisionChecksumIsTheSameRecordedOrNotAndSeparatesDifferentDecisions() {
        var replay = new TemporalReplay(fixtures.improvement(24));

        assertAll(
                () -> assertEquals(replay.run(ReplayArm.ADAPTIVE).decisionChecksum(),
                        replay.run(ReplayArm.ADAPTIVE, false).decisionChecksum()),
                () -> assertEquals(replay.run(ReplayArm.STATIC).decisionChecksum(),
                        replay.run(ReplayArm.DERIVED_NOT_CONSUMED, false).decisionChecksum()),
                () -> assertNotEquals(replay.run(ReplayArm.STATIC, false).decisionChecksum(),
                        replay.run(ReplayArm.ADAPTIVE, false).decisionChecksum()));
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
