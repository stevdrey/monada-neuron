package monada.neuron.routing.adaptation;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.adaptation.codec.CheckpointDecodeResult;
import monada.neuron.routing.adaptation.codec.CheckpointRejectReason;
import monada.neuron.routing.adaptation.recovery.CheckpointRecovery;
import monada.neuron.routing.adaptation.recovery.ExportState;
import monada.neuron.routing.adaptation.recovery.RecoveryPlan;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.TargetBindings;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static monada.neuron.routing.adaptation.AdaptationFixtures.DEFINITION;
import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_A;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_B;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.demo;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.policy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static monada.neuron.routing.adaptation.AdaptationFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Assessing and planning read the target store and change nothing it publishes or reserves. */
class RecoverySideEffectTest {

    private static final CohortKey KEY_A = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A);
    private static final CohortKey KEY_B = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B);

    private final Map<String, RoutingRequest> requests = new HashMap<>();
    private final Map<String, RoutingDecision.Selected> decisions = new HashMap<>();

    /** Everything a store publishes, plus the capacity it reports to an overflowing bind (its reservations). */
    record Observed(RoutingPreference snapshot, List<AppliedRevision> ledger, LedgerDigest digest,
            long processedCutoff, OptionalLong confirmedCutoff, Optional<RoutingStateCheckpoint> latestCheckpoint,
            OptionalLong horizon, List<CohortState> cohorts, TargetBindings bindings, BindResult probe) {

        static Observed of(RoutingStateStore store) {
            return new Observed(store.snapshot(policy(), demo()), store.ledger(), store.ledgerDigest(),
                    store.processedCutoff(), store.confirmedCutoff(), store.latestCheckpoint(),
                    store.compactionHorizon(), store.cohorts(demo()), store.bindings(), store.bind(probeKeys()));
        }

        /** More fresh keys than any store can hold, so the bind is always refused and never binds. */
        private static List<CohortKey> probeKeys() {
            List<CohortKey> keys = new ArrayList<>();
            for (int i = 0; i <= RoutingStateStore.MAX_COHORTS; i++) {
                keys.add(new CohortKey("scope-demo", "implement", "UNKNOWN", new RouteKey("probe-" + i, 1)));
            }
            return keys;
        }
    }

    private RoutingObservation sample(String ref, String routeId, long sequence, Kind kind) {
        var request = requests.computeIfAbsent(ref, r -> request("exec-" + r));
        var decision = decisions.computeIfAbsent(ref, r -> decide(request, routeId));
        return observation(request, decision, ref, sequence, kind);
    }

    private void deliverTo(RoutingStateStore store, RoutingObservation observation) {
        String ref = observation.experienceRef();
        deliver(store, observation, decisions.get(ref), requests.get(ref));
    }

    private static RoutingStateCheckpoint confirmedCheckpoint(RoutingStateStore store) {
        store.advanceCutoff(store.processedCutoff());
        return store.checkpoint(policy());
    }

    @Test
    void assessingAndPlanningLeaveTheTargetAsItWas() {
        var source = store();
        source.bind(List.of(KEY_A, KEY_B));
        deliverTo(source, sample("exp-1", "sub-a", 20, Kind.PASS));
        deliverTo(source, sample("exp-2", "sub-b", 30, Kind.FAIL));
        var checkpoint = confirmedCheckpoint(source);
        var compacted = confirmedCheckpoint(source);
        source.compact(compacted);

        // a target with its own learning, a confirmed watermark and a compaction horizon
        var target = store();
        target.bind(List.of(KEY_A));
        deliverTo(target, sample("exp-3", "sub-a", 10, Kind.PASS));
        deliverTo(target, sample("exp-4", "sub-b", 12, Kind.PASS));
        target.compact(confirmedCheckpoint(target));
        var foreign = new RoutingStateStore(AdaptationFixtures.definition("adaptive-test/2"),
                new StatePartition("scope-demo", "forge-gates", "1"));
        foreign.bind(List.of(KEY_A));

        var before = Observed.of(target);
        var beforeForeign = Observed.of(foreign);
        for (ExportState export : List.of(new ExportState.Observed(30), new ExportState.Observed(5),
                new ExportState.Unavailable())) {
            RecoveryPlan.of(CheckpointRecovery.assess(checkpoint, target, export));
            RecoveryPlan.of(CheckpointRecovery.assess(checkpoint, target, export, policy()));
            RecoveryPlan.of(CheckpointRecovery.assess(new CheckpointDecodeResult.Decoded(checkpoint), target, export));
            RecoveryPlan.of(CheckpointRecovery.assess(
                    new CheckpointDecodeResult.Rejected(CheckpointRejectReason.CORRUPT), target, export));
            RecoveryPlan.of(CheckpointRecovery.assess(checkpoint, foreign, export));
            target.restoreProblems(checkpoint, 30);
        }
        assertEquals(before, Observed.of(target));
        assertEquals(beforeForeign, Observed.of(foreign));
        assertEquals(DEFINITION, target.definition());
    }
}
