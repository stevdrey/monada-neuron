package monada.neuron.evaluation.knowledge;

/**
 * The bounded decision an evaluation-only host derives from one evidence-trace cycle (Issue #108). It is derived from
 * the cycle's own stage results and the {@link RecalledReferenceResolver}, never from gold labels, and it says only
 * whether the cycle carried resolvable recalled evidence: it is not an answer and not a correctness verdict.
 */
enum EvidenceDecision {

    /**
     * The memory recall completed, the {@code EVALUATION} stage selected a hypothesis with supporting evidence, and
     * every reference of that evidence was admitted in the same cycle and resolves to a verified persisted chunk.
     */
    SUPPORTED,

    /** The memory recall completed and no hypothesis with supporting evidence was selected. */
    UNSUPPORTED,

    /** The memory port answered {@code UNAVAILABLE}, {@code TIMED_OUT} or {@code FAILED}. */
    RECALL_FAILED,

    /** The cycle ended before its {@code EVALUATION} stage ran (for example on budget exhaustion). */
    INCOMPLETE,

    /**
     * Selected evidence names a reference that is unknown, unverified or was not admitted in the same cycle; the
     * decision fails closed instead of reporting support.
     */
    FAILED_CLOSED
}
