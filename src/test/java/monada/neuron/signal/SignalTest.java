package monada.neuron.signal;

import monada.neuron.model.FrequencyState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalTest {

    @Test
    void constructionRetainsExplicitComponents() {
        var id = new UUID(1L, 2L);
        var state = new FrequencyState(1.5, 440.0, Math.PI);

        var signal = new Signal(id, SignalKind.OBSERVATION, state);

        assertEquals(id, signal.id());
        assertEquals(SignalKind.OBSERVATION, signal.kind());
        assertEquals(state, signal.frequencyState());
    }

    @Test
    void constructionRejectsNullComponents() {
        var id = new UUID(1L, 2L);
        var state = FrequencyState.ZERO;

        assertThrows(NullPointerException.class,
                () -> new Signal(null, SignalKind.OBSERVATION, state));
        assertThrows(NullPointerException.class, () -> new Signal(id, null, state));
        assertThrows(NullPointerException.class,
                () -> new Signal(id, SignalKind.OBSERVATION, null));
    }

    @Test
    void equalityUsesAllRecordComponents() {
        var id = new UUID(1L, 2L);
        var state = new FrequencyState(1.0, 10.0, 0.5);

        var first = new Signal(id, SignalKind.INTERMEDIATE, state);
        var equal = new Signal(id, SignalKind.INTERMEDIATE, state);
        var differentKind = new Signal(id, SignalKind.FEEDBACK, state);
        var differentState = new Signal(
                id,
                SignalKind.INTERMEDIATE,
                new FrequencyState(2.0, 10.0, 0.5));

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, differentKind);
        assertNotEquals(first, differentState);
    }

    @Test
    void publicShapeRequiresUuidAndContainsNoSequenceOrEnergy() {
        assertTrue(Signal.class.isRecord());
        assertArrayEquals(
                new String[] {"id", "kind", "frequencyState"},
                Arrays.stream(Signal.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        assertArrayEquals(
                new Class<?>[] {UUID.class, SignalKind.class, FrequencyState.class},
                Arrays.stream(Signal.class.getRecordComponents())
                        .map(RecordComponent::getType)
                        .toArray(Class<?>[]::new));
    }
}
