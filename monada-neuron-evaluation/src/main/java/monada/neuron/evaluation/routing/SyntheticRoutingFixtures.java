package monada.neuron.evaluation.routing;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.adaptation.AdaptiveStateDefinition;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.StageAttribution;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageDeclaration;
import monada.neuron.routing.outcome.UsageKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * The synthetic replay fixtures {@value #VERSION}: small, fully enumerated workloads with a known outcome for every
 * route, built by index arithmetic only (no randomness, no clock).
 *
 * <p>Every fixture shares one scope, one evaluation policy (mandatory {@code TESTS} and {@code SECURITY}), the
 * reference adaptive definition ({@link AdaptiveStateDefinition#reference()}, deliberately untuned) and the reference
 * lexicographic policy with an auto-select tier cap of 1. The catalog holds two interchangeable subscription routes
 * ({@code sub-a}, {@code sub-b}: same tier and priority, so only learning or route order separates them) and one
 * tier-2 API route ({@code api-x}) that only serves {@code qa} and that the cap never auto-selects. Learned preference
 * applies only when every eligible route has enough support, so {@code api-x} is kept out of the learned stages. A
 * {@code sub-a} outage catalog lets the other route gather evidence before both are eligible; without it an on-policy
 * replay would never observe {@code sub-b}.
 *
 * <p>Positions: task {@code i} is decided at {@code 4096 (i + 1)}; its outcome is visible {@code d} decision slots
 * later at {@code 4096 (i + 1 + d) + 1 + i} and a correction at {@code 4096 (i + 1 + d + c) + 2049 + i}, so positions
 * are unique for {@code i < 2047} and an outcome always lands strictly between two decisions (at {@code i = 2047} a
 * correction would land on the next decision's cutoff).
 *
 * <p>Costs are hypothetical API-equivalent USD per chain, priced from each attempt's tokens at the list prices below,
 * the same table and prices the Store replay arm records, so every amount can be recomputed by hand: one {@code sub-a}
 * attempt (1200 input, 300 output tokens) is {@code 0.0420}, one {@code sub-b} attempt (1500, 400) is {@code 0.0545},
 * a repair attempt (500, 300) is {@code 0.0315}, and a stage whose output is unknown prices only its 900 known input
 * tokens, a {@code 0.0135} lower bound.
 */
public final class SyntheticRoutingFixtures {

    /** Fixture family version; any content change bumps it. */
    public static final String VERSION = "forge-routing-replay-v5";

    static final String SCOPE = "forge-replay";
    static final String IMPLEMENT = "implement";
    static final String REVIEW = "review";
    static final String QA = "qa";
    static final String DEPLOY = "deploy";

    static final RouteKey SUB_A = new RouteKey("sub-a", 1);
    static final RouteKey SUB_B = new RouteKey("sub-b", 1);
    static final RouteKey API_X = new RouteKey("api-x", 1);

    static final List<QualityDimension> GATES = List.of(QualityDimension.TESTS, QualityDimension.SECURITY);
    static final EvidencePolicy EVIDENCE = new EvidencePolicy("forge-gates", "1", Set.copyOf(GATES));
    static final AdaptiveStateDefinition DEFINITION = AdaptiveStateDefinition.reference();
    static final LexicographicRoutingPolicy POLICY = new LexicographicRoutingPolicy(DEFINITION.state(),
            LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.of(1), Optional.empty());
    static final StatePartition PARTITION = new StatePartition(SCOPE, EVIDENCE.id(), EVIDENCE.version());

    /** List price per 1000 input tokens, USD. */
    static final BigDecimal INPUT_PER_THOUSAND = new BigDecimal("0.015");
    /** List price per 1000 output tokens, USD. */
    static final BigDecimal OUTPUT_PER_THOUSAND = new BigDecimal("0.080");
    /** Input tokens known for a stage whose output tokens are unknown. */
    static final long UNKNOWN_USAGE_INPUT_TOKENS = 900;
    /** Input and output tokens of a repair attempt. */
    static final long REPAIR_INPUT_TOKENS = 500;
    static final long REPAIR_OUTPUT_TOKENS = 300;

    /** Ledger positions per decision slot. */
    static final long SLOT = 4096;
    /** Offset of a correction inside its slot, after every original outcome position of the slot. */
    static final long CORRECTION_OFFSET = 2049;

    static final RouteCatalog FULL = catalog("replay-cat/1", true);
    static final RouteCatalog SUB_A_OUTAGE = catalog("replay-cat/1-sub-a-out", false);

    private final ObservationFactory observations = new ObservationFactory();

    /**
     * {@code sub-a} always fails tests and {@code sub-b} always passes. Four outage tasks give {@code sub-b} evidence;
     * after that the static rule keeps advising {@code sub-a}, while the adaptive arm can move once {@code sub-a} has
     * enough support. Expected direction: adaptive accepts more.
     */
    public ReplayFixture improvement(int tasks) {
        var list = new ArrayList<ReplayTask>(tasks);
        for (int i = 0; i < tasks; i++) {
            list.add(task(i, IMPLEMENT, 120L, "ctx-1", "task-" + i, i < 4 ? SUB_A_OUTAGE : FULL,
                    Map.of(SUB_A, failing(i, 1, SUB_A, QualityDimension.TESTS), SUB_B, passing(i, 1, SUB_B))));
        }
        return fixture("improvement", list);
    }

    /**
     * Drift: {@code sub-b} passes during the six outage tasks and fails afterwards, while {@code sub-a} always passes.
     * The static rule keeps the always-passing route; learning from stale {@code sub-b} rewards can move the adaptive
     * arm onto a route that now fails. Expected direction: adaptive accepts fewer.
     */
    public ReplayFixture degradation(int tasks) {
        var list = new ArrayList<ReplayTask>(tasks);
        for (int i = 0; i < tasks; i++) {
            boolean outage = i < 6;
            list.add(task(i, IMPLEMENT, 120L, "ctx-1", "task-" + i, outage ? SUB_A_OUTAGE : FULL,
                    Map.of(SUB_A, passing(i, 1, SUB_A),
                            SUB_B, outage ? passing(i, 1, SUB_B) : failing(i, 1, SUB_B, QualityDimension.TESTS))));
        }
        return fixture("degradation", list);
    }

    /**
     * Every outcome is neutral: environmental failures, cancellations, pending outcomes and imported claims. Feedback
     * is derived but none may move a Node, so decisions equal the static arm's.
     */
    public ReplayFixture neutral(int tasks) {
        var list = new ArrayList<ReplayTask>(tasks);
        for (int i = 0; i < tasks; i++) {
            list.add(task(i, IMPLEMENT, 120L, "ctx-1", "task-" + i, i < 4 ? SUB_A_OUTAGE : FULL,
                    Map.of(SUB_A, neutralOutcome(i, SUB_A), SUB_B, neutralOutcome(i, SUB_B))));
        }
        return fixture("neutral", list);
    }

    /**
     * Named edge cases, all advised to {@code sub-a} by every arm (no route ever gains enough support):
     * <ol start="0">
     *   <li>cheap invalid: few tokens, but security proven to fail, so no cost and a penalty, never a reward;</li>
     *   <li>repaired accepted: the routed attempt failed and a repair was accepted; chain-only attribution earns no
     *       credit and the chain cost covers both attempts once ({@code 0.0420 + 0.0315 = 0.0735 USD});</li>
     *   <li>unknown usage: output tokens unknown and reasoning tokens not measured; the cost is a partial lower
     *       bound ({@code 0.0135 USD}, the known input tokens only);</li>
     *   <li>3 and 4: the same task under two different context fingerprints: an incomparable pair;</li>
     *   <li>5 and 6: the same task under identical keys: a directly comparable pair;</li>
     *   <li>7: two currencies ({@code 0.0420 USD}, {@code 0.0390 EUR}), never summed together;</li>
     *   <li>8: a {@code qa} stage only the tier-2 route serves: the tier cap abstains;</li>
     *   <li>9: a {@code deploy} stage no route serves: no eligible route;</li>
     *   <li>10: a pass corrected to a security failure two slots later, which requires a rebuild;</li>
     *   <li>11 and 12: the same task and fingerprints routed for {@code implement} and for {@code review}: different
     *       stages are never a direct pair.</li>
     * </ol>
     */
    public ReplayFixture cases() {
        var list = new ArrayList<ReplayTask>();
        var cheap = new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(),
                List.of(reported(UsageKind.INPUT_TOKENS, 200), reported(UsageKind.OUTPUT_TOKENS, 50)), List.of(),
                List.of());
        list.add(task(0, IMPLEMENT, 20L, "ctx-1", "task-cheap", FULL, Map.of(SUB_A,
                ScriptedOutcome.failing(observedAt(0, 1), GATES, QualityDimension.SECURITY, cheap,
                        new ChainOutcome(1, ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE"))))));
        var repaired = ScriptedOutcome.acceptedByRepair(observedAt(1, 1), GATES, stage(SUB_A),
                new ChainOutcome(2, 2, chainCost(stage(SUB_A), 2)));
        list.add(task(1, IMPLEMENT, 20L, "ctx-1", "task-repair", FULL, Map.of(SUB_A, repaired)));
        var unknown = new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(),
                List.of(reported(UsageKind.INPUT_TOKENS, UNKNOWN_USAGE_INPUT_TOKENS),
                        new UsageCounter(UsageKind.OUTPUT_TOKENS, new ResourceValue.Unknown()),
                        new UsageCounter(UsageKind.REASONING_TOKENS, new ResourceValue.NotMeasured())),
                List.of(), List.of());
        list.add(task(2, IMPLEMENT, 20L, "ctx-1", "task-unknown-usage", FULL, Map.of(SUB_A,
                ScriptedOutcome.passing(observedAt(2, 1), GATES, unknown, new ChainOutcome(1,
                        chainCost(unknown, 1))))));
        list.add(task(3, IMPLEMENT, 20L, "ctx-1", "task-context", FULL, Map.of(SUB_A, passing(3, 1, SUB_A))));
        list.add(task(4, IMPLEMENT, 20L, "ctx-2", "task-context", FULL, Map.of(SUB_A, passing(4, 1, SUB_A))));
        list.add(task(5, IMPLEMENT, 20L, "ctx-1", "task-pair", FULL, Map.of(SUB_A, passing(5, 1, SUB_A))));
        list.add(task(6, IMPLEMENT, 20L, "ctx-1", "task-pair", FULL, Map.of(SUB_A, passing(6, 1, SUB_A))));
        list.add(task(7, IMPLEMENT, 20L, "ctx-1", "task-currencies", FULL, Map.of(SUB_A,
                ScriptedOutcome.passing(observedAt(7, 1), GATES, stage(SUB_A), new ChainOutcome(1,
                        ReplayCost.available(usd(price(stage(SUB_A))),
                                new Money(new BigDecimal("0.0390"), "EUR")))))));
        list.add(task(8, QA, 20L, "ctx-1", "task-qa", FULL, Map.of(API_X, passing(8, 1, API_X))));
        list.add(task(9, DEPLOY, 20L, "ctx-1", "task-deploy", FULL, Map.of()));
        var corrected = passing(10, 1, SUB_A).corrected(new ScriptedOutcome.Correction(correctedAt(10, 1, 2),
                OutcomeStatus.REJECTED, failQuality(QualityDimension.SECURITY),
                new ChainOutcome(1, ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE"))));
        list.add(task(10, IMPLEMENT, 20L, "ctx-1", "task-corrected", FULL, Map.of(SUB_A, corrected)));
        list.add(task(11, IMPLEMENT, 20L, "ctx-1", "task-stages", FULL, Map.of(SUB_A, passing(11, 1, SUB_A))));
        list.add(task(12, REVIEW, 20L, "ctx-1", "task-stages", FULL, Map.of(SUB_A, passing(12, 1, SUB_A))));
        return fixture("cases", list);
    }

    /**
     * A store-like export: only the logged route's outcome exists. The logging policy was the static rule, so the log
     * holds {@code sub-b} passes during a four-task outage and {@code sub-a} failures afterwards; the last task was
     * executed but its outcome was never exported. Once the adaptive arm prefers {@code sub-b}, its choices have no
     * observed outcome and must be reported as missing counterfactuals.
     */
    public ReplayFixture chosenRouteOnly(int tasks) {
        var list = new ArrayList<ReplayTask>(tasks);
        for (int i = 0; i < tasks; i++) {
            RouteCatalog catalog = i < 4 ? SUB_A_OUTAGE : FULL;
            RoutingRequest request = request(i, IMPLEMENT, 120L, "ctx-1", "task-" + i);
            var logged = (RoutingDecision.Selected) POLICY.decide(request, catalog,
                    RoutingPreference.empty(request, DEFINITION.state(), POLICY.policyId(), POLICY.policyVersion()));
            ScriptedOutcome scripted = logged.route().equals(SUB_B) ? passing(i, 1, SUB_B)
                    : failing(i, 1, SUB_A, QualityDimension.TESTS);
            List<RoutingObservation> observed = i == tasks - 1 ? List.of()
                    : List.of(observations.observe(request, logged, scripted, 0));
            list.add(new ReplayTask(request, catalog,
                    new OutcomeOracle.ChosenRouteOnly(logged, observed, scripted.chain())));
        }
        return fixture("chosen-route-only", list);
    }

    /**
     * The measured workload: two routed stages, four change-size buckets, a {@code sub-a} outage over the first
     * eighth, delays of one to three slots, a correction every 17th task, unknown usage every 11th, a repair every
     * 13th and a {@code qa} abstention every 19th. Outcomes follow fixed residues of the task index per route and
     * stage, so both routes are good in some cohorts and poor in others.
     */
    public ReplayFixture workload(int tasks) {
        long[] sizes = {20L, 120L, 900L, -1L};
        var list = new ArrayList<ReplayTask>(tasks);
        for (int i = 0; i < tasks; i++) {
            if (i % 19 == 18) {
                list.add(task(i, QA, sizes[(i / 2) % 4], "ctx-1", "task-" + i, FULL,
                        Map.of(API_X, passing(i, 1, API_X))));
                continue;
            }
            String stage = i % 2 == 0 ? IMPLEMENT : REVIEW;
            int delay = 1 + i % 3;
            var outcomes = new TreeMap<RouteKey, ScriptedOutcome>();
            for (RouteKey route : List.of(SUB_A, SUB_B)) {
                boolean passes = route.equals(SUB_A)
                        ? (stage.equals(IMPLEMENT) ? i % 3 != 0 : i % 4 == 0)
                        : (stage.equals(IMPLEMENT) ? i % 5 != 0 : i % 4 != 0);
                ScriptedOutcome outcome = passes ? passing(i, delay, route)
                        : failing(i, delay, route, QualityDimension.TESTS);
                if (i % 13 == 12 && passes) {
                    outcome = ScriptedOutcome.acceptedByRepair(observedAt(i, delay), GATES, stage(route),
                            new ChainOutcome(2, 2, chainCost(stage(route), 2)));
                }
                if (i % 11 == 10) {
                    outcome = unknownUsage(outcome);
                }
                if (i % 17 == 16) {
                    outcome = outcome.corrected(flip(outcome, correctedAt(i, delay, 1)));
                }
                outcomes.put(route, outcome);
            }
            RouteCatalog catalog = i < tasks / 8 ? SUB_A_OUTAGE : FULL;
            list.add(task(i, stage, sizes[(i / 2) % 4], "ctx-1", "task-" + i, catalog, outcomes));
        }
        return fixture("workload", list);
    }

    /**
     * The same fixture with every outcome and correction visible at or after {@code from} replaced by its opposite
     * (pass and fail swapped, usage and cost changed). Nothing visible before {@code from} changes.
     */
    public ReplayFixture futureFlipped(ReplayFixture fixture, long from) {
        var list = new ArrayList<ReplayTask>(fixture.tasks().size());
        for (ReplayTask task : fixture.tasks()) {
            if (!(task.oracle() instanceof OutcomeOracle.KnownForEveryRoute known)) {
                throw new IllegalArgumentException("only synthetic outcomes can be flipped");
            }
            var outcomes = new TreeMap<RouteKey, ScriptedOutcome>();
            known.outcomes().forEach((route, outcome) -> outcomes.put(route, flippedFrom(outcome, from)));
            list.add(new ReplayTask(task.request(), task.catalog(), new OutcomeOracle.KnownForEveryRoute(outcomes)));
        }
        return fixture.withTasks(fixture.fixtureId() + "+flipped@" + from, list);
    }

    /** The same fixture with only the tasks decided before {@code before}. */
    public ReplayFixture truncated(ReplayFixture fixture, long before) {
        return fixture.withTasks(fixture.fixtureId() + "+truncated@" + before,
                fixture.tasks().stream().filter(t -> t.request().cutoff() < before).toList());
    }

    /**
     * The same fixture with a late correction added to every outcome of task {@code index}: visible {@code slots}
     * decision slots after the original, it swaps pass and fail.
     */
    public ReplayFixture lateCorrection(ReplayFixture fixture, int index, int slots) {
        var list = new ArrayList<ReplayTask>(fixture.tasks().size());
        for (ReplayTask task : fixture.tasks()) {
            if (task.request().requestOrdinal() == index
                    && task.oracle() instanceof OutcomeOracle.KnownForEveryRoute known) {
                var outcomes = new TreeMap<RouteKey, ScriptedOutcome>();
                known.outcomes().forEach((route, outcome) -> outcomes.put(route,
                        outcome.corrected(flip(outcome, outcome.observedAt() + SLOT * slots + SLOT / 2))));
                list.add(new ReplayTask(task.request(), task.catalog(),
                        new OutcomeOracle.KnownForEveryRoute(outcomes)));
            } else {
                list.add(task);
            }
        }
        return fixture.withTasks(fixture.fixtureId() + "+late-correction@" + index + "x" + slots, list);
    }

    /** The cutoff at which task {@code i} is decided. */
    public long cutoff(int i) {
        return SLOT * (i + 1);
    }

    private ScriptedOutcome flippedFrom(ScriptedOutcome outcome, long from) {
        ScriptedOutcome base = outcome;
        if (outcome.observedAt() >= from) {
            ResourceSummary usage = doubled(outcome.stage());
            ScriptedOutcome.Correction opposite = opposite(outcome.status(), outcome.chain(), usage,
                    outcome.observedAt());
            // the flipped outcome is a fresh, non-repaired verdict on the same chain, so its attribution follows the
            // chain (contract "StageAttribution"), not the outcome it replaced
            base = new ScriptedOutcome(outcome.observedAt(), InvocationStatus.COMPLETED, opposite.status(),
                    OutcomeOrigin.VALIDATED, opposite.quality(), attributionOf(opposite.chain()), false, usage,
                    opposite.chain(), Optional.empty());
            if (outcome.correction().isPresent()) {
                base = base.corrected(outcome.correction().get());
            }
        }
        if (outcome.correction().isPresent() && outcome.correction().get().observedAt() >= from) {
            ScriptedOutcome.Correction original = outcome.correction().get();
            base = base.corrected(opposite(original.status(), original.chain(), base.stage(),
                    original.observedAt()));
        }
        return base;
    }

    /** The opposite verdict of an outcome as a revision visible at {@code at}; the routed stage's usage is unchanged. */
    private ScriptedOutcome.Correction flip(ScriptedOutcome outcome, long at) {
        return opposite(outcome.status(), outcome.chain(), outcome.stage(), at);
    }

    /**
     * The attribution a verdict on {@code chain} can carry: stage-local only for one stage with one attempt (no later
     * attempt exists), chain-only for two or more stages, and none for one stage retried.
     */
    private static StageAttribution.Basis attributionOf(ChainOutcome chain) {
        if (chain.stages() >= 2) {
            return StageAttribution.Basis.CHAIN_ONLY;
        }
        return chain.attempts() == 1 ? StageAttribution.Basis.STAGE_LOCAL : StageAttribution.Basis.NONE;
    }

    /**
     * The opposite of {@code status} over the same chain: an acceptance becomes a test failure with no cost, anything
     * else a validated acceptance priced from {@code usage}. The chain keeps its attempts and stages, which the
     * correction does not undo.
     */
    private ScriptedOutcome.Correction opposite(OutcomeStatus status, ChainOutcome chain, ResourceSummary usage,
            long at) {
        boolean accepted = status == OutcomeStatus.ACCEPTED;
        return new ScriptedOutcome.Correction(at, accepted ? OutcomeStatus.REJECTED : OutcomeStatus.ACCEPTED,
                accepted ? failQuality(QualityDimension.TESTS) : passQuality(),
                new ChainOutcome(chain.attempts(), chain.stages(), accepted
                        ? ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE") : chainCost(usage, chain.attempts())));
    }

    /**
     * The cost of an accepted chain of {@code attempts}: the routed stage priced from {@code usage}, plus one repair
     * attempt per later attempt. Complete only when every routed-stage counter is known; an unknown or not-measured
     * counter leaves that stage uncovered, so the priced counters are a partial lower bound, never a complete cost.
     */
    private ReplayCost chainCost(ResourceSummary usage, int attempts) {
        BigDecimal amount = price(usage).add(repairPrice().multiply(BigDecimal.valueOf(attempts - 1L)));
        boolean uncovered = usage.counters().stream().anyMatch(c -> !(c.value() instanceof ResourceValue.Known));
        return uncovered ? ReplayCost.partial(List.of(usd(amount)), "UNKNOWN_USAGE")
                : ReplayCost.available(usd(amount));
    }

    /** The known input and output tokens of {@code usage} at the list prices; anything else is not priced. */
    static BigDecimal price(ResourceSummary usage) {
        long input = 0;
        long output = 0;
        for (UsageCounter counter : usage.counters()) {
            if (counter.value() instanceof ResourceValue.Known known) {
                switch (counter.kind()) {
                    case INPUT_TOKENS -> input += known.value();
                    case OUTPUT_TOKENS -> output += known.value();
                    default -> {
                        // only input and output tokens are priced
                    }
                }
            }
        }
        return listPrice(input, output);
    }

    /**
     * {@code input} and {@code output} tokens at the list prices, in USD at four decimals: the one pricing formula the
     * fixtures and every hand-calculated check use. Exact for the token counts used here; any other count that the
     * scale cannot hold exactly is rejected rather than rounded.
     */
    static BigDecimal listPrice(long input, long output) {
        return INPUT_PER_THOUSAND.multiply(BigDecimal.valueOf(input))
                .add(OUTPUT_PER_THOUSAND.multiply(BigDecimal.valueOf(output)))
                .movePointLeft(3).setScale(4, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal repairPrice() {
        return listPrice(REPAIR_INPUT_TOKENS, REPAIR_OUTPUT_TOKENS);
    }

    /**
     * The same outcome with the routed stage's output tokens unknown. An accepted chain keeps its repair attempts'
     * known cost in its partial lower bound; only the routed stage's unknown share is missing.
     */
    private ScriptedOutcome unknownUsage(ScriptedOutcome outcome) {
        var stage = new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(),
                List.of(reported(UsageKind.INPUT_TOKENS, UNKNOWN_USAGE_INPUT_TOKENS),
                        new UsageCounter(UsageKind.OUTPUT_TOKENS, new ResourceValue.Unknown())),
                List.of(), List.of());
        ReplayCost cost = outcome.chain().cost().status() == ReplayCost.Status.UNAVAILABLE ? outcome.chain().cost()
                : chainCost(stage, outcome.chain().attempts());
        return new ScriptedOutcome(outcome.observedAt(), outcome.invocation(), outcome.status(), outcome.origin(),
                outcome.quality(), outcome.attribution(), outcome.acceptedByRepair(), stage,
                new ChainOutcome(outcome.chain().attempts(), outcome.chain().stages(), cost), outcome.correction());
    }

    private ScriptedOutcome neutralOutcome(int i, RouteKey route) {
        ScriptedOutcome passing = passing(i, 1, route);
        var noCost = new ChainOutcome(1, ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE"));
        return switch (i % 4) {
            case 0 -> new ScriptedOutcome(passing.observedAt(), InvocationStatus.TIMED_OUT, OutcomeStatus.FAILED,
                    OutcomeOrigin.VALIDATED, Map.of(), passing.attribution(), false, passing.stage(), noCost,
                    Optional.empty());
            case 1 -> new ScriptedOutcome(passing.observedAt(), InvocationStatus.CANCELLED, OutcomeStatus.CANCELLED,
                    OutcomeOrigin.VALIDATED, Map.of(), passing.attribution(), false, passing.stage(), noCost,
                    Optional.empty());
            case 2 -> new ScriptedOutcome(passing.observedAt(), InvocationStatus.COMPLETED, OutcomeStatus.PENDING,
                    OutcomeOrigin.VALIDATED, passing.quality(), passing.attribution(), false, passing.stage(), noCost,
                    Optional.empty());
            default -> new ScriptedOutcome(passing.observedAt(), InvocationStatus.COMPLETED, OutcomeStatus.ACCEPTED,
                    OutcomeOrigin.IMPORTED_CLAIM, passing.quality(), passing.attribution(), false, passing.stage(),
                    noCost, Optional.empty());
        };
    }

    private ScriptedOutcome passing(int i, int delay, RouteKey route) {
        return ScriptedOutcome.passing(observedAt(i, delay), GATES, stage(route),
                new ChainOutcome(1, chainCost(stage(route), 1)));
    }

    private ScriptedOutcome failing(int i, int delay, RouteKey route, QualityDimension failed) {
        return ScriptedOutcome.failing(observedAt(i, delay), GATES, failed, stage(route),
                new ChainOutcome(1, ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE")));
    }

    private long observedAt(int i, int delay) {
        return SLOT * (i + 1 + delay) + 1 + i;
    }

    private long correctedAt(int i, int delay, int slots) {
        return SLOT * (i + 1 + delay + slots) + CORRECTION_OFFSET + i;
    }

    /** Input tokens of one attempt of {@code route}; {@code sub-b} is the larger route. */
    static long inputTokens(RouteKey route) {
        return route.equals(SUB_B) ? 1500 : 1200;
    }

    /** Output tokens of one attempt of {@code route}. */
    static long outputTokens(RouteKey route) {
        return route.equals(SUB_B) ? 400 : 300;
    }

    private ResourceSummary stage(RouteKey route) {
        long input = inputTokens(route);
        long output = outputTokens(route);
        return new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(),
                List.of(reported(UsageKind.INPUT_TOKENS, input), reported(UsageKind.OUTPUT_TOKENS, output)),
                List.of(), List.of());
    }

    private ResourceSummary doubled(ResourceSummary stage) {
        var counters = stage.counters().stream().map(c -> c.value() instanceof ResourceValue.Known known
                ? new UsageCounter(c.kind(), new ResourceValue.Known(known.value() * 2, known.provenance())) : c)
                .toList();
        return new ResourceSummary(stage.declaration(), stage.noBillableJustification(), counters,
                stage.actualBilling(), stage.hypotheticalCosts());
    }

    private Map<QualityDimension, QualityState> passQuality() {
        var quality = new EnumMap<QualityDimension, QualityState>(QualityDimension.class);
        GATES.forEach(d -> quality.put(d, QualityState.PASS));
        return quality;
    }

    private Map<QualityDimension, QualityState> failQuality(QualityDimension failed) {
        var quality = passQuality();
        quality.put(failed, QualityState.FAIL);
        return quality;
    }

    private UsageCounter reported(UsageKind kind, long value) {
        return new UsageCounter(kind, new ResourceValue.Known(value, ResourceValue.Provenance.REPORTED));
    }

    private Money usd(BigDecimal amount) {
        return new Money(amount, "USD");
    }

    private ReplayTask task(int i, String stage, long changeSize, String context, String taskId, RouteCatalog catalog,
            Map<RouteKey, ScriptedOutcome> outcomes) {
        return new ReplayTask(request(i, stage, changeSize, context, taskId), catalog,
                OutcomeOracle.KnownForEveryRoute.of(outcomes));
    }

    private RoutingRequest request(int i, String stage, long changeSize, String context, String taskId) {
        var features = TaskFeatures.builder().stageKind(stage);
        if (changeSize >= 0) {
            features.changeSize(changeSize);
        }
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, SCOPE, taskId, "exe-" + i, "att-1",
                "stage-" + stage, stage, i, "src-1", context, "con-1", EVIDENCE.id(), EVIDENCE.version(),
                features.build(), HardRequirements.allowing(List.of("hosted"), List.of("sandboxed")), false,
                cutoff(i));
    }

    private ReplayFixture fixture(String scenario, List<ReplayTask> tasks) {
        return new ReplayFixture(VERSION + "." + scenario, "1", DEFINITION, EVIDENCE, POLICY, PARTITION, tasks);
    }

    private static RouteCatalog catalog(String version, boolean subAAvailable) {
        return RouteCatalog.of(version, List.of(
                new CatalogEntry(route("sub-a", BillingMode.SUBSCRIPTION, 1, List.of(IMPLEMENT, REVIEW)),
                        subAAvailable ? Availability.AVAILABLE : Availability.UNAVAILABLE, 1),
                new CatalogEntry(route("sub-b", BillingMode.SUBSCRIPTION, 1, List.of(IMPLEMENT, REVIEW)),
                        Availability.AVAILABLE, 1),
                new CatalogEntry(route("api-x", BillingMode.API_METERED, 2, List.of(QA)),
                        Availability.AVAILABLE, 1)));
    }

    private static RouteDescriptor route(String id, BillingMode billing, int tier, List<String> stages) {
        return RouteDescriptor.builder(id, 1).worker("replay-worker").provider("fixture").model(id)
                .billingMode(billing).tier(tier).stages(stages).executionModes(List.of("sandboxed")).locality("hosted")
                .build();
    }
}
