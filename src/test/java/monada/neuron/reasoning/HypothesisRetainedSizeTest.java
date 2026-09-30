package monada.neuron.reasoning;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HypothesisRetainedSizeTest {

    private static final int EVIDENCE_PER_HYPOTHESIS = 4;
    private static final long MAX_BYTES_PER_HYPOTHESIS = 1_024;

    @Test
    void allocationPerHypothesisStaysBoundedAtRepresentativeCounts() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (var count : new int[] {10, 100, 1_000}) {
            build(count); // warm-up
            var before = threads.getCurrentThreadAllocatedBytes();
            var set = build(count);
            var perHypothesis = (threads.getCurrentThreadAllocatedBytes() - before) / count;
            System.out.printf("hypotheses=%d allocatedBytesPerHypothesis=%d%n", count, perHypothesis);
            assertTrue(set.size() == count);
            assertTrue(perHypothesis <= MAX_BYTES_PER_HYPOTHESIS,
                    "allocation per hypothesis too high at " + count + ": " + perHypothesis);
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
