package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.ProvenanceField;
import monada.neuron.routing.outcome.UsageKind;
import monada.neuron.routing.ResourceObjective;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        var b = new TargetBindings("s", "p", "1", "m", List.of(new TargetBinding(key("s", "b", "r", 2), NODE_B),
                new TargetBinding(key("s", "a", "r", 1), NODE_A)));
        assertEquals(key("s", "a", "r", 1), b.bindings().getFirst().key());
        assertEquals(Optional.of(NODE_B), b.find(key("s", "b", "r", 2)));
        assertEquals(Optional.empty(), b.find(key("s", "b", "r", 3)));
        assertEquals(Optional.empty(), b.find(key("s", "c", "r", 1)));

        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m",
                List.of(new TargetBinding(key("other", "a", "r", 1), NODE_A))));
        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m",
                List.of(new TargetBinding(key("s", "a", "r", 1), NODE_A),
                        new TargetBinding(key("s", "a", "r", 1), NODE_B))));
        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m",
                List.of(new TargetBinding(key("s", "a", "r", 1), NODE_A),
                        new TargetBinding(key("s", "a", "r", 2), NODE_A))));
    }

    @Test
    void bindingsAreBoundedAt256() {
        var full = new ArrayList<TargetBinding>();
        for (int i = 0; i < TargetBindings.MAX_BINDINGS; i++) {
            full.add(new TargetBinding(key("s", "b", "r", i + 1), new UUID(1L, i)));
        }
        var ok = new TargetBindings("s", "p", "1", "m", full);
        assertEquals(TargetBindings.MAX_BINDINGS, ok.bindings().size());
        assertEquals(Optional.of(new UUID(1L, 255)), ok.find(key("s", "b", "r", 256)));
        full.add(new TargetBinding(key("s", "b", "r", 999), new UUID(1L, 999)));
        assertThrows(IllegalArgumentException.class, () -> new TargetBindings("s", "p", "1", "m", full));
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
                f.reasons(), f.effectiveEvidence(), f.provenFailures());
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
            List<IneligibilityReason> reasons, List<monada.neuron.routing.outcome.QualityDimension> failures,
            List<FeedbackEntry> entries) {
        return new RoutingFeedback(f.contractVersion(), f.identity(), f.supersedesSequence(), f.cohortKey(),
                f.routingPolicyId(), f.routingPolicyVersion(), f.catalogVersion(), f.cutoff(), acceptance,
                ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE, FeedbackDisposition.PENALIZE, entries, reasons,
                f.effectiveEvidence(), failures);
    }

    @Test
    void routingEntriesAreScoreOnly() {
        var f = reward();
        var signal = new monada.neuron.signal.Signal(monada.neuron.signal.SignalKind.FEEDBACK,
                new monada.neuron.model.FrequencyState(1.0, 1.0, 0.0));
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
                List.of(monada.neuron.routing.outcome.QualityDimension.TESTS,
                        monada.neuron.routing.outcome.QualityDimension.SECURITY), minus));
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
        for (var reason : List.of(IneligibilityReason.IMPORTED_CLAIM, IneligibilityReason.NO_STAGE_ATTRIBUTION)) {
            assertThrows(IllegalArgumentException.class, () -> penaltyWith(p, p.derivedAcceptance(),
                    List.of(reason), p.provenFailures(), minus));
        }
    }

    private static RoutingFeedback rewardWith(RoutingFeedback f, long cutoff, long ledgerSequence,
            List<monada.neuron.routing.outcome.LearningAssessment.EffectiveEvidence> evidence) {
        var id = f.identity();
        return new RoutingFeedback(f.contractVersion(),
                new FeedbackIdentity(id.experienceRef(), id.revision(), ledgerSequence, id.decisionRef(), id.route(),
                        id.mappingVersion(), id.evaluationPolicyId(), id.evaluationPolicyVersion()),
                f.supersedesSequence(), f.cohortKey(), f.routingPolicyId(), f.routingPolicyVersion(),
                f.catalogVersion(), cutoff, f.derivedAcceptance(), f.rule(), f.disposition(), f.entries(),
                f.reasons(), evidence, f.provenFailures());
    }

    @Test
    void aRewardNeedsPassingEffectiveEvidence() {
        var f = reward();
        var passing = f.effectiveEvidence();
        rewardWith(f, f.cutoff(), f.identity().ledgerSequence(), passing);
        assertThrows(IllegalArgumentException.class,
                () -> rewardWith(f, f.cutoff(), f.identity().ledgerSequence(), List.of()));
        var unknown = new monada.neuron.routing.outcome.LearningAssessment.EffectiveEvidence(
                monada.neuron.routing.outcome.QualityDimension.TESTS,
                monada.neuron.routing.outcome.QualityState.UNKNOWN, 5L);
        assertThrows(IllegalArgumentException.class,
                () -> rewardWith(f, f.cutoff(), f.identity().ledgerSequence(), List.of(unknown)));
    }

    @Test
    void feedbackCannotLieBeyondItsCutoff() {
        var f = reward();
        long sequence = f.identity().ledgerSequence();
        assertThrows(IllegalArgumentException.class, () -> rewardWith(f, sequence - 1, sequence, f.effectiveEvidence()));
        var late = new monada.neuron.routing.outcome.LearningAssessment.EffectiveEvidence(
                monada.neuron.routing.outcome.QualityDimension.TESTS,
                monada.neuron.routing.outcome.QualityState.PASS, f.cutoff() + 1);
        assertThrows(IllegalArgumentException.class, () -> rewardWith(f, f.cutoff(), sequence, List.of(late)));
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
        assertThrows(IllegalArgumentException.class,
                () -> new AppliedRevision(f.identity(), f.routingPolicyId(), f.routingPolicyVersion(), f.supersedesSequence(),
                        f.cohortKey(), FeedbackDisposition.NEUTRAL, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AppliedRevision(f.identity(), f.routingPolicyId(), f.routingPolicyVersion(), f.supersedesSequence(),
                        f.cohortKey(), FeedbackDisposition.REINFORCE, -0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new Reconciliation.Apply(Optional.of(applied)));
    }
}
