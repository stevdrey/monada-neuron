package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionRequest;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic action capability fixture for evaluation and benchmarks.
 *
 * <p>Emits deterministic observation signals in response to action requests without calling external tools.
 */
public final class DeterministicActionFixture implements ActionCapability {

    private final ActionStatus status;

    /** Creates an action fixture that completes with {@link ActionStatus#SUCCEEDED}. */
    public DeterministicActionFixture() {
        this(ActionStatus.SUCCEEDED);
    }

    /** Creates an action fixture with a designated status. */
    public DeterministicActionFixture(ActionStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    @Override
    public ActionResult execute(ActionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        int maxObservations = request.maxObservations();
        var inputs = request.inputSignals();

        if (status != ActionStatus.SUCCEEDED && status != ActionStatus.PARTIALLY_COMPLETED) {
            return new ActionResult(status, maxObservations, List.of());
        }

        var observations = new ArrayList<Signal>();
        for (int i = 0; i < Math.min(inputs.size(), maxObservations); i++) {
            var input = inputs.get(i);
            var observation = new Signal(
                    SignalKind.OBSERVATION,
                    new FrequencyState(
                            input.frequencyState().amplitude(),
                            input.frequencyState().frequency() + 1.0,
                            0.0));
            observations.add(observation);
        }

        return new ActionResult(status, maxObservations, List.copyOf(observations));
    }
}
