package monada.neuron.aeon;

import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.runtime.graph.CognitiveSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.runtime.graph.SignalPropagationEngine;
import monada.neuron.runtime.graph.SignalRoutingPolicy;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;

/**
 * Bounded parallel implementation of {@link CognitiveAeonCoordinator} that executes independent
 * Aeon input propagations concurrently across CPU cores.
 *
 * <p>Caller-visible results strictly preserve the declared input order. In contextual mode, the
 * shared {@link CognitiveContext} is never mutated concurrently: worker threads record isolated
 * execution logs in bounded waves, and the coordinator reconciles events, step/signal budgets, and trace entries
 * sequentially on the calling thread in exact input index order.
 *
 * <p>If any input task encounters an exception, sibling tasks are cancelled and the exception
 * belonging to the earliest input index in original order is thrown, with any concurrent exceptions
 * attached as suppressed. Workloads that do not satisfy independence or meet the parallelism
 * threshold automatically fall back to the sequential reference path.
 */
public final class BoundedParallelAeonCoordinator implements CognitiveAeonCoordinator, AutoCloseable {

    public static final int DEFAULT_PARALLELISM_THRESHOLD = 2;

    private static final Comparator<Node> NODE_ID_ORDER = Comparator.comparing(Node::getId);

    private final SignalPropagationEngine propagationEngine;
    private final DeterministicAeonCoordinator sequentialFallback;
    private final Executor executor;
    private final int maxParallelism;
    private final int parallelismThreshold;
    private final AeonParallelEligibility eligibility;
    private final boolean ownsExecutor;

    /** Creates a parallel coordinator using default CPU core bounds and the shared ForkJoin pool. */
    public BoundedParallelAeonCoordinator(SignalPropagationEngine propagationEngine) {
        this(
                propagationEngine,
                ForkJoinPool.commonPool(),
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                DEFAULT_PARALLELISM_THRESHOLD,
                AeonParallelEligibility.INDEPENDENT_READ_ONLY,
                false);
    }

    /** Creates a parallel coordinator with explicit maximum CPU parallelism. */
    public BoundedParallelAeonCoordinator(SignalPropagationEngine propagationEngine, int maxParallelism) {
        this(propagationEngine, maxParallelism, DEFAULT_PARALLELISM_THRESHOLD);
    }

    /** Creates a parallel coordinator with explicit maximum CPU parallelism and threshold. */
    public BoundedParallelAeonCoordinator(
            SignalPropagationEngine propagationEngine,
            int maxParallelism,
            int parallelismThreshold) {
        this(
                propagationEngine,
                Executors.newFixedThreadPool(validateParallelism(maxParallelism), daemonThreadFactory()),
                maxParallelism,
                parallelismThreshold,
                AeonParallelEligibility.INDEPENDENT_READ_ONLY,
                true);
    }

    /** Creates a parallel coordinator with explicit executor, bounds, and eligibility policy. */
    public BoundedParallelAeonCoordinator(
            SignalPropagationEngine propagationEngine,
            Executor executor,
            int maxParallelism,
            int parallelismThreshold,
            AeonParallelEligibility eligibility) {
        this(
                propagationEngine,
                executor,
                maxParallelism,
                parallelismThreshold,
                eligibility,
                false);
    }

    private BoundedParallelAeonCoordinator(
            SignalPropagationEngine propagationEngine,
            Executor executor,
            int maxParallelism,
            int parallelismThreshold,
            AeonParallelEligibility eligibility,
            boolean ownsExecutor) {
        this.propagationEngine = Objects.requireNonNull(
                propagationEngine,
                "propagationEngine must not be null");
        this.sequentialFallback = new DeterministicAeonCoordinator(propagationEngine);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.maxParallelism = validateParallelism(maxParallelism);
        this.parallelismThreshold = validateThreshold(parallelismThreshold);
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility must not be null");
        this.ownsExecutor = ownsExecutor;
    }

