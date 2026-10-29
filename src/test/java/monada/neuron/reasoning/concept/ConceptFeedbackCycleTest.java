package monada.neuron.reasoning.concept;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.host.CycleInput;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.WaveProvenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.UUID;

import static monada.neuron.reasoning.concept.ConceptFixtures.HIGH_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.N;
import static monada.neuron.reasoning.concept.ConceptFixtures.leafSamples;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Cross-cycle feedback through the opt-in stage (contract section 10.2, ADR 0049): cycle N predicts with a snapshot,
 * the host verifies the step and applies the outcome, and cycle N+1 runs only with the snapshot the caller approved.
 */
class ConceptFeedbackCycleTest {

    private static final String VERIFIER = "verifier.host";
    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);
    private static final Signal CUE = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
    private static final ConceptFeedbackPolicy POLICY =
            new ConceptFeedbackPolicy(ConceptFeedbackLimits.trusting(VERIFIER));

    private static WaveWindow window(long leaf, long seed, long offset) {
        var provenance = new WaveProvenance("encoder.concept-fixture", 1, "stream", offset);
        return new WaveWindow(new SampledWave(leafSamples(leaf, new SplittableRandom(seed)), N, SampleUnit.HERTZ,
                provenance), OptionalLong.of(leaf));
    }

    private static CognitiveCycleResult cycle(ConceptModel model, WaveWindow window) {
        var runtime = NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 129L)))
                .defaultBudget(BUDGET)
                .stage(new WaveCognitionReasoningStage(context -> Optional.of(WaveCognitionInput.of(window)), model,
                        WaveCognitionLimits.DEFAULT))
                .build();
        return runtime.execute(CycleInput.of(List.of(CUE))
                .withHostContext(HostExecutionContext.of(new HostReference("run"))));
    }

    private static WaveCognitionStageResult reasoning(CognitiveCycleResult result) {
        return assertInstanceOf(WaveCognitionStageResult.class, result.stageResults().stream()
                .filter(stage -> stage.kind() == CognitiveStageKind.REASONING).findFirst().orElseThrow());
    }

    private static WindowOutcome.Predicted predicted(CognitiveCycleResult result) {
        return assertInstanceOf(WindowOutcome.Predicted.class, reasoning(result).report().windows().getFirst());
    }

    @Test
    void priorCycleStaysImmutableAndNextCycleReadsTheApprovedSnapshot() {
        var model = ConceptFixtures.trainedModel();
        var first = window(LOW_PURE, 1, 0);
        var cycleN = cycle(model, first);
        var reportN = reasoning(cycleN).report();
        var receipt = ConceptPredictionReceipt.of(model, first, predicted(cycleN));

        var observed = window(HIGH_PURE, 2, 64);
        var evidence = ((Decomposed) new DftWaveDecomposer().decompose(observed.wave())).pattern();
        var outcome = ConceptOutcome.verified(receipt, HIGH_PURE, evidence, OptionalLong.of(LOW_PURE), 0, VERIFIER);
        var update = assertInstanceOf(ConceptModelUpdate.Updated.class, POLICY.apply(model, List.of(outcome), 1));
        var approved = update.next();

        var cycleNPlusOne = cycle(approved, observed);
        var receiptNPlusOne = ConceptPredictionReceipt.of(approved, observed, predicted(cycleNPlusOne));

        assertAll(
                () -> assertEquals(reportN, reasoning(cycleN).report(), "cycle N's result is never rewritten"),
                () -> assertEquals(reportN, reasoning(cycle(model, first)).report(),
                        "the previous snapshot still predicts as it did"),
                () -> assertEquals(model.fingerprint(), receipt.modelFingerprint()),
                () -> assertEquals(approved.fingerprint(), receiptNPlusOne.modelFingerprint()),
                () -> assertNotEquals(receipt.modelFingerprint(), receiptNPlusOne.modelFingerprint()),
                () -> assertEquals(1, approved.revision()),
                () -> assertEquals(model.transitionCount(2, 4) + 1, approved.transitionCount(2, 4)));
    }

    @Test
    void withoutFeedbackTheNextCycleMatchesTheBaseline() {
        var model = ConceptFixtures.trainedModel();
        var window = window(LOW_PURE, 1, 0);
        var baseline = reasoning(cycle(model, window)).report();
        var receipt = ConceptPredictionReceipt.of(model, window, predicted(cycle(model, window)));
        var update = POLICY.apply(model, List.of(
                ConceptOutcome.unverified(receipt, ConceptOutcome.Verification.UNVERIFIED, 0, VERIFIER)), 1);

        assertAll(
                () -> assertSame(model, update.current()),
                () -> assertEquals(baseline, reasoning(cycle(update.current(), window)).report()));
    }
}
