package monada.neuron.reasoning.concept;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.evaluation.EvaluationCognitiveStageResult;
import monada.neuron.evaluation.HypothesisEvaluationCognitiveStage;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.host.CycleInput;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.perception.DeterministicPerceptionCapability;
import monada.neuron.perception.DeterministicPerceptionCapability.Script;
import monada.neuron.perception.PerceptionStatus;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.SpectralEvidence;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import monada.neuron.spectral.DecompositionRejectionReason;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SynthesisStatus;
import monada.neuron.spectral.WaveProvenance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.UUID;

import static monada.neuron.reasoning.concept.ConceptFixtures.HIERARCHY;
import static monada.neuron.reasoning.concept.ConceptFixtures.HIGH_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.LEAVES;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_CHORD;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.N;
import static monada.neuron.reasoning.concept.ConceptFixtures.leafSamples;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveCognitionReasoningStageTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);
    private static final ConceptModel MODEL = ConceptFixtures.trainedModel();
    private static final Signal CUE = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));

    private static HostExecutionContext host(String ref) {
        return HostExecutionContext.of(new HostReference(ref));
    }

    /** A clean window of a leaf whose provenance names the window by {@code ref} and {@code offset}. */
    private static WaveWindow window(long leaf, long seed, String ref, long offset) {
        var samples = leafSamples(leaf, new SplittableRandom(seed));
        var provenance = new WaveProvenance("encoder.concept-fixture", 1, ref, offset);
        return new WaveWindow(new SampledWave(samples, N, SampleUnit.HERTZ, provenance), OptionalLong.of(leaf));
    }

    /** Resolves the windows registered for an execution reference, recording every call. */
    private static WaveInputResolver resolver(Map<String, WaveCognitionInput> inputs, List<String> calls) {
        return context -> {
            calls.add(context.executionRef().value());
            return Optional.ofNullable(inputs.get(context.executionRef().value()));
        };
    }

    private static NeuronRuntime runtime(WaveInputResolver resolver, WaveCognitionLimits limits) {
        return NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 128L)))
                .defaultBudget(BUDGET)
                .stage(new WaveCognitionReasoningStage(resolver, MODEL, limits))
                .stage(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 4))
                .build();
    }

    private static CognitiveCycleResult run(NeuronRuntime runtime, String ref) {
        return runtime.execute(CycleInput.of(List.of(CUE)).withHostContext(host(ref)));
    }

    private static WaveCognitionStageResult reasoning(CognitiveCycleResult result) {
        return assertInstanceOf(WaveCognitionStageResult.class, result.stageResults().stream()
                .filter(stage -> stage.kind() == CognitiveStageKind.REASONING).findFirst().orElseThrow());
    }

    private static List<CognitiveStageKind> kinds(CognitiveCycleResult result) {
        return result.stageResults().stream().map(CognitiveStageResult::kind).toList();
    }

    @Test
    void predictedConceptsReachEvaluationWithWindowEvidence() {
        var input = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-a", 0), window(HIGH_PURE, 2, "stream-a", 64));
        var result = run(runtime(resolver(Map.of("run", input), new ArrayList<>()), WaveCognitionLimits.DEFAULT),
                "run");
        var reasoning = reasoning(result);
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast());
        var hypotheses = reasoning.hypotheses().hypotheses();
        var low = assertInstanceOf(WindowOutcome.Predicted.class, reasoning.report().windows().get(0));
        var high = assertInstanceOf(WindowOutcome.Predicted.class, reasoning.report().windows().get(1));

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(CognitiveStageKind.REASONING, CognitiveStageKind.EVALUATION),
                        kinds(result)),
                () -> assertEquals(List.of(CUE), reasoning.outputSignals()),
                () -> assertEquals(List.of(CUE), result.outputSignals()),
                () -> assertEquals(WaveCognitionOutcome.PROCESSED, reasoning.report().outcome()),
                () -> assertEquals(12, reasoning.report().totalComparisons()),
                () -> assertEquals(HIERARCHY.leaf(0), low.conceptOrdinal()),
                () -> assertEquals(HIERARCHY.leaf(2), high.conceptOrdinal()),
                () -> assertEquals(List.of(HIERARCHY.proposition(low.conceptOrdinal()),
                        HIERARCHY.proposition(high.conceptOrdinal())),
                        hypotheses.stream().map(h -> h.proposition()).toList()),
                () -> assertEquals(List.of(new SpectralEvidence("stream-a", 0, EvidenceRelation.SUPPORTS,
                        1.0)), hypotheses.get(0).evidence()),
                () -> assertEquals(List.of(new SpectralEvidence("stream-a", 64, EvidenceRelation.SUPPORTS,
                        1.0)), hypotheses.get(1).evidence()),
                () -> assertEquals(reasoning.hypotheses(), evaluation.evaluated()),
                () -> assertEquals(2, evaluation.evaluation().selected().size()),
                () -> assertTrue(evaluation.evaluation().selected().getFirst().breakdown().score() > 0.0));
    }

    @Test
    void perceptionAndMemoryRecallRunBeforeTheOptInReasoningStage() {
        var perception = new DeterministicPerceptionCapability(Map.of(
                "run", new Script(PerceptionStatus.SUCCEEDED, List.of(CUE))));
        var input = WaveCognitionInput.of(window(LOW_CHORD, 3, "stream-b", 0));
        var runtime = NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 128L)))
                .defaultBudget(BUDGET)
                .perceptionCapability(perception, 2)
                .memoryPort(request -> new ResonanceMemoryResponse(
                        ResonanceMemoryStatus.COMPLETE, request.maxResults(), List.of()), 2)
                .stage(new WaveCognitionReasoningStage(
                        resolver(Map.of("run", input), new ArrayList<>()), MODEL, WaveCognitionLimits.DEFAULT))
                .stage(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 1))
                .build();

        var result = runtime.execute(CycleInput.of(List.of()).withHostContext(host("run")));
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast());

        assertAll(
                () -> assertEquals(List.of(CognitiveStageKind.PERCEPTION, CognitiveStageKind.MEMORY_RECALL,
                        CognitiveStageKind.REASONING, CognitiveStageKind.EVALUATION), kinds(result)),
                () -> assertEquals(1, reasoning(result).hypotheses().size()),
                () -> assertEquals(1, evaluation.evaluation().selected().size()),
                () -> assertEquals(List.of(CUE), result.outputSignals()));
    }

    @Test
    void missingContextNoOptInAndNoSamplesPassSignalsThroughWithoutHypotheses() {
        var calls = new ArrayList<String>();
        var runtime = runtime(resolver(Map.of("empty", new WaveCognitionInput(List.of())), calls),
                WaveCognitionLimits.DEFAULT);

        var noContext = runtime.execute(CycleInput.of(List.of(CUE)));
        var notRequested = run(runtime, "other");
        var noWindows = run(runtime, "empty");

        for (var entry : Map.of(WaveCognitionOutcome.NO_HOST_CONTEXT, noContext,
                WaveCognitionOutcome.NOT_REQUESTED, notRequested,
                WaveCognitionOutcome.NO_WINDOWS, noWindows).entrySet()) {
            var result = entry.getValue();
            var reasoning = reasoning(result);
            assertAll(entry.getKey().name(),
                    () -> assertEquals(entry.getKey(), reasoning.report().outcome()),
                    () -> assertEquals(CognitiveStageStatus.COMPLETED, reasoning.status()),
                    () -> assertTrue(reasoning.hypotheses().isEmpty()),
                    () -> assertEquals(List.of(CUE), reasoning.outputSignals()),
                    () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                    () -> assertEquals(List.of(CognitiveStageKind.REASONING, CognitiveStageKind.EVALUATION),
                            kinds(result)));
        }
        assertEquals(List.of("other", "empty"), calls);
    }

    @Test
    void anOversizedInputIsRejectedWholeWithoutDecomposingAnyWindow() {
        var windows = new ArrayList<WaveWindow>();
        for (int i = 0; i < 3; i++) {
            windows.add(window(LOW_PURE, i, "stream-c", 64L * i));
        }
        var limits = new WaveCognitionLimits(2, 2_048, PredictionBudget.DEFAULT, HypothesisLimits.DEFAULT, false);
        var reasoning = reasoning(run(runtime(resolver(Map.of("run", new WaveCognitionInput(windows)),
                new ArrayList<>()), limits), "run"));

        assertAll(
                () -> assertEquals(WaveCognitionOutcome.REJECTED_OVERSIZED, reasoning.report().outcome()),
                () -> assertEquals(3, reasoning.report().windowsReceived()),
                () -> assertEquals(List.of(), reasoning.report().windows()),
                () -> assertEquals(0, reasoning.report().totalComparisons()),
                () -> assertTrue(reasoning.hypotheses().isEmpty()));
    }

    @Test
    void malformedWindowsContributeNothingAndTheRestArePredicted() {
        var provenance = new WaveProvenance("encoder.concept-fixture", 1, "stream-d", 0);
        var shortWindow = WaveWindow.of(new SampledWave(new double[N / 2], N, SampleUnit.HERTZ, provenance));
        var otherRate = WaveWindow.of(new SampledWave(new double[N], N * 2.0, SampleUnit.HERTZ, provenance));
        var overflowing = new double[N];
        Arrays.fill(overflowing, 1.0e200);
        var tooLoud = WaveWindow.of(new SampledWave(overflowing, N, SampleUnit.HERTZ, provenance));
        var unknownPrevious = new WaveWindow(window(LOW_PURE, 4, "stream-d", 0).wave(), OptionalLong.of(99));
        var good = window(HIGH_PURE, 5, "stream-d", 128);
        var input = WaveCognitionInput.of(shortWindow, otherRate, tooLoud, unknownPrevious, good);

        var reasoning = reasoning(run(runtime(resolver(Map.of("run", input), new ArrayList<>()),
                WaveCognitionLimits.DEFAULT), "run"));
        var windows = reasoning.report().windows();

        assertAll(
                () -> assertEquals(WaveCognitionOutcome.PROCESSED, reasoning.report().outcome()),
                () -> assertEquals(new WindowOutcome.Incompatible(0), windows.get(0)),
                () -> assertEquals(new WindowOutcome.Incompatible(1), windows.get(1)),
                () -> assertEquals(new WindowOutcome.Rejected(2, DecompositionRejectionReason.ENERGY_OUT_OF_RANGE),
                        windows.get(2)),
                () -> assertEquals(AbstentionReason.UNKNOWN_CONCEPT,
                        assertInstanceOf(WindowOutcome.Abstained.class, windows.get(3)).reason()),
                () -> assertInstanceOf(WindowOutcome.Predicted.class, windows.get(4)),
                () -> assertEquals(1, reasoning.hypotheses().size()),
                () -> assertEquals(new SpectralEvidence("stream-d", 128, EvidenceRelation.SUPPORTS, 1.0),
                        reasoning.hypotheses().hypotheses().getFirst().evidence().getFirst()));
    }

    @Test
    void theTotalComparisonBudgetStopsTheStageAndTheCycleAtAWindow() {
        var input = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-e", 0), window(HIGH_PURE, 2, "stream-e", 64));
        var limits = new WaveCognitionLimits(8, MODEL.scorableConcepts(), PredictionBudget.DEFAULT,
                HypothesisLimits.DEFAULT, false);
        var result = run(runtime(resolver(Map.of("run", input), new ArrayList<>()), limits), "run");
        var reasoning = reasoning(result);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.STAGE_LIMIT_REACHED, result.termination()),
                () -> assertEquals(List.of(CognitiveStageKind.REASONING), kinds(result)),
                () -> assertEquals(CognitiveStageStatus.LIMIT_REACHED, reasoning.status()),
                () -> assertEquals(WaveCognitionOutcome.WORK_LIMIT_REACHED, reasoning.report().outcome()),
                () -> assertEquals(1, reasoning.report().windows().size()),
                () -> assertEquals(MODEL.scorableConcepts(), reasoning.report().totalComparisons()),
                () -> assertEquals(1, reasoning.hypotheses().size()));
    }

    @Test
    void theHypothesisLimitsStopTheStageWhenACandidateOrItsEvidenceDoesNotFit() {
        var limits = new WaveCognitionLimits(8, 2_048, PredictionBudget.DEFAULT, new HypothesisLimits(1, 1), false);
        var distinct = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-f", 0), window(HIGH_PURE, 2, "stream-f", 64),
                window(LOW_PURE, 3, "stream-f", 128));
        var repeated = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-g", 0), window(LOW_PURE, 6, "stream-g", 64));
        var runtime = runtime(resolver(Map.of("distinct", distinct, "repeated", repeated), new ArrayList<>()), limits);

        for (var ref : List.of("distinct", "repeated")) {
            var result = run(runtime, ref);
            var reasoning = reasoning(result);
            assertAll(ref,
                    () -> assertEquals(CognitiveCycleTermination.STAGE_LIMIT_REACHED, result.termination()),
                    () -> assertEquals(WaveCognitionOutcome.HYPOTHESIS_LIMIT_REACHED, reasoning.report().outcome()),
                    () -> assertEquals(2, reasoning.report().windows().size()),
                    () -> assertEquals(1, reasoning.hypotheses().size()),
                    () -> assertEquals(1, reasoning.hypotheses().hypotheses().getFirst().evidence().size()));
        }
    }

    @Test
    void anExhaustedCognitiveBudgetKeepsTheHypothesesButEndsTheCycle() {
        var input = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-h", 0));
        var runtime = runtime(resolver(Map.of("run", input), new ArrayList<>()), WaveCognitionLimits.DEFAULT);

        var result = runtime.execute(CycleInput.of(List.of(CUE)).withBudget(new CognitiveBudget(1, 1, 0))
                .withHostContext(host("run")));
        var reasoning = reasoning(result);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED, result.termination()),
                () -> assertEquals(List.of(CognitiveStageKind.REASONING), kinds(result)),
                () -> assertEquals(List.of(), reasoning.outputSignals()),
                () -> assertEquals(1, reasoning.hypotheses().size()));
    }

    @Test
    void aReusedRuntimeCarriesNothingBetweenExecutionsAndRepeatsBitIdentically() {
        var calls = new ArrayList<String>();
        var input = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-i", 0), window(HIGH_PURE, 2, "stream-i", 64));
        var runtime = runtime(resolver(Map.of("a", input), calls), WaveCognitionLimits.DEFAULT);

        var first = reasoning(run(runtime, "a"));
        var between = reasoning(run(runtime, "b"));
        var again = reasoning(run(runtime, "a"));
        var fresh = reasoning(run(runtime(resolver(Map.of("a", input), new ArrayList<>()),
                WaveCognitionLimits.DEFAULT), "a"));

        assertAll(
                () -> assertEquals(List.of("a", "b", "a"), calls),
                () -> assertEquals(first, again),
                () -> assertEquals(first, fresh),
                () -> assertEquals(WaveCognitionOutcome.NOT_REQUESTED, between.report().outcome()),
                () -> assertTrue(between.hypotheses().isEmpty()));
    }

    @Test
    void theReconstructionCheckIsADiagnosticThatNeverChangesHypotheses() {
        var input = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-j", 0));
        var resolver = resolver(Map.of("run", input), new ArrayList<>());

        var plain = reasoning(run(runtime(resolver, WaveCognitionLimits.DEFAULT), "run"));
        var checked = reasoning(run(runtime(resolver, WaveCognitionLimits.DEFAULT.withVerifyReconstruction(true)),
                "run"));

        assertAll(
                () -> assertEquals(Optional.empty(),
                        ((WindowOutcome.Predicted) plain.report().windows().getFirst()).reconstruction()),
                () -> assertEquals(Optional.of(SynthesisStatus.EXACT),
                        ((WindowOutcome.Predicted) checked.report().windows().getFirst()).reconstruction()),
                () -> assertEquals(plain.hypotheses(), checked.hypotheses()));
    }

    @Test
    void aNullResolverAnswerIsAHostBugThatFailsTheCycle() {
        var runtime = runtime(context -> null, WaveCognitionLimits.DEFAULT);

        var failure = assertThrows(CognitiveCycleException.class, () -> run(runtime, "run"));

        assertInstanceOf(NullPointerException.class, failure.getCause());
    }

    @Test
    void everyLeafIsReachableThroughTheStage() {
        var windows = new ArrayList<WaveWindow>();
        for (int leaf = 0; leaf < LEAVES.length; leaf++) {
            windows.add(window(LEAVES[leaf], 10 + leaf, "stream-k", 64L * leaf));
        }
        var reasoning = reasoning(run(runtime(resolver(Map.of("run", new WaveCognitionInput(windows)),
                new ArrayList<>()), WaveCognitionLimits.DEFAULT), "run"));

        assertEquals(List.of(HIERARCHY.leaf(0), HIERARCHY.leaf(1), HIERARCHY.leaf(2), HIERARCHY.leaf(3)),
                reasoning.report().windows().stream()
                        .map(w -> ((WindowOutcome.Predicted) w).conceptOrdinal()).toList());
    }

    @Test
    void abstentionsDecidableWithoutAPatternSkipTheTransform() {
        var unknownPrevious = new WaveWindow(window(LOW_PURE, 4, "stream-l", 0).wave(), OptionalLong.of(99));
        var good = window(HIGH_PURE, 5, "stream-l", 64);
        var checked = WaveCognitionLimits.DEFAULT.withVerifyReconstruction(true);
        var tight = new WaveCognitionLimits(8, 2_048, new PredictionBudget(16, MODEL.scorableConcepts() - 1, 3),
                HypothesisLimits.DEFAULT, true);

        var mixed = reasoning(run(runtime(resolver(Map.of("run", WaveCognitionInput.of(unknownPrevious, good)),
                new ArrayList<>()), checked), "run"));
        var overBudget = reasoning(run(runtime(resolver(Map.of("run", WaveCognitionInput.of(good, good)),
                new ArrayList<>()), tight), "run"));

        assertAll(
                () -> assertEquals(new WindowOutcome.Abstained(0, AbstentionReason.UNKNOWN_CONCEPT,
                        PredictionWork.NONE, Optional.empty()), mixed.report().windows().get(0)),
                () -> assertInstanceOf(WindowOutcome.Predicted.class, mixed.report().windows().get(1)),
                () -> assertEquals(1, mixed.report().decompositions()),
                () -> assertEquals(1, mixed.report().syntheses()),
                () -> assertEquals(List.of(
                        new WindowOutcome.Abstained(0, AbstentionReason.BUDGET_EXHAUSTED, PredictionWork.NONE,
                                Optional.empty()),
                        new WindowOutcome.Abstained(1, AbstentionReason.BUDGET_EXHAUSTED, PredictionWork.NONE,
                                Optional.empty())), overBudget.report().windows()),
                () -> assertEquals(0, overBudget.report().decompositions()),
                () -> assertTrue(overBudget.hypotheses().isEmpty()));
    }

    @Test
    void theReportCountsEveryDecompositionAndSynthesis() {
        var provenance = new WaveProvenance("encoder.concept-fixture", 1, "stream-m", 0);
        var shortWindow = WaveWindow.of(new SampledWave(new double[N / 2], N, SampleUnit.HERTZ, provenance));
        var overflowing = new double[N];
        Arrays.fill(overflowing, 1.0e200);
        var tooLoud = WaveWindow.of(new SampledWave(overflowing, N, SampleUnit.HERTZ, provenance));
        var input = WaveCognitionInput.of(shortWindow, tooLoud, window(LOW_PURE, 1, "stream-m", 64),
                window(HIGH_PURE, 2, "stream-m", 128));
        var resolver = resolver(Map.of("run", input), new ArrayList<>());

        var plain = reasoning(run(runtime(resolver, WaveCognitionLimits.DEFAULT), "run")).report();
        var checked = reasoning(run(runtime(resolver, WaveCognitionLimits.DEFAULT.withVerifyReconstruction(true)),
                "run")).report();

        assertAll(
                () -> assertEquals(3, plain.decompositions()),
                () -> assertEquals(0, plain.syntheses()),
                () -> assertEquals(3, checked.decompositions()),
                () -> assertEquals(2, checked.syntheses()));
    }

    @Test
    void aSourceStageRunsSamplesOnlyExecutionsAndHandsOffTypedOnly() {
        var input = WaveCognitionInput.of(window(LOW_PURE, 1, "stream-n", 0));
        var calls = new ArrayList<String>();
        var stage = WaveCognitionReasoningStage.source(resolver(Map.of("run", input), calls), MODEL,
                WaveCognitionLimits.DEFAULT);
        var runtime = NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 128L)))
                .defaultBudget(BUDGET)
                .stage(stage)
                .stage(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 1))
                .build();

        var result = runtime.execute(CycleInput.of(List.of()).withHostContext(host("run")));
        var notRequested = runtime.execute(CycleInput.of(List.of()).withHostContext(host("other")));
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().getLast());

        assertAll(
                () -> assertTrue(stage.isSource()),
                () -> assertEquals(List.of(CognitiveStageKind.REASONING, CognitiveStageKind.EVALUATION),
                        kinds(result)),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(), reasoning(result).outputSignals()),
                () -> assertEquals(1, evaluation.evaluation().selected().size()),
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, notRequested.termination()),
                () -> assertEquals(List.of(CognitiveStageKind.REASONING), kinds(notRequested)),
                () -> assertEquals(List.of("run", "other"), calls),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> runtime.execute(CycleInput.of(List.of(CUE)).withHostContext(host("run")))),
                () -> assertThrows(IllegalArgumentException.class, () -> NeuronRuntime.builder()
                        .monad(new PrimaryMonad(new UUID(0L, 128L)))
                        .perceptionCapability(new DeterministicPerceptionCapability(Map.of()), 1)
                        .stage(stage)
                        .build()),
                () -> assertTrue(!new WaveCognitionReasoningStage(context -> Optional.empty(), MODEL,
                        WaveCognitionLimits.DEFAULT).isSource()));
    }
}
