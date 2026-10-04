package monada.neuron.evolution;

import monada.neuron.action.ActionStatus;
import monada.neuron.reasoning.Proposition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, bounded feedback artifact derived from one completed cycle's action outcome.
 *
 * <p><strong>Ownership and lifetime.</strong> The artifact is plain caller-owned data. Monada Neuron
 * keeps no queue, session, or history of it: the caller decides whether to pass it to a later
 * cycle's {@link FeedbackAdaptationCognitiveStage} or to discard it. Persisting reusable experience
 * belongs to Monada Resonance Store behind an explicit adapter.
 *
 * <p>It carries only typed, finite values: the source status, counters, stable identifiers, and
 * bounded attribution. Provider payloads, exceptions, Signals of the originating cycle, and
 * reasoning object graphs are never retained.
 *
 * @param monadId identifier of the Monad whose cycle produced the feedback
 * @param originCycleOrdinal non-negative caller-assigned ordinal of the producing cycle, used to
 *     correlate derivation with consumption in a later cycle trace
 * @param sourceStatus status of the action outcome the feedback was derived from
 * @param observationCount number of observations the outcome retained after cycle admission; zero for
 *     {@code REJECTED}, {@code UNAVAILABLE}, {@code TIMED_OUT}, and {@code FAILED}, which retain none
 * @param disposition explicit direction of the feedback
 * @param entries ordered feedback per target Node; empty exactly when the disposition is {@link FeedbackDisposition#NEUTRAL}
 * @param attributions evaluation-selected hypotheses in rank order, at most {@link #MAX_ATTRIBUTIONS}:
 *     each proposition appears once and the evaluation scores never increase along the list
 */
public record OutcomeFeedback(
        UUID monadId,
        long originCycleOrdinal,
        ActionStatus sourceStatus,
        int observationCount,
        FeedbackDisposition disposition,
        List<FeedbackEntry> entries,
        List<HypothesisAttribution> attributions) {

    /** Maximum number of entries one completed cycle may produce. */
    public static final int MAX_ENTRIES = 64;

    /** Maximum number of hypothesis attributions one artifact may carry. */
    public static final int MAX_ATTRIBUTIONS = 16;

    /**
     * Validates counters, bounds, target uniqueness, attribution uniqueness and rank order, that the
     * status can have retained the observation count, and agreement between disposition and scores.
     */
    public OutcomeFeedback {
        Objects.requireNonNull(monadId, "monadId must not be null");
        Objects.requireNonNull(sourceStatus, "sourceStatus must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        if (originCycleOrdinal < 0) {
            throw new IllegalArgumentException(
                    "originCycleOrdinal must be non-negative, got: " + originCycleOrdinal);
        }
        if (observationCount < 0) {
            throw new IllegalArgumentException(
                    "observationCount must be non-negative, got: " + observationCount);
        }
        // ActionResult forbids observations for these statuses, so no valid outcome could report any.
        var observationsPossible = switch (sourceStatus) {
            case SUCCEEDED, PARTIALLY_COMPLETED -> true;
            case REJECTED, UNAVAILABLE, TIMED_OUT, FAILED -> false;
        };
        if (!observationsPossible && observationCount != 0) {
            throw new IllegalArgumentException(
                    sourceStatus + " outcomes retain no observations, got observationCount: " + observationCount);
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        attributions = List.copyOf(Objects.requireNonNull(attributions, "attributions must not be null"));
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "entries must not exceed " + MAX_ENTRIES + ", got: " + entries.size());
        }
        if (attributions.size() > MAX_ATTRIBUTIONS) {
            throw new IllegalArgumentException(
                    "attributions must not exceed " + MAX_ATTRIBUTIONS + ", got: " + attributions.size());
        }
        var propositions = new HashSet<Proposition>(attributions.size() * 2);
        for (var index = 0; index < attributions.size(); index++) {
            var attribution = attributions.get(index);
            if (!propositions.add(attribution.proposition())) {
                throw new IllegalArgumentException("duplicate attributed proposition: " + attribution.proposition());
            }
            if (index > 0 && attribution.evaluationScore() > attributions.get(index - 1).evaluationScore()) {
                throw new IllegalArgumentException(
                        "attributions must be in rank order (non-increasing evaluationScore), but "
                                + attribution.evaluationScore() + " follows "
                                + attributions.get(index - 1).evaluationScore());
            }
        }
        if (disposition != FeedbackDisposition.NEUTRAL && entries.isEmpty()) {
            throw new IllegalArgumentException(disposition + " feedback requires at least one entry");
        }
        var targets = new HashSet<UUID>(entries.size() * 2);
        for (var entry : entries) {
            if (!targets.add(entry.targetNodeId())) {
                throw new IllegalArgumentException("duplicate feedback target: " + entry.targetNodeId());
            }
            var consistent = switch (disposition) {
                case REINFORCE -> entry.score() > 0.0;
                case PENALIZE -> entry.score() < 0.0;
                case NEUTRAL -> false;
            };
            if (!consistent) {
                throw new IllegalArgumentException(
                        disposition + " feedback cannot contain an entry with score " + entry.score());
            }
        }
    }

    /** Creates explicit neutral feedback: the outcome is recorded but no reward or penalty is implied. */
    public static OutcomeFeedback neutral(
            UUID monadId,
            long originCycleOrdinal,
            ActionStatus sourceStatus,
            List<HypothesisAttribution> attributions) {
        return new OutcomeFeedback(
                monadId, originCycleOrdinal, sourceStatus, 0, FeedbackDisposition.NEUTRAL, List.of(), attributions);
    }
}
