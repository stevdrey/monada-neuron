package monada.neuron.aeon;

/**
 * Selects whether contextual Aeon coordination may use the experimental parallel implementation.
 *
 * <p>{@link #SEQUENTIAL_ORACLE} preserves the deterministic sequential coordinator as the
 * default runtime path. {@link #EXPERIMENTAL_PARALLEL} is an explicit opt-in for benchmark and
 * experimental use while the contextual parallel path has no demonstrated end-to-end benefit.
 */
public enum ContextualParallelism {

    /** Always coordinate through the deterministic sequential oracle. */
    SEQUENTIAL_ORACLE,

    /** Permit the bounded contextual parallel implementation when its other eligibility checks pass. */
    EXPERIMENTAL_PARALLEL
}
