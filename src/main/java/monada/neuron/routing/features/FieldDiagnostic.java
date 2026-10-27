package monada.neuron.routing.features;

import java.util.Objects;

/**
 * One row of a feature readiness report: a dimension, what was observed about it, whether the host required it,
 * and the severity the fixed table gives for that pair. A row never carries the feature value itself.
 *
 * <p>Severity table: {@code KNOWN} gives {@code INFORMATION}; {@code UNKNOWN} gives {@code INFORMATION} when not
 * required and {@code MISSING_FOR_HOST_PREVIEW} when required; {@code MISMATCH} gives
 * {@code INCONSISTENT_WITH_REQUEST} either way.
 *
 * <p>Diagnostics are information only: they are never eligibility, readiness, authentication or authorization,
 * and no routing decision reads them.
 *
 * @param dimension the feature dimension this row describes
 * @param status what was observed for the dimension
 * @param required whether the caller listed the dimension as required for its preview, echoed unchanged
 * @param severity the severity the table gives for {@code status} and {@code required}
 */
public record FieldDiagnostic(
        FeatureDimension dimension,
        FieldStatus status,
        boolean required,
        DiagnosticSeverity severity) {

    /** Rejects {@code MISMATCH} off {@code STAGE_KIND} and a severity that disagrees with the table. */
    public FieldDiagnostic {
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        if (status == FieldStatus.MISMATCH && dimension != FeatureDimension.STAGE_KIND) {
            throw new IllegalArgumentException("MISMATCH applies only to STAGE_KIND, got: " + dimension);
        }
        DiagnosticSeverity expected = severityFor(status, required);
        if (severity != expected) {
            throw new IllegalArgumentException("severity for " + status + (required ? " required" : " not required")
                    + " must be " + expected + ", got: " + severity);
        }
    }

    /**
     * Creates the row with the severity the table gives for {@code status} and {@code required}.
     *
     * @param dimension the feature dimension the row describes
     * @param status what was observed for the dimension
     * @param required whether the caller required the dimension
     * @return the row with its derived severity
     */
    public static FieldDiagnostic of(FeatureDimension dimension, FieldStatus status, boolean required) {
        Objects.requireNonNull(status, "status must not be null");
        return new FieldDiagnostic(dimension, status, required, severityFor(status, required));
    }

    private static DiagnosticSeverity severityFor(FieldStatus status, boolean required) {
        return switch (status) {
            case KNOWN -> DiagnosticSeverity.INFORMATION;
            case UNKNOWN -> required ? DiagnosticSeverity.MISSING_FOR_HOST_PREVIEW : DiagnosticSeverity.INFORMATION;
            case MISMATCH -> DiagnosticSeverity.INCONSISTENT_WITH_REQUEST;
        };
    }
}
