package monada.neuron.action;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionContractsTest {

    @Test
    void requestAndResultSnapshotOrderedCollectionsAndPreserveDuplicateObservations() {
        var inputs = new ArrayList<>(List.of(signal(1.0)));
        var observations = new ArrayList<>(List.of(signal(2.0), signal(2.0)));
        var request = new ActionRequest(inputs, 2);
        var result = new ActionResult(ActionStatus.SUCCEEDED, 2, observations);
        inputs.clear();
        observations.clear();

        assertAll(
                () -> assertEquals(List.of(signal(1.0)), request.inputSignals()),
                () -> assertEquals(List.of(signal(2.0), signal(2.0)), result.observations()),
                () -> assertThrows(UnsupportedOperationException.class, () -> request.inputSignals().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> result.observations().clear()));
    }

    @Test
    void rejectsInvalidRequestsResultsAndOutcomes() {
        var request = new ActionRequest(List.of(signal(1.0)), 1);

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new ActionRequest(null, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ActionRequest(List.of(), 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ActionRequest(List.of(signal(1.0)), 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ActionResult(ActionStatus.SUCCEEDED, 1, List.of(signal(1.0), signal(2.0)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ActionResult(ActionStatus.REJECTED, 1, List.of(signal(1.0)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ActionOutcome(request, new ActionResult(ActionStatus.SUCCEEDED, 2, List.of()))));
    }

    @Test
    void preservesExpectedOutcomeSemanticsAndNormalizesAnAdmittedSuccessPrefix() {
        var request = new ActionRequest(List.of(signal(1.0)), 2);
        var complete = new ActionOutcome(request, new ActionResult(
                ActionStatus.SUCCEEDED,
                2,
                List.of(signal(2.0), signal(3.0))));

        assertAll(
                () -> assertEquals(ActionStatus.PARTIALLY_COMPLETED,
                        complete.withAdmittedObservationPrefix(List.of(signal(2.0))).result().status()),
                () -> assertEquals(List.of(signal(2.0)),
                        complete.withAdmittedObservationPrefix(List.of(signal(2.0))).result().observations()),
                () -> assertEquals(ActionStatus.PARTIALLY_COMPLETED,
                        new ActionResult(ActionStatus.PARTIALLY_COMPLETED, 2, List.of()).status()),
                () -> assertEquals(List.of(), new ActionResult(ActionStatus.REJECTED, 2, List.of()).observations()),
                () -> assertEquals(List.of(), new ActionResult(ActionStatus.UNAVAILABLE, 2, List.of()).observations()),
                () -> assertEquals(List.of(), new ActionResult(ActionStatus.TIMED_OUT, 2, List.of()).observations()),
                () -> assertEquals(List.of(), new ActionResult(ActionStatus.FAILED, 2, List.of()).observations()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> complete.withAdmittedObservationPrefix(List.of(signal(3.0)))));
    }

    @Test
    void deterministicExecutorReturnsExactFixturesDefaultsToUnavailableAndRecordsRequests() {
        var fixtureRequest = new ActionRequest(List.of(signal(1.0)), 2);
        var fixtureResult = new ActionResult(ActionStatus.SUCCEEDED, 2, List.of(signal(2.0)));
        var executor = new DeterministicActionExecutor(Map.of(fixtureRequest, fixtureResult));
        var defaultRequest = new ActionRequest(List.of(signal(3.0)), 2);

        assertAll(
                () -> assertEquals(fixtureResult, executor.execute(fixtureRequest)),
                () -> assertEquals(new ActionResult(ActionStatus.UNAVAILABLE, 2, List.of()),
                        executor.execute(defaultRequest)),
                () -> assertEquals(List.of(fixtureRequest, defaultRequest), executor.receivedRequests()));
    }

    private Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }
}
