package monada.neuron.evolution;

import monada.neuron.monad.CognitiveCycleResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Control policy that never derives feedback, preserving the current cycle behavior for A/B evaluation. */
public final class NoOpOutcomeFeedbackPolicy implements OutcomeFeedbackPolicy {

    /** Shared stateless instance. */
    public static final NoOpOutcomeFeedbackPolicy INSTANCE = new NoOpOutcomeFeedbackPolicy();

    private NoOpOutcomeFeedbackPolicy() {
    }

    /** Validates arguments and returns empty. */
    @Override
    public Optional<OutcomeFeedback> derive(
            CognitiveCycleResult cycle,
            List<UUID> targetNodeIds,
            long originCycleOrdinal) {
        Objects.requireNonNull(cycle, "cycle must not be null");
        Objects.requireNonNull(targetNodeIds, "targetNodeIds must not be null");
        return Optional.empty();
    }
}
