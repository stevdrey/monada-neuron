package monada.neuron.evolution;

import monada.neuron.aeon.Aeon;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code ADAPTATION} stage that consumes a prior cycle's {@link OutcomeFeedback}.
 *
 * <p>The feedback must have been produced by the Monad that executes the cycle; {@link #validate} rejects
 * any other. The feedback is supplied by the caller when the stage is created for this cycle, so the
 * canonical order {@code ... -> ADAPTATION -> ACTION} is untouched and no state survives the stage:
 * the artifact came from an earlier cycle that has already ended. Reusing one stage instance for
 * several cycles reapplies the same feedback; carrying feedback forward is always the caller's
 * explicit choice.
 *
 * <p>Entries are applied in {@link OutcomeFeedback#entries()} order. An entry whose target is not
 * among this stage's target Nodes is ineligible: it is counted and skipped, never an error, because a
 * later cycle may legitimately be configured with different targets. Neutral feedback applies
 * nothing. Either way one {@link monada.neuron.context.CognitiveTraceEvent.FeedbackConsumed} event is
 * recorded, followed by the usual {@code NodeAdapted} events as they happen. The cycle's signals pass
 * through unchanged so later stages still run.
 */
public final class FeedbackAdaptationCognitiveStage implements CognitiveStage {

    private final AdaptationPolicy policy;
    private final HashMap<UUID, Node> targetsById;
    private final OutcomeFeedback feedback;

    /**
     * Creates the stage with an explicit policy, target Nodes, and the prior-cycle feedback.
     *
     * <p>Targets are indexed by identifier once. Lookup cost is O(1) per entry and, because entries are
     * bounded by {@link OutcomeFeedback#MAX_ENTRIES}, iteration never depends on the index's order.
     */
    public FeedbackAdaptationCognitiveStage(
            AdaptationPolicy policy,
            List<Node> targetNodes,
            OutcomeFeedback feedback) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        var targets = Objects.requireNonNull(targetNodes, "targetNodes must not be null");
        this.targetsById = new HashMap<>(Math.max(16, targets.size() * 2));
        for (var node : targets) {
            Objects.requireNonNull(node, "targetNodes must not contain null elements");
            targetsById.putIfAbsent(node.getId(), node);
        }
        this.feedback = Objects.requireNonNull(feedback, "feedback must not be null");
    }

    /** Creates the stage targeting all member Nodes of an Aeon. */
    public FeedbackAdaptationCognitiveStage(
            AdaptationPolicy policy,
            Aeon targetAeon,
            OutcomeFeedback feedback) {
        this(policy,
                List.copyOf(Objects.requireNonNull(targetAeon, "targetAeon must not be null").getMembers()),
                feedback);
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ADAPTATION;
    }

    /**
     * Rejects feedback that a different Monad produced.
     *
     * <p>{@link OutcomeFeedback#monadId()} names the producing Monad, so an artifact from Monad A cannot
     * adapt Nodes through a cycle that Monad B executes. The cycle calls this before creating its context,
     * so a mismatch fails before any Node changes.
     *
     * @throws IllegalArgumentException if the feedback was produced by another Monad
     */
    @Override
    public void validate(PrimaryMonad monad) {
        Objects.requireNonNull(monad, "monad must not be null");
        if (!feedback.monadId().equals(monad.getId())) {
            throw new IllegalArgumentException(
                    "feedback was produced by Monad " + feedback.monadId()
                            + " and cannot be consumed by Monad " + monad.getId());
        }
    }

    @Override
    public AdaptationCognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        validate(monad);
        var stableInputs = List.copyOf(Objects.requireNonNull(inputSignals, "inputSignals must not be null"));
        Objects.requireNonNull(context, "context must not be null");

        var decisions = new ArrayList<AdaptationDecision>(feedback.entries().size());
        var ineligible = 0;
        for (var entry : feedback.entries()) {
            var node = targetsById.get(entry.targetNodeId());
            if (node == null) {
                ineligible++;
                continue;
            }
            var decision = Objects.requireNonNull(
                    policy.adapt(node, entry.toFeedbackInput()),
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
        context.recordFeedbackConsumed(
                feedback.originCycleOrdinal(),
                feedback.sourceStatus(),
                feedback.disposition(),
                feedback.entries().size(),
                decisions.size(),
                ineligible);
        return new AdaptationCognitiveStageResult(decisions, stableInputs);
    }
}
