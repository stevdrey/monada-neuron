package monada.neuron.evaluation.routing;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.catalog.RoutingRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What two executions must share to be compared directly. The routing contract ("Direct comparable pairs", Section 6)
 * requires the same scope, task, {@code stageKind}, source, context and constraints fingerprints and evaluation policy
 * id and version, plus any stage-specific objective binding: the Store v1 Section 10 key plus {@code stageKind},
 * because plan, implement, review and QA executions of one task have different objectives, evidence and resource
 * profiles. The replay also requires the same routing policy. Anything else is an observational cohort comparison,
 * never a paired one.
 *
 * <p>Every identity is its own component, never a concatenation: opaque tokens may contain any separator, so
 * {@code ("a@b", "c")} and {@code ("a", "b@c")} must stay different policies.
 *
 * @param scopeId scope
 * @param taskId task
 * @param stageKind routed stage kind
 * @param sourceFingerprint source revision fingerprint
 * @param contextFingerprint gathered-context fingerprint
 * @param constraintsFingerprint constraints fingerprint
 * @param evaluationPolicyId evaluation policy id
 * @param evaluationPolicyVersion evaluation policy version
 * @param routingPolicyId routing policy id
 * @param routingPolicyVersion routing policy version
 * @param objective the routing policy's resource objective, the replay's only stage-objective binding
 */
public record ComparisonKey(
        String scopeId,
        String taskId,
        String stageKind,
        String sourceFingerprint,
        String contextFingerprint,
        String constraintsFingerprint,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        String routingPolicyId,
        String routingPolicyVersion,
        Optional<ResourceObjective> objective) {

    /** Validates the fields. */
    public ComparisonKey {
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint must not be null");
        Objects.requireNonNull(contextFingerprint, "contextFingerprint must not be null");
        Objects.requireNonNull(constraintsFingerprint, "constraintsFingerprint must not be null");
        Objects.requireNonNull(evaluationPolicyId, "evaluationPolicyId must not be null");
        Objects.requireNonNull(evaluationPolicyVersion, "evaluationPolicyVersion must not be null");
        Objects.requireNonNull(routingPolicyId, "routingPolicyId must not be null");
        Objects.requireNonNull(routingPolicyVersion, "routingPolicyVersion must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
    }

    /** The key of a request routed by a policy. */
    public static ComparisonKey of(RoutingRequest request, LexicographicRoutingPolicy policy) {
        return new ComparisonKey(request.scopeId(), request.taskId(), request.stageKind(), request.sourceFingerprint(),
                request.contextFingerprint(), request.constraintsFingerprint(), request.evaluationPolicyId(),
                request.evaluationPolicyVersion(), policy.policyId(), policy.policyVersion(),
                policy.parameters().objective());
    }

    /** Names of the fields that differ from {@code other}, in declaration order; empty when directly comparable. */
    public List<String> differingFields(ComparisonKey other) {
        var fields = new ArrayList<String>(9);
        if (!scopeId.equals(other.scopeId)) {
            fields.add("scope");
        }
        if (!taskId.equals(other.taskId)) {
            fields.add("task");
        }
        if (!stageKind.equals(other.stageKind)) {
            fields.add("stageKind");
        }
        if (!sourceFingerprint.equals(other.sourceFingerprint)) {
            fields.add("source");
        }
        if (!contextFingerprint.equals(other.contextFingerprint)) {
            fields.add("context");
        }
        if (!constraintsFingerprint.equals(other.constraintsFingerprint)) {
            fields.add("constraints");
        }
        if (!evaluationPolicyId.equals(other.evaluationPolicyId)
                || !evaluationPolicyVersion.equals(other.evaluationPolicyVersion)) {
            fields.add("evaluationPolicy");
        }
        if (!routingPolicyId.equals(other.routingPolicyId) || !routingPolicyVersion.equals(other.routingPolicyVersion)) {
            fields.add("routingPolicy");
        }
        if (!objective.equals(other.objective)) {
            fields.add("objective");
        }
        return List.copyOf(fields);
    }
}
