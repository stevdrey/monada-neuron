package monada.neuron.aeon.formal;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonInput;
import monada.neuron.aeon.AeonParallelEligibility;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.BoundedParallelAeonCoordinator;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.runtime.graph.SignalPropagationEngine;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;

/**
 * Runs one schedule of the real {@link BoundedParallelAeonCoordinator} direct path.
 *
 * <p>The coordinator runs unmodified on its own thread. This class is its {@link Executor}: it keeps
 * every submitted task instead of running it, and decides when each one runs. A task runs to
 * completion in one step, so the schedule is the order of task completions relative to the
 * coordinator's own steps. There are two kinds of choice point:
 *
 * <ul>
 *   <li>inside {@code execute}, on the coordinator thread: either return to the coordinator, or first
 *       complete one pending task, which models a worker finishing while the coordinator is between
 *       two submissions or between a reconciliation and its next wait;</li>
 *   <li>while the coordinator waits in {@code completedTaskIndexes.take()} with an empty queue: complete
 *       one pending task on the scheduler thread, then wait until the coordinator waits again or ends.</li>
 * </ul>
 *
 * <p>The scheduler detects that the coordinator waits by reading its completion queue, which the
 * coordinator does not expose: the queue is found reflectively in the submitted task (see
 * {@link #completionQueueOf}). This is a test-only observation; the coordinator is not changed.
 */
final class ScheduledCoordinatorRun implements Executor {

    /** How long the scheduler waits for the coordinator to wait again or end before reporting lost control. */
    static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(10);

    private static final NodeProcessor UNUSED_PROCESSOR = (node, input) -> NodeProcessingResult.noOutput();
    private static final PropagationConfig CONFIG = PropagationConfig.routeAll(1, 0);
    private static final int PARALLELISM_THRESHOLD = 1;

    private final ScheduleWorkload workload;
    private final ChoicePath path;
    private final Fixture fixture;
    private final ThreadLocal<Integer> runningTask = new ThreadLocal<>();

    // Guarded by this.
    private final List<FutureTask<?>> submitted = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<Integer> completionOrder = new ArrayList<>();
    private final List<String> submissionViolations = new ArrayList<>();
    private BlockingQueue<?> completionQueue;

    private volatile ScheduleRun.Outcome outcome;

    private ScheduledCoordinatorRun(ScheduleWorkload workload, ChoicePath path) {
        this.workload = workload;
        this.path = path;
        this.fixture = new Fixture(workload);
    }

    /** Runs the schedule {@code path} selects on {@code workload} and returns what it observed. */
    static ScheduleRun run(ScheduleWorkload workload, ChoicePath path) {
        return new ScheduledCoordinatorRun(workload, path).execute();
    }

    private ScheduleRun execute() {
        var oracle = oracleOutcome();
        var coordinator = new BoundedParallelAeonCoordinator(
                fixture.engine(this::checkPropagatedInput),
                this,
                workload.maxParallelism(),
                PARALLELISM_THRESHOLD,
                AeonParallelEligibility.INDEPENDENT_READ_ONLY);
        var coordinatorThread = Thread.ofPlatform()
                .name("formal-pilot-coordinator")
                .daemon(true)
                .unstarted(() -> {
                    try {
                        outcome = new ScheduleRun.Outcome.Returned(
                                coordinator.coordinate(fixture.aeon(), fixture.inputs(), UNUSED_PROCESSOR, CONFIG));
                    } catch (Throwable failure) {
                        outcome = new ScheduleRun.Outcome.Threw(failure);
                    }
                });
        coordinatorThread.start();

        boolean stuck = false;
        boolean controlLost = false;
        while (true) {
            var control = awaitControl(coordinatorThread);
            if (control == Control.ENDED) {
                break;
            }
            if (control == Control.LOST) {
                controlLost = true;
                record("the coordinator neither waited nor ended within " + CONTROL_TIMEOUT.toSeconds() + " s");
                stop(coordinatorThread);
                break;
            }
            var pending = pendingTasks();
            if (pending.isEmpty()) {
                stuck = true;
                record("the coordinator waits in completedTaskIndexes.take() but no submitted task can run");
                stop(coordinatorThread);
                break;
            }
            int task = pending.get(path.choose(pending.size()));
            complete(task, "while the coordinator waits in completedTaskIndexes.take()");
        }

        var runnableAfterEnd = new ArrayList<Integer>();
        synchronized (this) {
            for (int task = 0; task < submitted.size(); task++) {
                var future = submitted.get(task);
                if (future.isCancelled()) {
                    events.add("task " + task + " is cancelled before it runs");
                } else if (!future.isDone()) {
                    runnableAfterEnd.add(task);
                    events.add("task " + task + " is still runnable after coordinate ended");
                }
            }
            return new ScheduleRun(
                    workload,
                    path.choices(),
                    events,
                    completionOrder,
                    submissionViolations,
                    stuck,
                    controlLost,
                    runnableAfterEnd,
                    outcome,
                    oracle);
        }
    }

