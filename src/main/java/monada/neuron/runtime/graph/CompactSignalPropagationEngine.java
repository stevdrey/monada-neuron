package monada.neuron.runtime.graph;

import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Optional CSR-backed implementation of deterministic direct signal propagation.
 *
 * <p>The engine reuses a {@link CompactGraphSnapshot} prepared outside the traversal hot path.
 * The object-rich {@link DeterministicSignalPropagationEngine} remains the semantic oracle and
 * handles context-aware propagation, whose bounded trace and shared-budget semantics are outside
 * this compact experiment.
 */
public final class CompactSignalPropagationEngine implements CognitiveSignalPropagationEngine {

    private final CompactGraphSnapshot snapshot;
    private final DeterministicSignalPropagationEngine contextualFallback;

    /** Creates a compact engine bound to one immutable compiled topology view. */
    public CompactSignalPropagationEngine(CompactGraphSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.contextualFallback = new DeterministicSignalPropagationEngine();
    }

    /**
     * Propagates through compact CSR adjacency while preserving the reference engine's observable
     * direct traversal semantics.
     */
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

        snapshot.requireCurrent();
        int startIndex = snapshot.requireCanonicalIndex(startNode);
        var pending = new CompactPropagationQueue(config.maxSteps());
        var emittedSignals = new ArrayList<Signal>();
        pending.addLast(startIndex, input, 0);

        int processedSteps = 0;
        boolean hopLimitReached = false;
        boolean overflowPending = false;

        while (!pending.isEmpty() && processedSteps < config.maxSteps()) {
            int sourceIndex = pending.firstNodeIndex();
            Signal received = pending.firstSignal();
            int hop = pending.firstHop();
            pending.removeFirst();

            Node source = snapshot.nodeAt(sourceIndex);
            NodeProcessingResult processingResult = Objects.requireNonNull(
                    processor.process(source, received),
                    "processor result must not be null");
            processedSteps++;

            List<Signal> outputs = processingResult.emittedSignals();
            emittedSignals.addAll(outputs);
            if (outputs.isEmpty()) {
                continue;
            }

            int firstTarget = snapshot.firstTargetOffset(sourceIndex);
            int targetLimit = snapshot.targetLimitOffset(sourceIndex);
            for (Signal output : outputs) {
                for (int targetOffset = firstTarget; targetOffset < targetLimit; targetOffset++) {
                    Node target = snapshot.nodeAt(snapshot.targetAt(targetOffset));
                    if (!config.routingPolicy().shouldRoute(source, target, output)) {
                        continue;
                    }
                    if (hop == config.maxHops()) {
                        hopLimitReached = true;
                        continue;
                    }

                    int remainingSteps = config.maxSteps() - processedSteps;
                    if (pending.size() >= remainingSteps) {
                        overflowPending = true;
                        continue;
                    }
                    pending.addLast(snapshot.targetAt(targetOffset), output, hop + 1);
                }
            }
        }

        snapshot.requireCurrent();
        return new PropagationResult(
                emittedSignals,
                processedSteps,
                !pending.isEmpty() || overflowPending,
                hopLimitReached);
    }

    /**
     * Delegates contextual propagation to the object-rich reference engine.
     *
     * <p>The fallback preserves existing shared-budget and trace behavior and deliberately reads
     * the current live topology rather than this snapshot.
     */
    @Override
    public PropagationResult propagate(
            Node startNode,
            Signal input,
            NodeProcessor processor,
            PropagationConfig config,
            CognitiveContext context) {
        return contextualFallback.propagate(startNode, input, processor, config, context);
    }

    /** Primitive parallel-array FIFO that retains only work that can still be processed. */
    private static final class CompactPropagationQueue {

        private final int maximumCapacity;
        private int[] nodeIndices;
        private Signal[] signals;
        private int[] hops;
        private int head;
        private int size;

        private CompactPropagationQueue(int maximumCapacity) {
            this.maximumCapacity = maximumCapacity;
            int initialCapacity = Math.min(16, maximumCapacity);
            this.nodeIndices = new int[initialCapacity];
            this.signals = new Signal[initialCapacity];
            this.hops = new int[initialCapacity];
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private int size() {
            return size;
        }

        private void addLast(int nodeIndex, Signal signal, int hop) {
            if (size == nodeIndices.length) {
                grow();
            }
            int tail = (head + size) % nodeIndices.length;
            nodeIndices[tail] = nodeIndex;
            signals[tail] = signal;
            hops[tail] = hop;
            size++;
        }

        private int firstNodeIndex() {
            return nodeIndices[head];
        }

        private Signal firstSignal() {
            return signals[head];
        }

        private int firstHop() {
            return hops[head];
        }

        private void removeFirst() {
            signals[head] = null;
            head = (head + 1) % nodeIndices.length;
            size--;
        }

        private void grow() {
            if (nodeIndices.length == maximumCapacity) {
                throw new IllegalStateException("compact propagation queue exceeded maximum capacity");
            }
            int nextCapacity = Math.min(maximumCapacity, nodeIndices.length * 2);
            int[] expandedIndices = new int[nextCapacity];
            Signal[] expandedSignals = new Signal[nextCapacity];
            int[] expandedHops = new int[nextCapacity];
            for (int index = 0; index < size; index++) {
                int sourceIndex = (head + index) % nodeIndices.length;
                expandedIndices[index] = nodeIndices[sourceIndex];
                expandedSignals[index] = signals[sourceIndex];
                expandedHops[index] = hops[sourceIndex];
            }
            nodeIndices = expandedIndices;
            signals = expandedSignals;
            hops = expandedHops;
            head = 0;
        }
    }
}
