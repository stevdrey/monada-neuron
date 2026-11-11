package monada.neuron.aeon.formal;

import monada.neuron.aeon.formal.ScheduleRun.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bounded formal-verification pilot for Issue #202: the direct-path failure semantics of
 * {@code BoundedParallelAeonCoordinator} (ADR 0016 decisions 1, 3, 5, and 6), checked on every
 * schedule within the bounds against the unmodified coordinator. Report:
 * {@code docs/experiments/formal-pilot-aeon-failure-semantics-v1.md}.
 *
 * <p>Excluded from {@code ./gradlew test}; run it with {@code ./gradlew formalPilotCheck}. The input
 * bound defaults to 4 and can be set with {@code -PformalPilotMaxInputs=<n>}.
 */
@Tag("formal-pilot")
class BoundedParallelFailureSemanticsPilotTest {

    private static final int MAX_INPUTS = maxInputs(System.getProperty("formalPilot.maxInputs", "4"));
    private static final Path REPORT_DIR = Path.of(System.getProperty("formalPilot.reportDir", "build/reports/formal-pilot"));

    @Test
    void everyScheduleWithinTheBoundsSatisfiesTheFailureSemantics() {
        var explorer = ScheduleExplorer.exploreBounds(MAX_INPUTS);
        var summary = explorer.summary("formal pilot #202: BoundedParallelAeonCoordinator direct-path failure "
                + "semantics, inputs 1.." + MAX_INPUTS + ", maxParallelism 2..inputs+1, every failure mask");
        write("exhaustive-max-inputs-" + MAX_INPUTS + ".txt", summary);
        System.out.println(summary);

        var failures = new ArrayList<String>();
        for (var property : FailureSemanticsProperty.values()) {
            int violations = explorer.violations(property);
            if (property.expectedToHold() && violations > 0) {
                failures.add("VIOLATED " + explorer.counterexample(property));
            }
            if (!property.expectedToHold() && violations == 0) {
                failures.add("negative control " + property + " found no counterexample: the check cannot fail");
            }
        }
        if (!failures.isEmpty()) {
            fail(String.join("\n\n", failures));
        }
    }

    @Test
    void deliberatelyFalsePropertiesHaveTwoInputCounterexamples() {
        var explorer = ScheduleExplorer.exploreBounds(2);
        write("negative-controls.txt", explorer.summary("formal pilot #202: negative controls, inputs 1..2"));

        var notSuppressed = explorer.smallestCounterexample(FailureSemanticsProperty.EVERY_LATER_FAILURE_IS_SUPPRESSED)
                .orElseThrow(() -> new AssertionError("no counterexample for EVERY_LATER_FAILURE_IS_SUPPRESSED"));
        var firstCompleted = explorer.smallestCounterexample(FailureSemanticsProperty.FIRST_COMPLETED_FAILURE_IS_THROWN)
                .orElseThrow(() -> new AssertionError("no counterexample for FIRST_COMPLETED_FAILURE_IS_THROWN"));
        System.out.println(explorer.counterexample(FailureSemanticsProperty.EVERY_LATER_FAILURE_IS_SUPPRESSED));
        System.out.println(explorer.counterexample(FailureSemanticsProperty.FIRST_COMPLETED_FAILURE_IS_THROWN));

        assertAll(
                // Input 0 fails and is reconciled before input 1 completes: input 1 is cancelled, not suppressed.
                () -> assertEquals(new ScheduleWorkload(2, 2, 0b11), notSuppressed.workload()),
                () -> assertTrue(notSuppressed.outcome() instanceof Outcome.Threw(InputFailure thrown)
                        && thrown.inputIndex() == 0 && thrown.getSuppressed().length == 0),
                () -> assertTrue(notSuppressed.events().contains("task 1 is cancelled before it runs")),
                // Input 1 fails first, yet input 0's failure is thrown with input 1's attached.
                () -> assertEquals(2, firstCompleted.workload().inputCount()),
                () -> assertEquals(1, firstCompleted.completionOrder().getFirst()),
                () -> assertTrue(firstCompleted.outcome() instanceof Outcome.Threw(InputFailure thrown)
                        && thrown.inputIndex() == 0));
    }

