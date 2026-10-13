package monada.neuron.routing;

import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;

/**
 * Identity of the exact decision a later outcome is matched to (contract section 3.2).
 *
 * @param scopeId scope token
 * @param taskId task token
 * @param executionId execution token
 * @param attemptId attempt token
 * @param stageId stage instance token
 * @param requestOrdinal caller-issued, non-negative request ordinal
 */
public record DecisionRef(
        String scopeId, String taskId, String executionId, String attemptId, String stageId, long requestOrdinal) {

    /** Validates tokens and the ordinal. */
    public DecisionRef {
        OpaqueTokens.require(scopeId, "scopeId");
        OpaqueTokens.require(taskId, "taskId");
        OpaqueTokens.require(executionId, "executionId");
        OpaqueTokens.require(attemptId, "attemptId");
        OpaqueTokens.require(stageId, "stageId");
        if (requestOrdinal < 0) {
            throw new IllegalArgumentException("requestOrdinal must be non-negative, got: " + requestOrdinal);
        }
    }

    /** Copies the identity fields of a request. */
    public static DecisionRef of(RoutingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return new DecisionRef(request.scopeId(), request.taskId(), request.executionId(), request.attemptId(),
                request.stageId(), request.requestOrdinal());
    }
}
