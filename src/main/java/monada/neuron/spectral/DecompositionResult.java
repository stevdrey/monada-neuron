package monada.neuron.spectral;

import java.util.Objects;

/**
 * Outcome of one {@link WaveDecomposer#decompose(SampledWave)}: the pattern, or an expected, typed rejection. A
 * rejected wave is never repaired, padded or resampled.
 */
public sealed interface DecompositionResult
        permits DecompositionResult.Decomposed, DecompositionResult.DecompositionRejected {

    /**
     * The decomposed pattern. It carries the frame (basis, scale, sample count, sample rate, unit) and the wave's
     * provenance unchanged.
     *
     * @param pattern the spectral pattern
     */
    record Decomposed(SpectralPattern pattern) implements DecompositionResult {

        /** Requires the pattern. */
        public Decomposed {
            Objects.requireNonNull(pattern, "pattern must not be null");
        }
    }

    /**
     * The wave is structurally valid but outside a limit of the transform.
     *
     * @param reason typed reason
     * @param detail short diagnostic, at most {@link #MAX_DETAIL_LENGTH} characters; never sample data
     */
    record DecompositionRejected(DecompositionRejectionReason reason, String detail) implements DecompositionResult {

        /** Largest diagnostic length in UTF-16 chars. */
        public static final int MAX_DETAIL_LENGTH = 256;

        /** Requires a reason and a bounded, non-blank detail. */
        public DecompositionRejected {
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
