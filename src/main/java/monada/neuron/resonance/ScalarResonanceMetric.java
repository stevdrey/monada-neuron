package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;

import java.util.Objects;

/**
 * Deterministic scalar reference metric for {@link FrequencyState} values.
 *
 * <p>The normalized score is the product of amplitude, frequency, and phase similarities:
 *
 * <pre>{@code
 * score = amplitudeSimilarity * frequencySimilarity * phaseSimilarity
 * }</pre>
 *
 * <p>Positive amplitudes and frequencies use the ratio {@code min / max}. Equal frequencies,
 * including two zero frequencies, have similarity {@code 1}; one zero and one positive frequency
 * have similarity {@code 0}. If either amplitude is zero, the complete score is {@code 0} because
 * a silent state does not resonate. Phase similarity is {@code (1 + cos(delta)) / 2}, where both
 * phases are wrapped before their delta is calculated.
 *
 * <p>For valid states the result is finite, symmetric, and in {@code [0, 1]}. Relative amplitude or
 * frequency divergence monotonically lowers the corresponding factor while other factors remain
 * fixed. Equal scores are ties; this metric does not impose ordering. The calculation uses only
 * primitive scalar operations and does not allocate on the successful hot path.
 */
public final class ScalarResonanceMetric implements ResonanceMetric {

    private static final double TWO_PI = 2.0 * StrictMath.PI;

    /** Creates a stateless scalar reference metric. */
    public ScalarResonanceMetric() {
    }

    /**
     * Calculates the scalar reference resonance score.
     *
     * @param first first frequency state
     * @param second second frequency state
     * @return a deterministic score in {@code [0, 1]}
     * @throws NullPointerException if either state is {@code null}
     */
    @Override
    public double score(FrequencyState first, FrequencyState second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");

        double firstAmplitude = first.amplitude();
        double secondAmplitude = second.amplitude();
        if (firstAmplitude == 0.0 || secondAmplitude == 0.0) {
            return 0.0;
        }

        double amplitudeSimilarity = relativeSimilarity(firstAmplitude, secondAmplitude);
        double frequencySimilarity = frequencySimilarity(first.frequency(), second.frequency());
        double phaseSimilarity = phaseSimilarity(first.phase(), second.phase());
        return amplitudeSimilarity * frequencySimilarity * phaseSimilarity;
    }

    private double relativeSimilarity(double first, double second) {
        return first < second ? first / second : second / first;
    }

    private double frequencySimilarity(double first, double second) {
        if (first == second) {
            return 1.0;
        }
        if (first == 0.0 || second == 0.0) {
            return 0.0;
        }
        return relativeSimilarity(first, second);
    }

    private double phaseSimilarity(double first, double second) {
        double wrappedFirst = StrictMath.IEEEremainder(first, TWO_PI);
        double wrappedSecond = StrictMath.IEEEremainder(second, TWO_PI);
        double delta = StrictMath.IEEEremainder(wrappedFirst - wrappedSecond, TWO_PI);
        return (1.0 + StrictMath.cos(delta)) / 2.0;
    }
}
