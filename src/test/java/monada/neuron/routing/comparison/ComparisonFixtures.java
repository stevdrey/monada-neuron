package monada.neuron.routing.comparison;

import monada.neuron.routing.CohortPreference;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.TrustedRoutingProfile;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.OverflowClass;
import monada.neuron.routing.catalog.ResourceEstimate;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Real scenarios decided by the reference policy, so comparison tests exercise trusted verification (ids invented). */
final class ComparisonFixtures {

    static final RouteKey A = route(0);
    static final RouteKey B = route(1);
    static final RouteKey C = route(2);

    static final LexicographicRoutingPolicy REFERENCE = LexicographicRoutingPolicy.reference();

    static final LexicographicRoutingPolicy COST = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
            LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.empty(),
            Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE)));

    /** Advises no route above tier 1 without host confirmation. */
    static final LexicographicRoutingPolicy TIER_ONE = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
            LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.of(1), Optional.empty());

    private ComparisonFixtures() {
    }

    static RouteKey route(int i) {
        return new RouteKey(String.format("route-%02d", i), 1);
    }

    /** A request for stage {@code implement} at cutoff 50 that requires capability {@code java}. */
    static Request request() {
        return new Request("scope-demo", "task-17", "implement", "task-features/1", "forge-gates", "1", 50L,
                List.of("java"), false, "exec-1", "con-1");
    }

    /** Request fields a test varies; the rest are fixed. */
    record Request(String scopeId, String taskId, String stageKind, String schemaVersion, String evaluationPolicyId,
            String evaluationPolicyVersion, long cutoff, List<String> capabilities, boolean overflowPermitted,
            String executionId, String constraintsFingerprint) {

        Request scope(String value) {
            return new Request(value, taskId, stageKind, schemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    cutoff, capabilities, overflowPermitted, executionId, constraintsFingerprint);
        }

        Request task(String value) {
            return new Request(scopeId, value, stageKind, schemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    cutoff, capabilities, overflowPermitted, executionId, constraintsFingerprint);
        }

        Request stage(String value) {
            return new Request(scopeId, taskId, value, schemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    cutoff, capabilities, overflowPermitted, executionId, constraintsFingerprint);
        }

        Request evaluationPolicy(String id, String version) {
            return new Request(scopeId, taskId, stageKind, schemaVersion, id, version, cutoff, capabilities,
                    overflowPermitted, executionId, constraintsFingerprint);
        }

        Request cutoff(long value) {
            return new Request(scopeId, taskId, stageKind, schemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    value, capabilities, overflowPermitted, executionId, constraintsFingerprint);
        }

        /** A changed requirement also changes the host's constraints fingerprint, as a host mints it. */
        Request capabilities(List<String> value, String fingerprint) {
            return new Request(scopeId, taskId, stageKind, schemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    cutoff, value, overflowPermitted, executionId, fingerprint);
        }

        Request overflow(boolean value) {
            return new Request(scopeId, taskId, stageKind, schemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    cutoff, capabilities, value, executionId, constraintsFingerprint);
        }

        Request execution(String value) {
            return new Request(scopeId, taskId, stageKind, schemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    cutoff, capabilities, overflowPermitted, value, constraintsFingerprint);
        }

        RoutingRequest build() {
            return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, scopeId, taskId, executionId, "att-1",
                    "s-impl", stageKind, 101L, "src-1", "ctx-1", constraintsFingerprint, evaluationPolicyId,
                    evaluationPolicyVersion, TaskFeatures.builder().stageKind(stageKind).build(),
                    new HardRequirements(capabilities, List.of(), List.of("hosted"), List.of("sandboxed"), 0L),
                    overflowPermitted, cutoff);
        }
    }

    /** A route offering capability {@code java} for stages {@code implement} and {@code review}. */
    static CatalogEntry entry(RouteKey key, int tier, int fallbackPriority, Availability availability) {
        return new CatalogEntry(RouteDescriptor.builder(key.routeId(), key.routeVersion())
                .stages(List.of("implement", "review"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .capabilities(List.of("java"))
                .tier(tier)
                .build(), availability, fallbackPriority);
    }

    /** An available overflow route, eligible only when the host permits overflow. */
    static CatalogEntry overflow(RouteKey key, int tier, int fallbackPriority) {
        return new CatalogEntry(RouteDescriptor.builder(key.routeId(), key.routeVersion())
                .stages(List.of("implement", "review"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .capabilities(List.of("java"))
                .tier(tier)
                .overflowClass(OverflowClass.OVERFLOW)
                .build(), Availability.AVAILABLE, fallbackPriority);
    }

    /** The standing of a fixture route the filter admits in mode {@code sandboxed}, as a primary route. */
    static RouteStanding eligible(RouteKey key) {
        return new RouteStanding.Eligible(new EligibilityReport.EligibleRoute(key, "sandboxed", false));
    }

    /** The standing of a fixture route the filter admits in mode {@code sandboxed}, only as overflow. */
    static RouteStanding eligibleOverflow(RouteKey key) {
        return new RouteStanding.Eligible(new EligibilityReport.EligibleRoute(key, "sandboxed", true));
    }

    static CatalogEntry entry(RouteKey key, int tier, int fallbackPriority) {
        return entry(key, tier, fallbackPriority, Availability.AVAILABLE);
    }

    /** Routes A, B and C in tier 1, preferred in that order by fallback priority. */
    static RouteCatalog catalog() {
        return RouteCatalog.of("cat-1", List.of(entry(A, 1, 0), entry(B, 1, 1), entry(C, 1, 2)));
    }

    static RouteCatalog catalog(String version, CatalogEntry... entries) {
        return RouteCatalog.of(version, List.of(entries));
    }

    static RouteCatalog withCosts(String version, List<CatalogEntry> entries, List<ResourceEstimate> estimates) {
        return new RouteCatalog(version, entries, estimates);
    }

    static ResourceEstimate cost(RouteKey key, ResourceValue value) {
        return new ResourceEstimate(key, "cost", "usd-cents", value);
    }

    /** {@code count} tier 1 routes, enough to truncate a ranking above eight. */
    static RouteCatalog wide(String version, int count) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry(route(i), 1, i));
        }
        return RouteCatalog.of(version, entries);
    }

    static RoutingPreference cold(RoutingRequest request) {
        return RoutingPreference.empty(request, RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.POLICY_ID, LexicographicRoutingPolicy.POLICY_VERSION);
    }

    static RoutingPreference learned(RoutingRequest request, long processedCutoff, CohortPreference... cohorts) {
        return new RoutingPreference(request.scopeId(), request.features().schemaVersion(),
                request.evaluationPolicyId(), request.evaluationPolicyVersion(),
                RoutingStateDefinition.reference().mappingVersion(), LexicographicRoutingPolicy.POLICY_ID,
                LexicographicRoutingPolicy.POLICY_VERSION, processedCutoff, List.of(cohorts));
    }

    /** A cohort of the default request (stage {@code implement}, change size unknown). */
    static CohortPreference cohort(RouteKey key, double value, int support) {
        return new CohortPreference("implement", "UNKNOWN", key, value, support);
    }

    static RoutingScenario scenario(LexicographicRoutingPolicy policy, RoutingInput input) {
        return new RoutingScenario(policy, TrustedRoutingProfile.lexicographic(policy), input);
    }

    /** A cold reference scenario. */
    static RoutingScenario scenario(Request request, RouteCatalog catalog) {
        RoutingRequest built = request.build();
        return scenario(REFERENCE, new RoutingInput(built, catalog, cold(built)));
    }
}
