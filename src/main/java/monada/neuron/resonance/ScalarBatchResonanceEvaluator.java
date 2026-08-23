package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;

import java.util.Objects;

/**
 * Portable, deterministic reference scalar batch evaluator for {@link FrequencyState} values.
 *
 * <p>Executes the exact normalized resonance formula without allocating objects in the hot path
 * and with zero incubator or native dependencies.
 */
public final class ScalarBatchResonanceEvaluator implements BatchResonanceEvaluator {

    /** Singleton instance. */
    public static final ScalarBatchResonanceEvaluator INSTANCE = new ScalarBatchResonanceEvaluator();

    private static final double TWO_PI = 2.0 * StrictMath.PI;

    /** Public constructor. */
    public ScalarBatchResonanceEvaluator() {
    }

    @Override
    public void scoreBatch(
            double[] firstAmplitudes,
            double[] firstFrequencies,
            double[] firstPhases,
            double[] secondAmplitudes,
            double[] secondFrequencies,
            double[] secondPhases,
            double[] results,
            int offset,
            int length) {
        validatePrimitiveInputs(
                firstAmplitudes,
                firstFrequencies,
                firstPhases,
                secondAmplitudes,
                secondFrequencies,
                secondPhases,
                results,
                offset,
                length);

        int end = offset + length;
        for (int i = offset; i < end; i++) {
            double a1 = firstAmplitudes[i];
            double a2 = secondAmplitudes[i];
            double f1 = firstFrequencies[i];
            double f2 = secondFrequencies[i];
            double p1 = firstPhases[i];
            double p2 = secondPhases[i];

            validateValues(a1, f1, p1, a2, f2, p2, i);

            results[i] = computeScalarScore(a1, f1, p1, a2, f2, p2);
        }
    }

    @Override
    public void scoreBatch(
            FrequencyState[] first,
            FrequencyState[] second,
            double[] results,
            int offset,
            int length) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        Objects.requireNonNull(results, "results must not be null");

        if (offset < 0 || length < 0 || offset + length > first.length || offset + length > second.length || offset + length > results.length) {
            throw new IndexOutOfBoundsException(String.format(
                    "Invalid offset/length: offset=%d, length=%d, first=%d, second=%d, results=%d",
                    offset, length, first.length, second.length, results.length));
        }

        int end = offset + length;
        for (int i = offset; i < end; i++) {
            FrequencyState s1 = Objects.requireNonNull(first[i], "first state at index " + i + " must not be null");
            FrequencyState s2 = Objects.requireNonNull(second[i], "second state at index " + i + " must not be null");

            results[i] = computeScalarScore(
                    s1.amplitude(), s1.frequency(), s1.phase(),
                    s2.amplitude(), s2.frequency(), s2.phase());
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private double computeScalarScore(double a1, double f1, double p1, double a2, double f2, double p2) {
        if (a1 == 0.0 || a2 == 0.0) {
            return 0.0;
        }

        double simA = a1 < a2 ? a1 / a2 : a2 / a1;

        double simF;
        if (f1 == f2) {
            simF = 1.0;
        } else if (f1 == 0.0 || f2 == 0.0) {
            simF = 0.0;
        } else {
            simF = f1 < f2 ? f1 / f2 : f2 / f1;
        }

        double w1 = StrictMath.IEEEremainder(p1, TWO_PI);
        double w2 = StrictMath.IEEEremainder(p2, TWO_PI);
        double delta = StrictMath.IEEEremainder(w1 - w2, TWO_PI);
        double simP = (1.0 + StrictMath.cos(delta)) / 2.0;

        return simA * simF * simP;
    }

    private void validatePrimitiveInputs(
            double[] a1, double[] f1, double[] p1,
            double[] a2, double[] f2, double[] p2,
            double[] results, int offset, int length) {
        Objects.requireNonNull(a1, "firstAmplitudes must not be null");
        Objects.requireNonNull(f1, "firstFrequencies must not be null");
        Objects.requireNonNull(p1, "firstPhases must not be null");
        Objects.requireNonNull(a2, "secondAmplitudes must not be null");
        Objects.requireNonNull(f2, "secondFrequencies must not be null");
        Objects.requireNonNull(p2, "secondPhases must not be null");
        Objects.requireNonNull(results, "results must not be null");

        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must be non-negative, got offset=" + offset + ", length=" + length);
        }

        int end = offset + length;
        if (end > a1.length || end > f1.length || end > p1.length
                || end > a2.length || end > f2.length || end > p2.length
                || end > results.length) {
            throw new IndexOutOfBoundsException(String.format(
                    "Range [%d, %d) exceeds array bounds", offset, end));
        }
    }

    private void validateValues(double a1, double f1, double p1, double a2, double f2, double p2, int index) {
        if (!Double.isFinite(a1) || a1 < 0.0 || !Double.isFinite(a2) || a2 < 0.0) {
            throw new IllegalArgumentException("amplitude at index " + index + " must be non-negative and finite");
        }
        if (!Double.isFinite(f1) || f1 < 0.0 || !Double.isFinite(f2) || f2 < 0.0) {
            throw new IllegalArgumentException("frequency at index " + index + " must be non-negative and finite");
        }
        if (!Double.isFinite(p1) || !Double.isFinite(p2)) {
            throw new IllegalArgumentException("phase at index " + index + " must be finite");
        }
    }
}
