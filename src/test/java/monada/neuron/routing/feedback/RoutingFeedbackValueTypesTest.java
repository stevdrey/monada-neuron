package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;
import monada.neuron.model.FrequencyState;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.LearningAssessment;
import monada.neuron.routing.outcome.ProvenanceField;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.UsageKind;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import static monada.neuron.routing.feedback.FeedbackFixtures.NODE_A;
import static monada.neuron.routing.feedback.FeedbackFixtures.NODE_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingFeedbackValueTypesTest {

    private static final CostObjective COST = new CostObjective(UsageKind.INPUT_TOKENS,
            ResourceObjective.Direction.MINIMIZE);

    private static CohortKey key(String scope, String bucket, String route, long version) {
        return new CohortKey(scope, "implement", bucket, new RouteKey(route, version));
    }

    // ---- config ----

    @Test
    void configEnforcesTheOrderingInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedbackConfig(0.0, -0.1, 0.0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedbackConfig(1.1, -0.1, 0.0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedbackConfig(0.5, 0.0, 0.0, Optional.empty()));
        // a penalty is never harsher than the reward is strong
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedbackConfig(0.5, -0.6, 0.0, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingFeedbackConfig(Double.NaN, -0.1, 0.0, Optional.empty()));
        // cost can never push the reward above 1.0
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingFeedbackConfig(0.8, -0.4, 0.3, Optional.of(COST)));
        // a bonus needs an objective and an objective needs a bonus
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedbackConfig(0.8, -0.4, 0.1, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingFeedbackConfig(0.8, -0.4, 0.0, Optional.of(COST)));
        assertEquals(1.0, new RoutingFeedbackConfig(0.8, -0.4, 0.2, Optional.of(COST)).refinedReinforceScore());
        assertEquals(1.0, RoutingFeedbackConfig.DEFAULT.refinedReinforceScore());
    }

    // ---- bindings ----

    @Test
    void cohortKeysAreOrderedByScopeStageBucketAndRoute() {
        var keys = new ArrayList<>(List.of(key("s", "b", "r", 2), key("s", "b", "r", 1), key("s", "a", "z", 1),
                key("r", "z", "z", 9)));
        java.util.Collections.sort(keys);
        assertEquals(List.of(key("r", "z", "z", 9), key("s", "a", "z", 1), key("s", "b", "r", 1),
                key("s", "b", "r", 2)), keys);
    }

    @Test
    void bindingsAreCanonicalPointQueriedAndRejectInconsistentInput() {
        var b = new TargetBindings("s", "p", "1", "m", FeedbackFixtures.SCHEMA, List.of(new TargetBinding(key("s", "b", "r", 2), NODE_B),
                new TargetBinding(key("s", "a", "r", 1), NODE_A)));
        assertEquals(key("s", "a", "r", 1), b.bindings().getFirst().key());
        assertEquals(Optional.of(NODE_B), b.find(key("s", "b", "r", 2)));
        assertEquals(Optional.empty(), b.find(key("s", "b", "r", 3)));
        assertEquals(Optional.empty(), b.find(key("s", "c", "r", 1)));

        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m", FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(key("other", "a", "r", 1), NODE_A))));
        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m", FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(key("s", "a", "r", 1), NODE_A),
                        new TargetBinding(key("s", "a", "r", 1), NODE_B))));
        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m", FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(key("s", "a", "r", 1), NODE_A),
                        new TargetBinding(key("s", "a", "r", 2), NODE_A))));
    }

    @Test
    void bindingsAreBoundedAt256() {
        var full = new ArrayList<TargetBinding>();
        for (int i = 0; i < TargetBindings.MAX_BINDINGS; i++) {
            full.add(new TargetBinding(key("s", "b", "r", i + 1), new UUID(1L, i)));
        }
        var ok = new TargetBindings("s", "p", "1", "m", FeedbackFixtures.SCHEMA, full);
        assertEquals(TargetBindings.MAX_BINDINGS, ok.bindings().size());
        assertEquals(Optional.of(new UUID(1L, 255)), ok.find(key("s", "b", "r", 256)));
        full.add(new TargetBinding(key("s", "b", "r", 999), new UUID(1L, 999)));
        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m", FeedbackFixtures.SCHEMA, full));
    }

    // ---- feedback invariants (built from a valid derived feedback) ----

    private static RoutingFeedback reward() {
        var s = new FeedbackFixtures.Spec().passing();
        return ((FeedbackDerivation.Derived) FeedbackFixtures.policy().derive(s.observation(), s.decision, s.request,
                FeedbackFixtures.bindings(s.decision, s.request, NODE_A))).feedback();
    }

    private static RoutingFeedback rebuilt(RoutingFeedback f, ScoreRule rule, FeedbackDisposition disposition,
            List<FeedbackEntry> entries, DerivedAcceptance acceptance, Feature<Long> supersedes) {
        return new RoutingFeedback(f.contractVersion(), f.identity(), supersedes, f.cohortKey(), f.routingPolicyId(),
                f.routingPolicyVersion(), f.catalogVersion(), f.cutoff(), acceptance, rule, disposition, entries,
                f.reasons(), f.effectiveEvidence(), f.provenFailures(), f.costPeer());
    }

    @Test
    void feedbackRejectsInconsistentRuleDispositionAndEntries() {
        var f = reward();
        var plus = List.of(FeedbackEntry.of(NODE_A, 0.8));
        var minus = List.of(FeedbackEntry.of(NODE_A, -0.4));
        var accepted = DerivedAcceptance.VALIDATED_ACCEPTED;
        var none = Feature.<Long>unknown();
        // the reference construction is valid
        rebuilt(f, ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, FeedbackDisposition.REINFORCE, plus, accepted, none);
        // rule and disposition must agree
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE,
                FeedbackDisposition.REINFORCE, plus, accepted, none));
        // sign must match the disposition
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                FeedbackDisposition.REINFORCE, minus, accepted, none));
        // neutral carries no entries; a reward credits exactly one Node
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.NEUTRAL_PENDING,
                FeedbackDisposition.NEUTRAL, plus, accepted, none));
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                FeedbackDisposition.REINFORCE, List.of(), accepted, none));
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                FeedbackDisposition.REINFORCE, List.of(FeedbackEntry.of(NODE_A, 0.8), FeedbackEntry.of(NODE_B, 0.8)),
                accepted, none));
        // a reward needs validated acceptance; a penalty needs a proven failure
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                FeedbackDisposition.REINFORCE, plus, DerivedAcceptance.ACCEPTED_UNVALIDATED, none));
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE,
                FeedbackDisposition.PENALIZE, minus, DerivedAcceptance.NOT_ACCEPTED, none));
        // a superseded sequence lies strictly before the revision's own position
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                FeedbackDisposition.REINFORCE, plus, accepted, Feature.known(f.identity().ledgerSequence())));
    }

    private static RoutingFeedback penalty() {
        var s = new FeedbackFixtures.Spec().failing();
        return ((FeedbackDerivation.Derived) FeedbackFixtures.policy().derive(s.observation(), s.decision, s.request,
                FeedbackFixtures.bindings(s.decision, s.request, NODE_A))).feedback();
    }

    private static RoutingFeedback penaltyWith(RoutingFeedback f, DerivedAcceptance acceptance,
            List<IneligibilityReason> reasons, List<QualityDimension> failures,
            List<FeedbackEntry> entries) {
        return new RoutingFeedback(f.contractVersion(), f.identity(), f.supersedesSequence(), f.cohortKey(),
                f.routingPolicyId(), f.routingPolicyVersion(), f.catalogVersion(), f.cutoff(), acceptance,
                ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE, FeedbackDisposition.PENALIZE, entries, reasons,
                f.effectiveEvidence(), failures, f.costPeer());
    }

    @Test
    void routingEntriesAreScoreOnly() {
        var f = reward();
        var signal = new Signal(SignalKind.FEEDBACK,
                new FrequencyState(1.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> rebuilt(f, ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                FeedbackDisposition.REINFORCE, List.of(new FeedbackEntry(NODE_A, signal, 0.8)),
                DerivedAcceptance.VALIDATED_ACCEPTED, Feature.unknown()));
        assertTrue(f.toFeedbackInputs().stream().allMatch(i -> i.targetSignal() == null));
    }

    @Test
    void aPenaltyMirrorsTheNegativeCandidateInvariants() {
        var p = penalty();
        var minus = List.of(FeedbackEntry.of(NODE_A, -0.4));
        // the derived penalty is valid
        penaltyWith(p, p.derivedAcceptance(), p.reasons(), p.provenFailures(), minus);
        // blocking reasons are rejected
        assertThrows(IllegalArgumentException.class, () -> penaltyWith(p, p.derivedAcceptance(),
                List.of(IneligibilityReason.PROVENANCE_MISMATCH), p.provenFailures(), minus));
        assertThrows(IllegalArgumentException.class, () -> penaltyWith(p, p.derivedAcceptance(),
                List.of(IneligibilityReason.PENDING), p.provenFailures(), minus));
        // failures must be the effective FAIL dimensions: fabricated, and dropped
        assertThrows(IllegalArgumentException.class, () -> penaltyWith(p, p.derivedAcceptance(), p.reasons(),
                List.of(QualityDimension.TESTS,
                        QualityDimension.SECURITY), minus));
        assertThrows(IllegalArgumentException.class, () -> penaltyWith(p, p.derivedAcceptance(), p.reasons(),
                List.of(), minus));
        // a penalty cannot follow validated or pending acceptance
        assertThrows(IllegalArgumentException.class,
                () -> penaltyWith(p, DerivedAcceptance.VALIDATED_ACCEPTED, p.reasons(), p.provenFailures(), minus));
        assertThrows(IllegalArgumentException.class,
                () -> penaltyWith(p, DerivedAcceptance.PENDING, p.reasons(), p.provenFailures(), minus));
    }

    @Test
    void aPenaltyCannotCarryReasonsThatMakeThePolicyNeutralOrDeriveNothing() {
        var p = penalty();
        var minus = List.of(FeedbackEntry.of(NODE_A, -0.4));
        for (var reason : List.of(IneligibilityReason.IMPORTED_CLAIM, IneligibilityReason.NO_STAGE_ATTRIBUTION,
                IneligibilityReason.NO_ATTRIBUTABLE_FAILURE)) {
            assertThrows(IllegalArgumentException.class, () -> penaltyWith(p, p.derivedAcceptance(),
                    List.of(reason), p.provenFailures(), minus));
        }
    }

    private static RoutingFeedback rewardWith(RoutingFeedback f, long cutoff, long ledgerSequence,
            List<LearningAssessment.EffectiveEvidence> evidence) {
        var id = f.identity();
        return new RoutingFeedback(f.contractVersion(),
                new FeedbackIdentity(id.experienceRef(), id.revision(), ledgerSequence, id.decisionRef(), id.route(),
                        id.mappingVersion(), id.evaluationPolicyId(), id.evaluationPolicyVersion()),
                f.supersedesSequence(), f.cohortKey(), f.routingPolicyId(), f.routingPolicyVersion(),
                f.catalogVersion(), cutoff, f.derivedAcceptance(), f.rule(), f.disposition(), f.entries(),
                f.reasons(), evidence, f.provenFailures(), f.costPeer());
    }

    @Test
    void aRewardNeedsPassingEffectiveEvidence() {
        var f = reward();
        var passing = f.effectiveEvidence();
        rewardWith(f, f.cutoff(), f.identity().ledgerSequence(), passing);
        assertThrows(IllegalArgumentException.class,
                () -> rewardWith(f, f.cutoff(), f.identity().ledgerSequence(), List.of()));
        var unknown = new LearningAssessment.EffectiveEvidence(
                QualityDimension.TESTS,
                QualityState.UNKNOWN, 5L);
        assertThrows(IllegalArgumentException.class,
                () -> rewardWith(f, f.cutoff(), f.identity().ledgerSequence(), List.of(unknown)));
    }

    @Test
    void evidenceCannotBeLaterThanTheSampleThatUsesIt() {
        var f = reward();
        long sequence = f.identity().ledgerSequence();
        var future = new LearningAssessment.EffectiveEvidence(
                QualityDimension.TESTS,
                QualityState.PASS, sequence + 1);
        // still within the cutoff, but after the sample's own position
        assertThrows(IllegalArgumentException.class,
                () -> rewardWith(f, sequence + 10, sequence, List.of(future)));
    }

    @Test
    void aCostPeerViewIsRecordedForARewardAndAlwaysForACostRefinedOne() {
        var f = reward();
        // a reward that consulted a peer but earned no bonus still records the view of it, wherever the peer sits
        var consulted = Optional.of(new PeerRevision("exp-2", 0, 99L));
        new RoutingFeedback(f.contractVersion(), f.identity(), f.supersedesSequence(), f.cohortKey(),
                f.routingPolicyId(), f.routingPolicyVersion(), f.catalogVersion(), f.cutoff(), f.derivedAcceptance(),
                f.rule(), f.disposition(), f.entries(), f.reasons(), f.effectiveEvidence(), f.provenFailures(),
                consulted);
        // only a reward consults one
        var p = penalty();
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedback(p.contractVersion(), p.identity(),
                p.supersedesSequence(), p.cohortKey(), p.routingPolicyId(), p.routingPolicyVersion(),
                p.catalogVersion(), p.cutoff(), p.derivedAcceptance(), p.rule(), p.disposition(), p.entries(),
                p.reasons(), p.effectiveEvidence(), p.provenFailures(), consulted));
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedback(f.contractVersion(), f.identity(),
                f.supersedesSequence(), f.cohortKey(), f.routingPolicyId(), f.routingPolicyVersion(),
                f.catalogVersion(), f.cutoff(), f.derivedAcceptance(), ScoreRule.REINFORCE_COST_REFINED,
                f.disposition(), f.entries(), f.reasons(), f.effectiveEvidence(), f.provenFailures(),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new PeerRevision("exp", -1, 0L));
    }

    private static RoutingFeedback neutralWith(RoutingFeedback f, ScoreRule rule, DerivedAcceptance acceptance,
            List<IneligibilityReason> reasons) {
        return new RoutingFeedback(f.contractVersion(), f.identity(), f.supersedesSequence(), f.cohortKey(),
                f.routingPolicyId(), f.routingPolicyVersion(), f.catalogVersion(), f.cutoff(), acceptance, rule,
                FeedbackDisposition.NEUTRAL, List.of(), reasons, f.effectiveEvidence(), List.of(), Optional.empty());
    }

    @Test
    void aNeutralRuleMustMatchTheRecordedAssessmentAndPrecedence() {
        var f = reward();
        var accepted = DerivedAcceptance.VALIDATED_ACCEPTED;
        // a pending rule over a validated, reason-free assessment would suppress a reward the policy derives
        assertThrows(IllegalArgumentException.class, () -> neutralWith(f, ScoreRule.NEUTRAL_PENDING, accepted, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> neutralWith(f, ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE, accepted, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> neutralWith(f, ScoreRule.NEUTRAL_UNVALIDATED_ACCEPTANCE, accepted, List.of()));
        // the matching assessments are accepted
        neutralWith(f, ScoreRule.NEUTRAL_PENDING, DerivedAcceptance.PENDING, List.of(IneligibilityReason.PENDING));
        neutralWith(f, ScoreRule.NEUTRAL_UNVALIDATED_ACCEPTANCE, DerivedAcceptance.ACCEPTED_UNVALIDATED, List.of());
        neutralWith(f, ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE, DerivedAcceptance.ACCEPTED_UNVALIDATED,
                List.of(IneligibilityReason.MISSING_EVIDENCE));
        // an earlier rule takes precedence: a pending outcome is not "incomplete evidence"
        assertThrows(IllegalArgumentException.class, () -> neutralWith(f, ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE,
                DerivedAcceptance.PENDING, List.of(IneligibilityReason.PENDING, IneligibilityReason.MISSING_EVIDENCE)));
        // reasons for which the policy derives nothing never appear on a derived feedback
        assertThrows(IllegalArgumentException.class, () -> neutralWith(f, ScoreRule.NEUTRAL_PENDING,
                DerivedAcceptance.PENDING, List.of(IneligibilityReason.PENDING, IneligibilityReason.CONTRADICTORY_EVIDENCE)));
        // an unattributed result is a no-update, so the late neutral rules cannot carry that reason
        assertThrows(IllegalArgumentException.class, () -> neutralWith(f, ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE,
                DerivedAcceptance.ACCEPTED_UNVALIDATED,
                List.of(IneligibilityReason.MISSING_EVIDENCE, IneligibilityReason.NO_STAGE_ATTRIBUTION)));
        assertTrue(!ScoreRule.REINFORCE_COST_REFINED.covers(accepted, List.of(), List.of()));
    }

    @Test
    void theCohortMustBelongToTheExecutedRouteAndScope() {
        var f = reward();
        var otherRoute = new CohortKey(f.cohortKey().scopeId(), f.cohortKey().stageKind(),
                f.cohortKey().cohortBucket(), new RouteKey("sub-z", 1));
        var otherScope = new CohortKey("scope-other", f.cohortKey().stageKind(), f.cohortKey().cohortBucket(),
                f.cohortKey().route());
        for (var key : List.of(otherRoute, otherScope)) {
            assertThrows(IllegalArgumentException.class, () -> new RoutingFeedback(f.contractVersion(), f.identity(),
                    f.supersedesSequence(), key, f.routingPolicyId(), f.routingPolicyVersion(), f.catalogVersion(),
                    f.cutoff(), f.derivedAcceptance(), f.rule(), f.disposition(), f.entries(), f.reasons(),
                    f.effectiveEvidence(), f.provenFailures(), f.costPeer()));
        }
    }

    @Test
    void aMarkerKeepsTheOutcomeProvenanceThatDistinguishesTwoClaims() {
        var base = new FeedbackFixtures.Spec().passing();
        var other = new FeedbackFixtures.Spec().passing();
        other.cutoff = 200L;
        var reason = NoUpdateReason.NO_STAGE_ATTRIBUTION;
        assertEquals(AppliedRevision.ofNoUpdate(base.observation(), reason),
                AppliedRevision.ofNoUpdate(base.observation(), reason));
        assertNotEquals(AppliedRevision.ofNoUpdate(base.observation(), reason),
                AppliedRevision.ofNoUpdate(other.observation(), reason));
        assertNotEquals(AppliedRevision.ofNoUpdate(base.observation(), reason),
                AppliedRevision.ofNoUpdate(base.observation(), NoUpdateReason.NO_TARGET_BINDING));
        var marker = AppliedRevision.ofNoUpdate(base.observation(), reason);
        assertEquals(base.observation().outcome().catalogVersion(), marker.catalogVersion());
        // a marker is neutral and moves no Node
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(marker, Optional.of(NODE_A),
                Optional.empty(), marker.basis(), FeedbackDisposition.REINFORCE, 0.5));
        // the unrecorded no-update reasons cannot form a marker
        assertThrows(IllegalArgumentException.class, () -> AppliedRevision.ofNoUpdate(base.observation(),
                NoUpdateReason.PROVENANCE_MISMATCH));
    }

    @Test
    void aCostPeerMustNotLieBeyondTheCutoffNorAttachToANonRewardLedgerEntry() {
        var f = reward();
        long position = f.identity().ledgerSequence();
        var future = Optional.of(new PeerRevision("exp-2", 0, position + 1));
        var samePositionLaterRef = Optional.of(new PeerRevision("exp-2", 0, position));
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedback(f.contractVersion(), f.identity(),
                f.supersedesSequence(), f.cohortKey(), f.routingPolicyId(), f.routingPolicyVersion(),
                f.catalogVersion(), f.cutoff(), f.derivedAcceptance(), ScoreRule.REINFORCE_COST_REFINED,
                f.disposition(), f.entries(), f.reasons(), f.effectiveEvidence(), f.provenFailures(), future));
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedback(f.contractVersion(), f.identity(),
                f.supersedesSequence(), f.cohortKey(), f.routingPolicyId(), f.routingPolicyVersion(),
                f.catalogVersion(), f.cutoff(), f.derivedAcceptance(), ScoreRule.REINFORCE_COST_REFINED,
                f.disposition(), f.entries(), f.reasons(), f.effectiveEvidence(), f.provenFailures(),
                samePositionLaterRef));
        var onTime = Optional.of(new PeerRevision("exp-2", 0, position - 1));
        new RoutingFeedback(f.contractVersion(), f.identity(), f.supersedesSequence(), f.cohortKey(),
                f.routingPolicyId(), f.routingPolicyVersion(), f.catalogVersion(), f.cutoff(), f.derivedAcceptance(),
                ScoreRule.REINFORCE_COST_REFINED, f.disposition(), f.entries(), f.reasons(), f.effectiveEvidence(),
                f.provenFailures(), onTime);
        // a neutral marker, or a penalty, cannot carry a cost dependency
        var entry = AppliedRevision.of(f);
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(entry, Optional.empty(), onTime,
                new EntryBasis.NoUpdateMarker(NoUpdateReason.NO_STAGE_ATTRIBUTION,
                        new FeedbackFixtures.Spec().observation().outcome().cohortBinding()),
                FeedbackDisposition.NEUTRAL, 0.0));
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(entry, Optional.of(NODE_A),
                onTime, entry.basis(), FeedbackDisposition.PENALIZE, -0.4));
    }

    @Test
    void aCostRefinedRewardCannotBeComparedAgainstItsOwnSample() {
        var f = reward();
        var self = Optional.of(new PeerRevision(f.identity().experienceRef(), 0, f.identity().ledgerSequence() - 1));
        assertThrows(IllegalArgumentException.class, () -> new RoutingFeedback(f.contractVersion(), f.identity(),
                f.supersedesSequence(), f.cohortKey(), f.routingPolicyId(), f.routingPolicyVersion(),
                f.catalogVersion(), f.cutoff(), f.derivedAcceptance(), ScoreRule.REINFORCE_COST_REFINED,
                f.disposition(), f.entries(), f.reasons(), f.effectiveEvidence(), f.provenFailures(), self));
    }

    private static AppliedRevision entryWith(AppliedRevision base, FeedbackIdentity identity, long cutoff,
            Feature<Long> supersedes, EntryBasis basis, Optional<PeerRevision> peer) {
        return new AppliedRevision(identity, base.routingPolicyId(), base.routingPolicyVersion(), supersedes,
                base.catalogVersion(), cutoff, base.targetNodeId(), peer, basis, base.disposition(), base.score());
    }

    private static FeedbackIdentity withSequence(FeedbackIdentity id, long revision, long sequence) {
        return new FeedbackIdentity(id.experienceRef(), revision, sequence, id.decisionRef(), id.route(),
                id.mappingVersion(), id.evaluationPolicyId(), id.evaluationPolicyVersion());
    }

    @Test
    void aLedgerEntryMirrorsTheInvariantsOfTheFeedbackItRecords() {
        var base = AppliedRevision.of(reward());
        var id = base.identity();
        long position = id.ledgerSequence();
        // valid reference construction
        entryWith(base, id, base.cutoff(), base.supersedesSequence(), base.basis(), Optional.empty());
        // beyond its cutoff
        assertThrows(IllegalArgumentException.class, () -> entryWith(base, id, position - 1,
                base.supersedesSequence(), base.basis(), Optional.empty()));
        // ancestry follows the revision
        assertThrows(IllegalArgumentException.class, () -> entryWith(base, withSequence(id, 1, position), base.cutoff(),
                Feature.unknown(), base.basis(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> entryWith(base, id, base.cutoff(), Feature.known(3L),
                base.basis(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> entryWith(base, withSequence(id, 1, position), base.cutoff(),
                Feature.known(position), base.basis(), Optional.empty()));
        // the cohort belongs to the executed route and scope
        var derived = (EntryBasis.Derived) base.basis();
        var otherRoute = new EntryBasis.Derived(
                new CohortKey(derived.cohortKey().scopeId(), derived.cohortKey().stageKind(),
                        derived.cohortKey().cohortBucket(), new RouteKey("sub-z", 1)),
                derived.rule(), derived.acceptance(), derived.reasons());
        assertThrows(IllegalArgumentException.class, () -> entryWith(base, id, base.cutoff(),
                base.supersedesSequence(), otherRoute, Optional.empty()));
        // a marker's binding was recorded under the identity's mapping version
        var marker = AppliedRevision.ofNoUpdate(new FeedbackFixtures.Spec().passing().observation(),
                NoUpdateReason.NO_STAGE_ATTRIBUTION);
        var binding = ((EntryBasis.NoUpdateMarker) marker.basis()).cohortBinding();
        var foreignMarker = new EntryBasis.NoUpdateMarker(NoUpdateReason.NO_STAGE_ATTRIBUTION,
                new CohortBinding(binding.cohortBucket(), binding.bucketMappingVersion(), "routing-state/other",
                        binding.featureSchemaVersion()));
        assertThrows(IllegalArgumentException.class, () -> entryWith(marker, marker.identity(), marker.cutoff(),
                marker.supersedesSequence(), foreignMarker, Optional.empty()));
        // a cost peer is another sample that precedes the reward in applied order
        var refined = new EntryBasis.Derived(derived.cohortKey(), ScoreRule.REINFORCE_COST_REFINED,
                derived.acceptance(), derived.reasons());
        entryWith(base, id, base.cutoff(), base.supersedesSequence(), refined,
                Optional.of(new PeerRevision("exp-2", 0, position - 1)));
        assertThrows(IllegalArgumentException.class, () -> entryWith(base, id, base.cutoff(),
                base.supersedesSequence(), refined, Optional.of(new PeerRevision("exp-2", 0, position + 1))));
        assertThrows(IllegalArgumentException.class, () -> entryWith(base, id, base.cutoff(),
                base.supersedesSequence(), refined,
                Optional.of(new PeerRevision(id.experienceRef(), 0, position - 1))));
    }

    @Test
    void correctionAncestryFollowsTheRevision() {
        var f = reward();
        var id = f.identity();
        var plus = List.of(FeedbackEntry.of(NODE_A, 0.8));
        BiFunction<Long, Feature<Long>, RoutingFeedback> make = (revision, supersedes) ->
                new RoutingFeedback(f.contractVersion(),
                        new FeedbackIdentity(id.experienceRef(), revision, 30L, id.decisionRef(), id.route(),
                                id.mappingVersion(), id.evaluationPolicyId(), id.evaluationPolicyVersion()),
                        supersedes, f.cohortKey(), f.routingPolicyId(), f.routingPolicyVersion(), f.catalogVersion(),
                        f.cutoff(), f.derivedAcceptance(), f.rule(), f.disposition(), plus, f.reasons(),
                        f.effectiveEvidence(), f.provenFailures(), f.costPeer());
        make.apply(0L, Feature.unknown());
        make.apply(1L, Feature.known(12L));
        assertThrows(IllegalArgumentException.class, () -> make.apply(1L, Feature.unknown()));
        assertThrows(IllegalArgumentException.class, () -> make.apply(0L, Feature.known(12L)));
    }

    @Test
    void feedbackCannotLieBeyondItsCutoff() {
        var f = reward();
        long sequence = f.identity().ledgerSequence();
        assertThrows(IllegalArgumentException.class, () -> rewardWith(f, sequence - 1, sequence, f.effectiveEvidence()));
        var late = new LearningAssessment.EffectiveEvidence(
                QualityDimension.TESTS,
                QualityState.PASS, f.cutoff() + 1);
        assertThrows(IllegalArgumentException.class, () -> rewardWith(f, f.cutoff(), sequence, List.of(late)));
    }

    @Test
    void provenanceReasonAndMismatchesAreEquivalentInBothDirections() {
        assertThrows(IllegalArgumentException.class, () -> new FeedbackDerivation.NoUpdate(
                NoUpdateReason.MAPPING_MISMATCH, List.of(IneligibilityReason.PROVENANCE_MISMATCH), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FeedbackDerivation.NoUpdate(
                NoUpdateReason.PROVENANCE_MISMATCH, List.of(), List.of()));
        new FeedbackDerivation.NoUpdate(NoUpdateReason.MAPPING_MISMATCH, List.of(IneligibilityReason.PENDING),
                List.of());
        new FeedbackDerivation.NoUpdate(NoUpdateReason.PROVENANCE_MISMATCH,
                List.of(IneligibilityReason.PROVENANCE_MISMATCH), List.of(ProvenanceField.CUTOFF));
    }

    @Test
    void recordOnlyAcceptsOnlyTheNeutralCohortLessMarker() {
        var f = reward();
        var marker = AppliedRevision.ofNoUpdate(new FeedbackFixtures.Spec().passing().observation(),
                NoUpdateReason.NO_STAGE_ATTRIBUTION);
        new Reconciliation.RecordOnly(marker, Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new Reconciliation.RecordOnly(AppliedRevision.of(f), Optional.empty()));
        var neutralWithCohort = AppliedRevision.of(
                ((FeedbackDerivation.Derived) FeedbackFixtures.policy().derive(
                        new FeedbackFixtures.Spec().observation(), new FeedbackFixtures.Spec().decision,
                        new FeedbackFixtures.Spec().request,
                        FeedbackFixtures.bindings(new FeedbackFixtures.Spec().decision,
                                new FeedbackFixtures.Spec().request, NODE_A))).feedback());
        assertThrows(IllegalArgumentException.class,
                () -> new Reconciliation.RecordOnly(neutralWithCohort, Optional.empty()));
    }

    @Test
    void aProvenanceNoUpdateNeedsTheDifferingFieldsAndTheMatchingReason() {
        assertThrows(IllegalArgumentException.class, () -> new FeedbackDerivation.NoUpdate(
                NoUpdateReason.PROVENANCE_MISMATCH, List.of(IneligibilityReason.PROVENANCE_MISMATCH), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FeedbackDerivation.NoUpdate(
                NoUpdateReason.PROVENANCE_MISMATCH, List.of(), List.of(ProvenanceField.CUTOFF)));
    }

    @Test
    void scoreRulesMapToExactlyOneDisposition() {
        for (var rule : ScoreRule.values()) {
            if (rule.name().startsWith("NEUTRAL")) {
                assertEquals(FeedbackDisposition.NEUTRAL, rule.disposition(), rule.name());
            } else {
                assertNotEquals(FeedbackDisposition.NEUTRAL, rule.disposition(), rule.name());
            }
        }
        assertEquals(FeedbackDisposition.REINFORCE, ScoreRule.REINFORCE_COST_REFINED.disposition());
        assertEquals(FeedbackDisposition.PENALIZE, ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE.disposition());
    }

    @Test
    void noUpdateListsMismatchesOnlyForProvenanceAndCanonicalizesReasons() {
        var none = new FeedbackDerivation.NoUpdate(NoUpdateReason.PROVENANCE_MISMATCH,
                List.of(IneligibilityReason.PENDING, IneligibilityReason.PROVENANCE_MISMATCH),
                List.of(ProvenanceField.CUTOFF, ProvenanceField.DECISION_REF));
        assertEquals(List.of(IneligibilityReason.PROVENANCE_MISMATCH, IneligibilityReason.PENDING), none.reasons());
        assertEquals(List.of(ProvenanceField.DECISION_REF, ProvenanceField.CUTOFF), none.mismatches());
        assertThrows(IllegalArgumentException.class, () -> new FeedbackDerivation.NoUpdate(
                NoUpdateReason.NO_TARGET_BINDING, List.of(), List.of(ProvenanceField.CUTOFF)));
    }

    @Test
    void appliedRevisionRecordsTheEffectAndRejectsInconsistentScores() {
        var f = reward();
        var applied = AppliedRevision.of(f);
        assertEquals("exp-1", applied.experienceRef());
        assertEquals(0, applied.revision());
        assertEquals(12L, applied.ledgerSequence());
        assertEquals(0.8, applied.score(), 1e-12);
        assertTrue(applied.changedState());
        var neutralBasis = new EntryBasis.Derived(f.cohortKey(), ScoreRule.NEUTRAL_PENDING, DerivedAcceptance.PENDING,
                List.of(IneligibilityReason.PENDING));
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(applied, Optional.empty(),
                Optional.empty(), neutralBasis, FeedbackDisposition.NEUTRAL, 0.5));
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(applied, Optional.of(NODE_A),
                Optional.empty(), applied.basis(), FeedbackDisposition.REINFORCE, -0.5));
        // a target Node is recorded exactly when the entry moved a Node
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(applied, Optional.empty(),
                Optional.empty(), applied.basis(), FeedbackDisposition.REINFORCE, 0.5));
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(applied, Optional.of(NODE_A),
                Optional.empty(), neutralBasis, FeedbackDisposition.NEUTRAL, 0.0));
        // the rule's disposition must agree with the entry's
        assertThrows(IllegalArgumentException.class, () -> FeedbackFixtures.variant(applied, Optional.empty(),
                Optional.empty(), applied.basis(), FeedbackDisposition.NEUTRAL, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Reconciliation.Apply(Optional.of(applied)));
    }
}
