package monada.neuron.runtime.selection;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonCoordinationResult;
import monada.neuron.aeon.AeonInput;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.resonance.ScalarBatchResonanceEvaluator;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Backend Equivalence Specification")
final class BackendEquivalenceTest {

    private static final double TOLERANCE = 1.0e-12;

    @Test
    @DisplayName("selected resonance paths match scalar oracle across representative states and batch sizes")
    void selectedResonancePathsMatchScalarOracle() {
        var random = new Random(42);
        int[] batchSizes = {1, 3, 4, 7, 16, 64, 256};

        var referenceEvaluator = ScalarBatchResonanceEvaluator.INSTANCE;
        var autoEvaluator = BatchResonanceEvaluator.selecting(RuntimeSelectionConfig.autoDefault());
        var forcedReferenceEvaluator = BatchResonanceEvaluator.selecting(RuntimeSelectionConfig.forcedReference());

        for (int size : batchSizes) {
            double[] a1 = new double[size];
            double[] f1 = new double[size];
            double[] p1 = new double[size];
            double[] a2 = new double[size];
            double[] f2 = new double[size];
            double[] p2 = new double[size];

            for (int i = 0; i < size; i++) {
                a1[i] = random.nextDouble() * 2.0;
                f1[i] = random.nextDouble() * 50.0;
                p1[i] = (random.nextDouble() - 0.5) * 4.0 * Math.PI;

                a2[i] = random.nextDouble() * 2.0;
                f2[i] = random.nextDouble() * 50.0;
                p2[i] = (random.nextDouble() - 0.5) * 4.0 * Math.PI;
            }

            double[] expected = new double[size];
            double[] autoResults = new double[size];
            double[] forcedRefResults = new double[size];

            referenceEvaluator.scoreBatch(a1, f1, p1, a2, f2, p2, expected, 0, size);
            autoEvaluator.scoreBatch(a1, f1, p1, a2, f2, p2, autoResults, 0, size);
            forcedReferenceEvaluator.scoreBatch(a1, f1, p1, a2, f2, p2, forcedRefResults, 0, size);

            assertArrayEquals(expected, autoResults, TOLERANCE, "Mismatch at batch size " + size);
            assertArrayEquals(expected, forcedRefResults, TOLERANCE, "Mismatch on forced reference at size " + size);
        }
    }

    @Test
    @DisplayName("selected graph propagation engines produce identical results")
    void selectedGraphPropagationEnginesProduceIdenticalResults() {
        // Construct a deterministic test graph
        var nodes = new ArrayList<Node>();
        for (int i = 0; i < 20; i++) {
            nodes.add(createNode(10.0 + i));
        }

        // Add directed edges
        for (int i = 0; i < nodes.size() - 1; i++) {
            nodes.get(i).connect(nodes.get(i + 1));
            if (i + 2 < nodes.size()) {
                nodes.get(i).connect(nodes.get(i + 2));
            }
        }

        var snapshot = CompactGraphSnapshot.compile(nodes);
        var refConfig = RuntimeSelectionConfig.referenceDefault();
        var compactConfig = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();

        var refEngine = new SelectingSignalPropagationEngine(refConfig, snapshot);
        var compactEngine = new SelectingSignalPropagationEngine(compactConfig, snapshot);

        var startNode = nodes.getFirst();
        var signal = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(
                new Signal(SignalKind.INTERMEDIATE, input.frequencyState())));
        var propConfig = PropagationConfig.routeAll(50, 5);

        PropagationResult refResult = refEngine.propagate(startNode, signal, processor, propConfig);
        PropagationResult compactResult = compactEngine.propagate(startNode, signal, processor, propConfig);

