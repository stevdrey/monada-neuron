package monada.neuron.routing.outcome;

import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.features.Feature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static monada.neuron.routing.outcome.OutcomeFixtures.obs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeValueTypesTest {

    private static final ResourceValue.Known REPORTED_ZERO = new ResourceValue.Known(0, ResourceValue.Provenance.REPORTED);

    @Test
    void notApplicableNeedsAJustificationAndOnlyItCarriesOne() {
        assertThrows(IllegalArgumentException.class, () -> new QualityObservation("e", 0, QualityDimension.TESTS,
                QualityState.NOT_APPLICABLE, Feature.unknown(), "r", "1", "p", "1", EvidenceSource.TOOL, "a", 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QualityObservation("e", 0, QualityDimension.TESTS,
                QualityState.PASS, Feature.known("why"), "r", "1", "p", "1", EvidenceSource.TOOL, "a", 1, List.of()));
    }

    @Test
    void malformedTokensAndNumbersAreRejected() {
        for (String bad : new String[] {"", " padded", "padded ", "ctl\u0007", "zero​", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> obs("e", 0, QualityDimension.TESTS, QualityState.PASS, bad, 1));
        }
        assertThrows(NullPointerException.class, () -> obs("e", 0, QualityDimension.TESTS, QualityState.PASS, null, 1));
        assertThrows(IllegalArgumentException.class, () -> obs("e", -1, QualityDimension.TESTS, QualityState.PASS, "a", 1));
        assertThrows(IllegalArgumentException.class, () -> obs("e", 0, QualityDimension.TESTS, QualityState.PASS, "a", -1));
        assertEquals("x".repeat(128), obs("e", 0, QualityDimension.TESTS, QualityState.PASS, "x".repeat(128), 1).attemptId());
    }

    @Test
    void mandatoryDimensionsIterateInEnumOrder() {
        var policy = new EvidencePolicy("p", "1",
                new LinkedHashSet<>(List.of(QualityDimension.SECURITY, QualityDimension.CORRECTNESS, QualityDimension.TESTS)));
        assertEquals(List.of(QualityDimension.CORRECTNESS, QualityDimension.TESTS, QualityDimension.SECURITY),
                List.copyOf(policy.mandatoryDimensions()));
    }

    @Test
    void evidencePolicyNeedsMandatoryDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new EvidencePolicy("p", "1", Set.of()));
        var copy = new ArrayList<>(List.of(QualityDimension.TESTS));
        var policy = new EvidencePolicy("p", "1", Set.copyOf(copy));
        copy.add(QualityDimension.SECURITY);
        assertEquals(Set.of(QualityDimension.TESTS), policy.mandatoryDimensions());
        assertThrows(UnsupportedOperationException.class, () -> policy.mandatoryDimensions().add(QualityDimension.SECURITY));
    }

    @Test
    void callerMutationNeverChangesAnOutcome() {
        var spec = new OutcomeFixtures.Spec().passing();
        spec.judged.add(new EvidenceRevision("ev-tests", 0));
        var outcome = spec.outcome();
        spec.observations.add(obs("late", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 50));
        spec.judged.clear();
        assertEquals(2, outcome.observations().size());
        assertEquals(1, outcome.judgedRevisions().size());
        assertThrows(UnsupportedOperationException.class, () -> outcome.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> outcome.judgedRevisions().clear());
    }

    @Test
    void outcomeBoundsAndCrossFieldRulesAreEnforced() {
        var many = new OutcomeFixtures.Spec();
        for (int i = 0; i < 33; i++) {
            many.with(obs("e" + i, 0, QualityDimension.TESTS, QualityState.PASS, "att-1", i));
        }
        assertThrows(IllegalArgumentException.class, many::outcome);

        var duplicate = new OutcomeFixtures.Spec().passing().with(obs("ev-tests", 0, QualityDimension.TESTS, QualityState.PASS, "att-1", 5));
        assertThrows(IllegalArgumentException.class, duplicate::outcome);

        var sameRevisionTwice = new OutcomeFixtures.Spec()
                .with(obs("e1", 0, QualityDimension.TESTS, QualityState.PASS, "att-1", 5))
                .with(obs("e1", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 9));
        assertThrows(IllegalArgumentException.class, sameRevisionTwice::outcome);
        var splitPosition = new OutcomeFixtures.Spec()
                .with(obs("e1", 0, QualityDimension.TESTS, QualityState.PASS, "att-1", 5))
                .with(obs("e1", 0, QualityDimension.SECURITY, QualityState.PASS, "att-1", 9));
        assertThrows(IllegalArgumentException.class, splitPosition::outcome);

        var rejectedWithAttempt = new OutcomeFixtures.Spec();
        rejectedWithAttempt.status = OutcomeStatus.REJECTED;
        assertThrows(IllegalArgumentException.class, rejectedWithAttempt::outcome);

        var statusOnRoutedAttempt = new OutcomeFixtures.Spec();
        statusOnRoutedAttempt.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        assertThrows(IllegalArgumentException.class, statusOnRoutedAttempt::outcome);
        var statusOnRejected = new OutcomeFixtures.Spec();
        statusOnRejected.status = OutcomeStatus.REJECTED;
        statusOnRejected.acceptedAttempt = Feature.unknown();
        statusOnRejected.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        assertThrows(IllegalArgumentException.class, statusOnRejected::outcome);
        var statusOnRepair = new OutcomeFixtures.Spec();
        statusOnRepair.acceptedAttempt = Feature.known("att-2");
        statusOnRepair.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        assertEquals(Feature.known(AttemptStatus.COMPLETED), statusOnRepair.outcome().acceptedAttemptStatus());

        var noAttempt = new OutcomeFixtures.Spec();
        noAttempt.acceptedAttempt = Feature.unknown();
        assertThrows(IllegalArgumentException.class, noAttempt::outcome);

        var beyondCutoff = new OutcomeFixtures.Spec();
        beyondCutoff.outcomeSequence = 101;
        assertThrows(IllegalArgumentException.class, beyondCutoff::outcome);

        var spec = new OutcomeFixtures.Spec();
        var outcome = spec.outcome();
        assertThrows(IllegalArgumentException.class, () -> new RoutingObservation(outcome, "x", 0, 101, Feature.unknown()));
        assertThrows(IllegalArgumentException.class, () -> new RoutingObservation(outcome, "x", 1, 11, Feature.known(11L)));
        assertThrows(IllegalArgumentException.class, () -> new RoutingObservation(outcome, "x", -1, 11, Feature.unknown()));
        assertEquals(Feature.known(10L), new RoutingObservation(outcome, "x", 1, 11, Feature.known(10L)).supersedesSequence());
        assertThrows(IllegalArgumentException.class, () -> new RoutingObservation(outcome, "x", 1, 11, Feature.unknown()));
        assertThrows(IllegalArgumentException.class, () -> new RoutingObservation(outcome, "x", 0, 11, Feature.known(10L)));
    }

    @Test
    void sampleCannotPrecedeItsOutcomeOrIncludedEvidence() {
        var spec = new OutcomeFixtures.Spec().passing();
        spec.outcomeSequence = 10;
        var outcome = spec.outcome();
        assertThrows(IllegalArgumentException.class, () -> new RoutingObservation(outcome, "x", 0, 9, Feature.unknown()));
        new RoutingObservation(outcome, "x", 0, 10, Feature.unknown());

        var evidence = new OutcomeFixtures.Spec().passing()
                .with(obs("late", 0, QualityDimension.ARCHITECTURE, QualityState.PASS, "att-1", 20));
        evidence.outcomeSequence = 5;
        var withLate = evidence.outcome();
        assertThrows(IllegalArgumentException.class, () -> new RoutingObservation(withLate, "x", 0, 19, Feature.unknown()));
        new RoutingObservation(withLate, "x", 0, 20, Feature.unknown());

        var future = new OutcomeFixtures.Spec().passing()
                .with(obs("future", 0, QualityDimension.ARCHITECTURE, QualityState.FAIL, "att-1", 101));
        new RoutingObservation(future.outcome(), "x", 0, 10, Feature.unknown());
    }

    @Test
    void attributionRulesNeverInventEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StageAttribution(StageAttribution.Basis.CHAIN_ONLY, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StageAttribution(StageAttribution.Basis.NONE, 1,
                List.of(new EvidenceRef("log", "r", Feature.unknown()))));
        assertEquals(StageAttribution.Basis.NONE, StageAttribution.none().basis());
    }

    @Test
    void evidenceReferencesAreBoundedAndCanonical() {
        var refs = new ArrayList<EvidenceRef>();
        for (int i = 0; i < 9; i++) {
            refs.add(new EvidenceRef("log", "r" + i, Feature.unknown()));
        }
        assertThrows(IllegalArgumentException.class, () -> new QualityObservation("e", 0, QualityDimension.TESTS,
                QualityState.PASS, Feature.unknown(), "r", "1", "p", "1", EvidenceSource.TOOL, "a", 1, refs));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceRef("log", "x".repeat(513), Feature.unknown()));
        assertEquals("x".repeat(512), new EvidenceRef("log", "x".repeat(512), Feature.unknown()).reference());
        var unordered = List.of(new EvidenceRef("log", "b", Feature.unknown()), new EvidenceRef("log", "a", Feature.unknown()));
        var observation = new QualityObservation("e", 0, QualityDimension.TESTS, QualityState.PASS, Feature.unknown(),
                "r", "1", "p", "1", EvidenceSource.TOOL, "a", 1, unordered);
        assertEquals("a", observation.evidenceRefs().getFirst().reference());
    }

    @Test
    void moneyKeepsExactDecimalsAndRejectsBadCurrencies() {
        assertEquals(new BigDecimal("0.002000"), new Money(new BigDecimal("0.002000"), "USD").amount());
        for (String bad : new String[] {"usd", "US", "USDX", "U5D", ""}) {
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.ONE, bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal("-0.01"), "USD"));
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal("1".repeat(39)), "USD"));
    }

    @Test
    void resourcesKeepCostKindsApartAndNeverTurnMissingIntoZero() {
        var summary = new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(),
                List.of(new UsageCounter(UsageKind.OUTPUT_TOKENS, new ResourceValue.Unknown()),
                        new UsageCounter(UsageKind.INPUT_TOKENS, REPORTED_ZERO),
                        new UsageCounter(UsageKind.REQUESTS, new ResourceValue.NotMeasured())),
                List.of(new Money(new BigDecimal("1.50"), "EUR")),
                List.of(new HypotheticalCost(new Money(new BigDecimal("0.002"), "USD"), "prices", "3", "list price")));
        assertEquals(UsageKind.INPUT_TOKENS, summary.counters().getFirst().kind());
        assertEquals(List.of(UsageKind.OUTPUT_TOKENS, UsageKind.REQUESTS, UsageKind.TOOL_CALLS),
                summary.unmeasured(Set.of(UsageKind.INPUT_TOKENS, UsageKind.OUTPUT_TOKENS, UsageKind.REQUESTS, UsageKind.TOOL_CALLS)));
        assertEquals("EUR", summary.actualBilling().getFirst().currency());
        assertEquals("USD", summary.hypotheticalCosts().getFirst().amount().currency());
        assertEquals(List.of(UsageKind.INPUT_TOKENS), ResourceSummary.unrecorded().unmeasured(Set.of(UsageKind.INPUT_TOKENS)));
    }

    @Test
    void usageDeclarationRulesFollowTheStoreContract() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceSummary(UsageDeclaration.NO_BILLABLE_USAGE,
                Feature.unknown(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSummary(UsageDeclaration.NO_BILLABLE_USAGE,
                Feature.known("local check"), List.of(new UsageCounter(UsageKind.REQUESTS, REPORTED_ZERO)), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSummary(UsageDeclaration.MEASURED,
                Feature.known("nothing"), List.of(), List.of(), List.of()));
        var declared = new ResourceSummary(UsageDeclaration.NO_BILLABLE_USAGE, Feature.known("local check"),
                List.of(), List.of(), List.of());
        assertTrue(declared.unmeasured(Set.of(UsageKind.INPUT_TOKENS)).isEmpty());
        var twoUsd = List.of(new Money(BigDecimal.ONE, "USD"), new Money(BigDecimal.TEN, "USD"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSummary(UsageDeclaration.MEASURED,
                Feature.unknown(), List.of(), twoUsd, List.of()));
    }

    @ParameterizedTest
    @EnumSource(IneligibilityReason.class)
    void negativeCandidateNeverCarriesABlockingReason(IneligibilityReason reason) {
        Runnable build = () -> new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED,
                EligibilityVerdict.NEGATIVE_CANDIDATE, List.of(reason),
                reason == IneligibilityReason.PROVENANCE_MISMATCH ? List.of(ProvenanceField.DECISION_REF) : List.of(),
                List.of(new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.FAIL, 5)),
                List.of(QualityDimension.TESTS), 0);
        if (reason.blocking()) {
            assertThrows(IllegalArgumentException.class, build::run);
        } else {
            build.run();
        }
    }

    @ParameterizedTest
    @EnumSource(DerivedAcceptance.class)
    void negativeCandidateNeedsARejectableDerivedState(DerivedAcceptance derived) {
        Runnable build = () -> new LearningAssessment(derived, EligibilityVerdict.NEGATIVE_CANDIDATE, List.of(),
                List.of(), List.of(new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.FAIL, 5)),
                List.of(QualityDimension.TESTS), 0);
        if (derived == DerivedAcceptance.NOT_ACCEPTED || derived == DerivedAcceptance.ACCEPTED_UNVALIDATED) {
            build.run();
        } else {
            assertThrows(IllegalArgumentException.class, build::run);
        }
    }

    @Test
    void assessmentListsAreCanonicalAndDuplicateFree() {
        var tests = new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.PASS, 5);
        var security = new LearningAssessment.EffectiveEvidence(QualityDimension.SECURITY, QualityState.PASS, 6);
        var ordered = new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED, EligibilityVerdict.NOT_ELIGIBLE,
                List.of(IneligibilityReason.PROVENANCE_MISMATCH, IneligibilityReason.PENDING),
                List.of(ProvenanceField.DECISION_REF, ProvenanceField.CUTOFF), List.of(tests, security), List.of(), 0);
        var shuffled = new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED, EligibilityVerdict.NOT_ELIGIBLE,
                List.of(IneligibilityReason.PENDING, IneligibilityReason.PROVENANCE_MISMATCH),
                List.of(ProvenanceField.CUTOFF, ProvenanceField.DECISION_REF), List.of(security, tests), List.of(), 0);
        assertEquals(ordered, shuffled);
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED,
                EligibilityVerdict.NOT_ELIGIBLE, List.of(IneligibilityReason.PENDING, IneligibilityReason.PENDING),
                List.of(), List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED,
                EligibilityVerdict.NOT_ELIGIBLE, List.of(), List.of(), List.of(tests, tests), List.of(), 0));
    }

    @Test
    void laterRevisionsMustSitAtLaterLedgerSequences() {
        var backward = new OutcomeFixtures.Spec()
                .with(obs("e1", 1, QualityDimension.TESTS, QualityState.PASS, "att-1", 5))
                .with(obs("e1", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 9));
        assertThrows(IllegalArgumentException.class, backward::outcome);
        var forward = new OutcomeFixtures.Spec()
                .with(obs("e1", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 5))
                .with(obs("e1", 1, QualityDimension.TESTS, QualityState.PASS, "att-1", 9));
        assertEquals(2, forward.outcome().observations().size());
    }

    @Test
    void effectiveEvidenceNeedsANonNegativeLedgerPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.PASS, -1));
        assertEquals(0L, new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.PASS, 0).ledgerSequence());
    }

    @Test
    void positiveCandidateNeedsPassingEffectiveEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.VALIDATED_ACCEPTED,
                EligibilityVerdict.POSITIVE_CANDIDATE, List.of(), List.of(), List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.VALIDATED_ACCEPTED,
                EligibilityVerdict.POSITIVE_CANDIDATE, List.of(), List.of(),
                List.of(new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.UNKNOWN, 5)),
                List.of(), 0));
        new LearningAssessment(DerivedAcceptance.VALIDATED_ACCEPTED, EligibilityVerdict.POSITIVE_CANDIDATE, List.of(),
                List.of(), List.of(new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS,
                        QualityState.NOT_APPLICABLE, 5)), List.of(), 0);
    }

    @Test
    void provenFailuresMustAgreeWithTheEffectiveEvidence() {
        var pass = new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.PASS, 5);
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED,
                EligibilityVerdict.NEGATIVE_CANDIDATE, List.of(), List.of(), List.of(pass), List.of(QualityDimension.TESTS), 0));
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED,
                EligibilityVerdict.NOT_ELIGIBLE, List.of(), List.of(),
                List.of(new LearningAssessment.EffectiveEvidence(QualityDimension.TESTS, QualityState.FAIL, 5)), List.of(), 0));
    }

    @Test
    void assessmentInvariantsRejectInconsistentVerdicts() {
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.ACCEPTED_UNVALIDATED,
                EligibilityVerdict.POSITIVE_CANDIDATE, List.of(), List.of(), List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED,
                EligibilityVerdict.NEGATIVE_CANDIDATE, List.of(), List.of(), List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new LearningAssessment(DerivedAcceptance.NOT_ACCEPTED,
                EligibilityVerdict.NOT_ELIGIBLE, List.of(IneligibilityReason.PROVENANCE_MISMATCH), List.of(), List.of(), List.of(), 0));
    }
}
