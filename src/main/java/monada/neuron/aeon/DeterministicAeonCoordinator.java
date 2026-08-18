package monada.neuron.aeon;

import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.runtime.graph.CognitiveSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.runtime.graph.SignalPropagationEngine;
import monada.neuron.runtime.graph.SignalRoutingPolicy;
import monada.neuron.signal.NodeProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sequential reference coordinator that delegates every graph traversal to a propagation engine.
 *
 * <p>All inputs and starting memberships are validated before processing begins. Propagation is
 * confined to canonical Aeon member instances by composing the caller's routing policy with
 * membership gating. The existing Node graph remains the single topology source and is never
 * copied or mutated by this coordinator.
 *
 * <p>Membership, Node state, and graph topology must not change during this call. Processing and
 * routing failures propagate to the caller; no partial coordination result is returned.
 */
public final class DeterministicAeonCoordinator implements CognitiveAeonCoordinator {

    private final SignalPropagationEngine propagationEngine;

    /** Creates a coordinator backed by the supplied graph propagation implementation. */
    public DeterministicAeonCoordinator(SignalPropagationEngine propagationEngine) {
        this.propagationEngine = Objects.requireNonNull(
                propagationEngine,
                "propagationEngine must not be null");
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

        var stableInputs = List.copyOf(inputs);
        if (stableInputs.isEmpty()) {
            return AeonCoordinationResult.empty();
        }

        var startNodes = resolveStartNodes(aeon, stableInputs);
        var confinedConfig = confinedConfig(aeon, config);
        var inputResults = new ArrayList<AeonInputResult>(stableInputs.size());
        for (int index = 0; index < stableInputs.size(); index++) {
            var input = stableInputs.get(index);
            PropagationResult propagationResult = Objects.requireNonNull(
                    propagationEngine.propagate(
                            startNodes.get(index),
                            input.signal(),
                            processor,
                            confinedConfig),
                    "propagation result must not be null");
            inputResults.add(new AeonInputResult(input, propagationResult));
        }
        return new AeonCoordinationResult(inputResults);
    }

    /**
     * Coordinates inputs through a context-aware propagation engine while preserving the legacy
     * Aeon ordering, membership validation, and topology confinement rules.
     *
     * <p>Every declared input receives one result in its original order. If a shared cycle budget
     * has already suppressed the input, the result has zero steps and the context trace identifies
     * the applicable cycle-limit condition.
     */
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
        if (!context.isActive()) {
            throw new IllegalStateException("context must be active: " + context.lifecycle());
        }

        var stableInputs = List.copyOf(inputs);
        if (stableInputs.isEmpty()) {
            return AeonCoordinationResult.empty();
        }

        var startNodes = resolveStartNodes(aeon, stableInputs);
        if (!(propagationEngine instanceof CognitiveSignalPropagationEngine cognitiveEngine)) {
            throw new UnsupportedOperationException(
                    "propagationEngine must implement CognitiveSignalPropagationEngine");
        }

        var confinedConfig = confinedConfig(aeon, config);
        var inputResults = new ArrayList<AeonInputResult>(stableInputs.size());
        for (int index = 0; index < stableInputs.size(); index++) {
            var input = stableInputs.get(index);
            boolean stepBudgetExhaustedBefore = context.stepBudgetExhausted();
            boolean signalBudgetExhaustedBefore = context.signalBudgetExhausted();
            context.recordAeonInputStarted(aeon.getId(), input.startNodeId());

            PropagationResult propagationResult = Objects.requireNonNull(
                    cognitiveEngine.propagate(
                            startNodes.get(index),
                            input.signal(),
                            processor,
                            confinedConfig,
                            context),
                    "propagation result must not be null");
            var inputResult = new AeonInputResult(input, propagationResult);
            inputResults.add(inputResult);
            context.recordAeonResult(aeon.getId(), inputResult);

            boolean contextLimitReached = (!stepBudgetExhaustedBefore
                    && context.stepBudgetExhausted())
                    || (!signalBudgetExhaustedBefore && context.signalBudgetExhausted())
                    || (propagationResult.processedSteps() == 0
                    && (context.stepBudgetExhausted() || context.signalBudgetExhausted()));
            context.recordAeonInputCompleted(
                    aeon.getId(),
                    input.startNodeId(),
                    propagationResult.processedSteps(),
                    propagationResult.stepLimitReached(),
                    propagationResult.hopLimitReached(),
                    contextLimitReached);
        }
        return new AeonCoordinationResult(inputResults);
    }

    private List<Node> resolveStartNodes(Aeon aeon, List<AeonInput> inputs) {
        var startNodes = new ArrayList<Node>(inputs.size());
        for (var input : inputs) {
            var startNode = aeon.findMember(input.startNodeId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "startNodeId must identify an Aeon member: " + input.startNodeId()));
            startNodes.add(startNode);
        }
        return startNodes;
    }

    private PropagationConfig confinedConfig(Aeon aeon, PropagationConfig config) {
        SignalRoutingPolicy callerPolicy = config.routingPolicy();
        SignalRoutingPolicy confinedPolicy = (source, target, signal) ->
                aeon.isCanonicalMember(target)
                        && callerPolicy.shouldRoute(source, target, signal);
        return new PropagationConfig(config.maxSteps(), config.maxHops(), confinedPolicy);
    }
}
