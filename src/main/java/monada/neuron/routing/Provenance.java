package monada.neuron.routing;

import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;
import java.util.Optional;

/**
 * Common provenance of every decision variant.
 *
 * @param decisionRef identity of the decision
 * @param catalogVersion catalog snapshot that was evaluated
 * @param policyId routing policy id
 * @param policyVersion routing policy version
 * @param cutoff request cutoff
 * @param state identity of the preference snapshot
 * @param validation admission result of that snapshot
 * @param parameters configuration of the policy that decided
 * @param stageKind stage kind of the request the decision answers; empty for a decision that did not record it
 *         (such a decision cannot earn routing feedback, because the stage cannot be verified)
 */
public record Provenance(
        DecisionRef decisionRef,
        String catalogVersion,
        String policyId,
        String policyVersion,
        long cutoff,
        StateBinding state,
        StateValidation validation,
        PolicyParameters parameters,
        Optional<String> stageKind) {

    /** A provenance that does not record the stage kind (decisions written before it was recorded). */
    public Provenance(
            DecisionRef decisionRef,
            String catalogVersion,
            String policyId,
            String policyVersion,
            long cutoff,
            StateBinding state,
            StateValidation validation,
            PolicyParameters parameters) {
        this(decisionRef, catalogVersion, policyId, policyVersion, cutoff, state, validation, parameters,
                Optional.empty());
    }

    /** Validates parts. */
    public Provenance {
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        OpaqueTokens.require(catalogVersion, "catalogVersion");
        OpaqueTokens.require(policyId, "policyId");
        OpaqueTokens.require(policyVersion, "policyVersion");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must be non-negative, got: " + cutoff);
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(validation, "validation must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        stageKind.ifPresent(kind -> OpaqueTokens.require(kind, "stageKind"));
        if (validation instanceof StateValidation.Compatible
                && (!state.mappingVersion().equals(parameters.stateMappingVersion())
                        || !state.featureSchemaVersion().equals(parameters.stateFeatureSchemaVersion()))) {
            throw new IllegalArgumentException("a compatible state was built under mapping " + state.mappingVersion()
                    + " and schema " + state.featureSchemaVersion() + " but the policy parameters record "
                    + parameters.stateMappingVersion() + " and " + parameters.stateFeatureSchemaVersion());
        }
    }
}
