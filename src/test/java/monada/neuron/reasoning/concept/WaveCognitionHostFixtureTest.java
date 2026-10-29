package monada.neuron.reasoning.concept;

import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.evaluation.HypothesisEvaluationCognitiveStage;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.host.CycleInput;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.perception.DeterministicPerceptionCapability;
import monada.neuron.perception.DeterministicPerceptionCapability.Script;
import monada.neuron.perception.PerceptionStatus;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.WaveProvenance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.UUID;

import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.N;
import static monada.neuron.reasoning.concept.ConceptFixtures.leafSamples;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration fixture of the minimal host API for opt-in wave cognition (contract {@code ncm-wave-cognition/2}
 * section 10.1, fixture N13, ADR 0044).
 *
 * <p>The host opts in by composing one extra stage; it supplies samples per execution through a
 * {@link WaveInputResolver} keyed by the {@link HostExecutionContext}, and a caller-owned {@link ConceptModel}:
 * <pre>{@code
 * NeuronRuntime.builder()
 *         .monad(monad)
 *         .memoryPort(port, maxResults)                              // unchanged default composition
 *         .stage(new WaveCognitionReasoningStage(resolver, model, WaveCognitionLimits.DEFAULT))
 *         .stage(new HypothesisEvaluationCognitiveStage(policy, maxSelected))
 *         .build();
 * runtime.execute(CycleInput.of(signals).withHostContext(HostExecutionContext.of(executionRef)));
 * }</pre>
 * A runtime built without the stage is the default runtime, and an execution whose context resolves to no input runs
 * the stage as a pass-through.
 *
 * <p>The golden files {@code n13-*.txt} are the {@code toString} of the same default executions recorded on
 * {@code main} at {@code e3b26fa}, before this stage existed; every record in a cycle result prints its values, so
 * equal text means an equal result.
 */
class WaveCognitionHostFixtureTest {

    private static final PrimaryMonad MONAD = new PrimaryMonad(new UUID(0L, 13L));
    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);
    private static final Signal CUE = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
    private static final Signal RECALLED = new Signal(SignalKind.OBSERVATION, new FrequencyState(0.5, 20.0, 0.0));
    private static final ResonanceMemoryPort MEMORY = request -> new ResonanceMemoryResponse(
            ResonanceMemoryStatus.COMPLETE, request.maxResults(),
            List.of(new ResonanceMemoryResult("memory-1", RECALLED, 0.9)));

    /** The default composition as on {@code main}: memory recall then evaluation. */
    private static NeuronRuntime.Builder defaultComposition() {
        return NeuronRuntime.builder().monad(MONAD).defaultBudget(BUDGET).memoryPort(MEMORY, 1);
    }

    private static NeuronRuntime defaultRuntime() {
        return defaultComposition()
                .stage(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 2))
                .build();
    }

    /** A second default composition as on {@code main}: perception, memory recall, evaluation and action. */
    private static NeuronRuntime perceivingDefaultRuntime() {
        return NeuronRuntime.builder().monad(MONAD).defaultBudget(BUDGET)
                .perceptionCapability(new DeterministicPerceptionCapability(Map.of(
                        "with-waves", new Script(PerceptionStatus.SUCCEEDED, List.of(CUE)))), 2)
                .memoryPort(MEMORY, 1)
                .stage(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 2))
                .actionCapability(request -> new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(),
                        List.of()), 1)
                .build();
    }

    private static String golden(String name) {
        try (InputStream in = WaveCognitionHostFixtureTest.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static NeuronRuntime optInRuntime() {
        var samples = leafSamples(LOW_PURE, new SplittableRandom(13));
        var wave = new SampledWave(samples, N, SampleUnit.HERTZ,
                new WaveProvenance("encoder.host-fixture", 1, "sensor-1", 0));
        Map<String, WaveCognitionInput> perExecution = Map.of(
                "with-waves", WaveCognitionInput.of(new WaveWindow(wave, OptionalLong.of(LOW_PURE))));
        WaveInputResolver resolver = context -> Optional.ofNullable(perExecution.get(context.executionRef().value()));
        return defaultComposition()
                .stage(new WaveCognitionReasoningStage(resolver, ConceptFixtures.trainedModel(),
                        WaveCognitionLimits.DEFAULT))
                .stage(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 2))
                .build();
    }

    private static CycleInput input(String ref) {
        return CycleInput.of(List.of(CUE)).withHostContext(HostExecutionContext.of(new HostReference(ref)));
    }

    @Test
    void n13TheDefaultRuntimeIsUnchangedBeforeAndAfterAnOptInCycle() {
        var before = defaultRuntime().execute(input("with-waves"));
        var optIn = optInRuntime().execute(input("with-waves"));
        var after = defaultRuntime().execute(input("with-waves"));
        CognitiveCycleResult perceiving = perceivingDefaultRuntime().execute(CycleInput.of(List.of())
                .withHostContext(HostExecutionContext.of(new HostReference("with-waves"))));

        assertAll(
                () -> assertEquals(golden("n13-memory-evaluation.txt"), before.toString()),
                () -> assertEquals(golden("n13-perception-memory-evaluation-action.txt"), perceiving.toString()),
                () -> assertEquals(before, after),
                () -> assertEquals(List.of(CognitiveStageKind.MEMORY_RECALL, CognitiveStageKind.EVALUATION),
                        before.stageResults().stream().map(CognitiveStageResult::kind).toList()),
                () -> assertEquals(List.of(CognitiveStageKind.MEMORY_RECALL, CognitiveStageKind.REASONING,
                        CognitiveStageKind.EVALUATION),
                        optIn.stageResults().stream().map(CognitiveStageResult::kind).toList()),
                () -> assertEquals(1, ((WaveCognitionStageResult) optIn.stageResults().get(1)).hypotheses().size()));
    }

    @Test
    void anOptInRuntimeWithoutInputForAnExecutionKeepsTheDefaultSignalsAndTermination() {
        var runtime = optInRuntime();
        runtime.execute(input("with-waves"));

        var baseline = defaultRuntime().execute(input("plain"));
        var passThrough = runtime.execute(input("plain"));
        var reasoning = (WaveCognitionStageResult) passThrough.stageResults().get(1);

        assertAll(
                () -> assertEquals(baseline.termination(), passThrough.termination()),
                () -> assertEquals(baseline.outputSignals(), passThrough.outputSignals()),
                () -> assertEquals(baseline.stageResults().getFirst(), passThrough.stageResults().getFirst()),
                () -> assertEquals(WaveCognitionOutcome.NOT_REQUESTED, reasoning.report().outcome()),
                () -> assertEquals(List.of(CUE, RECALLED), reasoning.outputSignals()),
                () -> assertEquals(baseline.stageResults().getLast(), passThrough.stageResults().getLast()));
    }
}
