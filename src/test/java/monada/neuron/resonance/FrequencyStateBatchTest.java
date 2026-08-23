package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrequencyStateBatchTest {

    @Test
    void constructsValidBatchFromPrimitiveArrays() {
        double[] a = {1.0, 2.0, 3.0};
        double[] f = {10.0, 20.0, 30.0};
        double[] p = {0.0, 1.5, 3.14};

        var batch = FrequencyStateBatch.of(a, f, p);

        assertAll(
                () -> assertEquals(3, batch.size()),
                () -> assertEquals(1.0, batch.amplitude(0)),
                () -> assertEquals(20.0, batch.frequency(1)),
                () -> assertEquals(3.14, batch.phase(2)),
                () -> assertEquals(new FrequencyState(2.0, 20.0, 1.5), batch.get(1)));
    }

    @Test
    void defensiveCopyingProtectsImmutability() {
        double[] a = {1.0, 2.0};
        double[] f = {10.0, 20.0};
        double[] p = {0.0, 1.0};

        var batch = FrequencyStateBatch.of(a, f, p);
        a[0] = 999.0;
        f[0] = 888.0;
        p[0] = 777.0;

        assertAll(
                () -> assertEquals(1.0, batch.amplitude(0)),
                () -> assertEquals(10.0, batch.frequency(0)),
                () -> assertEquals(0.0, batch.phase(0)));

        double[] retrievedAmplitudes = batch.amplitudes();
        retrievedAmplitudes[0] = 555.0;
        assertEquals(1.0, batch.amplitude(0));
    }

    @Test
    void constructsFromFrequencyStateArrayAndList() {
        var states = List.of(
                new FrequencyState(1.0, 100.0, 0.0),
                new FrequencyState(2.0, 200.0, 1.5),
                FrequencyState.ZERO);

        var batchFromList = FrequencyStateBatch.fromStates(states);
        var batchFromArray = FrequencyStateBatch.fromStates(states.toArray(new FrequencyState[0]));

        assertAll(
                () -> assertEquals(3, batchFromList.size()),
                () -> assertEquals(batchFromList, batchFromArray),
                () -> assertEquals(batchFromList.hashCode(), batchFromArray.hashCode()),
                () -> assertEquals(new FrequencyState(2.0, 200.0, 1.5), batchFromList.get(1)),
                () -> assertEquals(FrequencyState.ZERO, batchFromList.get(2)));
    }

    @Test
    void rejectsNullArraysAndInvalidSizes() {
        double[] valid = {1.0};

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new FrequencyStateBatch(null, valid, valid, 1)),
                () -> assertThrows(NullPointerException.class, () -> new FrequencyStateBatch(valid, null, valid, 1)),
                () -> assertThrows(NullPointerException.class, () -> new FrequencyStateBatch(valid, valid, null, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new FrequencyStateBatch(valid, valid, valid, -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new FrequencyStateBatch(valid, valid, valid, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> FrequencyStateBatch.of(new double[2], new double[3], new double[2])));
    }

    @Test
    void rejectsNegativeAndNonFiniteValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FrequencyStateBatch.of(new double[]{-1.0}, new double[]{1.0}, new double[]{0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FrequencyStateBatch.of(new double[]{Double.NaN}, new double[]{1.0}, new double[]{0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FrequencyStateBatch.of(new double[]{1.0}, new double[]{-10.0}, new double[]{0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FrequencyStateBatch.of(new double[]{1.0}, new double[]{Double.POSITIVE_INFINITY}, new double[]{0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FrequencyStateBatch.of(new double[]{1.0}, new double[]{1.0}, new double[]{Double.NaN})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FrequencyStateBatch.of(new double[]{1.0}, new double[]{1.0}, new double[]{Double.NEGATIVE_INFINITY})));
    }

    @Test
    void indexOutOfBoundsChecks() {
        var batch = FrequencyStateBatch.of(new double[]{1.0}, new double[]{1.0}, new double[]{0.0});

        assertAll(
                () -> assertThrows(IndexOutOfBoundsException.class, () -> batch.amplitude(-1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> batch.amplitude(1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> batch.frequency(2)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> batch.phase(-1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> batch.get(5)));
    }

    @Test
    void equalsAndHashCodeSemantics() {
        var b1 = FrequencyStateBatch.of(new double[]{1.0, 2.0}, new double[]{10.0, 20.0}, new double[]{0.0, 0.5});
        var b2 = FrequencyStateBatch.of(new double[]{1.0, 2.0}, new double[]{10.0, 20.0}, new double[]{0.0, 0.5});
        var b3 = FrequencyStateBatch.of(new double[]{1.0, 3.0}, new double[]{10.0, 20.0}, new double[]{0.0, 0.5});

        assertAll(
                () -> assertEquals(b1, b1),
                () -> assertEquals(b1, b2),
                () -> assertEquals(b1.hashCode(), b2.hashCode()),
                () -> assertNotEquals(b1, b3),
                () -> assertNotEquals(b1, null),
                () -> assertNotNull(b1.toString()));
    }
}
