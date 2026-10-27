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
     * numerical noise; {@code 1} for silent samples (source energy {@code 0}). Dimensionless.
     */
    double retainedEnergyFraction();

    /**
     * Reconstructed samples, in the unit of the decomposed samples, one per index of the pattern's frame, with the
     * pattern's time origin (its first sample).
     *
     * <p>The error diagnostics compare with the original samples and are present only when the caller supplied them;
     * otherwise all three are empty, never estimated. They are present or absent together.
     *
     * @param status {@link SynthesisStatus#EXACT} or {@link SynthesisStatus#LOSSY}
     * @param samples reconstructed samples, finite; copied
     * @param retainedEnergyFraction retained energy over source energy, in {@code [0, 1]}
     * @param reconstructedEnergy {@code Σ x̂_n²} of the reconstructed samples, in squared sample units
     * @param maxAbsoluteError {@code max |x̂_n − x_n|}, in sample units
     * @param rmsError {@code √(Σ (x̂_n − x_n)² / N)}, in sample units
     * @param relativeEnergyError {@code |Σ x̂_n² − Σ x_n²| / Σ x_n²}, dimensionless; {@code 0} when both are {@code 0}
     */
    record Reconstructed(SynthesisStatus status, double[] samples, double retainedEnergyFraction,
                         double reconstructedEnergy, OptionalDouble maxAbsoluteError, OptionalDouble rmsError,
                         OptionalDouble relativeEnergyError) implements SynthesisResult {

        /** Validates the status, the value ranges and the error presence rule, and copies the samples. */
        public Reconstructed {
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
            requireNonNegative(reconstructedEnergy, "reconstructedEnergy");
            if (maxAbsoluteError.isPresent() != rmsError.isPresent()
                    || maxAbsoluteError.isPresent() != relativeEnergyError.isPresent()) {
                throw new IllegalArgumentException("error diagnostics must be all present or all absent");
            }
            if (maxAbsoluteError.isPresent()) {
                requireNonNegative(maxAbsoluteError.getAsDouble(), "maxAbsoluteError");
                requireNonNegative(rmsError.getAsDouble(), "rmsError");
                requireNonNegative(relativeEnergyError.getAsDouble(), "relativeEnergyError");
            }
            var copy = new double[samples.length];
            for (int i = 0; i < samples.length; i++) {
                double sample = samples[i];
                if (!Double.isFinite(sample)) {
                    throw new IllegalArgumentException("sample at index " + i + " must be finite, got: " + sample);
                }
                copy[i] = SpectralArithmetic.canonicalZero(sample);
            }
            samples = copy;
        }

        /** Returns a copy of the reconstructed samples. */
        @Override
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
