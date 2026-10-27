package monada.neuron.evaluation.wave;

/**
 * The one place the H2 harness and the concept fixture derive {@link java.util.Random} seeds.
 *
 * <p>{@code java.util.Random} keeps only the low 48 bits of a seed, so two derivations that differ only above them
 * silently share a stream. {@link #derive} keeps the registered XOR derivations bit for bit and rejects a salt and
 * component that would differ only in the dropped bits; {@link #mixed} spreads every bit of a {@code double}
 * component (such as an SNR level, whose whole-decibel bit patterns differ only above bit 48) with the SplitMix64
 * finalizer.
 */
final class SeedDerivation {

    private static final int RANDOM_SEED_BITS = 48;

    /**
     * Returns {@code base ^ salt ^ component}.
     *
     * @throws IllegalArgumentException if {@code salt ^ component} has a bit {@code java.util.Random} would drop
     */
    long derive(long base, long salt, long component) {
        long offset = salt ^ component;
        if (offset >>> RANDOM_SEED_BITS != 0) {
            throw new IllegalArgumentException("salt and component must fit the low " + RANDOM_SEED_BITS
                    + " seed bits, got: 0x" + Long.toHexString(offset));
        }
        return base ^ offset;
    }

    /** Returns the SplitMix64 finalizer of {@code base ^ salt ^ bits(component)}. */
    long mixed(long base, long salt, double component) {
        long z = base ^ salt ^ Double.doubleToLongBits(component);
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return z ^ (z >>> 31);
    }
}
