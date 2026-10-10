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
        stubbed(permitted, RoutingFixtures.API_X, "sandboxed", true)
                .execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
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

    @Test
    void aCompatibleTradeoffMustMatchThePolicyMappingVersion() {
        var request = request(List.of("long-context"), true);
        var report = new RouteEligibilityFilter().evaluate(request, demo());
        var provenance = new Provenance(DecisionRef.of(request), "cat-demo-7", "stub", "1", request.cutoff(),
                empty(request).binding(), new StateValidation.Compatible(),
                new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "other-mapping"));
        RoutingPolicy policy = stubbedPolicy(r -> new RoutingDecision.Abstain(provenance,
                AbstainReason.POLICY_TRADEOFF_UNRESOLVED, List.of(RoutingFixtures.API_X),
                List.of(new RankedCandidate(0, RoutingFixtures.API_X, Placement.ONLY_ELIGIBLE)), false,
                report.excluded()));
        var stage = new RoutingReasoningStage(policy,
                context -> Optional.of(new RoutingInput(request, demo(), empty(request))), DOMAIN);

        assertTrue(rejected(stage).getMessage().contains("MAPPING_VERSION_MISMATCH"));
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
}
