package monada.neuron.evaluation;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.evolution.AdaptationCognitiveStage;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.CognitiveCycleException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
                        () -> new HypothesisEvaluationCognitiveStage(policy, -1)));
    }

    @Test
    void acceptsZeroAsAnEvaluateWithoutSelectionPass() {
        var result = run(reasoningStub(hypotheses(), List.of()), new HypothesisEvaluationCognitiveStage(policy, 0));
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast());
        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertTrue(evaluation.evaluation().selected().isEmpty()),
                () -> assertEquals(3, evaluation.evaluation().evaluatedCount()),
                () -> assertEquals(0, evaluation.evaluation().requestedMaxSelected()));
    }

    @Test
    void evaluatesReasoningHypothesesAfterAHypothesisOnlyHandOff() {
        var produced = hypotheses();
        var result = run(reasoningStub(produced, List.of()), new HypothesisEvaluationCognitiveStage(policy, 2));
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast());
        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertSame(produced, evaluation.evaluated()),
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
    void evaluationLeavesAdaptationOutcomeIdenticalToACycleWithoutIt() {
        var without = runWithAdaptation(false);
        var with = runWithAdaptation(true);
        var adaptedWith = nodeAdaptedEvents(with.result());
        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, with.result().termination()),
                () -> assertTrue(with.target().getEnergy() > 0.0, "adaptation must actually run"),
                () -> assertFalse(adaptedWith.isEmpty()),
                () -> assertEquals(nodeAdaptedEvents(without.result()), adaptedWith),
                () -> assertEquals(without.target().getFrequencyState(), with.target().getFrequencyState()),
                () -> assertEquals(without.target().getEnergy(), with.target().getEnergy()),
                () -> assertEquals(without.target().getHistory(), with.target().getHistory()),
                () -> assertEquals(without.target().getTopologyVersion(), with.target().getTopologyVersion()),
                () -> assertEquals(FrequencyState.ZERO, with.bystander().getFrequencyState()),
                () -> assertEquals(0.0, with.bystander().getEnergy()),
                () -> assertTrue(with.bystander().getHistory().isEmpty()),
                () -> assertEquals(0L, with.bystander().getTopologyVersion()));
    }

    @Test
    void failsCycleWhenACustomEvaluationStageReturnsUnacceptedSignalEvidence() {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(1, 1));
        var seq = builder.propose(new Proposition(0, 1)).getAsInt();
        builder.addEvidence(seq, new SignalEvidence(999, EvidenceRelation.SUPPORTS, 0.5));
        var forged = builder.build();
        CognitiveStage custom = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.EVALUATION;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                return new EvaluationCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED,
                        inputSignals,
                        policy.evaluate(forged, 1));
            }
        };
        var failure = assertThrows(
                CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(reasoningStub(hypotheses(), List.of(signal(1.0))), custom))
                        .execute(new PrimaryMonad(uuid(1)), List.of(signal(1.0)), new CognitiveBudget(10, 10, 30)));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void rejectsAPolicyThatReturnsNull() throws Exception {
        var stage = new HypothesisEvaluationCognitiveStage((set, maxSelected) -> null, 1);
        try (var context = new CognitiveContext(new CognitiveBudget(10, 10, 10))) {
            assertThrows(NullPointerException.class, () -> stage.execute(
                    new PrimaryMonad(uuid(1)), List.of(signal(1.0)), Optional.empty(), context));
        }
    }

    @Test
    void ignoresPreviousResultsThatAreNotReasoningResults() throws Exception {
        var stage = new HypothesisEvaluationCognitiveStage(policy, 2);
        var previous = new StubResult(CognitiveStageKind.MEMORY_RECALL, List.of(signal(1.0)));
        try (var context = new CognitiveContext(new CognitiveBudget(10, 10, 10))) {
            var result = (EvaluationCognitiveStageResult) stage.execute(
                    new PrimaryMonad(uuid(1)), List.of(signal(1.0)), Optional.of(previous), context);
            assertAll(
                    () -> assertEquals(0, result.evaluation().evaluatedCount()),
                    () -> assertTrue(result.evaluation().selected().isEmpty()),
                    () -> assertEquals(List.of(signal(1.0)), result.outputSignals()));
        }
    }

    @Test
    void laterStagesRunOnThePassedThroughSignals() {
        var inputs = List.of(signal(1.0), signal(2.0));
        var seen = new ArrayList<List<Signal>>();
        var result = new DeterministicCognitiveCycle(List.of(
                reasoningStub(hypotheses(), inputs),
                new HypothesisEvaluationCognitiveStage(policy, 2),
                recordingStage(CognitiveStageKind.ACTION, seen)))
                .execute(new PrimaryMonad(uuid(1)), List.of(signal(1.0)), new CognitiveBudget(10, 50, 100));
        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(inputs), seen));
    }

    @Test
    void hypothesisOnlyHandOffEndsBeforeStagesThatRequireSignals() {
        var seen = new ArrayList<List<Signal>>();
        var result = new DeterministicCognitiveCycle(List.of(
                reasoningStub(hypotheses(), List.of()),
                new HypothesisEvaluationCognitiveStage(policy, 2),
                recordingStage(CognitiveStageKind.ACTION, seen)))
                .execute(new PrimaryMonad(uuid(1)), List.of(signal(1.0)), new CognitiveBudget(10, 10, 30));
        assertAll(
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                () -> assertTrue(seen.isEmpty()),
                () -> assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast()));
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

    private static Run runWithAdaptation(boolean withEvaluation) {
        var target = new Node.Builder().id(uuid(3)).type(NodeType.PROCESSOR).build();
        var bystander = new Node.Builder().id(uuid(4)).type(NodeType.PROCESSOR).build();
        var aeon = new Aeon(uuid(10), AeonPurpose.REASONING);
        aeon.addMember(bystander);
        var monad = new PrimaryMonad(uuid(1));
        monad.registerAeon(aeon);
        var stages = new ArrayList<CognitiveStage>();
        stages.add(reasoningStub(hypotheses(), List.of(signal(5.0))));
        if (withEvaluation) {
            stages.add(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 2));
        }
        stages.add(new AdaptationCognitiveStage(new DeterministicBaselineAdaptationPolicy(), List.of(target)));
        var result = new DeterministicCognitiveCycle(stages)
                .execute(monad, List.of(signal(1.0)), new CognitiveBudget(10, 10, 30));
        return new Run(result, target, bystander);
    }

    private static List<CognitiveTraceEvent.NodeAdapted> nodeAdaptedEvents(CognitiveCycleResult result) {
        return result.snapshot().traceEntries().stream()
                .map(entry -> entry.event())
                .filter(CognitiveTraceEvent.NodeAdapted.class::isInstance)
                .map(CognitiveTraceEvent.NodeAdapted.class::cast)
                .toList();
    }

    private static CognitiveStage recordingStage(CognitiveStageKind kind, List<List<Signal>> seen) {
        return new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return kind;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                seen.add(inputSignals);
                return new StubResult(kind, inputSignals);
            }
        };
    }

    private record Run(CognitiveCycleResult result, Node target, Node bystander) {
    }

    private record StubResult(CognitiveStageKind kind, List<Signal> outputSignals)
            implements CognitiveStageResult {

        private StubResult {
            outputSignals = List.copyOf(outputSignals);
        }

        @Override
        public CognitiveStageStatus status() {
            return CognitiveStageStatus.COMPLETED;
        }
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
