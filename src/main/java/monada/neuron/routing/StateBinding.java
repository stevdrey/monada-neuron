package monada.neuron.routing;

import monada.neuron.routing.token.OpaqueTokens;

/**
 * The preference snapshot's identity as recorded on every decision, so an abstention or a no-route decision can be
 * replayed without host-side state.
 *
 * @param scopeId snapshot scope
 * @param featureSchemaVersion feature schema the snapshot was built for
 * @param evaluationPolicyId evaluation policy id the snapshot is partitioned by
 * @param evaluationPolicyVersion evaluation policy version
 * @param mappingVersion state-transition definition version
 * @param processedCutoff export/rebuild watermark
 * @param producedByPolicyId routing policy that produced the snapshot (provenance, not an admission key)
 * @param producedByPolicyVersion version of that routing policy
 */
public record StateBinding(
        String scopeId,
        String featureSchemaVersion,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        String mappingVersion,
        long processedCutoff,
        String producedByPolicyId,
        String producedByPolicyVersion) {

    /** Validates tokens. */
    public StateBinding {
        OpaqueTokens.require(scopeId, "scopeId");
        OpaqueTokens.require(featureSchemaVersion, "featureSchemaVersion");
        OpaqueTokens.require(evaluationPolicyId, "evaluationPolicyId");
        OpaqueTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
        OpaqueTokens.require(mappingVersion, "mappingVersion");
        OpaqueTokens.require(producedByPolicyId, "producedByPolicyId");
        OpaqueTokens.require(producedByPolicyVersion, "producedByPolicyVersion");
        if (processedCutoff < 0) {
            throw new IllegalArgumentException("processedCutoff must be non-negative, got: " + processedCutoff);
        }
    }
}
