package monada.neuron.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NodeTest {

    // =========================================================================
    // FrequencyState tests
    // =========================================================================

    @Nested
    @DisplayName("FrequencyState")
    class FrequencyStateTests {

        @Test
        @DisplayName("ZERO constant has all-zero components")
        void zeroConstant() {
            assertEquals(0.0, FrequencyState.ZERO.amplitude());
            assertEquals(0.0, FrequencyState.ZERO.frequency());
            assertEquals(0.0, FrequencyState.ZERO.phase());
        }

        @Test
        @DisplayName("Valid construction succeeds")
        void validConstruction() {
            var state = new FrequencyState(1.5, 440.0, Math.PI);
            assertEquals(1.5, state.amplitude());
            assertEquals(440.0, state.frequency());
            assertEquals(Math.PI, state.phase());
        }

        @Test
        @DisplayName("Negative amplitude throws")
        void negativeAmplitudeThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(-1.0, 1.0, 0.0));
        }

        @Test
        @DisplayName("Negative frequency throws")
        void negativeFrequencyThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(1.0, -1.0, 0.0));
        }

        @Test
        @DisplayName("withScaledAmplitude returns new instance with scaled amplitude")
        void scaledAmplitude() {
            var state = new FrequencyState(2.0, 100.0, 0.5);
            var scaled = state.withScaledAmplitude(3.0);
            assertEquals(6.0, scaled.amplitude(), 1e-9);
            assertEquals(100.0, scaled.frequency());
            assertEquals(0.5, scaled.phase());
        }

        @Test
        @DisplayName("withScaledAmplitude with negative factor throws")
        void scaledAmplitudeNegativeFactorThrows() {
            var state = new FrequencyState(1.0, 1.0, 0.0);
            assertThrows(IllegalArgumentException.class, () -> state.withScaledAmplitude(-0.1));
        }

        @Test
        @DisplayName("withPhaseShift returns new instance with shifted phase")
        void phaseShift() {
            var state = new FrequencyState(1.0, 50.0, 1.0);
            var shifted = state.withPhaseShift(Math.PI);
            assertEquals(1.0 + Math.PI, shifted.phase(), 1e-9);
            assertEquals(1.0, shifted.amplitude());
            assertEquals(50.0, shifted.frequency());
        }
    }

    // =========================================================================
    // Node.Builder tests
    // =========================================================================

    @Nested
    @DisplayName("Node.Builder")
    class BuilderTests {

        @Test
        @DisplayName("Build without type throws IllegalStateException")
        void buildWithoutTypeThrows() {
            assertThrows(IllegalStateException.class, () -> new Node.Builder().build());
        }

        @Test
        @DisplayName("Minimal build assigns defaults")
        void minimalBuild() {
            var node = new Node.Builder().type(NodeType.INPUT).build();
            assertNotNull(node.getId());
            assertEquals(NodeType.INPUT, node.getType());
            assertEquals(FrequencyState.ZERO, node.getFrequencyState());
            assertEquals(0.0, node.getEnergy());
            assertTrue(node.getConnections().isEmpty());
            assertTrue(node.getHistory().isEmpty());
        }

        @Test
        @DisplayName("Builder respects explicit id")
        void explicitId() {
            var uuid = UUID.randomUUID();
            var node = new Node.Builder().id(uuid).type(NodeType.MEMORY).build();
            assertEquals(uuid, node.getId());
        }

        @Test
        @DisplayName("Builder respects explicit energy")
        void explicitEnergy() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).energy(3.14).build();
            assertEquals(3.14, node.getEnergy(), 1e-9);
        }

        @Test
        @DisplayName("Builder rejects negative energy")
        void negativeEnergyThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Node.Builder().type(NodeType.PROCESSOR).energy(-1.0).build());
        }
    }

    // =========================================================================
    // Node connections tests
    // =========================================================================

    @Nested
    @DisplayName("Node connections")
    class ConnectionTests {

        @Test
        @DisplayName("connect adds target to connections")
        void connectAddsTarget() {
            var a = new Node.Builder().type(NodeType.INPUT).build();
            var b = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertTrue(a.connect(b));
            assertTrue(a.getConnections().contains(b));
        }

        @Test
        @DisplayName("connect returns false for duplicate connection")
        void connectDuplicateReturnsFalse() {
            var a = new Node.Builder().type(NodeType.INPUT).build();
            var b = new Node.Builder().type(NodeType.PROCESSOR).build();
            a.connect(b);
            assertFalse(a.connect(b));
        }

        @Test
        @DisplayName("connect self throws")
        void selfConnectionThrows() {
            var a = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertThrows(IllegalArgumentException.class, () -> a.connect(a));
        }

        @Test
        @DisplayName("disconnect removes target")
        void disconnectRemovesTarget() {
            var a = new Node.Builder().type(NodeType.INPUT).build();
            var b = new Node.Builder().type(NodeType.PROCESSOR).build();
            a.connect(b);
            assertTrue(a.disconnect(b));
            assertFalse(a.getConnections().contains(b));
        }

        @Test
        @DisplayName("disconnect non-existent returns false")
        void disconnectNonExistentReturnsFalse() {
            var a = new Node.Builder().type(NodeType.INPUT).build();
            var b = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertFalse(a.disconnect(b));
        }

        @Test
        @DisplayName("getConnections returns unmodifiable view")
        void connectionsViewIsUnmodifiable() {
            var a = new Node.Builder().type(NodeType.PROCESSOR).build();
            var b = new Node.Builder().type(NodeType.MEMORY).build();
            assertThrows(UnsupportedOperationException.class, () -> a.getConnections().add(b));
        }
    }

    // =========================================================================
    // Node state transition & history tests
    // =========================================================================

    @Nested
    @DisplayName("Node state transitions and history")
    class StateTransitionTests {

        @Test
        @DisplayName("transition updates current state")
        void transitionUpdatesState() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            var newState = new FrequencyState(2.0, 100.0, 0.0);
            node.transition(newState);
            assertEquals(newState, node.getFrequencyState());
        }

        @Test
        @DisplayName("transition records previous state in history")
        void transitionRecordsHistory() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            var initial = node.getFrequencyState();
            node.transition(new FrequencyState(1.0, 50.0, 0.0));
            assertEquals(1, node.getHistory().size());
            assertEquals(initial, node.getHistory().getFirst());
        }

        @Test
        @DisplayName("multiple transitions accumulate history in order")
        void multipleTransitionsAccumulateHistory() {
            var node = new Node.Builder().type(NodeType.MEMORY).build();
            var s1 = new FrequencyState(1.0, 10.0, 0.0);
            var s2 = new FrequencyState(2.0, 20.0, 0.0);
            var s3 = new FrequencyState(3.0, 30.0, 0.0);
            node.transition(s1);
            node.transition(s2);
            node.transition(s3);
            var history = node.getHistory();
            assertEquals(3, history.size());
            assertEquals(FrequencyState.ZERO, history.get(0));
            assertEquals(s1, history.get(1));
            assertEquals(s2, history.get(2));
        }

        @Test
        @DisplayName("transition with null throws")
        void transitionNullThrows() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertThrows(NullPointerException.class, () -> node.transition(null));
        }

        @Test
        @DisplayName("setEnergy updates energy")
        void setEnergyUpdates() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            node.setEnergy(9.81);
            assertEquals(9.81, node.getEnergy(), 1e-9);
        }

        @Test
        @DisplayName("setEnergy with negative value throws")
        void setEnergyNegativeThrows() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertThrows(IllegalArgumentException.class, () -> node.setEnergy(-0.001));
        }
    }

    // =========================================================================
    // Node identity tests
    // =========================================================================

    @Nested
    @DisplayName("Node identity (equals/hashCode)")
    class IdentityTests {

        @Test
        @DisplayName("Two nodes with the same UUID are equal")
        void sameUuidEquals() {
            var uuid = UUID.randomUUID();
            var a = new Node.Builder().id(uuid).type(NodeType.INPUT).build();
            var b = new Node.Builder().id(uuid).type(NodeType.PROCESSOR).build();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Two nodes with different UUIDs are not equal")
        void differentUuidNotEqual() {
            var a = new Node.Builder().type(NodeType.INPUT).build();
            var b = new Node.Builder().type(NodeType.INPUT).build();
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Node is equal to itself")
        void reflexiveEquality() {
            var a = new Node.Builder().type(NodeType.MEMORY).build();
            assertEquals(a, a);
        }
    }
}
