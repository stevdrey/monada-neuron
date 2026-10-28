package monada.neuron.reasoning.concept;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import monada.neuron.spectral.WaveProvenance;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Small copy of the contract section 12.3 concept fixture for unit tests: {@code N = 64} at 64 Hz, {@code LOW_*}
 * dominant tone in 4–8 Hz, {@code HIGH_*} in 16–20 Hz, chords add a tone at 3/2 of the dominant frequency. The
 * evaluation module owns the full generator.
 */
final class ConceptFixtures {

    static final int DOMAIN = 127;
    static final int N = 64;
    static final SpectralFrame FRAME = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, N, N,
            SampleUnit.HERTZ);
    static final WaveProvenance PROVENANCE = new WaveProvenance("encoder.concept-fixture", 1, "window", 0L);

    static final long LOW_BAND = 1;
    static final long HIGH_BAND = 2;
    static final long LOW_PURE = 11;
    static final long LOW_CHORD = 12;
    static final long HIGH_PURE = 21;
    static final long HIGH_CHORD = 22;
    static final long[] LEAVES = {LOW_PURE, LOW_CHORD, HIGH_PURE, HIGH_CHORD};

    /** Ordinals 0..5: LOW_BAND, HIGH_BAND, LOW_PURE, LOW_CHORD, HIGH_PURE, HIGH_CHORD. */
    static final ConceptHierarchy HIERARCHY = ConceptHierarchy.of(DOMAIN, List.of(
            new ConceptNode(0, LOW_BAND, ConceptNode.ROOT, 0),
            new ConceptNode(1, HIGH_BAND, ConceptNode.ROOT, 0),
            new ConceptNode(2, LOW_PURE, 0, 1),
            new ConceptNode(3, LOW_CHORD, 0, 1),
            new ConceptNode(4, HIGH_PURE, 1, 1),
            new ConceptNode(5, HIGH_CHORD, 1, 1)));

    /** Section 12.3 transition table, rows and columns in {@link #LEAVES} order. */
    private static final double[][] TRANSITIONS = {
            {0.50, 0.20, 0.15, 0.15},
            {0.20, 0.50, 0.15, 0.15},
            {0.15, 0.15, 0.50, 0.20},
            {0.15, 0.15, 0.20, 0.50}};

    private static final DftWaveDecomposer DECOMPOSER = new DftWaveDecomposer();

    private ConceptFixtures() {
    }

    /** A clean window of a leaf, with seeded amplitude, frequency and phases. */
    static SpectralPattern window(long leaf, SplittableRandom random) {
        return decompose(leafSamples(leaf, random));
    }

    /** The samples of a clean leaf window, drawing from the random source exactly as {@link #window}. */
    static double[] leafSamples(long leaf, SplittableRandom random) {
        boolean low = leaf == LOW_PURE || leaf == LOW_CHORD;
        boolean chord = leaf == LOW_CHORD || leaf == HIGH_CHORD;
        double amplitude = 0.5 + 1.5 * random.nextDouble();
        double frequency = (low ? 4.0 : 16.0) + 4.0 * random.nextDouble();
        double phase = 2.0 * StrictMath.PI * random.nextDouble();
        double ratio = 0.4 + 0.4 * random.nextDouble();
        double phase2 = 2.0 * StrictMath.PI * random.nextDouble();
        double[] samples = new double[N];
        for (int n = 0; n < N; n++) {
            double t = n / (double) N;
            samples[n] = amplitude * StrictMath.cos(2.0 * StrictMath.PI * frequency * t + phase);
            if (chord) {
                samples[n] += ratio * amplitude * StrictMath.cos(2.0 * StrictMath.PI * 1.5 * frequency * t + phase2);
            }
        }
        return samples;
    }

    /** Decomposes samples of the fixture frame. */
    static SpectralPattern decompose(double[] samples) {
        return ((Decomposed) DECOMPOSER.decompose(new SampledWave(samples, N, SampleUnit.HERTZ, PROVENANCE)))
                .pattern();
    }

    /** A pure tone at an integer bin with the given amplitude. */
    static double[] tone(int bin, double amplitude) {
        double[] samples = new double[N];
        for (int n = 0; n < N; n++) {
            samples[n] = amplitude * StrictMath.cos(2.0 * StrictMath.PI * bin * n / N);
        }
        return samples;
    }

    /** Adds two sample arrays. */
    static double[] plus(double[] first, double[] second) {
        double[] sum = new double[N];
        for (int n = 0; n < N; n++) {
            sum[n] = first[n] + second[n];
        }
        return sum;
    }

    /** Seeded labeled sequences that follow the section 12.3 transition table. */
    static List<LabeledSequence> sequences(int count, int length, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<LabeledSequence> sequences = new ArrayList<>(count);
        for (int s = 0; s < count; s++) {
            List<LabeledSequence.Window> windows = new ArrayList<>(length);
            int leaf = random.nextInt(LEAVES.length);
            for (int w = 0; w < length; w++) {
                windows.add(new LabeledSequence.Window(window(LEAVES[leaf], random), LEAVES[leaf]));
                leaf = nextLeaf(leaf, random.nextDouble());
            }
            sequences.add(new LabeledSequence(windows));
        }
        return sequences;
    }

    private static int nextLeaf(int leaf, double draw) {
        double cumulative = 0.0;
        for (int next = 0; next < LEAVES.length - 1; next++) {
            cumulative += TRANSITIONS[leaf][next];
            if (draw < cumulative) {
                return next;
            }
        }
        return LEAVES.length - 1;
    }

    /** A model trained on the default seeded sequences. */
    static ConceptModel trainedModel() {
        return new ConceptModelTrainer().train(HIERARCHY, FRAME, sequences(8, 32, 0x127L), 0.05);
    }
}
