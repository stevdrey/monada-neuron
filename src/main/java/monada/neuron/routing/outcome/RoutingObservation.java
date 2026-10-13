package monada.neuron.routing.outcome;

import monada.neuron.routing.features.Feature;

import java.util.Objects;

/**
 * Neutral, Store-independent record of one effective execution sample: a {@link RoutingOutcome} plus the exact
 * position of the sample in the ledger. The sample reference is an opaque string; no Store type appears here.
 *
 * @param outcome the host-evaluated outcome
 * @param experienceRef opaque exact reference of the sample, up to 512 code points
 * @param revision revision of the sample, non-negative
 * @param ledgerSequence ledger sequence of the sample, at most the outcome's cutoff
 * @param supersedesSequence sequence of the earliest revision this sample replaces, strictly less than
 *         {@code ledgerSequence}; required when {@code revision > 0}, unknown for an initial revision
 */
public record RoutingObservation(
        RoutingOutcome outcome,
        String experienceRef,
        long revision,
        long ledgerSequence,
        Feature<Long> supersedesSequence) {

    /** Validates the position. */
    public RoutingObservation {
        Objects.requireNonNull(outcome, "outcome must not be null");
        OutcomeTokens.text(experienceRef, "experienceRef");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative, got: " + revision);
        }
        if (ledgerSequence < 0 || ledgerSequence > outcome.cutoff()) {
            throw new IllegalArgumentException(
                    "ledgerSequence must be within [0, cutoff=" + outcome.cutoff() + "], got: " + ledgerSequence);
        }
        Objects.requireNonNull(supersedesSequence, "supersedesSequence must not be null");
        if (supersedesSequence instanceof Feature.Known<Long> known) {
            if (revision == 0) {
                throw new IllegalArgumentException("an initial revision replaces nothing, so it has no supersedesSequence");
            }
            if (known.value() < 0 || known.value() >= ledgerSequence) {
                throw new IllegalArgumentException("supersedesSequence must be in [0, ledgerSequence), got: "
                        + known.value());
            }
        } else if (revision > 0) {
            throw new IllegalArgumentException("a corrected revision must carry the supersedesSequence it replaces");
        }
    }
}