        assertEquals(refResult, compactResult, "Compact CSR execution diverged from reference engine");
    }

    @Test
    @DisplayName("selected Aeon coordinators produce identical results")
    void selectedAeonCoordinatorsProduceIdenticalResults() {
        int processors = Runtime.getRuntime().availableProcessors();
        if (processors <= 1) {
            return;
        }

        var nodes = new ArrayList<Node>();
        for (int i = 0; i < 10; i++) {
            nodes.add(createNode(5.0 + i));
        }
        for (int i = 0; i < nodes.size() - 1; i++) {
            nodes.get(i).connect(nodes.get(i + 1));
        }

        var aeon = new Aeon(UUID.randomUUID(), AeonPurpose.REASONING);
        for (var n : nodes) {
            aeon.addMember(n);
        }

        var inputs = new ArrayList<AeonInput>();
        for (int i = 0; i < 8; i++) {
            inputs.add(new AeonInput(
                    nodes.getFirst().getId(),
                    new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0 + i, 0.0))));
        }

        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(
                new Signal(SignalKind.INTERMEDIATE, input.frequencyState())));
        var propConfig = PropagationConfig.routeAll(20, 3);

        var refConfig = RuntimeSelectionConfig.referenceDefault();
        var parConfig = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .aeon(new AeonSelectionConfig(
                        ExecutionPreference.AUTO,
                        processors,
                        2,
                        monada.neuron.aeon.ContextualParallelism.SEQUENTIAL_ORACLE,
                        java.util.Optional.empty()))
                .build();

        var refCoord = new SelectingAeonCoordinator(refConfig);
        var parCoord = new SelectingAeonCoordinator(parConfig);

        AeonCoordinationResult refResult = refCoord.coordinate(aeon, inputs, processor, propConfig);
        AeonCoordinationResult parResult = parCoord.coordinate(aeon, inputs, processor, propConfig);

        assertEquals(refResult, parResult, "Bounded parallel Aeon coordination diverged from reference sequential");
    }

    @Test
    @DisplayName("selected resonance paths match scalar oracle on FrequencyState[] object arrays")
    void selectedResonanceObjectArrayMatchesScalarOracle() {
        var random = new Random(99);
        int[] batchSizes = {1, 10, 31, 32, 64};

        var referenceEvaluator = ScalarBatchResonanceEvaluator.INSTANCE;
        var autoSelecting = new SelectingBatchResonanceEvaluator(RuntimeSelectionConfig.autoDefault());

        for (int size : batchSizes) {
            var first = new FrequencyState[size];
            var second = new FrequencyState[size];
            for (int i = 0; i < size; i++) {
                first[i] = new FrequencyState(random.nextDouble() * 2.0, random.nextDouble() * 50.0, 0.0);
                second[i] = new FrequencyState(random.nextDouble() * 2.0, random.nextDouble() * 50.0, 0.0);
            }

            double[] expected = new double[size];
            double[] actual = new double[size];

            referenceEvaluator.scoreBatch(first, second, expected, 0, size);
            autoSelecting.scoreBatch(first, second, actual, 0, size);

            assertArrayEquals(expected, actual, TOLERANCE, "Object array mismatch at size " + size);

            var diagnostic = autoSelecting.lastDiagnostic().orElseThrow();
            if (size < 32) {
                assertEquals(ResonanceBackendId.SCALAR, diagnostic.selectedBackendId());
                assertEquals(SelectionReason.AUTO_BELOW_THRESHOLD, diagnostic.reason());
            } else if (autoSelecting.selector().capabilities().isVectorApiAvailable()) {
                assertEquals(ResonanceBackendId.VECTOR_API, diagnostic.selectedBackendId());
                assertEquals(SelectionReason.AUTO_THRESHOLD_MET, diagnostic.reason());
            }
        }
    }

    @Test
    @DisplayName("SelectingSignalPropagationEngine cleanly falls back to reference for noncanonical start node")
    void selectingGraphEngineFallsBackForNoncanonicalStartNode() {
        var node1 = createNode(10.0);
        var node2 = createNode(20.0);
        node1.connect(node2);
        var snapshot = CompactGraphSnapshot.compile(List.of(node1, node2));

        var fallbackConfig = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FALLBACK_TO_REFERENCE)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();

        var engine = new SelectingSignalPropagationEngine(fallbackConfig, snapshot);
        var nonCanonicalNode = createNode(10.0);

        var signal = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(
                new Signal(SignalKind.INTERMEDIATE, input.frequencyState())));
        var propConfig = PropagationConfig.routeAll(20, 3);

        // Propagate with noncanonical node triggers fallback without exception
        var result = engine.propagate(nonCanonicalNode, signal, processor, propConfig);
        var diag = engine.lastDiagnostic().orElseThrow();
        assertEquals(GraphBackendId.DETERMINISTIC_OBJECT, diag.selectedBackendId());
        assertEquals(SelectionReason.FALLBACK_INELIGIBLE, diag.reason());
        assertTrue(diag.isFallback());
    }

    private static Node createNode(double frequency) {
        return new Node.Builder()
                .id(UUID.randomUUID())
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(1.0, frequency, 0.0))
                .energy(1.0)
                .build();
    }
}
