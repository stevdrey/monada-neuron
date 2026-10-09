package monada.neuron.routing.catalog;

import monada.neuron.routing.features.Feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, fail-closed hard-constraint filter over an immutable {@link RouteCatalog}.
 *
 * <p>Stateless and side-effect free: no clock, randomness, ranking, learned preference, discovery or quota lookup.
 * Every route is evaluated against every constraint in the contract's precedence order, so equal inputs give equal
 * reports whatever the catalog input order. Time is {@code O(R*Q)} for {@code R} routes and {@code Q} requirement
 * tokens (all sets are sorted, so subset and intersection checks are linear merges) and extra space is {@code O(R)}
 * for the report. Selection reserves nothing: Forge must revalidate before executing.
 */
public final class RouteEligibilityFilter {

    private static final EligibilityReason[] REASONS = EligibilityReason.values();

    private static int bit(EligibilityReason reason) {
        return 1 << reason.ordinal();
    }

    /** Lowest set bit (highest precedence) is primary; the remaining bits follow in precedence order. */
    private static EligibilityReport.Exclusion exclusion(RouteKey key, int violated) {
        EligibilityReason primary = REASONS[Integer.numberOfTrailingZeros(violated)];
        int rest = violated & (violated - 1);
        if (rest == 0) {
            return new EligibilityReport.Exclusion(key, primary, List.of());
        }
        List<EligibilityReason> additional = new ArrayList<>(Integer.bitCount(rest));
        for (; rest != 0; rest &= rest - 1) {
            additional.add(REASONS[Integer.numberOfTrailingZeros(rest)]);
        }
        return new EligibilityReport.Exclusion(key, primary, additional);
    }

    /**
     * Partitions the catalog into eligible and excluded routes.
     *
     * @param request host request with hard requirements
     * @param catalog immutable catalog snapshot
     * @return a report that accounts for every catalog route exactly once
     */
    public EligibilityReport evaluate(RoutingRequest request, RouteCatalog catalog) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        HardRequirements need = request.requirements();
        List<EligibilityReport.EligibleRoute> eligible = new ArrayList<>(catalog.entries().size());
        List<EligibilityReport.Exclusion> excluded = new ArrayList<>(catalog.entries().size());
        for (CatalogEntry entry : catalog.entries()) {
            RouteDescriptor route = entry.descriptor();
            int violated = 0;
            if (!RouteTokens.contains(route.stages(), request.stageKind())) {
                violated |= bit(EligibilityReason.STAGE_INCOMPATIBLE);
            }
            switch (entry.availability()) {
                case UNAVAILABLE -> violated |= bit(EligibilityReason.ROUTE_UNAVAILABLE);
                case UNKNOWN -> violated |= bit(EligibilityReason.AVAILABILITY_UNKNOWN);
                case AVAILABLE -> { }
            }
            String mode = RouteTokens.firstCommon(need.permittedModes(), route.executionModes());
            if (mode == null) {
                violated |= bit(EligibilityReason.MODE_NOT_PERMITTED);
            }
            if (!(route.locality() instanceof Feature.Known<String> locality)
                    || !RouteTokens.contains(need.allowedLocalities(), locality.value())) {
                violated |= bit(EligibilityReason.LOCALITY_NOT_PERMITTED);
            }
            if (route.overflowClass() == OverflowClass.OVERFLOW && !request.overflowPermitted()) {
                violated |= bit(EligibilityReason.OVERFLOW_NOT_PERMITTED);
            }
            if (!RouteTokens.containsAll(route.capabilities(), need.requiredCapabilities())
                    || !RouteTokens.containsAll(route.tools(), need.requiredTools())) {
                violated |= bit(EligibilityReason.MISSING_CAPABILITY);
            }
            if (need.requiredContextSize() > 0) {
                switch (route.contextCeiling()) {
                    case Feature.Known<Long> ceiling when ceiling.value() < need.requiredContextSize() ->
                            violated |= bit(EligibilityReason.REQUIRED_LIMIT_EXCEEDED);
                    case Feature.Known<Long> ceiling -> { }
                    case Feature.Unknown<Long> unknown -> violated |= bit(EligibilityReason.REQUIRED_LIMIT_UNKNOWN);
                }
            }
            if (violated == 0) {
                eligible.add(new EligibilityReport.EligibleRoute(route.key(), mode,
                        route.overflowClass() == OverflowClass.OVERFLOW));
            } else {
                excluded.add(exclusion(route.key(), violated));
            }
        }
        return new EligibilityReport(catalog.catalogVersion(), eligible, excluded);
    }
}
