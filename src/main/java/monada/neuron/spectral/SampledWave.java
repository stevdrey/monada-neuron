package monada.neuron.spectral;

import java.util.Arrays;
import java.util.Objects;

/**
 * One window of explicitly supplied, uniformly spaced numeric samples (contract {@code ncm-wave-cognition/1} section
 * 4.1, ADR 0036): the only input a {@link WaveDecomposer} accepts.
 *
 * <p>The samples mean only what the host encoder that built them declares (see {@link WaveEncoder}); Neuron never
 * derives samples from a {@link monada.neuron.signal.Signal}. Spacing is uniform by construction: there are no
 * timestamps, and non-uniform data must be resampled by the host, which records that in the provenance.
 *
 * <p><b>Validation.</b> A structurally invalid wave is a caller bug and throws {@link IllegalArgumentException} or
 * {@link NullPointerException}: an empty or oversized array (the length is checked before any copy), a NaN or infinite
 * sample, a sample rate that is not finite and positive, an {@link SampleUnit#ABSTRACT_PER_INDEX} rate other than
 * exactly {@code 1}, or a {@code windowOffset + sampleCount} that overflows a {@code long}. Nothing is repaired except
 * signed zeros, which are canonicalized to {@code +0.0} so equal waves are equal values. Limits that depend on a
 * transform are reported by the decomposer as a typed {@link DecompositionResult.DecompositionRejected}.
 *
 * <p>The array is copied once at construction and never escapes: {@link #samples()} returns a copy, and
 * {@link #sample(int)} reads without allocating.
 *
 * @param samples the samples, {@code 1..MAX_SAMPLES} finite values
 * @param sampleRate samples per second for {@link SampleUnit#HERTZ}; exactly {@code 1} for
 *                   {@link SampleUnit#ABSTRACT_PER_INDEX}
 * @param unit declared sample unit; there is no default
 * @param provenance host provenance of the window
 */
public record SampledWave(double[] samples, double sampleRate, SampleUnit unit, WaveProvenance provenance) {

    /** Largest number of samples per window: {@link SpectralPattern#MAX_SAMPLE_COUNT}. */
    public static final int MAX_SAMPLES = SpectralPattern.MAX_SAMPLE_COUNT;

    /** Validates the input schema of contract section 4.1 and copies the samples. */
    public SampledWave {
        Objects.requireNonNull(samples, "samples must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        if (samples.length < 1 || samples.length > MAX_SAMPLES) {
            throw new IllegalArgumentException(
                    "samples length must be in [1, " + MAX_SAMPLES + "], got: " + samples.length);
        }
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) {
            throw new IllegalArgumentException("sampleRate must be finite and positive, got: " + sampleRate);
        }
        if (unit == SampleUnit.ABSTRACT_PER_INDEX && sampleRate != 1.0) {
            throw new IllegalArgumentException(
                    "sampleRate must be exactly 1 for ABSTRACT_PER_INDEX, got: " + sampleRate);
        }
        if (provenance.windowOffset() > Long.MAX_VALUE - samples.length) {
            throw new IllegalArgumentException("windowOffset + sampleCount overflows: windowOffset="
                    + provenance.windowOffset() + ", sampleCount=" + samples.length);
        }
        var copy = new double[samples.length];
        for (int i = 0; i < samples.length; i++) {
            double sample = samples[i];
            if (!Double.isFinite(sample)) {
                throw new IllegalArgumentException("sample at index " + i + " must be finite, got: " + sample);
            }
            copy[i] = sample == 0.0 ? 0.0 : sample;
        }
        samples = copy;
    }

    /** Returns a copy of the samples. */
    @Override
    public double[] samples() {
        return samples.clone();
    }

    /** Returns the number of samples {@code N}. */
    public int sampleCount() {
        return samples.length;
    }

    /** Returns sample {@code i} without allocating. */
    public double sample(int i) {
        return samples[Objects.checkIndex(i, samples.length)];
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SampledWave that
                && Arrays.equals(samples, that.samples)
                && Double.compare(sampleRate, that.sampleRate) == 0
                && unit == that.unit
                && provenance.equals(that.provenance);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(samples);
        result = 31 * result + Double.hashCode(sampleRate);
        result = 31 * result + unit.hashCode();
        return 31 * result + provenance.hashCode();
    }

    @Override
    public String toString() {
        return "SampledWave[sampleCount=" + samples.length + ", sampleRate=" + sampleRate + ", unit=" + unit
                + ", provenance=" + provenance + "]";
    }
}
