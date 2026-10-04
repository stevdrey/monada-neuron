package monada.neuron.evolution;

import monada.neuron.action.ActionCognitiveStageResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.evaluation.EvaluationCognitiveStageResult;
import monada.neuron.monad.CognitiveCycleResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reference outcome-to-feedback derivation (ADR 0021).
 *
 * <p>Status semantics, with scores from {@link OutcomeFeedbackConfig}:
 * <ul>
 *   <li>{@code SUCCEEDED}, {@code PARTIALLY_COMPLETED}: {@link FeedbackDisposition#REINFORCE};</li>
 *   <li>{@code FAILED}, {@code REJECTED}: {@link FeedbackDisposition#PENALIZE};</li>
 *   <li>{@code UNAVAILABLE}, {@code TIMED_OUT}: {@link FeedbackDisposition#NEUTRAL}, because the cause
 *       is environmental and no reward or penalty is fabricated.</li>
 * </ul>
 * Every considered target receives the same status score, in the order supplied. Only the first
 * {@link OutcomeFeedbackConfig#maxEntries()} targets are considered. With no considered target the
 * feedback is neutral. Scalar scores carry no target Signal, so the baseline adaptation policy scales
 * amplitude and energy.
 *
 * <p>Hypotheses selected by an {@code EVALUATION} result are attributed by {@code Proposition} and
 * score in rank order, up to {@link OutcomeFeedbackConfig#maxAttributions()}.
 */
public final class DeterministicOutcomeFeedbackPolicy implements OutcomeFeedbackPolicy {

    private final OutcomeFeedbackConfig config;

    /** Creates the policy with explicit configuration. */
    public DeterministicOutcomeFeedbackPolicy(OutcomeFeedbackConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Creates the policy with {@link OutcomeFeedbackConfig#DEFAULT}. */
    public DeterministicOutcomeFeedbackPolicy() {
        this(OutcomeFeedbackConfig.DEFAULT);
    }

    /** Returns this policy's configuration. */
    public OutcomeFeedbackConfig config() {
        return config;
    }

    @Override
    public Optional<OutcomeFeedback> derive(
            CognitiveCycleResult cycle,
            List<UUID> targetNodeIds,
            long originCycleOrdinal) {
        Objects.requireNonNull(cycle, "cycle must not be null");
        FeedbackArguments.requireOrdinal(originCycleOrdinal);
        var targets = FeedbackArguments.considerTargets(targetNodeIds, config.maxEntries());

        ActionCognitiveStageResult action = null;
        EvaluationCognitiveStageResult evaluation = null;
        for (var stageResult : cycle.stageResults()) {
            if (stageResult instanceof ActionCognitiveStageResult found) {
                action = found;
            } else if (stageResult instanceof EvaluationCognitiveStageResult found) {
                evaluation = found;
            }
        }
        if (action == null) {
            return Optional.empty();
        }

        var result = action.outcome().result();
        var attributions = attributions(evaluation);
        var disposition = disposition(result.status());
        if (disposition == FeedbackDisposition.NEUTRAL || targets.isEmpty()) {
            return Optional.of(new OutcomeFeedback(
                    cycle.monadId(),
                    originCycleOrdinal,
                    result.status(),
                    result.observations().size(),
                    FeedbackDisposition.NEUTRAL,
                    List.of(),
                    attributions));
        }

        var score = score(result.status());
        var entries = new ArrayList<FeedbackEntry>(targets.size());
        for (var target : targets) {
            entries.add(FeedbackEntry.of(target, score));
        }
        return Optional.of(new OutcomeFeedback(
                cycle.monadId(),
                originCycleOrdinal,
                result.status(),
                result.observations().size(),
                disposition,
                entries,
                attributions));
    }

    private List<HypothesisAttribution> attributions(EvaluationCognitiveStageResult evaluation) {
        if (evaluation == null || config.maxAttributions() == 0) {
            return List.of();
        }
        var result = evaluation.evaluation();
        var limit = Math.min(result.selected().size(), config.maxAttributions());
        var attributions = new ArrayList<HypothesisAttribution>(limit);
        for (var index = 0; index < limit; index++) {
            var selected = result.selected().get(index);
            attributions.add(new HypothesisAttribution(
                    result.evaluated().get(selected.sequence()).proposition(),
                    selected.breakdown().score()));
        }
        return attributions;
    }

    private static FeedbackDisposition disposition(ActionStatus status) {
        return switch (status) {
            case SUCCEEDED, PARTIALLY_COMPLETED -> FeedbackDisposition.REINFORCE;
            case FAILED, REJECTED -> FeedbackDisposition.PENALIZE;
            case UNAVAILABLE, TIMED_OUT -> FeedbackDisposition.NEUTRAL;
        };
    }

    private double score(ActionStatus status) {
        return switch (status) {
            case SUCCEEDED -> config.successScore();
            case PARTIALLY_COMPLETED -> config.partialScore();
            case REJECTED -> config.rejectedScore();
            case FAILED -> config.failedScore();
            case UNAVAILABLE, TIMED_OUT -> throw new IllegalStateException("neutral status has no score: " + status);
        };
    }
}
