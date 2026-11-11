package monada.neuron.aeon.formal;

import monada.neuron.aeon.AeonCoordinationResult;

import java.util.Arrays;
import java.util.List;

/**
 * Everything one schedule observed on the real coordinator, plus the sequential oracle's outcome.
 *
 * @param workload the bounded workload
 * @param choices the scheduler choices that produced this run; replaying them reproduces it
 * @param events the schedule in code terms, in the order it happened
 * @param completionOrder input indexes in the order their tasks completed
 * @param submissionViolations submissions out of input order, repeated, or above the window
 * @param stuckWithoutPendingWork whether the coordinator waited for a completion while no task could run
 * @param controlLost whether the coordinator neither waited nor finished within the control timeout
 * @param runnableAfterEnd submitted tasks that could still start after {@code coordinate} ended
 * @param outcome what {@code coordinate} returned or threw
 * @param oracle what {@code DeterministicAeonCoordinator} returned or threw on the same workload
 */
record ScheduleRun(
        ScheduleWorkload workload,
        int[] choices,
        List<String> events,
        List<Integer> completionOrder,
        List<String> submissionViolations,
        boolean stuckWithoutPendingWork,
        boolean controlLost,
        List<Integer> runnableAfterEnd,
        Outcome outcome,
        Outcome oracle) {

    ScheduleRun {
        choices = choices.clone();
        events = List.copyOf(events);
        completionOrder = List.copyOf(completionOrder);
        submissionViolations = List.copyOf(submissionViolations);
        runnableAfterEnd = List.copyOf(runnableAfterEnd);
    }

    /** What a coordinator call produced. */
    sealed interface Outcome {

        /** {@code coordinate} returned. */
        record Returned(AeonCoordinationResult result) implements Outcome {}

        /** {@code coordinate} threw. */
        record Threw(Throwable failure) implements Outcome {}
    }

    /** Renders the run as a readable counterexample. */
    String describe() {
        var text = new StringBuilder();
        text.append("workload: ").append(workload).append('\n');
        text.append("replay choices: ").append(Arrays.toString(choices)).append('\n');
        text.append("schedule:\n");
        for (int i = 0; i < events.size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(events.get(i)).append('\n');
        }
        text.append("coordinate: ").append(render(outcome)).append('\n');
        text.append("sequential oracle: ").append(render(oracle));
        return text.toString();
    }

    static String render(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Returned returned -> "returned " + returned.result().inputResults().size() + " results";
            case Outcome.Threw threw -> {
                var failure = threw.failure();
                var suppressed = Arrays.stream(failure.getSuppressed())
                        .map(ScheduleRun::name)
                        .toList();
                yield "threw " + name(failure) + " suppressed=" + suppressed;
            }
        };
    }

    private static String name(Throwable failure) {
        return failure instanceof InputFailure inputFailure
                ? "InputFailure(" + inputFailure.inputIndex() + ")"
                : failure.getClass().getSimpleName() + "(" + failure.getMessage() + ")";
    }
}
