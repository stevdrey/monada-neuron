package monada.neuron.evaluation.wave;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.evaluation.HypothesisEvaluationCognitiveStage;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Check;
import monada.neuron.host.CycleInput;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.WaveCognitionInput;
import monada.neuron.reasoning.concept.WaveCognitionLimits;
import monada.neuron.reasoning.concept.WaveCognitionReasoningStage;
import monada.neuron.reasoning.concept.WaveCognitionReport;
import monada.neuron.reasoning.concept.WaveCognitionStageResult;
import monada.neuron.reasoning.concept.WaveWindow;
import monada.neuron.reasoning.concept.WindowOutcome;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import monada.neuron.spectral.SampledWave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Host memory on/off check of issue #130: the opt-in wave cognition stage (ADR 0044) gives the same numeric
 * predictions whether the host composes no memory port, a port that recalls nothing, a port that reports
 * {@link ResonanceMemoryStatus#UNAVAILABLE} or a port that recalls a signal. Prediction success therefore does not
 * rely on the Resonance Store: the stage reads only its typed per-execution samples and the caller-owned model.
 *
 * <p>Each window is one execution (contract section 10.1: a time series is one execution per step), whose previous
 * concept is the stage's own selection for the preceding window (made two executions earlier, none at a sequence
 * start), never a label. Every composition runs the same
 * windows in the same order; the check compares the stage's {@code WaveCognitionReport} and hypotheses of every
 * execution for equality.
 */
final class HostMemoryIndependence {

    /** The host compositions compared. */
    enum Composition {
        /** No memory stage at all. */
        NO_MEMORY,
        /** A memory port that completes with no result. */
        EMPTY,
        /** A memory port that reports the memory unavailable. */
        UNAVAILABLE,
        /** A memory port that recalls one signal. */
        RECALLING
    }

    private static final PrimaryMonad MONAD = new PrimaryMonad(new UUID(0L, 130L));
    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);
    private static final Signal CUE = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
    private static final Signal RECALLED = new Signal(SignalKind.OBSERVATION, new FrequencyState(0.5, 20.0, 0.0));

    /**
     * Builds an opt-in runtime: an optional memory recall stage, the wave cognition stage reading {@code inputs} by
     * execution reference, and the reference hypothesis evaluation.
     */
    NeuronRuntime runtime(Composition composition, ConceptModel model, Map<String, WaveCognitionInput> inputs,
                          WaveCognitionLimits limits) {
        var builder = NeuronRuntime.builder().monad(MONAD).defaultBudget(BUDGET);
        ResonanceMemoryPort port = switch (composition) {
            case NO_MEMORY -> null;
            case EMPTY -> request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                    request.maxResults(), List.of());
            case UNAVAILABLE -> request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.UNAVAILABLE,
                    request.maxResults(), List.of());
            case RECALLING -> request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                    request.maxResults(), List.of(new ResonanceMemoryResult("memory-1", RECALLED, 0.9)));
        };
        if (port != null) {
            builder.memoryPort(port, 1);
        }
        return builder
                .stage(new WaveCognitionReasoningStage(context -> Optional.ofNullable(
                        inputs.get(context.executionRef().value())), model, limits))
                .stage(new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 2))
                .build();
    }

    /** Returns one cycle input for execution {@code ref}. */
    CycleInput input(String ref) {
        return CycleInput.of(List.of(CUE)).withHostContext(HostExecutionContext.of(new HostReference(ref)));
    }

    /** Returns the wave cognition stage result of a cycle. */
    WaveCognitionStageResult stageResult(CognitiveCycleResult cycle) {
        return cycle.stageResults().stream().filter(WaveCognitionStageResult.class::isInstance)
                .map(WaveCognitionStageResult.class::cast).findFirst()
                .orElseThrow(() -> new IllegalStateException("the cycle ran no wave cognition stage"));
    }

    /**
     * Runs every window as one execution, chaining the stage's own selections as the evaluation arms do: execution
     * {@code w} predicts the concept of window {@code w + 1}, so it becomes the previous concept of execution
     * {@code w + 2}; none at a sequence start or after an abstention. Per execution the trace holds the previous
     * concept the host passed, the stage's report and its hypotheses.
     */
    List<Object> trace(Composition composition, ConceptModel model, List<SampledWave> windows,
                       int windowsPerSequence) {
        var inputs = new HashMap<String, WaveCognitionInput>();
        NeuronRuntime runtime = runtime(composition, model, inputs, WaveCognitionLimits.DEFAULT);
        var trace = new ArrayList<Object>(3 * windows.size());
        OptionalLong forPrevious = OptionalLong.empty();
        OptionalLong forCurrent = OptionalLong.empty();
        for (int w = 0; w < windows.size(); w++) {
            if (w % windowsPerSequence == 0) {
                forPrevious = OptionalLong.empty();
                forCurrent = OptionalLong.empty();
            }
            String ref = "w" + w;
            inputs.put(ref, WaveCognitionInput.of(new WaveWindow(windows.get(w), forPrevious)));
            WaveCognitionStageResult result = stageResult(runtime.execute(input(ref)));
            trace.add(forPrevious);
            trace.add(result.report());
            trace.add(result.hypotheses());
            forPrevious = forCurrent;
            forCurrent = predicted(result.report())
                    .map(ordinal -> OptionalLong.of(model.hierarchy().node(ordinal).conceptCode()))
                    .orElse(OptionalLong.empty());
        }
        return trace;
    }

    /** Returns the concept ordinal the report's last examined window predicted, empty when none did. */
    Optional<Integer> predicted(WaveCognitionReport report) {
        return !report.windows().isEmpty() && report.windows().getLast() instanceof WindowOutcome.Predicted predicted
                ? Optional.of(predicted.conceptOrdinal()) : Optional.empty();
    }

    /** Compares every composition with {@link Composition#NO_MEMORY} over the same windows. */
    Check check(ConceptModel model, List<SampledWave> windows, int windowsPerSequence) {
        List<Object> reference = trace(Composition.NO_MEMORY, model, windows, windowsPerSequence);
        int predicted = 0;
        for (Object entry : reference) {
            if (entry instanceof WaveCognitionReport report && predicted(report).isPresent()) {
                predicted++;
            }
        }
        var differing = new ArrayList<String>();
        for (Composition composition : Composition.values()) {
            if (composition != Composition.NO_MEMORY
                    && !reference.equals(trace(composition, model, windows, windowsPerSequence))) {
                differing.add(composition.name());
            }
        }
        boolean passed = differing.isEmpty() && predicted > 0;
        return new Check("host.memory-independence", passed, windows.size() + " executions in sequences of "
                + windowsPerSequence + " (" + predicted + " with a predicted concept) give "
                + (differing.isEmpty() ? "identical" : "DIFFERENT " + differing)
                + " previous concepts, wave cognition reports and hypotheses with no memory port, an empty port, an "
                + "UNAVAILABLE port and a recalling port");
    }
}
