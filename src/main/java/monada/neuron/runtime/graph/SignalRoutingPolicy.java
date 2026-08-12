package monada.neuron.runtime.graph;

import monada.neuron.model.NodeView;
import monada.neuron.signal.Signal;

/** Decides whether one emitted signal should traverse one directed node connection. */
@FunctionalInterface
public interface SignalRoutingPolicy {

    /**
     * Returns whether {@code signal} should be delivered from {@code source} to {@code target}.
     *
     * <p>Implementations should be deterministic and must not mutate either node.
     *
     * @param source node that emitted the signal
     * @param target connected node that may receive the signal
     * @param signal emitted signal being considered
     * @return {@code true} when the runtime should enqueue the delivery
     */
    boolean shouldRoute(NodeView source, NodeView target, Signal signal);

    /**
     * Returns the shared policy that accepts every emitted-signal/target pair.
     *
     * @return route-all policy
     */
    static SignalRoutingPolicy routeAll() {
        return RouteAllRoutingPolicy.INSTANCE;
    }
}

/** Shared stateless implementation backing {@link SignalRoutingPolicy#routeAll()}. */
final class RouteAllRoutingPolicy implements SignalRoutingPolicy {

    static final RouteAllRoutingPolicy INSTANCE = new RouteAllRoutingPolicy();

    private RouteAllRoutingPolicy() {
    }

    @Override
    public boolean shouldRoute(NodeView source, NodeView target, Signal signal) {
        return true;
    }
}
