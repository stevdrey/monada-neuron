package monada.neuron.evolution;

/**
 * Immutable configuration of {@link DeterministicOutcomeFeedbackPolicy}.
 *
 * <p>Each score is the per-target feedback derived from one action status. Statuses without a score,
 * {@code UNAVAILABLE} and {@code TIMED_OUT}, are environmental and always derive neutral feedback.
 *
 * @param successScore score for {@code SUCCEEDED}, in {@code (0.0, 1.0]}
 * @param partialScore score for {@code PARTIALLY_COMPLETED}, in {@code (0.0, 1.0]}
 * @param rejectedScore score for {@code REJECTED}, in {@code [-1.0, 0.0)}
 * @param failedScore score for {@code FAILED}, in {@code [-1.0, 0.0)}
 * @param maxEntries maximum entries per artifact, in {@code [1, OutcomeFeedback.MAX_ENTRIES]}
 * @param maxAttributions maximum attributions per artifact, in {@code [0, OutcomeFeedback.MAX_ATTRIBUTIONS]}
 */
public record OutcomeFeedbackConfig(
        double successScore,
        double partialScore,
        double rejectedScore,
        double failedScore,
        int maxEntries,
        int maxAttributions) {

    /** Conservative defaults documented in ADR 0021. */
    public static final OutcomeFeedbackConfig DEFAULT = new OutcomeFeedbackConfig(
            1.0, 0.5, -0.25, -1.0, OutcomeFeedback.MAX_ENTRIES, 4);

    /** Validates finiteness, score signs, and size bounds. */
    public OutcomeFeedbackConfig {
        requirePositive("successScore", successScore);
        requirePositive("partialScore", partialScore);
        requireNegative("rejectedScore", rejectedScore);
        requireNegative("failedScore", failedScore);
        if (maxEntries < 1 || maxEntries > OutcomeFeedback.MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "maxEntries must be within [1, " + OutcomeFeedback.MAX_ENTRIES + "], got: " + maxEntries);
        }
        if (maxAttributions < 0 || maxAttributions > OutcomeFeedback.MAX_ATTRIBUTIONS) {
            throw new IllegalArgumentException(
                    "maxAttributions must be within [0, " + OutcomeFeedback.MAX_ATTRIBUTIONS
                            + "], got: " + maxAttributions);
        }
    }

    private static void requirePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and within (0.0, 1.0], got: " + value);
        }
    }

    private static void requireNegative(String name, double value) {
        if (!Double.isFinite(value) || value >= 0.0 || value < -1.0) {
            throw new IllegalArgumentException(name + " must be finite and within [-1.0, 0.0), got: " + value);
        }
    }
}
