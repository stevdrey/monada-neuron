package monada.neuron.runtime.selection;

/**
 * Execution preference governing runtime backend selection.
 */
public enum ExecutionPreference {

    /** Always select the safe, portable reference implementation. */
    REFERENCE,

    /**
     * Automatically select an optimized backend when available, semantically eligible,
     * and beneficial according to benchmark-derived workload thresholds.
     */
    AUTO,

    /** Select a specific explicitly requested backend. */
    EXPLICIT
}