    /** Returns the underlying propagation engine. */
    public SignalPropagationEngine propagationEngine() {
        return propagationEngine;
    }

    /** Returns the maximum configured parallelism. */
    public int maxParallelism() {
        return maxParallelism;
    }

    /** Returns the minimum input count required to trigger parallel execution. */
    public int parallelismThreshold() {
        return parallelismThreshold;
    }

    /** Returns the active parallel eligibility policy. */
    public AeonParallelEligibility eligibility() {
        return eligibility;
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
        if (!isParallelEligible(aeon, stableInputs, processor, config)) {
            return sequentialFallback.coordinate(aeon, stableInputs, processor, config);
        }

        var confinedConfig = confinedConfig(aeon, config);
        int inputCount = stableInputs.size();
        var results = new PropagationResult[inputCount];
        var failures = new Throwable[inputCount];
        var futures = new CompletableFuture<?>[inputCount];

        for (int i = 0; i < inputCount; i++) {
            final int index = i;
            futures[index] = CompletableFuture.runAsync(() -> {
                try {
                    results[index] = Objects.requireNonNull(
                            propagationEngine.propagate(
                                    startNodes.get(index),
                                    stableInputs.get(index).signal(),
                                    processor,
                                    confinedConfig),
                            "propagation result must not be null");
                } catch (Throwable t) {
                    failures[index] = t;
                }
            }, executor);
        }

        CompletableFuture.allOf(futures).join();

        checkAndPropagateFailures(failures);

        var inputResults = new ArrayList<AeonInputResult>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            inputResults.add(new AeonInputResult(stableInputs.get(i), results[i]));
        }
        return new AeonCoordinationResult(inputResults);
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
        if (!context.isActive()) {
            throw new IllegalStateException("context must be active: " + context.lifecycle());
        }

        var stableInputs = List.copyOf(inputs);
        if (stableInputs.isEmpty()) {
            return AeonCoordinationResult.empty();
        }

        var startNodes = resolveStartNodes(aeon, stableInputs);
        if (!(propagationEngine instanceof CognitiveSignalPropagationEngine)) {
            throw new UnsupportedOperationException(
                    "propagationEngine must implement CognitiveSignalPropagationEngine");
        }

        if (!isParallelEligible(aeon, stableInputs, processor, config)) {
            return sequentialFallback.coordinate(aeon, stableInputs, processor, config, context);
        }

        var confinedConfig = confinedConfig(aeon, config);
        int inputCount = stableInputs.size();
        int waveSize = Math.max(parallelismThreshold, maxParallelism);
        var inputResults = new ArrayList<AeonInputResult>(inputCount);

        int budgetMaxSteps = context.budget().maxSteps();
        int budgetMaxSignals = context.budget().maxSignals();

        for (int waveStart = 0; waveStart < inputCount; waveStart += waveSize) {
            int waveEnd = Math.min(inputCount, waveStart + waveSize);
            int waveLength = waveEnd - waveStart;

            if (!context.hasRemainingStepCapacity()) {
                for (int i = waveStart; i < waveEnd; i++) {
                    var input = stableInputs.get(i);
                    boolean stepBudgetExhaustedBefore = context.stepBudgetExhausted();
                    boolean signalBudgetExhaustedBefore = context.signalBudgetExhausted();
                    context.recordAeonInputStarted(aeon.getId(), input.startNodeId());

                    var emptyResult = new PropagationResult(List.of(), 0, false, false);
                    var inputResult = new AeonInputResult(input, emptyResult);
                    inputResults.add(inputResult);

                    boolean contextLimitReached = (!stepBudgetExhaustedBefore && context.stepBudgetExhausted())
                            || (!signalBudgetExhaustedBefore && context.signalBudgetExhausted())
                            || (context.stepBudgetExhausted() || context.signalBudgetExhausted());

                    context.recordAeonInputCompleted(
                            aeon.getId(),
                            input.startNodeId(),
                            0,
                            false,
                            false,
                            contextLimitReached);
                }
                continue;
            }

            var waveLogs = new InputExecutionLog[waveLength];
            var waveFailures = new Throwable[waveLength];
            var waveFutures = new CompletableFuture<?>[waveLength];

            for (int w = 0; w < waveLength; w++) {
                final int waveIndex = w;
                final int inputIndex = waveStart + w;
                waveFutures[waveIndex] = CompletableFuture.runAsync(() -> {
                    try {
                        waveLogs[waveIndex] = recordExecution(
                                startNodes.get(inputIndex),
                                stableInputs.get(inputIndex).signal(),
                                processor,
                                confinedConfig,
                                budgetMaxSteps,
                                budgetMaxSignals);
                    } catch (Throwable t) {
                        waveFailures[waveIndex] = t;
                    }
                }, executor);
            }

            CompletableFuture.allOf(waveFutures).join();

            reconcileContextualWave(
                    aeon,
                    stableInputs,
                    waveStart,
                    waveLength,
                    confinedConfig,
                    context,
                    waveLogs,
                    waveFailures,
                    inputResults);
        }

