package monada.neuron.runtime.selection;

/**
 * Diagnostic reason explaining why a specific backend was selected.
 */
public enum SelectionReason {

    /** Selected because reference mode is active as safe default. */
    REFERENCE_DEFAULT,

    /** Selected because forced reference mode was requested for debugging or testing. */
    FORCED_REFERENCE,

    /** Selected automatically because capability is present and workload scale meets or exceeds threshold. */
    AUTO_THRESHOLD_MET,

    /** Reference selected in AUTO mode because workload scale is below the benchmark crossover threshold. */
    AUTO_BELOW_THRESHOLD,

    /** Reference selected in AUTO mode because semantic prerequisites were not satisfied. */
    AUTO_INELIGIBLE,

    /** Reference selected in AUTO mode because optional platform capability is unavailable. */
    AUTO_UNAVAILABLE,

    /** Selected because the backend was explicitly requested by caller. */
    EXPLICIT_SELECTION,

    /** Reference selected as fallback because the requested backend is unavailable on this platform. */
    FALLBACK_UNAVAILABLE,

    /** Reference selected as fallback because the requested backend is semantically ineligible for the workload. */
    FALLBACK_INELIGIBLE
}