    @Test
    void smallestCounterexampleReplaysIdenticallyOnTheRealCoordinator() {
        var explorer = ScheduleExplorer.exploreBounds(2);
        var counterexample = explorer.smallestCounterexample(FailureSemanticsProperty.EVERY_LATER_FAILURE_IS_SUPPRESSED)
                .orElseThrow();

        var replay = ScheduledCoordinatorRun.run(counterexample.workload(), new ChoicePath(counterexample.choices()));
        // A caller cannot change a stored counterexample's replay through the returned array.
        var returned = counterexample.choices();
        returned[0]++;

        assertAll(
                () -> assertArrayEquals(replay.choices(), counterexample.choices()),
                () -> assertEquals(returned[0] - 1, counterexample.choices()[0]),
                () -> assertEquals(counterexample.events(), replay.events()),
                () -> assertEquals(ScheduleRun.render(counterexample.outcome()), ScheduleRun.render(replay.outcome())),
                () -> assertTrue(FailureSemanticsProperty.EVERY_LATER_FAILURE_IS_SUPPRESSED.violation(replay).isPresent()));
    }

    @Test
    void aCoordinatorThatNeverEndsIsReportedAsAViolation() {
        // Choice 1 inside execute(task 0) runs task 0 on the coordinator thread, where this propagation spins
        // and ignores the harness's interrupt, so coordinate neither waits nor ends.
        var workload = new ScheduleWorkload(1, 2, 0);
        var released = new AtomicBoolean();
        IntConsumer hang = inputIndex -> {
            while (!released.get()) {
                Thread.onSpinWait();
            }
        };
        var path = new ChoicePath(new int[] {1});
        ScheduleRun neverEnded;
        try {
            neverEnded = ScheduledCoordinatorRun.run(workload, path,
                    ScheduledCoordinatorRun.oracle(workload), Duration.ofSeconds(1), hang);
        } finally {
            // The abandoned thread resumes and is rejected by the closed executor at its next choice.
            released.set(true);
        }
        awaitNoCoordinatorThread();

        for (var property : FailureSemanticsProperty.values()) {
            assertDoesNotThrow(() -> property.violation(neverEnded), property.name());
        }
        var run = neverEnded;
        assertAll(
                () -> assertTrue(run.controlLost()),
                () -> assertTrue(run.outcome() instanceof Outcome.DidNotEnd, ScheduleRun.render(run.outcome())),
                () -> assertTrue(run.events().contains("coordinate did not end after the interrupt and a 1 s join"),
                        run.events().toString()),
                () -> assertTrue(FailureSemanticsProperty.NO_WAIT_WITHOUT_PENDING_WORK.violation(run).isPresent()),
                () -> assertTrue(FailureSemanticsProperty.MATCHES_SEQUENTIAL_ORACLE.violation(run).isPresent()),
                () -> assertTrue(run.describe().contains("coordinate: did not end")),
                // Without the closed executor, the resumed thread would have taken a second choice on this path.
                () -> assertArrayEquals(new int[] {1}, path.choices()));
    }

    private static void awaitNoCoordinatorThread() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals("formal-pilot-coordinator") && thread.isAlive())) {
            if (System.nanoTime() > deadline) {
                fail("the abandoned coordinator thread did not end after it was released");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void anInvalidInputBoundIsRejected() {
        assertAll(
                () -> assertEquals(5, maxInputs(" 5 ")),
                () -> assertThrows(IllegalArgumentException.class, () -> maxInputs("five")),
                () -> assertThrows(IllegalArgumentException.class, () -> maxInputs("1")),
                () -> assertThrows(IllegalArgumentException.class, () -> maxInputs("31")));
    }

    /**
     * Parses the input bound. The negative controls need two inputs, and {@link ScheduleWorkload} allows at
     * most 30; any other value fails instead of silently running a different bound.
     */
    static int maxInputs(String value) {
        int bound;
        try {
            bound = Integer.parseInt(value.strip());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("formalPilot.maxInputs (-PformalPilotMaxInputs) must be an integer "
                    + "in [2, 30], got: '" + value + "'", notANumber);
        }
        if (bound < 2 || bound > 30) {
            throw new IllegalArgumentException("formalPilot.maxInputs (-PformalPilotMaxInputs) must be in [2, 30]: "
                    + "the negative controls need two inputs; got: " + bound);
        }
        return bound;
    }

    private static void write(String name, String text) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve(name), text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
