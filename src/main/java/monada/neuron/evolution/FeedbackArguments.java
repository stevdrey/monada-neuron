package monada.neuron.evolution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Argument validation shared by every {@link OutcomeFeedbackPolicy} implementation, so that swapping the
 * reference policy for the no-op control policy never hides malformed provenance or target data.
 */
final class FeedbackArguments {

    private FeedbackArguments() {
    }

    /** Requires a non-negative caller-assigned cycle ordinal. */
    static void requireOrdinal(long originCycleOrdinal) {
        if (originCycleOrdinal < 0) {
            throw new IllegalArgumentException(
                    "originCycleOrdinal must be non-negative, got: " + originCycleOrdinal);
        }
    }

    /**
     * Validates and returns the unique, non-null prefix of at most {@code limit} targets that a policy
     * considers; targets beyond the prefix are not inspected.
     */
    static List<UUID> considerTargets(List<UUID> targetNodeIds, int limit) {
        Objects.requireNonNull(targetNodeIds, "targetNodeIds must not be null");
        var count = Math.min(targetNodeIds.size(), limit);
        var considered = new ArrayList<UUID>(count);
        var seen = new HashSet<UUID>(count * 2);
        for (var index = 0; index < count; index++) {
            var target = Objects.requireNonNull(targetNodeIds.get(index), "targetNodeIds must not contain null");
            if (!seen.add(target)) {
                throw new IllegalArgumentException("duplicate feedback target: " + target);
            }
            considered.add(target);
        }
        return considered;
    }
}
