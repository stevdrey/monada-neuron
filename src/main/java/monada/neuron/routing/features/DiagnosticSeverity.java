package monada.neuron.routing.features;

/**
 * How a host preview should present one {@link FieldDiagnostic}, fixed by its status and whether the host
 * required the dimension.
 *
 * <p>Declaration order is fixed for determinism only. It is not a scale: do not compare severities or take a
 * maximum of them.
 *
 * <p>Diagnostics are information only: they are never eligibility, readiness, authentication or authorization,
 * and no routing decision reads them.
 */
public enum DiagnosticSeverity {
    /** A known value, or an unknown value the host did not require. */
    INFORMATION,
    /** An unknown value the host listed as required for its own preview. */
    MISSING_FOR_HOST_PREVIEW,
    /** A value that contradicts the request the host is about to build, so that request cannot be built. */
    INCONSISTENT_WITH_REQUEST
}
