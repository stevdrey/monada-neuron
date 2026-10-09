package monada.neuron.routing.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

    /** Defensive immutable copies. */
    public EligibilityReport {
        RouteTokens.require(catalogVersion, "catalogVersion");
        Objects.requireNonNull(eligible, "eligible must not be null");
        Objects.requireNonNull(excluded, "excluded must not be null");
        if ((long) eligible.size() + excluded.size() > RouteCatalog.MAX_ROUTES) {
            throw new IllegalArgumentException("a report covers at most " + RouteCatalog.MAX_ROUTES
                    + " routes, got: " + ((long) eligible.size() + excluded.size()));
        }
        HashSet<RouteKey> seen = HashSet.newHashSet(eligible.size() + excluded.size());
        for (EligibleRoute route : eligible) {
            requireFirstOccurrence(seen, route.key());
        }
        for (Exclusion exclusion : excluded) {
            requireFirstOccurrence(seen, exclusion.key());
        }
        List<EligibleRoute> sortedEligible = new ArrayList<>(eligible);
        sortedEligible.sort(Comparator.comparing(EligibleRoute::key));
        List<Exclusion> sortedExcluded = new ArrayList<>(excluded);
        sortedExcluded.sort(Comparator.comparing(Exclusion::key));
        eligible = List.copyOf(sortedEligible);
        excluded = List.copyOf(sortedExcluded);
    }

    private static void requireFirstOccurrence(HashSet<RouteKey> seen, RouteKey key) {
        if (!seen.add(key)) {
            throw new IllegalArgumentException(
                    "a route may appear once, in exactly one of eligible or excluded: " + key);
        }
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
