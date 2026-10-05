package monada.neuron.host;

import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.evolution.OutcomeFeedback;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CycleInputTest {

    private static final Signal SIGNAL = new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 10.0, 0.0));

    @Test
    void ofCarriesOnlyTheSignals() {
        var input = CycleInput.of(List.of(SIGNAL));

        assertAll(
                () -> assertEquals(List.of(SIGNAL), input.signals()),
                () -> assertEquals(Optional.empty(), input.budget()),
                () -> assertEquals(Optional.empty(), input.priorFeedback()));
    }

    @Test
    void withersReturnNewInputsAndKeepTheOtherValues() {
        var budget = new CognitiveBudget(1, 2, 3);
        var feedback = OutcomeFeedback.neutral(new UUID(0L, 1L), 0L, ActionStatus.TIMED_OUT, List.of());

        var input = CycleInput.of(List.of(SIGNAL)).withBudget(budget).withPriorFeedback(feedback);

        assertAll(
                () -> assertEquals(List.of(SIGNAL), input.signals()),
                () -> assertEquals(Optional.of(budget), input.budget()),
                () -> assertEquals(Optional.of(feedback), input.priorFeedback()),
                () -> assertEquals(Optional.empty(), CycleInput.of(List.of(SIGNAL)).budget()));
    }

    @Test
    void snapshotsTheSignalsAndRejectsNulls() {
        var mutable = new ArrayList<>(List.of(SIGNAL));
        var input = CycleInput.of(mutable);
        mutable.clear();

        assertAll(
                () -> assertEquals(List.of(SIGNAL), input.signals()),
                () -> assertThrows(NullPointerException.class, () -> CycleInput.of(null)),
                () -> assertThrows(NullPointerException.class, () -> CycleInput.of(List.of(SIGNAL)).withBudget(null)),
                () -> assertThrows(NullPointerException.class, () -> CycleInput.of(List.of(SIGNAL)).withPriorFeedback(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new CycleInput(List.of(SIGNAL), null, Optional.empty())),
                () -> assertThrows(NullPointerException.class,
                        () -> new CycleInput(List.of(SIGNAL), Optional.empty(), null)));
    }
}
