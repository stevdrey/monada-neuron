package monada.neuron.reasoning;

/**
 * Analytic model of the retained heap footprint of an immutable {@link HypothesisSet}.
 *
 * <p>Assumes a 64-bit HotSpot JVM with compressed references: 12-byte object header, 4-byte
 * references, 8-byte object alignment. It models only the final snapshot, excluding transient
 * builder state and the shared {@link HypothesisLimits} instance. It is an estimate for sizing
 * decisions, not a measurement.
 */
final class HypothesisFootprintModel {

    private static final int HEADER = 12;
    private static final int REF = 4;
    private static final int ARRAY_HEADER = 16;

    /** Returns the modeled retained bytes of the set, including its evidence and strings. */
    long retainedBytes(HypothesisSet set) {
        var total = align(HEADER + 2 * REF)                       // HypothesisSet record
                + listBytes(set.size());                          // outer immutable list
        for (var hypothesis : set.hypotheses()) {
            total += align(HEADER + 4 + 2 * REF)                  // Hypothesis
                    + align(HEADER + 4 + 8)                       // Proposition
                    + listBytes(hypothesis.evidence().size());
            for (var evidence : hypothesis.evidence()) {
                total += evidenceBytes(evidence);
            }
        }
        return total;
    }

    private long evidenceBytes(Evidence evidence) {
        return switch (evidence) {
            case SignalEvidence signal -> align(HEADER + 8 + REF + 8);
            case MemoryReferenceEvidence memory -> align(HEADER + 2 * REF + 8)
                    + align(HEADER + REF + 4 + 2)                 // String
                    + align(ARRAY_HEADER + memory.reference().length()); // Latin-1 byte[]
        };
    }

    /** Immutable list: shared when empty, List12 for one or two elements, else ListN plus array. */
    private long listBytes(int size) {
        if (size == 0) {
            return 0;
        }
        if (size <= 2) {
            return align(HEADER + 2 * REF);
        }
        return align(HEADER + REF + 1) + align(ARRAY_HEADER + (long) REF * size);
    }

    private static long align(long bytes) {
        return (bytes + 7) & ~7L;
    }
}
