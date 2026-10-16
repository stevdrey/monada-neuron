package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.CohortKey;

import java.util.Objects;

/**
 * One cohort of a {@link RoutingStateCheckpoint}: the four numeric channels of its Node and its support count. A Node
 * restored from it is built like a reset one (deterministic identity, the definition's {@code NodeType}, no history)
 * with these channels, so a restore and a from-scratch replay are identical in every retained field (contract section
 * 7). The amplitude is a preference ordinal, not a calibrated success probability.
 *
 * @param key cohort
 * @param amplitude Node amplitude, finite
 * @param frequency Node frequency, finite
 * @param phase Node phase, finite
 * @param energy Node energy, finite
 * @param supportingObservations applied observations that moved the Node, non-negative; a store saturates the count at
 *        {@code Integer.MAX_VALUE} rather than wrapping
 */
public record CheckpointCohort(
        CohortKey key, double amplitude, double frequency, double phase, double energy, int supportingObservations) {

    /** Requires the key, finite channels and a non-negative count. */
    public CheckpointCohort {
        Objects.requireNonNull(key, "key must not be null");
        if (!Double.isFinite(amplitude) || !Double.isFinite(frequency) || !Double.isFinite(phase)
                || !Double.isFinite(energy)) {
            throw new IllegalArgumentException("cohort " + key + " channels must be finite");
        }
        if (supportingObservations < 0) {
            throw new IllegalArgumentException(
                    "supportingObservations must be non-negative, got: " + supportingObservations);
        }
    }
}
