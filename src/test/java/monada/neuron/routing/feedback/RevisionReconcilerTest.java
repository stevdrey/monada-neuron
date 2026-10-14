package monada.neuron.routing.feedback;

import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static monada.neuron.routing.feedback.FeedbackFixtures.NODE_A;
import static monada.neuron.routing.feedback.FeedbackFixtures.obs;
import static monada.neuron.routing.feedback.FeedbackFixtures.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RevisionReconcilerTest {

    private static final double EPS = 1e-12;

    private final RevisionReconciler reconciler = new RevisionReconciler();
    private final FeedbackFixtures.Spec spec = new FeedbackFixtures.Spec();

    private FeedbackDerivation derive(RoutingFeedbackPolicy policy, FeedbackFixtures.Spec s) {
        return policy.derive(s.observation(), s.decision, s.request,
                FeedbackFixtures.bindings(s.decision, s.request, NODE_A));
    }

    private static AppliedRevision applied(FeedbackDerivation derivation) {
        return AppliedRevision.of(((FeedbackDerivation.Derived) derivation).feedback());
    }

    @Test
    void aFirstDeliveryIsAppliedAndARepeatIsADuplicate() {
        spec.passing();
        var derivation = derive(policy(), spec);
        assertEquals(new Reconciliation.Apply(Optional.empty()),
                reconciler.reconcile(List.of(), spec.observation(), derivation));

        var ledger = List.of(applied(derivation));
        assertEquals(new Reconciliation.AlreadyApplied(ledger.getFirst()),
                reconciler.reconcile(ledger, spec.observation(), derivation));
    }

    @Test
    void theSameRevisionWithAnotherScoreOrIdentityIsAConflict() {
        spec.passing();
        var ledger = List.of(applied(derive(policy(), spec)));
        // same sample revision, different scoring configuration under the same mappingVersion
        var other = new RoutingFeedbackPolicy(FeedbackFixtures.DEFINITION,
                new RoutingFeedbackConfig(0.6, -0.3, 0.0, Optional.empty()), FeedbackFixtures.GATES);
        assertEquals(new Reconciliation.ConflictingRevision(ledger.getFirst()),
                reconciler.reconcile(ledger, spec.observation(), derive(other, spec)));
        // same sample revision at another ledger position
        var moved = new FeedbackFixtures.Spec().passing();
        moved.ledgerSequence = 20L;
        assertEquals(new Reconciliation.ConflictingRevision(ledger.getFirst()),
                reconciler.reconcile(ledger, moved.observation(), derive(policy(), moved)));
        // same revision that now derives nothing
        var none = new FeedbackDerivation.NoUpdate(NoUpdateReason.NO_STAGE_ATTRIBUTION, List.of(), List.of());
        assertEquals(new Reconciliation.ConflictingRevision(ledger.getFirst()),
                reconciler.reconcile(ledger, spec.observation(), none));
    }

    @Test
    void theSameRevisionFromAnotherRoutingPolicyIsAConflictNotADuplicate() {
        spec.passing();
        var derived = (FeedbackDerivation.Derived) derive(policy(), spec);
        var entry = AppliedRevision.of(derived.feedback());
        assertEquals(derived.feedback().routingPolicyId(), entry.routingPolicyId());
        assertEquals(derived.feedback().routingPolicyVersion(), entry.routingPolicyVersion());
        var otherPolicy = new AppliedRevision(entry.identity(), entry.routingPolicyId(), "other-version",
                entry.supersedesSequence(), entry.cohortKey(), entry.targetNodeId(), entry.disposition(), entry.score());
        assertEquals(new Reconciliation.ConflictingRevision(otherPolicy),
                reconciler.reconcile(List.of(otherPolicy), spec.observation(), derived));
    }

    @Test
    void theSameRevisionWithDifferentCorrectionAncestryIsAConflict() {
        var first = new FeedbackFixtures.Spec().passing();
        first.revision = 1;
        first.supersedes = Feature.known(3L);
        var second = new FeedbackFixtures.Spec().passing();
        second.revision = 1;
        second.supersedes = Feature.known(4L);
        var ledger = List.of(applied(derive(policy(), first)));
        assertEquals(Feature.known(3L), ledger.getFirst().supersedesSequence());
        assertEquals(new Reconciliation.ConflictingRevision(ledger.getFirst()),
                reconciler.reconcile(ledger, second.observation(), derive(policy(), second)));
    }

    @Test
    void aFeedbackFromAnotherLedgerPositionOfTheSameRevisionDoesNotAnswerTheObservation() {
        spec.passing();
        var derivation = derive(policy(), spec);
        var moved = new FeedbackFixtures.Spec().passing();
        moved.ledgerSequence = 20L;
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile(List.of(), moved.observation(), derivation));
        var otherSupersedes = new FeedbackFixtures.Spec().passing();
        otherSupersedes.revision = 1;
        otherSupersedes.supersedes = Feature.known(3L);
        var withSupersedes = new FeedbackFixtures.Spec().passing();
        withSupersedes.revision = 1;
        withSupersedes.supersedes = Feature.known(4L);
        var supersedesDerivation = derive(policy(), withSupersedes);
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile(List.of(), otherSupersedes.observation(), supersedesDerivation));
    }

    @Test
    void theSameObservationCreditingAnotherNodeIsAConflict() {
        spec.passing();
        var key = FeedbackFixtures.keyOf(spec.decision, spec.request);
        var otherBinding = new TargetBindings("scope-demo", "forge-gates", "1",
                FeedbackFixtures.DEFINITION.mappingVersion(),
                List.of(new TargetBinding(key, FeedbackFixtures.NODE_B)));
        var ledger = List.of(applied(derive(policy(), spec)));
        assertEquals(Optional.of(NODE_A), ledger.getFirst().targetNodeId());
        var rebound = policy().derive(spec.observation(), spec.decision, spec.request, otherBinding);
        assertEquals(new Reconciliation.ConflictingRevision(ledger.getFirst()),
                reconciler.reconcile(ledger, spec.observation(), rebound));
    }

    @Test
    void anObservationOfAnotherStatePartitionIsNeverRecorded() {
        var r2 = corrected(spec.passing(), 2, 40L).passing();
        for (var reason : List.of(NoUpdateReason.MAPPING_MISMATCH, NoUpdateReason.BINDING_SCOPE_MISMATCH,
                NoUpdateReason.BINDING_PARTITION_MISMATCH)) {
            var none = new FeedbackDerivation.NoUpdate(reason, List.of(), List.of());
            assertEquals(new Reconciliation.OutOfPartition(reason),
                    reconciler.reconcile(List.of(), r2.observation(), none));
            // a full ledger neither overflows nor is touched
            assertEquals(new Reconciliation.OutOfPartition(reason),
                    reconciler.reconcile(ledgerOf(RevisionReconciler.MAX_LEDGER), r2.observation(), none));
        }
        // an unbound cohort of this partition is still recorded
        var unbound = new FeedbackDerivation.NoUpdate(NoUpdateReason.NO_TARGET_BINDING, List.of(), List.of());
        assertInstanceOf(Reconciliation.RecordOnly.class, reconciler.reconcile(List.of(), r2.observation(), unbound));
        assertThrows(IllegalArgumentException.class,
                () -> new Reconciliation.OutOfPartition(NoUpdateReason.NO_TARGET_BINDING));
    }

    @Test
    void aCorrectionMustSitAtALaterLedgerPositionThanTheRevisionItReplaces() {
        var first = new FeedbackFixtures.Spec().passing();
        first.ledgerSequence = 41L;
        var ledger = List.of(applied(derive(policy(), first)));
        for (long sequence : new long[] {41L, 30L}) {
            var invalid = corrected(first, 1, sequence).passing();
            invalid.supersedes = Feature.known(5L);
            assertThrows(IllegalArgumentException.class,
                    () -> reconciler.reconcile(ledger, invalid.observation(), derive(policy(), invalid)));
        }
        // an older revision at a later position is just as inconsistent
        var current = new FeedbackFixtures.Spec().passing();
        current.revision = 1;
        current.ledgerSequence = 30L;
        current.supersedes = Feature.known(12L);
        var currentLedger = List.of(applied(derive(policy(), current)));
        var late = new FeedbackFixtures.Spec().passing();
        late.ledgerSequence = 35L;
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile(currentLedger, late.observation(), derive(policy(), late)));
    }

    @Test
    void anOlderRevisionArrivingLateIsStale() {
        var current = new FeedbackFixtures.Spec().passing();
        current.revision = 1;
        current.ledgerSequence = 30L;
        current.supersedes = Feature.known(12L);
        var ledger = List.of(applied(derive(policy(), current)));
        spec.passing();
        assertEquals(new Reconciliation.StaleRevision(ledger.getFirst()),
                reconciler.reconcile(ledger, spec.observation(), derive(policy(), spec)));
    }

    private FeedbackFixtures.Spec corrected(FeedbackFixtures.Spec base, long revision, long ledgerSequence) {
        var next = new FeedbackFixtures.Spec(base.request, base.decision);
        next.experienceRef = base.experienceRef;
        next.revision = revision;
        next.ledgerSequence = ledgerSequence;
        next.supersedes = Feature.known(base.ledgerSequence);
        return next;
    }

    @Test
    void aSupersededNeutralIsReplacedWithoutARebuild() {
        var first = new FeedbackFixtures.Spec();
        var neutral = derive(policy(), first);
        var ledger = List.of(applied(neutral));
        var next = corrected(first, 1, 30L).passing();
        assertEquals(new Reconciliation.Apply(Optional.of(ledger.getFirst())),
                reconciler.reconcile(ledger, next.observation(), derive(policy(), next)));
        // a corrected revision that derives nothing replaces a neutral entry with a marker
        var none = new FeedbackDerivation.NoUpdate(NoUpdateReason.CONTRADICTORY_EVIDENCE, List.of(), List.of());
        assertEquals(new Reconciliation.RecordOnly(AppliedRevision.ofNoUpdate(next.observation()),
                Optional.of(ledger.getFirst())), reconciler.reconcile(ledger, next.observation(), none));
    }

    @Test
    void aSupersededRewardRequiresAResetAndRebuildWhateverTheCorrectionSays() {
        var first = new FeedbackFixtures.Spec().passing();
        first.ledgerSequence = 41L;
        var ledger = List.of(applied(derive(policy(), first)));

        // contract section 9.5: a later mandatory FAIL turns the reward into a penalty
        var failed = corrected(first, 1, 58L).passing()
                .with(obs("ev-tests-late", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 50));
        var penalty = derive(policy(), failed);
        assertEquals(FeedbackDisposition.PENALIZE, ((FeedbackDerivation.Derived) penalty).feedback().disposition());
        assertEquals(new Reconciliation.RebuildRequired(ledger.getFirst(), 41L),
                reconciler.reconcile(ledger, failed.observation(), penalty));

        // an UNKNOWN correction turns it neutral: still a rebuild, the reward cannot be subtracted
        var unknown = corrected(first, 1, 58L).passing()
                .with(obs("ev-tests-late", 0, QualityDimension.TESTS, QualityState.UNKNOWN, "att-1", 50));
        var neutral = derive(policy(), unknown);
        assertEquals(FeedbackDisposition.NEUTRAL, ((FeedbackDerivation.Derived) neutral).feedback().disposition());
        assertInstanceOf(Reconciliation.RebuildRequired.class,
                reconciler.reconcile(ledger, unknown.observation(), neutral));

        // a correction that is now unusable derives nothing, yet the applied reward is obsolete
        var none = new FeedbackDerivation.NoUpdate(NoUpdateReason.CONTRADICTORY_EVIDENCE, List.of(), List.of());
        assertInstanceOf(Reconciliation.RebuildRequired.class,
                reconciler.reconcile(ledger, failed.observation(), none));
    }

    @Test
    void theAffectedSequenceIsTheEarliestSupersededPositionSoCheckpointsStayHonest() {
        var first = new FeedbackFixtures.Spec().passing();
        first.ledgerSequence = 41L;
        var ledger = List.of(applied(derive(policy(), first)));
        var failed = corrected(first, 1, 58L).passing()
                .with(obs("ev-tests-late", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 50));
        failed.supersedes = Feature.known(30L);
        var result = assertInstanceOf(Reconciliation.RebuildRequired.class,
                reconciler.reconcile(ledger, failed.observation(), derive(policy(), failed)));
        assertEquals(30L, result.affectedFromSequence());
        failed.supersedes = Feature.known(45L);
        result = assertInstanceOf(Reconciliation.RebuildRequired.class,
                reconciler.reconcile(ledger, failed.observation(), derive(policy(), failed)));
        assertEquals(41L, result.affectedFromSequence());
    }

    @Test
    void aNewSampleWithNothingToApplyIsRecordedAsAMarkerThatKeepsOlderDeliveriesStale() {
        var none = new FeedbackDerivation.NoUpdate(NoUpdateReason.NO_STAGE_ATTRIBUTION, List.of(), List.of());
        var r2 = corrected(spec.passing(), 2, 40L).passing();
        var result = assertInstanceOf(Reconciliation.RecordOnly.class,
                reconciler.reconcile(List.of(), r2.observation(), none));
        assertEquals(AppliedRevision.ofNoUpdate(r2.observation()), result.marker());
        assertEquals(Optional.empty(), result.marker().cohortKey());
        assertEquals(FeedbackDisposition.NEUTRAL, result.marker().disposition());
        // a delayed older revision of the same sample is now stale instead of a fresh Apply
        var ledger = List.of(result.marker());
        var r1 = corrected(spec, 1, 30L).passing();
        assertEquals(new Reconciliation.StaleRevision(result.marker()),
                reconciler.reconcile(ledger, r1.observation(), derive(policy(), r1)));
        // redelivery of the same no-update is a duplicate; a derived feedback at that revision conflicts
        assertEquals(new Reconciliation.AlreadyApplied(result.marker()),
                reconciler.reconcile(ledger, r2.observation(), none));
        assertInstanceOf(Reconciliation.ConflictingRevision.class,
                reconciler.reconcile(ledger, r2.observation(), derive(policy(), r2)));
        // a full ledger has no slot for a new marker either
        assertEquals(new Reconciliation.CapacityExhausted(), reconciler.reconcile(
                ledgerOf(RevisionReconciler.MAX_LEDGER), r2.observation(), none));
    }

    @Test
    void aFeedbackDerivedFromAnotherOutcomeDoesNotAnswerTheObservation() {
        spec.passing();
        var derivation = derive(policy(), spec);
        // same reference, revision, ledger position and ancestry, but another decision and route
        var otherRequest = FeedbackFixtures.request("scope-demo", "task-99", "exec-9", "att-1", "s-other", 900L);
        var other = new FeedbackFixtures.Spec(otherRequest, FeedbackFixtures.decide(otherRequest, "sub-b")).passing();
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile(List.of(), other.observation(), derivation));
        // same decision and route, but another executed-route catalog/policy view of the outcome
        var otherCutoff = new FeedbackFixtures.Spec().passing();
        otherCutoff.cutoff = 200L;
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile(List.of(), otherCutoff.observation(), derivation));
    }

    // ---- capacity and argument validation ----

    private List<AppliedRevision> ledgerOf(int size) {
        var base = ((FeedbackDerivation.Derived) derive(policy(), new FeedbackFixtures.Spec().passing())).feedback();
        var ledger = new ArrayList<AppliedRevision>();
        for (int i = 0; i < size; i++) {
            var id = base.identity();
            ledger.add(new AppliedRevision(new FeedbackIdentity("ref-" + i, 0, 12L, id.decisionRef(), id.route(),
                    id.mappingVersion(), id.evaluationPolicyId(), id.evaluationPolicyVersion()),
                    base.routingPolicyId(), base.routingPolicyVersion(), base.supersedesSequence(), Optional.of(base.cohortKey()), Optional.of(NODE_A),
                    FeedbackDisposition.REINFORCE, 0.8));
        }
        return ledger;
    }

    @Test
    void aFullLedgerReportsCapacityExhaustedForANewSampleOnly() {
        var ledger = ledgerOf(RevisionReconciler.MAX_LEDGER);
        spec.passing();
        assertEquals(new Reconciliation.CapacityExhausted(),
                reconciler.reconcile(ledger, spec.observation(), derive(policy(), spec)));
        // a sample that is already in the ledger needs no new slot: this exact revision is a duplicate delivery
        var known = new FeedbackFixtures.Spec().passing();
        known.experienceRef = "ref-7";
        assertInstanceOf(Reconciliation.AlreadyApplied.class,
                reconciler.reconcile(ledger, known.observation(), derive(policy(), known)));
        // one below the bound still has room
        assertEquals(new Reconciliation.Apply(Optional.empty()), reconciler.reconcile(
                ledgerOf(RevisionReconciler.MAX_LEDGER - 1), spec.observation(), derive(policy(), spec)));
    }

    @Test
    void anInvalidLedgerOrAMismatchedFeedbackIsAContractViolation() {
        spec.passing();
        var derivation = derive(policy(), spec);
        assertThrows(IllegalArgumentException.class, () -> reconciler.reconcile(
                ledgerOf(RevisionReconciler.MAX_LEDGER + 1), spec.observation(), derivation));
        var twice = ledgerOf(1);
        var duplicated = List.of(twice.getFirst(), twice.getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile(duplicated, spec.observation(), derivation));
        var other = new FeedbackFixtures.Spec().passing();
        other.experienceRef = "exp-other";
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile(List.of(), other.observation(), derivation));
    }

    // ---- reset and rebuild: a rebuilt state equals one built from scratch ----

    private static Node baselineNode() {
        return new Node.Builder().id(NODE_A).frequencyState(new FrequencyState(4.0, 50.0, 0.5)).energy(10.0)
                .type(NodeType.PROCESSOR).build();
    }

    private static final DeterministicBaselineAdaptationPolicy ADAPTATION = new DeterministicBaselineAdaptationPolicy(
            new AdaptationConfig(0.5, 0.5, 100.0, 0.0, 1000.0, 0.0, 10000.0, 2.0));

    /** Derives and applies the observations in the given order to a fresh baseline Node. */
    private Node replay(List<RoutingObservation> ordered, java.util.function.Function<RoutingObservation,
            FeedbackFixtures.Spec> specs) {
        var node = baselineNode();
        for (var observation : ordered) {
            var s = specs.apply(observation);
            var derivation = derive(policy(), s);
            if (derivation instanceof FeedbackDerivation.Derived derived) {
                derived.feedback().toFeedbackInputs().forEach(input -> ADAPTATION.adapt(node, input));
            }
        }
        return node;
    }

    @Test
    void resetAndReplayInAppliedOrderEqualsABuildFromScratchOnTheCorrectedExport() {
        // X2 rewarded at 45; X1 rewarded at 41 and later corrected (revision 1 at 58) into a proven failure.
        var x1r0 = new FeedbackFixtures.Spec().passing();
        x1r0.experienceRef = "x1";
        x1r0.ledgerSequence = 41L;
        var x2request = FeedbackFixtures.request("scope-demo", "task-18", "exec-2", "att-1", "s-impl-2", 102L);
        var x2 = new FeedbackFixtures.Spec(x2request, FeedbackFixtures.decide(x2request, "sub-a")).passing();
        x2.experienceRef = "x2";
        x2.ledgerSequence = 45L;
        var x1r1 = corrected(x1r0, 1, 58L).passing()
                .with(obs("ev-tests-late", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 50));
        x1r1.status = OutcomeStatus.ACCEPTED;

        java.util.function.Function<RoutingObservation, FeedbackFixtures.Spec> specs = o ->
                o.experienceRef().equals("x2") ? x2 : (o.revision() == 0 ? x1r0 : x1r1);

        // the incremental path before the correction: X1 reward, then X2 reward
        var incremental = replay(List.of(x1r0.observation(), x2.observation()), specs);
        // 4.0 * 1.4 * 1.4 = 7.84; energy 10 + 0.8 + 0.8 = 11.6
        assertEquals(7.84, incremental.getFrequencyState().amplitude(), EPS);
        assertEquals(11.6, incremental.getEnergy(), EPS);

        // the correction supersedes an applied reward
        var ledger = List.of(applied(derive(policy(), x1r0)), applied(derive(policy(), x2)));
        assertInstanceOf(Reconciliation.RebuildRequired.class,
                reconciler.reconcile(ledger, x1r1.observation(), derive(policy(), x1r1)));

        // reset to baseline and replay the effective observations in applied order: X2@45 then X1r1@58
        var export = new ArrayList<>(List.of(x1r0.observation(), x2.observation(), x1r1.observation()));
        var ordered = new RebuildOrder().order(export);
        assertEquals(List.of("x2", "x1"), ordered.stream().map(RoutingObservation::experienceRef).toList());
        var rebuilt = replay(ordered, specs);
        // 4.0 * 1.4 = 5.6 (X2), then * (1 - 0.2) = 4.48 (X1 penalty); energy 10 + 0.8 - 0.4 = 10.4
        assertEquals(4.48, rebuilt.getFrequencyState().amplitude(), EPS);
        assertEquals(10.4, rebuilt.getEnergy(), EPS);
        assertNotEquals(incremental.getFrequencyState(), rebuilt.getFrequencyState());

        // a build from scratch from the same corrected export, in any input order, is bit-identical
        for (int i = 0; i < 6; i++) {
            Collections.rotate(export, 1);
            var again = replay(new RebuildOrder().order(export), specs);
            assertEquals(rebuilt.getFrequencyState(), again.getFrequencyState());
            assertEquals(rebuilt.getEnergy(), again.getEnergy());
        }
        Collections.reverse(export);
        assertEquals(ordered, new RebuildOrder().order(export));
    }

    @Test
    void routeKeyIsPartOfTheCohortSoARouteChangeNeverInheritsAnotherRoutesCredit() {
        var a = FeedbackFixtures.keyOf(spec.decision, spec.request);
        var b = new CohortKey(a.scopeId(), a.stageKind(), a.cohortBucket(), new RouteKey("sub-a", 2));
        assertNotEquals(a, b);
    }
}
