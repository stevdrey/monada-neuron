package monada.neuron.runtime.selection;

import monada.neuron.aeon.ContextualParallelism;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuntimeBackendSelector Specification")
final class RuntimeBackendSelectorTest {

    @Test
    @DisplayName("referenceSelector forces reference oracle across all components")
    void referenceSelectorForcesReferenceOracle() {
        var selector = RuntimeBackendSelector.referenceSelector();

        var resonance = selector.selectResonance(10_000);
        assertEquals(ResonanceBackendId.SCALAR, resonance.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FORCED_REFERENCE, resonance.diagnostic().reason());
        assertFalse(resonance.diagnostic().isFallback());

        var graph = selector.selectGraphPropagation(null, false);
        assertEquals(GraphBackendId.DETERMINISTIC_OBJECT, graph.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FORCED_REFERENCE, graph.diagnostic().reason());

        var aeon = selector.selectAeonCoordinator(128, false, true);
        assertEquals(AeonBackendId.DETERMINISTIC_SEQUENTIAL, aeon.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FORCED_REFERENCE, aeon.diagnostic().reason());
    }

    @Test
    @DisplayName("resonance AUTO selects SCALAR below threshold and VECTOR_API at or above threshold")
    void resonanceAutoThresholdBehavior() {
        var selector = RuntimeBackendSelector.autoSelector();
        boolean vectorAvailable = selector.capabilities().isVectorApiAvailable();

        // Workload below default threshold 4
        var below = selector.selectResonance(3);
        assertEquals(ResonanceBackendId.SCALAR, below.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.AUTO_BELOW_THRESHOLD, below.diagnostic().reason());
        assertEquals(3, below.diagnostic().workloadScale());

        // Workload at threshold 4
        var at = selector.selectResonance(4);
        if (vectorAvailable) {
            assertEquals(ResonanceBackendId.VECTOR_API, at.diagnostic().selectedBackendId());
            assertEquals(SelectionReason.AUTO_THRESHOLD_MET, at.diagnostic().reason());
        } else {
            assertEquals(ResonanceBackendId.SCALAR, at.diagnostic().selectedBackendId());
            assertEquals(SelectionReason.AUTO_UNAVAILABLE, at.diagnostic().reason());
            assertTrue(at.diagnostic().isFallback());
        }

        // Workload well above threshold
        var above = selector.selectResonance(100);
        if (vectorAvailable) {
            assertEquals(ResonanceBackendId.VECTOR_API, above.diagnostic().selectedBackendId());
            assertEquals(SelectionReason.AUTO_THRESHOLD_MET, above.diagnostic().reason());
        } else {
            assertEquals(ResonanceBackendId.SCALAR, above.diagnostic().selectedBackendId());
            assertTrue(above.diagnostic().isFallback());
        }
    }

    @Test
    @DisplayName("resonance explicit selection respects fallback policy")
    void resonanceExplicitSelectionRespectsFallbackPolicy() {
        // Explicit SCALAR
        var configScalar = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .resonance(ResonanceSelectionConfig.explicit(ResonanceBackendId.SCALAR))
                .build();
        var selectorScalar = new RuntimeBackendSelector(configScalar);
        var resScalar = selectorScalar.selectResonance(100);
        assertEquals(ResonanceBackendId.SCALAR, resScalar.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.EXPLICIT_SELECTION, resScalar.diagnostic().reason());
    }

    @Test
    @DisplayName("graph AUTO selects deterministic object reference engine per ADR 0014")
    void graphAutoSelectsReferenceEngine() {
        var selector = RuntimeBackendSelector.autoSelector();
        var node1 = createNode();
        var snapshot = CompactGraphSnapshot.compile(List.of(node1));

        var selection = selector.selectGraphPropagation(snapshot, false);
        assertEquals(GraphBackendId.DETERMINISTIC_OBJECT, selection.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.REFERENCE_DEFAULT, selection.diagnostic().reason());
    }

    @Test
    @DisplayName("graph explicit COMPACT_CSR succeeds on current direct snapshot")
    void graphExplicitCompactCsrSucceedsOnCurrentDirectSnapshot() {
        var config = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();
        var selector = new RuntimeBackendSelector(config);

        var node1 = createNode();
        var snapshot = CompactGraphSnapshot.compile(List.of(node1));

        var directSelection = selector.selectGraphPropagation(snapshot, false);
        assertEquals(GraphBackendId.COMPACT_CSR, directSelection.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.EXPLICIT_SELECTION, directSelection.diagnostic().reason());
        assertFalse(directSelection.diagnostic().isFallback());
    }

    @Test
    @DisplayName("graph explicit COMPACT_CSR falls back or fails fast when snapshot is null or stale")
    void graphExplicitCompactCsrHandlesIneligibleSnapshot() {
        // Fallback policy: FALLBACK_TO_REFERENCE
        var configFallback = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FALLBACK_TO_REFERENCE)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();
        var selectorFallback = new RuntimeBackendSelector(configFallback);

        var nullResult = selectorFallback.selectGraphPropagation(null, false);
        assertEquals(GraphBackendId.DETERMINISTIC_OBJECT, nullResult.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FALLBACK_INELIGIBLE, nullResult.diagnostic().reason());
        assertTrue(nullResult.diagnostic().isFallback());

        // Stale snapshot
        var node1 = createNode();
        var node2 = createNode();
        var snapshot = CompactGraphSnapshot.compile(List.of(node1, node2));
        node1.connect(node2); // mutates topology, invalidates snapshot

