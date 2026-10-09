package monada.neuron.routing.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Result of eligibility filtering: every catalog route is either eligible or excluded with structured reasons.
 *
 * <p>This is not a {@code RoutingDecision} and does not rank. It reserves nothing and grants no authorization;
 * Forge must revalidate authorization, availability and quota before executing any route. Construction stores both
 * lists in canonical route order, so equivalent inputs give equal reports, and rejects a route key that appears more
 * than once or in both lists.
 *
 * @param catalogVersion the snapshot that was evaluated
 * @param eligible routes that satisfy every hard constraint
 * @param excluded every other route, each with a primary reason and additional reasons
 */
public record EligibilityReport(String catalogVersion, List<EligibleRoute> eligible, List<Exclusion> excluded) {

    private static final Comparator<EligibleRoute> ELIGIBLE_ORDER = Comparator.comparing(EligibleRoute::key);

    private static final Comparator<Exclusion> EXCLUDED_ORDER = Comparator.comparing(Exclusion::key);

    /** Validates bounds, key uniqueness and canonicalizes order; the stored lists are immutable. */
    public EligibilityReport {
        RouteTokens.require(catalogVersion, "catalogVersion");
        Objects.requireNonNull(eligible, "eligible must not be null");
        Objects.requireNonNull(excluded, "excluded must not be null");
        if ((long) eligible.size() + excluded.size() > RouteCatalog.MAX_ROUTES) {
            throw new IllegalArgumentException("a report covers at most " + RouteCatalog.MAX_ROUTES
                    + " routes, got: " + ((long) eligible.size() + excluded.size()));
        }
        eligible = RouteTokens.canonicalOrder(eligible, ELIGIBLE_ORDER, duplicate -> {
            throw repeated(duplicate.key());
        });
        excluded = RouteTokens.canonicalOrder(excluded, EXCLUDED_ORDER, duplicate -> {
            throw repeated(duplicate.key());
        });
        int i = 0;
        int j = 0;
        while (i < eligible.size() && j < excluded.size()) {
            int order = eligible.get(i).key().compareTo(excluded.get(j).key());
            if (order == 0) {
                throw repeated(eligible.get(i).key());
            }
            if (order < 0) {
                i++;
            } else {
                j++;
            }
        }
    }

    private static IllegalArgumentException repeated(RouteKey key) {
        return new IllegalArgumentException(
                "a route may appear once, in exactly one of eligible or excluded: " + key);
    }

    /** True when no route is eligible (the contract's {@code NoEligibleRoute} condition). */
    public boolean noneEligible() {
        return eligible.isEmpty();
    }

    /**
     * An eligible route.
     *
     * @param key route identity
     * @param executionMode first permitted mode in code point order that the route offers
     * @param overflow whether the route is an overflow route (eligible only because the host permitted it)
     */
    public record EligibleRoute(RouteKey key, String executionMode, boolean overflow) {

        /** Requires non-null parts. */
        public EligibleRoute {
            Objects.requireNonNull(key, "key must not be null");
            RouteTokens.require(executionMode, "executionMode");
        }
    }

    /**
     * An excluded route.
     *
     * @param key route identity
     * @param primaryReason first violated constraint in contract precedence order
     * @param additionalReasons every other violated constraint, at most 8, unique and in strictly increasing
     *         precedence order after the primary reason
     */
    public record Exclusion(RouteKey key, EligibilityReason primaryReason, List<EligibilityReason> additionalReasons) {

        /** Maximum additional reasons per route. */
        public static final int MAX_ADDITIONAL = 8;

        /** Validates and copies. */
        public Exclusion {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(primaryReason, "primaryReason must not be null");
            Objects.requireNonNull(additionalReasons, "additionalReasons must not be null");
            if (additionalReasons.size() > MAX_ADDITIONAL) {
                throw new IllegalArgumentException("at most " + MAX_ADDITIONAL + " additional reasons");
            }
            EligibilityReason previous = primaryReason;
            for (EligibilityReason reason : additionalReasons) {
                Objects.requireNonNull(reason, "additional reason must not be null");
                if (reason.compareTo(previous) <= 0) {
                    throw new IllegalArgumentException("additionalReasons must be unique and strictly later than "
                            + previous + " in precedence order, got: " + reason);
                }
                previous = reason;
            }
            additionalReasons = List.copyOf(additionalReasons);
        }
    }
}
