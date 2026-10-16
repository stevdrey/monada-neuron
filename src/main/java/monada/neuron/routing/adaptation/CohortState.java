package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.CohortKey;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-only diagnostic view of one cohort Node. The amplitude is a preference ordinal, not a calibrated success
 * probability.
 *
 * @param key cohort
 * @param nodeId deterministic Node identity
 * @param amplitude current amplitude
 * @param frequency current frequency
 * @param phase current phase
 * @param energy current energy
 * @param preferenceValue {@code amplitude - baselineAmplitude}
 * @param supportingObservations applied observations that moved the Node, saturated at {@code Integer.MAX_VALUE}
 * @param status whether the route is in the catalog the view was taken for
 */
public record CohortState(
        CohortKey key,
        UUID nodeId,
        double amplitude,
        double frequency,
        double phase,
        double energy,
        double preferenceValue,
        int supportingObservations,
        CohortStatus status) {

    /** Requires the parts. */
    public CohortState {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (supportingObservations < 0) {
            throw new IllegalArgumentException(
                    "supportingObservations must be non-negative, got: " + supportingObservations);
        }
    }
}
