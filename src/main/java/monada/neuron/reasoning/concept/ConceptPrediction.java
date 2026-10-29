package monada.neuron.reasoning.concept;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of one {@link NextConceptPredictor#predict}: ranked concept hypotheses, or a typed abstention (contract
 * {@code ncm-wave-cognition/1} sections 8.2 and 8.4). An abstention is an expected value, never an exception.
 */
public sealed interface ConceptPrediction
        permits ConceptPrediction.ConceptCandidates, ConceptPrediction.ConceptAbstention {

    /** Returns the work the call performed. */
    PredictionWork work();

    /** Returns the full score table when scoring ran; empty when the call stopped before scoring. */
    Optional<ConceptScores> scores();

    /**
     * Hypotheses for the next window, coarse to fine: one {@link ConceptLevel} per confident depth, from depth
     * {@code 0} down to the selected concept. Hypotheses, not truths (contract section 8.5).
     *
     * <p>When the descent stopped above a leaf the prediction is <b>coarse-only</b>: {@code refinementStop} names the
     * reason ({@link AbstentionReason#LOW_CONFIDENCE}, {@link AbstentionReason#LOW_MARGIN} or
     * {@link AbstentionReason#BUDGET_EXHAUSTED}) and {@code stoppedLevel} holds the candidates of the depth that
     * failed its thresholds, as diagnostics. A coarse-only prediction answers at the depths it reaches and is not
     * covered at deeper ones (contract section 8.2).
     *
     * @param levels confident levels, depths {@code 0..k} in order
     * @param refinementStop why the descent stopped above a leaf, or empty when it reached one
     * @param stoppedLevel candidates of the depth that failed its thresholds, or empty
     * @param scoreTable the score table of the call
     * @param work the work the call performed
     */
    record ConceptCandidates(List<ConceptLevel> levels, Optional<AbstentionReason> refinementStop,
                             Optional<ConceptLevel> stoppedLevel, ConceptScores scoreTable, PredictionWork work)
            implements ConceptPrediction {

        /** Copies the levels and validates their consistency. */
        public ConceptCandidates {
            levels = List.copyOf(Objects.requireNonNull(levels, "levels must not be null"));
            Objects.requireNonNull(refinementStop, "refinementStop must not be null");
            Objects.requireNonNull(stoppedLevel, "stoppedLevel must not be null");
            Objects.requireNonNull(scoreTable, "scoreTable must not be null");
            Objects.requireNonNull(work, "work must not be null");
            if (levels.isEmpty()) {
                throw new IllegalArgumentException("candidates need at least the depth-0 level");
            }
            for (int d = 0; d < levels.size(); d++) {
                if (levels.get(d).depth() != d) {
                    throw new IllegalArgumentException("level " + d + " has depth " + levels.get(d).depth());
                }
            }
            if (refinementStop.isPresent() && (refinementStop.get() == AbstentionReason.INCOMPATIBLE_INPUT
                    || refinementStop.get() == AbstentionReason.UNKNOWN_CONCEPT)) {
                throw new IllegalArgumentException("a descent cannot stop for " + refinementStop.get());
            }
            if (stoppedLevel.isPresent() && (refinementStop.isEmpty()
                    || stoppedLevel.get().depth() != levels.size())) {
                throw new IllegalArgumentException("a stopped level follows the last confident level and needs a "
                        + "refinement stop");
            }
        }

        /** Returns the selected concept: the top candidate of the deepest confident level. */
        public ConceptCandidate selected() {
            return levels.getLast().top();
        }

        /** Returns whether the descent stopped above a leaf. */
        public boolean coarseOnly() {
            return refinementStop.isPresent();
        }

        @Override
        public Optional<ConceptScores> scores() {
            return Optional.of(scoreTable);
        }
    }

    /**
     * A claim of insufficient evidence, with the candidates computed so far as diagnostics.
     *
     * @param reason why the prediction abstained
     * @param diagnostics levels scored before abstaining (the failing depth-0 level), possibly empty
     * @param scores the score table when scoring ran
     * @param work the work the call performed
     * @param detail bounded human-readable detail, at most {@link #MAX_DETAIL_LENGTH} characters
     */
    record ConceptAbstention(AbstentionReason reason, List<ConceptLevel> diagnostics, Optional<ConceptScores> scores,
                             PredictionWork work, String detail) implements ConceptPrediction {

        /** Largest detail length, as {@code DecompositionRejected.MAX_DETAIL_LENGTH}. */
        public static final int MAX_DETAIL_LENGTH = 256;

        /** Copies the diagnostics and validates the references. */
        public ConceptAbstention {
            Objects.requireNonNull(reason, "reason must not be null");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
            Objects.requireNonNull(scores, "scores must not be null");
            Objects.requireNonNull(work, "work must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            if (detail.length() > MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException("detail must be at most " + MAX_DETAIL_LENGTH + " characters");
            }
        }

        static ConceptAbstention early(AbstentionReason reason, String detail) {
            String bounded = detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail;
            return new ConceptAbstention(reason, List.of(), Optional.empty(), PredictionWork.NONE, bounded);
        }
    }
}
