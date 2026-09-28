package monada.neuron.runtime.selection;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonCoordinationResult;
import monada.neuron.aeon.AeonInput;
import monada.neuron.aeon.AeonParallelEligibility;
import monada.neuron.aeon.CognitiveAeonCoordinator;
import monada.neuron.context.CognitiveContext;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic {@link CognitiveAeonCoordinator} that delegates coordination to backends selected by
 * {@link RuntimeBackendSelector}.
 */
public final class SelectingAeonCoordinator implements CognitiveAeonCoordinator {

    private final RuntimeBackendSelector selector;
    private final AeonParallelEligibility eligibility;
    private volatile SelectionDiagnostic<AeonBackendId> lastDiagnostic;

    public SelectingAeonCoordinator(RuntimeBackendSelector selector) {
        this(selector, AeonParallelEligibility.INDEPENDENT_READ_ONLY);
    }

    public SelectingAeonCoordinator(
            RuntimeBackendSelector selector,
            AeonParallelEligibility eligibility) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility must not be null");
    }

    public SelectingAeonCoordinator(RuntimeSelectionConfig config) {
        this(new RuntimeBackendSelector(config));
    }

    /** Returns the underlying backend selector. */
    public RuntimeBackendSelector selector() {
        return selector;
    }

    /** Returns the diagnostic record from the most recent coordination, if any. */
    public Optional<SelectionDiagnostic<AeonBackendId>> lastDiagnostic() {
        return Optional.ofNullable(lastDiagnostic);
    }

    @Override
    public AeonCoordinationResult coordinate(
            Aeon aeon,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config) {
        Objects.requireNonNull(aeon, "aeon must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        Objects.requireNonNull(config, "config must not be null");

        boolean isIndependent = eligibility.isEligible(aeon, inputs, processor, config);
        var selection = selector.selectAeonCoordinator(inputs.size(), false, isIndependent);
        this.lastDiagnostic = selection.diagnostic();
        return selection.backend().coordinate(aeon, inputs, processor, config);
    }

    @Override
    public AeonCoordinationResult coordinate(
            Aeon aeon,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config,
            CognitiveContext context) {
        Objects.requireNonNull(aeon, "aeon must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(context, "context must not be null");

        boolean isIndependent = eligibility.isEligible(aeon, inputs, processor, config);
        var selection = selector.selectAeonCoordinator(inputs.size(), true, isIndependent);
        this.lastDiagnostic = selection.diagnostic();
        return selection.backend().coordinate(aeon, inputs, processor, config, context);
    }
}
