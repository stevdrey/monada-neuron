package monada.neuron.runtime.selection;

import monada.neuron.aeon.AeonParallelEligibility;
import monada.neuron.aeon.BoundedParallelAeonCoordinator;
import monada.neuron.aeon.CognitiveAeonCoordinator;
import monada.neuron.aeon.ContextualParallelism;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.model.Node;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.resonance.ScalarBatchResonanceEvaluator;
import monada.neuron.runtime.graph.CognitiveSignalPropagationEngine;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.graph.CompactSignalPropagationEngine;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.SignalPropagationEngine;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

/**
 * Control-plane selector that deterministically chooses among reference and optimized backend
 * implementations using configuration, cached capability checks, and benchmark-derived workload thresholds.
 *
 * <p>All decisions are $O(1)$, deterministic, thread-safe, and inspectable.
 */
public final class RuntimeBackendSelector {

    private static final Map<String, String> SCALAR_EVALUATOR_METADATA =
            Map.of("evaluator", "ScalarBatchResonanceEvaluator");
    private static final Map<String, String> ATTEMPTED_VECTOR_METADATA =
            Map.of("attemptedBackend", ResonanceBackendId.VECTOR_API.name());
    private static final Map<String, String> GRAPH_OBJECT_ENGINE_METADATA =
            Map.of("engine", "DeterministicSignalPropagationEngine");
    private static final Map<String, String> ATTEMPTED_CSR_METADATA =
            Map.of("attemptedBackend", GraphBackendId.COMPACT_CSR.name());
    private static final Map<String, String> AEON_SEQUENTIAL_METADATA =
            Map.of("coordinator", "DeterministicAeonCoordinator");
    private static final Map<String, String> ATTEMPTED_PARALLEL_METADATA =
            Map.of("attemptedBackend", AeonBackendId.BOUNDED_PARALLEL.name());

    private static final RuntimeBackendSelector REFERENCE_SELECTOR =
            new RuntimeBackendSelector(RuntimeSelectionConfig.referenceDefault());
    private static final RuntimeBackendSelector AUTO_SELECTOR =
            new RuntimeBackendSelector(RuntimeSelectionConfig.autoDefault());

    private final RuntimeSelectionConfig config;
    private final BackendCapabilities capabilities;

    // Cached shared instances for reference paths to minimize allocation
    private final DeterministicSignalPropagationEngine referencePropagationEngine;
    private final DeterministicAeonCoordinator referenceAeonCoordinator;
    private BatchResonanceEvaluator vectorEvaluator;

    public RuntimeBackendSelector(RuntimeSelectionConfig config) {
        this(config, BackendCapabilities.current());
    }

