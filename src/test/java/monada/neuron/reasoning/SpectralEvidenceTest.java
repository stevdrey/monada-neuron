package monada.neuron.reasoning;

import monada.neuron.memory.ResonanceMemoryResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpectralEvidenceTest {

    @Test
    void validatesReferenceOffsetAndWeight() {
        var longest = "r".repeat(ResonanceMemoryResult.MAX_REFERENCE_LENGTH);
        assertAll(
                () -> assertDoesNotThrow(() -> new SpectralEvidence(longest, 0, EvidenceRelation.SUPPORTS, 1.0)),
                () -> assertThrows(NullPointerException.class,
                        () -> new SpectralEvidence(null, 0, EvidenceRelation.SUPPORTS, 0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SpectralEvidence(" ", 0, EvidenceRelation.SUPPORTS, 0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SpectralEvidence(longest + "r", 0, EvidenceRelation.SUPPORTS, 0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SpectralEvidence("window", -1, EvidenceRelation.SUPPORTS, 0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SpectralEvidence("window", 0, EvidenceRelation.SUPPORTS, 0.0)),
                () -> assertThrows(NullPointerException.class,
                        () -> new SpectralEvidence("window", 0, null, 0.5)));
    }

    @Test
    void signalProvenanceValidationIgnoresWindowReferences() {
        var set = new HypothesisSet(List.of(new Hypothesis(0, new Proposition(1, 11),
                List.of(new SpectralEvidence("window", 64, EvidenceRelation.SUPPORTS, 0.7)))), HypothesisLimits.DEFAULT);

        assertDoesNotThrow(() -> set.validateSignalProvenance(0));
    }
}
