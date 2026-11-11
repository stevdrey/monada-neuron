package monada.neuron.aeon.formal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explores every schedule of every workload within the bounds and checks every property on each run.
 *
 * <p>For each property it keeps the number of violating schedules and the smallest counterexample:
 * fewest inputs, then fewest schedule events, then smallest window, failure mask, and replay choices.
 */
final class ScheduleExplorer {

    private static final Comparator<ScheduleRun> SMALLEST_FIRST = Comparator
            .comparingInt((ScheduleRun run) -> run.workload().inputCount())
            .thenComparingInt(run -> run.events().size())
            .thenComparingInt(run -> run.workload().maxParallelism())
            .thenComparingInt(run -> run.workload().failureMask())
            .thenComparing(ScheduleRun::choices, Arrays::compare);

    private final Map<FailureSemanticsProperty, Integer> violations = new EnumMap<>(FailureSemanticsProperty.class);
    private final Map<FailureSemanticsProperty, ScheduleRun> smallest = new EnumMap<>(FailureSemanticsProperty.class);
    private final Map<FailureSemanticsProperty, String> smallestDetail = new EnumMap<>(FailureSemanticsProperty.class);
    private final List<String> workloadLines = new ArrayList<>();
    private long schedules;
    private int workloads;
    private int longestSchedule;
    private long elapsedNanos;

    /** Explores all workloads with 1 to {@code maxInputs} inputs and windows 2 to {@code inputs + 1}. */
    static ScheduleExplorer exploreBounds(int maxInputs) {
        var explorer = new ScheduleExplorer();
        for (int inputs = 1; inputs <= maxInputs; inputs++) {
            for (int window = 2; window <= inputs + 1; window++) {
                for (int mask = 0; mask < (1 << inputs); mask++) {
                    explorer.explore(new ScheduleWorkload(inputs, window, mask));
                }
            }
        }
        return explorer;
    }

    /** Explores every schedule of one workload. */
    void explore(ScheduleWorkload workload) {
        long start = System.nanoTime();
        long before = schedules;
        var oracle = ScheduledCoordinatorRun.oracle(workload);
        int[] prefix = new int[0];
        while (true) {
            var path = new ChoicePath(prefix);
            var run = ScheduledCoordinatorRun.run(workload, path, oracle);
            path.requireReplayed();
            check(run);
            var next = path.next();
            if (next.isEmpty()) {
                break;
            }
            prefix = next.get();
        }
        workloads++;
        elapsedNanos += System.nanoTime() - start;
        workloadLines.add(workload + " schedules=" + (schedules - before));
    }

    private void check(ScheduleRun run) {
        schedules++;
        longestSchedule = Math.max(longestSchedule, run.events().size());
        for (var property : FailureSemanticsProperty.values()) {
            var detail = property.violation(run);
            if (detail.isEmpty()) {
                continue;
            }
            violations.merge(property, 1, Integer::sum);
            var current = smallest.get(property);
            if (current == null || SMALLEST_FIRST.compare(run, current) < 0) {
                smallest.put(property, run);
                smallestDetail.put(property, detail.get());
            }
        }
    }

    long schedules() {
        return schedules;
    }

    int workloads() {
        return workloads;
    }

    int violations(FailureSemanticsProperty property) {
        return violations.getOrDefault(property, 0);
    }

    Optional<ScheduleRun> smallestCounterexample(FailureSemanticsProperty property) {
        return Optional.ofNullable(smallest.get(property));
    }

    /** The smallest counterexample for {@code property} with why it violates it, or a note that none exists. */
    String counterexample(FailureSemanticsProperty property) {
        var run = smallest.get(property);
        if (run == null) {
            return property + ": no counterexample within the bounds";
        }
        return property + " (" + property.statement() + ")\n"
                + "violation: " + smallestDetail.get(property) + "\n"
                + run.describe();
    }

    /** A summary of the exploration: bounds, counts, time, and the verdict and counterexample per property. */
    String summary(String title) {
        var text = new StringBuilder();
        text.append(title).append('\n');
        text.append("java.version=").append(System.getProperty("java.version"))
                .append(" java.vm=").append(System.getProperty("java.vm.name"))
                .append(' ').append(System.getProperty("java.vm.version"))
                .append(" processors=").append(Runtime.getRuntime().availableProcessors()).append('\n');
        text.append("workloads=").append(workloads)
                .append(" schedules=").append(schedules)
                .append(" longestSchedule=").append(longestSchedule).append(" events")
                .append(" exploreMillis=").append(elapsedNanos / 1_000_000).append('\n');
        text.append('\n');
        for (var property : FailureSemanticsProperty.values()) {
            int count = violations(property);
            String verdict;
            if (property.expectedToHold()) {
                verdict = count == 0 ? "HOLDS within bounds" : "VIOLATED";
            } else {
                verdict = count == 0 ? "NOT REFUTED (negative control failed)" : "REFUTED as expected";
            }
            text.append(property).append(": ").append(verdict)
                    .append(" (violating schedules: ").append(count).append(")\n");
            text.append("  ").append(property.statement()).append('\n');
        }
        for (var property : FailureSemanticsProperty.values()) {
            if (smallest.containsKey(property)) {
                text.append('\n').append("smallest counterexample, ").append(counterexample(property)).append('\n');
            }
        }
        text.append('\n').append("schedules per workload:\n");
        for (var line : workloadLines) {
            text.append("  ").append(line).append('\n');
        }
        return text.toString();
    }
}
