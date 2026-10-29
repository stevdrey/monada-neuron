package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static monada.neuron.reasoning.concept.ConceptFixtures.FRAME;
import static monada.neuron.reasoning.concept.ConceptFixtures.HIERARCHY;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_BAND;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_CHORD;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.decompose;
import static monada.neuron.reasoning.concept.ConceptFixtures.tone;
import static monada.neuron.reasoning.concept.ConceptFixtures.window;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Snapshot content and replay determinism of {@link ConceptModelTrainer}. */
class ConceptModelTrainerTest {

    private final ConceptModelTrainer trainer = new ConceptModelTrainer();

    @Test
    void countsCentroidsAndTransitionsFromLabeledWindows() {
        LabeledSequence sequence = new LabeledSequence(List.of(
                new LabeledSequence.Window(decompose(tone(5, 1.0)), LOW_PURE),
                new LabeledSequence.Window(decompose(tone(5, 2.0)), LOW_PURE),
                new LabeledSequence.Window(decompose(tone(7, 1.0)), LOW_CHORD),
                new LabeledSequence.Window(decompose(new double[ConceptFixtures.N]), LOW_CHORD)));
        ConceptModel model = trainer.train(HIERARCHY, FRAME, List.of(sequence), 0.1);
        double[] pure = model.centroid(2);
        double[] band = model.centroid(0);
        double half = 1.0 / StrictMath.sqrt(5.0);
        assertAll(
                () -> assertEquals(2, model.exemplarCount(2)),
                () -> assertEquals(1, model.exemplarCount(3), "a silent window is no centroid exemplar"),
                () -> assertEquals(3, model.exemplarCount(0)),
                () -> assertEquals(0, model.exemplarCount(1)),
                () -> assertEquals(3, model.scorableConcepts()),
                () -> assertEquals(2, model.leafCount(3), "a silent window still counts as a labeled window"),
                () -> assertEquals(1, model.transitionCount(2, 2)),
                () -> assertEquals(1, model.transitionCount(2, 3)),
                () -> assertEquals(1, model.transitionCount(3, 3)),
                () -> assertEquals(0, model.transitionCount(3, 2)),
                () -> assertEquals(1.0, pure[5], 1e-15),
                () -> assertEquals(2.0 * half, band[5], 1e-15),
                () -> assertEquals(half, band[7], 1e-15),
                () -> assertEquals(ConceptThresholds.NONE, model.thresholds()),
                () -> assertEquals(8L * (6 * 33 + 6 + 16 + 4), model.retainedBytes()));
    }

    @Test
    void replayingTheSameSequencesGivesAnEqualModel() {
        ConceptModel first = trainer.train(HIERARCHY, FRAME, ConceptFixtures.sequences(4, 16, 7L), 0.05);
        ConceptModel second = trainer.train(HIERARCHY, FRAME, ConceptFixtures.sequences(4, 16, 7L), 0.05);
        assertAll(
                () -> assertEquals(first, second),
                () -> assertArrayEquals(first.centroid(4), second.centroid(4)));
    }

    @Test
    void rejectsLabelsAndFramesTheModelCannotUse() {
        SplittableRandom random = new SplittableRandom(1);
        SpectralFrame other = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 8.0, SampleUnit.HERTZ);
        SpectralFrame haar = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(1), 64, 64.0, SampleUnit.HERTZ);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> trainer.train(HIERARCHY, FRAME, List.of(
                        single(window(LOW_PURE, random), LOW_BAND)), 0.1), "inner label"),
                () -> assertThrows(IllegalArgumentException.class, () -> trainer.train(HIERARCHY, FRAME, List.of(
                        single(window(LOW_PURE, random), 99)), 0.1), "unknown label"),
                () -> assertThrows(IllegalArgumentException.class, () -> trainer.train(HIERARCHY, other, List.of(
                        single(window(LOW_PURE, random), LOW_PURE)), 0.1), "frame mismatch"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> trainer.train(HIERARCHY, haar, List.of(), 0.1), "non-DFT frame"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> trainer.train(HIERARCHY, FRAME, List.of(), 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> trainer.train(HIERARCHY, FRAME, List.of(), Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> new LabeledSequence(List.of())));
    }

    private static LabeledSequence single(SpectralPattern pattern, long code) {
        return new LabeledSequence(List.of(new LabeledSequence.Window(pattern, code)));
    }
}
