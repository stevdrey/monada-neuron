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
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
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

    /**
     * Creates a parallel coordinator with explicit executor, bounds, and eligibility policy.
     *
     * <p>The supplied executor remains caller-owned. This coordinator submits no more than
     * {@code maxParallelism} propagation tasks at once, regardless of that executor's capacity.
     */
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
        var inputResults = new ArrayList<AeonInputResult>(inputCount);
        var tasks = this.<PropagationResult>newTaskArray(inputCount);
        int nextToSubmit = submitDirectTasks(
                tasks,
                0,
                Math.min(inputCount, maxParallelism),
                startNodes,
                stableInputs,
                processor,
                confinedConfig);

        for (int i = 0; i < inputCount; i++) {
            var outcome = awaitTask(tasks[i]);
            if (outcome.failure() != null) {
                var concurrentFailures = collectCompletedFailures(tasks, i + 1, nextToSubmit);
                cancelTasks(tasks, i + 1, nextToSubmit);
                rethrowDeterministicFailure(outcome.failure(), concurrentFailures);
            }

            inputResults.add(new AeonInputResult(stableInputs.get(i), outcome.result()));
            if (nextToSubmit < inputCount) {
                nextToSubmit = submitDirectTasks(
                        tasks,
                        nextToSubmit,
                        nextToSubmit + 1,
                        startNodes,
                        stableInputs,
                        processor,
                        confinedConfig);
            }
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
        int waveSize = maxParallelism;
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

            var waveTasks = this.<InputExecutionLog>newTaskArray(waveLength);

            for (int w = 0; w < waveLength; w++) {
                final int waveIndex = w;
                final int inputIndex = waveStart + w;
                waveTasks[waveIndex] = new FutureTask<>(() -> new TaskOutcome<>(
                        recordExecution(
                                startNodes.get(inputIndex),
                                stableInputs.get(inputIndex).signal(),
                                processor,
                                confinedConfig,
                                budgetMaxSteps,
                                budgetMaxSignals),
                        null));
                executor.execute(waveTasks[waveIndex]);
            }

            reconcileContextualWave(
                    aeon,
                    stableInputs,
                    waveStart,
                    waveLength,
                    confinedConfig,
                    context,
                    waveTasks,
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
            FutureTask<TaskOutcome<InputExecutionLog>>[] waveTasks,
            List<AeonInputResult> inputResults) {
        for (int w = 0; w < waveLength; w++) {
            int inputIndex = waveStart + w;
            var input = stableInputs.get(inputIndex);
            boolean stepBudgetExhaustedBefore = context.stepBudgetExhausted();
            boolean signalBudgetExhaustedBefore = context.signalBudgetExhausted();
            context.recordAeonInputStarted(aeon.getId(), input.startNodeId());

            if (!context.hasRemainingStepCapacity()) {
                cancelTasks(waveTasks, w, waveLength);
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
                cancelTasks(waveTasks, w, waveLength);
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

            var outcome = awaitTask(waveTasks[w]);
            if (outcome.failure() != null) {
                var concurrentFailures = collectCompletedFailures(waveTasks, w + 1, waveLength);
                cancelTasks(waveTasks, w + 1, waveLength);
                rethrowDeterministicFailure(outcome.failure(), concurrentFailures);
            }

            var log = outcome.result();

            long[] seqMap = new long[log.totalSignalSequences()];
            seqMap[0] = initialSequence.getAsLong();

            var admittedSignals = new ArrayList<Signal>();
            int admittedSteps = 0;
            boolean stepBudgetReachedDuring = false;
            boolean signalBudgetReachedDuring = false;
            boolean admittedHopLimitReached = false;

            for (var step : log.steps()) {
                if (!context.hasRemainingStepCapacity()) {
                    stepBudgetReachedDuring = true;
                    break;
                }

                context.recordCompletedStep(
                        step.nodeId(),
                        seqMap[(int) step.inputSequence()],
                        step.emittedSignalCount());
                admittedSteps++;
                admittedHopLimitReached |= step.hopLimitReached();

                boolean canEnqueueMoreWork = true;

                for (var emission : step.emissions()) {
                    OptionalLong emittedSeq = context.tryRecordEmittedSignal(step.nodeId(), emission.signal());
                    if (emittedSeq.isEmpty()) {
                        signalBudgetReachedDuring = true;
                        break;
                    }
                    seqMap[(int) emission.emissionSequence()] = emittedSeq.getAsLong();
                    admittedSignals.add(emission.signal());

                    if (canEnqueueMoreWork) {
                        for (var delivery : emission.deliveries()) {
                            if (!context.hasRemainingStepCapacity()) {
                                stepBudgetReachedDuring = true;
                                canEnqueueMoreWork = false;
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
                    }

                    if (signalBudgetReachedDuring) {
                        break;
                    }
                }

                if (signalBudgetReachedDuring) {
                    break;
                }

                if (step.deliveryStepBudgetReached()) {
                    context.hasRemainingStepCapacity();
                    stepBudgetReachedDuring = true;
                }
            }

            if (log.attemptedExhaustedSignal() != null && !signalBudgetReachedDuring) {
                context.tryRecordEmittedSignal(log.startNodeId(), log.attemptedExhaustedSignal());
            }

            if (log.failure() != null
                    && !signalBudgetReachedDuring
                    && !stepBudgetReachedDuring
                    && context.hasRemainingStepCapacity()) {
                var concurrentFailures = collectCompletedFailures(waveTasks, w + 1, waveLength);
                cancelTasks(waveTasks, w + 1, waveLength);
                rethrowDeterministicFailure(log.failure(), concurrentFailures);
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
                        admittedHopLimitReached);
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
            NodeProcessingResult processingResult;
            try {
                processingResult = Objects.requireNonNull(
                        processor.process(work.node(), work.signal()),
                        "processor result must not be null");
            } catch (Throwable failure) {
                return createExecutionLog(
                        startNode,
                        inputSignal,
                        stepLogs,
                        emittedSignals,
                        processedSteps,
                        hopLimitReached,
                        config,
                        nextSignalSequence,
                        !pending.isEmpty(),
                        attemptedExhaustedSignal,
                        failure);
            }
            processedSteps++;

            var outputs = processingResult.emittedSignals();
            var emissionLogs = new ArrayList<EmissionLog>(outputs.size());
            boolean stepHopLimitReached = false;
            boolean deliveryStepBudgetReached = false;

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
                                stepHopLimitReached = true;
                                continue;
                            }
                            if (processedSteps >= maxBudgetSteps) {
                                canEnqueueMoreWork = false;
                                deliveryStepBudgetReached = true;
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

            stepLogs.add(new StepLog(
                    work.node().getId(),
                    work.inputSignalSequence(),
                    outputs.size(),
                    stepHopLimitReached,
                    deliveryStepBudgetReached,
                    emissionLogs));
        }

        return createExecutionLog(
                startNode,
                inputSignal,
                stepLogs,
                emittedSignals,
                processedSteps,
                hopLimitReached,
                config,
                nextSignalSequence,
                !pending.isEmpty(),
                attemptedExhaustedSignal,
                null);
    }

    private InputExecutionLog createExecutionLog(
            Node startNode,
            Signal inputSignal,
            List<StepLog> stepLogs,
            List<Signal> emittedSignals,
            int processedSteps,
            boolean hopLimitReached,
            PropagationConfig config,
            long nextSignalSequence,
            boolean hasPendingWork,
            Signal attemptedExhaustedSignal,
            Throwable failure) {
        return new InputExecutionLog(
                startNode.getId(),
                inputSignal,
                stepLogs,
                new PropagationResult(
                        emittedSignals,
                        processedSteps,
                        hasPendingWork && processedSteps == config.maxSteps(),
                        hopLimitReached),
                (int) nextSignalSequence,
                hasPendingWork,
                attemptedExhaustedSignal,
                failure);
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

    private int submitDirectTasks(
            FutureTask<TaskOutcome<PropagationResult>>[] tasks,
            int startInclusive,
            int endExclusive,
            List<Node> startNodes,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config) {
        for (int i = startInclusive; i < endExclusive; i++) {
            final int inputIndex = i;
            tasks[inputIndex] = new FutureTask<>(() -> {
                try {
                    return new TaskOutcome<>(Objects.requireNonNull(
                            propagationEngine.propagate(
                                    startNodes.get(inputIndex),
                                    inputs.get(inputIndex).signal(),
                                    processor,
                                    config),
                            "propagation result must not be null"), null);
                } catch (Throwable failure) {
                    return new TaskOutcome<>(null, failure);
                }
            });
            executor.execute(tasks[inputIndex]);
        }
        return endExclusive;
    }

    private <T> TaskOutcome<T> awaitTask(FutureTask<TaskOutcome<T>> task) {
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating Aeon inputs", interrupted);
        } catch (CancellationException cancelled) {
            throw new IllegalStateException("Aeon input task was cancelled before reconciliation", cancelled);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Aeon input task execution failed", failure.getCause());
        }
    }

    private void cancelTasks(FutureTask<?>[] tasks, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            var task = tasks[i];
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    private List<Throwable> collectCompletedFailures(
            FutureTask<?>[] tasks,
            int startInclusive,
            int endExclusive) {
        var failures = new ArrayList<Throwable>();
        for (int i = startInclusive; i < endExclusive; i++) {
            var task = tasks[i];
            if (task == null || !task.isDone() || task.isCancelled()) {
                continue;
            }
            var outcome = completedOutcome(task);
            if (outcome != null && outcome.failure() != null) {
                failures.add(outcome.failure());
            }
        }
        return failures;
    }

    private TaskOutcome<?> completedOutcome(FutureTask<?> task) {
        try {
            var result = task.get();
            if (result instanceof TaskOutcome<?> outcome) {
                return outcome;
            }
            throw new IllegalStateException("Aeon input task returned an unexpected result type");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while inspecting Aeon input task failures", interrupted);
        } catch (CancellationException cancelled) {
            return null;
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Aeon input task execution failed", failure.getCause());
        }
    }

    private void rethrowDeterministicFailure(Throwable primary, List<Throwable> concurrentFailures) {
        for (var failure : concurrentFailures) {
            if (failure != primary) {
                primary.addSuppressed(failure);
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

    @SuppressWarnings("unchecked")
    private <T> FutureTask<TaskOutcome<T>>[] newTaskArray(int length) {
        return (FutureTask<TaskOutcome<T>>[]) new FutureTask<?>[length];
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

    private record StepLog(
            UUID nodeId,
            long inputSequence,
            int emittedSignalCount,
            boolean hopLimitReached,
            boolean deliveryStepBudgetReached,
            List<EmissionLog> emissions) {}

    private record EmissionLog(long emissionSequence, Signal signal, List<DeliveryLog> deliveries) {}

    private record DeliveryLog(long deliverySequence, UUID targetNodeId, Signal signal) {}

    private record InputExecutionLog(
            UUID startNodeId,
            Signal inputSignal,
            List<StepLog> steps,
            PropagationResult propagationResult,
            int totalSignalSequences,
            boolean hasPendingWork,
            Signal attemptedExhaustedSignal,
            Throwable failure) {}

    private record TaskOutcome<T>(T result, Throwable failure) {}
}
