package monada.neuron.resonance;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one {@link SpectralResonanceDynamics#compare}: the comparison, or a typed incompatibility. Incompatible
 * patterns are never interpolated, padded or resampled to make them comparable.
 */
public sealed interface ComparisonResult
        permits ComparisonResult.SpectralComparison, ComparisonResult.Incompatible {

    /**
     * Measured relation between two compatible patterns (contract {@code ncm-wave-cognition/1} section 7.3).
     *
     * <p>The bands, the norms and the counts are enough to recompute both summary values whenever
     * {@code firstNorm · secondNorm} is a normal double. The summary values themselves are computed with power-of-two
     * scaling and stay meaningful below that limit, where the unscaled norms and interference terms underflow:
     *
     * <pre>{@code
     * aggregate = (1 + Σ interference / (firstNorm · secondNorm)) / 2      0 when either norm is 0
     * coherence = |Σ w · e^{i·phaseDifference}| / Σ w,  w = m1 · m2,
     *             over the bands whose phasesDefined is true                0 when Σ w = 0
     * }</pre>
     *
     * <p>The aggregate is a similarity in {@code [0, 1]}, not an identity: {@code 1} for identical patterns,
     * {@code 0.5} when no retained energy is shared in phase or anti-phase, {@code 0} for exact opposition. Coherence
     * measures only how consistent the phase differences are; equal frequencies are not implied.
     *
     * @param bands contributions of the shared bands, in strictly ascending {@code (level, index)} order
     * @param firstNorm Euclidean norm of every magnitude the first pattern retains
     * @param secondNorm Euclidean norm of every magnitude the second pattern retains
     * @param firstOnlyCount number of components only the first pattern retains
     * @param secondOnlyCount number of components only the second pattern retains
     * @param aggregate normalized real cross-correlation mapped to {@code [0, 1]}
     * @param coherence amplitude-weighted phase-difference consistency in {@code [0, 1]}
     */
    record SpectralComparison(List<BandContribution> bands, double firstNorm, double secondNorm,
                              int firstOnlyCount, int secondOnlyCount, double aggregate, double coherence)
            implements ComparisonResult {

        /** Copies the bands and validates their order, the counts and the value ranges. */
        public SpectralComparison {
            bands = List.copyOf(Objects.requireNonNull(bands, "bands must not be null"));
            for (int i = 1; i < bands.size(); i++) {
                BandContribution previous = bands.get(i - 1);
                BandContribution current = bands.get(i);
                if (BandKeys.compare(previous.level(), previous.index(), current.level(), current.index()) >= 0) {
                    throw new IllegalArgumentException("bands must be in strictly ascending (level, index) order "
                            + "at position " + i);
                }
            }
            requireNorm(firstNorm, "firstNorm");
            requireNorm(secondNorm, "secondNorm");
            if (firstOnlyCount < 0 || secondOnlyCount < 0) {
                throw new IllegalArgumentException("component counts must be non-negative, got: " + firstOnlyCount
                        + ", " + secondOnlyCount);
            }
            requireUnit(aggregate, "aggregate");
            requireUnit(coherence, "coherence");
        }

        private static void requireNorm(double norm, String name) {
            if (!Double.isFinite(norm) || norm < 0.0) {
                throw new IllegalArgumentException(name + " must be finite and non-negative, got: " + norm);
            }
        }

        private static void requireUnit(double value, String name) {
            if (!(value >= 0.0 && value <= 1.0)) {
                throw new IllegalArgumentException(name + " must be in [0, 1], got: " + value);
            }
        }
    }

    /**
     * The patterns differ in a field of contract section 7.2 and cannot be compared.
     *
     * @param reason the first differing field, in {@link IncompatibilityReason} declaration order
     * @param detail short diagnostic, at most {@link #MAX_DETAIL_LENGTH} characters; never component data
     */
    record Incompatible(IncompatibilityReason reason, String detail) implements ComparisonResult {

        /** Largest diagnostic length in UTF-16 chars. */
        public static final int MAX_DETAIL_LENGTH = 256;

        /** Requires a reason and a bounded, non-blank detail. */
        public Incompatible {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            if (detail.length() > MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException(
                        "detail must be at most " + MAX_DETAIL_LENGTH + " characters, got: " + detail.length());
            }
        }
    }
}
