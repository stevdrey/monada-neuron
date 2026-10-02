package monada.neuron.evaluation;

import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.SignalEvidence;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reports retained size (modeled) and transient allocation (measured) of evaluation separately,
 * mirroring the hypothesis footprint and allocation tests.
 */
class HypothesisEvaluationFootprintTest {

    private static final int EVIDENCE_PER_HYPOTHESIS = 4;

    private final ReferenceHypothesisEvaluationPolicy policy = new ReferenceHypothesisEvaluationPolicy();
    private final HypothesisEvaluationFootprintModel model = new HypothesisEvaluationFootprintModel();

    @Test
    void modelsExactBytesForKnownSmallResults() {
        // record 24; List12 24; 1 * (EvaluatedHypothesis 24 + breakdown 64)
        assertEquals(24 + 24 + 88, model.retainedBytes(policy.evaluate(set(5), 1)));
        // record 24; ListN 24 + array 32; 3 * 88
        assertEquals(24 + 56 + 3 * 88, model.retainedBytes(policy.evaluate(set(5), 3)));
        assertEquals(24, model.retainedBytes(policy.evaluate(HypothesisSet.EMPTY, 3)));
    }

    @Test
    void retainedSizeDependsOnSelectedCountNotOnCandidateCount() {
        var small = model.retainedBytes(policy.evaluate(set(100), 10));
        var large = model.retainedBytes(policy.evaluate(set(10_000), 10));
        System.out.printf("modeledRetainedBytes K=10: N=100 -> %d, N=10000 -> %d, perSelected=%d%n",
                small, large, model.bytesPerSelected());
        assertEquals(small, large);
    }

    @Test
    void transientAllocationScalesWithCandidatesOnlyByTheScoreArray() {
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
        var k = 10;
        for (var count : new int[] {100, 1_000, 10_000}) {
            var set = set(count);
            for (var warm = 0; warm < 3; warm++) {
                policy.evaluate(set, k);
            }
            var before = threads.getCurrentThreadAllocatedBytes();
            var evaluation = policy.evaluate(set, k);
            var allocated = threads.getCurrentThreadAllocatedBytes() - before;
            System.out.printf("candidates=%d K=%d allocatedBytes=%d perCandidate=%.1f%n",
                    count, k, allocated, (double) allocated / count);
            assertEquals(k, evaluation.selected().size());
            // double[] scores (8 B per candidate) plus a bounded, K-sized result; slack for tooling.
            var bound = 8L * count + 256L * k + 1_024;
            assertTrue(allocated > 0 && allocated <= bound,
                    "allocation not bounded at N=" + count + ": " + allocated + " > " + bound);
        }
    }

    private static HypothesisSet set(int count) {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(count, EVIDENCE_PER_HYPOTHESIS));
        for (var i = 0; i < count; i++) {
            var seq = builder.propose(new Proposition(0, i)).getAsInt();
            for (var e = 0; e < EVIDENCE_PER_HYPOTHESIS; e++) {
                var relation = (i + e) % 5 == 0 ? EvidenceRelation.CONTRADICTS : EvidenceRelation.SUPPORTS;
                builder.addEvidence(seq, new SignalEvidence(e, relation, 0.25 + 0.25 * ((i + e) % 4)));
            }
        }
        return builder.build();
    }
}
