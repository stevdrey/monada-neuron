package monada.neuron.evolution;

import monada.neuron.monad.CognitiveCycleResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Control policy that never derives feedback, preserving the current cycle behavior for A/B evaluation.
 *
 * <p>It validates its arguments exactly like the reference policy does, so that swapping policies never
 * hides malformed input. With no configured bound it inspects the first {@link OutcomeFeedback#MAX_ENTRIES}
 * targets, the most any policy can consider.
 */
public final class NoOpOutcomeFeedbackPolicy implements OutcomeFeedbackPolicy {

    /** Shared stateless instance. */
    public static final NoOpOutcomeFeedbackPolicy INSTANCE = new NoOpOutcomeFeedbackPolicy();

    private NoOpOutcomeFeedbackPolicy() {
    }

    /** Validates the ordinal and considered targets, then returns empty. */
    @Override
    public Optional<OutcomeFeedback> derive(
            CognitiveCycleResult cycle,
            List<UUID> targetNodeIds,
            long originCycleOrdinal) {
        Objects.requireNonNull(cycle, "cycle must not be null");
        FeedbackArguments.requireOrdinal(originCycleOrdinal);
        FeedbackArguments.considerTargets(targetNodeIds, OutcomeFeedback.MAX_ENTRIES);
        return Optional.empty();
    }
}
