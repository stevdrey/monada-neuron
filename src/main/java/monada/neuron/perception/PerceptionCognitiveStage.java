package monada.neuron.perception;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Optional PERCEPTION-stage source that asks one {@link PerceptionCapability} for bounded observations.
 *
 * <p>It is a source stage: it needs no initial signals, and the deterministic cycle owns the rules that
 * follow from that (see {@link CognitiveStage#isSource()}). It occupies the single PERCEPTION position,
 * so it is an alternative to, not a companion of, an {@code AeonCognitiveStage} at that position.
 */
public final class PerceptionCognitiveStage implements CognitiveStage {

    private final PerceptionCapability capability;
    private final int maxSignals;

    /** Creates a perception stage with one explicit signal limit for every request. */
    public PerceptionCognitiveStage(PerceptionCapability capability, int maxSignals) {
        this.capability = Objects.requireNonNull(capability, "capability must not be null");
        if (maxSignals <= 0) {
            throw new IllegalArgumentException("maxSignals must be positive, got: " + maxSignals);
        }
        this.maxSignals = maxSignals;
    }

    /** Returns the fixed perception position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.PERCEPTION;
    }

    /** Declares this stage a source: it produces signals from the host rather than consuming any. */
    @Override
    public boolean isSource() {
        return true;
    }

    /** Executes one perception request bound to the cycle's host context, if any. */
    @Override
    public PerceptionCognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        Objects.requireNonNull(context, "context must not be null");
        var request = new PerceptionRequest(maxSignals, context.hostContext());
        var result = Objects.requireNonNull(capability.perceive(request), "perception result must not be null");
        return new PerceptionCognitiveStageResult(new PerceptionOutcome(request, result));
    }
}
