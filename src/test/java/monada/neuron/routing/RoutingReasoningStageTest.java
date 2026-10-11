package monada.neuron.routing;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.evaluation.EvaluationCognitiveStageResult;
import monada.neuron.evaluation.HypothesisEvaluationCognitiveStage;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.Proposition;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.ResourceEstimate;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

import static monada.neuron.routing.RoutingFixtures.demo;
import static monada.neuron.routing.RoutingFixtures.empty;
import static monada.neuron.routing.RoutingFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingReasoningStageTest {

    private static final int DOMAIN = 7;
    private static final List<RoutingRule> APPLIED =
            List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.ROUTE_ORDER);
    private static final List<RoutingRule> SKIPPED =
            List.of(RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE);
    private static final PolicyParameters PARAMETERS =
            new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "routing-state/1");
    private static final CognitiveBudget MINIMUM = new CognitiveBudget(1, 1, 0);
    private static final Optional<HostExecutionContext> HOST =
            Optional.of(HostExecutionContext.of(new HostReference("exec-1")));

    private final LexicographicRoutingPolicy policy = LexicographicRoutingPolicy.reference();

    private static PrimaryMonad monad() {
        return new PrimaryMonad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private RoutingReasoningStage stage(boolean overflow, List<String> capabilities) {
        var request = request(capabilities, overflow);
        return new RoutingReasoningStage(policy,
                context -> Optional.of(new RoutingInput(request, demo(), empty(request))), DOMAIN);
    }

    private static CognitiveStage untouchedStage(CognitiveStageKind kind) {
        return new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return kind;
            }

            @Override
            public CognitiveStageResult execute(PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                throw new AssertionError("a stage without typed-only support must not run");
            }
        };
    }

    @Test
    void routingStageRetainsTheFullDecisionThroughCycleNormalization() {
        var cycle = new DeterministicCognitiveCycle(List.of(stage(false, List.of("java"))));

        CognitiveCycleResult result = cycle.execute(monad(), List.of(), MINIMUM, HOST);

        var routed = assertInstanceOf(RoutingCognitiveStageResult.class, result.stageResults().getFirst());
        var selected = assertInstanceOf(RoutingDecision.Selected.class, routed.decision());
        assertEquals(RoutingFixtures.SUB_A, selected.route());
        assertEquals(List.of(), routed.outputSignals());
        assertEquals(List.of(), result.outputSignals());
        // catalog canonical order is api-x, sub-a, sub-b: sub-a is index 1.
        assertEquals(new Proposition(DOMAIN, 1), routed.hypotheses().get(0).proposition());
        assertTrue(routed.retainsTypedHandOff());
        assertEquals(CognitiveStageKind.REASONING, routed.kind());
        assertEquals(selected.provenance().catalogVersion(), "cat-demo-7");
    }

    @Test
    void followingEvaluationStageReceivesTheTypedHandOff() {
        var cycle = new DeterministicCognitiveCycle(List.of(stage(false, List.of("java")),
                new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 1)));

        CognitiveCycleResult result = cycle.execute(monad(), List.of(), new CognitiveBudget(2, 2, 0), HOST);

        assertEquals(2, result.stageResults().size());
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().get(1));
        assertEquals(1, evaluation.evaluated().size());
        assertEquals(new Proposition(DOMAIN, 1), evaluation.evaluated().get(0).proposition());
    }

    @Test
    void aFollowingStageWithoutTypedSupportEndsTheCycleWithNoSignals() {
        var cycle = new DeterministicCognitiveCycle(
                List.of(stage(false, List.of("java")), untouchedStage(CognitiveStageKind.ACTION)));

        CognitiveCycleResult result = cycle.execute(monad(), List.of(), MINIMUM, HOST);

        assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination());
        assertEquals(1, result.stageResults().size());
        assertInstanceOf(RoutingCognitiveStageResult.class, result.stageResults().getFirst());
    }

    @Test
    void abstentionAndNoRouteProduceNoHypothesisAndKeepTheDecision() {
        var cycle = new DeterministicCognitiveCycle(List.of(stage(false, List.of("java", "long-context")),
                untouchedStage(CognitiveStageKind.EVALUATION)));

        CognitiveCycleResult result = cycle.execute(monad(), List.of(), MINIMUM, HOST);

        var routed = assertInstanceOf(RoutingCognitiveStageResult.class, result.stageResults().getFirst());
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, routed.decision());
        assertTrue(routed.hypotheses().isEmpty());
        assertFalse(routed.retainsTypedHandOff());
        assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination());
    }

    @Test
    void resultAndStageEnforceTheirContracts() {
        var routed = stage(false, List.of("java")).execute(monad(), List.of(),
                new CognitiveContext(MINIMUM, HOST));
        assertEquals(routed, routed.withAdmittedOutputSignals(List.of()));
        var signal = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> routed.withAdmittedOutputSignals(List.of(signal)));
        assertThrows(IllegalArgumentException.class, () -> new RoutingCognitiveStageResult(
                routed.decision(), HypothesisSet.EMPTY));
        assertTrue(stage(false, List.of("java")).isSource());
        assertThrows(IllegalArgumentException.class, () -> new RoutingReasoningStage(policy, c -> Optional.empty(), -1));
    }

    @Test
    void sourceRulesRejectInitialSignalsAndMissingHostContext() {
        var cycle = new DeterministicCognitiveCycle(List.of(stage(false, List.of("java"))));
        var signal = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));

        assertThrows(IllegalArgumentException.class,
                () -> cycle.execute(monad(), List.of(signal), MINIMUM, HOST));
        assertThrows(RuntimeException.class, () -> cycle.execute(monad(), List.of(), MINIMUM, Optional.empty()));
        var unresolved = new DeterministicCognitiveCycle(
                List.of(new RoutingReasoningStage(policy, c -> Optional.empty(), DOMAIN)));
        assertThrows(RuntimeException.class, () -> unresolved.execute(monad(), List.of(), MINIMUM, HOST));
    }

    @Test
    void legacyCyclesAreUnchangedAndRoutingIsNeverAnActionStatus() {
        var legacy = new DeterministicCognitiveCycle(List.of()).execute(monad(), List.of(), MINIMUM);
        assertEquals(CognitiveCycleTermination.COMPLETED, legacy.termination());
        assertTrue(legacy.stageResults().isEmpty());
        for (Class<?> type : RoutingDecision.class.getPermittedSubclasses()) {
            assertFalse(Arrays.stream(type.getRecordComponents())
                    .anyMatch(c -> c.getType().getSimpleName().equals("ActionStatus")));
        }
    }

    private static RoutingReasoningStage stubbed(RoutingRequest request, RouteKey route, String mode, boolean overflow) {
        var provenance = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                empty(request).binding(), new StateValidation.Compatible(), PARAMETERS);
        return stubbed(request, provenance, route, mode, overflow);
    }

    private static RoutingReasoningStage stubbed(
            RoutingRequest request, Provenance provenance, RouteKey route, String mode, boolean overflow) {
        return stubbed(request, provenance, route, mode, overflow, new RouteEligibilityFilter()
                .evaluate(request, demo()).excluded());
    }

    private static RoutingReasoningStage stubbed(RoutingRequest request, Provenance provenance, RouteKey route,
            String mode, boolean overflow, List<EligibilityReport.Exclusion> exclusions) {
        var ranked = new ArrayList<RankedCandidate>();
        ranked.add(new RankedCandidate(0, route, Placement.ROUTE_ORDER));
        for (var eligible : new RouteEligibilityFilter().evaluate(request, demo()).eligible()) {
            if (!eligible.key().equals(route)) {
                ranked.add(new RankedCandidate(ranked.size(), eligible.key(), Placement.ROUTE_ORDER));
            }
        }
        return stubbedRanking(request, provenance, route, mode, overflow, exclusions, ranked, false);
    }

    private static RoutingReasoningStage stubbedRanking(RoutingRequest request, Provenance provenance, RouteKey route,
            String mode, boolean overflow, List<EligibilityReport.Exclusion> exclusions,
            List<RankedCandidate> ranked, boolean truncated) {
        return stubbing(request, (r, c, p) -> new RoutingDecision.Selected(provenance, route, mode,
                Basis.COLD_START, overflow, ranked, truncated, APPLIED, SKIPPED, exclusions,
                new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1")));
    }

    private interface Decider {
        RoutingDecision decide(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference);
    }

    private static RoutingReasoningStage stubbing(RoutingRequest request, Decider decider) {
        RoutingPolicy stub = new RoutingPolicy() {
            @Override
            public String policyId() {
                return "stub";
            }

            @Override
            public String policyVersion() {
                return "1";
            }

            @Override
            public RoutingDecision decide(RoutingRequest r, RouteCatalog c, RoutingPreference p) {
                return decider.decide(r, c, p);
            }
        };
        return new RoutingReasoningStage(stub,
                context -> Optional.of(new RoutingInput(request, demo(), empty(request))), DOMAIN);
    }

    private static IllegalStateException rejected(RoutingReasoningStage stage) {
        var failure = assertThrows(IllegalStateException.class,
                () -> stage.execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST)));
        assertThrows(RuntimeException.class,
                () -> new DeterministicCognitiveCycle(List.of(stage)).execute(monad(), List.of(), MINIMUM, HOST));
        return failure;
    }

    @Test
    void aPolicyThatSelectsARouteOutsideTheCatalogIsRejectedClearly() {
        var request = request(List.of("java"), false);
        var failure = rejected(stubbed(request, new RouteKey("not-in-catalog", 1), "sandboxed", false));
        assertTrue(failure.getMessage().contains("not-in-catalog"));
    }

    @Test
    void aPolicyThatSelectsAnIneligibleCatalogRouteIsRejected() {
        var request = request(List.of("java"), false);
        // A structurally valid decision cannot list the route as excluded, so the exclusions omit it.
        var failure = rejected(stubbed(request, stubProvenance(request), RoutingFixtures.API_X, "sandboxed", true,
                List.of()));
        assertTrue(failure.getMessage().contains("not eligible"));
        // The same selection is accepted once the host permits overflow.
        var permitted = request(List.of("java"), true);
        var ranked = List.of(new RankedCandidate(0, RoutingFixtures.SUB_A, Placement.ROUTE_ORDER),
                new RankedCandidate(1, RoutingFixtures.SUB_B, Placement.ROUTE_ORDER),
                new RankedCandidate(2, RoutingFixtures.API_X, Placement.TIER));
        stubbedRanking(permitted, stubProvenance(permitted), RoutingFixtures.SUB_A, "sandboxed", false, List.of(),
                ranked, false).execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
    }

    @Test
    void aSelectionWithAnotherModeOrOverflowFlagThanTheFilterIsRejected() {
        var request = request(List.of("java"), false);
        assertTrue(rejected(stubbed(request, RoutingFixtures.SUB_A, "other-mode", false))
                .getMessage().contains("disagrees"));
        assertTrue(rejected(stubbed(request, RoutingFixtures.SUB_A, "sandboxed", true))
                .getMessage().contains("disagrees"));
    }

    @Test
    void aDecisionForAnotherCatalogOrRequestIsRejected() {
        var request = request(List.of("java"), false);
        var state = empty(request).binding();
        var foreignCatalog = new Provenance(DecisionRef.of(request), "cat-other", "stub", "1", request.cutoff(),
                state, new StateValidation.Compatible(), PARAMETERS);
        var foreignRequest = new Provenance(new DecisionRef("scope-demo", "other-task", "exec-1", "att-1", "s-impl", 101L),
                "cat-demo-7", "stub", "1", request.cutoff(), state, new StateValidation.Compatible(), PARAMETERS);

        assertTrue(rejected(stubbed(request, foreignCatalog, RoutingFixtures.SUB_A, "sandboxed", false))
                .getMessage().contains("cat-other"));
        assertTrue(rejected(stubbed(request, foreignRequest, RoutingFixtures.SUB_A, "sandboxed", false))
                .getMessage().contains("another request"));
    }

    private static Provenance stubProvenance(RoutingRequest request) {
        return new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                empty(request).binding(), new StateValidation.Compatible(), PARAMETERS);
    }

    @Test
    void aNoRouteDecisionIsRecheckedAgainstTheFilter() {
        var request = request(List.of("java"), false);
        var provenance = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                empty(request).binding(), new StateValidation.NotEvaluated(), PARAMETERS);
        var stage = stubbing(request, (r, c, p) -> new RoutingDecision.NoEligibleRoute(provenance, List.of()));

        assertTrue(rejected(stage).getMessage().contains("no eligible route"));
    }

    @Test
    void tamperedExclusionsOrAbstentionListsAreRejected() {
        var request = request(List.of("java"), false);

        var tamperedSelected = stubbed(request, stubProvenance(request), RoutingFixtures.SUB_A, "sandboxed", false,
                List.of());
        assertTrue(rejected(tamperedSelected).getMessage().contains("exclusions disagree"));

        var report = new RouteEligibilityFilter().evaluate(request, demo());
        var good = empty(request);
        var foreignScope = new RoutingPreference("other-scope", good.featureSchemaVersion(), good.evaluationPolicyId(),
                good.evaluationPolicyVersion(), good.mappingVersion(), "p", "1", 0, List.of());
        var incompatible = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                foreignScope.binding(), new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)),
                PARAMETERS);
        RoutingPolicy wrongEligible = stubbedPolicy(r -> new RoutingDecision.Abstain(incompatible,
                AbstainReason.STATE_INCOMPATIBLE, List.of(RoutingFixtures.SUB_A), List.of(), false, report.excluded()));
        var wrongEligibleStage = new RoutingReasoningStage(wrongEligible,
                context -> Optional.of(new RoutingInput(request, demo(), foreignScope)), DOMAIN);
        assertTrue(rejected(wrongEligibleStage).getMessage().contains("other eligible routes"));
    }

    @Test
    void aDecisionWithForeignCutoffStateOrPolicyIdentityIsRejected() {
        var request = request(List.of("java"), false);
        var state = empty(request).binding();
        var other = new StateBinding("scope-demo", "task-features/1", "forge-gates", "1", "routing-state/1", 9,
                "stub", "1");
        var cutoff = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff() + 1, state,
                new StateValidation.Compatible(), PARAMETERS);
        var foreignState = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(), other,
                new StateValidation.Compatible(), PARAMETERS);
        var policy = new Provenance(DecisionRef.of(request), "cat-demo-7", "other-policy", "1", request.cutoff(),
                state, new StateValidation.Compatible(), PARAMETERS);

        assertTrue(rejected(stubbed(request, cutoff, RoutingFixtures.SUB_A, "sandboxed", false))
                .getMessage().contains("cutoff"));
        assertTrue(rejected(stubbed(request, foreignState, RoutingFixtures.SUB_A, "sandboxed", false))
                .getMessage().contains("preference snapshot"));
        assertTrue(rejected(stubbed(request, policy, RoutingFixtures.SUB_A, "sandboxed", false))
                .getMessage().contains("other-policy"));
    }

    @Test
    void everyRankedCandidateAndTheTruncationFlagAreCheckedAgainstTheFilter() {
        var request = request(List.of("java"), false);
        var provenance = stubProvenance(request);
        var excluded = new RouteEligibilityFilter().evaluate(request, demo()).excluded();
        var ghost = new RouteKey("ghost", 1);
        var withGhost = List.of(new RankedCandidate(0, RoutingFixtures.SUB_A, Placement.ROUTE_ORDER),
                new RankedCandidate(1, ghost, Placement.ROUTE_ORDER));
        var complete = List.of(new RankedCandidate(0, RoutingFixtures.SUB_A, Placement.ROUTE_ORDER),
                new RankedCandidate(1, RoutingFixtures.SUB_B, Placement.ROUTE_ORDER));
        var partial = List.of(new RankedCandidate(0, RoutingFixtures.SUB_A, Placement.ONLY_ELIGIBLE));

        assertTrue(rejected(stubbedRanking(request, provenance, RoutingFixtures.SUB_A, "sandboxed", false, excluded,
                withGhost, false)).getMessage().contains("ghost"));
        assertTrue(rejected(stubbedRanking(request, provenance, RoutingFixtures.SUB_A, "sandboxed", false, excluded,
                partial, false)).getMessage().contains("1 candidates"));
        stubbedRanking(request, provenance, RoutingFixtures.SUB_A, "sandboxed", false, excluded, complete, false)
                .execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
    }

    @Test
    void aSnapshotThatConflictsWithTheRequestIsRejectedEvenWhenProvenanceCopiesIt() {
        var request = request(List.of("java"), false);
        var good = empty(request);
        var foreignScope = new RoutingPreference("other-scope", good.featureSchemaVersion(), good.evaluationPolicyId(),
                good.evaluationPolicyVersion(), good.mappingVersion(), "p", "1", 0, List.of());
        var future = new RoutingPreference(good.scopeId(), good.featureSchemaVersion(), good.evaluationPolicyId(),
                good.evaluationPolicyVersion(), good.mappingVersion(), "p", "1", request.cutoff() + 1, List.of());
        var otherEvaluation = new RoutingPreference(good.scopeId(), good.featureSchemaVersion(), "other-eval", "1",
                good.mappingVersion(), "p", "1", 0, List.of());

        for (RoutingPreference conflicting : List.of(foreignScope, future, otherEvaluation)) {
            var provenance = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                    conflicting.binding(), new StateValidation.Compatible(), PARAMETERS);
            RoutingPolicy policy = stubbedPolicy(r -> new RoutingDecision.Selected(provenance, RoutingFixtures.SUB_A,
                    "sandboxed", Basis.COLD_START, false,
                    List.of(new RankedCandidate(0, RoutingFixtures.SUB_A, Placement.ROUTE_ORDER),
                            new RankedCandidate(1, RoutingFixtures.SUB_B, Placement.ROUTE_ORDER)), false, APPLIED,
                    SKIPPED, new RouteEligibilityFilter().evaluate(request, demo()).excluded(),
                    new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1")));
            var stage = new RoutingReasoningStage(policy,
                    context -> Optional.of(new RoutingInput(request, demo(), conflicting)), DOMAIN);
            assertTrue(rejected(stage).getMessage().contains("conflicts with the request"));
        }

        var omitting = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                foreignScope.binding(),
                new StateValidation.Incompatible(List.of(StateMismatch.MAPPING_VERSION_MISMATCH)), PARAMETERS);
        var report = new RouteEligibilityFilter().evaluate(request, demo());
        RoutingPolicy policy = stubbedPolicy(r -> new RoutingDecision.Abstain(omitting, AbstainReason.STATE_INCOMPATIBLE,
                List.of(RoutingFixtures.SUB_A, RoutingFixtures.SUB_B), List.of(), false, report.excluded()));
        var stage = new RoutingReasoningStage(policy,
                context -> Optional.of(new RoutingInput(request, demo(), foreignScope)), DOMAIN);
        assertTrue(rejected(stage).getMessage().contains("omits state mismatches"));
    }

    private static RoutingPolicy stubbedPolicy(Function<RoutingRequest, RoutingDecision> decider) {
        return new RoutingPolicy() {
            @Override
            public String policyId() {
                return "stub";
            }

            @Override
            public String policyVersion() {
                return "1";
            }

            @Override
            public RoutingDecision decide(RoutingRequest r, RouteCatalog c, RoutingPreference p) {
                return decider.apply(r);
            }
        };
    }

    private static RoutingReasoningStage abstaining(
            RoutingRequest request, RoutingPreference preference, List<StateMismatch> recorded, PolicyParameters parameters) {
        var provenance = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                preference.binding(), new StateValidation.Incompatible(recorded), parameters);
        var report = new RouteEligibilityFilter().evaluate(request, demo());
        RoutingPolicy policy = stubbedPolicy(r -> new RoutingDecision.Abstain(provenance,
                AbstainReason.STATE_INCOMPATIBLE, List.of(RoutingFixtures.SUB_A, RoutingFixtures.SUB_B), List.of(),
                false, report.excluded()));
        return new RoutingReasoningStage(policy,
                context -> Optional.of(new RoutingInput(request, demo(), preference)), DOMAIN);
    }

    @Test
    void anAbstentionMustReportExactlyTheMismatchesThatExist() {
        var request = request(List.of("java"), false);
        var good = empty(request);
        var otherMapping = new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "other-mapping");

        // Spurious reasons for a request-compatible, mapping-compatible preference.
        assertTrue(rejected(abstaining(request, good, List.of(StateMismatch.SCOPE_MISMATCH), PARAMETERS))
                .getMessage().contains("does not exist"));
        assertTrue(rejected(abstaining(request, good, List.of(StateMismatch.MAPPING_VERSION_MISMATCH), PARAMETERS))
                .getMessage().contains("does not exist"));
        assertTrue(rejected(abstaining(request, good, List.of(StateMismatch.PROCESSED_CUTOFF_NEWER), PARAMETERS))
                .getMessage().contains("does not exist"));
        // A real mapping mismatch (snapshot built under another mapping than the recorded policy mapping) is required.
        assertTrue(rejected(abstaining(request, good, List.of(StateMismatch.SCOPE_MISMATCH), otherMapping))
                .getMessage().contains("omits"));
        abstaining(request, good, List.of(StateMismatch.MAPPING_VERSION_MISMATCH), otherMapping)
                .execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
    }

    private static RoutingReasoningStage onlyApi(OptionalInt cap, boolean abstain) {
        var request = request(List.of("long-context"), true);
        var report = new RouteEligibilityFilter().evaluate(request, demo());
        var parameters = new PolicyParameters(3, cap, Optional.empty(), "routing-state/1");
        var provenance = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                empty(request).binding(), new StateValidation.Compatible(), parameters);
        var ranked = List.of(new RankedCandidate(0, RoutingFixtures.API_X, Placement.ONLY_ELIGIBLE));
        RoutingPolicy policy = stubbedPolicy(r -> abstain
                ? new RoutingDecision.Abstain(provenance, AbstainReason.POLICY_TRADEOFF_UNRESOLVED,
                        List.of(RoutingFixtures.API_X), ranked, false, report.excluded())
                : new RoutingDecision.Selected(provenance, RoutingFixtures.API_X, "sandboxed", Basis.COLD_START, true,
                        ranked, false, APPLIED, SKIPPED, report.excluded(),
                        new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1")));
        return new RoutingReasoningStage(policy,
                context -> Optional.of(new RoutingInput(request, demo(), empty(request))), DOMAIN);
    }

    @Test
    void theTierCapTriggerIsRecomputedFromTheRecordedConfiguration() {
        // api-x is tier 2.
        var context = new CognitiveContext(MINIMUM, HOST);
        onlyApi(OptionalInt.of(1), true).execute(monad(), List.of(), context);
        onlyApi(OptionalInt.of(2), false).execute(monad(), List.of(), context);
        onlyApi(OptionalInt.empty(), false).execute(monad(), List.of(), context);

        assertTrue(rejected(onlyApi(OptionalInt.empty(), true)).getMessage().contains("does not exceed"));
        assertTrue(rejected(onlyApi(OptionalInt.of(2), true)).getMessage().contains("does not exceed"));
        assertTrue(rejected(onlyApi(OptionalInt.of(1), false)).getMessage().contains("above the recorded tier cap"));
    }

    private static RoutingReasoningStage resourceRanked(
            ResourceObjective.Direction direction, List<ResourceEstimate> estimates) {
        var request = request(List.of("java"), false);
        var catalog = RoutingFixtures.demoWith(estimates);
        var parameters = new PolicyParameters(3, OptionalInt.empty(),
                Optional.of(new ResourceObjective("cost", direction)), "routing-state/1");
        var provenance = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                empty(request).binding(), new StateValidation.Compatible(), parameters);
        var ranked = List.of(new RankedCandidate(0, RoutingFixtures.SUB_A, Placement.RESOURCE_OBJECTIVE),
                new RankedCandidate(1, RoutingFixtures.SUB_B, Placement.RESOURCE_OBJECTIVE));
        RoutingPolicy policy = stubbedPolicy(r -> new RoutingDecision.Selected(provenance, RoutingFixtures.SUB_A,
                "sandboxed", Basis.RESOURCE_OBJECTIVE, false, ranked, false,
                List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.RESOURCE_OBJECTIVE,
                        RoutingRule.ROUTE_ORDER), List.of(RoutingRule.LEARNED_PREFERENCE),
                new RouteEligibilityFilter().evaluate(request, catalog).excluded(),
                new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1")));
        return new RoutingReasoningStage(policy,
                context -> Optional.of(new RoutingInput(request, catalog, empty(request))), DOMAIN);
    }

    @Test
    void aResourcePlacementMustBeSupportedByTheCatalogEstimates() {
        var a = RoutingFixtures.SUB_A;
        var b = RoutingFixtures.SUB_B;
        var context = new CognitiveContext(MINIMUM, HOST);
        var minimize = ResourceObjective.Direction.MINIMIZE;

        resourceRanked(minimize, List.of(RoutingFixtures.cost(a, 20, "usd-cents"), RoutingFixtures.cost(b, 50, "usd-cents")))
                .execute(monad(), List.of(), context);
        resourceRanked(ResourceObjective.Direction.MAXIMIZE,
                List.of(RoutingFixtures.cost(a, 50, "usd-cents"), RoutingFixtures.cost(b, 20, "usd-cents")))
                .execute(monad(), List.of(), context);

        assertTrue(rejected(resourceRanked(minimize, List.of())).getMessage().contains("recomputed ranking"));
        assertTrue(rejected(resourceRanked(minimize, List.of(RoutingFixtures.cost(a, 20, "usd-cents"),
                RoutingFixtures.cost(b, new ResourceValue.Unknown())))).getMessage().contains("recomputed ranking"));
        assertTrue(rejected(resourceRanked(minimize, List.of(RoutingFixtures.cost(a, 20, "usd-cents"),
                RoutingFixtures.cost(b, 50, "eur-cents")))).getMessage().contains("recomputed ranking"));
        assertTrue(rejected(resourceRanked(minimize, List.of(RoutingFixtures.cost(a, 50, "usd-cents"),
                RoutingFixtures.cost(b, 20, "usd-cents")))).getMessage().contains("recomputed ranking"));
        assertTrue(rejected(resourceRanked(minimize, List.of(RoutingFixtures.cost(a, 20, "usd-cents"),
                RoutingFixtures.cost(b, 20, "usd-cents")))).getMessage().contains("recomputed ranking"));
    }

    private static final PolicyParameters COST_MINIMIZE = new PolicyParameters(3, OptionalInt.empty(),
            Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE)), "routing-state/1");

    private static final List<RoutingRule> WITH_LEARNED = List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY,
            RoutingRule.LEARNED_PREFERENCE, RoutingRule.ROUTE_ORDER);

    /** A stage whose stub policy returns the given ranking over a custom catalog and preference. */
    private static RoutingReasoningStage ordered(RouteCatalog catalog, RoutingPreference preference,
            List<RankedCandidate> ranked, Basis basis, List<RoutingRule> applied, List<RoutingRule> skipped) {
        var request = request(List.of("java"), true);
        var provenance = new Provenance(DecisionRef.of(request), catalog.catalogVersion(), "stub", "1",
                request.cutoff(), preference.binding(), new StateValidation.Compatible(), COST_MINIMIZE);
        var report = new RouteEligibilityFilter().evaluate(request, catalog);
        // The explanation must list every eligible route, so the remaining ones follow the given pair.
        var full = new ArrayList<>(ranked);
        for (var eligible : report.eligible()) {
            if (full.stream().noneMatch(candidate -> candidate.key().equals(eligible.key()))) {
                full.add(new RankedCandidate(full.size(), eligible.key(), Placement.TIER));
            }
        }
        RoutingPolicy policy = stubbedPolicy(r -> new RoutingDecision.Selected(provenance, ranked.getFirst().key(),
                "sandboxed", basis, ranked.getFirst().key().equals(RoutingFixtures.API_X), full, false, applied,
                skipped, report.excluded(),
                new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1")));
        return new RoutingReasoningStage(policy,
                context -> Optional.of(new RoutingInput(request, catalog, preference)), DOMAIN);
    }

    private static RouteCatalog catalogWith(int subAPriority, int subBPriority, List<ResourceEstimate> estimates) {
        return new RouteCatalog("cat-ordered", List.of(
                RoutingFixtures.entry(RoutingFixtures.base("sub-a", 1).build(), subAPriority),
                RoutingFixtures.entry(RoutingFixtures.base("sub-b", 1).build(), subBPriority),
                RoutingFixtures.entry(RoutingFixtures.apiX(), 1)), estimates);
    }

    private static List<RankedCandidate> pair(RouteKey first, RouteKey second, Placement placement) {
        return List.of(new RankedCandidate(0, first, placement), new RankedCandidate(1, second, placement));
    }

    @Test
    void rankingsMustRespectTierPriorityAndLearnedOrderBeforeTheResourceRule() {
        var a = RoutingFixtures.SUB_A;
        var b = RoutingFixtures.SUB_B;
        var api = RoutingFixtures.API_X;
        var cheapApi = List.of(RoutingFixtures.cost(api, 1, "usd-cents"), RoutingFixtures.cost(a, 9, "usd-cents"),
                RoutingFixtures.cost(b, 9, "usd-cents"));
        var request = request(List.of("java"), true);
        var cold = empty(request);
        var resourceRules = List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.RESOURCE_OBJECTIVE,
                RoutingRule.ROUTE_ORDER);
        var learnedSkipped = List.of(RoutingRule.LEARNED_PREFERENCE);
        var context = new CognitiveContext(MINIMUM, HOST);

        // A cheaper tier-2 route can never lead a tier-1 route through the resource rule.
        assertTrue(rejected(ordered(catalogWith(1, 1, cheapApi), cold, pair(api, a, Placement.RESOURCE_OBJECTIVE),
                Basis.RESOURCE_OBJECTIVE, resourceRules, learnedSkipped)).getMessage().contains("recomputed ranking"));
        // Resource placement across different fallback priorities.
        assertTrue(rejected(ordered(catalogWith(1, 2, List.of(RoutingFixtures.cost(a, 1, "usd-cents"),
                RoutingFixtures.cost(b, 9, "usd-cents"))), cold, pair(a, b, Placement.RESOURCE_OBJECTIVE),
                Basis.RESOURCE_OBJECTIVE, resourceRules, learnedSkipped)).getMessage().contains("recomputed ranking"));
        // A priority placement is allowed exactly when priorities differ and ascend.
        ordered(catalogWith(1, 2, List.of()), cold, pair(a, b, Placement.FALLBACK_PRIORITY), Basis.HOST_PRIORITY,
                APPLIED, SKIPPED).execute(monad(), List.of(), context);
        assertTrue(rejected(ordered(catalogWith(2, 1, List.of()), cold, pair(a, b, Placement.FALLBACK_PRIORITY),
                Basis.HOST_PRIORITY, APPLIED, SKIPPED)).getMessage().contains("recomputed ranking"));
        // TIER placement needs differing tiers.
        assertTrue(rejected(ordered(catalogWith(1, 1, List.of()), cold, pair(a, b, Placement.TIER),
                Basis.HOST_PRIORITY, APPLIED, SKIPPED)).getMessage().contains("recomputed ranking"));
        // Route order must ascend.
        assertTrue(rejected(ordered(catalogWith(1, 1, List.of()), cold, pair(b, a, Placement.ROUTE_ORDER),
                Basis.COLD_START, APPLIED, SKIPPED)).getMessage().contains("recomputed ranking"));

        // Learned preference: descending values, placement exactly when the values differ.
        RoutingPreference prefersA = new RoutingPreference(cold.scopeId(), cold.featureSchemaVersion(),
                cold.evaluationPolicyId(), cold.evaluationPolicyVersion(), cold.mappingVersion(), "p", "1", 0,
                List.of(RoutingFixtures.cohort(a, 0.5, 3), RoutingFixtures.cohort(b, 0.1, 3),
                        RoutingFixtures.cohort(api, 0.0, 3)));
        var resourceAfterLearned = List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY,
                RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE, RoutingRule.ROUTE_ORDER);
        var estimates = List.of(RoutingFixtures.cost(a, 1, "usd-cents"), RoutingFixtures.cost(b, 9, "usd-cents"));
        ordered(catalogWith(1, 1, estimates), prefersA, pair(a, b, Placement.LEARNED_PREFERENCE),
                Basis.LEARNED_PREFERENCE, WITH_LEARNED, List.of(RoutingRule.RESOURCE_OBJECTIVE))
                .execute(monad(), List.of(), context);
        assertTrue(rejected(ordered(catalogWith(1, 1, estimates), prefersA,
                pair(a, b, Placement.RESOURCE_OBJECTIVE), Basis.RESOURCE_OBJECTIVE, resourceAfterLearned, List.of()))
                .getMessage().contains("recomputed ranking"));
        assertTrue(rejected(ordered(catalogWith(1, 1, estimates), prefersA, pair(b, a, Placement.LEARNED_PREFERENCE),
                Basis.LEARNED_PREFERENCE, WITH_LEARNED, List.of(RoutingRule.RESOURCE_OBJECTIVE)))
                .getMessage().contains("recomputed ranking"));
        assertTrue(rejected(ordered(catalogWith(1, 1, estimates), prefersA, pair(a, b, Placement.ROUTE_ORDER),
                Basis.COLD_START, WITH_LEARNED, List.of(RoutingRule.RESOURCE_OBJECTIVE)))
                .getMessage().contains("recomputed ranking"));
    }

    @Test
    void learnedOrderingIsAcceptedOnlyWithEnoughSupportForEveryRoute() {
        var a = RoutingFixtures.SUB_A;
        var b = RoutingFixtures.SUB_B;
        var api = RoutingFixtures.API_X;
        var cold = empty(request(List.of("java"), true));
        RoutingPreference thin = new RoutingPreference(cold.scopeId(), cold.featureSchemaVersion(),
                cold.evaluationPolicyId(), cold.evaluationPolicyVersion(), cold.mappingVersion(), "p", "1", 0,
                List.of(RoutingFixtures.cohort(a, 0.5, 3), RoutingFixtures.cohort(b, 0.1, 2),
                        RoutingFixtures.cohort(api, 0.0, 3)));
        RoutingPreference enough = new RoutingPreference(cold.scopeId(), cold.featureSchemaVersion(),
                cold.evaluationPolicyId(), cold.evaluationPolicyVersion(), cold.mappingVersion(), "p", "1", 0,
                List.of(RoutingFixtures.cohort(a, 0.5, 3), RoutingFixtures.cohort(b, 0.1, 3),
                        RoutingFixtures.cohort(api, 0.0, 3)));
        var optionalSkipped = List.of(RoutingRule.RESOURCE_OBJECTIVE);

        // The learned rule claimed although sub-b has only 2 supporting observations.
        assertTrue(rejected(ordered(catalogWith(1, 1, List.of()), thin, pair(a, b, Placement.LEARNED_PREFERENCE),
                Basis.LEARNED_PREFERENCE, WITH_LEARNED, optionalSkipped)).getMessage().contains("recomputed ranking"));
        // The learned rule skipped although every route has enough support.
        assertTrue(rejected(ordered(catalogWith(1, 1, List.of()), enough, pair(a, b, Placement.ROUTE_ORDER),
                Basis.COLD_START, APPLIED, SKIPPED)).getMessage().contains("recomputed ranking"));
        ordered(catalogWith(1, 1, List.of()), enough, pair(a, b, Placement.LEARNED_PREFERENCE),
                Basis.LEARNED_PREFERENCE, WITH_LEARNED, optionalSkipped)
                .execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
    }

    @Test
    void routeOrderIsRejectedWhereComparableEstimatesSeparateTheRoutes() {
        var estimates = List.of(RoutingFixtures.cost(RoutingFixtures.SUB_A, 1, "usd-cents"),
                RoutingFixtures.cost(RoutingFixtures.SUB_B, 9, "usd-cents"));
        var cold = empty(request(List.of("java"), true));

        assertTrue(rejected(ordered(catalogWith(1, 1, estimates), cold,
                pair(RoutingFixtures.SUB_A, RoutingFixtures.SUB_B, Placement.ROUTE_ORDER), Basis.COLD_START, APPLIED,
                SKIPPED)).getMessage().contains("recomputed ranking"));
    }

    private static RouteCatalog twelveRoutes() {
        var entries = new ArrayList<monada.neuron.routing.catalog.CatalogEntry>();
        for (int i = 0; i < 12; i++) {
            // route-11 has the best fallback priority but the last canonical position.
            entries.add(RoutingFixtures.entry(RoutingFixtures.base("route-%02d".formatted(i), 1).build(),
                    i == 11 ? 0 : 1));
        }
        return RouteCatalog.of("cat-twelve", entries);
    }

    @Test
    void aTruncatedRankingMustNotOmitAHigherPriorityRoute() {
        var request = request(List.of("java"), false);
        var catalog = twelveRoutes();
        var preference = empty(request);
        var report = new RouteEligibilityFilter().evaluate(request, catalog);
        var firstEight = new ArrayList<RankedCandidate>();
        for (int i = 0; i < 8; i++) {
            firstEight.add(new RankedCandidate(i, new RouteKey("route-%02d".formatted(i), 1), Placement.ROUTE_ORDER));
        }
        var provenance = new Provenance(DecisionRef.of(request), "cat-twelve", "stub", "1", request.cutoff(),
                preference.binding(), new StateValidation.Compatible(), PARAMETERS);
        RoutingPolicy omitting = stubbedPolicy(r -> new RoutingDecision.Selected(provenance, firstEight.getFirst().key(),
                "sandboxed", Basis.COLD_START, false, firstEight, true, APPLIED, SKIPPED, report.excluded(),
                new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1")));
        var wrongStage = new RoutingReasoningStage(omitting,
                context -> Optional.of(new RoutingInput(request, catalog, preference)), DOMAIN);
        assertTrue(rejected(wrongStage).getMessage().contains("recomputed ranking"));

        // The real policy for the same inputs lists route-11 first and is accepted.
        var real = new RoutingReasoningStage(LexicographicRoutingPolicy.reference(),
                context -> Optional.of(new RoutingInput(request, catalog, preference)), DOMAIN);
        var result = real.execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
        var selected = (RoutingDecision.Selected) result.decision();
        assertEquals(new RouteKey("route-11", 1), selected.route());
        assertTrue(selected.candidatesTruncated());
    }

    @Test
    void aTradeoffAbstentionMustRankLikeTheContractOrdering() {
        var request = request(List.of("java"), true);
        var overflow = monada.neuron.routing.catalog.OverflowClass.OVERFLOW;
        var catalog = RouteCatalog.of("cat-api", List.of(
                RoutingFixtures.entry(RoutingFixtures.apiX(), 1),
                RoutingFixtures.entry(RoutingFixtures.base("api-y", 1)
                        .billingMode(monada.neuron.routing.catalog.BillingMode.API_METERED).overflowClass(overflow)
                        .tier(2).build(), 1)));
        var parameters = new PolicyParameters(3, OptionalInt.of(1), Optional.empty(), "routing-state/1");
        var provenance = new Provenance(DecisionRef.of(request), "cat-api", "stub", "1", request.cutoff(),
                empty(request).binding(), new StateValidation.Compatible(), parameters);
        var report = new RouteEligibilityFilter().evaluate(request, catalog);
        var apiY = new RouteKey("api-y", 1);
        var eligible = List.of(RoutingFixtures.API_X, apiY);

        for (boolean correct : new boolean[] {true, false}) {
            var ranked = correct
                    ? pair(RoutingFixtures.API_X, apiY, Placement.ROUTE_ORDER)
                    : pair(apiY, RoutingFixtures.API_X, Placement.ROUTE_ORDER);
            RoutingPolicy policy = stubbedPolicy(r -> new RoutingDecision.Abstain(provenance,
                    AbstainReason.POLICY_TRADEOFF_UNRESOLVED, eligible, ranked, false, report.excluded()));
            var stage = new RoutingReasoningStage(policy,
                    context -> Optional.of(new RoutingInput(request, catalog, empty(request))), DOMAIN);
            if (correct) {
                stage.execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
            } else {
                assertTrue(rejected(stage).getMessage().contains("recomputed ranking"));
            }
        }
    }
}
