package monada.neuron.resonance;

/**
 * Child-JVM probe used to verify that the default evaluator remains usable when the Vector API
 * module is not present at runtime.
 */
public final class NoVectorModuleFallbackProbe {

    private NoVectorModuleFallbackProbe() {
    }

    public static void main(String[] args) {
        BatchResonanceEvaluator evaluator = BatchResonanceEvaluator.defaultEvaluator();
        require(evaluator instanceof AdaptiveBatchResonanceEvaluator,
                "default evaluator must remain the adaptive evaluator");

        AdaptiveBatchResonanceEvaluator adaptive = (AdaptiveBatchResonanceEvaluator) evaluator;
        require(evaluator.isAvailable(), "default evaluator must be available");
        require(!adaptive.isVectorAvailable(), "Vector API must be unavailable without its module");

        double[] firstAmplitudes = {1.0, 2.0};
        double[] firstFrequencies = {440.0, 220.0};
        double[] firstPhases = {0.0, Math.PI / 2.0};
        double[] secondAmplitudes = {1.0, 1.0};
        double[] secondFrequencies = {440.0, 110.0};
        double[] secondPhases = {0.0, Math.PI};
        double[] actual = new double[2];
        double[] expected = new double[2];

        evaluator.scoreBatch(
                firstAmplitudes, firstFrequencies, firstPhases,
                secondAmplitudes, secondFrequencies, secondPhases,
                actual, 0, actual.length);
        ScalarBatchResonanceEvaluator.INSTANCE.scoreBatch(
                firstAmplitudes, firstFrequencies, firstPhases,
                secondAmplitudes, secondFrequencies, secondPhases,
                expected, 0, expected.length);

        for (int index = 0; index < actual.length; index++) {
            require(Double.doubleToLongBits(actual[index]) == Double.doubleToLongBits(expected[index]),
                    "scalar fallback mismatch at index " + index);
        }

        System.out.println("SCALAR_FALLBACK_OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
