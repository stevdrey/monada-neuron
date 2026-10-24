package monada.neuron.spectral;

/**
 * Analytic model of the retained heap footprint of a {@link SpectralPattern}.
 *
 * <p>Assumes a 64-bit HotSpot JVM with compressed references: 12-byte object header, 4-byte references, 16-byte
 * array header, 8-byte alignment. The frame and provenance are caller values shared by reference and excluded. It
 * is an estimate, not a measurement.
 */
final class SpectralPatternFootprintModel {

    private static final int HEADER = 12;
    private static final int REF = 4;
    private static final int ARRAY_HEADER = 16;

    /** Returns the modeled retained bytes of a pattern with {@code k} components. */
    long retainedBytes(int k) {
        return align(HEADER + 6 * REF + 3 * 8)                    // pattern: 6 refs, 3 doubles
                + 2 * align(ARRAY_HEADER + 4L * k)                 // levels, indices
                + 2 * align(ARRAY_HEADER + 8L * k);                // magnitudes, phases
    }

    private static long align(long bytes) {
        return (bytes + 7) & ~7L;
    }
}
