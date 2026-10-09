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
import monada.neuron.reasoning.Proposition;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                routed.decision(), monada.neuron.reasoning.HypothesisSet.EMPTY));
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
            assertFalse(java.util.Arrays.stream(type.getRecordComponents())
                    .anyMatch(c -> c.getType().getSimpleName().equals("ActionStatus")));
        }
    }
}
