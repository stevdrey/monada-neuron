package monada.neuron.routing.feedback;

import monada.neuron.routing.token.OpaqueTokens;

/**
 * The exact revision of a comparison execution that a cost-refined reward depended on. The caller's ledger keeps it,
 * so a later correction of that execution, even one that never moved a Node itself, forces the dependent reward to be
 * rebuilt.
 *
 * @param experienceRef opaque sample reference of the peer observation
 * @param revision peer revision the reward was derived against
 * @param ledgerSequence ledger sequence of that peer revision
 */
public record PeerRevision(String experienceRef, long revision, long ledgerSequence) {

    /** Validates the parts. */
    public PeerRevision {
        OpaqueTokens.requireText(experienceRef, "experienceRef");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative, got: " + revision);
        }
        if (ledgerSequence < 0) {
            throw new IllegalArgumentException("ledgerSequence must be non-negative, got: " + ledgerSequence);
        }
    }

    /**
     * True when this peer revision comes strictly before the given position in applied order
     * {@code (ledgerSequence, experienceRef by code point)}, so a replay always applies the peer first and the reward
     * never depends on data recorded after it.
     */
    public boolean precedes(long otherLedgerSequence, String otherExperienceRef) {
        return ledgerSequence != otherLedgerSequence
                ? ledgerSequence < otherLedgerSequence
                : OpaqueTokens.compareCodePoints(experienceRef, otherExperienceRef) < 0;
    }
}
