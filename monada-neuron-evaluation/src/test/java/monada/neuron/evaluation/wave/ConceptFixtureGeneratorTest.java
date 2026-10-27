package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.ConceptFixtureGenerator.ConceptSplit;
import monada.neuron.resonance.DominantComponentProjection;
import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DftWaveDecomposer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seeded concept fixture of contract section 12.3 (unit tests use seeds outside the registered protocol). */
class ConceptFixtureGeneratorTest {

    private static final long SEED = 0x7E57_0001L;

    private final ConceptFixtureGenerator generator = new ConceptFixtureGenerator();

    @Test
    void sameSeedGivesBitIdenticalWindowsAndLabels() {
        ConceptSplit first = generator.generate(SplitRole.TRAINING, SEED, 10.0, 3, 8);
        ConceptSplit second = generator.generate(SplitRole.TRAINING, SEED, 10.0, 3, 8);

        assertEquals(first.windows(), second.windows());
        for (int w = 0; w < first.windows().size(); w++) {
            assertEquals(first.labels().label(w), second.labels().label(w));
        }
        assertNotEquals(first.windows(), generator.generate(SplitRole.TRAINING, SEED + 1, 10.0, 3, 8).windows());
    }

    @Test
    void fewerSequencesAreAPrefix() {
        ConceptSplit small = generator.generate(SplitRole.TRAINING, SEED, 0.0, 2, 8);
        ConceptSplit large = generator.generate(SplitRole.TRAINING, SEED, 0.0, 5, 8);

        assertEquals(small.windows(), large.windows().subList(0, small.windows().size()));
    }

    @Test
    void cleanWindowsDoNotDependOnTheSnrAndNoiseMatchesIt() {
        ConceptSplit clean = generator.generate(SplitRole.TRAINING, SEED, 300.0, 4, 16);
        ConceptSplit noisy = generator.generate(SplitRole.TRAINING, SEED, 10.0, 4, 16);

        double signal = 0.0;
        double noise = 0.0;
        for (int w = 0; w < clean.windows().size(); w++) {
            assertEquals(clean.labels().label(w), noisy.labels().label(w));
            double[] x = clean.windows().get(w).wave().samples();
            double[] y = noisy.windows().get(w).wave().samples();
            for (int n = 0; n < x.length; n++) {
                signal += x[n] * x[n];
                noise += (y[n] - x[n]) * (y[n] - x[n]);
            }
        }
        double measuredSnr = 10.0 * StrictMath.log10(signal / noise);
        assertEquals(10.0, measuredSnr, 0.3);
    }

    @Test
    void dominantToneLiesInTheConceptBand() {
        ConceptSplit split = generator.generate(SplitRole.TRAINING, SEED, 300.0, 4, 16);
        var decomposer = new DftWaveDecomposer();
        var projection = new DominantComponentProjection();

        for (int w = 0; w < split.windows().size(); w++) {
            FixtureConcept concept = split.labels().label(w);
            double frequency = projection.project(assertInstanceOf(Decomposed.class,
                    decomposer.decompose(split.windows().get(w).wave())).pattern()).frequency();
            if (concept.low()) {
                assertTrue(frequency >= 3.0 && frequency <= 17.0, concept + " at " + frequency);
            } else {
                assertTrue(frequency >= 63.0 && frequency <= 81.0, concept + " at " + frequency);
            }
        }
    }

    @Test
    void rejectsRangesThatWouldAlias() {
        assertThrows(IllegalArgumentException.class, () -> new ConceptFixtureGenerator(4.0, 16.0, 64.0, 90.0));
        assertThrows(IllegalArgumentException.class, () -> new ConceptFixtureGenerator(16.0, 4.0, 64.0, 80.0));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(SplitRole.TEST, SEED, Double.NaN, 1, 1));
    }

    @Test
    void testLabelsAreGuardedAndWavesCarryNoLabel() {
        ConceptSplit test = generator.generate(SplitRole.TEST, SEED, 10.0, 1, 4);
        ConceptSplit training = generator.generate(SplitRole.TRAINING, SEED, 10.0, 1, 4);

        test.labels().label(0);
        training.labels().label(0);
        assertEquals(1, test.labels().earlyReads());
        assertEquals(0, training.labels().earlyReads());
        test.labels().openForScoring();
        test.labels().label(1);
        assertEquals(1, test.labels().earlyReads());

        String reference = test.windows().getFirst().wave().provenance().sourceReference();
        assertAll(Arrays.stream(FixtureConcept.values())
                .map(concept -> () -> assertFalse(reference.contains(concept.name()), reference)));
    }

    @Test
    void transitionRowsAreDistributions() {
        for (double[] row : ConceptFixtureGenerator.TRANSITIONS) {
            assertEquals(1.0, Arrays.stream(row).sum(), 1e-15);
        }
        assertArrayEquals(new double[] {0.50, 0.20, 0.15, 0.15}, ConceptFixtureGenerator.TRANSITIONS[0]);
    }
}
