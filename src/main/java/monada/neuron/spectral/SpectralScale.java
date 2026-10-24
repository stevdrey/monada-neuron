package monada.neuron.spectral;

/**
 * Decomposition depth of a {@link SpectralPattern}.
 *
 * <p>A DFT pattern has depth {@code 0} and one component level. A Haar pattern of depth {@code L} has
 * {@code L + 1} component levels: level {@code 0} for the final approximation and levels {@code 1..L} for the details.
 * A scale is a dyadic resolution, not a frequency and not a concept level.
 *
 * @param levels decomposition depth, {@code 0..MAX_HAAR_LEVELS}
 */
public record SpectralScale(int levels) {

    /** Largest Haar depth: {@code log2} of {@link SpectralPattern#MAX_SAMPLE_COUNT}. */
    public static final int MAX_HAAR_LEVELS = 12;

    /** The single-level scale of a DFT pattern. */
    public static final SpectralScale SINGLE = new SpectralScale(0);

    /** Validates the depth bound. */
    public SpectralScale {
        if (levels < 0 || levels > MAX_HAAR_LEVELS) {
            throw new IllegalArgumentException(
                    "levels must be in [0, " + MAX_HAAR_LEVELS + "], got: " + levels);
        }
    }

    /** Returns the number of component levels, {@code levels + 1}. */
    public int levelCount() {
        return levels + 1;
    }
}
