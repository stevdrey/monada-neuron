package monada.neuron.evaluation;

/**
 * Analytic model of the retained heap footprint of an immutable {@link HypothesisEvaluation}.
 *
 * <p>Assumes a 64-bit HotSpot JVM with compressed references: 12-byte object header, 4-byte
 * references, 8-byte object alignment. It models only the evaluation result. The evaluated
 * {@code HypothesisSet} is shared by reference and excluded. It is an estimate, not a measurement.
 */
final class HypothesisEvaluationFootprintModel {

    private static final int HEADER = 12;
    private static final int REF = 4;
    private static final int ARRAY_HEADER = 16;

    /** Returns the modeled retained bytes of the evaluation and its selected candidates. */
    long retainedBytes(HypothesisEvaluation evaluation) {
        var selected = evaluation.selected().size();
        return align(HEADER + 2 * REF + 4)                        // HypothesisEvaluation record (set ref, list ref, int)
                + listBytes(selected)
                + selected * (evaluatedBytes() + breakdownBytes());
    }

    /** Modeled bytes of one selected candidate (reference record plus its breakdown). */
    long bytesPerSelected() {
        return evaluatedBytes() + breakdownBytes();
    }

    private long evaluatedBytes() {
        return align(HEADER + 4 + REF);                           // EvaluatedHypothesis
    }

    private long breakdownBytes() {
        return align(HEADER + 5 * 8 + 3 * 4);                     // 5 doubles + 3 ints
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
