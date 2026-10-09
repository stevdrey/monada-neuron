package monada.neuron.routing;

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
        RoutingTokens.require(scopeId, "scopeId");
        RoutingTokens.require(featureSchemaVersion, "featureSchemaVersion");
        RoutingTokens.require(evaluationPolicyId, "evaluationPolicyId");
        RoutingTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
        RoutingTokens.require(mappingVersion, "mappingVersion");
        RoutingTokens.require(producedByPolicyId, "producedByPolicyId");
        RoutingTokens.require(producedByPolicyVersion, "producedByPolicyVersion");
        if (processedCutoff < 0) {
            throw new IllegalArgumentException("processedCutoff must be non-negative, got: " + processedCutoff);
        }
    }
}
