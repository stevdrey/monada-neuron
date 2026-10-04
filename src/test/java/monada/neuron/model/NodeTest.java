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

        @Test
        @DisplayName("NaN amplitude throws")
        void nanAmplitudeThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(Double.NaN, 1.0, 0.0));
        }

        @Test
        @DisplayName("Infinity amplitude throws")
        void infinityAmplitudeThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(Double.POSITIVE_INFINITY, 1.0, 0.0));
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(Double.NEGATIVE_INFINITY, 1.0, 0.0));
        }

        @Test
        @DisplayName("NaN frequency throws")
        void nanFrequencyThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(1.0, Double.NaN, 0.0));
        }

        @Test
        @DisplayName("Infinity frequency throws")
        void infinityFrequencyThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(1.0, Double.POSITIVE_INFINITY, 0.0));
            assertThrows(IllegalArgumentException.class, () -> new FrequencyState(1.0, Double.NEGATIVE_INFINITY, 0.0));
        }

        @Test
        @DisplayName("Non-finite phase throws")
        void nonFinitePhaseThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FrequencyState(1.0, 1.0, Double.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> new FrequencyState(1.0, 1.0, Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class,
                    () -> new FrequencyState(1.0, 1.0, Double.NEGATIVE_INFINITY));
        }

        @Test
        @DisplayName("Non-finite amplitude scale throws")
        void nonFiniteAmplitudeScaleThrows() {
            var state = new FrequencyState(1.0, 1.0, 0.0);
            assertThrows(IllegalArgumentException.class,
                    () -> state.withScaledAmplitude(Double.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> state.withScaledAmplitude(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class,
                    () -> state.withScaledAmplitude(Double.NEGATIVE_INFINITY));
        }

        @Test
        @DisplayName("Non-finite phase shift throws")
        void nonFinitePhaseShiftThrows() {
            var state = new FrequencyState(1.0, 1.0, 0.0);
            assertThrows(IllegalArgumentException.class,
                    () -> state.withPhaseShift(Double.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> state.withPhaseShift(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class,
                    () -> state.withPhaseShift(Double.NEGATIVE_INFINITY));
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

        @Test
        @DisplayName("Builder rejects NaN energy")
        void nanEnergyThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Node.Builder().type(NodeType.PROCESSOR).energy(Double.NaN).build());
        }

        @Test
        @DisplayName("Builder rejects Infinity energy")
        void infinityEnergyThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Node.Builder().type(NodeType.PROCESSOR).energy(Double.POSITIVE_INFINITY).build());
            assertThrows(IllegalArgumentException.class,
                    () -> new Node.Builder().type(NodeType.PROCESSOR).energy(Double.NEGATIVE_INFINITY).build());
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
        @DisplayName("connect to node with same UUID throws")
        void sameUuidConnectionThrows() {
            var uuid = UUID.randomUUID();
            var a = new Node.Builder().id(uuid).type(NodeType.PROCESSOR).build();
            var b = new Node.Builder().id(uuid).type(NodeType.INPUT).build();
            assertThrows(IllegalArgumentException.class, () -> a.connect(b));
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

        @Test
        @DisplayName("Builder rejects self-connection at build time")
        void builderRejectsSelfConnection() {
            var uuid = UUID.randomUUID();
            var selfNode = new Node.Builder().id(uuid).type(NodeType.PROCESSOR).build();
            assertThrows(IllegalArgumentException.class,
                    () -> new Node.Builder().id(uuid).type(NodeType.PROCESSOR).connection(selfNode).build());
        }

        @Test
        @DisplayName("Builder rejects connection with same UUID at build time")
        void builderRejectsSameUuidConnection() {
            var uuid = UUID.randomUUID();
            var nodeA = new Node.Builder().id(uuid).type(NodeType.PROCESSOR).build();
            var nodeB = new Node.Builder().id(uuid).type(NodeType.INPUT).build();
            assertThrows(IllegalArgumentException.class,
                    () -> new Node.Builder().id(uuid).type(NodeType.PROCESSOR).connection(nodeB).build());
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

        @Test
        @DisplayName("setEnergy with NaN throws")
        void setEnergyNanThrows() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertThrows(IllegalArgumentException.class, () -> node.setEnergy(Double.NaN));
        }

        @Test
        @DisplayName("setEnergy with Infinity throws")
        void setEnergyInfinityThrows() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertThrows(IllegalArgumentException.class, () -> node.setEnergy(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class, () -> node.setEnergy(Double.NEGATIVE_INFINITY));
        }
    }

    // =========================================================================
    // Bounded history tests
    // =========================================================================

    @Nested
    @DisplayName("Bounded history")
    class HistoryLimitTests {

        private FrequencyState state(int index) {
            return new FrequencyState(index, 10.0, 0.0);
        }

        @Test
        @DisplayName("default limit is the documented constant")
        void defaultLimit() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            assertEquals(256, Node.DEFAULT_HISTORY_LIMIT);
            assertEquals(Node.DEFAULT_HISTORY_LIMIT, node.getHistoryLimit());
        }

        @Test
        @DisplayName("history keeps only the most recent states, oldest first, after wrapping")
        void retainsMostRecentStatesInOrder() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).historyLimit(3).build();
            for (int i = 1; i <= 8; i++) {
                node.transition(state(i));
            }
            // previous states recorded: ZERO, s1, ..., s7 -> last three are s5, s6, s7
            assertAll(
                    () -> assertEquals(3, node.getHistory().size()),
                    () -> assertEquals(state(5), node.getHistory().get(0)),
                    () -> assertEquals(state(6), node.getHistory().get(1)),
                    () -> assertEquals(state(7), node.getHistory().get(2)),
                    () -> assertEquals(state(8), node.getFrequencyState()),
                    () -> assertEquals(java.util.List.of(state(5), state(6), state(7)),
                            java.util.List.copyOf(node.getHistory())));
        }

        @Test
        @DisplayName("history below the limit is unchanged and ordered")
        void belowLimitKeepsEverything() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).historyLimit(5).build();
            node.transition(state(1));
            node.transition(state(2));
            assertEquals(java.util.List.of(FrequencyState.ZERO, state(1)),
                    java.util.List.copyOf(node.getHistory()));
        }

        @Test
        @DisplayName("limit zero disables history without disabling transitions")
        void zeroLimitDisablesHistory() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).historyLimit(0).build();
            node.transition(state(1));
            assertAll(
                    () -> assertTrue(node.getHistory().isEmpty()),
                    () -> assertEquals(state(1), node.getFrequencyState()));
        }

        @Test
        @DisplayName("history size never exceeds the limit over many transitions")
        void sizeStaysBounded() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).build();
            for (int i = 0; i < Node.DEFAULT_HISTORY_LIMIT * 4; i++) {
                node.transition(state(i));
            }
            assertEquals(Node.DEFAULT_HISTORY_LIMIT, node.getHistory().size());
        }

        @Test
        @DisplayName("history view is read-only")
        void historyIsReadOnly() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).historyLimit(2).build();
            node.transition(state(1));
            assertThrows(UnsupportedOperationException.class,
                    () -> node.getHistory().add(FrequencyState.ZERO));
            assertThrows(IndexOutOfBoundsException.class, () -> node.getHistory().get(1));
        }

        @Test
        @DisplayName("a returned history is a snapshot that later transitions do not change")
        void returnedHistoryIsASnapshot() {
            var node = new Node.Builder().type(NodeType.PROCESSOR).historyLimit(2).build();
            node.transition(state(1));
            var held = node.getHistory();
            var heldCopy = java.util.List.copyOf(held);

            node.transition(state(2));
            node.transition(state(3)); // wraps: the oldest retained state is overwritten

            assertAll(
                    () -> assertEquals(heldCopy, held),
                    () -> assertEquals(1, held.size()),
                    () -> assertEquals(FrequencyState.ZERO, held.getFirst()),
                    () -> assertEquals(java.util.List.of(state(1), state(2)),
                            java.util.List.copyOf(node.getHistory())));
        }

        @Test
        @DisplayName("negative limit throws")
        void negativeLimitThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Node.Builder().historyLimit(-1));
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
