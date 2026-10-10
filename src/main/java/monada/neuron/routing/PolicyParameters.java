package monada.neuron.routing;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The configuration of the policy that decided, recorded in every decision so equal audit data reproduces the
 * decision. {@code policyId} and {@code policyVersion} identify only the ordering rules; these parameters can change
 * the outcome for identical inputs without changing them.
 *
 * @param minSupportingObservations observations a cohort needs before learned preference counts, at least 1
 * @param maxAutoSelectTier highest tier advised without host confirmation; empty means unbounded
 * @param objective optional resource objective
 * @param stateMappingVersion {@code mappingVersion} of the state definition the policy admits snapshots against
 */
public record PolicyParameters(
        int minSupportingObservations,
        OptionalInt maxAutoSelectTier,
        Optional<ResourceObjective> objective,
        String stateMappingVersion) {

    /** Validates parts. */
    public PolicyParameters {
        if (minSupportingObservations < 1) {
            throw new IllegalArgumentException(
                    "minSupportingObservations must be at least 1, got: " + minSupportingObservations);
        }
        Objects.requireNonNull(maxAutoSelectTier, "maxAutoSelectTier must not be null");
        if (maxAutoSelectTier.isPresent() && maxAutoSelectTier.getAsInt() < 1) {
            throw new IllegalArgumentException(
                    "maxAutoSelectTier must be at least 1, got: " + maxAutoSelectTier.getAsInt());
        }
        Objects.requireNonNull(objective, "objective must not be null");
        RoutingTokens.require(stateMappingVersion, "stateMappingVersion");
    }
}
