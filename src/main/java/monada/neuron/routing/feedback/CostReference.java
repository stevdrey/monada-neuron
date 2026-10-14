package monada.neuron.routing.feedback;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.outcome.RoutingObservation;

import java.util.Objects;

/**
 * A comparison execution for the optional cost refinement. The policy assesses it itself with the same
 * {@code OutcomeEvaluator} and evidence policy, so a caller cannot assert that a peer was validated; it needs the peer's
 * own decision and request, which also supply its stage kind.
 *
 * @param peer observation of the other execution
 * @param peerDecision the {@code Selected} decision the peer answers
 * @param peerRequest the request that produced that decision
 */
public record CostReference(
        RoutingObservation peer, RoutingDecision.Selected peerDecision, RoutingRequest peerRequest) {

    /** Requires all parts. */
    public CostReference {
        Objects.requireNonNull(peer, "peer must not be null");
        Objects.requireNonNull(peerDecision, "peerDecision must not be null");
        Objects.requireNonNull(peerRequest, "peerRequest must not be null");
    }
}
