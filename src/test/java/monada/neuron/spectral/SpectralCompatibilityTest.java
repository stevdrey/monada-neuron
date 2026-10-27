package monada.neuron.spectral;

import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.resonance.BandContribution;
import monada.neuron.resonance.ComparisonResult;
import monada.neuron.resonance.DominantComponentProjection;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.signal.Signal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Proves the opt-in spectral types left the single-wave path of ADR 0005 and ADR 0006 unchanged. */
class SpectralCompatibilityTest {

    @Test
    void signalAndFrequencyStateKeepTheirComponents() {
        assertEquals(List.of("kind:SignalKind", "frequencyState:FrequencyState"), components(Signal.class));
        assertEquals(List.of("amplitude:double", "frequency:double", "phase:double"),
                components(FrequencyState.class));
        assertEquals(new FrequencyState(0.0, 0.0, 0.0), FrequencyState.ZERO);
    }

    @Test
    void scalarMetricKeepsItsReferenceScores() {
        var metric = new ScalarResonanceMetric();

        assertEquals(1.0, metric.score(new FrequencyState(1.0, 10.0, 0.0), new FrequencyState(1.0, 10.0, 0.0)));
        assertEquals(0.125, metric.score(new FrequencyState(1.0, 10.0, 0.0),
                new FrequencyState(0.5, 20.0, StrictMath.PI / 2)));
        assertEquals(0.0, metric.score(FrequencyState.ZERO, new FrequencyState(1.0, 1.0, 0.0)));
    }

    @Test
    void canonicalStageKindsAreUnchanged() {
        assertEquals(List.of("PERCEPTION", "MEMORY_RECALL", "REASONING", "EVALUATION", "ADAPTATION", "ACTION"),
                Arrays.stream(CognitiveStageKind.values()).map(Enum::name).toList());
    }

    @Test
    void spectralTypesTakeExplicitSamplesOrPatternsNeverSignals() {
        assertEquals(List.of("samples:double[]", "sampleRate:double", "unit:SampleUnit",
                "provenance:WaveProvenance"), components(SampledWave.class));
        for (Class<?> type : List.of(SampledWave.class, WaveEncoder.class, WaveDecomposer.class,
                DftWaveDecomposer.class, HaarWaveDecomposer.class, DecompositionResult.class,
                SpectralResonanceDynamics.class, ComparisonResult.class, ComparisonResult.SpectralComparison.class,
                ComparisonResult.Incompatible.class, BandContribution.class, DominantComponentProjection.class)) {
            for (Method method : type.getMethods()) {
                assertFalse(Arrays.asList(method.getParameterTypes()).contains(Signal.class),
                        type.getSimpleName() + "." + method.getName() + " must not accept a Signal");
                assertNotEquals(Signal.class, method.getReturnType());
            }
        }
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(c -> c.getName() + ":" + c.getType().getSimpleName())
                .toList();
    }
}
