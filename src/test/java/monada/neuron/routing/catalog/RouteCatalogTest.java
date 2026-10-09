package monada.neuron.routing.catalog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static monada.neuron.routing.catalog.CatalogFixtures.available;
import static monada.neuron.routing.catalog.CatalogFixtures.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCatalogTest {

    private static ResourceEstimate estimate(RouteDescriptor route, String dimension) {
        return new ResourceEstimate(route.key(), dimension, "unit",
                new ResourceValue.Known(0, ResourceValue.Provenance.ESTIMATED));
    }

    @Test
    void entryPermutationsYieldEqualCatalogsInCanonicalOrder() {
        List<CatalogEntry> entries = new ArrayList<>(List.of(available(CatalogFixtures.subB()),
                available(CatalogFixtures.apiX()), available(CatalogFixtures.subA()),
                available(base("sub-a", 10).build()), available(base("sub-a", 2).build())));
        RouteCatalog reference = RouteCatalog.of("v1", entries);
        for (int seed = 0; seed < 20; seed++) {
            Collections.shuffle(entries, new java.util.Random(seed));
            RouteCatalog shuffled = RouteCatalog.of("v1", entries);
            assertEquals(reference, shuffled);
            assertEquals(reference.hashCode(), shuffled.hashCode());
        }
        List<String> order = reference.entries().stream()
                .map(e -> e.key().routeId() + "@" + e.key().routeVersion()).toList();
        assertEquals(List.of("api-x@1", "sub-a@1", "sub-a@2", "sub-a@10", "sub-b@1"), order);
    }

    @Test
    void duplicateRouteIdentityIsRejectedEvenWithConflictingDescriptors() {
        CatalogEntry first = available(base("r", 1).capabilities(List.of("java")).build());
        CatalogEntry conflicting = available(base("r", 1).capabilities(List.of("rust")).tier(2).build());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RouteCatalog.of("v1", List.of(first, conflicting)));
        assertTrue(e.getMessage().contains("duplicate route identity"));
    }

    @Test
    void sameRouteIdWithDifferentVersionsIsAllowed() {
        RouteCatalog catalog = RouteCatalog.of("v1",
                List.of(available(base("r", 1).build()), available(base("r", 2).build())));
        assertEquals(2, catalog.entries().size());
    }

    @Test
    void catalogIsBoundedToThirtyTwoRoutes() {
        List<CatalogEntry> max = IntStream.rangeClosed(1, 32).mapToObj(i -> available(base("r" + i, 1).build())).toList();
        assertEquals(32, RouteCatalog.of("v1", max).entries().size());
        List<CatalogEntry> over = IntStream.rangeClosed(1, 33).mapToObj(i -> available(base("r" + i, 1).build())).toList();
        assertThrows(IllegalArgumentException.class, () -> RouteCatalog.of("v1", over));
    }

    @Test
    void catalogIsImmutableAndCopiesItsInput() {
        List<CatalogEntry> source = new ArrayList<>(List.of(available(CatalogFixtures.subA())));
        RouteCatalog catalog = RouteCatalog.of("v1", source);
        source.add(available(CatalogFixtures.subB()));

        assertEquals(1, catalog.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.estimates().clear());
    }

    @Test
    void estimatesAreBoundedUniqueAndMustReferenceCatalogRoutes() {
        RouteDescriptor route = CatalogFixtures.subA();
        List<CatalogEntry> entries = List.of(available(route));
        List<ResourceEstimate> four = List.of(estimate(route, "a"), estimate(route, "b"),
                estimate(route, "c"), estimate(route, "d"));
        assertEquals(4, new RouteCatalog("v1", entries, four).estimates().size());

        List<ResourceEstimate> five = new ArrayList<>(four);
        five.add(estimate(route, "e"));
        assertThrows(IllegalArgumentException.class, () -> new RouteCatalog("v1", entries, five));
        assertThrows(IllegalArgumentException.class,
                () -> new RouteCatalog("v1", entries, List.of(estimate(route, "a"), estimate(route, "a"))));
        assertThrows(IllegalArgumentException.class, () -> new RouteCatalog("v1", entries,
                List.of(estimate(CatalogFixtures.subB(), "a"))));
    }

    @Test
    void oversizedEstimateCollectionIsRejectedBeforeAnyPerRouteValidation() {
        List<CatalogEntry> max = IntStream.rangeClosed(1, 32).mapToObj(i -> available(base("r" + i, 1).build())).toList();
        List<ResourceEstimate> full = max.stream().flatMap(e -> IntStream.range(0, 4)
                .mapToObj(d -> estimate(e.descriptor(), "d" + d))).toList();
        assertEquals(128, new RouteCatalog("v1", max, full).estimates().size());

        // Duplicates of one dangling estimate: only a size check that precedes validation can reject this by size.
        ResourceEstimate dangling = estimate(base("ghost", 1).build(), "x");
        List<ResourceEstimate> oversized = Collections.nCopies(129, dangling);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new RouteCatalog("v1", max, oversized));
        assertTrue(e.getMessage().contains("at most 128 estimates"), e.getMessage());
    }

    @Test
    void estimatePermutationsYieldEqualCatalogs() {
        RouteDescriptor route = CatalogFixtures.subA();
        List<CatalogEntry> entries = List.of(available(route));
        assertEquals(new RouteCatalog("v1", entries, List.of(estimate(route, "a"), estimate(route, "b"))),
                new RouteCatalog("v1", entries, List.of(estimate(route, "b"), estimate(route, "a"))));
    }

    @Test
    void resourceValuesKeepKnownZeroDistinctFromUnknownAndNotMeasured() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceValue.Known(-1, ResourceValue.Provenance.REPORTED));
        ResourceValue zero = new ResourceValue.Known(0, ResourceValue.Provenance.REPORTED);
        assertTrue(!zero.equals(new ResourceValue.Unknown()) && !zero.equals(new ResourceValue.NotMeasured()));
    }

    @Test
    void descriptorValidatesTokensTiersVersionsAndSetBounds() {
        assertThrows(IllegalArgumentException.class, () -> RouteDescriptor.builder("r", 0));
        assertThrows(IllegalArgumentException.class, () -> RouteDescriptor.builder(" r", 1));
        assertThrows(IllegalArgumentException.class, () -> base("r", 1).tier(0).build());
        assertThrows(IllegalArgumentException.class, () -> base("r", 1).contextCeiling(-1).build());
        assertThrows(IllegalArgumentException.class, () -> base("r", 1).capabilities(List.of("a", "a")).build());
        List<String> sixteen = IntStream.range(0, 16).mapToObj(i -> "c" + i).toList();
        List<String> seventeen = IntStream.range(0, 17).mapToObj(i -> "c" + i).toList();
        assertEquals(16, base("r", 1).capabilities(sixteen).build().capabilities().size());
        assertThrows(IllegalArgumentException.class, () -> base("r", 1).capabilities(seventeen).build());
    }

    @Test
    void descriptorSetsAreCopiedAndStoredInCodePointOrder() {
        List<String> source = new ArrayList<>(List.of("b", "a", "c"));
        RouteDescriptor descriptor = base("r", 1).capabilities(source).build();
        source.clear();
        assertEquals(List.of("a", "b", "c"), descriptor.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.capabilities().add("x"));
    }
}
