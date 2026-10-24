package monada.neuron.spectral;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DecompositionResult.DecompositionRejected;
import org.junit.jupiter.api.Test;

import static monada.neuron.spectral.SpectralFixtures.p4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecompositionResultTest {

    @Test
    void resultsAreExhaustiveValues() {
        DecompositionResult decomposed = new Decomposed(p4());
        DecompositionResult rejected = new DecompositionRejected(
                DecompositionRejectionReason.ENERGY_OUT_OF_RANGE, "energy overflows");

        assertEquals("decomposed", describe(decomposed));
        assertEquals("rejected:ENERGY_OUT_OF_RANGE", describe(rejected));
        assertEquals(new Decomposed(p4()), decomposed);
    }

    @Test
    void validatesItsComponents() {
        assertThrows(NullPointerException.class, () -> new Decomposed(null));
        assertThrows(NullPointerException.class, () -> new DecompositionRejected(null, "detail"));
        assertThrows(NullPointerException.class,
                () -> new DecompositionRejected(DecompositionRejectionReason.SAMPLE_RATE_OUT_OF_RANGE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DecompositionRejected(DecompositionRejectionReason.SAMPLE_RATE_OUT_OF_RANGE, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new DecompositionRejected(DecompositionRejectionReason.SAMPLE_RATE_OUT_OF_RANGE,
                        "x".repeat(DecompositionRejected.MAX_DETAIL_LENGTH + 1)));
    }

    private static String describe(DecompositionResult result) {
        return switch (result) {
            case Decomposed _ -> "decomposed";
            case DecompositionRejected rejected -> "rejected:" + rejected.reason();
        };
    }
}
