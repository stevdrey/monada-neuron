package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;

import java.util.Objects;

/**
 * High-performance batch evaluator for resonance scores between pairs of {@link FrequencyState} values.
 *
 * <p>Supports both Structure-of-Arrays (SoA) contiguous primitive arrays and Object arrays (AoO).
 * Implementations must document their availability, tolerance, and tail-handling semantics.
 */
public interface BatchResonanceEvaluator {

    /**
     * Scores a batch of frequency state pairs provided as contiguous parallel primitive arrays (SoA).
     *
     * @param firstAmplitudes non-negative finite amplitudes of first states
     * @param firstFrequencies non-negative finite frequencies of first states
     * @param firstPhases finite phases of first states
     * @param secondAmplitudes non-negative finite amplitudes of second states
     * @param secondFrequencies non-negative finite frequencies of second states
     * @param secondPhases finite phases of second states
     * @param results destination array for computed resonance scores
     * @param offset starting index in the input and result arrays
     * @param length number of pairs to evaluate
     * @throws NullPointerException if any array is {@code null}
     * @throws IllegalArgumentException if offset/length are invalid or any value violates domain constraints
     * @throws IndexOutOfBoundsException if array bounds are exceeded
     */
    void scoreBatch(
            double[] firstAmplitudes,
            double[] firstFrequencies,
            double[] firstPhases,
            double[] secondAmplitudes,
            double[] secondFrequencies,
            double[] secondPhases,
            double[] results,
            int offset,
            int length);

    /**
     * Scores a batch of frequency states from two {@link FrequencyStateBatch} instances into {@code results}.
     *
     * @param first batch of first states
     * @param second batch of second states
     * @param results destination array for computed resonance scores
     * @param offset starting index in the input batches and result array
     * @param length number of pairs to evaluate
     */
    default void scoreBatch(
            FrequencyStateBatch first,
            FrequencyStateBatch second,
            double[] results,
            int offset,
            int length) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        Objects.requireNonNull(results, "results must not be null");

        scoreBatch(
                first.rawAmplitudes(),
                first.rawFrequencies(),
                first.rawPhases(),
                second.rawAmplitudes(),
                second.rawFrequencies(),
                second.rawPhases(),
                results,
                offset,
                length);
    }

    /**
     * Scores a batch of frequency states from arrays of {@link FrequencyState} objects into {@code results}.
     *
     * @param first array of first frequency states
     * @param second array of second frequency states
     * @param results destination array for computed resonance scores
     * @param offset starting index in arrays
     * @param length number of pairs to evaluate
     */
    void scoreBatch(
            FrequencyState[] first,
            FrequencyState[] second,
            double[] results,
            int offset,
            int length);

    /**
     * Returns whether this evaluator backend is supported and available in the current runtime environment.
     *
     * @return {@code true} if available, {@code false} otherwise
     */
    boolean isAvailable();

    /**
     * Returns the portable scalar batch reference evaluator (always available, zero incubator dependencies).
     *
     * @return scalar reference batch evaluator
     */
    static BatchResonanceEvaluator scalar() {
        return ScalarBatchResonanceEvaluator.INSTANCE;
    }

    /**
     * Returns the Java 26 Vector API SIMD batch evaluator, or the scalar reference evaluator if unavailable.
     *
     * @return vector batch evaluator if available, otherwise scalar evaluator
     */
    static BatchResonanceEvaluator vector() {
        try {
            Class<?> clazz = Class.forName(
                    "monada.neuron.resonance.VectorBatchResonanceEvaluator",
                    true,
                    BatchResonanceEvaluator.class.getClassLoader());
            BatchResonanceEvaluator evaluator = (BatchResonanceEvaluator) clazz.getField("INSTANCE").get(null);
            return evaluator.isAvailable() ? evaluator : ScalarBatchResonanceEvaluator.INSTANCE;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return ScalarBatchResonanceEvaluator.INSTANCE;
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access the Vector API batch evaluator", exception);
        }
    }

    /**
     * Returns an adaptive batch evaluator that uses the Vector API when available and profitable (batch size &ge; threshold),
     * and otherwise falls back to the scalar evaluator.
     *
     * @return adaptive batch evaluator
     */
    static BatchResonanceEvaluator adaptive() {
        return AdaptiveBatchResonanceEvaluator.INSTANCE;
    }

    /**
     * Returns the default system batch evaluator (adaptive).
     *
     * @return default batch evaluator
     */
    static BatchResonanceEvaluator defaultEvaluator() {
        return adaptive();
    }
}
