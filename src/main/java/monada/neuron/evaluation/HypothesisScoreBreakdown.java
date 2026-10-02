package monada.neuron.evaluation;

/**
 * Inspectable, immutable score components for one evaluated hypothesis.
 *
 * @param supportMass sum of weights of supporting evidence
 * @param contradictionMass sum of weights of contradicting evidence
 * @param neutralMass sum of weights of neutral evidence (reported, never scored)
 * @param supportCount number of supporting evidence entries
 * @param contradictionCount number of contradicting evidence entries
 * @param neutralCount number of neutral evidence entries
 * @param resonanceContribution optional resonance support added to the support side; zero when absent
 * @param score final finite score in {@code [0, 1)}
 */
public record HypothesisScoreBreakdown(
        double supportMass,
        double contradictionMass,
        double neutralMass,
        int supportCount,
        int contradictionCount,
        int neutralCount,
        double resonanceContribution,
        double score) {

    /** Validates that every component is finite, non-negative, and the score is in {@code [0, 1)}. */
    public HypothesisScoreBreakdown {
        requireMass(supportMass, "supportMass");
        requireMass(contradictionMass, "contradictionMass");
        requireMass(neutralMass, "neutralMass");
        requireMass(resonanceContribution, "resonanceContribution");
        requireCount(supportCount, "supportCount");
        requireCount(contradictionCount, "contradictionCount");
        requireCount(neutralCount, "neutralCount");
        if (!(score >= 0.0 && score < 1.0)) {
            throw new IllegalArgumentException("score must be finite and in [0, 1), got: " + score);
        }
    }

    private static void requireMass(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative, got: " + value);
        }
    }

    private static void requireCount(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative, got: " + value);
        }
    }
}
