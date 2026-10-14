package monada.neuron.routing.adaptation;

import monada.neuron.routing.Basis;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.Reconciliation;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.testsupport.AllocationProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static monada.neuron.routing.adaptation.AdaptationFixtures.Kind;
import static monada.neuron.routing.adaptation.AdaptationFixtures.decide;
import static monada.neuron.routing.adaptation.AdaptationFixtures.deliver;
import static monada.neuron.routing.adaptation.AdaptationFixtures.derive;
import static monada.neuron.routing.adaptation.AdaptationFixtures.observation;
import static monada.neuron.routing.adaptation.AdaptationFixtures.policy;
import static monada.neuron.routing.adaptation.AdaptationFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long deterministic fixture: thousands of seeded deliveries with corrections and staged rebuilds over 3 stages, 4
 * buckets and 3 routes in two scopes. Two independent runs give identical states and decisions, scope B never reaches
 * scope A, every bound holds, and the ledger reports capacity explicitly at its bound.
 */
class AdaptiveStateDeterminismTest {

    private static final int OBSERVATIONS = 4000;
    private static final List<String> STAGES = List.of("plan", "implement", "review");
    private static final List<Feature<Long>> SIZES =
            List.of(Feature.known(10L), Feature.known(100L), Feature.known(1000L), Feature.unknown());
    private static final List<String> ROUTES = List.of("sub-a", "sub-b", "sub-c");

    /** One cohort: the request and the advised decision every sample of it answers. */
    private record Cell(RoutingRequest request, RoutingDecision.Selected decision, CohortKey key) {
    }

    /** A delivered revision and the cohort it belongs to. */
    private record Delivered(RoutingObservation observation, Cell cell) {
    }

    /** What one run produced for one scope. */
    private record Outcome(List<CohortState> cohorts, List<?> ledger, long cutoff,
            List<RoutingDecision> decisions, int rebuilds) {
    }

