package monada.neuron.routing.outcome;

import java.util.Objects;

/**
 * An evaluation-time API cost over a dated, caller-supplied price snapshot. It is hypothetical for every billing mode
 * and is never actual billing.
 *
 * @param amount exact amount and currency
 * @param priceSnapshotId opaque price snapshot id
 * @param priceSnapshotVersion price snapshot version
 * @param assumptions free-text pricing assumptions, up to 512 code points
 */
public record HypotheticalCost(Money amount, String priceSnapshotId, String priceSnapshotVersion, String assumptions) {

    /** Validates the parts. */
    public HypotheticalCost {
        Objects.requireNonNull(amount, "amount must not be null");
        OutcomeTokens.token(priceSnapshotId, "priceSnapshotId");
        OutcomeTokens.token(priceSnapshotVersion, "priceSnapshotVersion");
        OutcomeTokens.text(assumptions, "assumptions");
    }
}
