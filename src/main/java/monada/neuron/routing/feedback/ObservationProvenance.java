package monada.neuron.routing.feedback;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;

/**
 * The outcome provenance a no-update marker keeps, because a marker has no derived feedback to carry it: two
 * observations that claim the same sample revision and position but differ in any of these are different claims, as
 * {@link RebuildOrder} treats them, and must not compare equal in the ledger.
 *
 * @param catalogVersion catalog snapshot behind the decision
 * @param cutoff ledger cutoff the outcome was evaluated as of
 * @param cohortBinding cohort binding the outcome recorded
 */
public record ObservationProvenance(String catalogVersion, long cutoff, CohortBinding cohortBinding) {

    /** Validates the parts. */
    public ObservationProvenance {
        OpaqueTokens.require(catalogVersion, "catalogVersion");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must be non-negative, got: " + cutoff);
        }
        Objects.requireNonNull(cohortBinding, "cohortBinding must not be null");
    }
}
