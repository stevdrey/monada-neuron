package monada.neuron.action;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Optional ACTION-stage adapter that requests one ordered, bounded external capability. */
public final class ActionCognitiveStage implements CognitiveStage {

    private final ActionCapability capability;
    private final int maxObservations;

    /** Creates an action stage with one explicit observation limit for every request. */
    public ActionCognitiveStage(ActionCapability capability, int maxObservations) {
        this.capability = Objects.requireNonNull(capability, "capability must not be null");
        if (maxObservations <= 0) {
            throw new IllegalArgumentException("maxObservations must be positive, got: " + maxObservations);
        }
        this.maxObservations = maxObservations;
    }

    /** Returns the fixed action position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ACTION;
    }

    /** Executes one action request from the cycle-admitted Signal batch. */
    @Override
    public ActionCognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        Objects.requireNonNull(context, "context must not be null");
        var request = new ActionRequest(inputSignals, maxObservations, context.hostContext());
        var result = Objects.requireNonNull(capability.execute(request), "action result must not be null");
        return new ActionCognitiveStageResult(new ActionOutcome(request, result));
    }
}
