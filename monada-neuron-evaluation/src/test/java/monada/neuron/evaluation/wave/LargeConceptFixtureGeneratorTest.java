package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.LargeConceptFixtureGenerator.LargeSplit;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The large-workload fixture {@code ncm-concept-fixture-large-v1}. */
class LargeConceptFixtureGeneratorTest {

    private final LargeConceptFixtureGenerator generator = new LargeConceptFixtureGenerator();

    @Test
    void theHierarchyHasTheContractsLargestShape() {
        ConceptHierarchy hierarchy = generator.hierarchy();
        assertEquals(ConceptHierarchy.MAX_CONCEPTS, hierarchy.size());
        assertEquals(ConceptHierarchy.MAX_DEPTH, hierarchy.levels());
        assertEquals(LargeConceptFixtureGenerator.ROOTS, hierarchy.rootCount());
        assertEquals(LargeConceptFixtureGenerator.LEAVES, hierarchy.leafCount());
        for (int i = 0; i < hierarchy.leafCount(); i++) {
            assertEquals(generator.leafOrdinal(i), hierarchy.leaf(i));
        }
    }

    @Test
    void theSameSeedGivesBitIdenticalWindowsAndLeafLabels() {
        LargeSplit first = generator.generate(SplitRole.TRAINING, 7L, 20.0, 2, 4);
        LargeSplit second = generator.generate(SplitRole.TRAINING, 7L, 20.0, 2, 4);
        ConceptHierarchy hierarchy = generator.hierarchy();
        for (int w = 0; w < first.waves().size(); w++) {
            assertArrayEquals(first.waves().get(w).samples(), second.waves().get(w).samples());
            assertEquals(first.labels().ordinal(w), second.labels().ordinal(w));
            assertTrue(hierarchy.isLeaf(first.labels().ordinal(w)));
            assertEquals(LargeConceptFixtureGenerator.SAMPLE_COUNT, first.waves().get(w).sampleCount());
        }
        assertFalse(Arrays.equals(first.waves().getFirst().samples(),
                generator.generate(SplitRole.TRAINING, 8L, 20.0, 1, 1).waves().getFirst().samples()));
    }

    @Test
    void cleanDigestsIdentifyTheCleanContentWhateverTheNoise() {
        LargeSplit quiet = generator.generate(SplitRole.TRAINING, 7L, 40.0, 1, 3);
        LargeSplit noisy = generator.generate(SplitRole.TRAINING, 7L, 0.0, 1, 3);
        assertEquals(3, quiet.cleanDigests().size());
        assertEquals(quiet.cleanDigests(), noisy.cleanDigests());
        assertFalse(Arrays.equals(quiet.waves().getFirst().samples(), noisy.waves().getFirst().samples()));
        assertFalse(quiet.cleanDigests().contains(generator.generate(SplitRole.TEST, 8L, 40.0, 1, 1)
                .cleanDigests().getFirst()));
    }

    @Test
    void heldOutLabelsAreGuardedUntilOpened() {
        LargeSplit test = generator.generate(SplitRole.TEST, 9L, 20.0, 1, 2);
        test.labels().ordinal(0);
        assertEquals(1, test.labels().earlyReads());
        test.labels().openForScoring();
        test.labels().ordinal(1);
        assertEquals(1, test.labels().earlyReads());
        assertEquals(0, generator.generate(SplitRole.TRAINING, 9L, 20.0, 1, 2).labels().earlyReads());
    }

    @Test
    void invalidSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(SplitRole.TEST, 1L, Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(SplitRole.TEST, 1L, 20.0, 0, 1));
    }
}
