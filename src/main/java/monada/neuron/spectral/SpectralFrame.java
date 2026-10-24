package monada.neuron.spectral;

import java.util.Objects;

/**
 * Basis and sampling metadata shared by every component of a {@link SpectralPattern}.
 *
 * <p>The frame decides which {@code (level, index)} keys exist and what a locator means. Two patterns can be
 * compared only when their frames are equal (contract section 7.2); Neuron never resamples or interpolates.
 *
 * <ul>
 *   <li>{@link SpectralBasis#DFT}: scale {@link SpectralScale#SINGLE}; bin spacing {@code sampleRate / N} at least
 *       {@link Double#MIN_NORMAL}; level {@code 0}; bins {@code 0..floor(N/2)}
 *       (single-sided); locator {@code k * sampleRate / N}. The DC bin is {@code k = 0}; the Nyquist bin exists only
 *       for even {@code N} at {@code k = N/2}. Content above the Nyquist limit aliases and is not detectable here.</li>
 *   <li>{@link SpectralBasis#HAAR}: {@code N} a power of two, depth {@code L >= 1} with {@code 2^L <= N}; level
 *       {@code 0} (approximation) has {@code N / 2^L} positions with locator {@code m * 2^L}; detail level
 *       {@code j} has {@code N / 2^j} positions with locator {@code m * 2^j}.</li>
 * </ul>
 *
 * @param basis transform family
 * @param scale decomposition depth
 * @param sampleCount number of decomposed samples {@code N}, {@code 1..SpectralPattern.MAX_SAMPLE_COUNT}
 * @param sampleRate finite positive rate; exactly {@code 1} for {@link SampleUnit#ABSTRACT_PER_INDEX}
 * @param unit declared sample unit
 */
public record SpectralFrame(SpectralBasis basis, SpectralScale scale, int sampleCount, double sampleRate,
                            SampleUnit unit) {

    /** Validates the basis, scale and sampling consistency. */
    public SpectralFrame {
        Objects.requireNonNull(basis, "basis must not be null");
        Objects.requireNonNull(scale, "scale must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (sampleCount < 1 || sampleCount > SpectralPattern.MAX_SAMPLE_COUNT) {
            throw new IllegalArgumentException("sampleCount must be in [1, " + SpectralPattern.MAX_SAMPLE_COUNT
                    + "], got: " + sampleCount);
        }
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) {
            throw new IllegalArgumentException("sampleRate must be finite and positive, got: " + sampleRate);
        }
        if (unit == SampleUnit.ABSTRACT_PER_INDEX && sampleRate != 1.0) {
            throw new IllegalArgumentException(
                    "sampleRate must be exactly 1 for ABSTRACT_PER_INDEX, got: " + sampleRate);
        }
        switch (basis) {
            case DFT -> {
                if (scale.levels() != 0) {
                    throw new IllegalArgumentException("a DFT frame has scale 0, got: " + scale.levels());
                }
                if (!Double.isFinite((sampleCount / 2) * sampleRate)) {
                    throw new IllegalArgumentException("the highest DFT locator overflows: sampleCount="
                            + sampleCount + ", sampleRate=" + sampleRate);
                }
                if (sampleRate / sampleCount < Double.MIN_NORMAL) {
                    throw new IllegalArgumentException("the DFT bin spacing sampleRate / sampleCount is subnormal: "
                            + "sampleCount=" + sampleCount + ", sampleRate=" + sampleRate);
                }
            }
            case HAAR -> {
                if (Integer.bitCount(sampleCount) != 1) {
                    throw new IllegalArgumentException(
                            "a HAAR frame needs a power-of-two sampleCount, got: " + sampleCount);
                }
                if (scale.levels() < 1 || (1 << scale.levels()) > sampleCount) {
                    throw new IllegalArgumentException("a HAAR frame needs 1 <= levels <= log2(sampleCount), got: "
                            + scale.levels() + " for sampleCount " + sampleCount);
                }
            }
        }
    }

    /** Returns the unit of every locator of this frame. */
    public LocatorUnit locatorUnit() {
        return switch (basis) {
            case DFT -> unit == SampleUnit.HERTZ ? LocatorUnit.HERTZ : LocatorUnit.CYCLES_PER_INDEX;
            case HAAR -> LocatorUnit.SAMPLE_POSITION;
        };
    }

    /** Returns the number of distinct component keys: {@code floor(N/2) + 1} for DFT, {@code N} for Haar. */
    public int componentCapacity() {
        return switch (basis) {
            case DFT -> sampleCount / 2 + 1;
            case HAAR -> sampleCount;
        };
    }

    /** Returns whether {@code index} is the DC bin or, for even {@code N}, the Nyquist bin of a DFT frame. */
    boolean isRealDftBin(int index) {
        return index == 0 || (sampleCount % 2 == 0 && index == sampleCount / 2);
    }

    /**
     * Returns whether the coefficient at a valid key is real by definition, so its phase encodes only a sign: every
     * Haar coefficient, and the DC and (even {@code N}) Nyquist bins of a DFT.
     */
    boolean isRealValued(int level, int index) {
        return basis == SpectralBasis.HAAR || isRealDftBin(index);
    }

    /**
     * Returns whether {@code (level, index)} is a valid component key of this frame.
     *
     * @param level component level
     * @param index component index
     * @return {@code true} when the key exists
     */
    public boolean containsKey(int level, int index) {
        if (level < 0 || index < 0) {
            return false;
        }
        return switch (basis) {
            case DFT -> level == 0 && index <= sampleCount / 2;
            case HAAR -> level <= scale.levels() && index < (sampleCount >> shift(level));
        };
    }

    /**
     * Returns the locator of a valid key: {@code k * sampleRate / N} for DFT, the support start position for Haar.
     *
     * @param level component level
     * @param index component index
     * @return the locator in {@link #locatorUnit()}
     * @throws IllegalArgumentException if the key is not in this frame
     */
    public double locator(int level, int index) {
        if (!containsKey(level, index)) {
            throw new IllegalArgumentException("(" + level + ", " + index + ") is not a key of " + this);
        }
        return locatorOf(level, index);
    }

    /** Returns the locator of a key already known to be valid; no key check (hot read path). */
    double locatorOf(int level, int index) {
        return switch (basis) {
            case DFT -> index * sampleRate / sampleCount;
            case HAAR -> (double) (index << shift(level));
        };
    }

    private int shift(int level) {
        return level == 0 ? scale.levels() : level;
    }
}
