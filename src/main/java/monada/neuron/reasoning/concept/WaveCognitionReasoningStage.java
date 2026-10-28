package monada.neuron.reasoning.concept;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.SpectralEvidence;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptAbstention;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptCandidates;
import monada.neuron.signal.Signal;
import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DecompositionResult.DecompositionRejected;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SynthesisStatus;
import monada.neuron.spectral.WaveSynthesizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional, experimental {@code REASONING} stage that runs wave cognition on host-supplied windows inside the
 * existing bounded cycle (contract {@code ncm-wave-cognition/2} section 10.1, ADR 0044).
 *
 * <p>Per execution it resolves the windows from the cycle's {@code HostExecutionContext}, decomposes each with the DFT
 * reference decomposer, predicts the next concept with {@link NextConceptPredictor} against a caller-owned
 * {@link ConceptModel}, and hands one hypothesis per selected concept to {@code EVALUATION}, with one
 * {@link SpectralEvidence} of weight {@code 1} that references the window's provenance; the predictor's score stays
 * in the {@link WaveCognitionReport}. Gate G3 of the
 * contract is no-go: the hypotheses carry no claim of predictive benefit, and a host that does not compose this stage
 * keeps the default cycle unchanged.
 *
 * <p>It is not a source: it runs after perception or memory recall, passes its input signals through unchanged, and
 * never encodes samples, components or provenance in a {@code Signal}. It holds only immutable configuration and
 * stateless collaborators, so a reused runtime carries nothing from one execution to the next.
 *
 * <p><b>Fail-closed.</b> A cycle without host context, a resolver that returns empty, an input without windows, or an
 * input with more than {@link WaveCognitionLimits#maxWindows()} windows produces no hypothesis and a typed
 * {@link WaveCognitionOutcome}; a window whose frame differs from the model's is not decomposed, a rejected
 * decomposition or an abstaining prediction contributes nothing. Reaching {@link WaveCognitionLimits#maxTotalComparisons()}
 * or the hypothesis limits stops processing deterministically at that window and reports {@code LIMIT_REACHED}, which
 * ends the cycle with {@code STAGE_LIMIT_REACHED}.
 *
 * <p><b>Cost.</b> One DFT of the model's sample count per compatible window (O(N²) for the reference decomposer), one
 * bounded prediction, and one synthesis when {@link WaveCognitionLimits#verifyReconstruction()} is set; at most
 * {@link WaveCognitionLimits#maxWindows()} windows per execution.
 */
public final class WaveCognitionReasoningStage implements CognitiveStage {

    /**
     * Weight of one window's evidence. The predictor's score is reported, not used as evidence mass: contract section
     * 8.5 admits it into evaluation only as an explicitly configured resonance-like component.
     */
    static final double WINDOW_WEIGHT = 1.0;

    private final DftWaveDecomposer decomposer = new DftWaveDecomposer();
    private final NextConceptPredictor predictor = new NextConceptPredictor();
    private final WaveSynthesizer synthesizer = new WaveSynthesizer();
    private final WaveInputResolver resolver;
    private final ConceptModel model;
    private final WaveCognitionLimits limits;

    /**
     * Creates a stage.
     *
     * @param resolver host adapter resolving the windows from the host context
     * @param model caller-owned immutable model snapshot; its hierarchy's domain is the hypotheses' domain
     * @param limits work bounds of each execution
     */
    public WaveCognitionReasoningStage(WaveInputResolver resolver, ConceptModel model, WaveCognitionLimits limits) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.REASONING;
    }

    /**
     * Resolves the windows and predicts.
     *
     * @throws NullPointerException if the resolver returns {@code null}
     */
    @Override
    public WaveCognitionStageResult execute(PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        var signals = List.copyOf(Objects.requireNonNull(inputSignals, "inputSignals must not be null"));
        Objects.requireNonNull(context, "context must not be null");
        var hostContext = context.hostContext();
        if (hostContext.isEmpty()) {
            return withoutWindows(signals, WaveCognitionOutcome.NO_HOST_CONTEXT, 0);
        }
        Optional<WaveCognitionInput> resolved = Objects.requireNonNull(
                resolver.resolve(hostContext.get()), "resolver result must not be null");
        if (resolved.isEmpty()) {
            return withoutWindows(signals, WaveCognitionOutcome.NOT_REQUESTED, 0);
        }
        List<WaveWindow> windows = resolved.get().windows();
        if (windows.isEmpty()) {
            return withoutWindows(signals, WaveCognitionOutcome.NO_WINDOWS, 0);
        }
        if (windows.size() > limits.maxWindows()) {
            return withoutWindows(signals, WaveCognitionOutcome.REJECTED_OVERSIZED, windows.size());
        }
        return predict(signals, windows);
    }

    private WaveCognitionStageResult predict(List<Signal> signals, List<WaveWindow> windows) {
        var builder = new HypothesisSetBuilder(limits.hypothesisLimits());
        var outcomes = new ArrayList<WindowOutcome>(windows.size());
        var outcome = WaveCognitionOutcome.PROCESSED;
        int totalComparisons = 0;
        for (int position = 0; position < windows.size(); position++) {
            WaveWindow window = windows.get(position);
            SampledWave wave = window.wave();
            if (!matchesModelFrame(wave)) {
                outcomes.add(new WindowOutcome.Incompatible(position));
                continue;
            }
            if (totalComparisons + model.scorableConcepts() > limits.maxTotalComparisons()) {
                outcome = WaveCognitionOutcome.WORK_LIMIT_REACHED;
                break;
            }
            SpectralPattern pattern;
            switch (decomposer.decompose(wave)) {
                case DecompositionRejected rejected -> {
                    outcomes.add(new WindowOutcome.Rejected(position, rejected.reason()));
                    continue;
                }
                case Decomposed decomposed -> pattern = decomposed.pattern();
            }
            Optional<SynthesisStatus> reconstruction = limits.verifyReconstruction()
                    ? Optional.of(synthesizer.synthesize(pattern, wave).status())
                    : Optional.empty();
            ConceptPrediction prediction = predictor.predict(
                    new ConceptQuery(pattern, window.previousConceptCode()), model, limits.predictionBudget());
            totalComparisons += prediction.work().comparisons();
            switch (prediction) {
                case ConceptAbstention abstention -> outcomes.add(new WindowOutcome.Abstained(
                        position, abstention.reason(), abstention.work(), reconstruction));
                case ConceptCandidates candidates -> {
                    ConceptCandidate selected = candidates.selected();
                    outcomes.add(new WindowOutcome.Predicted(position, selected.conceptOrdinal(), selected.depth(),
                            selected.score(), candidates.refinementStop(), candidates.work(), reconstruction));
                    if (!propose(builder, selected, wave)) {
                        outcome = WaveCognitionOutcome.HYPOTHESIS_LIMIT_REACHED;
                    }
                }
            }
            if (outcome != WaveCognitionOutcome.PROCESSED) {
                break;
            }
        }
        var status = outcome.limitReached() ? CognitiveStageStatus.LIMIT_REACHED : CognitiveStageStatus.COMPLETED;
        return new WaveCognitionStageResult(status, signals, builder.build(),
                new WaveCognitionReport(outcome, windows.size(), outcomes, totalComparisons));
    }

    /** Proposes the selected concept with the window as evidence; false when the hypothesis limits are reached. */
    private boolean propose(HypothesisSetBuilder builder, ConceptCandidate selected, SampledWave wave) {
        var sequence = builder.propose(model.hierarchy().proposition(selected.conceptOrdinal()));
        if (sequence.isEmpty()) {
            return false;
        }
        var provenance = wave.provenance();
        return builder.addEvidence(sequence.getAsInt(), new SpectralEvidence(
                provenance.sourceReference(), provenance.windowOffset(), EvidenceRelation.SUPPORTS, WINDOW_WEIGHT));
    }

    /** Compares the window with the model frame before any decomposition work. */
    private boolean matchesModelFrame(SampledWave wave) {
        SpectralFrame frame = model.frame();
        return wave.sampleCount() == frame.sampleCount()
                && Double.compare(wave.sampleRate(), frame.sampleRate()) == 0
                && wave.unit() == frame.unit();
    }

    private static WaveCognitionStageResult withoutWindows(
            List<Signal> signals, WaveCognitionOutcome outcome, int windowsReceived) {
        return new WaveCognitionStageResult(CognitiveStageStatus.COMPLETED, signals, HypothesisSet.EMPTY,
                WaveCognitionReport.empty(outcome, windowsReceived));
    }
}
