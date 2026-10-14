package monada.neuron.routing.feedback;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;

/**
 * Stable identity of one derived feedback: the exact sample revision it answers, the decision and route it credits and
 * the state definition and evaluation policy it was derived under. It is built from exact components compared by
 * equality, never hashed, generated or parsed, so equal inputs always give an equal identity.
 *
 * @param experienceRef opaque sample reference of the observation
 * @param revision sample revision
 * @param ledgerSequence ledger sequence of that revision
 * @param decisionRef decision the outcome answers
 * @param route executed route
 * @param mappingVersion composite state-definition version
 * @param evaluationPolicyId evaluation policy id
 * @param evaluationPolicyVersion evaluation policy version
 */
public record FeedbackIdentity(
        String experienceRef,
        long revision,
        long ledgerSequence,
        DecisionRef decisionRef,
        RouteKey route,
        String mappingVersion,
        String evaluationPolicyId,
        String evaluationPolicyVersion) {

    /** Validates the parts. */
    public FeedbackIdentity {
        OpaqueTokens.requireText(experienceRef, "experienceRef");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative, got: " + revision);
        }
        if (ledgerSequence < 0) {
            throw new IllegalArgumentException("ledgerSequence must be non-negative, got: " + ledgerSequence);
        }
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        Objects.requireNonNull(route, "route must not be null");
        OpaqueTokens.require(mappingVersion, "mappingVersion");
        OpaqueTokens.require(evaluationPolicyId, "evaluationPolicyId");
        OpaqueTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
    }
}
