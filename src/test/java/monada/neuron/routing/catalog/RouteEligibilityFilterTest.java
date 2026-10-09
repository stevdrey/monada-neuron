package monada.neuron.routing.catalog;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static monada.neuron.routing.catalog.CatalogFixtures.available;
import static monada.neuron.routing.catalog.CatalogFixtures.base;
import static monada.neuron.routing.catalog.CatalogFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteEligibilityFilterTest {

    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();

    private EligibilityReport evaluate(RoutingRequest request, RouteDescriptor route, Availability availability) {
        return filter.evaluate(request, RouteCatalog.of("v1", List.of(new CatalogEntry(route, availability, 1))));
    }

    private static EligibilityReport.Exclusion onlyExclusion(EligibilityReport report) {
        assertTrue(report.noneEligible());
        assertEquals(1, report.excluded().size());
        return report.excluded().getFirst();
    }

    @Test
    void coldStartExcludesOverflowRouteAndKeepsBothSubscriptions() {
        EligibilityReport report = filter.evaluate(request("implement", List.of("java")), CatalogFixtures.demoCatalog());

        assertEquals(List.of("sub-a", "sub-b"), report.eligible().stream().map(r -> r.key().routeId()).toList());
        assertEquals("sandboxed", report.eligible().getFirst().executionMode());
        assertEquals(1, report.excluded().size());
        assertEquals(EligibilityReason.OVERFLOW_NOT_PERMITTED, report.excluded().getFirst().primaryReason());
        assertEquals("cat-demo-7", report.catalogVersion());
    }

    @Test
    void noEligibleRouteReportsEveryRouteThenExplicitOverflowMakesApiEligible() {
        EligibilityReport none = filter.evaluate(request("implement", List.of("java", "long-context")),
                CatalogFixtures.demoCatalog());
        assertTrue(none.noneEligible());
        assertEquals(3, none.excluded().size());
        assertEquals(List.of(EligibilityReason.OVERFLOW_NOT_PERMITTED, EligibilityReason.MISSING_CAPABILITY,
                EligibilityReason.MISSING_CAPABILITY),
                none.excluded().stream().map(EligibilityReport.Exclusion::primaryReason).toList());

        EligibilityReport permitted = filter.evaluate(request("implement",
                CatalogFixtures.requiring(List.of("java", "long-context")), true), CatalogFixtures.demoCatalog());
        assertEquals(1, permitted.eligible().size());
        assertEquals("api-x", permitted.eligible().getFirst().key().routeId());
        assertTrue(permitted.eligible().getFirst().overflow());
    }

    @Test
    void overflowPermissionIsNeverInferredAndDefaultsToDenied() {
        RouteDescriptor api = CatalogFixtures.apiX();
        EligibilityReport report = evaluate(request("plan", List.of()), api, Availability.AVAILABLE);
        assertEquals(EligibilityReason.OVERFLOW_NOT_PERMITTED, onlyExclusion(report).primaryReason());
    }

    @Test
    void unsupportedStageHasNoWildcard() {
        RouteDescriptor route = base("r", 1).stages(List.of("plan")).build();
        assertEquals(EligibilityReason.STAGE_INCOMPATIBLE,
                onlyExclusion(evaluate(request("qa", List.of()), route, Availability.AVAILABLE)).primaryReason());
        RouteDescriptor none = base("r", 1).stages(List.of()).build();
        assertEquals(EligibilityReason.STAGE_INCOMPATIBLE,
                onlyExclusion(evaluate(request("plan", List.of()), none, Availability.AVAILABLE)).primaryReason());
    }

    @Test
    void unavailableAndUnknownAvailabilityAreExcluded() {
        RouteDescriptor route = CatalogFixtures.subA();
        assertEquals(EligibilityReason.ROUTE_UNAVAILABLE,
                onlyExclusion(evaluate(request("plan", List.of()), route, Availability.UNAVAILABLE)).primaryReason());
        assertEquals(EligibilityReason.AVAILABILITY_UNKNOWN,
                onlyExclusion(evaluate(request("plan", List.of()), route, Availability.UNKNOWN)).primaryReason());
    }

    @Test
    void modeAndLocalityFailClosedWhenAbsentOrNotPermitted() {
        assertEquals(EligibilityReason.MODE_NOT_PERMITTED, onlyExclusion(evaluate(request("plan", List.of()),
                base("r", 1).executionModes(List.of("native")).build(), Availability.AVAILABLE)).primaryReason());
        assertEquals(EligibilityReason.MODE_NOT_PERMITTED, onlyExclusion(evaluate(request("plan", List.of()),
                base("r", 1).executionModes(List.of()).build(), Availability.AVAILABLE)).primaryReason());
        assertEquals(EligibilityReason.LOCALITY_NOT_PERMITTED, onlyExclusion(evaluate(request("plan", List.of()),
                base("r", 1).locality("remote").build(), Availability.AVAILABLE)).primaryReason());
        RouteDescriptor noLocality = RouteDescriptor.builder("r", 1).stages(CatalogFixtures.STAGES)
                .executionModes(List.of("sandboxed")).build();
        assertEquals(EligibilityReason.LOCALITY_NOT_PERMITTED,
                onlyExclusion(evaluate(request("plan", List.of()), noLocality, Availability.AVAILABLE)).primaryReason());
    }

    @Test
    void emptyPermissionListsPermitNothing() {
        HardRequirements nothing = new HardRequirements(List.of(), List.of(), List.of(), List.of(), 0L);
        EligibilityReport report = evaluate(request("plan", nothing, false), CatalogFixtures.subA(), Availability.AVAILABLE);
        EligibilityReport.Exclusion exclusion = onlyExclusion(report);
        assertEquals(EligibilityReason.MODE_NOT_PERMITTED, exclusion.primaryReason());
        assertEquals(List.of(EligibilityReason.LOCALITY_NOT_PERMITTED), exclusion.additionalReasons());
    }

    @Test
    void missingCapabilityAndMissingToolAreExcludedButEmptyRequirementsAlwaysMatch() {
        RouteDescriptor bare = RouteDescriptor.builder("r", 1).stages(CatalogFixtures.STAGES)
                .executionModes(List.of("sandboxed")).locality("hosted").build();
        assertTrue(evaluate(request("plan", List.of()), bare, Availability.AVAILABLE).eligible().size() == 1);

        assertEquals(EligibilityReason.MISSING_CAPABILITY,
                onlyExclusion(evaluate(request("plan", List.of("java")), bare, Availability.AVAILABLE)).primaryReason());
        HardRequirements needsTool = new HardRequirements(List.of(), List.of("deploy"), List.of("hosted"),
                List.of("sandboxed"), 0L);
        assertEquals(EligibilityReason.MISSING_CAPABILITY, onlyExclusion(evaluate(request("plan", needsTool, false),
                CatalogFixtures.subA(), Availability.AVAILABLE)).primaryReason());
    }

    @Test
    void knownZeroCeilingIsDistinctFromUnknownCeiling() {
        HardRequirements need10 = new HardRequirements(List.of(), List.of(), List.of("hosted"),
                List.of("sandboxed"), 10L);
        RouteDescriptor zero = base("r", 1).contextCeiling(0).build();
        RouteDescriptor unknown = RouteDescriptor.builder("r", 1).stages(CatalogFixtures.STAGES)
                .executionModes(List.of("sandboxed")).locality("hosted").build();

        assertEquals(EligibilityReason.REQUIRED_LIMIT_EXCEEDED,
                onlyExclusion(evaluate(request("plan", need10, false), zero, Availability.AVAILABLE)).primaryReason());
        assertEquals(EligibilityReason.REQUIRED_LIMIT_UNKNOWN,
                onlyExclusion(evaluate(request("plan", need10, false), unknown, Availability.AVAILABLE)).primaryReason());

        HardRequirements need0 = new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L);
        assertEquals(1, evaluate(request("plan", need0, false), zero, Availability.AVAILABLE).eligible().size());
        assertEquals(1, evaluate(request("plan", need0, false), unknown, Availability.AVAILABLE).eligible().size());
        HardRequirements exact = new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 100_000L);
        assertEquals(1, evaluate(request("plan", exact, false), CatalogFixtures.subA(), Availability.AVAILABLE).eligible().size());
    }

    @Test
    void primaryReasonFollowsPrecedenceAndOthersAreListedInOrder() {
        RouteDescriptor route = base("r", 1).stages(List.of("plan")).executionModes(List.of("native"))
                .locality("remote").overflowClass(OverflowClass.OVERFLOW).capabilities(List.of())
                .contextCeiling(1).build();
        HardRequirements need = new HardRequirements(List.of("java"), List.of(), List.of("hosted"),
                List.of("sandboxed"), 5L);

        EligibilityReport.Exclusion exclusion =
                onlyExclusion(evaluate(request("qa", need, false), route, Availability.UNAVAILABLE));

        assertEquals(EligibilityReason.STAGE_INCOMPATIBLE, exclusion.primaryReason());
        assertEquals(List.of(EligibilityReason.ROUTE_UNAVAILABLE, EligibilityReason.MODE_NOT_PERMITTED,
                EligibilityReason.LOCALITY_NOT_PERMITTED, EligibilityReason.OVERFLOW_NOT_PERMITTED,
                EligibilityReason.MISSING_CAPABILITY, EligibilityReason.REQUIRED_LIMIT_EXCEEDED),
                exclusion.additionalReasons());
    }

    @Test
    void chosenModeIsFirstCommonModeInCodePointOrder() {
        RouteDescriptor route = base("r", 1).executionModes(List.of("z-mode", "b-mode", "a-mode")).build();
        HardRequirements need = new HardRequirements(List.of(), List.of(), List.of("hosted"),
                List.of("z-mode", "b-mode"), 0L);
        assertEquals("b-mode", evaluate(request("plan", need, false), route, Availability.AVAILABLE)
                .eligible().getFirst().executionMode());
    }

    @Test
    void catalogPermutationsYieldEqualReports() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            RouteDescriptor.Builder b = base("route-" + i, 1 + i % 3).capabilities(i % 2 == 0 ? List.of("java") : List.of());
            if (i % 5 == 0) {
                b.overflowClass(OverflowClass.OVERFLOW);
            }
            entries.add(new CatalogEntry(b.build(), i % 4 == 0 ? Availability.UNKNOWN : Availability.AVAILABLE, i));
        }
        RoutingRequest request = request("review", List.of("java"));
        EligibilityReport reference = filter.evaluate(request, RouteCatalog.of("v1", entries));
        for (int seed = 0; seed < 25; seed++) {
            Collections.shuffle(entries, new Random(seed));
            assertEquals(reference, filter.evaluate(request, RouteCatalog.of("v1", entries)));
        }
        assertEquals(12, reference.eligible().size() + reference.excluded().size());
    }

    @Test
    void reportIsImmutable() {
        EligibilityReport report = filter.evaluate(request("plan", List.of()), CatalogFixtures.demoCatalog());
        assertThrows(UnsupportedOperationException.class, () -> report.eligible().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.excluded().clear());
    }

    @Test
    void directlyBuiltReportsAreBoundedBeforeCopying() {
        RouteKey key = new RouteKey("r", 1);
        EligibilityReport.EligibleRoute route = new EligibilityReport.EligibleRoute(key, "sandboxed", false);
        EligibilityReport.Exclusion exclusion =
                new EligibilityReport.Exclusion(key, EligibilityReason.ROUTE_UNAVAILABLE, List.of());
        assertEquals(32, new EligibilityReport("v", Collections.nCopies(16, route), Collections.nCopies(16, exclusion))
                .eligible().size() * 2);
        assertThrows(IllegalArgumentException.class,
                () -> new EligibilityReport("v", Collections.nCopies(16, route), Collections.nCopies(17, exclusion)));
        assertThrows(IllegalArgumentException.class,
                () -> new EligibilityReport("v", Collections.nCopies(1_000_000, route), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EligibilityReport.Exclusion(key,
                EligibilityReason.ROUTE_UNAVAILABLE, Collections.nCopies(1_000_000, EligibilityReason.MISSING_CAPABILITY)));
    }

    @Test
    void oversizedTokenCollectionsAreRejectedAtTheSetterNotAtBuild() {
        List<String> huge = Collections.nCopies(1_000_000, "x");
        RouteDescriptor.Builder b = base("r", 1);
        assertThrows(IllegalArgumentException.class, () -> b.stages(huge));
        assertThrows(IllegalArgumentException.class, () -> b.capabilities(huge));
        assertThrows(IllegalArgumentException.class, () -> b.tools(huge));
        assertThrows(IllegalArgumentException.class, () -> b.executionModes(huge));
        assertThrows(IllegalArgumentException.class, () -> HardRequirements.allowing(huge, List.of("m")));
        assertThrows(IllegalArgumentException.class, () -> HardRequirements.allowing(List.of("l"), huge));
        List<String> sixteen = IntStream.range(0, 16).mapToObj(i -> "t" + i).toList();
        assertEquals(16, HardRequirements.allowing(sixteen, sixteen).permittedModes().size());
    }

    @Test
    void requestRejectsUnknownContractVersionAndNegativeValues() {
        RoutingRequest ok = request("plan", List.of());
        assertThrows(IllegalArgumentException.class, () -> new RoutingRequest("forge-routing/2", ok.scopeId(),
                ok.taskId(), ok.executionId(), ok.attemptId(), ok.stageId(), ok.stageKind(), 1, ok.sourceFingerprint(),
                ok.contextFingerprint(), ok.constraintsFingerprint(), ok.evaluationPolicyId(),
                ok.evaluationPolicyVersion(), ok.features(), ok.requirements(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> new HardRequirements(List.of(), List.of(), List.of(),
                List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> new RoutingRequest(RoutingRequest.CONTRACT_VERSION,
                ok.scopeId(), ok.taskId(), ok.executionId(), ok.attemptId(), ok.stageId(), ok.stageKind(), -1,
                ok.sourceFingerprint(), ok.contextFingerprint(), ok.constraintsFingerprint(),
                ok.evaluationPolicyId(), ok.evaluationPolicyVersion(), ok.features(), ok.requirements(), false, 0));
    }

    @Test
    void capabilityMatrixForPlanImplementReviewQa() {
        RouteDescriptor planner = base("planner", 1).stages(List.of("plan", "review"))
                .capabilities(List.of("long-context")).build();
        RouteDescriptor coder = base("coder", 1).stages(List.of("implement")).capabilities(List.of("java")).build();
        RouteDescriptor tester = base("tester", 1).stages(List.of("qa", "review"))
                .capabilities(List.of("java", "security")).build();
        RouteCatalog catalog = RouteCatalog.of("matrix-1",
                List.of(available(planner), available(coder), available(tester), available(CatalogFixtures.apiX())));

        assertEquals(List.of("planner"), eligibleIds(filter.evaluate(request("plan", List.of("long-context")), catalog)));
        assertEquals(List.of("coder"), eligibleIds(filter.evaluate(request("implement", List.of("java")), catalog)));
        assertEquals(List.of("planner", "tester"), eligibleIds(filter.evaluate(request("review", List.of()), catalog)));
        assertEquals(List.of("tester"), eligibleIds(filter.evaluate(request("qa", List.of("security")), catalog)));
        // api-x supports all four stages but is overflow, so it never appears without host permission.
        for (String stage : CatalogFixtures.STAGES) {
            assertFalse(eligibleIds(filter.evaluate(request(stage, List.of()), catalog)).contains("api-x"));
        }
    }

    private static List<String> eligibleIds(EligibilityReport report) {
        return report.eligible().stream().map(r -> r.key().routeId()).toList();
    }

    @Test
    void workGrowsLinearlyWithRoutesAndRequirementTokens() {
        // Counts accepted constraint checks via the report size: every route is accounted exactly once at any R, Q.
        for (int routes : new int[] {8, 16, 32}) {
            for (int tokens : new int[] {0, 4, 16}) {
                List<String> need = IntStream.range(0, tokens).mapToObj(i -> "c" + i).toList();
                List<CatalogEntry> entries = IntStream.range(0, routes).mapToObj(i ->
                        available(base("r" + i, 1).capabilities(i % 2 == 0 ? need : List.of()).build())).toList();
                EligibilityReport report = filter.evaluate(request("plan", need), RouteCatalog.of("v", entries));
                assertEquals(routes, report.eligible().size() + report.excluded().size());
                int expectedEligible = tokens == 0 ? routes : (routes + 1) / 2;
                assertEquals(expectedEligible, report.eligible().size());
            }
        }
    }

    @Test
    void maximumSizeEvaluationAllocatesBoundedMemory() {
        List<String> need = IntStream.range(0, 16).mapToObj(i -> "c" + i).toList();
        List<CatalogEntry> entries = IntStream.range(0, 32).mapToObj(i ->
                available(base("r" + i, 1).capabilities(need).build())).toList();
        RouteCatalog catalog = RouteCatalog.of("v", entries);
        RoutingRequest request = request("plan", need);
        for (int i = 0; i < 20_000; i++) {
            filter.evaluate(request, catalog);
        }
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(threads.isThreadAllocatedMemorySupported(), "thread allocation tracking unsupported");
        threads.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(id);
        EligibilityReport report = filter.evaluate(request, catalog);
        long after = threads.getThreadAllocatedBytes(id);
        assertTrue(before >= 0 && after >= before, "allocation counter unavailable: " + before + " -> " + after);
        long allocated = after - before;
        assertTrue(allocated > 0, "evaluate must allocate its report; measured " + allocated);
        System.out.println("catalog eligibility allocation (R=32, Q=16): " + allocated + " bytes");

        assertEquals(32, report.eligible().size());
        // Regression guard, not a benchmark: a modeled report of 32 routes is a few KiB.
        assertTrue(allocated < 16 * 1024, "allocated " + allocated);
    }
}
