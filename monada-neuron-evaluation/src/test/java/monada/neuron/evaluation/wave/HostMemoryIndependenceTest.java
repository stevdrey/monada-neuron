package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.HostMemoryIndependence.Composition;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.WaveCognitionReport;
import monada.neuron.spectral.SampledWave;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host memory on/off check on the tiny workload's model. */
class HostMemoryIndependenceTest {

    private final HostMemoryIndependence host = new HostMemoryIndependence();
    private final TinyWorkload tiny = new TinyWorkload();

    @Test
    void everyMemoryCompositionGivesTheSamePredictions() {
        ConceptModel model = tiny.model();
        List<SampledWave> windows = List.of(tiny.window(0, 0), tiny.window(1, 1), tiny.window(0, 2));

        var check = host.check(model, windows);

        assertTrue(check.passed(), check.detail());
        assertTrue(check.detail().startsWith("3 executions (3 with a predicted concept)"), check.detail());
    }

    @Test
    void theTraceChainsTheStagesOwnSelection() {
        List<Object> trace = host.trace(Composition.RECALLING, tiny.model(),
                List.of(tiny.window(0, 0), tiny.window(1, 1)));
        assertEquals(4, trace.size());
        var first = (WaveCognitionReport) trace.getFirst();
        var second = (WaveCognitionReport) trace.get(2);
        assertEquals(1, first.windows().size());
        assertEquals(1, second.windows().size());
    }
}
