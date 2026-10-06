package monada.neuron.perception;

import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerceptionContractsTest {

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    @Test
    void requestRequiresAPositiveLimitAndAnExplicitOptionalContext() {
        var context = HostExecutionContext.of(new HostReference("run-1"));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new PerceptionRequest(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new PerceptionRequest(-1)),
                () -> assertThrows(NullPointerException.class, () -> new PerceptionRequest(1, null)),
                () -> assertEquals(Optional.empty(), new PerceptionRequest(2).hostContext()),
                () -> assertEquals(Optional.of(context), new PerceptionRequest(2, Optional.of(context)).hostContext()),
                () -> assertEquals(2, new PerceptionRequest(2).maxSignals()));
    }

    @Test
    void resultSnapshotsSignalsAndEnforcesTheLimit() {
        var first = observation(1.0);
        var second = observation(2.0);
        var mutable = new ArrayList<>(List.of(first, second));

        var result = new PerceptionResult(PerceptionStatus.SUCCEEDED, 2, mutable);
        mutable.clear();

        assertAll(
                () -> assertEquals(List.of(first, second), result.signals()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 1, List.of(first, second))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 0, List.of(first))),
                () -> assertThrows(NullPointerException.class,
                        () -> new PerceptionResult(null, 1, List.of(first))),
                () -> assertThrows(NullPointerException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 1, null)));
    }

    @Test
    void resultRejectsAnySignalThatIsNotAnObservation() {
        var intermediate = new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 10.0, 0.0));
        var feedback = new Signal(SignalKind.FEEDBACK, new FrequencyState(1.0, 10.0, 0.0));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.SUCCEEDED, 2, List.of(observation(1.0), intermediate))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionResult(PerceptionStatus.PARTIALLY_COMPLETED, 1, List.of(feedback))));
    }

    @Test
    void nonFiniteFrequencyStatesAreAlreadyRejectedByTheExistingValidation() {
        assertThrows(IllegalArgumentException.class, () -> observation(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> observation(Double.POSITIVE_INFINITY));
    }

    @Test
    void statusDecidesWhetherSignalsAreRequiredOrForbidden() {
        var signal = observation(1.0);

        for (var status : List.of(PerceptionStatus.SUCCEEDED, PerceptionStatus.PARTIALLY_COMPLETED)) {
            assertAll(
                    () -> assertEquals(List.of(signal), new PerceptionResult(status, 1, List.of(signal)).signals()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new PerceptionResult(status, 1, List.of())));
        }
        for (var status : List.of(
                PerceptionStatus.EMPTY,
                PerceptionStatus.REJECTED,
                PerceptionStatus.UNAVAILABLE,
                PerceptionStatus.TIMED_OUT,
                PerceptionStatus.FAILED)) {
            assertAll(
                    () -> assertEquals(List.of(), new PerceptionResult(status, 1, List.of()).signals()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new PerceptionResult(status, 1, List.of(signal))));
        }
    }

    @Test
    void outcomeRequiresTheResultLimitToMatchTheRequest() {
        var request = new PerceptionRequest(2);
        var result = new PerceptionResult(PerceptionStatus.EMPTY, 3, List.of());

        assertThrows(IllegalArgumentException.class, () -> new PerceptionOutcome(request, result));
        assertThrows(NullPointerException.class, () -> new PerceptionOutcome(null, result));
        assertThrows(NullPointerException.class, () -> new PerceptionOutcome(request, null));
    }

    @Test
    void admittedPrefixKeepsTheAdapterStatusAndRejectsAnythingButAnOrderedPrefix() {
        var first = observation(1.0);
        var second = observation(2.0);
        var third = observation(3.0);
        var result = new PerceptionResult(PerceptionStatus.SUCCEEDED, 3, List.of(first, second, third));
        var outcome = new PerceptionOutcome(new PerceptionRequest(3), result);

        var prefix = outcome.withAdmittedSignalPrefix(List.of(first, second));

        assertAll(
                () -> assertEquals(List.of(first, second), prefix.result().signals()),
                // the cycle budget limits what is kept; it never turns a success into a partial one
                () -> assertEquals(PerceptionStatus.SUCCEEDED, prefix.result().status()),
                () -> assertEquals(3, prefix.result().signalLimit()),
                () -> assertSame(result, result.withAdmittedSignalPrefix(List.of(first, second, third))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> result.withAdmittedSignalPrefix(List.of(second))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> result.withAdmittedSignalPrefix(List.of(first, second, third, observation(4.0)))));
    }
}
