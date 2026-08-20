package monada.neuron.evolution;

import monada.neuron.aeon.Aeon;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Optional cognitive stage that adapts eligible target nodes based on evaluated feedback signals.
 *
 * <p>When executing, admitted input signals are mapped cyclically (round-robin by target node index,
 * {@code inputSignals.get(i % inputSignals.size())}) to each target node and passed to the
 * {@link BiFunction} feedback mapper. Callers requiring custom multi-signal routing can supply a
 * specialized feedback mapper.
 */
public final class AdaptationCognitiveStage implements CognitiveStage {

    private final AdaptationPolicy policy;
    private final List<Node> targetNodes;
    private final BiFunction<Node, Signal, FeedbackInput> feedbackMapper;

    /**
     * Creates an adaptation stage with explicit policy, target nodes, and feedback mapping.
     *
     * <p>Target nodes are evaluated sequentially in the supplied order. Input signals are distributed
     * cyclically across target nodes if fewer or more signals than nodes are available.
     */
    public AdaptationCognitiveStage(
            AdaptationPolicy policy,
            List<Node> targetNodes,
            BiFunction<Node, Signal, FeedbackInput> feedbackMapper) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.targetNodes = List.copyOf(Objects.requireNonNull(targetNodes, "targetNodes must not be null"));
        for (var node : this.targetNodes) {
            Objects.requireNonNull(node, "targetNodes must not contain null elements");
        }
        this.feedbackMapper = Objects.requireNonNull(feedbackMapper, "feedbackMapper must not be null");
    }

    /**
     * Creates an adaptation stage with a default target-signal feedback mapping.
     *
     * <p>Distributes input signals cyclically across target nodes, pairing each {@code (node, signal)}
     * via {@link FeedbackInput#ofTarget(java.util.UUID, Signal, double)} with a score of {@code 1.0}.
     */
    public AdaptationCognitiveStage(AdaptationPolicy policy, List<Node> targetNodes) {
        this(policy, targetNodes, (node, signal) -> FeedbackInput.ofTarget(node.getId(), signal, 1.0));
    }

    /**
     * Creates an adaptation stage targeting all member nodes of an Aeon.
     */
    public AdaptationCognitiveStage(AdaptationPolicy policy, Aeon targetAeon) {
        this(policy, List.copyOf(Objects.requireNonNull(targetAeon, "targetAeon must not be null").getMembers()));
    }

    /**
     * Creates an adaptation stage targeting all member nodes of an Aeon with a custom feedback mapping.
     */
    public AdaptationCognitiveStage(
            AdaptationPolicy policy,
            Aeon targetAeon,
            BiFunction<Node, Signal, FeedbackInput> feedbackMapper) {
        this(policy, List.copyOf(Objects.requireNonNull(targetAeon, "targetAeon must not be null").getMembers()), feedbackMapper);
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ADAPTATION;
    }

    @Override
    public void validate(PrimaryMonad monad) {
        Objects.requireNonNull(monad, "monad must not be null");
    }

    @Override
    public AdaptationCognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        validate(monad);
        var stableInputs = List.copyOf(Objects.requireNonNull(
                inputSignals,
                "inputSignals must not be null"));
        Objects.requireNonNull(context, "context must not be null");

        if (stableInputs.isEmpty() || targetNodes.isEmpty()) {
            return new AdaptationCognitiveStageResult(List.of(), stableInputs);
        }

        var decisions = new ArrayList<AdaptationDecision>(targetNodes.size());
        for (int i = 0; i < targetNodes.size(); i++) {
            var node = targetNodes.get(i);
            var signal = stableInputs.get(i % stableInputs.size());
            var feedback = Objects.requireNonNull(
                    feedbackMapper.apply(node, signal),
                    "feedbackMapper must not return null");
            var decision = Objects.requireNonNull(
                    policy.adapt(node, feedback),
                    "adaptation policy decision must not be null");
            decisions.add(decision);
            context.recordNodeAdapted(
                    decision.nodeId(),
                    decision.adapted(),
                    decision.previousState(),
                    decision.newState(),
                    decision.previousEnergy(),
                    decision.newEnergy());
        }

        return new AdaptationCognitiveStageResult(decisions, stableInputs);
    }
}
