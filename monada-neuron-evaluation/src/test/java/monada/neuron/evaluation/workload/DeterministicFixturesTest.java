package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionRequest;
import monada.neuron.action.ActionStatus;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the literal behavior of the fixtures that the full-cycle validity gate uses as its reference. */
class DeterministicFixturesTest {

    private static final double TOLERANCE = 1e-12;

    private final List<Signal> inputs = List.of(
            new Signal(SignalKind.INTERMEDIATE, new FrequencyState(0.8, 10.0, 1.0)),
            new Signal(SignalKind.INTERMEDIATE, new FrequencyState(0.4, 20.0, 2.0)),
            new Signal(SignalKind.INTERMEDIATE, new FrequencyState(0.2, 30.0, 3.0)));

    @Test
    void memoryFixtureRecallsOneScaledMatchPerInputUpToTheLimit() {
        var response = new DeterministicMemoryFixture().recall(new ResonanceMemoryRequest(inputs, 2));

        assertEquals(ResonanceMemoryStatus.COMPLETE, response.status());
        assertEquals(2, response.resultLimit());
        assertEquals(2, response.results().size());
        for (int i = 0; i < 2; i++) {
            var result = response.results().get(i);
            var source = inputs.get(i).frequencyState();
            var state = result.signal().frequencyState();
            assertEquals("mem-" + i, result.reference());
            assertEquals(0.85, result.score(), TOLERANCE);
            assertEquals(SignalKind.INTERMEDIATE, result.signal().kind());
            assertEquals(source.amplitude() * 0.95, state.amplitude(), TOLERANCE);
            assertEquals(source.frequency() * 1.02, state.frequency(), TOLERANCE);
            assertEquals(source.phase() + 0.05, state.phase(), TOLERANCE);
        }
    }

    @Test
    void actionFixtureEmitsOneShiftedObservationPerInputUpToTheLimit() {
        var result = new DeterministicActionFixture().execute(new ActionRequest(inputs, 2, Optional.empty()));

        assertEquals(ActionStatus.SUCCEEDED, result.status());
        assertEquals(2, result.observationLimit());
        assertEquals(2, result.observations().size());
        for (int i = 0; i < 2; i++) {
            var observation = result.observations().get(i);
            var source = inputs.get(i).frequencyState();
            var state = observation.frequencyState();
            assertEquals(SignalKind.OBSERVATION, observation.kind());
            assertEquals(source.amplitude(), state.amplitude(), TOLERANCE);
            assertEquals(source.frequency() + 1.0, state.frequency(), TOLERANCE);
            assertEquals(0.0, state.phase(), TOLERANCE);
        }
    }
}
