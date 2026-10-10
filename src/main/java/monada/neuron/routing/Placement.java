package monada.neuron.routing;

import java.util.Optional;

/** What placed a ranked candidate relative to its neighbour. */
public enum Placement {
    /** It is the only eligible route. */
    ONLY_ELIGIBLE(Optional.empty(), Basis.COLD_START),
    /** Host tier decided. */
    TIER(Optional.of(RoutingRule.TIER), Basis.HOST_PRIORITY),
    /** Host fallback priority decided. */
    FALLBACK_PRIORITY(Optional.of(RoutingRule.FALLBACK_PRIORITY), Basis.HOST_PRIORITY),
    /** Learned preference decided. */
    LEARNED_PREFERENCE(Optional.of(RoutingRule.LEARNED_PREFERENCE), Basis.LEARNED_PREFERENCE),
    /** The resource objective decided. */
    RESOURCE_OBJECTIVE(Optional.of(RoutingRule.RESOURCE_OBJECTIVE), Basis.RESOURCE_OBJECTIVE),
    /** Every earlier rule tied; canonical route order decided. */
    ROUTE_ORDER(Optional.of(RoutingRule.ROUTE_ORDER), Basis.COLD_START);

    private final Optional<RoutingRule> rule;
    private final Basis basis;

    Placement(Optional<RoutingRule> rule, Basis basis) {
        this.rule = rule;
        this.basis = basis;
    }

    /** The ordering rule that placed the candidate; empty for {@link #ONLY_ELIGIBLE}. */
    public Optional<RoutingRule> rule() {
        return rule;
    }

    /** The {@link Basis} a winner placed this way is reported with. */
    public Basis basis() {
        return basis;
    }
}
