package monada.neuron.runtime.selection;

import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.runtime.graph.CognitiveSignalPropagationEngine;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Dynamic {@link CognitiveSignalPropagationEngine} that delegates execution to backends selected by
 * {@link RuntimeBackendSelector}.
 */
public final class SelectingSignalPropagationEngine implements CognitiveSignalPropagationEngine {

    private final RuntimeBackendSelector selector;
    private final Supplier<CompactGraphSnapshot> snapshotSupplier;
    private volatile SelectionDiagnostic<GraphBackendId> lastDiagnostic;

    public SelectingSignalPropagationEngine(RuntimeBackendSelector selector) {
        this(selector, () -> null);
    }

    public SelectingSignalPropagationEngine(
            RuntimeBackendSelector selector,
            CompactGraphSnapshot snapshot) {
        this(selector, () -> snapshot);
    }

    public SelectingSignalPropagationEngine(
            RuntimeBackendSelector selector,
            Supplier<CompactGraphSnapshot> snapshotSupplier) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier must not be null");
    }

    public SelectingSignalPropagationEngine(RuntimeSelectionConfig config) {
        this(new RuntimeBackendSelector(config));
    }

    public SelectingSignalPropagationEngine(
            RuntimeSelectionConfig config,
            CompactGraphSnapshot snapshot) {
        this(new RuntimeBackendSelector(config), () -> snapshot);
    }

    /** Returns the underlying backend selector. */
    public RuntimeBackendSelector selector() {
        return selector;
    }

    /** Returns the diagnostic record from the most recent propagation, if any. */
    public Optional<SelectionDiagnostic<GraphBackendId>> lastDiagnostic() {
        return Optional.ofNullable(lastDiagnostic);
    }

    @Override
    public PropagationResult propagate(
            Node startNode,
            Signal input,
            NodeProcessor processor,
            PropagationConfig config) {
        var snapshot = snapshotSupplier.get();
        var selection = selector.selectGraphPropagation(snapshot, startNode, false);
        this.lastDiagnostic = selection.diagnostic();
        try {
            return selection.backend().propagate(startNode, input, processor, config);
        } catch (IllegalStateException | IllegalArgumentException e) {
            if (selection.diagnostic().selectedBackendId() == GraphBackendId.COMPACT_CSR) {
                if (selector.config().fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(GraphBackendId.COMPACT_CSR, e.getMessage(), e);
                }
                this.lastDiagnostic = new SelectionDiagnostic<>(
                        GraphBackendId.DETERMINISTIC_OBJECT,
                        SelectionReason.FALLBACK_INELIGIBLE,
                        snapshot != null ? snapshot.nodeCount() : 0,
                        true,
                        Optional.of(e.getMessage()),
                        Map.of("attemptedBackend", GraphBackendId.COMPACT_CSR.name(), "failureReason", e.getMessage()));
                return selector.referencePropagationEngine().propagate(startNode, input, processor, config);
            }
            throw e;
        }
    }

    @Override
    public PropagationResult propagate(
            Node startNode,
            Signal input,
            NodeProcessor processor,
            PropagationConfig config,
            CognitiveContext context) {
        var snapshot = snapshotSupplier.get();
        var selection = selector.selectGraphPropagation(snapshot, startNode, true);
        this.lastDiagnostic = selection.diagnostic();
        try {
            return selection.backend().propagate(startNode, input, processor, config, context);
        } catch (IllegalStateException | IllegalArgumentException e) {
            if (selection.diagnostic().selectedBackendId() == GraphBackendId.COMPACT_CSR) {
                if (selector.config().fallbackPolicy() == FallbackPolicy.FAIL_FAST) {
                    throw new BackendIneligibleException(GraphBackendId.COMPACT_CSR, e.getMessage(), e);
                }
                this.lastDiagnostic = new SelectionDiagnostic<>(
                        GraphBackendId.DETERMINISTIC_OBJECT,
                        SelectionReason.FALLBACK_INELIGIBLE,
                        snapshot != null ? snapshot.nodeCount() : 0,
                        true,
                        Optional.of(e.getMessage()),
                        Map.of("attemptedBackend", GraphBackendId.COMPACT_CSR.name(), "failureReason", e.getMessage()));
                return selector.referencePropagationEngine().propagate(startNode, input, processor, config, context);
            }
            throw e;
        }
    }
}