    private static RouteCatalog catalog() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (String route : ROUTES) {
            entries.add(new CatalogEntry(AdaptationFixtures.route(route), Availability.AVAILABLE, 1));
        }
        return RouteCatalog.of("cat-three", entries);
    }

    private static List<Cell> cells(String scope) {
        List<Cell> cells = new ArrayList<>();
        int execution = 0;
        for (String stage : STAGES) {
            for (Feature<Long> size : SIZES) {
                for (String route : ROUTES) {
                    var request = request(scope, stage, size, "exec-" + execution, execution);
                    execution++;
                    var decision = decide(request, route);
                    cells.add(new Cell(request, decision, AdaptationFixtures.key(request, decision)));
                }
            }
        }
        return cells;
    }

    /** Runs scope A alone, or with scope B interleaved and every B sample also offered to A. */
    private Map<String, Outcome> run(long seed, boolean withScopeB) {
        var scopes = withScopeB ? List.of("scope-demo", "scope-b") : List.of("scope-demo");
        Map<String, RoutingStateStore> stores = new HashMap<>();
        Map<String, List<Cell>> cellsByScope = new HashMap<>();
        Map<String, Map<String, Delivered>> known = new HashMap<>();
        Map<String, Random> randoms = new HashMap<>();
        Map<String, Integer> rebuilds = new HashMap<>();
        // each scope has its own export ledger, so its sequence never depends on another scope's deliveries
        Map<String, Long> sequences = new HashMap<>();
        for (String scope : scopes) {
            var store = new RoutingStateStore(AdaptationFixtures.DEFINITION,
                    new StatePartition(scope, "forge-gates", "1"));
            var cells = cells(scope);
            assertInstanceOf(BindResult.Bound.class, store.bind(cells.stream().map(Cell::key).toList()));
            stores.put(scope, store);
            cellsByScope.put(scope, cells);
            known.put(scope, new LinkedHashMap<>());
            randoms.put(scope, new Random(seed * 31 + scope.hashCode()));
            rebuilds.put(scope, 0);
            sequences.put(scope, 100L);
        }
        Kind[] kinds = Kind.values();
        for (int i = 0; i < OBSERVATIONS; i++) {
            for (String scope : scopes) {
                var random = randoms.get(scope);
                var knownHere = known.get(scope);
                long sequence = sequences.merge(scope, 1L, Long::sum);
                Delivered delivered;
                if (!knownHere.isEmpty() && random.nextInt(500) == 0) {
                    // a correction of an earlier sample: a newer revision that supersedes the latest one
                    var refs = new ArrayList<>(knownHere.keySet());
                    var previous = knownHere.get(refs.get(random.nextInt(refs.size())));
                    var old = previous.observation();
                    delivered = new Delivered(observation(previous.cell().request(), previous.cell().decision(),
                            old.experienceRef(), old.revision() + 1, sequence, Feature.known(old.ledgerSequence()),
                            kinds[random.nextInt(kinds.length)]), previous.cell());
                } else {
                    var cell = cellsByScope.get(scope).get(random.nextInt(cellsByScope.get(scope).size()));
                    delivered = new Delivered(observation(cell.request(), cell.decision(), scope + "-" + i, sequence,
                            kinds[random.nextInt(kinds.length)]), cell);
                }
                knownHere.put(delivered.observation().experienceRef(), delivered);
                var store = stores.get(scope);
                var update = deliver(store, delivered.observation(), delivered.cell().decision(),
                        delivered.cell().request());
                switch (update) {
                    case StateUpdate.Applied ignored -> { }
                    case StateUpdate.Recorded ignored -> { }
                    case StateUpdate.RebuildRequired ignored -> {
                        var result = store.rebuild(
                                knownHere.values().stream().map(Delivered::observation).toList(), sequence,
                                o -> {
                                    var cell = knownHere.get(o.experienceRef()).cell();
                                    return derive(store, o, cell.decision(), cell.request());
                                });
                        stores.put(scope, assertInstanceOf(RebuildResult.Rebuilt.class, result).store());
                        rebuilds.merge(scope, 1, Integer::sum);
                    }
                    default -> fail("seed " + seed + " delivery " + i + ": unexpected " + update);
                }
                if (scope.equals("scope-b")) {
                    // scope B's sample offered to scope A, derived against A's bindings: never examined there
                    var a = stores.get("scope-demo");
                    var foreign = a.consume(delivered.observation(),
                            derive(a, delivered.observation(), delivered.cell().decision(), delivered.cell().request()));
                    assertInstanceOf(StateUpdate.Unchanged.class, foreign);
                    assertFalse(foreign.examined());
                }
            }
        }
        Map<String, Outcome> outcomes = new HashMap<>();
        var catalog = catalog();
        for (String scope : scopes) {
            var store = stores.get(scope);
            assertTrue(store.cohortCount() <= RoutingStateStore.MAX_COHORTS);
            assertTrue(store.ledger().size() <= RoutingStateStore.MAX_LEDGER);
            var snapshot = store.snapshot(policy(), catalog);
            List<RoutingDecision> decisions = new ArrayList<>();
            for (Cell cell : cellsByScope.get(scope)) {
                decisions.add(policy().decide(cell.request(), catalog, snapshot));
            }
            outcomes.put(scope, new Outcome(store.cohorts(catalog), store.ledger(), store.processedCutoff(), decisions,
                    rebuilds.get(scope)));
        }
        return outcomes;
    }

    @Test
    void identicalOrderedFeedbackYieldsIdenticalStatesAndDecisionsWithinBounds(TestReporter reporter) {
        var first = run(66, true);
        var second = run(66, true);
        assertEquals(first, second);
        var alone = run(66, false);
        assertEquals(alone.get("scope-demo"), first.get("scope-demo"), "scope B changed scope A");

        var a = first.get("scope-demo");
        assertEquals(36, a.cohorts().size());
        assertTrue(a.rebuilds() > 0, "the fixture must exercise corrections");
        long learned = a.decisions().stream().filter(d -> d instanceof RoutingDecision.Selected s
                && s.basis() == Basis.LEARNED_PREFERENCE).count();
        assertTrue(learned > 0, "learned preference never changed a decision");
        for (var cohort : a.cohorts()) {
            assertTrue(cohort.amplitude() >= 0.5 && cohort.amplitude() <= 6.0, cohort.toString());
            assertTrue(cohort.energy() >= 0.0 && cohort.energy() <= 12.0, cohort.toString());
        }
        reporter.publishEntry("adaptive-state.long-run", "observations=" + OBSERVATIONS + " per scope, cohorts="
                + a.cohorts().size() + ", ledger=" + a.ledger().size() + ", rebuilds=" + a.rebuilds()
                + ", learnedDecisions=" + learned + "/" + a.decisions().size());
    }

    @Test
    void theLedgerReportsCapacityAtItsBoundAndKeepsTheStateUnchanged() {
        var store = new RoutingStateStore(AdaptationFixtures.DEFINITION, AdaptationFixtures.PARTITION);
        var cells = cells("scope-demo");
        store.bind(cells.stream().map(Cell::key).toList());
        var random = new Random(67);
        Kind[] kinds = Kind.values();
        for (int i = 0; i < RoutingStateStore.MAX_LEDGER; i++) {
            var cell = cells.get(random.nextInt(cells.size()));
            var update = deliver(store, observation(cell.request(), cell.decision(), String.format("r-%05d", i),
                    100 + i, kinds[random.nextInt(kinds.length)]), cell.decision(), cell.request());
            assertInstanceOf(StateUpdate.Applied.class, update);
        }
        var before = store.cohorts(catalog());
        long cutoff = store.processedCutoff();
        var cell = cells.getFirst();
        var blocked = assertInstanceOf(StateUpdate.Blocked.class, deliver(store,
                observation(cell.request(), cell.decision(), "r-overflow", 90_000, Kind.PASS), cell.decision(),
                cell.request()));
        assertInstanceOf(Reconciliation.CapacityExhausted.class, blocked.advice());
        assertEquals(before, store.cohorts(catalog()));
        assertEquals(cutoff, store.processedCutoff());
    }

    @Test
    void consumptionAndSnapshotAllocationStaysBounded(TestReporter reporter) {
        var store = new RoutingStateStore(AdaptationFixtures.DEFINITION, AdaptationFixtures.PARTITION);
        var cells = cells("scope-demo");
        store.bind(cells.stream().map(Cell::key).toList());
        var cell = cells.getFirst();
        var observation = observation(cell.request(), cell.decision(), "r-1", 100, Kind.PASS);
        var derivation = derive(store, observation, cell.decision(), cell.request());
        store.consume(observation, derivation);
        var catalog = catalog();
        // a duplicate delivery runs the whole consumption path (partition check, reconciliation) without mutation
        long consume = AllocationProbe.measure(20_000, () -> store.consume(observation, derivation));
        long snapshot = AllocationProbe.measure(20_000, () -> store.snapshot(policy(), catalog));
        reporter.publishEntry("adaptive-state.allocation", "duplicate consume=" + consume + " B (ledger 1), snapshot="
                + snapshot + " B (36 cohorts)");
        assertTrue(consume < 16 * 1024, "consume allocated " + consume);
        assertTrue(snapshot < 32 * 1024, "snapshot allocated " + snapshot);
    }
}
