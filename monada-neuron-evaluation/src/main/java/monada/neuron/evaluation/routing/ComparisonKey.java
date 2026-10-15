package monada.neuron.evaluation.routing;

import monada.neuron.routing.RoutingPolicy;
import monada.neuron.routing.catalog.RoutingRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What two executions must share to be compared directly (execution-memory contract v1 section 10, Store #100): the
 * same scope, task, source revision, context, constraints, evaluation policy and routing policy. Anything else is an
 * observational cohort comparison, never a paired one.
 *
 * @param scopeId scope
 * @param taskId task
 * @param sourceFingerprint source revision fingerprint
 * @param contextFingerprint gathered-context fingerprint
 * @param constraintsFingerprint constraints fingerprint
 * @param evaluationPolicy evaluation policy {@code id@version}
 * @param routingPolicy routing policy {@code id@version}
 */
public record ComparisonKey(
        String scopeId,
        String taskId,
        String sourceFingerprint,
        String contextFingerprint,
        String constraintsFingerprint,
        String evaluationPolicy,
        String routingPolicy) {

    /** Validates the fields. */
    public ComparisonKey {
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint must not be null");
        Objects.requireNonNull(contextFingerprint, "contextFingerprint must not be null");
        Objects.requireNonNull(constraintsFingerprint, "constraintsFingerprint must not be null");
        Objects.requireNonNull(evaluationPolicy, "evaluationPolicy must not be null");
        Objects.requireNonNull(routingPolicy, "routingPolicy must not be null");
    }

    /** The key of a request routed by a policy. */
    public static ComparisonKey of(RoutingRequest request, RoutingPolicy policy) {
        return new ComparisonKey(request.scopeId(), request.taskId(), request.sourceFingerprint(),
                request.contextFingerprint(), request.constraintsFingerprint(),
                request.evaluationPolicyId() + "@" + request.evaluationPolicyVersion(),
                policy.policyId() + "@" + policy.policyVersion());
    }

    /** Names of the fields that differ from {@code other}, in declaration order; empty when directly comparable. */
    public List<String> differingFields(ComparisonKey other) {
        var fields = new ArrayList<String>(7);
        if (!scopeId.equals(other.scopeId)) {
            fields.add("scope");
        }
        if (!taskId.equals(other.taskId)) {
            fields.add("task");
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
        if (!evaluationPolicy.equals(other.evaluationPolicy)) {
            fields.add("evaluationPolicy");
        }
        if (!routingPolicy.equals(other.routingPolicy)) {
            fields.add("routingPolicy");
        }
        return List.copyOf(fields);
    }
}
