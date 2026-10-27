package monada.neuron.spectral;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Outcome of one {@link WaveSynthesizer#synthesize}: reconstructed samples with fidelity diagnostics, or a typed
 * refusal (contract {@code ncm-wave-cognition/1} section 9, ADR 0042). Both variants report the retained-energy
 * fraction, which the pattern alone determines. Reconstruction is of samples only, never of meaning.
 */
public sealed interface SynthesisResult permits SynthesisResult.Reconstructed, SynthesisResult.NotInvertible {

    /** Returns the fidelity class: {@code EXACT} or {@code LOSSY} when reconstructed, else {@code NOT_INVERTIBLE}. */
    SynthesisStatus status();

    /**
     * Returns the pattern's retained energy divided by its source energy, clamped to {@code [0, 1]} against
     * numerical noise; {@code 1} for silent samples (source energy {@code 0}) and for a complete pattern, whose
     * retained energy {@link SpectralPattern} already validated to equal the source energy within the Parseval
     * allowance. Dimensionless.
     */
    double retainedEnergyFraction();

    /**
     * Reconstructed samples, in the unit of the decomposed samples, one per index of the pattern's frame, with the
     * pattern's time origin (its first sample).
     *
     * <p>A final value class rather than a record, like {@link SpectralPattern} (ADR 0035): the public factory
     * {@link #of} copies the samples, while the synthesizer hands over the array it just built without a second copy.
     * No internal array escapes: {@link #samples()} returns a copy and {@link #sample(int)} reads without allocating.
     *
     * <p>Invariants, checked at construction:
     * <ul>
     *   <li>the status is {@code EXACT} or {@code LOSSY}; an {@code EXACT} result retains all of the source energy,
     *       so its {@link #retainedEnergyFraction()} is exactly {@code 1};</li>
     *   <li>the error diagnostics compare with the original samples and are present only when the caller supplied
     *       them, otherwise all three are empty, never estimated; they are present or absent together;</li>
     *   <li>when present, {@code rmsError <= maxAbsoluteError} (an RMS never exceeds the peak).</li>
     * </ul>
     */
    final class Reconstructed implements SynthesisResult {

        private final SynthesisStatus status;
        private final double[] samples;
        private final double retainedEnergyFraction;
        private final double reconstructedEnergy;
        private final OptionalDouble maxAbsoluteError;
        private final OptionalDouble rmsError;
        private final OptionalDouble relativeEnergyError;

        private Reconstructed(SynthesisStatus status, double[] samples, double retainedEnergyFraction,
                              double reconstructedEnergy, OptionalDouble maxAbsoluteError, OptionalDouble rmsError,
                              OptionalDouble relativeEnergyError) {
            this.status = status;
            this.samples = samples;
            this.retainedEnergyFraction = retainedEnergyFraction;
            this.reconstructedEnergy = reconstructedEnergy;
            this.maxAbsoluteError = maxAbsoluteError;
            this.rmsError = rmsError;
            this.relativeEnergyError = relativeEnergyError;
        }

        /**
         * Creates a reconstruction, copying the samples once.
         *
         * @param status {@link SynthesisStatus#EXACT} or {@link SynthesisStatus#LOSSY}
         * @param samples reconstructed samples, {@code 1..SpectralPattern.MAX_SAMPLE_COUNT} finite values; copied
         * @param retainedEnergyFraction retained energy over source energy, in {@code [0, 1]}; {@code 1} for
         *                               {@code EXACT}
         * @param reconstructedEnergy {@code Σ x̂_n²} of the reconstructed samples, in squared sample units
         * @param maxAbsoluteError {@code max |x̂_n − x_n|}, in sample units
         * @param rmsError {@code √(Σ (x̂_n − x_n)² / N)}, in sample units, at most {@code maxAbsoluteError}
         * @param relativeEnergyError {@code |Σ x̂_n² − Σ x_n²| / Σ x_n²}, dimensionless; {@code 0} when both are
         *                            {@code 0}
         * @return the reconstruction
         * @throws IllegalArgumentException if any invariant is violated
         */
        public static Reconstructed of(SynthesisStatus status, double[] samples, double retainedEnergyFraction,
                                       double reconstructedEnergy, OptionalDouble maxAbsoluteError,
                                       OptionalDouble rmsError, OptionalDouble relativeEnergyError) {
            Objects.requireNonNull(samples, "samples must not be null");
            return ofOwned(status, samples.clone(), retainedEnergyFraction, reconstructedEnergy, maxAbsoluteError,
                    rmsError, relativeEnergyError);
        }

        /**
         * Creates a reconstruction that takes ownership of a freshly allocated array, for the synthesizer in this
         * package. The caller must not keep or mutate the array; signed zeros are canonicalized in place and every
         * rule of {@link #of} is validated.
         */
        static Reconstructed ofOwned(SynthesisStatus status, double[] samples, double retainedEnergyFraction,
                                     double reconstructedEnergy, OptionalDouble maxAbsoluteError,
                                     OptionalDouble rmsError, OptionalDouble relativeEnergyError) {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(samples, "samples must not be null");
            Objects.requireNonNull(maxAbsoluteError, "maxAbsoluteError must not be null");
            Objects.requireNonNull(rmsError, "rmsError must not be null");
            Objects.requireNonNull(relativeEnergyError, "relativeEnergyError must not be null");
            if (status == SynthesisStatus.NOT_INVERTIBLE) {
                throw new IllegalArgumentException("a reconstruction is EXACT or LOSSY, got: " + status);
            }
            if (samples.length < 1 || samples.length > SpectralPattern.MAX_SAMPLE_COUNT) {
                throw new IllegalArgumentException("samples length must be in [1, "
                        + SpectralPattern.MAX_SAMPLE_COUNT + "], got: " + samples.length);
            }
            requireFraction(retainedEnergyFraction);
            if (status == SynthesisStatus.EXACT && retainedEnergyFraction != 1.0) {
                throw new IllegalArgumentException(
                        "an EXACT reconstruction retains all source energy, got fraction: " + retainedEnergyFraction);
            }
            requireNonNegative(reconstructedEnergy, "reconstructedEnergy");
            if (maxAbsoluteError.isPresent() != rmsError.isPresent()
                    || maxAbsoluteError.isPresent() != relativeEnergyError.isPresent()) {
                throw new IllegalArgumentException("error diagnostics must be all present or all absent");
            }
            if (maxAbsoluteError.isPresent()) {
                double max = maxAbsoluteError.getAsDouble();
                double rms = rmsError.getAsDouble();
                requireNonNegative(max, "maxAbsoluteError");
                requireNonNegative(rms, "rmsError");
                requireNonNegative(relativeEnergyError.getAsDouble(), "relativeEnergyError");
                if (rms > max) {
                    throw new IllegalArgumentException(
                            "rmsError " + rms + " must not exceed maxAbsoluteError " + max);
                }
            }
            for (int i = 0; i < samples.length; i++) {
                double sample = samples[i];
                if (!Double.isFinite(sample)) {
                    throw new IllegalArgumentException("sample at index " + i + " must be finite, got: " + sample);
                }
                samples[i] = SpectralArithmetic.canonicalZero(sample);
            }
            return new Reconstructed(status, samples, retainedEnergyFraction, reconstructedEnergy, maxAbsoluteError,
                    rmsError, relativeEnergyError);
        }

        @Override
        public SynthesisStatus status() {
            return status;
        }

        /** Returns a copy of the reconstructed samples. */
        public double[] samples() {
            return samples.clone();
        }

        /** Returns the number of reconstructed samples {@code N}. */
        public int sampleCount() {
            return samples.length;
        }

        /** Returns reconstructed sample {@code i} without allocating. */
        public double sample(int i) {
            return samples[Objects.checkIndex(i, samples.length)];
        }

        @Override
        public double retainedEnergyFraction() {
            return retainedEnergyFraction;
        }

        /** Returns {@code Σ x̂_n²} of the reconstructed samples, in squared sample units. */
        public double reconstructedEnergy() {
            return reconstructedEnergy;
        }

        /** Returns {@code max |x̂_n − x_n|} in sample units, or empty when no original was supplied. */
        public OptionalDouble maxAbsoluteError() {
            return maxAbsoluteError;
        }

        /** Returns {@code √(Σ (x̂_n − x_n)² / N)} in sample units, or empty when no original was supplied. */
        public OptionalDouble rmsError() {
            return rmsError;
        }

        /** Returns {@code |Σ x̂_n² − Σ x_n²| / Σ x_n²}, dimensionless, or empty when no original was supplied. */
        public OptionalDouble relativeEnergyError() {
            return relativeEnergyError;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Reconstructed that
                    && status == that.status
                    && Arrays.equals(samples, that.samples)
                    && Double.compare(retainedEnergyFraction, that.retainedEnergyFraction) == 0
                    && Double.compare(reconstructedEnergy, that.reconstructedEnergy) == 0
                    && maxAbsoluteError.equals(that.maxAbsoluteError)
                    && rmsError.equals(that.rmsError)
                    && relativeEnergyError.equals(that.relativeEnergyError);
        }

        @Override
        public int hashCode() {
            int result = status.hashCode();
            result = 31 * result + Arrays.hashCode(samples);
            result = 31 * result + Double.hashCode(retainedEnergyFraction);
            result = 31 * result + Double.hashCode(reconstructedEnergy);
            result = 31 * result + maxAbsoluteError.hashCode();
            result = 31 * result + rmsError.hashCode();
            return 31 * result + relativeEnergyError.hashCode();
        }

        @Override
        public String toString() {
            return "Reconstructed[status=" + status + ", sampleCount=" + samples.length + ", retainedEnergyFraction="
                    + retainedEnergyFraction + ", reconstructedEnergy=" + reconstructedEnergy + ", maxAbsoluteError="
                    + maxAbsoluteError + ", rmsError=" + rmsError + ", relativeEnergyError=" + relativeEnergyError
                    + "]";
        }
    }

    /**
     * The pattern is valid but lacks information the reference inverse needs; no samples are produced.
     *
     * @param reason typed reason
     * @param retainedEnergyFraction retained energy over source energy, in {@code [0, 1]}
     * @param detail short diagnostic, at most {@link #MAX_DETAIL_LENGTH} characters; never sample data
     */
    record NotInvertible(NotInvertibleReason reason, double retainedEnergyFraction, String detail)
            implements SynthesisResult {

        /** Largest diagnostic length in UTF-16 chars. */
        public static final int MAX_DETAIL_LENGTH = 256;

        /** Requires a reason, a fraction in range and a bounded, non-blank detail. */
        public NotInvertible {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            requireFraction(retainedEnergyFraction);
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            if (detail.length() > MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException(
                        "detail must be at most " + MAX_DETAIL_LENGTH + " characters, got: " + detail.length());
            }
        }

        @Override
        public SynthesisStatus status() {
            return SynthesisStatus.NOT_INVERTIBLE;
        }
    }

    private static void requireFraction(double fraction) {
        if (!(fraction >= 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("retainedEnergyFraction must be in [0, 1], got: " + fraction);
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative, got: " + value);
        }
    }
}
