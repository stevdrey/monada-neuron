package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;

import java.util.Objects;

/**
 * Capability-driven adaptive batch resonance evaluator.
 *
 * <p>Automatically routes batch evaluation requests to {@link VectorBatchResonanceEvaluator} when
 * the Java 26 Vector API is available and the batch size amortizes SIMD dispatch overhead
 * (default: &ge; 64 pairs). Otherwise routes to {@link ScalarBatchResonanceEvaluator}.
 */
public final class AdaptiveBatchResonanceEvaluator implements BatchResonanceEvaluator {

    /** Default crossover threshold below which the scalar backend is preferred. */
    public static final int DEFAULT_CROSSOVER_THRESHOLD = 64;

    /** Singleton instance with default crossover threshold. */
    public static final AdaptiveBatchResonanceEvaluator INSTANCE = new AdaptiveBatchResonanceEvaluator(DEFAULT_CROSSOVER_THRESHOLD);

    private final int crossoverThreshold;
    private final VectorBatchResonanceEvaluator vectorEvaluator;
    private final ScalarBatchResonanceEvaluator scalarEvaluator;

    /**
     * Creates an adaptive evaluator with the specified crossover threshold.
     *
     * @param crossoverThreshold minimum batch size to dispatch to Vector API backend
     */
    public AdaptiveBatchResonanceEvaluator(int crossoverThreshold) {
        if (crossoverThreshold < 0) {
            throw new IllegalArgumentException("crossoverThreshold must be non-negative, got: " + crossoverThreshold);
        }
        this.crossoverThreshold = crossoverThreshold;
        this.vectorEvaluator = VectorBatchResonanceEvaluator.INSTANCE;
        this.scalarEvaluator = ScalarBatchResonanceEvaluator.INSTANCE;
    }

    /** Returns the crossover threshold configured for this evaluator. */
    public int crossoverThreshold() {
        return crossoverThreshold;
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
        if (vectorEvaluator.isAvailable() && length >= crossoverThreshold) {
            vectorEvaluator.scoreBatch(
                    firstAmplitudes, firstFrequencies, firstPhases,
                    secondAmplitudes, secondFrequencies, secondPhases,
                    results, offset, length);
        } else {
            scalarEvaluator.scoreBatch(
                    firstAmplitudes, firstFrequencies, firstPhases,
                    secondAmplitudes, secondFrequencies, secondPhases,
                    results, offset, length);
        }
    }

    @Override
    public void scoreBatch(
            FrequencyState[] first,
            FrequencyState[] second,
            double[] results,
            int offset,
            int length) {
        if (vectorEvaluator.isAvailable() && length >= crossoverThreshold) {
            vectorEvaluator.scoreBatch(first, second, results, offset, length);
        } else {
            scalarEvaluator.scoreBatch(first, second, results, offset, length);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
