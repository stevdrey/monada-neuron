package monada.neuron.routing.outcome;

/**
 * An exact evidence event revision the host judged an outcome on. If a newer revision of the same event exists at the
 * outcome's cutoff, the judgment is stale.
 *
 * @param eventRef opaque event reference
 * @param revision judged revision, non-negative
 */
public record EvidenceRevision(String eventRef, long revision) {

    /** Validates the reference and revision. */
    public EvidenceRevision {
        OutcomeTokens.text(eventRef, "eventRef");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative, got: " + revision);
        }
    }
}
