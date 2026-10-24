package monada.neuron.spectral;

import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.signal.Signal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent.class::cast)
                .map(c -> c.getName() + ":" + c.getType().getSimpleName())
                .toList();
    }
}
