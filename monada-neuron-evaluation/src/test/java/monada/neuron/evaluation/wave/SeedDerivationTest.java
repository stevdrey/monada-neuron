package monada.neuron.evaluation.wave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Seed derivations: registered XOR values kept, dropped bits rejected, SNR levels mixed. */
class SeedDerivationTest {

    private static final long RANDOM_SEED_MASK = (1L << 48) - 1;

    private final SeedDerivation seeds = new SeedDerivation();

    @Test
    void deriveKeepsTheRegisteredXorValues() {
        long seed = 0x1250_1001L;
        assertEquals(seed ^ 0x5041_4952L, seeds.derive(seed, 0x5041_4952L, 0));
        assertEquals(seed ^ 0x5048_4153_45L ^ 2, seeds.derive(seed, 0x5048_4153_45L, 2));
    }

    @Test
    void deriveRejectsBitsThatRandomWouldDrop() {
        assertThrows(IllegalArgumentException.class, () -> seeds.derive(1L, 1L << 48, 0));
        assertThrows(IllegalArgumentException.class,
                () -> seeds.derive(1L, 0, Double.doubleToLongBits(20.0)));
    }

    @Test
    void mixedSeedsOfWholeDecibelLevelsDifferInTheRetainedBits() {
        long salt = 0x4E4F_4953_455FL;
        long twenty = seeds.mixed(0x1250_1001L, salt, 20.0) & RANDOM_SEED_MASK;
        long ten = seeds.mixed(0x1250_1001L, salt, 10.0) & RANDOM_SEED_MASK;
        long zero = seeds.mixed(0x1250_1001L, salt, 0.0) & RANDOM_SEED_MASK;

        assertNotEquals(twenty, ten);
        assertNotEquals(twenty, zero);
        assertNotEquals(ten, zero);
    }
}
