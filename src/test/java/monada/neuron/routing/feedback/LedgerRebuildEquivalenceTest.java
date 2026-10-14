package monada.neuron.routing.feedback;

import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.StageAttribution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static monada.neuron.routing.feedback.FeedbackFixtures.NODE_A;
import static monada.neuron.routing.feedback.FeedbackFixtures.NODE_B;
import static monada.neuron.routing.feedback.FeedbackFixtures.obs;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The contract invariant behind the reconciler: after any sequence of deliveries, the state kept incrementally by
 * following {@link Reconciliation} advice equals a rebuild from scratch from the latest known revision of every sample,
 * in applied order. Deterministic seeds; every failure message names its seed and the delivery trace.
 */
class LedgerRebuildEquivalenceTest {

    private static final UUID NODE_C = new UUID(0L, 9L);
    private static final int SAMPLES = 4;
    /** The third route has no bound Node, so its positive results derive nothing yet can still be cost peers. */
    private static final List<String> ROUTES = List.of("sub-a", "sub-b", "sub-c", "sub-a");
    private static final int SEEDS = 3000;
    private static final int DELIVERIES = 20;
    private static final DeterministicBaselineAdaptationPolicy ADAPTATION = new DeterministicBaselineAdaptationPolicy(
            new AdaptationConfig(0.5, 0.5, 6.0, 0.0, 12.0, 0.0, 10_000.0, 2.0));

    private final RoutingFeedbackPolicy policy = FeedbackFixtures.policy();
    private final RevisionReconciler reconciler = new RevisionReconciler();
    private final RebuildOrder rebuildOrder = new RebuildOrder();
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private int costRefined;
    private int stalePeers;

    /** One sample: a fixed request and decision, and the revisions the export will create for it. */
    private record Sample(String ref, RoutingRequest request, RoutingDecision.Selected decision) {
    }

    private static Node baseline(UUID id) {
        return new Node.Builder().id(id).frequencyState(new FrequencyState(4.0, 50.0, 0.5)).energy(10.0)
                .type(NodeType.PROCESSOR).build();
    }

    /** The caller's mutable state: Nodes, the ledger and what is known. */
    private final class State {
        final Map<UUID, Node> nodes = new HashMap<>();
        final Map<String, AppliedRevision> ledger = new HashMap<>();
        final Map<String, FeedbackFixtures.Spec> known = new HashMap<>();
        final boolean cost;
        boolean routeCBound;
        final List<Sample> samples;

        State(boolean cost, List<Sample> samples) {
            this.cost = cost;
            this.samples = samples;
            reset();
        }

        void reset() {
            nodes.clear();
            nodes.put(NODE_A, baseline(NODE_A));
            nodes.put(NODE_B, baseline(NODE_B));
            nodes.put(NODE_C, baseline(NODE_C));
            ledger.clear();
        }

        TargetBindings bindings() {
            var a = samples.get(0);
            var b = samples.get(1);
            var c = samples.get(2);
            List<TargetBinding> bound = new ArrayList<>(List.of(
                    new TargetBinding(FeedbackFixtures.keyOf(a.decision(), a.request()), NODE_A),
                    new TargetBinding(FeedbackFixtures.keyOf(b.decision(), b.request()), NODE_B)));
            if (routeCBound) {
                bound.add(new TargetBinding(FeedbackFixtures.keyOf(c.decision(), c.request()), NODE_C));
            }
            return new TargetBindings("scope-demo", "forge-gates", "1", FeedbackFixtures.DEFINITION.mappingVersion(),
                    FeedbackFixtures.SCHEMA, bound);
        }

        Optional<CostReference> costFor(FeedbackFixtures.Spec spec, Map<String, FeedbackFixtures.Spec> view) {
            if (!cost) {
                return Optional.empty();
            }
            int index = indexOf(spec.experienceRef);
            if (index == 0) {
                return Optional.empty();
            }
            var peer = view.get(samples.get(index - 1).ref());
            return peer == null ? Optional.empty()
                    : Optional.of(new CostReference(peer.observation(), peer.decision, peer.request));
        }

        private int indexOf(String ref) {
            for (int i = 0; i < samples.size(); i++) {
                if (samples.get(i).ref().equals(ref)) {
                    return i;
                }
            }
            throw new IllegalStateException(ref);
        }

        FeedbackDerivation derive(FeedbackFixtures.Spec spec) {
            return derive(spec, known);
        }

        /** Derives against the given view of what is known, which may be older than the current knowledge. */
        FeedbackDerivation derive(FeedbackFixtures.Spec spec, Map<String, FeedbackFixtures.Spec> view) {
            return policy.derive(spec.observation(), spec.decision, spec.request, bindings(), costFor(spec, view));
        }

        void apply(RoutingFeedback feedback) {
            if (feedback.rule() == ScoreRule.REINFORCE_COST_REFINED) {
                costRefined++;
            }
            for (var input : feedback.toFeedbackInputs()) {
                ADAPTATION.adapt(nodes.get(input.targetNodeId()), input);
            }
        }