    /** Called by the coordinator thread from {@code submitDirectTasks}. */
    @Override
    public void execute(Runnable command) {
        if (!(command instanceof FutureTask<?> task)) {
            throw new IllegalStateException("harness expects the coordinator to submit FutureTask instances, got: "
                    + command.getClass().getName());
        }
        int index;
        synchronized (this) {
            index = submitted.size();
            submitted.add(task);
            if (completionQueue == null) {
                completionQueue = completionQueueOf(task);
            }
            events.add("execute(task " + index + ")");
            if (index >= workload.inputCount()) {
                submissionViolations.add("task " + index + " submitted, but there are only "
                        + workload.inputCount() + " inputs");
            }
            int inFlight = 0;
            for (var future : submitted) {
                if (!future.isDone()) {
                    inFlight++;
                }
            }
            if (inFlight > workload.maxParallelism()) {
                submissionViolations.add(inFlight + " tasks in flight after execute(task " + index
                        + "), above maxParallelism " + workload.maxParallelism());
            }
        }
        while (true) {
            var pending = pendingTasks();
            int choice = path.choose(pending.size() + 1);
            if (choice == 0) {
                return;
            }
            complete(pending.get(choice - 1), "before execute(task " + index + ") returns to the coordinator");
        }
    }

    private void complete(int task, String when) {
        FutureTask<?> future;
        synchronized (this) {
            future = submitted.get(task);
            completionOrder.add(task);
            events.add("task " + task + " completes, propagate "
                    + (workload.fails(task) ? "throws InputFailure(" + task + ")" : "returns")
                    + ", " + when);
        }
        runningTask.set(task);
        try {
            future.run();
        } finally {
            runningTask.remove();
        }
    }

    /** Checks, inside the running task, that the task submitted as number {@code n} propagates input {@code n}. */
    private void checkPropagatedInput(int inputIndex) {
        Integer task = runningTask.get();
        if (task == null || task != inputIndex) {
            synchronized (this) {
                submissionViolations.add("task " + task + " propagated input " + inputIndex);
            }
        }
    }

    private synchronized List<Integer> pendingTasks() {
        var pending = new ArrayList<Integer>();
        for (int task = 0; task < submitted.size(); task++) {
            if (!submitted.get(task).isDone()) {
                pending.add(task);
            }
        }
        return pending;
    }

    private synchronized void record(String event) {
        events.add(event);
    }

    private Control awaitControl(Thread coordinatorThread) {
        long deadline = System.nanoTime() + CONTROL_TIMEOUT.toNanos();
        while (true) {
            var state = coordinatorThread.getState();
            if (state == Thread.State.TERMINATED) {
                joinQuietly(coordinatorThread);
                return Control.ENDED;
            }
            if (state == Thread.State.WAITING) {
                BlockingQueue<?> queue;
                synchronized (this) {
                    queue = completionQueue;
                }
                // The coordinator takes from the queue only while running, so an empty queue followed by
                // WAITING means it consumed every completion and parked again in take().
                if (queue != null && queue.isEmpty() && coordinatorThread.getState() == Thread.State.WAITING) {
                    return Control.WAITING;
                }
            }
            if (System.nanoTime() > deadline) {
                return Control.LOST;
            }
            Thread.yield();
        }
    }

