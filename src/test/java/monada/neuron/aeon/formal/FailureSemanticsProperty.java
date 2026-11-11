package monada.neuron.aeon.formal;

import monada.neuron.aeon.formal.ScheduleRun.Outcome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

/**
 * The properties checked on every schedule, in plain sentences.
 *
 * <p>The first five state ADR 0016 decisions 1, 3, 5, and 6 for the direct path and are expected to
 * hold. The last two are deliberately false: they are stronger than the ADR and serve as negative
 * controls, so the checker must find a counterexample for each.
 */
enum FailureSemanticsProperty {

    /**
     * ADR 0016 decisions 3, 5, and 6: with no failing input, {@code coordinate} returns the oracle's results in
     * input order; with failing inputs, it throws the exception of the earliest failing input, as the oracle does.
     */
    MATCHES_SEQUENTIAL_ORACLE(true,
            "coordinate returns exactly the sequential oracle's results in input order, or throws the exception "
                    + "of the same input as the oracle (the earliest failing input)") {
        @Override
        Optional<String> violation(ScheduleRun run) {
            return switch (run.oracle()) {
                case Outcome.Returned expected -> run.outcome() instanceof Outcome.Returned actual
                        && actual.result().equals(expected.result())
                        ? Optional.empty()
                        : Optional.of("oracle " + ScheduleRun.render(expected) + ", coordinate "
                                + ScheduleRun.render(run.outcome()));
                case Outcome.Threw expected -> {
                    int expectedInput = ((InputFailure) expected.failure()).inputIndex();
                    yield run.outcome() instanceof Outcome.Threw(InputFailure actual)
                            && actual.inputIndex() == expectedInput
                            ? Optional.empty()
                            : Optional.of("oracle threw InputFailure(" + expectedInput + "), coordinate "
                                    + ScheduleRun.render(run.outcome()));
                }
                // The oracle runs synchronously, so it always ends; keep the switch exhaustive anyway.
                case Outcome.DidNotEnd _ -> Optional.of("the sequential oracle did not end");
            };
        }
    },

    /**
     * ADR 0016 decision 5: the subsequent completed concurrent failures are attached as suppressed, in input order.
     * In this model no task completes while the coordinator reconciles, so the failures that had completed when
     * {@code coordinate} threw are exactly those in the run's completion order.
     */
    SUPPRESSES_COMPLETED_LATER_FAILURES(true,
            "the suppressed exceptions are exactly the failures of later inputs whose tasks had completed when "
                    + "coordinate threw, each once, in input order") {
        @Override
        Optional<String> violation(ScheduleRun run) {
            if (!(run.outcome() instanceof Outcome.Threw(InputFailure primary))) {
                return Optional.empty();
            }
            var expected = new ArrayList<Integer>();
            for (int task : run.completionOrder()) {
                if (task > primary.inputIndex() && run.workload().fails(task)) {
                    expected.add(task);
                }
            }
            expected.sort(null);
            var actual = new ArrayList<Integer>();
            for (var suppressed : primary.getSuppressed()) {
                if (!(suppressed instanceof InputFailure later)) {
                    return Optional.of("thrown InputFailure(" + primary.inputIndex() + ") suppresses "
                            + Arrays.toString(primary.getSuppressed()));
                }
                actual.add(later.inputIndex());
            }
            return actual.equals(expected)
                    ? Optional.empty()
                    : Optional.of("thrown InputFailure(" + primary.inputIndex() + ") suppresses inputs " + actual
                            + ", but the later failures completed before it was thrown are " + expected);
        }
    },

    /** ADR 0016 decision 1: at most {@code maxParallelism} tasks are submitted and not finished at once. */
    BOUNDED_IN_ORDER_SUBMISSION(true,
            "no more tasks are submitted than there are inputs, no submission leaves more than maxParallelism "
                    + "tasks unfinished, and every task that runs propagates the input of its submission position") {
        @Override
        Optional<String> violation(ScheduleRun run) {
            return run.submissionViolations().isEmpty()
                    ? Optional.empty()
                    : Optional.of(String.join("; ", run.submissionViolations()));
        }
    },

    /** ADR 0016 decision 5: sibling tasks are cancelled; no submitted task can start after the call ends. */
    NO_RUNNABLE_TASK_AFTER_END(true,
            "when coordinate returns or throws, every submitted task has completed or been cancelled, so none "
                    + "can still start") {
        @Override
        Optional<String> violation(ScheduleRun run) {
            return run.runnableAfterEnd().isEmpty()
                    ? Optional.empty()
                    : Optional.of("tasks " + run.runnableAfterEnd() + " were neither completed nor cancelled");
        }
    },

    /** Progress under the fairness assumption that every submitted task eventually runs. */
    NO_WAIT_WITHOUT_PENDING_WORK(true,
            "the coordinator never waits for a completion while no submitted task can run, and always ends") {
        @Override
        Optional<String> violation(ScheduleRun run) {
            if (run.stuckWithoutPendingWork()) {
                return Optional.of("the coordinator waited with no runnable task");
            }
            if (run.controlLost()) {
                return Optional.of("the coordinator neither waited nor ended within the control timeout");
            }
            return Optional.empty();
        }
    },

    /** Deliberately false: a later failure that has not completed when the earliest one is reconciled is dropped. */
    EVERY_LATER_FAILURE_IS_SUPPRESSED(false,
            "(deliberately false) every failing input after the thrown one is attached as suppressed") {
        @Override
        Optional<String> violation(ScheduleRun run) {
            if (!(run.outcome() instanceof Outcome.Threw(InputFailure primary))) {
                return Optional.empty();
            }
            var suppressed = new ArrayList<Integer>();
            for (var failure : primary.getSuppressed()) {
                if (failure instanceof InputFailure inputFailure) {
                    suppressed.add(inputFailure.inputIndex());
                }
            }
            for (int input = primary.inputIndex() + 1; input < run.workload().inputCount(); input++) {
                if (run.workload().fails(input) && !suppressed.contains(input)) {
                    return Optional.of("failing input " + input + " is not suppressed by InputFailure("
                            + primary.inputIndex() + ")");
                }
            }
            return Optional.empty();
        }
    },

    /** Deliberately false: the thrown exception is chosen by input order, not by completion order. */
    FIRST_COMPLETED_FAILURE_IS_THROWN(false,
            "(deliberately false) the thrown exception is the failure that completed first") {
        @Override
        Optional<String> violation(ScheduleRun run) {
            if (!(run.outcome() instanceof Outcome.Threw(InputFailure primary))) {
                return Optional.empty();
            }
            for (int task : run.completionOrder()) {
                if (run.workload().fails(task)) {
                    return task == primary.inputIndex()
                            ? Optional.empty()
                            : Optional.of("InputFailure(" + task + ") completed first, but coordinate threw "
                                    + "InputFailure(" + primary.inputIndex() + ")");
                }
            }
            return Optional.empty();
        }
    };

    private final boolean expectedToHold;
    private final String statement;

    FailureSemanticsProperty(boolean expectedToHold, String statement) {
        this.expectedToHold = expectedToHold;
        this.statement = statement;
    }

    /** Whether the property is claimed to hold; false for the negative controls. */
    boolean expectedToHold() {
        return expectedToHold;
    }

    /** The property in one plain sentence. */
    String statement() {
        return statement;
    }

    /** Returns why {@code run} violates this property, or empty when it satisfies it. */
    abstract Optional<String> violation(ScheduleRun run);
}
