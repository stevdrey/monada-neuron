package monada.neuron.routing;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * {@code REASONING} result that retains the full {@link RoutingDecision} together with the hypothesis hand-off.
 *
 * <p>Route identity never enters a {@code Signal}: the output signals are always empty. {@code Selected} yields one
 * hypothesis; {@code Abstain} and {@code NoEligibleRoute} yield none. The host reads the authoritative decision from
 * the cycle's stage results.
 *
 * @param decision the retained decision
 * @param hypotheses hypotheses handed to the following stage
 */
public record RoutingCognitiveStageResult(RoutingDecision decision, HypothesisSet hypotheses)
        implements CognitiveStageResult {

    /** Requires one hypothesis exactly for a selected decision. */
    public RoutingCognitiveStageResult {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(hypotheses, "hypotheses must not be null");
        int expected = decision instanceof RoutingDecision.Selected ? 1 : 0;
        if (hypotheses.size() != expected) {
            throw new IllegalArgumentException("a " + decision.getClass().getSimpleName() + " decision needs "
                    + expected + " hypotheses, got: " + hypotheses.size());
        }
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.REASONING;
    }

    @Override
    public CognitiveStageStatus status() {
        return CognitiveStageStatus.COMPLETED;
    }

    @Override
    public List<Signal> outputSignals() {
        return List.of();
    }

    /** Keeps the decision and hypotheses; the admitted prefix of the empty output can only be empty. */
    @Override
    public CognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        Objects.requireNonNull(admittedOutputSignals, "admittedOutputSignals must not be null");
        if (!admittedOutputSignals.isEmpty()) {
            throw new IllegalArgumentException("a routing result offers no output signals to admit");
        }
        return this;
    }

    @Override
    public boolean retainsTypedHandOff() {
        return !hypotheses.isEmpty();
    }

    @Override
    public void validateProvenance(CognitiveContext context) {
        Objects.requireNonNull(context, "context must not be null");
        hypotheses.validateSignalProvenance(context.acceptedSignals());
    }
}
