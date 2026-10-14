package monada.neuron.routing.adaptation;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.Reconciliation;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static monada.neuron.routing.adaptation.AdaptationFixtures.API_X;
import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.PARTITION;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_A;
import static monada.neuron.routing.adaptation.AdaptationFixtures.SUB_B;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.demo;
import static monada.neuron.routing.adaptation.AdaptationFixtures.derive;
import static monada.neuron.routing.adaptation.AdaptationFixtures.key;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.policy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static monada.neuron.routing.adaptation.AdaptationFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingStateStoreTest {

    private static final RoutingRequest REQUEST = request("exec-0");
    private static final CohortKey KEY_A = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_A);
    private static final CohortKey KEY_B = new CohortKey("scope-demo", "implement", "UNKNOWN", SUB_B);

    /** A store with both demo routes bound for the default request's cohort. */
    private static RoutingStateStore bound() {
        var store = store();
        assertInstanceOf(BindResult.Bound.class, store.bind(List.of(KEY_A, KEY_B)));
        return store;
    }

    /** One executed sample of {@code routeId} for its own request. */
    private record Sample(RoutingRequest request, RoutingDecision.Selected decision) {

        static Sample of(String routeId, int index) {
            var request = AdaptationFixtures.request("exec-" + routeId + "-" + index);
            return new Sample(request, decide(request, routeId));
        }

        RoutingObservation observe(String ref, long sequence, Kind kind) {
            return observation(request, decision, ref, sequence, kind);
        }

        StateUpdate deliverTo(RoutingStateStore store, String ref, long sequence, Kind kind) {
            return deliver(store, observe(ref, sequence, kind), decision, request);
        }
    }

    private static CohortState cohort(RoutingStateStore store, CohortKey key) {
        return store.cohorts(demo()).stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void anEmptyStorePublishesAValidColdStartSnapshot() {
        var store = store();
        var snapshot = store.snapshot(policy(), demo());
        assertEquals(List.of(), snapshot.cohorts());
        assertEquals(0L, snapshot.processedCutoff());
        assertEquals("adaptive-test/1", snapshot.mappingVersion());
        assertEquals(List.of(), snapshot.mismatches(REQUEST, policy().definition()));
        var decision = assertInstanceOf(RoutingDecision.Selected.class, policy().decide(REQUEST, demo(), snapshot));
        assertEquals(SUB_A, decision.route());
        assertEquals(Basis.COLD_START, decision.basis());
    }

    @Test
    void bindingBuildsBaselineNodesWithDeterministicIdentitiesAndNoHistory() {
        var store = bound();
        var a = cohort(store, KEY_A);
        assertEquals(4.0, a.amplitude());
        assertEquals(50.0, a.frequency());
        assertEquals(0.5, a.phase());
        assertEquals(10.0, a.energy());
        assertEquals(0.0, a.preferenceValue());
        assertEquals(0, a.supportingObservations());
        assertEquals(CohortNodeIdentity.of(KEY_A, "forge-gates", "1", "adaptive-test/1"), a.nodeId());
        assertEquals(a.nodeId(), bound().bindings().find(KEY_A).orElseThrow());
        assertEquals(new BindResult.Bound(List.of()), store.bind(List.of(KEY_A)));
    }

    @Test
    void derivationWithoutConsumptionLeavesTheStateBitIdentical() {
        var store = bound();
        var before = store.snapshot(policy(), demo());
        var cohortsBefore = store.cohorts(demo());
        var bindingsBefore = store.bindings();
        var sample = Sample.of("sub-a", 1);
        for (Kind kind : Kind.values()) {
            var derivation = derive(store, sample.observe("exp-" + kind, 20, kind), sample.decision, sample.request);
            assertInstanceOf(FeedbackDerivation.Derived.class, derivation);
        }
        assertEquals(before, store.snapshot(policy(), demo()));
        assertEquals(cohortsBefore, store.cohorts(demo()));
        assertEquals(bindingsBefore, store.bindings());
        assertEquals(List.of(), store.ledger());
    }

    @Test
    void aConsumedRewardMovesOnlyTheExecutedRoutesNode() {
        var store = bound();
        var update = Sample.of("sub-a", 1).deliverTo(store, "exp-1", 20, Kind.PASS);
        var applied = assertInstanceOf(StateUpdate.Applied.class, update);
        assertTrue(applied.entry().changedState());
        var a = cohort(store, KEY_A);
        assertEquals(5.6, a.amplitude(), 1e-12);
        assertEquals(10.8, a.energy(), 1e-12);
        assertEquals(1.6, a.preferenceValue(), 1e-12);
        assertEquals(1, a.supportingObservations());
        assertEquals(50.0, a.frequency());
        assertEquals(0.5, a.phase());
        var b = cohort(store, KEY_B);
        assertEquals(4.0, b.amplitude());
        assertEquals(0, b.supportingObservations());
        assertEquals(20L, store.processedCutoff());
        assertEquals(1, store.ledger().size());
    }

    @Test
    void aConsumedPenaltyLowersOnlyTheExecutedRoutesNode() {
        var store = bound();
        assertInstanceOf(StateUpdate.Applied.class, Sample.of("sub-b", 1).deliverTo(store, "exp-1", 20, Kind.FAIL));
        var b = cohort(store, KEY_B);
        assertEquals(3.2, b.amplitude(), 1e-12);
        assertEquals(9.6, b.energy(), 1e-12);
        assertEquals(1, b.supportingObservations());
        assertEquals(4.0, cohort(store, KEY_A).amplitude());
    }

    @Test
    void neutralFeedbackChangesNoNodeButAdvancesTheWatermark() {
        var store = bound();
        var before = store.cohorts(demo());
        var applied = assertInstanceOf(StateUpdate.Applied.class,
                Sample.of("sub-a", 1).deliverTo(store, "exp-1", 30, Kind.PENDING));
        assertFalse(applied.entry().changedState());
        assertEquals(before, store.cohorts(demo()));
        assertEquals(30L, store.processedCutoff());
        var snapshot = store.snapshot(policy(), demo());
        assertEquals(30L, snapshot.processedCutoff());
        assertEquals(0, snapshot.cohorts().getFirst().supportingObservations());
    }

    @Test
    void aDuplicateDeliveryIsAppliedOnce() {
        var store = bound();
        var sample = Sample.of("sub-a", 1);
        sample.deliverTo(store, "exp-1", 20, Kind.PASS);
        var after = store.cohorts(demo());
        var duplicate = assertInstanceOf(StateUpdate.Unchanged.class, sample.deliverTo(store, "exp-1", 20, Kind.PASS));
        assertInstanceOf(Reconciliation.AlreadyApplied.class, duplicate.advice());
        assertTrue(duplicate.examined());
        assertEquals(after, store.cohorts(demo()));
    }

    @Test
    void consumedFeedbackCanChangeALaterEligibleRouteOnlyWithSufficientSupport() {
        var store = bound();
        long sequence = 20;
        for (int i = 0; i < 2; i++) {
            Sample.of("sub-a", i).deliverTo(store, "exp-a-" + i, sequence++, Kind.FAIL);
            Sample.of("sub-b", i).deliverTo(store, "exp-b-" + i, sequence++, Kind.PASS);
        }
        // two observations per route are below minSupportingObservations = 3: exactly the #62 baseline
        var early = assertInstanceOf(RoutingDecision.Selected.class,
                policy().decide(REQUEST, demo(), store.snapshot(policy(), demo())));
        assertEquals(SUB_A, early.route());
        assertEquals(Basis.COLD_START, early.basis());

        Sample.of("sub-a", 2).deliverTo(store, "exp-a-2", sequence++, Kind.FAIL);
        Sample.of("sub-b", 2).deliverTo(store, "exp-b-2", sequence, Kind.PASS);
        var learned = assertInstanceOf(RoutingDecision.Selected.class,
                policy().decide(REQUEST, demo(), store.snapshot(policy(), demo())));
        assertEquals(SUB_B, learned.route());
        assertEquals(Basis.LEARNED_PREFERENCE, learned.basis());
        assertEquals(StateValidation.Compatible.class, learned.provenance().validation().getClass());
    }

    @Test
    void learningNeverSelectsAnIneligibleRoute() {
        var store = bound();
        for (int i = 0; i < 6; i++) {
            Sample.of("sub-b", i).deliverTo(store, "exp-b-" + i, 20 + i, Kind.PASS);
            Sample.of("sub-a", i).deliverTo(store, "exp-a-" + i, 40 + i, Kind.FAIL);
        }
        var withoutB = RouteCatalog.of("cat-demo-8", List.of(
                new CatalogEntry(AdaptationFixtures.route("sub-a"), Availability.AVAILABLE, 1),
                new CatalogEntry(AdaptationFixtures.route("sub-b"), Availability.UNAVAILABLE, 1)));
        var decision = assertInstanceOf(RoutingDecision.Selected.class,
                policy().decide(REQUEST, withoutB, store.snapshot(policy(), withoutB)));
        assertEquals(SUB_A, decision.route());
        assertEquals(SUB_B, decision.exclusions().getFirst().key());
    }

    @Test
    void amplitudesSaturateWithinTheBoundsAndAPenalizedRouteRecovers() {
        var store = bound();
        long sequence = 20;
        for (int i = 0; i < 12; i++) {
            Sample.of("sub-a", i).deliverTo(store, "exp-up-" + i, sequence++, Kind.PASS);
        }
        assertEquals(6.0, cohort(store, KEY_A).amplitude());
        assertEquals(12.0, cohort(store, KEY_A).energy());
        for (int i = 0; i < 40; i++) {
            Sample.of("sub-a", 100 + i).deliverTo(store, "exp-down-" + i, sequence++, Kind.FAIL);
        }
        assertEquals(0.5, cohort(store, KEY_A).amplitude());
        assertEquals(-3.5, cohort(store, KEY_A).preferenceValue());
        Sample.of("sub-a", 999).deliverTo(store, "exp-recover", sequence, Kind.PASS);
        assertEquals(0.7, cohort(store, KEY_A).amplitude(), 1e-12);
        assertEquals(53, cohort(store, KEY_A).supportingObservations());
    }

    @Test
    void anUnboundRouteIsRecordedAndMovesNothing() {
        var store = bound();
        var before = store.cohorts(demo());
        var update = Sample.of("api-x", 1).deliverTo(store, "exp-x", 25, Kind.PASS);
        var recorded = assertInstanceOf(StateUpdate.Recorded.class, update);
        assertEquals(NoUpdateReason.NO_TARGET_BINDING,
                ((monada.neuron.routing.feedback.EntryBasis.NoUpdateMarker) recorded.marker().basis()).reason());
        assertEquals(before, store.cohorts(demo()));
        assertEquals(25L, store.processedCutoff());
    }

    @Test
    void scopeAFeedbackNeverReachesScopeB() {
        var scopeA = bound();
        var scopeB = new RoutingStateStore(AdaptationFixtures.DEFINITION,
                new StatePartition("scope-b", "forge-gates", "1"));
        scopeB.bind(List.of(new CohortKey("scope-b", "implement", "UNKNOWN", SUB_A)));
        var catalog = demo();
        var bBefore = scopeB.cohorts(catalog);
        var sample = Sample.of("sub-a", 1);
        var observation = sample.observe("exp-1", 20, Kind.PASS);

        // derived against scope B's own bindings: foreign, so nothing is derived or recorded
        var foreignDerivation = derive(scopeB, observation, sample.decision, sample.request);
        var unchanged = assertInstanceOf(StateUpdate.Unchanged.class, scopeB.consume(observation, foreignDerivation));
        assertEquals(new Reconciliation.NotRecorded(NoUpdateReason.BINDING_SCOPE_MISMATCH), unchanged.advice());
        assertFalse(unchanged.examined());

        // a feedback derived for scope A, handed to scope B, is rejected as foreign
        var derivedForA = derive(scopeA, observation, sample.decision, sample.request);
        assertEquals(new StateUpdate.ForeignPartition(List.of(PartitionField.SCOPE)),
                scopeB.consume(observation, derivedForA));
        assertInstanceOf(StateUpdate.Applied.class, scopeA.consume(observation, derivedForA));

        assertEquals(bBefore, scopeB.cohorts(catalog));
        assertEquals(0L, scopeB.processedCutoff());
        assertEquals(List.of(), scopeB.ledger());
    }

    @Test
    void anotherMappingVersionIsForeignAndItsSnapshotIsIncompatible() {
        var v2 = AdaptationFixtures.definition("adaptive-test/2");
        var store = new RoutingStateStore(v2, PARTITION);
        store.bind(List.of(KEY_A, KEY_B));
        var v1 = bound();
        var sample = Sample.of("sub-a", 1);
        var observation = sample.observe("exp-1", 20, Kind.PASS);
        var derivedForV1 = derive(v1, observation, sample.decision, sample.request);
        assertEquals(new StateUpdate.ForeignPartition(List.of(PartitionField.MAPPING_VERSION)),
                store.consume(observation, derivedForV1));
        assertEquals(List.of(), store.ledger());

        // a v2 snapshot handed to the v1 policy is incompatible: abstain, never fall back
        var decision = assertInstanceOf(RoutingDecision.Abstain.class,
                policy().decide(REQUEST, demo(), store.snapshot(policy(), demo())));
        assertEquals(AbstainReason.STATE_INCOMPATIBLE, decision.reason());
        assertEquals(new StateValidation.Incompatible(List.of(StateMismatch.MAPPING_VERSION_MISMATCH)),
                decision.provenance().validation());
    }

    @Test
    void aFeedbackThatCreditsANodeTheStoreDoesNotBindIsRejected() {
        var other = new RoutingStateStore(AdaptationFixtures.DEFINITION, PARTITION);
        other.bind(List.of(KEY_A));
        var sample = Sample.of("sub-a", 1);
        var observation = sample.observe("exp-1", 20, Kind.PASS);
        var derivation = derive(other, observation, sample.decision, sample.request);
        var empty = store();
        var thrown = assertThrows(IllegalArgumentException.class, () -> empty.consume(observation, derivation));
        assertTrue(thrown.getMessage().startsWith("the feedback credits Node "), thrown.getMessage());
        assertEquals(0L, empty.processedCutoff());
    }

    @Test
    void cohortCapacityIsReportedAndBindingIsAllOrNothing() {
        var store = store();
        var keys = new ArrayList<CohortKey>();
        for (int i = 0; i < RoutingStateStore.MAX_COHORTS - 1; i++) {
            keys.add(new CohortKey("scope-demo", "implement", "UNKNOWN", new RouteKey("route-" + i, 1)));
        }
        assertEquals(255, ((BindResult.Bound) store.bind(keys)).added().size());
        var twoMore = List.of(new CohortKey("scope-demo", "plan", "S", SUB_A),
                new CohortKey("scope-demo", "plan", "S", SUB_B));
        assertEquals(new BindResult.CapacityExhausted(2, 1), store.bind(twoMore));
        assertEquals(255, store.cohortCount());
        assertEquals(new BindResult.Bound(List.of(twoMore.getFirst())), store.bind(List.of(twoMore.getFirst())));
        assertEquals(new BindResult.CapacityExhausted(1, 0), store.bind(List.of(twoMore.getLast())));
        assertEquals(RoutingPreference.MAX_COHORTS, store.bindings().bindings().size());
    }

    @Test
    void bindingAnotherScopesCohortIsRejected() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> store().bind(List.of(new CohortKey("scope-b", "implement", "UNKNOWN", SUB_A))));
        assertEquals("cohort CohortKey[scopeId=scope-b, stageKind=implement, cohortBucket=UNKNOWN, "
                + "route=RouteKey[routeId=sub-a, routeVersion=1]] does not belong to scope scope-demo",
                thrown.getMessage());
    }

    @Test
    void ledgerCapacityIsReportedWithTheStateUnchanged() {
        var store = bound();
        var sample = Sample.of("api-x", 1);
        for (int i = 0; i < RoutingStateStore.MAX_LEDGER; i++) {
            assertInstanceOf(StateUpdate.Recorded.class,
                    sample.deliverTo(store, String.format("exp-%05d", i), 20 + i, Kind.PASS));
        }
        var cohortsBefore = store.cohorts(demo());
        long cutoffBefore = store.processedCutoff();
        var blocked = assertInstanceOf(StateUpdate.Blocked.class,
                Sample.of("sub-a", 1).deliverTo(store, "exp-overflow", 50_000, Kind.PASS));
        assertInstanceOf(Reconciliation.CapacityExhausted.class, blocked.advice());
        assertEquals(cohortsBefore, store.cohorts(demo()));
        assertEquals(cutoffBefore, store.processedCutoff());
        assertEquals(RoutingStateStore.MAX_LEDGER, store.ledger().size());
    }

    @Test
    void dormantCohortsAreKeptUnpublishedAndReturnIntact() {
        var store = bound();
        for (int i = 0; i < 3; i++) {
            Sample.of("sub-b", i).deliverTo(store, "exp-b-" + i, 20 + i, Kind.PASS);
        }
        var onlyA = AdaptationFixtures.only("sub-a");
        var snapshot = store.snapshot(policy(), onlyA);
        assertEquals(List.of(SUB_A), snapshot.cohorts().stream().map(c -> c.route()).toList());
        var b = store.cohorts(onlyA).stream().filter(c -> c.key().equals(KEY_B)).findFirst().orElseThrow();
        assertEquals(CohortStatus.DORMANT, b.status());
        assertEquals(3, b.supportingObservations());

        var returned = store.snapshot(policy(), demo()).cohorts().stream()
                .filter(c -> c.route().equals(SUB_B)).findFirst().orElseThrow();
        assertEquals(b.preferenceValue(), returned.preferenceValue());
        assertEquals(3, returned.supportingObservations());

        var thrown = assertThrows(IllegalArgumentException.class, () -> store.unbindDormant(KEY_B, demo()));
        assertTrue(thrown.getMessage().endsWith("is active in catalog cat-demo-7"), thrown.getMessage());
        assertTrue(store.unbindDormant(KEY_B, onlyA));
        assertFalse(store.unbindDormant(KEY_B, onlyA));
        assertEquals(1, store.cohortCount());
        assertTrue(store.bindings().find(KEY_B).isEmpty());
    }

    @Test
    void theWatermarkNeverMovesBackwardsAndBoundsTheSnapshotsAdmission() {
        var store = bound();
        store.advanceCutoff(500);
        var thrown = assertThrows(IllegalArgumentException.class, () -> store.advanceCutoff(499));
        assertEquals("processedCutoff cannot move backwards from 500 to 499", thrown.getMessage());
        Sample.of("sub-a", 1).deliverTo(store, "exp-1", 20, Kind.PASS);
        assertEquals(500L, store.processedCutoff());

        var early = request("scope-demo", "implement", Feature.unknown(), "exec-early", 1L);
        var earlyCutoff = new RoutingRequest(early.contractVersion(), early.scopeId(), early.taskId(),
                early.executionId(), early.attemptId(), early.stageId(), early.stageKind(), early.requestOrdinal(),
                early.sourceFingerprint(), early.contextFingerprint(), early.constraintsFingerprint(),
                early.evaluationPolicyId(), early.evaluationPolicyVersion(), early.features(), early.requirements(),
                early.overflowPermitted(), 400L);
        assertEquals(List.of(StateMismatch.PROCESSED_CUTOFF_NEWER),
                store.snapshot(policy(), demo()).mismatches(earlyCutoff, policy().definition()));
    }

    @Test
    void aCorrectionOfAnAppliedRewardRequiresARebuildAndChangesNothing() {
        var store = bound();
        var sample = Sample.of("sub-a", 1);
        sample.deliverTo(store, "exp-1", 20, Kind.PASS);
        var before = store.cohorts(demo());
        var corrected = observation(sample.request, sample.decision, "exp-1", 1, 30, Feature.known(20L), Kind.FAIL);
        var update = deliver(store, corrected, sample.decision, sample.request);
        var rebuild = assertInstanceOf(StateUpdate.RebuildRequired.class, update);
        assertEquals(20L, rebuild.advice().affectedFromSequence());
        assertFalse(update.examined());
        assertEquals(before, store.cohorts(demo()));
        assertEquals(20L, store.processedCutoff());
        assertNotEquals(List.of(), store.ledger());
    }
}