        return new AeonCoordinationResult(inputResults);
    }

    private void reconcileContextualWave(
            Aeon aeon,
            List<AeonInput> stableInputs,
            int waveStart,
            int waveLength,
            PropagationConfig confinedConfig,
            CognitiveContext context,
            InputExecutionLog[] waveLogs,
            Throwable[] waveFailures,
            List<AeonInputResult> inputResults) {
        for (int w = 0; w < waveLength; w++) {
            int inputIndex = waveStart + w;
            var input = stableInputs.get(inputIndex);
            boolean stepBudgetExhaustedBefore = context.stepBudgetExhausted();
            boolean signalBudgetExhaustedBefore = context.signalBudgetExhausted();
            context.recordAeonInputStarted(aeon.getId(), input.startNodeId());

            if (waveFailures[w] != null) {
                if (context.hasRemainingStepCapacity()) {
                    context.tryRecordInputSignal(input.startNodeId(), input.signal());
                }
                rethrowDeterministicFailure(w, waveFailures);
            }

            var log = waveLogs[w];
            waveLogs[w] = null;

            if (!context.hasRemainingStepCapacity()) {
                var emptyResult = new PropagationResult(List.of(), 0, false, false);
                var inputResult = new AeonInputResult(input, emptyResult);
                inputResults.add(inputResult);
                context.recordAeonInputCompleted(
                        aeon.getId(),
                        input.startNodeId(),
                        0,
                        false,
                        false,
                        true);
                continue;
            }

            OptionalLong initialSequence = context.tryRecordInputSignal(input.startNodeId(), input.signal());
            if (initialSequence.isEmpty()) {
                var emptyResult = new PropagationResult(List.of(), 0, false, false);
                var inputResult = new AeonInputResult(input, emptyResult);
                inputResults.add(inputResult);
                context.recordAeonInputCompleted(
                        aeon.getId(),
                        input.startNodeId(),
                        0,
                        false,
                        false,
                        true);
                continue;
            }

            long[] seqMap = new long[log.totalSignalSequences()];
            seqMap[0] = initialSequence.getAsLong();

            var admittedSignals = new ArrayList<Signal>();
            int admittedSteps = 0;
            boolean stepBudgetReachedDuring = false;
            boolean signalBudgetReachedDuring = false;

            for (var step : log.steps()) {
                if (!context.hasRemainingStepCapacity()) {
                    stepBudgetReachedDuring = true;
                    break;
                }

                context.recordCompletedStep(
                        step.nodeId(),
                        seqMap[(int) step.inputSequence()],
                        step.emissions().size());
                admittedSteps++;

                for (var emission : step.emissions()) {
                    OptionalLong emittedSeq = context.tryRecordEmittedSignal(step.nodeId(), emission.signal());
                    if (emittedSeq.isEmpty()) {
                        signalBudgetReachedDuring = true;
                        break;
                    }
                    seqMap[(int) emission.emissionSequence()] = emittedSeq.getAsLong();
                    admittedSignals.add(emission.signal());

                    for (var delivery : emission.deliveries()) {
                        if (!context.hasRemainingStepCapacity()) {
                            stepBudgetReachedDuring = true;
                            break;
                        }
                        OptionalLong deliveredSeq = context.tryRecordDeliveredSignal(
                                step.nodeId(),
                                delivery.targetNodeId(),
                                delivery.signal());
                        if (deliveredSeq.isEmpty()) {
                            signalBudgetReachedDuring = true;
                            break;
                        }
                        seqMap[(int) delivery.deliverySequence()] = deliveredSeq.getAsLong();
                        context.recordAcceptedRoute(
                                step.nodeId(),
                                delivery.targetNodeId(),
                                emittedSeq.getAsLong(),
                                deliveredSeq.getAsLong());
                    }

                    if (signalBudgetReachedDuring || stepBudgetReachedDuring) {
                        break;
                    }
                }

                if (signalBudgetReachedDuring || stepBudgetReachedDuring) {
                    break;
                }
            }

            if (log.attemptedExhaustedSignal() != null && !signalBudgetReachedDuring) {
                context.tryRecordEmittedSignal(log.startNodeId(), log.attemptedExhaustedSignal());
            }

            PropagationResult effectiveResult;
            if (admittedSteps == log.propagationResult().processedSteps()
                    && !stepBudgetReachedDuring
                    && !signalBudgetReachedDuring) {
                effectiveResult = log.propagationResult();
            } else {
                effectiveResult = new PropagationResult(
                        admittedSignals,
                        admittedSteps,
                        log.hasPendingWork() && admittedSteps == confinedConfig.maxSteps(),
                        log.propagationResult().hopLimitReached());
            }

            var inputResult = new AeonInputResult(input, effectiveResult);
            inputResults.add(inputResult);
            context.recordAeonResult(aeon.getId(), inputResult);

            boolean contextLimitReached = (!stepBudgetExhaustedBefore
                    && context.stepBudgetExhausted())
                    || (!signalBudgetExhaustedBefore && context.signalBudgetExhausted())
                    || (effectiveResult.processedSteps() == 0
                    && (context.stepBudgetExhausted() || context.signalBudgetExhausted()));

            context.recordAeonInputCompleted(
                    aeon.getId(),
                    input.startNodeId(),
                    effectiveResult.processedSteps(),
                    effectiveResult.stepLimitReached(),
                    effectiveResult.hopLimitReached(),
                    contextLimitReached);
        }
    }

    private InputExecutionLog recordExecution(
            Node startNode,
            Signal inputSignal,
            NodeProcessor processor,
            PropagationConfig config,
            int maxBudgetSteps,
            int maxBudgetSignals) {
        var pending = new ArrayDeque<RecordedWork>();
        var orderedAdjacency = new HashMap<Node, List<Node>>();
        var emittedSignals = new ArrayList<Signal>();
        var stepLogs = new ArrayList<StepLog>();

        long nextSignalSequence = 0;
        long initialSequence = nextSignalSequence++;
        pending.addLast(new RecordedWork(startNode, inputSignal, 0, initialSequence));

        int processedSteps = 0;
        boolean hopLimitReached = false;
        int maxStepsLimit = Math.min(config.maxSteps(), maxBudgetSteps);
        Signal attemptedExhaustedSignal = null;

        while (!pending.isEmpty() && processedSteps < maxStepsLimit) {
            if (processedSteps >= maxBudgetSteps) {
                break;
            }

            var work = pending.removeFirst();
            NodeProcessingResult processingResult = Objects.requireNonNull(
                processor.process(work.node(), work.signal()),
                "processor result must not be null");
            processedSteps++;

            var outputs = processingResult.emittedSignals();
            var emissionLogs = new ArrayList<EmissionLog>(outputs.size());

            if (!outputs.isEmpty()) {
                boolean canEnqueueMoreWork = true;
                boolean signalBudgetReached = false;
                var targets = orderedConnections(work.node(), orderedAdjacency);

                for (var output : outputs) {
                    if (nextSignalSequence >= maxBudgetSignals) {
                        signalBudgetReached = true;
                        attemptedExhaustedSignal = output;
                        break;
                    }
                    long emittedSequence = nextSignalSequence++;
                    emittedSignals.add(output);
                    var deliveryLogs = new ArrayList<DeliveryLog>();

                    if (canEnqueueMoreWork) {
                        for (var target : targets) {
                            if (!config.routingPolicy().shouldRoute(work.node(), target, output)) {
                                continue;
                            }
                            if (work.hop() == config.maxHops()) {
                                hopLimitReached = true;
                                continue;
                            }
                            if (processedSteps >= maxBudgetSteps) {
                                canEnqueueMoreWork = false;
                                break;
                            }
                            if (nextSignalSequence >= maxBudgetSignals) {
                                signalBudgetReached = true;
                                attemptedExhaustedSignal = output;
                                break;
                            }
                            long deliveredSequence = nextSignalSequence++;
                            deliveryLogs.add(new DeliveryLog(deliveredSequence, target.getId(), output));
                            pending.addLast(new RecordedWork(target, output, work.hop() + 1, deliveredSequence));
                        }
                    }
                    emissionLogs.add(new EmissionLog(emittedSequence, output, deliveryLogs));
                    if (signalBudgetReached) {
                        break;
                    }
                }
            }

            stepLogs.add(new StepLog(work.node().getId(), work.inputSignalSequence(), emissionLogs));
        }

        var propagationResult = new PropagationResult(
                emittedSignals,
                processedSteps,
                !pending.isEmpty() && processedSteps == config.maxSteps(),
                hopLimitReached);

        return new InputExecutionLog(
                startNode.getId(),
                inputSignal,
                stepLogs,
                propagationResult,
                (int) nextSignalSequence,
                !pending.isEmpty(),
                attemptedExhaustedSignal);
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

    private boolean isParallelEligible(
            Aeon aeon,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config) {
        return inputs.size() >= parallelismThreshold
                && maxParallelism > 1
                && eligibility.isEligible(aeon, inputs, processor, config);
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

    private void checkAndPropagateFailures(Throwable[] failures) {
        for (int i = 0; i < failures.length; i++) {
            if (failures[i] != null) {
                rethrowDeterministicFailure(i, failures);
            }
        }
    }

    private void rethrowDeterministicFailure(int primaryIndex, Throwable[] failures) {
        Throwable primary = failures[primaryIndex];
        for (int j = primaryIndex + 1; j < failures.length; j++) {
            if (failures[j] != null && failures[j] != primary) {
                primary.addSuppressed(failures[j]);
            }
        }
        if (primary instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (primary instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(primary);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            var thread = new Thread(runnable, "bounded-parallel-aeon-worker");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static int validateParallelism(int parallelism) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("maxParallelism must be positive, got: " + parallelism);
        }
        return parallelism;
    }

    private static int validateThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("parallelismThreshold must be positive, got: " + threshold);
        }
        return threshold;
    }

    @Override
    public void close() {
        if (ownsExecutor && executor instanceof ExecutorService executorService) {
            executorService.shutdown();
        }
    }

    private record RecordedWork(Node node, Signal signal, int hop, long inputSignalSequence) {}

    private record StepLog(UUID nodeId, long inputSequence, List<EmissionLog> emissions) {}

    private record EmissionLog(long emissionSequence, Signal signal, List<DeliveryLog> deliveries) {}

    private record DeliveryLog(long deliverySequence, UUID targetNodeId, Signal signal) {}

    private record InputExecutionLog(
            UUID startNodeId,
            Signal inputSignal,
            List<StepLog> steps,
            PropagationResult propagationResult,
            int totalSignalSequences,
            boolean hasPendingWork,
            Signal attemptedExhaustedSignal) {}
}
