package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;

import java.util.Objects;

/**
 * Capability-driven adaptive batch resonance evaluator.
 *
 * <p>Automatically routes batch evaluation requests to {@link VectorBatchResonanceEvaluator} when
 * the Java 26 Vector API is available and the batch size amortizes SIMD dispatch overhead
 * (default: &ge; 4 pairs). Otherwise routes to {@link ScalarBatchResonanceEvaluator}.
 *
 * <p>Vector API linkage is deferred dynamically so runtimes without the incubator module degrade
 * gracefully to the scalar reference backend without {@link NoClassDefFoundError}. Backend
 * initialization failures remain visible rather than being treated as missing capability.
 */
public final class AdaptiveBatchResonanceEvaluator implements BatchResonanceEvaluator {

    /** Default crossover threshold below which the scalar backend is preferred. */
    public static final int DEFAULT_CROSSOVER_THRESHOLD = 4;

    /** Singleton instance with default crossover threshold. */
    public static final AdaptiveBatchResonanceEvaluator INSTANCE = new AdaptiveBatchResonanceEvaluator(DEFAULT_CROSSOVER_THRESHOLD);

    private final int crossoverThreshold;
    private final BatchResonanceEvaluator vectorEvaluator;
    private final BatchResonanceEvaluator scalarEvaluator;

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
        this.scalarEvaluator = ScalarBatchResonanceEvaluator.INSTANCE;
        this.vectorEvaluator = loadVectorEvaluatorIfAvailable();
    }

    private static BatchResonanceEvaluator loadVectorEvaluatorIfAvailable() {
        try {
            Class<?> clazz = Class.forName(
                    "monada.neuron.resonance.VectorBatchResonanceEvaluator",
                    true,
                    AdaptiveBatchResonanceEvaluator.class.getClassLoader());
            BatchResonanceEvaluator evaluator = (BatchResonanceEvaluator) clazz.getField("INSTANCE").get(null);
            return evaluator.isAvailable() ? evaluator : null;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return null;
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access the Vector API batch evaluator", exception);
        }
    }

    /** Returns the crossover threshold configured for this evaluator. */
    public int crossoverThreshold() {
        return crossoverThreshold;
    }

    /** Returns whether the Vector API backend is available and active in this evaluator. */
    public boolean isVectorAvailable() {
        return vectorEvaluator != null && vectorEvaluator.isAvailable();
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
        if (vectorEvaluator != null && vectorEvaluator.isAvailable() && length >= crossoverThreshold) {
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
        if (vectorEvaluator != null && vectorEvaluator.isAvailable() && length >= crossoverThreshold) {
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