        /** The caller binds a Node to the third route and redelivers every sample it knows (a late binding). */
        void bindRouteC() {
            routeCBound = true;
            for (var spec : new ArrayList<>(known.values())) {
                deliver(spec, known);
            }
        }

        /** A rebuild from scratch from the latest known revision of every sample, in applied order. */
        void rebuild() {
            reset();
            Map<String, FeedbackFixtures.Spec> byRef = new HashMap<>();
            List<RoutingObservation> observations = new ArrayList<>();
            for (var spec : known.values()) {
                observations.add(spec.observation());
                byRef.put(spec.experienceRef, spec);
            }
            for (var observation : rebuildOrder.order(observations)) {
                var spec = byRef.get(observation.experienceRef());
                var derivation = derive(spec);
                if (derivation instanceof FeedbackDerivation.Derived derived) {
                    apply(derived.feedback());
                    ledger.put(spec.experienceRef, AppliedRevision.of(derived.feedback()));
                } else {
                    var none = (FeedbackDerivation.NoUpdate) derivation;
                    if (none.reason().recordable()) {
                        ledger.put(spec.experienceRef, AppliedRevision.ofNoUpdate(observation, none.reason()));
                    }
                }
            }
        }

        /** Delivers one revision and follows the reconciler's advice exactly as the contract describes it. */
        void deliver(FeedbackFixtures.Spec delivered, Map<String, FeedbackFixtures.Spec> view) {
            deliver(delivered, view, true);
        }

        private void deliver(FeedbackFixtures.Spec delivered, Map<String, FeedbackFixtures.Spec> view, boolean retry) {
            var current = known.get(delivered.experienceRef);
            if (current == null || delivered.revision > current.revision) {
                known.put(delivered.experienceRef, delivered);
            }
            var derivation = derive(delivered, view);
            var result = reconciler.reconcile(List.copyOf(ledger.values()), delivered.observation(), derivation);
            outcomes.merge(result.getClass().getSimpleName(), 1, Integer::sum);
            switch (result) {
                case Reconciliation.Apply apply -> {
                    var feedback = ((FeedbackDerivation.Derived) derivation).feedback();
                    apply(feedback);
                    ledger.put(delivered.experienceRef, AppliedRevision.of(feedback));
                }
                case Reconciliation.RecordOnly record -> ledger.put(delivered.experienceRef, record.marker());
                case Reconciliation.RebuildRequired rebuild -> rebuild();
                case Reconciliation.AlreadyApplied ignored -> { }
                case Reconciliation.StaleRevision ignored -> { }
                case Reconciliation.NotRecorded ignored -> { }
                case Reconciliation.StalePeerDependency stale -> {
                    // the contract: derive the sample again against the latest peer revision, then reconcile again
                    stalePeers++;
                    if (!retry) {
                        fail("still stale after deriving against the latest knowledge: " + stale);
                    }
                    deliver(delivered, known, false);
                }
                case Reconciliation.ConflictingRevision conflict -> fail("unexpected conflict: " + conflict);
                case Reconciliation.CapacityExhausted capacity -> fail("unexpected capacity");
            }
        }
    }

    private static FeedbackFixtures.Spec revision(Sample sample, int revision, long sequence, long previous,
            Random random) {
        var spec = new FeedbackFixtures.Spec(sample.request(), sample.decision());
        spec.experienceRef = sample.ref();
        spec.revision = revision;
        spec.ledgerSequence = sequence;
        spec.cutoff = 10_000L;
        spec.supersedes = revision == 0 ? Feature.unknown() : Feature.known(previous);
        spec.resources = FeedbackFixtures.tokens(100 + random.nextInt(900), ResourceValue.Provenance.REPORTED);
        switch (random.nextInt(12)) {
            case 0, 1, 2 -> spec.passing();
            case 3, 4 -> spec.failing();
            case 5 -> {
                spec.passing();
                spec.status = OutcomeStatus.PENDING;
            }
            case 6 -> spec.passing().with(obs("ev-late", 0, QualityDimension.TESTS, QualityState.UNKNOWN,
                    sample.request().attemptId(), 8));
            case 7 -> {
                spec.passing();
                spec.attribution = StageAttribution.none();
            }
            case 8 -> {
                spec.passing();
                spec.origin = OutcomeOrigin.IMPORTED_CLAIM;
            }
            case 9 -> {
                spec.failing();
                spec.invocation = InvocationStatus.TIMED_OUT;
            }
            case 10 -> {
                spec.passing();
                spec.status = OutcomeStatus.CANCELLED;
            }
            default -> {
                // a revision whose source fingerprint no longer matches the request: a provenance mismatch
                spec.passing();
                spec.sourceFingerprint = "src-other";
            }
        }
        return spec;
    }

