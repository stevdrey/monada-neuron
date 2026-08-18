package monada.neuron.runtime.graph;

import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Portable sequential reference implementation of deterministic signal propagation.
 *
 * <p>Traversal is breadth-first. The start node is processed at hop zero. Each emitted signal is
 * considered in processor emission order and then against target nodes in ascending UUID order.
 * Every accepted delivery creates an independent work item, so nodes and structurally equal
 * signals may be revisited until a configured limit terminates propagation.
 *
 * <p>The engine caches one sorted adjacency snapshot per expanded node for the duration of the
 * call. Callers must not mutate node state or graph topology concurrently with propagation; the
 * current Phase-1 {@link Node} model is not thread-safe.
 *
 * <p>For expanded out-degrees {@code d}, adjacency preparation costs
 * {@code sum(d * log(d))}. Processing and routing otherwise scale with completed steps and
 * evaluated emitted-signal/edge pairs. Memory is bounded by the work queue, adjacency snapshots,
 * and the observable emitted-signal result, subject to the configured limits.
 */
public final class DeterministicSignalPropagationEngine implements CognitiveSignalPropagationEngine {

    private static final Comparator<Node> NODE_ID_ORDER = Comparator.comparing(Node::getId);

    @Override
    public PropagationResult propagate(
            Node startNode,
            Signal input,
            NodeProcessor processor,
            PropagationConfig config) {
        Objects.requireNonNull(startNode, "startNode must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        Objects.requireNonNull(config, "config must not be null");

        var pending = new ArrayDeque<PropagationWork>();
        var orderedAdjacency = new HashMap<Node, List<Node>>();
        var emittedSignals = new ArrayList<Signal>();
        pending.addLast(new PropagationWork(startNode, input, 0));

        int processedSteps = 0;
        boolean hopLimitReached = false;

        while (!pending.isEmpty() && processedSteps < config.maxSteps()) {
            var work = pending.removeFirst();
            NodeProcessingResult processingResult = Objects.requireNonNull(
                    processor.process(work.node(), work.signal()),
                    "processor result must not be null");
            processedSteps++;

            var outputs = processingResult.emittedSignals();
            emittedSignals.addAll(outputs);
            if (outputs.isEmpty()) {
                continue;
            }

            var targets = orderedConnections(work.node(), orderedAdjacency);
            for (var output : outputs) {
                for (var target : targets) {
                    if (!config.routingPolicy().shouldRoute(work.node(), target, output)) {
                        continue;
                    }
                    if (work.hop() == config.maxHops()) {
                        hopLimitReached = true;
                        continue;
                    }
                    pending.addLast(new PropagationWork(target, output, work.hop() + 1));
                }
            }
        }

        return new PropagationResult(
                emittedSignals,
                processedSteps,
                !pending.isEmpty(),
                hopLimitReached);
    }

    /**
     * Context-aware reference propagation that preserves the base traversal order while applying
     * shared cycle limits to steps, signal occurrences, and diagnostics.
     *
     * <p>Only successful {@link NodeProcessor} calls consume a context step. Inputs, accepted
     * emissions, and enqueued deliveries consume signal capacity. Once the global step budget is
     * exhausted, completed processing output remains observable but no further delivery is
     * enqueued. Existing queued work remains observable until another bound ends it.
     */
    @Override
    public PropagationResult propagate(
            Node startNode,
            Signal input,
            NodeProcessor processor,
            PropagationConfig config,
            CognitiveContext context) {
        Objects.requireNonNull(startNode, "startNode must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(context, "context must not be null");

        if (!context.hasRemainingStepCapacity()) {
            return new PropagationResult(List.of(), 0, true, false);
        }

        OptionalLong initialSequence = context.tryRecordInputSignal(startNode.getId(), input);
        if (initialSequence.isEmpty()) {
            return new PropagationResult(List.of(), 0, false, false);
        }

        var pending = new ArrayDeque<ContextualPropagationWork>();
        var orderedAdjacency = new HashMap<Node, List<Node>>();
        var emittedSignals = new ArrayList<Signal>();
        pending.addLast(new ContextualPropagationWork(startNode, input, 0, initialSequence.getAsLong()));

        int processedSteps = 0;
        boolean hopLimitReached = false;
        boolean contextStepLimitReached = false;

        while (!pending.isEmpty() && processedSteps < config.maxSteps()) {
            if (!context.hasRemainingStepCapacity()) {
                contextStepLimitReached = true;
                break;
            }

            var work = pending.removeFirst();
            NodeProcessingResult processingResult = Objects.requireNonNull(
                    processor.process(work.node(), work.signal()),
                    "processor result must not be null");
            processedSteps++;

            var outputs = processingResult.emittedSignals();
            context.recordCompletedStep(
                    work.node().getId(),
                    work.inputSignalSequence(),
                    outputs.size());
            if (outputs.isEmpty()) {
                continue;
            }

            boolean canEnqueueMoreWork = true;
            boolean signalBudgetReached = false;
            var targets = orderedConnections(work.node(), orderedAdjacency);
            for (var output : outputs) {
                OptionalLong emittedSequence = context.tryRecordEmittedSignal(work.node().getId(), output);
                if (emittedSequence.isEmpty()) {
                    signalBudgetReached = true;
                    break;
                }
                emittedSignals.add(output);

                if (!canEnqueueMoreWork) {
                    continue;
                }

                for (var target : targets) {
                    if (!config.routingPolicy().shouldRoute(work.node(), target, output)) {
                        continue;
                    }
                    if (work.hop() == config.maxHops()) {
                        hopLimitReached = true;
                        continue;
                    }
                    if (!context.hasRemainingStepCapacity()) {
                        contextStepLimitReached = true;
                        canEnqueueMoreWork = false;
                        break;
                    }

                    OptionalLong deliveredSequence = context.tryRecordDeliveredSignal(
                            work.node().getId(),
                            target.getId(),
                            output);
                    if (deliveredSequence.isEmpty()) {
                        signalBudgetReached = true;
                        break;
                    }

                    context.recordAcceptedRoute(
                            work.node().getId(),
                            target.getId(),
                            emittedSequence.getAsLong(),
                            deliveredSequence.getAsLong());
                    pending.addLast(new ContextualPropagationWork(
                            target,
                            output,
                            work.hop() + 1,
                            deliveredSequence.getAsLong()));
                }

                if (signalBudgetReached) {
                    break;
                }
            }
        }

        return new PropagationResult(
                emittedSignals,
                processedSteps,
                !pending.isEmpty() || contextStepLimitReached,
                hopLimitReached);
    }

    private List<Node> orderedConnections(
            Node node,
            Map<Node, List<Node>> orderedAdjacency) {
        return orderedAdjacency.computeIfAbsent(node, this::createOrderedConnections);
    }

    private List<Node> createOrderedConnections(Node node) {
        return node.getConnections().stream()
                .sorted(NODE_ID_ORDER)
                .toList();
    }

    /** One queued delivery; records keep traversal metadata outside the high-volume Signal value. */
    private record PropagationWork(Node node, Signal signal, int hop) {
    }

    /** One queued delivery with cycle-local correlation metadata. */
    private record ContextualPropagationWork(Node node, Signal signal, int hop, long inputSignalSequence) {
    }
}
