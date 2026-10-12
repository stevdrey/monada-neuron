package monada.neuron.routing.outcome;

import org.junit.jupiter.api.Assumptions;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Measures the bytes the current thread allocates while running an action (skips the test when unsupported). */
final class AllocationProbe {

    private AllocationProbe() {
    }

    /** Runs {@code action} {@code warmUps} times, then once more and returns the bytes allocated by that last run. */
    static long measure(int warmUps, Runnable action) {
        for (int i = 0; i < warmUps; i++) {
            action.run();
        }
        // Fully qualified: the simple name collides with java.lang.management.ThreadMXBean.
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(threads.isThreadAllocatedMemorySupported(), "thread allocation tracking unsupported");
        threads.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(id);
        action.run();
        long after = threads.getThreadAllocatedBytes(id);
        assertTrue(before >= 0 && after >= before, "allocation counter unavailable");
        return after - before;
    }
}
