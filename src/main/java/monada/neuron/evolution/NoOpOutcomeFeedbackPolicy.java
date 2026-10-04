package monada.neuron.evolution;

import monada.neuron.monad.CognitiveCycleResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Control policy that never derives feedback, preserving the current cycle behavior for A/B evaluation.
 *
 * <p>It validates its arguments exactly like {@link DeterministicOutcomeFeedbackPolicy}, including the
 * same configured target bound ({@link OutcomeFeedbackConfig#maxEntries()}), so swapping the control for
 * the reference policy changes only whether feedback is derived, never whether a workload runs. Build
 * the control with the same {@link OutcomeFeedbackConfig} as the reference policy; {@link #INSTANCE}
 * uses {@link OutcomeFeedbackConfig#DEFAULT} and matches the reference policy only in that default case.
 */
public final class NoOpOutcomeFeedbackPolicy implements OutcomeFeedbackPolicy {

    /** Shared stateless control for the default configuration. */
    public static final NoOpOutcomeFeedbackPolicy INSTANCE =
            new NoOpOutcomeFeedbackPolicy(OutcomeFeedbackConfig.DEFAULT);

    private final OutcomeFeedbackConfig config;

    /** Creates a control that validates targets with the bound of {@code config}. */
    public NoOpOutcomeFeedbackPolicy(OutcomeFeedbackConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Returns the configuration whose target bound this control validates against. */
    public OutcomeFeedbackConfig config() {
        return config;
    }

    /** Validates the ordinal and considered targets, then returns empty. */
    @Override
    public Optional<OutcomeFeedback> derive(
            CognitiveCycleResult cycle,
            List<UUID> targetNodeIds,
            long originCycleOrdinal) {
        Objects.requireNonNull(cycle, "cycle must not be null");
        FeedbackArguments.requireOrdinal(originCycleOrdinal);
        FeedbackArguments.considerTargets(targetNodeIds, config.maxEntries());
        return Optional.empty();
    }
}
