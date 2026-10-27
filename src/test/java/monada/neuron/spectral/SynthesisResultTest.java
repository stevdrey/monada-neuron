package monada.neuron.spectral;

import monada.neuron.spectral.SynthesisResult.NotInvertible;
import monada.neuron.spectral.SynthesisResult.Reconstructed;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Validation, defensive copies and value semantics of {@link SynthesisResult}. */
class SynthesisResultTest {

    private static final OptionalDouble NONE = OptionalDouble.empty();

    @Test
    void reconstructedCopiesSamplesAndCanonicalizesZeros() {
        var samples = new double[] {1.0, -0.0, 2.0};
        var result = Reconstructed.of(SynthesisStatus.EXACT, samples, 1.0, 5.0, NONE, NONE, NONE);
        samples[0] = 99.0;

        assertArrayEquals(new double[] {1.0, 0.0, 2.0}, result.samples());
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(result.sample(1)));
        result.samples()[2] = 99.0;
        assertEquals(2.0, result.sample(2));
        assertEquals(3, result.sampleCount());
    }

    @Test
    void reconstructedIsAValue() {
        var first = Reconstructed.of(SynthesisStatus.LOSSY, new double[] {1.0, 2.0}, 0.5, 5.0,
                OptionalDouble.of(0.1), OptionalDouble.of(0.05), OptionalDouble.of(0.2));
        var second = Reconstructed.of(SynthesisStatus.LOSSY, new double[] {1.0, 2.0}, 0.5, 5.0,
                OptionalDouble.of(0.1), OptionalDouble.of(0.05), OptionalDouble.of(0.2));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, Reconstructed.of(SynthesisStatus.EXACT, new double[] {1.0, 2.0}, 1.0, 5.0,
                OptionalDouble.of(0.1), OptionalDouble.of(0.05), OptionalDouble.of(0.2)));
        assertNotEquals(first, Reconstructed.of(SynthesisStatus.LOSSY, new double[] {1.0, 2.5}, 0.5, 5.0,
                OptionalDouble.of(0.1), OptionalDouble.of(0.05), OptionalDouble.of(0.2)));
    }

    @Test
    void reconstructedRejectsInvalidValues() {
        double[] one = {1.0};
        assertThrows(IllegalArgumentException.class,
                () -> Reconstructed.of(SynthesisStatus.NOT_INVERTIBLE, one, 1.0, 1.0, NONE, NONE, NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Reconstructed.of(SynthesisStatus.EXACT, new double[0], 1.0, 0.0, NONE, NONE, NONE));
        assertThrows(IllegalArgumentException.class, () -> Reconstructed.of(SynthesisStatus.EXACT,
                new double[SpectralPattern.MAX_SAMPLE_COUNT + 1], 1.0, 0.0, NONE, NONE, NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Reconstructed.of(SynthesisStatus.EXACT, new double[] {Double.NaN}, 1.0, 1.0, NONE, NONE,
                        NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Reconstructed.of(SynthesisStatus.LOSSY, one, 1.5, 1.0, NONE, NONE, NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Reconstructed.of(SynthesisStatus.LOSSY, one, -0.1, 1.0, NONE, NONE, NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Reconstructed.of(SynthesisStatus.EXACT, one, 1.0, Double.POSITIVE_INFINITY, NONE, NONE,
                        NONE));
        assertThrows(IllegalArgumentException.class, () -> Reconstructed.of(SynthesisStatus.EXACT, one, 1.0, 1.0,
                OptionalDouble.of(0.0), NONE, NONE));
        assertThrows(IllegalArgumentException.class, () -> Reconstructed.of(SynthesisStatus.EXACT, one, 1.0, 1.0,
                OptionalDouble.of(-1.0), OptionalDouble.of(0.0), OptionalDouble.of(0.0)));
        assertThrows(IllegalArgumentException.class, () -> Reconstructed.of(SynthesisStatus.EXACT, one, 0.2, 1.0,
                NONE, NONE, NONE), "EXACT retains all source energy");
        assertThrows(IllegalArgumentException.class, () -> Reconstructed.of(SynthesisStatus.LOSSY, one, 0.5, 1.0,
                OptionalDouble.of(1.0), OptionalDouble.of(5.0), OptionalDouble.of(0.1)), "RMS above the peak");
        assertThrows(IllegalArgumentException.class, () -> Reconstructed.of(SynthesisStatus.LOSSY, one, 0.5, 1.0,
                OptionalDouble.of(0.0), OptionalDouble.of(Double.MIN_VALUE), OptionalDouble.of(0.0)),
                "a zero peak admits only a zero RMS");
        assertThrows(NullPointerException.class,
                () -> Reconstructed.of(null, one, 1.0, 1.0, NONE, NONE, NONE));
        assertThrows(NullPointerException.class,
                () -> Reconstructed.of(SynthesisStatus.EXACT, one, 1.0, 1.0, null, NONE, NONE));
    }

    @Test
    void notInvertibleReportsItsStatusAndBoundsItsDetail() {
        var result = new NotInvertible(NotInvertibleReason.MISSING_APPROXIMATION, 0.25, "approximation missing");

        assertEquals(SynthesisStatus.NOT_INVERTIBLE, result.status());
        assertEquals(0.25, result.retainedEnergyFraction());
        assertThrows(IllegalArgumentException.class,
                () -> new NotInvertible(NotInvertibleReason.MISSING_APPROXIMATION, 0.25, " "));
        assertThrows(IllegalArgumentException.class, () -> new NotInvertible(
                NotInvertibleReason.MISSING_APPROXIMATION, 0.25, "x".repeat(NotInvertible.MAX_DETAIL_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new NotInvertible(NotInvertibleReason.MISSING_APPROXIMATION, Double.NaN, "detail"));
        assertThrows(NullPointerException.class, () -> new NotInvertible(null, 0.25, "detail"));
    }
}