    private void run(boolean cost) {
        for (int seed = 0; seed < SEEDS; seed++) {
            var random = new Random(seed);
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < SAMPLES; i++) {
                var request = FeedbackFixtures.request("scope-demo", "task-17", "exec-" + i, "att-1", "s-impl-" + i,
                        100L + i);
                samples.add(new Sample("s" + i, request, FeedbackFixtures.decide(request, ROUTES.get(i))));
            }
            // the export creates revisions in one global order; a sample's later revision sits later in that order
            int[] revisions = new int[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                revisions[i] = 1 + random.nextInt(3);
            }
            List<int[]> creation = new ArrayList<>();
            int[] next = new int[SAMPLES];
            int remaining = 0;
            for (int r : revisions) {
                remaining += r;
            }
            while (remaining > 0) {
                int i = random.nextInt(SAMPLES);
                if (next[i] < revisions[i]) {
                    creation.add(new int[] {i, next[i]++});
                    remaining--;
                }
            }
            List<FeedbackFixtures.Spec> created = new ArrayList<>();
            Map<Integer, Long> previousSequence = new HashMap<>();
            long sequence = 100L;
            for (int[] event : creation) {
                var sample = samples.get(event[0]);
                created.add(revision(sample, event[1], sequence, previousSequence.getOrDefault(event[0], 0L), random));
                previousSequence.put(event[0], sequence);
                sequence += 10L;
            }
            var state = new State(cost, samples);
            StringBuilder trace = new StringBuilder();
            List<Map<String, FeedbackFixtures.Spec>> views = new ArrayList<>();
            int bindAt = random.nextInt(2) == 0 ? random.nextInt(DELIVERIES) : -1;
            for (int d = 0; d < DELIVERIES; d++) {
                if (d == bindAt) {
                    trace.append("[bind route C] ");
                    try {
                        state.bindRouteC();
                    } catch (RuntimeException | AssertionError e) {
                        throw new AssertionError("seed " + seed + " trace " + trace + ": " + e, e);
                    }
                }
                var delivered = created.get(random.nextInt(created.size()));
                // a delivery is sometimes derived against what was known a few deliveries ago (an asynchronous caller)
                // (a peer revision may be out of date, but a known peer is never dropped: the reconciler can detect
                // a superseded peer revision, not a peer the caller left out)
                Map<String, FeedbackFixtures.Spec> view = state.known;
                if (!views.isEmpty() && random.nextInt(4) == 0) {
                    view = new HashMap<>(state.known);
                    view.putAll(views.get(random.nextInt(views.size())));
                }
                trace.append(delivered.experienceRef).append('@').append(delivered.revision)
                        .append(view == state.known ? " " : "(stale view) ");
                try {
                    state.deliver(delivered, view);
                } catch (RuntimeException | AssertionError e) {
                    throw new AssertionError("seed " + seed + " trace " + trace + ": " + e, e);
                }
                var oracle = new State(cost, samples);
                oracle.routeCBound = state.routeCBound;
                oracle.known.putAll(state.known);
                oracle.rebuild();
                for (var id : List.of(NODE_A, NODE_B, NODE_C)) {
                    var mine = state.nodes.get(id);
                    var expected = oracle.nodes.get(id);
                    if (!mine.getFrequencyState().equals(expected.getFrequencyState())
                            || Double.compare(mine.getEnergy(), expected.getEnergy()) != 0) {
                        throw new AssertionError("seed " + seed + " trace " + trace + ": node " + id
                                + " incremental " + mine.getFrequencyState().amplitude() + "/" + mine.getEnergy()
                                + " but rebuild " + expected.getFrequencyState().amplitude() + "/"
                                + expected.getEnergy());
                    }
                }
                views.add(new HashMap<>(state.known));
                if (!state.ledger.equals(oracle.ledger)) {
                    throw new AssertionError("seed " + seed + " trace " + trace + ": ledger differs\n  incremental "
                            + state.ledger + "\n  rebuild     " + oracle.ledger);
                }
            }
        }
    }

    @Test
    void incrementalStateEqualsARebuildWithoutCostRefinement() {
        run(false);
        // NotRecorded (a partition mismatch) and CapacityExhausted are covered by unit tests: they depend on the caller's
        // routing of samples and on a 4,096-entry ledger
        for (var kind : List.of("Apply", "RecordOnly", "RebuildRequired", "StaleRevision", "AlreadyApplied")) {
            assertTrue(outcomes.containsKey(kind), "never exercised " + kind + ": " + outcomes);
        }
    }

    @Test
    void incrementalStateEqualsARebuildWithCostRefinement() {
        run(true);
        assertTrue(costRefined > 0, "the cost refinement was never exercised: " + outcomes);
        assertTrue(stalePeers > 0, "no reward was ever derived against a superseded peer: " + outcomes);
        assertTrue(outcomes.containsKey("RebuildRequired"), outcomes.toString());
    }
}
