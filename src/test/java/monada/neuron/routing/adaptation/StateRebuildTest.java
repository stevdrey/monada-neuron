package monada.neuron.routing.adaptation;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.EntryBasis;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_A;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_B;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.demo;
import static monada.neuron.routing.adaptation.AdaptationFixtures.derive;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.policy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static monada.neuron.routing.adaptation.AdaptationFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateRebuildTest {

    private static final CohortKey KEY_A = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A);
    private static final CohortKey KEY_B = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B);

    /** Decisions and requests by sample reference, so a rebuild can derive any observation again. */
    private final Map<String, RoutingRequest> requests = new HashMap<>();
    private final Map<String, RoutingDecision.Selected> decisions = new HashMap<>();

    private RoutingStateStore bound() {
        var store = store();
        store.bind(List.of(KEY_A, KEY_B));
        return store;
    }

    private RoutingObservation sample(String ref, String routeId, long revision, long sequence, Feature<Long> previous,
            Kind kind) {
        var request = requests.computeIfAbsent(ref, r -> request("exec-" + r));
        var decision = decisions.computeIfAbsent(ref, r -> decide(request, routeId));
        return observation(request, decision, ref, revision, sequence, previous, kind);
    }

    private StateUpdate deliverTo(RoutingStateStore store, RoutingObservation observation) {
        String ref = observation.experienceRef();
        return deliver(store, observation, decisions.get(ref), requests.get(ref));
    }

    private RebuildResult rebuild(RoutingStateStore live, List<RoutingObservation> observations, long cutoff) {
        return live.rebuild(observations, cutoff,
                o -> derive(live, o, decisions.get(o.experienceRef()), requests.get(o.experienceRef())));
    }

    @Test
    void aRebuildAfterACorrectionEqualsTheStateBuiltFromScratch() {
        var live = bound();
        var first = sample("exp-1", "sub-a", 0, 20, Feature.unknown(), Kind.PASS);
        var second = sample("exp-2", "sub-b", 0, 25, Feature.unknown(), Kind.PASS);
        var corrected = sample("exp-1", "sub-a", 1, 30, Feature.known(20L), Kind.FAIL);
        assertInstanceOf(StateUpdate.Applied.class, deliverTo(live, first));
        assertInstanceOf(StateUpdate.Applied.class, deliverTo(live, second));
        var liveBefore = live.cohorts(demo());
        assertInstanceOf(StateUpdate.RebuildRequired.class, deliverTo(live, corrected));

        var rebuilt = assertInstanceOf(RebuildResult.Rebuilt.class,
                rebuild(live, List.of(first, second, corrected), 40)).store();
        assertEquals(liveBefore, live.cohorts(demo()));
        assertNotSame(live, rebuilt);

        var scratch = bound();
        deliverTo(scratch, second);
        deliverTo(scratch, corrected);
        scratch.advanceCutoff(40);
        assertEquals(scratch.cohorts(demo()), rebuilt.cohorts(demo()));
        assertEquals(scratch.ledger(), rebuilt.ledger());
        assertEquals(scratch.snapshot(policy(), demo()), rebuilt.snapshot(policy(), demo()));
        var a = rebuilt.cohorts(demo()).getFirst();
        assertEquals(KEY_A, a.key());
        assertEquals(3.2, a.amplitude(), 1e-12);
        assertEquals(9.6, a.energy(), 1e-12);
        assertEquals(1, a.supportingObservations());
        assertEquals(40L, rebuilt.processedCutoff());
    }

    @Test
    void aRebuildDoesNotDependOnTheInputOrder() {
        var live = bound();
        List<RoutingObservation> observations = new ArrayList<>();
        Kind[] kinds = Kind.values();
        for (int i = 0; i < 40; i++) {
            observations.add(sample("exp-" + i, i % 2 == 0 ? "sub-a" : "sub-b", 0, 20 + i, Feature.unknown(),
                    kinds[i % kinds.length]));
        }
        observations.add(sample("exp-3", "sub-b", 1, 70, Feature.known(23L), Kind.FAIL));
        var reference = ((RebuildResult.Rebuilt) rebuild(live, observations, 80)).store();
        var random = new Random(66);
        for (int round = 0; round < 25; round++) {
            var shuffled = new ArrayList<>(observations);
            Collections.shuffle(shuffled, random);
            var rebuilt = ((RebuildResult.Rebuilt) rebuild(live, shuffled, 80)).store();
            assertEquals(reference.cohorts(demo()), rebuilt.cohorts(demo()), "round " + round);
            assertEquals(reference.ledger(), rebuilt.ledger(), "round " + round);
        }
    }

    @Test
    void aFailedRebuildLeavesTheLiveStateUnchanged() {
        var live = bound();
        var first = sample("exp-1", "sub-a", 0, 20, Feature.unknown(), Kind.PASS);
        deliverTo(live, first);
        var before = live.cohorts(demo());
        var ledgerBefore = live.ledger();
        var foreignRequest = request("scope-other", "implement", Feature.unknown(), "exec-f", 7L);
        requests.put("exp-f", foreignRequest);
        decisions.put("exp-f", decide(foreignRequest, "sub-a"));
        var foreign = observation(foreignRequest, decisions.get("exp-f"), "exp-f", 25, Kind.PASS);
        // derived with a policy that binds the foreign sample to a foreign store's Nodes: the replay stops
        var foreignStore = new RoutingStateStore(AdaptationFixtures.DEFINITION,
                new StatePartition("scope-other", "forge-gates", "1"));
        foreignStore.bind(List.of(new CohortKey("scope-other", "implement", "UNKNOWN", SUB_A)));
        var result = live.rebuild(List.of(first, foreign), 40, o -> o == foreign
                ? derive(foreignStore, o, decisions.get("exp-f"), foreignRequest)
                : derive(live, o, decisions.get(o.experienceRef()), requests.get(o.experienceRef())));
        var failed = assertInstanceOf(RebuildResult.Failed.class, result);
        assertEquals(foreign, failed.observation());
        assertEquals(new StateUpdate.ForeignPartition(List.of(PartitionField.SCOPE)), failed.update());
        assertEquals(before, live.cohorts(demo()));
        assertEquals(ledgerBefore, live.ledger());
        assertEquals(20L, live.processedCutoff());
    }

    @Test
    void aResetCopyKeepsCohortsAndIdentitiesButNoLearnedState() {
        var live = bound();
        deliverTo(live, sample("exp-1", "sub-a", 0, 20, Feature.unknown(), Kind.PASS));
        var reset = live.resetCopy();
        assertEquals(live.bindings(), reset.bindings());
        assertEquals(List.of(), reset.ledger());
        assertEquals(0L, reset.processedCutoff());
        assertEquals(bound().cohorts(demo()), reset.cohorts(demo()));
        assertNotEquals(live.cohorts(demo()), reset.cohorts(demo()));
    }

    @Test
    void dormantRoutesAreReplayedByTheirOwnKey() {
        var live = bound();
        var b = sample("exp-1", "sub-b", 0, 20, Feature.unknown(), Kind.PASS);
        deliverTo(live, b);
        var onlyA = AdaptationFixtures.only("sub-a");
        var rebuilt = ((RebuildResult.Rebuilt) rebuild(live, List.of(b), 20)).store();
        var dormant = rebuilt.cohorts(onlyA).getLast();
        assertEquals(KEY_B, dormant.key());
        assertEquals(CohortStatus.DORMANT, dormant.status());
        assertEquals(5.6, dormant.amplitude(), 1e-12);
    }

    private RebuildResult rebuildWith(RoutingStateStore live, List<RoutingObservation> observations, long cutoff,
            List<CohortKey> cohortKeys) {
        return live.rebuild(observations, cutoff, cohortKeys, (o, bindings) -> AdaptationFixtures.feedbackPolicy()
                .derive(o, decisions.get(o.experienceRef()), requests.get(o.experienceRef()), bindings));
    }

    @Test
    void aRemovedCohortIsRestoredByOneRebuildEvenWithALaterEffectElsewhere() {
        var live = bound();
        List<RoutingObservation> observations = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var observation = sample("exp-b-" + i, "sub-b", 0, 10 + i, Feature.unknown(), Kind.PASS);
            observations.add(observation);
            assertInstanceOf(StateUpdate.Applied.class, deliverTo(live, observation));
        }
        // a later Node change on another, still-bound cohort
        var laterA = sample("exp-a", "sub-a", 0, 20, Feature.unknown(), Kind.PASS);
        observations.add(laterA);
        assertInstanceOf(StateUpdate.Applied.class, deliverTo(live, laterA));
        var scratch = bound();
        observations.forEach(observation -> deliverTo(scratch, observation));
        scratch.advanceCutoff(30);

        assertTrue(live.unbindDormant(KEY_B, AdaptationFixtures.only("sub-a")));
        // rebinding in place would put a baseline Node under entries the ledger records as applied
        assertEquals(new BindResult.RebuildRequired(List.of(KEY_B)), live.bind(List.of(KEY_B)));
        assertEquals(1, live.cohortCount());

        var restored = assertInstanceOf(RebuildResult.Rebuilt.class,
                rebuildWith(live, observations, 30, List.of(KEY_A, KEY_B))).store();
        assertEquals(scratch.cohorts(demo()), restored.cohorts(demo()));
        assertEquals(scratch.ledger(), restored.ledger());
        assertEquals(scratch.snapshot(policy(), demo()), restored.snapshot(policy(), demo()));
    }

    @Test
    void aRebuildWithoutACohortDropsItsEffectsAsMarkers() {
        var live = bound();
        var b = sample("exp-b", "sub-b", 0, 10, Feature.unknown(), Kind.PASS);
        var a = sample("exp-a", "sub-a", 0, 20, Feature.unknown(), Kind.PASS);
        deliverTo(live, b);
        deliverTo(live, a);
        var dropped = assertInstanceOf(RebuildResult.Rebuilt.class,
                rebuildWith(live, List.of(b, a), 20, List.of(KEY_A))).store();
        assertEquals(List.of(KEY_A), dropped.cohorts(demo()).stream().map(CohortState::key).toList());
        var marker = dropped.ledger().stream().filter(e -> e.experienceRef().equals("exp-b")).findFirst().orElseThrow();
        assertEquals(NoUpdateReason.NO_TARGET_BINDING, ((EntryBasis.NoUpdateMarker) marker.basis()).reason());
        assertEquals(live.cohorts(demo()).getFirst(), dropped.cohorts(demo()).getFirst());
        assertEquals(new BindResult.Bound(List.of(KEY_B)), dropped.bind(List.of(KEY_B)));
    }

    @Test
    void aRebuildNeverMovesTheWatermarkBackwards() {
        var live = bound();
        var first = sample("exp-1", "sub-a", 0, 20, Feature.unknown(), Kind.PASS);
        deliverTo(live, first);
        live.advanceCutoff(50);
        assertEquals("exportCutoff 40 is below processedCutoff 50: the rebuilt state would forget examined observations",
                assertThrows(IllegalArgumentException.class, () -> rebuild(live, List.of(first), 40)).getMessage());
        var fresh = bound();
        assertEquals("processedCutoff cannot move backwards from 20 to 10",
                assertThrows(IllegalArgumentException.class, () -> rebuild(fresh, List.of(first), 10)).getMessage());
    }

    @Test
    void anOversizedCohortSetIsRejected() {
        List<CohortKey> keys = new ArrayList<>();
        for (int i = 0; i <= RoutingStateStore.MAX_COHORTS; i++) {
            keys.add(new CohortKey("scope-demo", "implement", "UNKNOWN", new RouteKey("route-" + i, 1)));
        }
        assertEquals("a rebuilt store allows at most 256 cohorts, got: 257", assertThrows(
                IllegalArgumentException.class, () -> rebuildWith(bound(), List.of(), 0, keys)).getMessage());
    }

    @Test
    void aRemovedCohortWithoutAppliedEffectsCanBeBoundAgainDirectly() {
        var live = bound();
        deliverTo(live, sample("exp-b", "sub-b", 0, 20, Feature.unknown(), Kind.PENDING));
        assertTrue(live.unbindDormant(KEY_B, AdaptationFixtures.only("sub-a")));
        assertEquals(new BindResult.Bound(List.of(KEY_B)), live.bind(List.of(KEY_B)));
    }
}
