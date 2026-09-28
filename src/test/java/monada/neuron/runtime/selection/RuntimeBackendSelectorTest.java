package monada.neuron.runtime.selection;

import monada.neuron.aeon.ContextualParallelism;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        assertEquals(SelectionReason.AUTO_INELIGIBLE, nonIndependent.diagnostic().reason());
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
    @DisplayName("aeon parallel coordination rejects single worker configuration")
    void aeonParallelRejectsSingleWorker() {
        var configFailFast = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FAIL_FAST)
                .aeon(new AeonSelectionConfig(
                        ExecutionPreference.EXPLICIT,
                        1,
                        4,
                        ContextualParallelism.SEQUENTIAL_ORACLE,
                        Optional.of(AeonBackendId.BOUNDED_PARALLEL)))
                .build();
        var selectorFailFast = new RuntimeBackendSelector(configFailFast);

        assertThrows(BackendIneligibleException.class, () ->
                selectorFailFast.selectAeonCoordinator(10, false, true));

        var configFallback = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FALLBACK_TO_REFERENCE)
                .aeon(new AeonSelectionConfig(
                        ExecutionPreference.EXPLICIT,
                        1,
                        4,
                        ContextualParallelism.SEQUENTIAL_ORACLE,
                        Optional.of(AeonBackendId.BOUNDED_PARALLEL)))
                .build();
        var selectorFallback = new RuntimeBackendSelector(configFallback);
        var res = selectorFallback.selectAeonCoordinator(10, false, true);
        assertEquals(AeonBackendId.DETERMINISTIC_SEQUENTIAL, res.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FALLBACK_INELIGIBLE, res.diagnostic().reason());

        // In AUTO mode with maxParallelism = 1
        var configAutoSingle = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .aeon(new AeonSelectionConfig(
                        ExecutionPreference.AUTO,
                        1,
                        4,
                        ContextualParallelism.SEQUENTIAL_ORACLE,
                        Optional.empty()))
                .build();
        var selectorAutoSingle = new RuntimeBackendSelector(configAutoSingle);
        var autoRes = selectorAutoSingle.selectAeonCoordinator(10, false, true);
        assertEquals(AeonBackendId.DETERMINISTIC_SEQUENTIAL, autoRes.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.AUTO_INELIGIBLE, autoRes.diagnostic().reason());
    }

    @Test
    @DisplayName("resonance AUTO accounts for object array vs primitive layout threshold")
    void resonanceAutoLayoutAwareBehavior() {
        var selector = RuntimeBackendSelector.autoSelector();
        boolean vectorAvailable = selector.capabilities().isVectorApiAvailable();

        // Object array with length between 4 and 31: VectorBatchResonanceEvaluator delegates to scalar
        // Therefore selector must report SCALAR with AUTO_BELOW_THRESHOLD
        var objBelow = selector.selectResonance(10, true);
        assertEquals(ResonanceBackendId.SCALAR, objBelow.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.AUTO_BELOW_THRESHOLD, objBelow.diagnostic().reason());
        assertEquals("OBJECT_ARRAY", objBelow.diagnostic().metadata().get("inputLayout"));

        // Object array with length >= 32
        var objAbove = selector.selectResonance(32, true);
        if (vectorAvailable) {
            assertEquals(ResonanceBackendId.VECTOR_API, objAbove.diagnostic().selectedBackendId());
            assertEquals(SelectionReason.AUTO_THRESHOLD_MET, objAbove.diagnostic().reason());
        } else {
            assertEquals(ResonanceBackendId.SCALAR, objAbove.diagnostic().selectedBackendId());
            assertEquals(SelectionReason.AUTO_UNAVAILABLE, objAbove.diagnostic().reason());
        }

        // Primitive SoA with length 10: meets default threshold 4 and lane width
        var primAbove = selector.selectResonance(10, false);
        if (vectorAvailable) {
            assertEquals(ResonanceBackendId.VECTOR_API, primAbove.diagnostic().selectedBackendId());
            assertEquals(SelectionReason.AUTO_THRESHOLD_MET, primAbove.diagnostic().reason());
        } else {
            assertEquals(ResonanceBackendId.SCALAR, primAbove.diagnostic().selectedBackendId());
        }
    }

    @Test
    @DisplayName("graph explicit COMPACT_CSR validates canonical start node")
    void graphExplicitCompactCsrValidatesCanonicalStartNode() {
        var node1 = createNode();
        var node2 = createNode();
        var snapshot = CompactGraphSnapshot.compile(List.of(node1, node2));

        var nonCanonicalNode = createNode();

        // FAIL_FAST
        var configFailFast = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FAIL_FAST)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();
        var selectorFailFast = new RuntimeBackendSelector(configFailFast);

        assertThrows(BackendIneligibleException.class, () ->
                selectorFailFast.selectGraphPropagation(snapshot, nonCanonicalNode, false));

        // FALLBACK_TO_REFERENCE
        var configFallback = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FALLBACK_TO_REFERENCE)
                .graph(GraphSelectionConfig.explicit(GraphBackendId.COMPACT_CSR))
                .build();
        var selectorFallback = new RuntimeBackendSelector(configFallback);

        var result = selectorFallback.selectGraphPropagation(snapshot, nonCanonicalNode, false);
        assertEquals(GraphBackendId.DETERMINISTIC_OBJECT, result.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.FALLBACK_INELIGIBLE, result.diagnostic().reason());
        assertTrue(result.diagnostic().isFallback());
        assertEquals(nonCanonicalNode.getId().toString(), result.diagnostic().metadata().get("noncanonicalStartNode"));

        // Canonical start node succeeds
        var canonicalResult = selectorFallback.selectGraphPropagation(snapshot, node1, false);
        assertEquals(GraphBackendId.COMPACT_CSR, canonicalResult.diagnostic().selectedBackendId());
        assertEquals(SelectionReason.EXPLICIT_SELECTION, canonicalResult.diagnostic().reason());
    }

    @Test
    @DisplayName("SelectionDiagnostic defensively snapshots mutable metadata")
    void selectionDiagnosticDefensivelySnapshotsMetadata() {
        var mutableMap = new HashMap<String, String>();
        mutableMap.put("key", "initialValue");

        var diagnostic = new SelectionDiagnostic<>(
                ResonanceBackendId.SCALAR,
                SelectionReason.REFERENCE_DEFAULT,
                10,
                false,
                Optional.empty(),
                mutableMap);

        assertEquals("initialValue", diagnostic.metadata().get("key"));

        // Mutating the input map does not mutate the diagnostic
        mutableMap.put("key", "modifiedValue");
        mutableMap.put("newKey", "newValue");

        assertEquals("initialValue", diagnostic.metadata().get("key"));
        assertFalse(diagnostic.metadata().containsKey("newKey"));
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
