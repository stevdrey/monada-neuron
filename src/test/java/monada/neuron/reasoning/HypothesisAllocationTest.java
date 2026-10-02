package monada.neuron.reasoning;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures transient allocation (builder growth plus final snapshot). This is not retained size;
 * see {@link HypothesisFootprintModelTest} for the retained-footprint model.
 */
class HypothesisAllocationTest {

    private static final int EVIDENCE_PER_HYPOTHESIS = 4;
    private static final long MAX_BYTES_PER_HYPOTHESIS = 1_024;

    @Test
    void transientAllocationPerHypothesisStaysBoundedAtRepresentativeCounts() {
        var bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean,
                "com.sun.management.ThreadMXBean is unavailable");
        var threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported(),
                "thread allocated-memory tracking is unsupported");
        if (!threads.isThreadAllocatedMemoryEnabled()) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
        assumeTrue(threads.getCurrentThreadAllocatedBytes() >= 0,
                "thread allocated-memory tracking is disabled");
        for (var count : new int[] {10, 100, 1_000}) {
            build(count); // warm-up
            var before = threads.getCurrentThreadAllocatedBytes();
            var set = build(count);
            var perHypothesis = (threads.getCurrentThreadAllocatedBytes() - before) / count;
            System.out.printf("hypotheses=%d allocatedBytesPerHypothesis=%d%n", count, perHypothesis);
            assertTrue(before >= 0 && perHypothesis > 0, "allocation was not measured");
            assertTrue(set.size() == count);
            assertTrue(perHypothesis <= MAX_BYTES_PER_HYPOTHESIS,
                    "transient allocation per hypothesis too high at " + count + ": " + perHypothesis);
        }
    }

    private HypothesisSet build(int count) {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(count, EVIDENCE_PER_HYPOTHESIS));
        for (var i = 0; i < count; i++) {
            var seq = builder.propose(new Proposition(0, i)).getAsInt();
            for (var e = 0; e < EVIDENCE_PER_HYPOTHESIS; e++) {
                builder.addEvidence(seq, new SignalEvidence(e, EvidenceRelation.SUPPORTS, 0.5));
            }
        }
        return builder.build();
    }
}
