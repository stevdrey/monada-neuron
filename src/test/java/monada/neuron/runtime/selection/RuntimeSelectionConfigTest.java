package monada.neuron.runtime.selection;

import monada.neuron.aeon.BoundedParallelAeonCoordinator;
import monada.neuron.aeon.ContextualParallelism;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuntimeSelectionConfig Specification")
final class RuntimeSelectionConfigTest {

    @Test
    @DisplayName("referenceDefault provides safe reference baseline across all components")
    void referenceDefaultProvidesSafeReferenceBaseline() {
        var config = RuntimeSelectionConfig.referenceDefault();

        assertEquals(ExecutionPreference.REFERENCE, config.overallPreference());
        assertEquals(FallbackPolicy.FALLBACK_TO_REFERENCE, config.fallbackPolicy());

        assertEquals(ExecutionPreference.REFERENCE, config.resonance().preference());
        assertEquals(ResonanceSelectionConfig.DEFAULT_VECTOR_CROSSOVER_THRESHOLD, config.resonance().vectorCrossoverThreshold());
        assertTrue(config.resonance().explicitBackend().isEmpty());

        assertEquals(ExecutionPreference.REFERENCE, config.graph().preference());
        assertTrue(config.graph().explicitBackend().isEmpty());

        assertEquals(ExecutionPreference.REFERENCE, config.aeon().preference());
        assertEquals(BoundedParallelAeonCoordinator.DEFAULT_PARALLELISM_THRESHOLD, config.aeon().directParallelismThreshold());
        assertEquals(ContextualParallelism.SEQUENTIAL_ORACLE, config.aeon().contextualMode());
        assertTrue(config.aeon().explicitBackend().isEmpty());
    }

    @Test
    @DisplayName("autoDefault provides benchmark-driven configurations with empirical thresholds")
    void autoDefaultProvidesBenchmarkDrivenConfigurations() {
        var config = RuntimeSelectionConfig.autoDefault();

        assertEquals(ExecutionPreference.AUTO, config.overallPreference());
        assertEquals(FallbackPolicy.FALLBACK_TO_REFERENCE, config.fallbackPolicy());

        assertEquals(ExecutionPreference.AUTO, config.resonance().preference());
        assertEquals(4, config.resonance().vectorCrossoverThreshold());

        assertEquals(ExecutionPreference.AUTO, config.graph().preference());
        assertEquals(ExecutionPreference.AUTO, config.aeon().preference());
        assertEquals(ContextualParallelism.SEQUENTIAL_ORACLE, config.aeon().contextualMode());
    }

    @Test
    @DisplayName("builder allows custom tuning of preferences and thresholds")
    void builderAllowsCustomTuning() {
        var config = RuntimeSelectionConfig.builder()
                .overallPreference(ExecutionPreference.AUTO)
                .fallbackPolicy(FallbackPolicy.FAIL_FAST)
                .resonance(new ResonanceSelectionConfig(ExecutionPreference.EXPLICIT, 16, Optional.of(ResonanceBackendId.VECTOR_API)))
                .graph(new GraphSelectionConfig(ExecutionPreference.EXPLICIT, Optional.of(GraphBackendId.COMPACT_CSR)))
                .aeon(new AeonSelectionConfig(
                        ExecutionPreference.AUTO,
                        4,
                        8,
                        ContextualParallelism.EXPERIMENTAL_PARALLEL,
                        Optional.empty()))
                .build();

        assertEquals(ExecutionPreference.AUTO, config.overallPreference());
        assertEquals(FallbackPolicy.FAIL_FAST, config.fallbackPolicy());
        assertEquals(ExecutionPreference.EXPLICIT, config.resonance().preference());
        assertEquals(16, config.resonance().vectorCrossoverThreshold());
        assertEquals(Optional.of(ResonanceBackendId.VECTOR_API), config.resonance().explicitBackend());
        assertEquals(Optional.of(GraphBackendId.COMPACT_CSR), config.graph().explicitBackend());
        assertEquals(4, config.aeon().maxParallelism());
        assertEquals(8, config.aeon().directParallelismThreshold());
        assertEquals(ContextualParallelism.EXPERIMENTAL_PARALLEL, config.aeon().contextualMode());
    }

    @Test
    @DisplayName("validates threshold and parallelism arguments")
    void validatesThresholdAndParallelismArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                new ResonanceSelectionConfig(ExecutionPreference.AUTO, -1, Optional.empty()));

        assertThrows(IllegalArgumentException.class, () ->
                new AeonSelectionConfig(
                        ExecutionPreference.AUTO,
                        0,
                        10,
                        ContextualParallelism.SEQUENTIAL_ORACLE,
                        Optional.empty()));

        assertThrows(IllegalArgumentException.class, () ->
                new AeonSelectionConfig(
                        ExecutionPreference.AUTO,
                        4,
                        -1,
                        ContextualParallelism.SEQUENTIAL_ORACLE,
                        Optional.empty()));
    }

    @Test
    @DisplayName("rejects null configuration components")
    void rejectsNullConfigurationComponents() {
        assertThrows(NullPointerException.class, () ->
                new RuntimeSelectionConfig(
                        null,
                        FallbackPolicy.FALLBACK_TO_REFERENCE,
                        ResonanceSelectionConfig.reference(),
                        GraphSelectionConfig.reference(),
                        AeonSelectionConfig.reference()));

        assertThrows(NullPointerException.class, () ->
                new RuntimeSelectionConfig(
                        ExecutionPreference.REFERENCE,
                        null,
                        ResonanceSelectionConfig.reference(),
                        GraphSelectionConfig.reference(),
                        AeonSelectionConfig.reference()));
    }

    @Test
    @DisplayName("rejects explicit preference when explicitBackend is empty")
    void rejectsExplicitPreferenceWithoutBackend() {
        assertThrows(IllegalArgumentException.class, () ->
                new ResonanceSelectionConfig(ExecutionPreference.EXPLICIT, 4, Optional.empty()));

        assertThrows(IllegalArgumentException.class, () ->
                new GraphSelectionConfig(ExecutionPreference.EXPLICIT, Optional.empty()));

        assertThrows(IllegalArgumentException.class, () ->
                new AeonSelectionConfig(
                    ExecutionPreference.EXPLICIT,
                    4,
                    10,
                    ContextualParallelism.SEQUENTIAL_ORACLE,
                    Optional.empty()));
    }

    @Test
    @DisplayName("rejects EXPLICIT overallPreference at master configuration level")
    void rejectsExplicitOverallPreferenceAtMasterLevel() {
        assertThrows(IllegalArgumentException.class, () ->
                new RuntimeSelectionConfig(
                        ExecutionPreference.EXPLICIT,
                        FallbackPolicy.FALLBACK_TO_REFERENCE,
                        ResonanceSelectionConfig.reference(),
                        GraphSelectionConfig.reference(),
                        AeonSelectionConfig.reference()));

        assertThrows(IllegalArgumentException.class, () ->
                RuntimeSelectionConfig.builder().overallPreference(ExecutionPreference.EXPLICIT));
    }
}
