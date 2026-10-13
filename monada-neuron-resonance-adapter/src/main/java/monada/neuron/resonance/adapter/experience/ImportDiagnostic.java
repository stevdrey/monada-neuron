package monada.neuron.resonance.adapter.experience;

/** Import-level findings that are not about a single sample. */
public enum ImportDiagnostic {
    /** The requested scope is not the scope the caller's memory was opened for; nothing was read. */
    SCOPE_MISMATCH,
    /** The page schema is not {@code execution-sample/1}; paging stopped and nothing was guessed. */
    UNSUPPORTED_SCHEMA,
    /** The Store reported the scope-level projection as not current; exports do not depend on it, so this is only a note. */
    PROJECTION_NOT_CURRENT,
    /** {@code maxTotalSamples} was reached while the snapshot had more samples. */
    SAMPLE_CAP_REACHED
}