    private void stop(Thread coordinatorThread) {
        coordinatorThread.interrupt();
        joinQuietly(coordinatorThread);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(CONTROL_TIMEOUT.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while joining the coordinator thread", interrupted);
        }
    }

    private ScheduleRun.Outcome oracleOutcome() {
        var oracleFixture = new Fixture(workload);
        var oracle = new DeterministicAeonCoordinator(oracleFixture.engine(inputIndex -> { }));
        try {
            return new ScheduleRun.Outcome.Returned(
                    oracle.coordinate(oracleFixture.aeon(), oracleFixture.inputs(), UNUSED_PROCESSOR, CONFIG));
        } catch (RuntimeException failure) {
            return new ScheduleRun.Outcome.Threw(failure);
        }
    }

    /**
     * Returns the completion queue that the submitted task's {@code done()} feeds. The coordinator
     * creates each direct task as an anonymous {@link FutureTask} that captures this queue.
     */
    private static BlockingQueue<?> completionQueueOf(FutureTask<?> task) {
        for (Class<?> type = task.getClass(); type != FutureTask.class && type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (BlockingQueue.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return (BlockingQueue<?>) field.get(task);
                    } catch (ReflectiveOperationException | RuntimeException unavailable) {
                        throw new IllegalStateException("cannot read the coordinator's completion queue", unavailable);
                    }
                }
            }
        }
        throw new IllegalStateException("the submitted task captures no completion queue; the harness no longer "
                + "matches submitDirectTasks in BoundedParallelAeonCoordinator");
    }

    private enum Control {
        WAITING,
        ENDED,
        LOST
    }

    /** The Aeon, inputs, and scripted propagation for one workload. Input {@code i} starts at member {@code i}. */
    private static final class Fixture {

        private final ScheduleWorkload workload;
        private final Aeon aeon;
        private final List<Node> members;
        private final List<AeonInput> inputs;
        private final InputFailure[] failures;

        Fixture(ScheduleWorkload workload) {
            this.workload = workload;
            this.aeon = new Aeon(new UUID(0L, 1_000L), AeonPurpose.REASONING);
            var nodes = new ArrayList<Node>(workload.inputCount());
            var declared = new ArrayList<AeonInput>(workload.inputCount());
            this.failures = new InputFailure[workload.inputCount()];
            for (int i = 0; i < workload.inputCount(); i++) {
                var node = new Node.Builder().id(new UUID(0L, i + 1L)).type(NodeType.PROCESSOR).build();
                aeon.addMember(node);
                nodes.add(node);
                declared.add(new AeonInput(node.getId(), new Signal(
                        SignalKind.INTERMEDIATE,
                        new FrequencyState(i + 1.0, 10.0, 0.0))));
                if (workload.fails(i)) {
                    failures[i] = new InputFailure(i);
                }
            }
            this.members = List.copyOf(nodes);
            this.inputs = List.copyOf(declared);
        }

        Aeon aeon() {
            return aeon;
        }

        List<AeonInput> inputs() {
            return inputs;
        }

        /** A propagation that returns a result unique to the input, or throws that input's own failure. */
        SignalPropagationEngine engine(IntConsumer onPropagate) {
            return (start, signal, processor, config) -> {
                int inputIndex = members.indexOf(start);
                if (inputIndex < 0) {
                    throw new IllegalStateException("propagation started outside the fixture: " + start.getId());
                }
                onPropagate.accept(inputIndex);
                if (workload.fails(inputIndex)) {
                    throw failures[inputIndex];
                }
                return new PropagationResult(List.of(signal), inputIndex + 1, false, false);
            };
        }
    }
}
