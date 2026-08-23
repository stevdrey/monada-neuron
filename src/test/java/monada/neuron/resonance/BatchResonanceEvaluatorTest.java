package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchResonanceEvaluatorTest {

    private static final double TOLERANCE = 1.0e-12;

    private final ScalarResonanceMetric oracle = new ScalarResonanceMetric();
    private final BatchResonanceEvaluator scalarBatch = ScalarBatchResonanceEvaluator.INSTANCE;
    private final BatchResonanceEvaluator vectorBatch = VectorBatchResonanceEvaluator.INSTANCE;
    private final BatchResonanceEvaluator adaptiveBatch = AdaptiveBatchResonanceEvaluator.INSTANCE;

    @Test
    void evaluatorsAreAvailableAndDocumentSpecies() {
        assertAll(
                () -> assertTrue(scalarBatch.isAvailable()),
                () -> assertTrue(adaptiveBatch.isAvailable()),
                () -> assertNotNull(BatchResonanceEvaluator.scalar()),
                () -> assertNotNull(BatchResonanceEvaluator.vector()),
                () -> assertNotNull(BatchResonanceEvaluator.adaptive()),
                () -> assertNotNull(BatchResonanceEvaluator.defaultEvaluator()),
                () -> {
                    if (vectorBatch instanceof VectorBatchResonanceEvaluator v) {
                        if (v.isAvailable()) {
                            assertTrue(v.vectorWidth() >= 2);
                            assertTrue(v.species() != null);
                        }
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 33, 63, 64, 65, 127, 128, 129, 500, 1023, 1024, 1025})
    void equivalenceWithScalarOracleAcrossVariousBatchSizes(int size) {
        if (size == 0) {
            double[] resScalar = new double[0];
            double[] resVector = new double[0];
            double[] resAdaptive = new double[0];
            scalarBatch.scoreBatch(new double[0], new double[0], new double[0], new double[0], new double[0], new double[0], resScalar, 0, 0);
            vectorBatch.scoreBatch(new double[0], new double[0], new double[0], new double[0], new double[0], new double[0], resVector, 0, 0);
            adaptiveBatch.scoreBatch(new double[0], new double[0], new double[0], new double[0], new double[0], new double[0], resAdaptive, 0, 0);
            return;
        }

        var random = new Random(42L + size);
        var firstStates = new FrequencyState[size];
        var secondStates = new FrequencyState[size];
        var expected = new double[size];

        for (int i = 0; i < size; i++) {
            firstStates[i] = randomState(random);
            secondStates[i] = randomState(random);
            expected[i] = oracle.score(firstStates[i], secondStates[i]);
        }

        var firstBatch = FrequencyStateBatch.fromStates(firstStates);
        var secondBatch = FrequencyStateBatch.fromStates(secondStates);

        double[] actualScalarSoA = new double[size];
        double[] actualVectorSoA = new double[size];
        double[] actualAdaptiveSoA = new double[size];

        scalarBatch.scoreBatch(firstBatch, secondBatch, actualScalarSoA, 0, size);
        vectorBatch.scoreBatch(firstBatch, secondBatch, actualVectorSoA, 0, size);
        adaptiveBatch.scoreBatch(firstBatch, secondBatch, actualAdaptiveSoA, 0, size);

        double[] actualScalarAoO = new double[size];
        double[] actualVectorAoO = new double[size];
        double[] actualAdaptiveAoO = new double[size];

        scalarBatch.scoreBatch(firstStates, secondStates, actualScalarAoO, 0, size);
        vectorBatch.scoreBatch(firstStates, secondStates, actualVectorAoO, 0, size);
        adaptiveBatch.scoreBatch(firstStates, secondStates, actualAdaptiveAoO, 0, size);

        for (int i = 0; i < size; i++) {
            double exp = expected[i];
            assertEquals(exp, actualScalarSoA[i], TOLERANCE, "Scalar SoA mismatch at index " + i);
            assertEquals(exp, actualVectorSoA[i], TOLERANCE, "Vector SoA mismatch at index " + i);
            assertEquals(exp, actualAdaptiveSoA[i], TOLERANCE, "Adaptive SoA mismatch at index " + i);

            assertEquals(exp, actualScalarAoO[i], TOLERANCE, "Scalar AoO mismatch at index " + i);
            assertEquals(exp, actualVectorAoO[i], TOLERANCE, "Vector AoO mismatch at index " + i);
            assertEquals(exp, actualAdaptiveAoO[i], TOLERANCE, "Adaptive AoO mismatch at index " + i);
        }
    }

    @Test
    void largePhaseValuesEquivalenceAcrossThresholds() {
        double[] largePhases = {
                500.0, 1000.0, 2000.0, 5000.0, 1.0e5, 1.0e6, 1.0e7, 1.0e10, 1.0e12,
                7.3e13, 7.5e13, 1.0e14, 1.0e15, Double.MAX_VALUE
        };

        var pairs = new ArrayList<FrequencyState[]>();
        for (double p1 : largePhases) {
            for (double p2 : largePhases) {
                pairs.add(new FrequencyState[]{
                        new FrequencyState(1.5, 100.0, p1),
                        new FrequencyState(2.0, 100.0, p2)
                });
            }
        }

        int count = pairs.size();
        var first = new FrequencyState[count];
        var second = new FrequencyState[count];
        var expected = new double[count];

        for (int i = 0; i < count; i++) {
            first[i] = pairs.get(i)[0];
            second[i] = pairs.get(i)[1];
            expected[i] = oracle.score(first[i], second[i]);
        }

        var b1 = FrequencyStateBatch.fromStates(first);
        var b2 = FrequencyStateBatch.fromStates(second);

        double[] resScalar = new double[count];
        double[] resVector = new double[count];
        double[] resAdaptive = new double[count];

        scalarBatch.scoreBatch(b1, b2, resScalar, 0, count);
        vectorBatch.scoreBatch(b1, b2, resVector, 0, count);
        adaptiveBatch.scoreBatch(b1, b2, resAdaptive, 0, count);

        for (int i = 0; i < count; i++) {
            double exp = expected[i];
            assertEquals(exp, resScalar[i], TOLERANCE, "Scalar mismatch at large phase index " + i);
            assertEquals(exp, resVector[i], TOLERANCE, "Vector mismatch at large phase index " + i);
            assertEquals(exp, resAdaptive[i], TOLERANCE, "Adaptive mismatch at large phase index " + i);
        }
    }

    @Test
    void edgeCasesEquivalenceAcrossBackends() {
        var states = List.of(
                FrequencyState.ZERO,
                new FrequencyState(0.0, 440.0, 0.0),
                new FrequencyState(1.0, 0.0, 0.0),
                new FrequencyState(1.0, 440.0, 0.0),
                new FrequencyState(1.0, 440.0, Math.PI / 2.0),
                new FrequencyState(1.0, 440.0, Math.PI),
                new FrequencyState(1.0, 440.0, 2.0 * Math.PI),
                new FrequencyState(2.0, 880.0, 0.0),
                new FrequencyState(1.0, 1.0, Double.MAX_VALUE),
                new FrequencyState(1.0, 1.0, -Double.MAX_VALUE));

        var pairs = new ArrayList<FrequencyState[]>();
        for (var s1 : states) {
            for (var s2 : states) {
                pairs.add(new FrequencyState[]{s1, s2});
            }
        }

        int count = pairs.size();
        var first = new FrequencyState[count];
        var second = new FrequencyState[count];
        var expected = new double[count];

        for (int i = 0; i < count; i++) {
            first[i] = pairs.get(i)[0];
            second[i] = pairs.get(i)[1];
            expected[i] = oracle.score(first[i], second[i]);
        }

        var b1 = FrequencyStateBatch.fromStates(first);
        var b2 = FrequencyStateBatch.fromStates(second);

        double[] resScalar = new double[count];
        double[] resVector = new double[count];
        double[] resAdaptive = new double[count];

        scalarBatch.scoreBatch(b1, b2, resScalar, 0, count);
        vectorBatch.scoreBatch(b1, b2, resVector, 0, count);
        adaptiveBatch.scoreBatch(b1, b2, resAdaptive, 0, count);

        for (int i = 0; i < count; i++) {
            double exp = expected[i];
            assertEquals(exp, resScalar[i], TOLERANCE, "Scalar mismatch at edge case " + i);
            assertEquals(exp, resVector[i], TOLERANCE, "Vector mismatch at edge case " + i);
            assertEquals(exp, resAdaptive[i], TOLERANCE, "Adaptive mismatch at edge case " + i);
        }
    }

    @Test
    void offsetAndSliceExecution() {
        int total = 100;
        int offset = 20;
        int length = 50;

        var random = new Random(12345L);
        var first = new FrequencyState[total];
        var second = new FrequencyState[total];
        for (int i = 0; i < total; i++) {
            first[i] = randomState(random);
            second[i] = randomState(random);
        }

        var b1 = FrequencyStateBatch.fromStates(first);
        var b2 = FrequencyStateBatch.fromStates(second);

        double[] resultsScalar = new double[total];
        double[] resultsVector = new double[total];

        scalarBatch.scoreBatch(b1, b2, resultsScalar, offset, length);
        vectorBatch.scoreBatch(b1, b2, resultsVector, offset, length);

        for (int i = 0; i < total; i++) {
            if (i >= offset && i < offset + length) {
                double exp = oracle.score(first[i], second[i]);
                assertEquals(exp, resultsScalar[i], TOLERANCE);
                assertEquals(exp, resultsVector[i], TOLERANCE);
            } else {
                assertEquals(0.0, resultsScalar[i]);
                assertEquals(0.0, resultsVector[i]);
            }
        }
    }

    @Test
    void rejectsInvalidArgumentsAndRanges() {
        double[] valid = new double[10];

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> scalarBatch.scoreBatch(null, valid, valid, valid, valid, valid, valid, 0, 10)),
                () -> assertThrows(NullPointerException.class, () -> vectorBatch.scoreBatch(null, valid, valid, valid, valid, valid, valid, 0, 10)),
                () -> assertThrows(IllegalArgumentException.class, () -> scalarBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, -1, 5)),
                () -> assertThrows(IllegalArgumentException.class, () -> vectorBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, 0, -1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> scalarBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, 5, 10)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> vectorBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, 5, 10)),
                // Overflow tests
                () -> assertThrows(IndexOutOfBoundsException.class, () -> scalarBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, Integer.MAX_VALUE, 1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> vectorBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, Integer.MAX_VALUE, 1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> scalarBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, 1, Integer.MAX_VALUE)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> vectorBatch.scoreBatch(valid, valid, valid, valid, valid, valid, valid, 1, Integer.MAX_VALUE)));
    }

    @Test
    void rejectsInvalidNumericInputsInBatch() {
        double[] invalidAmp = {-1.0, 1.0, 1.0, 1.0};
        double[] valid = {1.0, 1.0, 1.0, 1.0};
        double[] res = new double[4];

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> scalarBatch.scoreBatch(invalidAmp, valid, valid, valid, valid, valid, res, 0, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> vectorBatch.scoreBatch(invalidAmp, valid, valid, valid, valid, valid, res, 0, 4)));
    }

    private FrequencyState randomState(Random random) {
        double a = random.nextDouble() < 0.1 ? 0.0 : random.nextDouble() * 5.0;
        double f = random.nextDouble() < 0.1 ? 0.0 : random.nextDouble() * 200.0;
        double p = random.nextDouble() * 4.0 * Math.PI - 2.0 * Math.PI;
        return new FrequencyState(a, f, p);
    }
}
