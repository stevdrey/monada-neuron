package monada.neuron.reasoning;

/**
 * Explicit capacity limits for the hypotheses retained at the reasoning boundary of one cycle.
 *
 * @param maxCandidates maximum distinct hypotheses retained in one {@link HypothesisSet}
 * @param maxEvidencePerCandidate maximum evidence entries retained per hypothesis
 */
public record HypothesisLimits(int maxCandidates, int maxEvidencePerCandidate) {

    /** Conservative default bounds for reference reasoning stages. */
    public static final HypothesisLimits DEFAULT = new HypothesisLimits(32, 16);

    /** Validates both capacities. */
    public HypothesisLimits {
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException(
                    "maxCandidates must be positive, got: " + maxCandidates);
        }
        if (maxEvidencePerCandidate <= 0) {
            throw new IllegalArgumentException(
                    "maxEvidencePerCandidate must be positive, got: " + maxEvidencePerCandidate);
        }
    }
}