    public RuntimeBackendSelector(RuntimeSelectionConfig config, BackendCapabilities capabilities) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
        this.referencePropagationEngine = new DeterministicSignalPropagationEngine();
        this.referenceAeonCoordinator = new DeterministicAeonCoordinator(this.referencePropagationEngine);
        this.vectorEvaluator = loadVectorEvaluatorIfAvailable();
    }

    /** Returns the singleton reference-default selector. */
    public static RuntimeBackendSelector referenceSelector() {
        return REFERENCE_SELECTOR;
    }

    /** Returns the singleton benchmark-driven automatic selector. */
    public static RuntimeBackendSelector autoSelector() {
        return AUTO_SELECTOR;
    }

    /** Returns the active selection configuration. */
    public RuntimeSelectionConfig config() {
        return config;
    }

    /** Returns the detected platform capabilities. */
    public BackendCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Selects a batch resonance evaluator for primitive/SoA workload of the given batch size.
     *
     * @param batchSize number of frequency state pairs in the batch
     * @return backend selection containing the evaluator and inspectable diagnostic
     */
    public BackendSelection<BatchResonanceEvaluator, ResonanceBackendId> selectResonance(int batchSize) {
        return selectResonance(batchSize, false);
    }

    /**
     * Selects a batch resonance evaluator for the given workload batch size and input layout.
     *
     * @param batchSize number of frequency state pairs in the batch
     * @param isObjectArray {@code true} if inputs are {@link monada.neuron.model.FrequencyState FrequencyState[]} object arrays
     * @return backend selection containing the evaluator and inspectable diagnostic
     */
    public BackendSelection<BatchResonanceEvaluator, ResonanceBackendId> selectResonance(
            int batchSize,
            boolean isObjectArray) {
        ExecutionPreference effectivePref = effectivePreference(config.resonance().preference());

        if (effectivePref == ExecutionPreference.REFERENCE) {
            SelectionReason reason = config.overallPreference() == ExecutionPreference.REFERENCE
                    ? SelectionReason.FORCED_REFERENCE
                    : SelectionReason.REFERENCE_DEFAULT;
            return new BackendSelection<>(
                    ScalarBatchResonanceEvaluator.INSTANCE,
                    new SelectionDiagnostic<>(
                            ResonanceBackendId.SCALAR,
                            reason,
                            batchSize,
                            false,
                            Optional.empty(),
                            SCALAR_EVALUATOR_METADATA));
        }

        if (effectivePref == ExecutionPreference.EXPLICIT) {
            ResonanceBackendId target = config.resonance().explicitBackend()
                    .orElseThrow(() -> new IllegalStateException("explicitBackend must be present when preference is EXPLICIT"));
            if (target == ResonanceBackendId.SCALAR) {
                return new BackendSelection<>(
                        ScalarBatchResonanceEvaluator.INSTANCE,
                        new SelectionDiagnostic<>(
                                ResonanceBackendId.SCALAR,
                                SelectionReason.EXPLICIT_SELECTION,
                                batchSize,
                                false,
                                Optional.empty(),
                                SCALAR_EVALUATOR_METADATA));
            }

            // Target is VECTOR_API
            if (capabilities.isVectorApiAvailable() && vectorEvaluator != null) {
                return new BackendSelection<>(
                        vectorEvaluator,
                        new SelectionDiagnostic<>(
                                ResonanceBackendId.VECTOR_API,
                                SelectionReason.EXPLICIT_SELECTION,
                                batchSize,
                                false,
                                Optional.empty(),
                                capabilities.diagnosticSummary()));
            }

            // Unavailable or initialization failed
            var initFailure = capabilities.vectorInitializationFailure();
            String msg = initFailure
                    .map(f -> "Java 26 Vector API initialization failed: " + f.getMessage())
                    .orElse("Java 26 Vector API incubator module is unavailable on this runtime");
            if (config.fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                if (initFailure.isPresent()) {
                    throw new BackendUnavailableException(ResonanceBackendId.VECTOR_API, msg, initFailure.get());
                } else {
                    throw new BackendUnavailableException(ResonanceBackendId.VECTOR_API, msg);
                }
            }

            Map<String, String> fallbackMeta = initFailure.isPresent()
                    ? Map.of("attemptedBackend", ResonanceBackendId.VECTOR_API.name(), "failure", initFailure.get().toString())
                    : ATTEMPTED_VECTOR_METADATA;

            return new BackendSelection<>(
                    ScalarBatchResonanceEvaluator.INSTANCE,
                    new SelectionDiagnostic<>(
                            ResonanceBackendId.SCALAR,
                            SelectionReason.FALLBACK_UNAVAILABLE,
                            batchSize,
                            true,
                            Optional.of(msg),
                            fallbackMeta));
        }

        // AUTO preference: benchmark-derived crossover threshold (default 4 pairs from ADR 0013)
        // For object arrays (FrequencyState[]), VectorBatchResonanceEvaluator delegates length < 32 to scalar oracle
        // For primitive SoA, threshold is bounded by vector lane width
        int effectiveThreshold = isObjectArray
                ? Math.max(config.resonance().vectorCrossoverThreshold(), 32)
                : Math.max(config.resonance().vectorCrossoverThreshold(), capabilities.vectorLaneWidth());

        if (batchSize < effectiveThreshold) {
            return new BackendSelection<>(
                    ScalarBatchResonanceEvaluator.INSTANCE,
                    new SelectionDiagnostic<>(
                            ResonanceBackendId.SCALAR,
                            SelectionReason.AUTO_BELOW_THRESHOLD,
                            batchSize,
                            false,
                            Optional.empty(),
                            Map.of(
                                    "threshold", String.valueOf(effectiveThreshold),
                                    "batchSize", String.valueOf(batchSize),
                                    "inputLayout", isObjectArray ? "OBJECT_ARRAY" : "PRIMITIVE_SOA")));
        }

        if (capabilities.isVectorApiAvailable() && vectorEvaluator != null) {
            return new BackendSelection<>(
                    vectorEvaluator,
                    new SelectionDiagnostic<>(
                            ResonanceBackendId.VECTOR_API,
                            SelectionReason.AUTO_THRESHOLD_MET,
                            batchSize,
                            false,
                            Optional.empty(),
                            capabilities.diagnosticSummary()));
        }

        return new BackendSelection<>(
                ScalarBatchResonanceEvaluator.INSTANCE,
                new SelectionDiagnostic<>(
                        ResonanceBackendId.SCALAR,
                        SelectionReason.AUTO_UNAVAILABLE,
                        batchSize,
                        true,
                        Optional.of("Vector API incubator module unavailable; routed to scalar reference oracle"),
                        Map.of("threshold", String.valueOf(effectiveThreshold))));
    }

    /**
     * Selects a signal propagation engine for the given snapshot and execution mode.
     *
     * @param snapshot compiled compact graph snapshot, or {@code null} if using object model directly
     * @param isContextual {@code true} if running within a {@code CognitiveContext}
     * @return backend selection containing the engine and inspectable diagnostic
     */
    public BackendSelection<CognitiveSignalPropagationEngine, GraphBackendId> selectGraphPropagation(
            CompactGraphSnapshot snapshot,
            boolean isContextual) {
        return selectGraphPropagation(snapshot, null, isContextual);
    }

    /**
     * Selects a signal propagation engine for the given snapshot, start node, and execution mode.
     *
     * @param snapshot compiled compact graph snapshot, or {@code null} if using object model directly
     * @param startNode start node for signal propagation, or {@code null} if not yet specified
     * @param isContextual {@code true} if running within a {@code CognitiveContext}
     * @return backend selection containing the engine and inspectable diagnostic
     */
    public BackendSelection<CognitiveSignalPropagationEngine, GraphBackendId> selectGraphPropagation(
            CompactGraphSnapshot snapshot,
            Node startNode,
            boolean isContextual) {
        long scale = snapshot != null ? snapshot.nodeCount() : 0;
        ExecutionPreference effectivePref = effectivePreference(config.graph().preference());

        if (effectivePref == ExecutionPreference.REFERENCE) {
            SelectionReason reason = config.overallPreference() == ExecutionPreference.REFERENCE
                    ? SelectionReason.FORCED_REFERENCE
                    : SelectionReason.REFERENCE_DEFAULT;
            return new BackendSelection<>(
                    referencePropagationEngine,
                    new SelectionDiagnostic<>(
                            GraphBackendId.DETERMINISTIC_OBJECT,
                            reason,
                            scale,
                            false,
                            Optional.empty(),
                            GRAPH_OBJECT_ENGINE_METADATA));
        }

        if (effectivePref == ExecutionPreference.EXPLICIT) {
            GraphBackendId target = config.graph().explicitBackend()
                    .orElseThrow(() -> new IllegalStateException("explicitBackend must be present when preference is EXPLICIT"));
            if (target == GraphBackendId.DETERMINISTIC_OBJECT) {
                return new BackendSelection<>(
                        referencePropagationEngine,
                        new SelectionDiagnostic<>(
                                GraphBackendId.DETERMINISTIC_OBJECT,
                                SelectionReason.EXPLICIT_SELECTION,
                                scale,
                                false,
                                Optional.empty(),
                                GRAPH_OBJECT_ENGINE_METADATA));
            }

            // Target is COMPACT_CSR
            if (isContextual) {
                String msg = "Compact CSR graph engine delegates contextual propagation to reference engine per ADR 0014";
                if (config.fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(GraphBackendId.COMPACT_CSR, msg);
                }
                return new BackendSelection<>(
                        referencePropagationEngine,
                        new SelectionDiagnostic<>(
                                GraphBackendId.DETERMINISTIC_OBJECT,
                                SelectionReason.FALLBACK_INELIGIBLE,
                                scale,
                                true,
                                Optional.of(msg),
                                ATTEMPTED_CSR_METADATA));
            }

            if (snapshot == null || !snapshot.isCurrent()) {
                String msg = snapshot == null
                        ? "Compact CSR execution requires a non-null CompactGraphSnapshot"
                        : "Compact graph snapshot is stale; topology was mutated after compilation";
                if (config.fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(GraphBackendId.COMPACT_CSR, msg);
                }
                return new BackendSelection<>(
                        referencePropagationEngine,
                        new SelectionDiagnostic<>(
                                GraphBackendId.DETERMINISTIC_OBJECT,
                                SelectionReason.FALLBACK_INELIGIBLE,
                                scale,
                                true,
                                Optional.of(msg),
                                ATTEMPTED_CSR_METADATA));
            }

            if (startNode != null && !snapshot.containsCanonical(startNode)) {
                String msg = "Start node is not a canonical member of the compact graph snapshot: " + startNode.getId();
                if (config.fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(GraphBackendId.COMPACT_CSR, msg);
                }
                return new BackendSelection<>(
                        referencePropagationEngine,
                        new SelectionDiagnostic<>(
                                GraphBackendId.DETERMINISTIC_OBJECT,
                                SelectionReason.FALLBACK_INELIGIBLE,
                                scale,
                                true,
                                Optional.of(msg),
                                Map.of(
                                        "attemptedBackend", GraphBackendId.COMPACT_CSR.name(),
                                        "noncanonicalStartNode", startNode.getId().toString())));
            }

            return new BackendSelection<>(
                    new CompactSignalPropagationEngine(snapshot),
                    new SelectionDiagnostic<>(
                            GraphBackendId.COMPACT_CSR,
                            SelectionReason.EXPLICIT_SELECTION,
                            scale,
                            false,
                            Optional.empty(),
                            Map.of("nodeCount", String.valueOf(snapshot.nodeCount()), "edgeCount", String.valueOf(snapshot.edgeCount()))));
        }

        // AUTO preference: ADR 0014 showed CSR did not meet the 75% allocation reduction gate and was slower on threshold routing.
        // Therefore CSR remains opt-in and is not promoted as an automatic default.
        return new BackendSelection<>(
                referencePropagationEngine,
                new SelectionDiagnostic<>(
                        GraphBackendId.DETERMINISTIC_OBJECT,
                        SelectionReason.REFERENCE_DEFAULT,
                        scale,
                        false,
                        Optional.empty(),
                        GRAPH_OBJECT_ENGINE_METADATA));
    }

    /**
     * Selects an Aeon coordinator for the given workload parameters.
     *
     * @param inputCount number of inputs to coordinate
     * @param isContextual {@code true} if running within a {@code CognitiveContext}
     * @param isIndependent {@code true} if inputs are guaranteed independent and read-only
     * @return backend selection containing the coordinator and inspectable diagnostic
     */
    public BackendSelection<CognitiveAeonCoordinator, AeonBackendId> selectAeonCoordinator(
            int inputCount,
            boolean isContextual,
            boolean isIndependent) {
        ExecutionPreference effectivePref = effectivePreference(config.aeon().preference());

        if (effectivePref == ExecutionPreference.REFERENCE) {
            SelectionReason reason = config.overallPreference() == ExecutionPreference.REFERENCE
                    ? SelectionReason.FORCED_REFERENCE
                    : SelectionReason.REFERENCE_DEFAULT;
            return new BackendSelection<>(
                    referenceAeonCoordinator,
                    new SelectionDiagnostic<>(
                            AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                            reason,
                            inputCount,
                            false,
                            Optional.empty(),
                            AEON_SEQUENTIAL_METADATA));
        }

        if (effectivePref == ExecutionPreference.EXPLICIT) {
            AeonBackendId target = config.aeon().explicitBackend()
                    .orElseThrow(() -> new IllegalStateException("explicitBackend must be present when preference is EXPLICIT"));
            if (target == AeonBackendId.DETERMINISTIC_SEQUENTIAL) {
                return new BackendSelection<>(
                        referenceAeonCoordinator,
                        new SelectionDiagnostic<>(
                                AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                                SelectionReason.EXPLICIT_SELECTION,
                                inputCount,
                                false,
                                Optional.empty(),
                                AEON_SEQUENTIAL_METADATA));
            }

            // Target is BOUNDED_PARALLEL
            if (config.aeon().maxParallelism() <= 1) {
                String msg = "Bounded parallel coordination requires maxParallelism > 1, but configured maxParallelism is "
                        + config.aeon().maxParallelism();
                if (config.fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(AeonBackendId.BOUNDED_PARALLEL, msg);
                }
                return new BackendSelection<>(
                        referenceAeonCoordinator,
                        new SelectionDiagnostic<>(
                                AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                                SelectionReason.FALLBACK_INELIGIBLE,
                                inputCount,
                                true,
                                Optional.of(msg),
                                ATTEMPTED_PARALLEL_METADATA));
            }

            if (!isIndependent) {
                String msg = "Bounded parallel coordination requires independent read-only node processing";
                if (config.fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(AeonBackendId.BOUNDED_PARALLEL, msg);
                }
                return new BackendSelection<>(
                        referenceAeonCoordinator,
                        new SelectionDiagnostic<>(
                                AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                                SelectionReason.FALLBACK_INELIGIBLE,
                                inputCount,
                                true,
                                Optional.of(msg),
                                ATTEMPTED_PARALLEL_METADATA));
            }

            if (isContextual && config.aeon().contextualMode() != ContextualParallelism.EXPERIMENTAL_PARALLEL) {
                String msg = "Contextual parallel coordination requires explicit EXPERIMENTAL_PARALLEL mode";
                if (config.fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(AeonBackendId.BOUNDED_PARALLEL, msg);
                }
                return new BackendSelection<>(
                        referenceAeonCoordinator,
                        new SelectionDiagnostic<>(
                                AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                                SelectionReason.FALLBACK_INELIGIBLE,
                                inputCount,
                                true,
                                Optional.of(msg),
                                ATTEMPTED_PARALLEL_METADATA));
            }

            var coordinator = createParallelCoordinator(config.aeon().contextualMode());
            return new BackendSelection<>(
                    coordinator,
                    new SelectionDiagnostic<>(
                            AeonBackendId.BOUNDED_PARALLEL,
                            SelectionReason.EXPLICIT_SELECTION,
                            inputCount,
                            false,
                            Optional.empty(),
                            Map.of(
                                    "maxParallelism", String.valueOf(config.aeon().maxParallelism()),
                                    "contextualMode", config.aeon().contextualMode().name())));
        }

        // AUTO preference
        if (isContextual) {
            // Per ADR 0016 and PR #38 gate, contextual parallel coordination was 38.4% slower and 39.4% higher allocation.
            // AUTO strictly preserves the sequential oracle.
            return new BackendSelection<>(
                    referenceAeonCoordinator,
                    new SelectionDiagnostic<>(
                            AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                            SelectionReason.AUTO_INELIGIBLE,
                            inputCount,
                            false,
                            Optional.of("Contextual parallel path failed benchmark gate per ADR 0016; sequential oracle preserved"),
                            Map.of("contextual", "true", "coordinator", "DeterministicAeonCoordinator")));
        }

        // Direct mode: evaluate threshold and specific rejection reasons
        int threshold = config.aeon().directParallelismThreshold();
        if (threshold == BoundedParallelAeonCoordinator.DEFAULT_PARALLELISM_THRESHOLD || inputCount < threshold) {
            return new BackendSelection<>(
                    referenceAeonCoordinator,
                    new SelectionDiagnostic<>(
                            AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                            SelectionReason.AUTO_BELOW_THRESHOLD,
                            inputCount,
                            false,
                            Optional.empty(),
                            Map.of("threshold", String.valueOf(threshold), "inputCount", String.valueOf(inputCount))));
        }

        if (capabilities.availableProcessors() <= 1) {
            return new BackendSelection<>(
                    referenceAeonCoordinator,
                    new SelectionDiagnostic<>(
                            AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                            SelectionReason.AUTO_UNAVAILABLE,
                            inputCount,
                            true,
                            Optional.of("Parallel execution unavailable: runtime reports <= 1 available processor"),
                            Map.of("availableProcessors", String.valueOf(capabilities.availableProcessors()))));
        }

        if (config.aeon().maxParallelism() <= 1) {
            return new BackendSelection<>(
                    referenceAeonCoordinator,
                    new SelectionDiagnostic<>(
                            AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                            SelectionReason.AUTO_INELIGIBLE,
                            inputCount,
                            false,
                            Optional.of("Parallel coordination ineligible: configured maxParallelism <= 1"),
                            Map.of("maxParallelism", String.valueOf(config.aeon().maxParallelism()))));
        }

        if (!isIndependent) {
            return new BackendSelection<>(
                    referenceAeonCoordinator,
                    new SelectionDiagnostic<>(
                            AeonBackendId.DETERMINISTIC_SEQUENTIAL,
                            SelectionReason.AUTO_INELIGIBLE,
                            inputCount,
                            false,
                            Optional.of("Workload is not independent read-only; parallel execution ineligible"),
                            Map.of("isIndependent", "false")));
        }

        var coordinator = createParallelCoordinator(ContextualParallelism.SEQUENTIAL_ORACLE);
        return new BackendSelection<>(
                coordinator,
                new SelectionDiagnostic<>(
                        AeonBackendId.BOUNDED_PARALLEL,
                        SelectionReason.AUTO_THRESHOLD_MET,
                        inputCount,
                        false,
                        Optional.empty(),
                        Map.of("threshold", String.valueOf(threshold), "inputCount", String.valueOf(inputCount))));
    }

    private CognitiveAeonCoordinator createParallelCoordinator(ContextualParallelism contextualMode) {
        return new BoundedParallelAeonCoordinator(
                referencePropagationEngine,
                ForkJoinPool.commonPool(),
                config.aeon().maxParallelism(),
                1,
                AeonParallelEligibility.INDEPENDENT_READ_ONLY,
                contextualMode);
    }

    private ExecutionPreference effectivePreference(ExecutionPreference componentPreference) {
        if (config.overallPreference() == ExecutionPreference.REFERENCE) {
            return ExecutionPreference.REFERENCE;
        }
        return componentPreference;
    }

    private static BatchResonanceEvaluator loadVectorEvaluatorIfAvailable() {
        try {
            Class<?> clazz = Class.forName(
                    "monada.neuron.resonance.VectorBatchResonanceEvaluator",
                    true,
                    RuntimeBackendSelector.class.getClassLoader());
            BatchResonanceEvaluator evaluator = (BatchResonanceEvaluator) clazz.getField("INSTANCE").get(null);
            return evaluator.isAvailable() ? evaluator : null;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return null;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
