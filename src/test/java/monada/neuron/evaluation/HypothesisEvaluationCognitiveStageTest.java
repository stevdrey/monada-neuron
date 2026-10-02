package monada.neuron.evaluation;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.reasoning.SignalEvidence;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypothesisEvaluationCognitiveStageTest {

    private final HypothesisEvaluationPolicy policy = new ReferenceHypothesisEvaluationPolicy();

    @Test
    void occupiesTheEvaluationPositionAndAcceptsTypedOnlyHandOff() {
        var stage = new HypothesisEvaluationCognitiveStage(policy, 2);
        assertAll(
                () -> assertEquals(CognitiveStageKind.EVALUATION, stage.kind()),
                () -> assertTrue(stage.acceptsTypedOnlyHandOff()));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new HypothesisEvaluationCognitiveStage(null, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisEvaluationCognitiveStage(policy, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisEvaluationCognitiveStage(policy, -1)));
    }

    @Test
    void evaluatesReasoningHypothesesAfterAHypothesisOnlyHandOff() {
        var produced = hypotheses();
        var result = run(reasoningStub(produced, List.of()), new HypothesisEvaluationCognitiveStage(policy, 2));
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast());
        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(produced, evaluation.evaluated()),
                () -> assertEquals(3, evaluation.evaluation().evaluatedCount()),
                () -> assertEquals(List.of(1, 0),
                        evaluation.evaluation().selected().stream().map(EvaluatedHypothesis::sequence).toList()));
    }

    @Test
    void passesInputSignalsThroughUnchanged() {
        var inputs = List.of(signal(1.0), signal(2.0));
        var result = run(reasoningStub(hypotheses(), inputs), new HypothesisEvaluationCognitiveStage(policy, 2));
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast());
        assertAll(
                () -> assertEquals(inputs, evaluation.outputSignals()),
                () -> assertEquals(inputs, result.outputSignals()));
    }

    @Test
    void producesEmptyEvaluationWhenPreviousResultHasNoHypotheses() throws Exception {
        var stage = new HypothesisEvaluationCognitiveStage(policy, 3);
        try (var context = new CognitiveContext(new CognitiveBudget(10, 10, 10))) {
            var result = (EvaluationCognitiveStageResult) stage.execute(
                    new PrimaryMonad(uuid(1)), List.of(signal(1.0)), Optional.empty(), context);
            var viaLegacyOverload = (EvaluationCognitiveStageResult) stage.execute(
                    new PrimaryMonad(uuid(1)), List.of(signal(1.0)), context);
            assertAll(
                    () -> assertTrue(result.evaluation().selected().isEmpty()),
                    () -> assertEquals(0, result.evaluation().evaluatedCount()),
                    () -> assertEquals(CognitiveStageStatus.COMPLETED, result.status()),
                    () -> assertEquals(result, viaLegacyOverload));
        }
    }

    @Test
    void identicalInputsReplayToEqualCycleResults() {
        var first = run(reasoningStub(hypotheses(), List.of()), new HypothesisEvaluationCognitiveStage(policy, 2));
        var second = run(reasoningStub(hypotheses(), List.of()), new HypothesisEvaluationCognitiveStage(policy, 2));
        assertEquals(first, second);
    }

    @Test
    void evaluationDoesNotMutateNodesOrAdaptationState() {
        var node = new Node.Builder().id(uuid(5)).type(NodeType.PROCESSOR).build();
        var aeon = new Aeon(uuid(10), AeonPurpose.REASONING);
        aeon.addMember(node);
        var monad = new PrimaryMonad(uuid(1));
        monad.registerAeon(aeon);
        var state = node.getFrequencyState();
        var energy = node.getEnergy();
        var history = List.copyOf(node.getHistory());
        var topology = node.getTopologyVersion();
        var produced = hypotheses();

        var result = new DeterministicCognitiveCycle(List.of(
                reasoningStub(produced, List.of()),
                new HypothesisEvaluationCognitiveStage(policy, 2)))
                .execute(monad, List.of(signal(1.0)), new CognitiveBudget(10, 10, 30));

        assertAll(
                () -> assertEquals(state, node.getFrequencyState()),
                () -> assertEquals(energy, node.getEnergy()),
                () -> assertEquals(history, node.getHistory()),
                () -> assertEquals(topology, node.getTopologyVersion()),
                () -> assertEquals(hypotheses(), produced),
                () -> assertTrue(result.snapshot().traceEntries().stream()
                        .noneMatch(entry -> entry.event() instanceof CognitiveTraceEvent.NodeAdapted)));
    }

    // helpers ---------------------------------------------------------------------------------

    private static CognitiveCycleResult run(CognitiveStage reasoning, CognitiveStage evaluation) {
        return new DeterministicCognitiveCycle(List.of(reasoning, evaluation))
                .execute(new PrimaryMonad(uuid(1)), List.of(signal(1.0)), new CognitiveBudget(10, 10, 30));
    }

    /** Candidate 0 and 1 are supported (1 more strongly); candidate 2 is only contradicted. */
    private static HypothesisSet hypotheses() {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(3, 4));
        var zero = builder.propose(new Proposition(0, 0)).getAsInt();
        var one = builder.propose(new Proposition(0, 1)).getAsInt();
        var two = builder.propose(new Proposition(0, 2)).getAsInt();
        builder.addEvidence(zero, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 0.4));
        builder.addEvidence(one, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 0.8));
        builder.addEvidence(two, new SignalEvidence(0, EvidenceRelation.CONTRADICTS, 0.9));
        return builder.build();
    }

    private static CognitiveStage reasoningStub(HypothesisSet hypotheses, List<Signal> outputs) {
        return new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.REASONING;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                return new ReasoningCognitiveStageResult(CognitiveStageStatus.COMPLETED, outputs, hypotheses);
            }
        };
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
