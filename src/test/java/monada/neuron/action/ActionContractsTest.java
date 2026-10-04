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
    void preservesExpectedOutcomeSemanticsAndKeepsTheCapabilityStatusForAnAdmittedPrefix() {
        var request = new ActionRequest(List.of(signal(1.0)), 2);
        var complete = new ActionOutcome(request, new ActionResult(
                ActionStatus.SUCCEEDED,
                2,
                List.of(signal(2.0), signal(3.0))));

        assertAll(
                // the cycle budget never redefines what the capability reported
                () -> assertEquals(ActionStatus.SUCCEEDED,
                        complete.withAdmittedObservationPrefix(List.of(signal(2.0))).result().status()),
                () -> assertEquals(ActionStatus.SUCCEEDED,
                        complete.withAdmittedObservationPrefix(List.of()).result().status()),
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

    @Test
    void stageResultRecordsProducedObservationsAndWhetherTheCycleTruncatedThem() {
        var request = new ActionRequest(List.of(signal(1.0)), 3);
        var outcome = new ActionOutcome(request, new ActionResult(
                ActionStatus.SUCCEEDED, 3, List.of(signal(2.0), signal(3.0), signal(4.0))));

        var complete = new ActionCognitiveStageResult(outcome);
        var truncated = complete.withAdmittedOutputSignals(List.of(signal(2.0)));
        var untouched = complete.withAdmittedOutputSignals(List.of(signal(2.0), signal(3.0), signal(4.0)));

        assertAll(
                () -> assertEquals(3, complete.producedObservationCount()),
                () -> assertEquals(3, complete.admittedObservationCount()),
                () -> assertEquals(ObservationAdmission.COMPLETE, complete.observationAdmission()),
                () -> assertEquals(ActionStatus.SUCCEEDED, truncated.outcome().result().status()),
                () -> assertEquals(3, truncated.producedObservationCount()),
                () -> assertEquals(1, truncated.admittedObservationCount()),
                () -> assertEquals(ObservationAdmission.TRUNCATED, truncated.observationAdmission()),
                () -> assertEquals(List.of(signal(2.0)), truncated.outputSignals()),
                () -> assertEquals(ObservationAdmission.COMPLETE, untouched.observationAdmission()),
                // a later, smaller admission keeps the originally produced count
                () -> assertEquals(3, truncated.withAdmittedOutputSignals(List.of()).producedObservationCount()),
                () -> assertEquals(ObservationAdmission.TRUNCATED,
                        truncated.withAdmittedOutputSignals(List.of()).observationAdmission()));
    }

    @Test
    void stageResultValidatesItsProvenanceCounters() {
        var request = new ActionRequest(List.of(signal(1.0)), 3);
        var outcome = new ActionOutcome(request, new ActionResult(
                ActionStatus.PARTIALLY_COMPLETED, 3, List.of(signal(2.0), signal(3.0))));

        assertAll(
                () -> assertEquals(ObservationAdmission.TRUNCATED,
                        new ActionCognitiveStageResult(outcome, 3, ObservationAdmission.TRUNCATED).observationAdmission()),
                // produced cannot be smaller than what was admitted
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ActionCognitiveStageResult(outcome, 1, ObservationAdmission.COMPLETE)),
                // the admission flag must agree with the counters
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ActionCognitiveStageResult(outcome, 3, ObservationAdmission.COMPLETE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ActionCognitiveStageResult(outcome, 2, ObservationAdmission.TRUNCATED)),
                // produced observations are bounded by the request limit
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ActionCognitiveStageResult(outcome, 4, ObservationAdmission.TRUNCATED)),
                () -> assertThrows(NullPointerException.class,
                        () -> new ActionCognitiveStageResult(outcome, 2, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new ActionCognitiveStageResult(null)));
    }
}
