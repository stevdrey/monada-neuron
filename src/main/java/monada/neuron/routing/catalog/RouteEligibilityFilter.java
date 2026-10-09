package monada.neuron.routing.catalog;

import monada.neuron.routing.features.Feature;

import java.util.ArrayList;
import java.util.EnumSet;
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
            EnumSet<EligibilityReason> violated = EnumSet.noneOf(EligibilityReason.class);
            if (!RouteTokens.containsAll(route.stages(), List.of(request.stageKind()))) {
                violated.add(EligibilityReason.STAGE_INCOMPATIBLE);
            }
            switch (entry.availability()) {
                case UNAVAILABLE -> violated.add(EligibilityReason.ROUTE_UNAVAILABLE);
                case UNKNOWN -> violated.add(EligibilityReason.AVAILABILITY_UNKNOWN);
                case AVAILABLE -> { }
            }
            String mode = RouteTokens.firstCommon(need.permittedModes(), route.executionModes());
            if (mode == null) {
                violated.add(EligibilityReason.MODE_NOT_PERMITTED);
            }
            if (!(route.locality() instanceof Feature.Known<String> locality)
                    || !RouteTokens.containsAll(need.allowedLocalities(), List.of(locality.value()))) {
                violated.add(EligibilityReason.LOCALITY_NOT_PERMITTED);
            }
            if (route.overflowClass() == OverflowClass.OVERFLOW && !request.overflowPermitted()) {
                violated.add(EligibilityReason.OVERFLOW_NOT_PERMITTED);
            }
            if (!RouteTokens.containsAll(route.capabilities(), need.requiredCapabilities())
                    || !RouteTokens.containsAll(route.tools(), need.requiredTools())) {
                violated.add(EligibilityReason.MISSING_CAPABILITY);
            }
            if (need.requiredContextSize() > 0) {
                switch (route.contextCeiling()) {
                    case Feature.Known<Long> ceiling when ceiling.value() < need.requiredContextSize() ->
                            violated.add(EligibilityReason.REQUIRED_LIMIT_EXCEEDED);
                    case Feature.Known<Long> ceiling -> { }
                    case Feature.Unknown<Long> unknown -> violated.add(EligibilityReason.REQUIRED_LIMIT_UNKNOWN);
                }
            }
            if (violated.isEmpty()) {
                eligible.add(new EligibilityReport.EligibleRoute(route.key(), mode,
                        route.overflowClass() == OverflowClass.OVERFLOW));
            } else {
                List<EligibilityReason> ordered = new ArrayList<>(violated);
                excluded.add(new EligibilityReport.Exclusion(
                        route.key(), ordered.getFirst(), ordered.subList(1, ordered.size())));
            }
        }
        return new EligibilityReport(catalog.catalogVersion(), eligible, excluded);
    }
}
