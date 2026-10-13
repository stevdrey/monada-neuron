package monada.neuron.routing.outcome;

import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;

import static monada.neuron.routing.outcome.OutcomeFixtures.GATES;
import static monada.neuron.routing.outcome.OutcomeFixtures.TESTS_ONLY;
import static monada.neuron.routing.outcome.OutcomeFixtures.obs;
import static monada.neuron.routing.outcome.QualityDimension.SECURITY;
import static monada.neuron.routing.outcome.QualityDimension.TESTS;
import static monada.neuron.routing.outcome.QualityState.FAIL;
import static monada.neuron.routing.outcome.QualityState.NOT_APPLICABLE;
import static monada.neuron.routing.outcome.QualityState.PASS;
import static monada.neuron.routing.outcome.QualityState.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeEvaluatorTest {

    private OutcomeFixtures.Spec spec() {
        return new OutcomeFixtures.Spec();
    }

    // ---- Store v1 section 8 normative cases (policy forge-gates v1, mandatory TESTS, accepted attempt a2) ----

    private OutcomeFixtures.Spec storeCase(QualityObservation... observations) {
        var spec = spec();
        spec.acceptedAttempt = Feature.known("a2");
        spec.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        for (var o : observations) {
            spec.with(o);
        }
        return spec;
    }

    @Test
    void storeCasePassIsValidatedAccepted() {
        var a = storeCase(obs("e1", 0, TESTS, PASS, "a2", 5)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, a.verdict());
        assertTrue(a.reasons().isEmpty());
    }

    @Test
    void storeCaseLaterFailOverridesEarlierPass() {
        var a = storeCase(obs("e1", 0, TESTS, PASS, "a2", 5), obs("e2", 0, TESTS, FAIL, "a2", 7)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertEquals(List.of(TESTS), a.provenFailures());
        assertEquals(EligibilityVerdict.NEGATIVE_CANDIDATE, a.verdict());
    }

    @Test
    void storeCaseLaterPassOverridesEarlierFail() {
        var a = storeCase(obs("e1", 0, TESTS, FAIL, "a2", 5), obs("e2", 0, TESTS, PASS, "a2", 7)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
        assertEquals(7L, a.effectiveEvidence().getFirst().ledgerSequence());
    }

    @Test
    void storeCaseLaterUnknownOverridesEarlierPass() {
        var a = storeCase(obs("e1", 0, TESTS, PASS, "a2", 5), obs("e2", 0, TESTS, UNKNOWN, "a2", 7)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertTrue(a.reasons().contains(IneligibilityReason.UNKNOWN_EVIDENCE));
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void storeCasePolicyV2ObservationIsIgnored() {
        var a = storeCase(obs("e1", 0, TESTS, PASS, "2", "a2", 5)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertTrue(a.reasons().containsAll(List.of(IneligibilityReason.MISSING_EVIDENCE,
                IneligibilityReason.STALE_EVALUATION_POLICY)));
        assertEquals(1, a.excludedObservations());
    }

    @Test
    void storeCaseEvidenceOfAnotherAttemptIsIgnored() {
        var a = storeCase(obs("e1", 0, TESTS, PASS, "a1", 3)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertTrue(a.reasons().contains(IneligibilityReason.MISSING_EVIDENCE));
    }

    @Test
    void storeCaseCorrectedRevisionReplacesTheOriginal() {
        var a = storeCase(obs("e1", 0, TESTS, PASS, "a2", 5), obs("e1", 1, TESTS, FAIL, "a2", 9)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertEquals(List.of(TESTS), a.provenFailures());
        assertEquals(1, a.excludedObservations());
    }

    @Test
    void storeCaseJustifiedNotApplicableValidates() {
        var a = storeCase(obs("e1", 0, TESTS, NOT_APPLICABLE, "a2", 5)).assess(TESTS_ONLY);
        assertEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
    }

    // ---- Acceptance criteria and required examples ----

    @Test
    void accepted() {
        var a = spec().passing().assess();
        assertEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, a.verdict());
        assertEquals(List.of(TESTS, SECURITY), a.effectiveEvidence().stream()
                .map(LearningAssessment.EffectiveEvidence::dimension).toList());
    }

    @ParameterizedTest
    @EnumSource(value = QualityState.class, names = {"FAIL", "UNKNOWN"})
    void completedInvocationWithFailingOrUnknownMandatoryGateIsNeverPositive(QualityState bad) {
        var failingTests = spec().with(obs("e1", 0, TESTS, bad, "att-1", 5))
                .with(obs("e2", 0, SECURITY, PASS, "att-1", 6)).assess();
        var badSecurity = spec().with(obs("e1", 0, TESTS, PASS, "att-1", 5))
                .with(obs("e2", 0, SECURITY, bad, "att-1", 6)).assess();
        for (var a : List.of(failingTests, badSecurity)) {
            assertNotEquals(EligibilityVerdict.POSITIVE_CANDIDATE, a.verdict());
            assertNotEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
        }
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        assertFalse(unexpected.equals(actual), "unexpected " + actual);
    }

    @Test
    void acceptedFlagAloneIsInsufficient() {
        var a = spec().assess();
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertEquals(List.of(IneligibilityReason.MISSING_EVIDENCE), a.reasons());
    }

    @Test
    void importedClaimIsNeverPositive() {
        var s = spec().passing();
        s.origin = OutcomeOrigin.IMPORTED_CLAIM;
        var a = s.assess();
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertTrue(a.reasons().contains(IneligibilityReason.IMPORTED_CLAIM));
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void repairedChainWithoutStageLocalEvidenceIsNotCredited() {
        var s = spec().with(obs("e1", 0, TESTS, PASS, "att-2", 5)).with(obs("e2", 0, SECURITY, PASS, "att-2", 6));
        s.acceptedAttempt = Feature.known("att-2");
        s.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        s.attribution = new StageAttribution(StageAttribution.Basis.CHAIN_ONLY, 2, List.of());
        var a = s.assess();
        assertEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertEquals(List.of(IneligibilityReason.NO_STAGE_ATTRIBUTION), a.reasons());
    }

    @Test
    void repairedChainWithStageLocalEvidenceIsCredited() {
        var s = spec().with(obs("e1", 0, TESTS, PASS, "att-2", 5)).with(obs("e2", 0, SECURITY, PASS, "att-2", 6));
        s.acceptedAttempt = Feature.known("att-2");
        s.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, s.assess().verdict());
    }

    @Test
    void rejectedWithProvenFailureIsANegativeCandidate() {
        var s = spec().with(obs("e1", 0, TESTS, FAIL, "att-1", 5));
        s.status = OutcomeStatus.REJECTED;
        s.acceptedAttempt = Feature.unknown();
        var a = s.assess();
        assertEquals(DerivedAcceptance.NOT_ACCEPTED, a.derivedAcceptance());
        assertEquals(EligibilityVerdict.NEGATIVE_CANDIDATE, a.verdict());
        assertEquals(List.of(TESTS), a.provenFailures());
    }

    @Test
    void rejectedWithoutGateEvidenceIsNotAProvenFailure() {
        var s = spec();
        s.status = OutcomeStatus.REJECTED;
        s.acceptedAttempt = Feature.unknown();
        var a = s.assess();
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertTrue(a.reasons().contains(IneligibilityReason.NO_ATTRIBUTABLE_FAILURE));
    }

    @Test
    void provenFailureWithoutStageAttributionIsNotPenalized() {
        var s = spec().with(obs("e1", 0, TESTS, FAIL, "att-1", 5));
        s.status = OutcomeStatus.REJECTED;
        s.acceptedAttempt = Feature.unknown();
        s.attribution = StageAttribution.none();
        var a = s.assess();
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertTrue(a.reasons().contains(IneligibilityReason.NO_STAGE_ATTRIBUTION));
    }

    @Test
    void pendingAndCancelledSayNothing() {
        var pending = spec().passing();
        pending.status = OutcomeStatus.PENDING;
        pending.acceptedAttempt = Feature.unknown();
        var a = pending.assess();
        assertEquals(DerivedAcceptance.PENDING, a.derivedAcceptance());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertTrue(a.reasons().contains(IneligibilityReason.PENDING));

        var cancelled = spec().with(obs("e1", 0, TESTS, FAIL, "att-1", 5));
        cancelled.status = OutcomeStatus.CANCELLED;
        cancelled.acceptedAttempt = Feature.unknown();
        var c = cancelled.assess();
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, c.verdict());
        assertTrue(c.reasons().contains(IneligibilityReason.CANCELLED));
    }

    @ParameterizedTest
    @EnumSource(value = InvocationStatus.class, names = {"UNAVAILABLE", "TIMED_OUT"})
    void environmentalFailureNeverPenalizesEvenWithFailingEvidence(InvocationStatus environmental) {
        var s = spec().with(obs("e1", 0, TESTS, FAIL, "att-1", 5));
        s.invocation = environmental;
        s.status = OutcomeStatus.FAILED;
        s.acceptedAttempt = Feature.unknown();
        var a = s.assess();
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertTrue(a.reasons().contains(IneligibilityReason.ENVIRONMENTAL_FAILURE));
        assertFalse(a.reasons().contains(IneligibilityReason.PROVENANCE_MISMATCH));
    }

    @ParameterizedTest
    @EnumSource(value = InvocationStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void acceptedClaimWithoutACompletedRoutedInvocationIsNotValidated(InvocationStatus invocation) {
        var s = spec().passing();
        s.invocation = invocation;
        var a = s.assess();
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertTrue(a.reasons().contains(IneligibilityReason.CONTRADICTORY_EVIDENCE));
        assertEquals(invocation == InvocationStatus.CANCELLED, a.reasons().contains(IneligibilityReason.CANCELLED));
        assertEquals(invocation.environmental(), a.reasons().contains(IneligibilityReason.ENVIRONMENTAL_FAILURE));
    }

    @Test
    void repairedChainWithAFailedRoutedAttemptAndACompletedAcceptedAttemptIsValidated() {
        var s = spec().with(obs("e1", 0, TESTS, PASS, "att-2", 5)).with(obs("e2", 0, SECURITY, PASS, "att-2", 6));
        s.invocation = InvocationStatus.FAILED;
        s.acceptedAttempt = Feature.known("att-2");
        s.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        var a = s.assess();
        assertEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
        assertFalse(a.reasons().contains(IneligibilityReason.CONTRADICTORY_EVIDENCE));
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, a.verdict());
    }

    @Test
    void acceptedAttemptThatDidNotCompleteStaysUnvalidatedEvenWithPassingEvidence() {
        for (Feature<AttemptStatus> status : List.of(Feature.<AttemptStatus>unknown(),
                Feature.known(AttemptStatus.FAILED), Feature.known(AttemptStatus.CANCELLED))) {
            var s = spec().with(obs("e1", 0, TESTS, PASS, "att-2", 5)).with(obs("e2", 0, SECURITY, PASS, "att-2", 6));
            s.acceptedAttempt = Feature.known("att-2");
            s.acceptedAttemptStatus = status;
            var a = s.assess();
            assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance(), String.valueOf(status));
            assertNotEquals(EligibilityVerdict.POSITIVE_CANDIDATE, a.verdict());
        }
    }

    @Test
    void conflictingJudgmentsAtOneSequenceAreContradictory() {
        var s = spec().with(obs("e1", 0, TESTS, PASS, "att-1", 5)).with(obs("e2", 0, TESTS, FAIL, "att-1", 5))
                .with(obs("e3", 0, SECURITY, PASS, "att-1", 6));
        var a = s.assess();
        assertTrue(a.reasons().contains(IneligibilityReason.CONTRADICTORY_EVIDENCE));
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void staleEvaluationPolicyOfTheHostIsReported() {
        var a = spec().passing().assess(new EvidencePolicy("forge-gates", "2", GATES.mandatoryDimensions()));
        assertTrue(a.reasons().contains(IneligibilityReason.STALE_EVALUATION_POLICY));
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void staleArtifactRevisionBlocksLearning() {
        var s = spec().with(obs("e1", 0, TESTS, PASS, "att-1", 5)).with(obs("e1", 1, TESTS, PASS, "att-1", 20))
                .with(obs("e2", 0, SECURITY, PASS, "att-1", 6));
        s.judged.add(new EvidenceRevision("e1", 0));
        var a = s.assess();
        assertTrue(a.reasons().contains(IneligibilityReason.STALE_ARTIFACT_REVISION));
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void pinToAnAbsentIntermediateRevisionIsAMismatchAndStale() {
        var s = spec().with(obs("e1", 0, TESTS, PASS, "att-1", 5)).with(obs("e1", 2, TESTS, PASS, "att-1", 20))
                .with(obs("e2", 0, SECURITY, PASS, "att-1", 6));
        s.judged.add(new EvidenceRevision("e1", 1));
        var a = s.assess();
        assertEquals(List.of(ProvenanceField.EVIDENCE_REFERENCE), a.mismatches());
        assertTrue(a.reasons().contains(IneligibilityReason.STALE_ARTIFACT_REVISION));
    }

    @Test
    void decisionStateWithANewerWatermarkThanTheRequestMismatches() {
        var base = OutcomeFixtures.REQUEST;
        var decision = OutcomeFixtures.decide(base, 50L);
        var evaluator = new OutcomeEvaluator();
        var ok = evaluator.assess(spec().passing().observation(), decision, base, GATES);
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, ok.verdict());
        // same decision, but the supplied request is older than the state it was decided with
        var older = new RoutingRequest(base.contractVersion(), base.scopeId(), base.taskId(), base.executionId(),
                base.attemptId(), base.stageId(), base.stageKind(), base.requestOrdinal(), base.sourceFingerprint(),
                base.contextFingerprint(), base.constraintsFingerprint(), base.evaluationPolicyId(),
                base.evaluationPolicyVersion(), base.features(), base.requirements(), base.overflowPermitted(), 40L);
        var a = evaluator.assess(spec().passing().observation(), decision, older, GATES);
        assertTrue(a.mismatches().contains(ProvenanceField.STATE_BINDING));
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void currentJudgedRevisionIsNotStale() {
        var s = spec().passing();
        s.judged.add(new EvidenceRevision("ev-tests", 0));
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, s.assess().verdict());
    }

    @Test
    void unknownJudgedEvidenceIsAProvenanceMismatch() {
        var s = spec().passing();
        s.judged.add(new EvidenceRevision("not-listed", 0));
        var a = s.assess();
        assertEquals(List.of(ProvenanceField.EVIDENCE_REFERENCE), a.mismatches());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void pinToARevisionAbsentFromTheListedEvidenceIsAMismatch() {
        var s = spec().passing();
        s.judged.add(new EvidenceRevision("ev-tests", 2));
        var a = s.assess();
        assertEquals(List.of(ProvenanceField.EVIDENCE_REFERENCE), a.mismatches());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void contradictionInAnOptionalDimensionStillBlocks() {
        var s = spec().passing()
                .with(obs("a1", 0, QualityDimension.ARCHITECTURE, PASS, "att-1", 7))
                .with(obs("a2", 0, QualityDimension.ARCHITECTURE, FAIL, "att-1", 7));
        var a = s.assess();
        assertTrue(a.reasons().contains(IneligibilityReason.CONTRADICTORY_EVIDENCE));
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertEquals(DerivedAcceptance.VALIDATED_ACCEPTED, a.derivedAcceptance());
    }

    @Test
    void importedClaimIsReportedForRejectedOutcomesToo() {
        var s = spec().with(obs("e1", 0, TESTS, FAIL, "att-1", 5));
        s.status = OutcomeStatus.REJECTED;
        s.origin = OutcomeOrigin.IMPORTED_CLAIM;
        s.acceptedAttempt = Feature.unknown();
        var a = s.assess();
        assertTrue(a.reasons().contains(IneligibilityReason.IMPORTED_CLAIM));
        assertEquals(EligibilityVerdict.NEGATIVE_CANDIDATE, a.verdict());
    }

    @Test
    void futureEvidenceDoesNotLeakBackward() {
        var s = spec().with(obs("e1", 0, TESTS, PASS, "att-1", 5)).with(obs("e2", 0, SECURITY, PASS, "att-1", 6))
                .with(obs("e3", 0, TESTS, FAIL, "att-1", 101));
        var a = s.assess();
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, a.verdict());
        assertEquals(1, a.excludedObservations());
    }

    @Test
    void provenanceMismatchesAreReportedInDeclarationOrder() {
        var s = spec().passing();
        s.route = new RouteKey("sub-b", 1);
        s.catalogVersion = "other-catalog";
        s.sourceFingerprint = "src-2";
        var a = s.assess();
        assertEquals(List.of(ProvenanceField.EXECUTED_ROUTE, ProvenanceField.CATALOG_VERSION,
                ProvenanceField.SOURCE_FINGERPRINT), a.mismatches());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
        assertEquals(List.of(IneligibilityReason.PROVENANCE_MISMATCH), a.reasons());
    }

    @Test
    void requestWithAnotherFeatureSchemaThanTheDecisionBindingMismatches() {
        var base = OutcomeFixtures.REQUEST;
        var swapped = new RoutingRequest(base.contractVersion(), base.scopeId(), base.taskId(), base.executionId(),
                base.attemptId(), base.stageId(), base.stageKind(), base.requestOrdinal(), base.sourceFingerprint(),
                base.contextFingerprint(), base.constraintsFingerprint(), base.evaluationPolicyId(),
                base.evaluationPolicyVersion(),
                TaskFeatures.builder().stageKind("implement").schemaVersion("task-features/2").build(),
                base.requirements(), base.overflowPermitted(), base.cutoff());
        var a = new OutcomeEvaluator().assess(spec().passing().observation(), OutcomeFixtures.DECISION, swapped, GATES);
        assertEquals(List.of(ProvenanceField.FEATURE_SCHEMA_VERSION), a.mismatches());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void outcomeAndRequestUnderAnotherPolicyThanTheDecisionStateMismatch() {
        var base = OutcomeFixtures.REQUEST;
        var v2 = new RoutingRequest(base.contractVersion(), base.scopeId(), base.taskId(), base.executionId(),
                base.attemptId(), base.stageId(), base.stageKind(), base.requestOrdinal(), base.sourceFingerprint(),
                base.contextFingerprint(), base.constraintsFingerprint(), base.evaluationPolicyId(), "2",
                base.features(), base.requirements(), base.overflowPermitted(), base.cutoff());
        var s = spec().with(obs("e1", 0, TESTS, PASS, "2", "att-1", 5)).with(obs("e2", 0, SECURITY, PASS, "2", "att-1", 6));
        s.evaluationPolicyVersion = "2";
        var policyV2 = new EvidencePolicy("forge-gates", "2", GATES.mandatoryDimensions());
        var a = new OutcomeEvaluator().assess(s.observation(), OutcomeFixtures.DECISION, v2, policyV2);
        assertEquals(List.of(ProvenanceField.EVALUATION_POLICY), a.mismatches());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void requestWithAnotherCutoffThanTheDecisionMismatches() {
        var base = OutcomeFixtures.REQUEST;
        var other = new RoutingRequest(base.contractVersion(), base.scopeId(), base.taskId(), base.executionId(),
                base.attemptId(), base.stageId(), base.stageKind(), base.requestOrdinal(), base.sourceFingerprint(),
                base.contextFingerprint(), base.constraintsFingerprint(), base.evaluationPolicyId(),
                base.evaluationPolicyVersion(), base.features(), base.requirements(), base.overflowPermitted(),
                base.cutoff() + 1);
        var a = new OutcomeEvaluator().assess(spec().passing().observation(), OutcomeFixtures.DECISION, other, GATES);
        assertEquals(List.of(ProvenanceField.CUTOFF), a.mismatches());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, a.verdict());
    }

    @Test
    void outcomeUnderAnotherEvaluationPolicyThanTheRequestMismatches() {
        var s = spec().passing();
        s.evaluationPolicyVersion = "2";
        assertTrue(s.assess().mismatches().contains(ProvenanceField.EVALUATION_POLICY));
    }

    @Test
    void cheapOrZeroCostCannotOvercomeAFailedGate() {
        var s = spec().with(obs("e1", 0, TESTS, FAIL, "att-1", 5)).with(obs("e2", 0, SECURITY, PASS, "att-1", 6));
        s.resources = new ResourceSummary(UsageDeclaration.NO_BILLABLE_USAGE, Feature.known("local deterministic check"),
                List.of(), List.of(new Money(BigDecimal.ZERO, "USD")), List.of());
        var a = s.assess();
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, a.derivedAcceptance());
        assertNotEquals(EligibilityVerdict.POSITIVE_CANDIDATE, a.verdict());
    }

    @Test
    void assessmentIsDeterministicAcrossObservationOrder() {
        var forward = spec().passing().with(obs("e9", 0, TESTS, FAIL, "att-1", 3));
        var backward = new OutcomeFixtures.Spec().with(obs("e9", 0, TESTS, FAIL, "att-1", 3));
        backward.observations.add(obs("ev-sec", 0, SECURITY, PASS, "att-1", 6));
        backward.observations.add(obs("ev-tests", 0, TESTS, PASS, "att-1", 5));
        assertEquals(forward.outcome(), backward.outcome());
        assertEquals(forward.assess(), backward.assess());
    }

    private long allocatedBytes(int observations) {
        var s = spec();
        for (int i = 0; i < observations; i++) {
            s.with(obs("e" + i, 0, i % 2 == 0 ? TESTS : SECURITY, PASS, "att-1", i));
        }
        var observation = s.observation();
        var evaluator = new OutcomeEvaluator();
        return AllocationProbe.measure(20_000,
                () -> evaluator.assess(observation, OutcomeFixtures.DECISION, OutcomeFixtures.REQUEST, GATES));
    }

    @Test
    void allocationGrowsLinearlyWithObservationCount(TestReporter reporter) {
        long small = allocatedBytes(8);
        long large = allocatedBytes(32);
        reporter.publishEntry("assess allocated bytes at 8 observations", Long.toString(small));
        reporter.publishEntry("assess allocated bytes at 32 observations", Long.toString(large));
        // linear growth is about 4x for a 4x larger input; a quadratic cost would be about 16x
        assertTrue(large < 6 * small, "growth is not linear: " + small + " -> " + large);
        assertTrue(large < CEILING, "allocation regression guard, measured " + large);
    }

    private static final long CEILING = 8 * 1024;

    @Test
    void factoryDerivesTheVerdictFromReasonsAndAttribution() {
        var pass = new LearningAssessment.EffectiveEvidence(TESTS, PASS, 5);
        var fail = new LearningAssessment.EffectiveEvidence(TESTS, FAIL, 5);
        var none = List.<IneligibilityReason>of();
        assertEquals(EligibilityVerdict.POSITIVE_CANDIDATE, LearningAssessment.of(DerivedAcceptance.VALIDATED_ACCEPTED,
                true, none, List.of(), List.of(pass), List.of(), 0).verdict());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, LearningAssessment.of(DerivedAcceptance.VALIDATED_ACCEPTED,
                false, List.of(IneligibilityReason.NO_STAGE_ATTRIBUTION), List.of(), List.of(pass), List.of(), 0).verdict());
        assertEquals(EligibilityVerdict.NEGATIVE_CANDIDATE, LearningAssessment.of(DerivedAcceptance.NOT_ACCEPTED,
                true, List.of(IneligibilityReason.MISSING_EVIDENCE), List.of(), List.of(fail), List.of(TESTS), 0).verdict());
        assertEquals(EligibilityVerdict.NOT_ELIGIBLE, LearningAssessment.of(DerivedAcceptance.NOT_ACCEPTED,
                true, List.of(IneligibilityReason.CANCELLED), List.of(), List.of(fail), List.of(TESTS), 0).verdict());
    }
}
