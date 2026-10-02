package monada.neuron.reasoning;

import monada.neuron.memory.ResonanceMemoryResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retained-footprint model (not allocation); see {@link HypothesisAllocationTest} for that. */
class HypothesisFootprintModelTest {

    private static final int EVIDENCE_PER_HYPOTHESIS = 4;
    private static final long MAX_MODELED_BYTES_PER_HYPOTHESIS = 512;

    private final HypothesisFootprintModel model = new HypothesisFootprintModel();

    @Test
    void modelsLinearRetainedFootprintAtRepresentativeCounts() {
        var previousPerHypothesis = 0.0;
        for (var count : new int[] {10, 100, 1_000}) {
            var bytes = model.retainedBytes(set(count, false));
            var perHypothesis = (double) bytes / count;
            System.out.printf("hypotheses=%d modeledRetainedBytes=%d perHypothesis=%.1f%n",
                    count, bytes, perHypothesis);
            assertTrue(perHypothesis <= MAX_MODELED_BYTES_PER_HYPOTHESIS,
                    "modeled retained bytes per hypothesis too high at " + count);
            if (previousPerHypothesis > 0.0) {
                assertTrue(Math.abs(perHypothesis - previousPerHypothesis) < 8.0,
                        "per-hypothesis footprint must stay flat as count grows");
            }
            previousPerHypothesis = perHypothesis;
        }
    }

    @Test
    void modelsExactBytesForAKnownSmallGraph() {
        // set record 24 + list(1) 24; hypothesis 24 + proposition 24 + list(4) 24+32; 4 evidence * 32
        assertEquals(24 + 24 + 24 + 24 + 56 + 4 * 32, model.retainedBytes(set(1, false)));
    }

    @Test
    void memoryReferencesAddBoundedStringFootprint() {
        var withMemory = model.retainedBytes(set(100, true));
        var signalOnly = model.retainedBytes(set(100, false));
        var maxStringBytes = 24 + 16 + ResonanceMemoryResult.MAX_REFERENCE_LENGTH;
        assertTrue(withMemory > signalOnly);
        assertTrue(withMemory - signalOnly <= 100L * EVIDENCE_PER_HYPOTHESIS * (maxStringBytes + 8));
    }

    private HypothesisSet set(int count, boolean memoryEvidence) {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(count, EVIDENCE_PER_HYPOTHESIS));
        for (var i = 0; i < count; i++) {
            var seq = builder.propose(new Proposition(0, i)).getAsInt();
            for (var e = 0; e < EVIDENCE_PER_HYPOTHESIS; e++) {
                builder.addEvidence(seq, memoryEvidence
                        ? new MemoryReferenceEvidence("rs-" + "0123456789abcdef".repeat(2), EvidenceRelation.SUPPORTS, 0.5)
                        : new SignalEvidence(e, EvidenceRelation.SUPPORTS, 0.5));
            }
        }
        return builder.build();
    }
}