        var staleResult = selectorFallback.selectGraphPropagation(snapshot, false);
        assertEquals(GraphBackendId.DETERMINISTIC_OBJECT, staleResult.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FALLBACK_INELIGIBLE, staleResult.diagnostic().reason());
        assertTrue(staleResult.diagnostic().isFallback());

        // Fallback policy: FAIL_FAST
        var configFailFast = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FAIL_FAST)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();
        var selectorFailFast = new RuntimeBackendSelector(configFailFast);

        assertThrows(BackendIneligibleException.class, () ->
                selectorFailFast.selectGraphPropagation(null, false));

        assertThrows(BackendIneligibleException.class, () ->
                selectorFailFast.selectGraphPropagation(snapshot, false));
    }

    @Test
    @DisplayName("graph explicit COMPACT_CSR is ineligible in contextual mode")
    void graphExplicitCompactCsrIneligibleInContextualMode() {
        var configFallback = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FALLBACK_TO_REFERENCE)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();
        var selectorFallback = new RuntimeBackendSelector(configFallback);

        var node1 = createNode();
        var snapshot = CompactGraphSnapshot.compile(List.of(node1));

        var contextualResult = selectorFallback.selectGraphPropagation(snapshot, true);
        assertEquals(GraphBackendId.DETERMINISTIC_OBJECT, contextualResult.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FALLBACK_INELIGIBLE, contextualResult.diagnostic().reason());
        assertTrue(contextualResult.diagnostic().isFallback());

        var configFailFast = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FAIL_FAST)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();
        var selectorFailFast = new RuntimeBackendSelector(configFailFast);

        assertThrows(BackendIneligibleException.class, () ->
                selectorFailFast.selectGraphPropagation(snapshot, true));
    }

    @Test
    @DisplayName("aeon contextual coordination strictly preserves sequential oracle in AUTO mode")
    void aeonContextualAutoPreservesSequentialOracle() {
        var selector = RuntimeBackendSelector.autoSelector();

        var contextual = selector.selectAeonCoordinator(128, true, true);
        assertEquals(AeonBackendId.DETERMINISTIC_SEQUENTIAL, contextual.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.AUTO_INELIGIBLE, contextual.diagnostic().reason());
        assertTrue(contextual.diagnostic().fallbackReason().isPresent());
    }

    @Test
    @DisplayName("aeon direct coordination selects BOUNDED_PARALLEL when threshold configured and met")
    void aeonDirectParallelThresholdSelection() {
        int processors = Runtime.getRuntime().availableProcessors();
        if (processors <= 1) {
            return; // Single-processor environment cannot test multi-worker parallelism
        }

        var config = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .aeon(new AeonSelectionConfig(
                        ExecutionPreference.AUTO,
                        processors,
                        4,
                        ContextualParallelism.SEQUENTIAL_ORACLE,
                        Optional.empty()))
                .build();
        var selector = new RuntimeBackendSelector(config);

        // Below threshold
        var below = selector.selectAeonCoordinator(3, false, true);
        assertEquals(AeonBackendId.DETERMINISTIC_SEQUENTIAL, below.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.AUTO_BELOW_THRESHOLD, below.diagnostic().reason());

        // At threshold
        var at = selector.selectAeonCoordinator(4, false, true);
        assertEquals(AeonBackendId.BOUNDED_PARALLEL, at.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.AUTO_THRESHOLD_MET, at.diagnostic().reason());

        // Above threshold but not independent
        var nonIndependent = selector.selectAeonCoordinator(10, false, false);
        assertEquals(AeonBackendId.DETERMINISTIC_SEQUENTIAL, nonIndependent.diagnostic().selectedBackendId());
    }

    @Test
    @DisplayName("aeon explicit BOUNDED_PARALLEL handles ineligibility and fail-fast")
    void aeonExplicitParallelHandlesIneligibility() {
        var configFailFast = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FAIL_FAST)
                .aeon(AeonSelectionConfig.explicit(AeonBackendId.BOUNDED_PARALLEL))
                .build();
        var selectorFailFast = new RuntimeBackendSelector(configFailFast);

        // Non-independent fails fast
        assertThrows(BackendIneligibleException.class, () ->
                selectorFailFast.selectAeonCoordinator(10, false, false));

        // Contextual without experimental mode fails fast
        assertThrows(BackendIneligibleException.class, () ->
                selectorFailFast.selectAeonCoordinator(10, true, true));

        // Fallback policy: FALLBACK_TO_REFERENCE
        var configFallback = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FALLBACK_TO_REFERENCE)
                .aeon(AeonSelectionConfig.explicit(AeonBackendId.BOUNDED_PARALLEL))
                .build();
        var selectorFallback = new RuntimeBackendSelector(configFallback);

        var fallbackResult = selectorFallback.selectAeonCoordinator(10, false, false);
        assertEquals(AeonBackendId.DETERMINISTIC_SEQUENTIAL, fallbackResult.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FALLBACK_INELIGIBLE, fallbackResult.diagnostic().reason());
        assertTrue(fallbackResult.diagnostic().isFallback());
    }

    @Test
    @DisplayName("selection decisions are deterministic and inspectable")
    void selectionDecisionsAreDeterministicAndInspectable() {
        var selector = RuntimeBackendSelector.autoSelector();

        for (int i = 0; i < 100; i++) {
            var resonance = selector.selectResonance(10);
            assertNotNull(resonance.backend());
            assertNotNull(resonance.diagnostic().metadata());
            assertEquals(10, resonance.diagnostic().workloadScale());
        }
    }

    private static Node createNode() {
        return new Node.Builder()
                .id(UUID.randomUUID())
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(1.0, 10.0, 0.0))
                .energy(1.0)
                .build();
    }
}
