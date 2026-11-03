package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.HostMemoryIndependence.Composition;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.WaveCognitionOutcome;
import monada.neuron.reasoning.concept.WaveCognitionReport;
import monada.neuron.spectral.SampledWave;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

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

        var check = host.check(model, windows, 3);

        assertTrue(check.passed(), check.detail());
        assertTrue(check.detail().startsWith("3 executions in sequences of 3 (3 with a predicted concept)"),
                check.detail());
    }

    @Test
    void aPredictionBecomesThePreviousConceptTwoExecutionsLaterWithinItsSequence() {
        ConceptModel model = tiny.model();
        List<SampledWave> windows = List.of(tiny.window(0, 0), tiny.window(1, 1), tiny.window(0, 2),
                tiny.window(1, 0), tiny.window(0, 1), tiny.window(1, 2));

        List<Object> trace = host.trace(Composition.RECALLING, model, windows, 3);

        assertEquals(18, trace.size());
        // Sequence 1: none, none, then execution 0's prediction (the next concept of a TONE_1 window: TONE_3).
        assertEquals(OptionalLong.empty(), trace.get(0));
        assertEquals(OptionalLong.empty(), trace.get(3));
        assertEquals(OptionalLong.of(model.hierarchy().node(1).conceptCode()), trace.get(6));
        // Sequence 2 starts afresh: no previous concept carried across the boundary.
        assertEquals(OptionalLong.empty(), trace.get(9));
        assertEquals(OptionalLong.empty(), trace.get(12));
        assertEquals(OptionalLong.of(model.hierarchy().node(0).conceptCode()), trace.get(15));
    }

    @Test
    void aReportWithoutExaminedWindowsHasNoPrediction() {
        assertEquals(Optional.empty(), host.predicted(new WaveCognitionReport(WaveCognitionOutcome.NO_WINDOWS, 0,
                List.of(), 0, 0, 0)));
    }
}
