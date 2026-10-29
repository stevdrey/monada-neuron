package monada.neuron.routing.adaptation;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.adaptation.recovery.RecoveryAction;
import monada.neuron.routing.adaptation.recovery.RecoveryPlan;
import monada.neuron.routing.adaptation.recovery.RecoveryStep;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.Reconciliation;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_A;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_B;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.feedbackPolicy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.policy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static monada.neuron.routing.adaptation.AdaptationFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** {@code RecoveryPlan.after} on replays that really stopped, so the bound it names is the one the store reported. */
class RecoveryReplayPlanTest {

    private static final CohortKey KEY_A = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A);
    private static final CohortKey KEY_B = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B);
    private static final RecoveryStep SCRATCH = RecoveryStep.of(RecoveryAction.REBUILD_FROM_SCRATCH);

    private final Map<String, RoutingRequest> requests = new HashMap<>();
    private final Map<String, RoutingDecision.Selected> decisions = new HashMap<>();

    private RoutingObservation sample(String ref, String routeId, long sequence, Kind kind) {
        var request = requests.computeIfAbsent(ref, r -> request("exec-" + r));
        var decision = decisions.computeIfAbsent(ref, r -> decide(request, routeId));
        return observation(request, decision, ref, sequence, kind);
    }

    private RoutingObservation corrected(RoutingObservation original, long sequence, Kind kind) {
        String ref = original.experienceRef();
        return observation(requests.get(ref), decisions.get(ref), ref, original.revision() + 1, sequence,
                Feature.known(original.ledgerSequence()), kind);
    }

    private StateUpdate deliverTo(RoutingStateStore store, RoutingObservation observation) {
        String ref = observation.experienceRef();
        return deliver(store, observation, decisions.get(ref), requests.get(ref));
    }

    private static RoutingStateCheckpoint confirmedCheckpoint(RoutingStateStore store) {
        store.advanceCutoff(store.processedCutoff());
        return store.checkpoint(policy());
    }

    private RebuildResult.Failed failedRebuildFrom(RoutingStateStore target, RoutingStateCheckpoint checkpoint,
            List<RoutingObservation> observations, long cutoff) {
        var result = target.rebuildFrom(checkpoint, observations, cutoff, (o, bindings) -> feedbackPolicy()
                .derive(o, decisions.get(o.experienceRef()), requests.get(o.experienceRef()), bindings));
        return assertInstanceOf(RebuildResult.Failed.class, result);
    }

    // covers: AC-6
    @Test
    void aCorrectionOfACompactedRevisionAsksForAnOlderCheckpointBelowTheSupersededSequence() {
        var live = store();
        live.bind(List.of(KEY_A, KEY_B));
        var first = sample("exp-1", "sub-a", 20, Kind.PASS);
        deliverTo(live, first);
        deliverTo(live, sample("exp-2", "sub-b", 30, Kind.PASS));
        var late = confirmedCheckpoint(live);

        var failed = failedRebuildFrom(store(), late, List.of(corrected(first, 50, Kind.FAIL)), 50);
        assertEquals(new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.SUPERSEDES_COMPACTED, 30, 20),
                failed.update());
        assertEquals(new RecoveryPlan(List.of(RecoveryStep.olderCheckpoint(20), SCRATCH)), RecoveryPlan.after(failed));
    }

    // covers: AC-6
    @Test
    void aFirstApplicationBelowTheHorizonAsksForAnOlderCheckpointBelowItsOwnSequence() {
        var live = store();
        live.bind(List.of(KEY_A));
        deliverTo(live, sample("exp-b", "sub-b", 20, Kind.PASS));
        // a wrong pairing: the request claims another stage than the decision recorded, so it is a retained marker
        var mismatch = sample("exp-m", "sub-a", 30, Kind.PASS);
        var review = request("scope-demo", "review", Feature.unknown(), "exec-exp-m", 101L);
        assertInstanceOf(StateUpdate.Recorded.class, live.consume(mismatch,
                feedbackPolicy().derive(mismatch, decisions.get("exp-m"), review, live.bindings())));
        deliverTo(live, sample("exp-a", "sub-a", 40, Kind.PASS));
        var checkpoint = confirmedCheckpoint(live);
        assertEquals(2, checkpoint.retainedMarkers().size());

        // replayed with the corrected pairing, it is a first application at 30, below the horizon 40
        var target = store();
        target.bind(List.of(KEY_A));
        var failed = failedRebuildFrom(target, checkpoint, List.of(mismatch), 40);
        assertEquals(new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.BEFORE_HORIZON, 40, 30),
                failed.update());
        assertEquals(new RecoveryPlan(List.of(RecoveryStep.olderCheckpoint(30), SCRATCH)), RecoveryPlan.after(failed));
    }

    // covers: AC-6
    @Test
    void aForeignObservationIsHandedBackForInspection() {
        var live = store();
        live.bind(List.of(KEY_A));
        deliverTo(live, sample("exp-1", "sub-a", 20, Kind.PASS));
        var checkpoint = confirmedCheckpoint(live);
        var foreignRequest = request("scope-other", "implement", Feature.unknown(), "exec-f", 7L);
        requests.put("exp-f", foreignRequest);
        decisions.put("exp-f", decide(foreignRequest, "sub-a"));
        var foreign = observation(foreignRequest, decisions.get("exp-f"), "exp-f", 25, Kind.PASS);
        var foreignStore = new RoutingStateStore(AdaptationFixtures.DEFINITION,
                new StatePartition("scope-other", "forge-gates", "1"));
        foreignStore.bind(List.of(new CohortKey("scope-other", "implement", "UNKNOWN", SUB_A)));

        var result = store().rebuildFrom(checkpoint, List.of(foreign), 30, (o, bindings) -> feedbackPolicy()
                .derive(o, decisions.get("exp-f"), foreignRequest, foreignStore.bindings()));
        var failed = assertInstanceOf(RebuildResult.Failed.class, result);
        assertEquals(new StateUpdate.ForeignPartition(List.of(PartitionField.SCOPE)), failed.update());
        assertEquals(new RecoveryPlan(List.of(RecoveryStep.inspect(failed.update()))), RecoveryPlan.after(failed));
    }

    // covers: AC-6
    @Test
    void aRebuildRequiredAsksForTheRebuildAndABoundOfZeroLeavesOnlyIt() {
        var live = store();
        live.bind(List.of(KEY_A));
        var first = sample("exp-1", "sub-a", 20, Kind.PASS);
        deliverTo(live, first);
        var entry = live.ledger().getFirst();
        var rebuild = new RebuildResult.Failed(first,
                new StateUpdate.RebuildRequired(new Reconciliation.RebuildRequired(entry, 20, false)));
        assertEquals(new RecoveryPlan(List.of(SCRATCH)), RecoveryPlan.after(rebuild));

        var atZero = new RebuildResult.Failed(first,
                new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.SUPERSEDES_COMPACTED, 0, 0));
        assertEquals(new RecoveryPlan(List.of(SCRATCH)), RecoveryPlan.after(atZero));

        var covered = new RebuildResult.Failed(first,
                new StateUpdate.BeyondCheckpoint(StateUpdate.BeyondCheckpoint.Kind.COVERED, 20, 20));
        assertEquals(new RecoveryPlan(List.of(RecoveryStep.inspect(covered.update()))), RecoveryPlan.after(covered));
    }
}
