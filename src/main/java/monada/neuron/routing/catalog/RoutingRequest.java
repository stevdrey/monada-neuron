package monada.neuron.routing.catalog;

import monada.neuron.routing.features.TaskFeatures;

import java.util.Objects;

/**
 * One stage routing request: typed envelope, provenance, task features and host-policy hard requirements.
 *
 * <p>All identifiers, fingerprints, the ordinal and the cutoff are caller-supplied opaque values; nothing here is
 * generated, parsed or read from a clock. {@code overflowPermitted} has no default: the host must pass it explicitly,
 * {@code false} unless it authorizes overflow, and Neuron never infers authorization to spend. The typed {@link TaskFeatures} travel beside the
 * constraints and are never used to decide eligibility.
 *
 * @param contractVersion must be {@value #CONTRACT_VERSION}
 * @param scopeId scope token
 * @param taskId task token
 * @param executionId execution token
 * @param attemptId attempt token
 * @param stageId stage instance token
 * @param stageKind stage kind token matched against route stages
 * @param requestOrdinal caller-issued ordinal, non-negative
 * @param sourceFingerprint source revision fingerprint
 * @param contextFingerprint gathered-context fingerprint
 * @param constraintsFingerprint host-constraints fingerprint
 * @param evaluationPolicyId evaluation policy id
 * @param evaluationPolicyVersion evaluation policy version token
 * @param features task features
 * @param requirements host hard requirements
 * @param overflowPermitted whether the host permits overflow routes
 * @param cutoff caller-supplied inclusive ledger cutoff, non-negative
 */
public record RoutingRequest(
        String contractVersion,
        String scopeId,
        String taskId,
        String executionId,
        String attemptId,
        String stageId,
        String stageKind,
        long requestOrdinal,
        String sourceFingerprint,
        String contextFingerprint,
        String constraintsFingerprint,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        TaskFeatures features,
        HardRequirements requirements,
        boolean overflowPermitted,
        long cutoff) {

    /** The only contract identifier accepted by v1. */
    public static final String CONTRACT_VERSION = "forge-routing/1";

    /** Validates tokens, the contract version and ordinals. */
    public RoutingRequest {
        Objects.requireNonNull(contractVersion, "contractVersion must not be null");
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported contractVersion: " + contractVersion);
        }
        RouteTokens.require(scopeId, "scopeId");
        RouteTokens.require(taskId, "taskId");
        RouteTokens.require(executionId, "executionId");
        RouteTokens.require(attemptId, "attemptId");
        RouteTokens.require(stageId, "stageId");
        RouteTokens.require(stageKind, "stageKind");
        RouteTokens.require(sourceFingerprint, "sourceFingerprint");
        RouteTokens.require(contextFingerprint, "contextFingerprint");
        RouteTokens.require(constraintsFingerprint, "constraintsFingerprint");
        RouteTokens.require(evaluationPolicyId, "evaluationPolicyId");
        RouteTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
        Objects.requireNonNull(features, "features must not be null");
        Objects.requireNonNull(requirements, "requirements must not be null");
        if (requestOrdinal < 0) {
            throw new IllegalArgumentException("requestOrdinal must be non-negative, got: " + requestOrdinal);
        }
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must be non-negative, got: " + cutoff);
        }
    }
}
