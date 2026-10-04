package monada.neuron.evolution;

import monada.neuron.aeon.Aeon;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.model.Node;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

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
 * nothing. A {@code NodeAdapted} event is recorded for each applied entry as it happens, in entry
 * order. Either way exactly one {@link CognitiveTraceEvent.FeedbackConsumed} event
 * is recorded last, after every entry was visited, because it carries how many entries were adapted
 * (the policy changed a Node), left unchanged (an eligible target the policy did not change, as the
 * no-op policy never does), and ineligible.
 * The cycle's signals pass through unchanged so later stages still run.
 *
 * <p><strong>Observable consumption.</strong> The stage runs only if the cycle reaches {@code ADAPTATION}.
 * When no Signal reaches it (and no typed artifact keeps the cycle alive) the cycle ends with
 * {@code NO_SIGNALS} before the stage: the artifact was not consumed, no {@code FeedbackConsumed} event is
 * recorded, and the caller still owns it. Consumption is therefore visible without the trace as the presence
 * of an {@link AdaptationCognitiveStageResult} in {@code CognitiveCycleResult.stageResults()}.
 */
public final class FeedbackAdaptationCognitiveStage implements CognitiveStage {

    private final AdaptationPolicy policy;
    private final Function<UUID, Node> targets;
    private final OutcomeFeedback feedback;

    /**
     * Creates the stage with an explicit policy, target Nodes, and the prior-cycle feedback.
     *
     * <p>Node identity is its UUID, so two targets that the feedback refers to and that share an identifier
     * are ambiguous and rejected with an {@link IllegalArgumentException} instead of silently ignoring one;
     * a duplicate the feedback never refers to is inert. One pass over {@code targetNodes} resolves only the
     * (at most {@link OutcomeFeedback#MAX_ENTRIES}) referenced Nodes, so the stage allocates in proportion to
     * the feedback and not to the number of candidate targets.
     */
    public FeedbackAdaptationCognitiveStage(
            AdaptationPolicy policy,
            List<Node> targetNodes,
            OutcomeFeedback feedback) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(targetNodes, "targetNodes must not be null");
        this.feedback = Objects.requireNonNull(feedback, "feedback must not be null");
        var referenced = HashSet.<UUID>newHashSet(feedback.entries().size());
        for (var entry : feedback.entries()) {
            referenced.add(entry.targetNodeId());
        }
        var resolved = HashMap.<UUID, Node>newHashMap(referenced.size());
        for (var node : targetNodes) {
            Objects.requireNonNull(node, "targetNodes must not contain null elements");
            if (referenced.contains(node.getId()) && resolved.putIfAbsent(node.getId(), node) != null) {
                throw new IllegalArgumentException("duplicate target node: " + node.getId());
            }
        }
        this.targets = resolved::get;
    }

    /**
     * Creates the stage targeting the member Nodes of an Aeon.
     *
     * <p>Members are resolved through {@link Aeon#findMember} when the stage executes, in O(1) per entry and
     * without copying the membership. The stage is created for one cycle, and Aeon membership must not change
     * while a cycle executes, so a lookup at execution time sees the members the cycle runs with.
     */
    public FeedbackAdaptationCognitiveStage(
            AdaptationPolicy policy,
            Aeon targetAeon,
            OutcomeFeedback feedback) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(targetAeon, "targetAeon must not be null");
        this.feedback = Objects.requireNonNull(feedback, "feedback must not be null");
        this.targets = id -> targetAeon.findMember(id).orElse(null);
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ADAPTATION;
    }

    /**
     * The stage takes its input from the feedback artifact, not from Signals, so it also runs when the
     * preceding result retains a typed artifact (such as hypotheses) and emits no Signals.
     */
    @Override
    public boolean acceptsTypedOnlyHandOff() {
        return true;
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
        var adapted = 0;
        var ineligible = 0;
        for (var entry : feedback.entries()) {
            var node = targets.apply(entry.targetNodeId());
            if (node == null) {
                ineligible++;
                continue;
            }
            var decision = Objects.requireNonNull(
                    policy.adapt(node, entry.toFeedbackInput()),
                    "adaptation policy decision must not be null");
            decisions.add(decision);
            if (decision.adapted()) {
                adapted++;
            }
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
                adapted,
                decisions.size() - adapted,
                ineligible);
        return new AdaptationCognitiveStageResult(decisions, stableInputs);
    }
}
