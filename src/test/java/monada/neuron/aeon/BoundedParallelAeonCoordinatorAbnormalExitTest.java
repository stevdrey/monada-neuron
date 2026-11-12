package monada.neuron.aeon;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveCycleSnapshot;
import monada.neuron.context.CognitiveTraceEntry;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abnormal-exit contract of {@link BoundedParallelAeonCoordinator} for exits that are not input failures (issue #208,
 * ADR 0016 decision 7): every task already submitted is done or cancelled before the exception leaves
 * {@code coordinate}, and the caller's interrupt status is preserved.
 *
 * <p>The recording executors keep the submitted tasks instead of running them, so each scenario is deterministic: a
 * task that the coordinator abandoned without cancelling would still be runnable when the test inspects it.
 */
class BoundedParallelAeonCoordinatorAbnormalExitTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);

    private final DeterministicSignalPropagationEngine engine = new DeterministicSignalPropagationEngine();
    private final AtomicInteger processedInputs = new AtomicInteger();
    private final NodeProcessor countingProcessor = (node, input) -> {
        processedInputs.incrementAndGet();
        return NodeProcessingResult.noOutput();
    };

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void directPathCancelsSubmittedTasksWhenTheCallerIsInterruptedWhileWaiting() {
        var executor = new RecordingExecutor(Integer.MAX_VALUE);
        try (var coordinator = coordinator(executor, ContextualParallelism.SEQUENTIAL_ORACLE)) {
            var root = node(1);

            Thread.currentThread().interrupt();
            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), countingProcessor, config()));

            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted(), "caller interrupt status is preserved"),
                    () -> assertInstanceOf(InterruptedException.class, failure.getCause()),
                    () -> assertEquals(2, executor.submitted().size(), "the window was full when the caller waited"),
                    () -> assertEveryTaskCancelled(executor.submitted()));

            Thread.interrupted();
            executor.runAll();
            assertEquals(0, processedInputs.get(), "no abandoned task may still process an input");
        }
    }

    @Test
    void directPathCancelsSubmittedTasksWhenTheExecutorRejectsARefill() {
        // Runs the first submission inline, records the second, and rejects the third (the refill of input 2).
        var executor = new RecordingExecutor(2, 1);
        try (var coordinator = coordinator(executor, ContextualParallelism.SEQUENTIAL_ORACLE)) {
            var root = node(1);

            assertThrows(
                    RejectedExecutionException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), countingProcessor, config()));

            assertAll(
                    () -> assertFalse(Thread.currentThread().isInterrupted()),
                    () -> assertEquals(3, executor.submitted().size()),
                    () -> assertFalse(executor.submitted().getFirst().isCancelled(), "input 0 completed normally"),
                    () -> assertEveryTaskDone(executor.submitted()),
                    () -> assertTrue(executor.submitted().get(1).isCancelled(), "in-flight input 1 is cancelled"),
                    () -> assertTrue(executor.submitted().get(2).isCancelled(), "rejected input 2 can never run"));

            executor.runAll();
            assertEquals(1, processedInputs.get(), "only the inline input was processed");
        }
    }

    @Test
    void directPathCancelsEarlierTasksWhenTheExecutorRejectsTheInitialWindow() {
        var executor = new RecordingExecutor(1);
        try (var coordinator = coordinator(executor, ContextualParallelism.SEQUENTIAL_ORACLE)) {
            var root = node(1);

            assertThrows(
                    RejectedExecutionException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), countingProcessor, config()));

            assertAll(
                    () -> assertEquals(2, executor.submitted().size()),
                    () -> assertEveryTaskCancelled(executor.submitted()));

            executor.runAll();
            assertEquals(0, processedInputs.get());
        }
    }

    @Test
    void directPathInterruptsARunningCooperativeTaskWhenTheCallerIsInterrupted() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        var taskStarted = new CountDownLatch(1);
        var taskInterrupted = new CountDownLatch(1);
        var thrown = new AtomicReference<Throwable>();
        var interruptedAfterExit = new AtomicBoolean();
        try (var coordinator = coordinator(workers, ContextualParallelism.SEQUENTIAL_ORACLE)) {
            var root = node(1);
            NodeProcessor blockingProcessor = (node, input) -> {
                taskStarted.countDown();
                try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                    throw new AssertionError("running task was not interrupted");
                } catch (InterruptedException interrupted) {
                    taskInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("task interrupted", interrupted);
                }
            };
            var caller = Thread.ofPlatform().start(() -> {
                try {
                    coordinator.coordinate(aeonWith(root), inputs(root, 2), blockingProcessor, config());
                } catch (Throwable failure) {
                    thrown.set(failure);
                    interruptedAfterExit.set(Thread.currentThread().isInterrupted());
                }
            });

            assertTrue(taskStarted.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));

            assertAll(
                    () -> assertFalse(caller.isAlive(), "coordinate returned after the interrupt"),
                    () -> assertInstanceOf(IllegalStateException.class, thrown.get()),
                    () -> assertTrue(interruptedAfterExit.get(), "caller interrupt status is preserved"),
                    () -> assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS), "running task was interrupted"));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void contextualPathCancelsTheWaveWhenTheCallerIsInterruptedWhileWaiting() {
        var executor = new RecordingExecutor(Integer.MAX_VALUE);
        try (var coordinator = coordinator(executor, ContextualParallelism.EXPERIMENTAL_PARALLEL)) {
            var root = node(1);
            var context = new CognitiveContext(BUDGET);

            Thread.currentThread().interrupt();
            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), countingProcessor, config(), context));

            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted(), "caller interrupt status is preserved"),
                    () -> assertInstanceOf(InterruptedException.class, failure.getCause()),
                    () -> assertEquals(2, executor.submitted().size(), "one wave of maxParallelism tasks"),
                    () -> assertEveryTaskCancelled(executor.submitted()));

            Thread.interrupted();
            executor.runAll();
            assertEquals(0, processedInputs.get());

            // ADR 0016 decision 7: the context keeps the prefix recorded up to the interrupted input, as the
            // sequential oracle does when an input throws, and stays active for its owner to complete as a failure.
            assertTrue(context.isActive());
            var snapshot = context.complete(CognitiveCycleOutcome.FAILURE);
            var events = snapshot.traceEntries().stream().map(CognitiveTraceEntry::event).toList();
            assertAll(
                    () -> assertEquals(CognitiveCycleOutcome.FAILURE, snapshot.outcome()),
                    () -> assertEquals(1, events.stream()
                            .filter(CognitiveTraceEvent.AeonInputStarted.class::isInstance).count()),
                    () -> assertTrue(events.stream()
                            .noneMatch(CognitiveTraceEvent.AeonInputCompleted.class::isInstance)),
                    () -> assertEquals(0, snapshot.aeonResults().size()));
        }
    }

    @Test
    void contextualPathCancelsTheWaveWhenReconciliationFails() {
        // Input 0 runs inline and discards the context; reconciling it then fails while input 1 is still pending.
        var executor = new RecordingExecutor(Integer.MAX_VALUE, 1);
        var context = new CognitiveContext(BUDGET);
        NodeProcessor discardingFirst = (node, input) -> {
            processedInputs.incrementAndGet();
            if (input.frequencyState().amplitude() == 1.0) {
                context.close();
            }
            return NodeProcessingResult.noOutput();
        };
        try (var coordinator = coordinator(executor, ContextualParallelism.EXPERIMENTAL_PARALLEL)) {
            var root = node(1);

            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), discardingFirst, config(), context));

            assertAll(
                    () -> assertFalse(context.isActive()),
                    () -> assertTrue(failure.getMessage().contains("active"), failure.getMessage()),
                    () -> assertEquals(2, executor.submitted().size()),
                    () -> assertFalse(executor.submitted().getFirst().isCancelled(), "input 0 completed normally"),
                    () -> assertTrue(executor.submitted().get(1).isCancelled(), "pending input 1 is cancelled"));

            executor.runAll();
            assertEquals(1, processedInputs.get(), "only the inline input was processed");
        }
    }

    @Test
    void contextualPathCancelsTheWaveWhenTheExecutorRejectsASubmission() {
        var executor = new RecordingExecutor(1);
        try (var coordinator = coordinator(executor, ContextualParallelism.EXPERIMENTAL_PARALLEL)) {
            var root = node(1);
            var context = new CognitiveContext(BUDGET);

            assertThrows(
                    RejectedExecutionException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), countingProcessor, config(), context));

            assertAll(
                    () -> assertEquals(2, executor.submitted().size()),
                    () -> assertEveryTaskCancelled(executor.submitted()));

            executor.runAll();
            assertEquals(0, processedInputs.get());

            // ADR 0016 decision 7, exit before the wave's reconciliation: no input of the wave was started.
            assertTrue(context.isActive());
            var events = traceEvents(context.complete(CognitiveCycleOutcome.FAILURE));
            assertAll(
                    () -> assertEquals(0, count(events, CognitiveTraceEvent.AeonInputStarted.class)),
                    () -> assertEquals(0, count(events, CognitiveTraceEvent.AeonInputCompleted.class)));
        }
    }

    @Test
    void contextualPathKeepsEarlierWavesWhenALaterWaveSubmissionIsRejected() {
        // Wave 1 (inputs 0 and 1) runs inline and is reconciled; the first submission of wave 2 is rejected.
        var executor = new RecordingExecutor(2, 2);
        try (var coordinator = coordinator(executor, ContextualParallelism.EXPERIMENTAL_PARALLEL)) {
            var root = node(1);
            var context = new CognitiveContext(BUDGET);

            assertThrows(
                    RejectedExecutionException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), countingProcessor, config(), context));

            assertAll(
                    () -> assertEquals(3, executor.submitted().size()),
                    () -> assertTrue(executor.submitted().get(2).isCancelled(), "rejected input 2 can never run"),
                    () -> assertTrue(context.isActive()));

            var events = traceEvents(context.complete(CognitiveCycleOutcome.FAILURE));
            assertAll(
                    () -> assertEquals(2, count(events, CognitiveTraceEvent.AeonInputStarted.class)),
                    () -> assertEquals(2, count(events, CognitiveTraceEvent.AeonInputCompleted.class)),
                    () -> assertEquals(2, processedInputs.get()));
        }
    }

    @Test
    void directPathCancelsTheStalledInputWhenLaterInputsCompletedFirst() {
        // ADR 0016 decision 7 cost note: input 0 stalls while inputs 1..5 complete in order, so every completion
        // refills the window but the first unreconciled index stays 0. The last inline input then interrupts the
        // caller, whose next wait fails. The scan covers all six tasks; only the stalled one is still in flight.
        int inputCount = 6;
        var executor = new RecordingExecutor(Integer.MAX_VALUE, submission -> submission > 1);
        NodeProcessor interruptingLast = (node, input) -> {
            processedInputs.incrementAndGet();
            if (input.frequencyState().amplitude() == inputCount) {
                Thread.currentThread().interrupt();
            }
            return NodeProcessingResult.noOutput();
        };
        try (var coordinator = coordinator(executor, ContextualParallelism.SEQUENTIAL_ORACLE)) {
            var root = node(1);

            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, inputCount), interruptingLast, config()));

            var tasks = executor.submitted();
            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted(), "caller interrupt status is preserved"),
                    () -> assertInstanceOf(InterruptedException.class, failure.getCause()),
                    () -> assertEquals(inputCount, tasks.size(), "every refill happened before the interrupt"),
                    () -> assertTrue(tasks.getFirst().isCancelled(), "stalled input 0 is cancelled"),
                    () -> assertEveryTaskDone(tasks),
                    () -> assertTrue(tasks.subList(1, inputCount).stream().noneMatch(Future::isCancelled),
                            "completed inputs keep their results"));

            Thread.interrupted();
            executor.runAll();
            assertEquals(inputCount - 1, processedInputs.get(), "the stalled input never ran");
        }
    }

    @Test
    void inputFailureStillRethrowsTheSameInstanceAndCancelsSiblings() {
        // Input 0 runs inline and fails; input 1 is recorded. Decision 5 is unchanged by the abnormal-exit handler.
        var executor = new RecordingExecutor(Integer.MAX_VALUE, 1);
        var inputFailure = new IllegalStateException("input 0 failed");
        NodeProcessor failingFirst = (node, input) -> {
            if (input.frequencyState().amplitude() == 1.0) {
                throw inputFailure;
            }
            processedInputs.incrementAndGet();
            return NodeProcessingResult.noOutput();
        };
        try (var coordinator = coordinator(executor, ContextualParallelism.SEQUENTIAL_ORACLE)) {
            var root = node(1);

            var actual = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.coordinate(aeonWith(root), inputs(root, 3), failingFirst, config()));

            assertAll(
                    () -> assertSame(inputFailure, actual),
                    () -> assertEquals(0, actual.getSuppressed().length),
                    () -> assertFalse(Thread.currentThread().isInterrupted()),
                    () -> assertEquals(2, executor.submitted().size()),
                    () -> assertTrue(executor.submitted().get(1).isCancelled()));

            executor.runAll();
            assertEquals(0, processedInputs.get());
        }
    }

    private BoundedParallelAeonCoordinator coordinator(Executor executor, ContextualParallelism contextualParallelism) {
        return new BoundedParallelAeonCoordinator(
                engine,
                executor,
                2,
                1,
                AeonParallelEligibility.INDEPENDENT_READ_ONLY,
                contextualParallelism);
    }

    private static void assertEveryTaskCancelled(List<Future<?>> tasks) {
        for (int i = 0; i < tasks.size(); i++) {
            assertTrue(tasks.get(i).isCancelled(), "task " + i + " must be cancelled");
        }
    }

    private static List<CognitiveTraceEvent> traceEvents(CognitiveCycleSnapshot snapshot) {
        return snapshot.traceEntries().stream().map(CognitiveTraceEntry::event).toList();
    }

    private static long count(List<CognitiveTraceEvent> events, Class<? extends CognitiveTraceEvent> type) {
        return events.stream().filter(type::isInstance).count();
    }

    private static void assertEveryTaskDone(List<Future<?>> tasks) {
        for (int i = 0; i < tasks.size(); i++) {
            assertTrue(tasks.get(i).isDone(), "task " + i + " must be done or cancelled");
        }
    }

    private List<AeonInput> inputs(Node root, int count) {
        var inputs = new ArrayList<AeonInput>(count);
        for (int i = 1; i <= count; i++) {
            inputs.add(new AeonInput(root.getId(), signal(i)));
        }
        return inputs;
    }

    private PropagationConfig config() {
        return PropagationConfig.routeAll(1, 0);
    }

    private Aeon aeonWith(Node... nodes) {
        var aeon = new Aeon(uuid(100), AeonPurpose.REASONING);
        for (var node : nodes) {
            aeon.addMember(node);
        }
        return aeon;
    }

    private Node node(long id) {
        return new Node.Builder().id(uuid(id)).type(NodeType.PROCESSOR).build();
    }

    private Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }

    /**
     * Records every submitted {@link Future} without running it, except for the submissions (numbered from 1) that
     * {@code runsInline} selects, which run on the calling thread. Submission number {@code acceptedSubmissions + 1}
     * and later are recorded and then rejected, as a saturated or shut-down executor would.
     */
    private static final class RecordingExecutor implements Executor {

        private final int acceptedSubmissions;
        private final IntPredicate runsInline;
        private final List<Future<?>> submitted = new ArrayList<>();

        RecordingExecutor(int acceptedSubmissions) {
            this(acceptedSubmissions, 0);
        }

        /** Runs the first {@code inlineSubmissions} submissions inline. */
        RecordingExecutor(int acceptedSubmissions, int inlineSubmissions) {
            this(acceptedSubmissions, submission -> submission <= inlineSubmissions);
        }

        RecordingExecutor(int acceptedSubmissions, IntPredicate runsInline) {
            this.acceptedSubmissions = acceptedSubmissions;
            this.runsInline = runsInline;
        }

        @Override
        public void execute(Runnable command) {
            var future = assertInstanceOf(Future.class, command);
            assertNotNull(future);
            submitted.add(future);
            if (submitted.size() > acceptedSubmissions) {
                throw new RejectedExecutionException("rejected submission " + submitted.size());
            }
            if (runsInline.test(submitted.size())) {
                command.run();
            }
        }

        List<Future<?>> submitted() {
            return submitted;
        }

        void runAll() {
            for (var task : submitted) {
                ((Runnable) task).run();
            }
        }
    }
}
