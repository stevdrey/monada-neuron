package monada.neuron.evaluation.routing;

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
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageDeclaration;
import monada.neuron.routing.outcome.UsageKind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * The host scenario fixtures {@value #VERSION}: small Forge like task sequences replayed through
 * {@link TemporalReplay} with a scripted {@link HostScript}, built by index arithmetic only (no randomness, no clock).
 *
 * <p>They reuse the #67 evidence policy, reference definition and policy (auto select tier cap 1), token prices and
 * positions ({@link SyntheticRoutingFixtures#SLOT}, {@link SyntheticRoutingFixtures#CORRECTION_OFFSET}) so every figure
 * can be recomputed the same way, but have their own scope {@value #SCOPE} and catalogs, so no #67 fixture or digest
 * changes. Every task scripts an outcome for every route its catalog lists, so an alternative always has a scripted
 * result and never reads as unobserved by accident.
 *
 * <p>Catalog {@code host-cat/1}: {@code sub-a} (tier 1; implement, review, docs), {@code sub-b} (tier 1; implement,
 * review), {@code api-x} and {@code api-y} (tier 2; qa). Two tier 2 routes make the tier cap abstention
 * ({@code POLICY_TRADEOFF_UNRESOLVED}) rank more than one candidate; {@code docs} is served by {@code sub-a} alone, so
 * its selection ranks a single candidate.
 */
final class HostScenarioFixtures {

    /** Fixture family version; any content change bumps it. */
    static final String VERSION = "forge-host-scenarios-v1";

    static final String SCOPE = "forge-host";
    static final String DOCS = "docs";

    static final RouteKey SUB_A = SyntheticRoutingFixtures.SUB_A;
    static final RouteKey SUB_B = SyntheticRoutingFixtures.SUB_B;
    static final RouteKey API_X = SyntheticRoutingFixtures.API_X;
    static final RouteKey API_Y = new RouteKey("api-y", 1);

    /** Dimensions the host's preview requires; the readiness record reports which a task leaves unknown. */
    static final Set<FeatureDimension> REQUIRED_FOR_PREVIEW =
            EnumSet.of(FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES, FeatureDimension.CHANGE_SIZE);

    static final StatePartition PARTITION = new StatePartition(SCOPE, SyntheticRoutingFixtures.EVIDENCE.id(),
            SyntheticRoutingFixtures.EVIDENCE.version());

    static final RouteCatalog CATALOG_1 = RouteCatalog.of("host-cat/1", List.of(
            entry("sub-a", BillingMode.SUBSCRIPTION, 1, List.of(SyntheticRoutingFixtures.IMPLEMENT,
                    SyntheticRoutingFixtures.REVIEW, DOCS)),
            entry("sub-b", BillingMode.SUBSCRIPTION, 1, List.of(SyntheticRoutingFixtures.IMPLEMENT,
                    SyntheticRoutingFixtures.REVIEW)),
            entry("api-x", BillingMode.API_METERED, 2, List.of(SyntheticRoutingFixtures.QA)),
            entry("api-y", BillingMode.API_METERED, 2, List.of(SyntheticRoutingFixtures.QA))));

    static final RouteKey SUB_C = new RouteKey("sub-c", 1);

    /** {@code host-cat/2}: {@code sub-b} removed, {@code sub-c} added in its place; the tier 2 routes stay. */
    static final RouteCatalog CATALOG_2 = RouteCatalog.of("host-cat/2", List.of(
            entry("sub-a", BillingMode.SUBSCRIPTION, 1, List.of(SyntheticRoutingFixtures.IMPLEMENT,
                    SyntheticRoutingFixtures.REVIEW, DOCS)),
            entry("sub-c", BillingMode.SUBSCRIPTION, 1, List.of(SyntheticRoutingFixtures.IMPLEMENT,
                    SyntheticRoutingFixtures.REVIEW)),
            entry("api-x", BillingMode.API_METERED, 2, List.of(SyntheticRoutingFixtures.QA)),
            entry("api-y", BillingMode.API_METERED, 2, List.of(SyntheticRoutingFixtures.QA))));

    /** The first task of {@code catalog-change} decided under {@code host-cat/2}. */
    static final int CATALOG_SWITCH_TASK = 12;

    /**
     * {@code cold-start}, 16 implement tasks over an empty state with both subscription routes cold; every task is
     * followed and every route passes, so a learned preference can only appear once support reaches the policy's
     * minimum.
     */
    ReplayFixture coldStart() {
        var tasks = new ArrayList<ReplayTask>(16);
        for (int i = 0; i < 16; i++) {
            tasks.add(task(i, SyntheticRoutingFixtures.IMPLEMENT, CATALOG_1, full(120L), 1,
                    (index, route) -> passing(index, 1, route)));
        }
        return fixture("cold-start", tasks);
    }

    /**
     * {@code missing-features}, 16 implement tasks; every odd task leaves {@code CATEGORY}, {@code LANGUAGES} and
     * {@code CHANGE_SIZE} unknown. The host records the readiness assessment and still follows.
     */
    ReplayFixture missingFeatures() {
        var tasks = new ArrayList<ReplayTask>(16);
        for (int i = 0; i < 16; i++) {
            TaskFeatures.Builder features = i % 2 == 0 ? full(120L) : TaskFeatures.builder();
            tasks.add(task(i, SyntheticRoutingFixtures.IMPLEMENT, CATALOG_1, features, 1,
                    (index, route) -> passing(index, 1, route)));
        }
        return fixture("missing-features", tasks);
    }

    /**
     * {@code catalog-change}, 24 implement tasks: 0 to 11 under {@code host-cat/1}, 12 to 23 under {@code host-cat/2}.
     * The cohort keys of {@code sub-a}, {@code sub-b} and {@code sub-c} are bound from the start, as
     * {@link ReplayFixture#cohortKeys()} derives them from every task's catalog. Every route passes.
     */
    ReplayFixture catalogChange() {
        var tasks = new ArrayList<ReplayTask>(24);
        for (int i = 0; i < 24; i++) {
            tasks.add(task(i, SyntheticRoutingFixtures.IMPLEMENT, i < CATALOG_SWITCH_TASK ? CATALOG_1 : CATALOG_2,
                    full(120L), 1, (index, route) -> passing(index, 1, route)));
        }
        return fixture("catalog-change", tasks);
    }

    /** {@code host-cat/1} with {@code sub-a} unavailable, so {@code sub-b} gathers evidence first. */
    static final RouteCatalog CATALOG_1_SUB_A_OUT = RouteCatalog.of("host-cat/1-sub-a-out", List.of(
            entry("sub-a", BillingMode.SUBSCRIPTION, 1, List.of(SyntheticRoutingFixtures.IMPLEMENT,
                    SyntheticRoutingFixtures.REVIEW, DOCS), Availability.UNAVAILABLE),
            entry("sub-b", BillingMode.SUBSCRIPTION, 1, List.of(SyntheticRoutingFixtures.IMPLEMENT,
                    SyntheticRoutingFixtures.REVIEW)),
            entry("api-x", BillingMode.API_METERED, 2, List.of(SyntheticRoutingFixtures.QA)),
            entry("api-y", BillingMode.API_METERED, 2, List.of(SyntheticRoutingFixtures.QA))));

    /** Decision slots until a {@code delayed-acceptance} outcome is visible. */
    static final int ACCEPTANCE_DELAY = 8;

    /** The task whose cutoff splits the leakage variants: nothing visible after it may change it or anything before. */
    static final int LEAKAGE_TASK = 12;

    /** Scope of the foreign evidence probe. */
    static final String FOREIGN_SCOPE = "forge-host-other";

    /** Evaluation policy version of the foreign evidence probe ({@code forge-gates@2}). */
    static final String FOREIGN_EVALUATION_VERSION = "2";

    /**
     * {@code delayed-acceptance}, 24 implement tasks whose outcomes are visible {@value #ACCEPTANCE_DELAY} slots after
     * their decision, so several decisions run before any evidence lands. Tasks 0 to 3 see {@code sub-a} unavailable;
     * {@code sub-a} fails tests and {@code sub-b} passes, so once both have the policy's minimum support the adaptive
     * arm can move to {@code sub-b}, and flipping later evidence can change a later decision.
     */
    ReplayFixture delayedAcceptance() {
        var tasks = new ArrayList<ReplayTask>(24);
        for (int i = 0; i < 24; i++) {
            tasks.add(task(i, SyntheticRoutingFixtures.IMPLEMENT, i < 4 ? CATALOG_1_SUB_A_OUT : CATALOG_1,
                    full(120L), ACCEPTANCE_DELAY, (index, route) -> route.equals(SUB_B)
                            ? passing(index, ACCEPTANCE_DELAY, route) : failing(index, ACCEPTANCE_DELAY, route)));
        }
        return fixture("delayed-acceptance", tasks);
    }

    /** The task at whose cutoff the host persists its checkpoint in the restart probe. */
    static final int CHECKPOINT_TASK = 15;
    /** The task whose outcome a correction supersedes after the checkpoint. */
    static final int POST_CHECKPOINT_CORRECTED_TASK = 20;
    /** The task whose outcome a correction supersedes at or before the checkpoint. */
    static final int PRE_CHECKPOINT_CORRECTED_TASK = 4;

    /**
     * {@code restart}, 32 implement tasks with one slot delay: tasks 0 to 3 see {@code sub-a} unavailable, {@code sub-a}
     * fails tests and {@code sub-b} passes, so the state learns. Two corrections flip a pass or a failure, both visible
     * after task {@value #CHECKPOINT_TASK}'s cutoff: one to task {@value #POST_CHECKPOINT_CORRECTED_TASK}'s outcome,
     * which lies after that cutoff, and one to task {@value #PRE_CHECKPOINT_CORRECTED_TASK}'s, which lies before it.
     */
    ReplayFixture restart() {
        var tasks = new ArrayList<ReplayTask>(32);
        for (int i = 0; i < 32; i++) {
            int index = i;
            BiFunction<Integer, RouteKey, ScriptedOutcome> outcome = (t, route) -> {
                ScriptedOutcome base = route.equals(SUB_B) ? passing(t, 1, route) : failing(t, 1, route);
                if (index == PRE_CHECKPOINT_CORRECTED_TASK) {
                    return base.corrected(opposite(base, correctedAt(t, 1, 13)));
                }
                if (index == POST_CHECKPOINT_CORRECTED_TASK) {
                    return base.corrected(opposite(base, correctedAt(t, 1, 2)));
                }
                return base;
            };
            tasks.add(task(i, SyntheticRoutingFixtures.IMPLEMENT, i < 4 ? CATALOG_1_SUB_A_OUT : CATALOG_1,
                    full(120L), 1, outcome));
        }
        return fixture("restart", tasks);
    }

    private long correctedAt(int i, int delay, int slots) {
        return SyntheticRoutingFixtures.SLOT * (i + 1 + delay + slots) + SyntheticRoutingFixtures.CORRECTION_OFFSET + i;
    }

    /** The opposite verdict of {@code outcome} as a correction visible at {@code at}; the stage usage is unchanged. */
    private ScriptedOutcome.Correction opposite(ScriptedOutcome outcome, long at) {
        if (outcome.status() == OutcomeStatus.ACCEPTED) {
            var failed = new EnumMap<QualityDimension, QualityState>(outcome.quality());
            failed.put(QualityDimension.SECURITY, QualityState.FAIL);
            return new ScriptedOutcome.Correction(at, OutcomeStatus.REJECTED, failed,
                    new ChainOutcome(1, ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE")));
        }
        var passed = new EnumMap<QualityDimension, QualityState>(QualityDimension.class);
        SyntheticRoutingFixtures.GATES.forEach(d -> passed.put(d, QualityState.PASS));
        ResourceSummary stage = outcome.stage();
        return new ScriptedOutcome.Correction(at, OutcomeStatus.ACCEPTED, passed,
                new ChainOutcome(1, ReplayCost.available(new Money(SyntheticRoutingFixtures.price(stage), "USD"))));
    }

    /** {@code base} reissued for another scope and evaluation policy version, every other field equal. */
    RoutingRequest foreignRequest(RoutingRequest base, String scope, String evaluationPolicyVersion) {
        return new RoutingRequest(base.contractVersion(), scope, base.taskId(), base.executionId(), base.attemptId(),
                base.stageId(), base.stageKind(), base.requestOrdinal(), base.sourceFingerprint(),
                base.contextFingerprint(), base.constraintsFingerprint(), base.evaluationPolicyId(),
                evaluationPolicyVersion, base.features(), base.requirements(), base.overflowPermitted(),
                base.cutoff());
    }

    /** Stage of each block of four tasks in the {@code disposition} scenario. */
    private static final List<String> DISPOSITION_STAGES = List.of(SyntheticRoutingFixtures.IMPLEMENT,
            SyntheticRoutingFixtures.QA, SyntheticRoutingFixtures.IMPLEMENT, DOCS);

    /**
     * {@code disposition}, 32 tasks: blocks of four cycle the stages implement, qa, implement, docs, and inside each
     * block the person follows, picks an alternative, defers and declines, in that order. Over implement every
     * variant binds and the alternative runs {@code sub-b} or {@code sub-a}; over qa the tier cap abstains, so
     * following is rejected ({@code ADVICE_NOT_SELECTED}) and the alternative binds but runs nothing; over docs the
     * only ranked route is the advised one, so the alternative is rejected ({@code ALTERNATIVE_EQUALS_ADVICE}).
     * Every route passes.
     */
    ReplayFixture disposition() {
        var tasks = new ArrayList<ReplayTask>(32);
        for (int i = 0; i < 32; i++) {
            tasks.add(task(i, DISPOSITION_STAGES.get((i / 4) % 4), CATALOG_1, full(120L), 1,
                    (index, route) -> passing(index, 1, route)));
        }
        return fixture("disposition", tasks);
    }

    /** The {@code disposition} script: follow, alternative, defer, decline by task index modulo four. */
    Map<Integer, HostAction> dispositionScript() {
        var script = new TreeMap<Integer, HostAction>();
        HostAction[] cycle = HostAction.values();
        for (int i = 0; i < 32; i++) {
            script.put(i, cycle[i % cycle.length]);
        }
        return script;
    }

    /** The decision cutoff of task {@code i}. */
    long cutoff(int i) {
        return SyntheticRoutingFixtures.SLOT * (i + 1);
    }

    private long observedAt(int i, int delay) {
        return SyntheticRoutingFixtures.SLOT * (i + 1 + delay) + 1 + i;
    }

    private ScriptedOutcome passing(int i, int delay, RouteKey route) {
        ResourceSummary stage = stage(route);
        return ScriptedOutcome.passing(observedAt(i, delay), SyntheticRoutingFixtures.GATES, stage,
                new ChainOutcome(1, ReplayCost.available(new Money(SyntheticRoutingFixtures.price(stage), "USD"))));
    }

    private ScriptedOutcome failing(int i, int delay, RouteKey route) {
        return ScriptedOutcome.failing(observedAt(i, delay), SyntheticRoutingFixtures.GATES,
                QualityDimension.TESTS, stage(route),
                new ChainOutcome(1, ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE")));
    }

    private ResourceSummary stage(RouteKey route) {
        return new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(), List.of(
                reported(UsageKind.INPUT_TOKENS, SyntheticRoutingFixtures.inputTokens(route)),
                reported(UsageKind.OUTPUT_TOKENS, SyntheticRoutingFixtures.outputTokens(route))), List.of(),
                List.of());
    }

    private UsageCounter reported(UsageKind kind, long value) {
        return new UsageCounter(kind, new ResourceValue.Known(value, ResourceValue.Provenance.REPORTED));
    }

    /** Features with every preview dimension known. */
    private TaskFeatures.Builder full(long changeSize) {
        return TaskFeatures.builder().category("feature").languages(List.of("java")).changeSize(changeSize);
    }

    /**
     * One task whose outcomes are scripted for every route its catalog lists.
     *
     * @param delay decision slots until its outcome is visible, at least 1
     */
    private ReplayTask task(int i, String stage, RouteCatalog catalog, TaskFeatures.Builder features, int delay,
            BiFunction<Integer, RouteKey, ScriptedOutcome> outcome) {
        var outcomes = new TreeMap<RouteKey, ScriptedOutcome>();
        for (CatalogEntry entry : catalog.entries()) {
            RouteKey route = entry.key();
            outcomes.put(route, outcome.apply(i, route));
        }
        return new ReplayTask(request(i, stage, features), catalog, OutcomeOracle.KnownForEveryRoute.of(outcomes));
    }

    private RoutingRequest request(int i, String stage, TaskFeatures.Builder features) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, SCOPE, "task-" + i, "exe-" + i, "att-1",
                "stage-" + stage, stage, i, "src-1", "ctx-1", "con-1", SyntheticRoutingFixtures.EVIDENCE.id(),
                SyntheticRoutingFixtures.EVIDENCE.version(), features.stageKind(stage).build(),
                HardRequirements.allowing(List.of("hosted"), List.of("sandboxed")), false, cutoff(i));
    }

    private ReplayFixture fixture(String scenario, List<ReplayTask> tasks) {
        return new ReplayFixture(VERSION + "." + scenario, "1", SyntheticRoutingFixtures.DEFINITION,
                SyntheticRoutingFixtures.EVIDENCE, SyntheticRoutingFixtures.POLICY, PARTITION, tasks);
    }

    private static CatalogEntry entry(String id, BillingMode billing, int tier, List<String> stages) {
        return entry(id, billing, tier, stages, Availability.AVAILABLE);
    }

    private static CatalogEntry entry(String id, BillingMode billing, int tier, List<String> stages,
            Availability availability) {
        return new CatalogEntry(RouteDescriptor.builder(id, 1).worker("host-worker").provider("fixture").model(id)
                .billingMode(billing).tier(tier).stages(stages).executionModes(List.of("sandboxed")).locality("hosted")
                .build(), availability, 1);
    }
}
