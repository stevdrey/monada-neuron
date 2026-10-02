package monada.neuron.reasoning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReasoningContractsTest {

    @Test
    void limitsRejectNonPositiveCapacities() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisLimits(0, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisLimits(1, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisLimits(-1, 1)));
    }

    @Test
    void propositionRejectsNegativeDomainAndUsesStructuralEquality() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Proposition(-1, 0)),
                () -> assertEquals(new Proposition(1, 7), new Proposition(1, 7)));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.1, 1.0001})
    void evidenceRejectsInvalidWeights(double weight) {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SignalEvidence(0, EvidenceRelation.SUPPORTS, weight)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MemoryReferenceEvidence("m", EvidenceRelation.CONTRADICTS, weight)));
    }

    @Test
    void evidenceRejectsNegativeReferencesAndNullRelation() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SignalEvidence(-1, EvidenceRelation.SUPPORTS, 0.5)),
                () -> assertThrows(NullPointerException.class,
                        () -> new SignalEvidence(1, null, 0.5)));
    }

    @Test
    void memoryReferenceAcceptsAnyNonBlankReferenceAndIsStructurallyEqual() {
        var longest = "x".repeat(10_000);
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new MemoryReferenceEvidence(null, EvidenceRelation.SUPPORTS, 0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MemoryReferenceEvidence(" ", EvidenceRelation.SUPPORTS, 0.5)),
                () -> assertEquals(
                        new MemoryReferenceEvidence(longest, EvidenceRelation.SUPPORTS, 0.5),
                        new MemoryReferenceEvidence(longest, EvidenceRelation.SUPPORTS, 0.5)));
    }

    @Test
    void hypothesisSnapshotsEvidenceAndSetValidatesStructure() {
        var evidence = new ArrayList<Evidence>(List.of(new SignalEvidence(1, EvidenceRelation.SUPPORTS, 1.0)));
        var hypothesis = new Hypothesis(0, new Proposition(0, 1), evidence);
        evidence.clear();
        var limits = new HypothesisLimits(2, 2);
        assertAll(
                () -> assertEquals(1, hypothesis.evidence().size()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> hypothesis.evidence().clear()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisSet(List.of(new Hypothesis(1, new Proposition(0, 1), List.of())), limits)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisSet(List.of(
                                new Hypothesis(0, new Proposition(0, 1), List.of()),
                                new Hypothesis(1, new Proposition(0, 1), List.of())), limits)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisSet(List.of(
                                new Hypothesis(0, new Proposition(0, 1), List.of()),
                                new Hypothesis(1, new Proposition(0, 2), List.of()),
                                new Hypothesis(2, new Proposition(0, 3), List.of())), limits)));
    }
}
