package monada.neuron.evaluation.wave;

import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.WaveProvenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Seeded synthetic concept fixture {@code ncm-concept-fixture-v1} (contract {@code ncm-wave-cognition/1} section
 * 12.3), delivered by #125 for gate G2 and reused unchanged by #127 and #130. Its parameters are pre-registered in
 * {@code docs/benchmarks/spectral-resonance-h2.md}; changing any of them, or the draw order, bumps {@link #VERSION}.
 *
 * <ul>
 *   <li>Windows of {@value #SAMPLE_COUNT} samples at {@value #SAMPLE_RATE} Hz, {@link SampleUnit#HERTZ}.</li>
 *   <li>Dominant tone {@code A·cos(2πft + φ)}, {@code A ∈ [0.5, 2)}, {@code φ ∈ [0, 2π)}, {@code f ∈ [4, 16)} Hz
 *       for {@code LOW_*} and {@code [64, 80)} Hz for {@code HIGH_*}. Chord leaves add {@code r·A·cos(2π·1.5f·t + φ₂)},
 *       {@code r ∈ [0.4, 0.8)}, {@code φ₂ ∈ [0, 2π)}. No tone reaches the {@value #NYQUIST} Hz Nyquist limit.</li>
 *   <li>Additive Gaussian noise of variance {@code P / 10^(SNR/10)}, {@code P} the clean window's mean square, from
 *       Box-Muller over {@link Random#nextDouble()} with {@code StrictMath}. {@code java.util.Random} is specified
 *       by Java SE, so a seed reproduces on every JDK ({@code nextGaussian} changed in JDK 17 and is not used). The
 *       tone stream is independent of the SNR, so one seed gives the same clean windows at every SNR.</li>
 *   <li>Sequences start at a uniform leaf and follow the fixed first-order {@link #TRANSITIONS} table.</li>
 * </ul>
 *
 * <p>The waves carry no label: labels are returned in a separate {@link SplitLabels}, guarded for the test split.
 */
public final class ConceptFixtureGenerator {

    /** Generator version; part of every window's provenance. */
    public static final String VERSION = "ncm-concept-fixture-v1";

    /** Samples per window. */
    public static final int SAMPLE_COUNT = 256;

    /** Sample rate in hertz. */
    public static final double SAMPLE_RATE = 256.0;

    /** Nyquist limit in hertz; every tone stays strictly below it. */
    public static final double NYQUIST = SAMPLE_RATE / 2.0;

    /** Sequences per split of the medium workload. */
    public static final int SEQUENCES = 32;

    /** Windows per sequence of the medium workload. */
    public static final int WINDOWS_PER_SEQUENCE = 64;

    /** Ratio of the chord tone to the dominant tone. */
    static final double CHORD_RATIO = 1.5;

    /** First-order transition probabilities between {@link FixtureConcept#LEAVES}, row = current leaf. */
    static final double[][] TRANSITIONS = {
            {0.50, 0.20, 0.15, 0.15},
            {0.20, 0.50, 0.15, 0.15},
            {0.15, 0.15, 0.50, 0.20},
            {0.15, 0.15, 0.20, 0.50},
    };

    private static final String ENCODER_ID = "ncm-concept-fixture";
    private static final long NOISE_SALT = 0x4E4F_4953_455FL;
    private static final double TWO_PI = 2.0 * StrictMath.PI;

    private final double lowMin;
    private final double lowMax;
    private final double highMin;
    private final double highMax;

    /** Creates the generator with the registered tone ranges. */
    public ConceptFixtureGenerator() {
        this(4.0, 16.0, 64.0, 80.0);
    }

    /** Creates a generator with other dominant-tone ranges; rejects any range a tone could push past Nyquist. */
    ConceptFixtureGenerator(double lowMin, double lowMax, double highMin, double highMax) {
        requireRange(lowMin, lowMax, "low");
        requireRange(highMin, highMax, "high");
        this.lowMin = lowMin;
        this.lowMax = lowMax;
        this.highMin = highMin;
        this.highMax = highMax;
    }

    /**
     * Generates one split of the medium workload ({@value #SEQUENCES} sequences of {@value #WINDOWS_PER_SEQUENCE}
     * windows).
     */
    public ConceptSplit generate(SplitRole role, long seed, double snrDb) {
        return generate(role, seed, snrDb, SEQUENCES, WINDOWS_PER_SEQUENCE);
    }

    /**
     * Generates one split. For a fixed seed and window count, fewer sequences give a prefix of the windows of more.
     *
     * @param role split role; a {@link SplitRole#TEST} split has guarded labels
     * @param seed split seed
     * @param snrDb finite signal-to-noise ratio in decibels
     * @param sequences number of sequences, at least 1
     * @param windowsPerSequence windows per sequence, at least 1
     * @return the waves in sequence-major order and their separate labels
     */
    public ConceptSplit generate(SplitRole role, long seed, double snrDb, int sequences, int windowsPerSequence) {
        Objects.requireNonNull(role, "role must not be null");
        if (!Double.isFinite(snrDb)) {
            throw new IllegalArgumentException("snrDb must be finite, got: " + snrDb);
        }
        if (sequences < 1 || windowsPerSequence < 1) {
            throw new IllegalArgumentException("sequences and windowsPerSequence must be positive, got: "
                    + sequences + ", " + windowsPerSequence);
        }
        var tones = new Random(seed);
        var noise = new Random(seed ^ NOISE_SALT ^ Double.doubleToLongBits(snrDb));
        double noiseScale = StrictMath.pow(10.0, -snrDb / 10.0);
        int total = Math.multiplyExact(sequences, windowsPerSequence);
        var windows = new ArrayList<FixtureWindow>(total);
        var labels = new FixtureConcept[total];
        for (int sequence = 0; sequence < sequences; sequence++) {
            int leaf = tones.nextInt(FixtureConcept.LEAVES.size());
            for (int position = 0; position < windowsPerSequence; position++) {
                if (position > 0) {
                    leaf = nextLeaf(leaf, tones.nextDouble());
                }
                FixtureConcept concept = FixtureConcept.LEAVES.get(leaf);
                double[] samples = cleanWindow(concept, tones);
                addNoise(samples, noiseScale, noise);
                var provenance = new WaveProvenance(ENCODER_ID, 1, String.format(Locale.ROOT,
                        "%s/%s/seed-%x/snr-%s/s%d/w%d", VERSION, role, seed, snrDb, sequence, position),
                        (long) position * SAMPLE_COUNT);
                windows.add(new FixtureWindow(sequence, position,
                        new SampledWave(samples, SAMPLE_RATE, SampleUnit.HERTZ, provenance)));
                labels[windows.size() - 1] = concept;
            }
        }
        return new ConceptSplit(role, seed, snrDb, windows, new SplitLabels(labels, role == SplitRole.TEST));
    }

    private int nextLeaf(int current, double draw) {
        double[] row = TRANSITIONS[current];
        double cumulative = 0.0;
        for (int next = 0; next < row.length - 1; next++) {
            cumulative += row[next];
            if (draw < cumulative) {
                return next;
            }
        }
        return row.length - 1;
    }

    /** Draws the tone parameters in a fixed order (A, f, φ, then r, φ₂ for chords) and synthesizes the window. */
    private double[] cleanWindow(FixtureConcept concept, Random tones) {
        double amplitude = uniform(tones, 0.5, 2.0);
        double frequency = concept.low() ? uniform(tones, lowMin, lowMax) : uniform(tones, highMin, highMax);
        double phase = uniform(tones, 0.0, TWO_PI);
        double[] samples = new double[SAMPLE_COUNT];
        addTone(samples, amplitude, frequency, phase);
        if (concept.chord()) {
            double ratio = uniform(tones, 0.4, 0.8);
            double chordPhase = uniform(tones, 0.0, TWO_PI);
            addTone(samples, ratio * amplitude, CHORD_RATIO * frequency, chordPhase);
        }
        return samples;
    }

    private void addTone(double[] samples, double amplitude, double frequency, double phase) {
        requireBelowNyquist(frequency);
        for (int n = 0; n < samples.length; n++) {
            samples[n] += amplitude * StrictMath.cos(TWO_PI * frequency * n / SAMPLE_RATE + phase);
        }
    }

    private void addNoise(double[] samples, double noiseScale, Random noise) {
        double power = 0.0;
        for (double sample : samples) {
            power += sample * sample;
        }
        double sigma = StrictMath.sqrt(power / samples.length * noiseScale);
        for (int n = 0; n < samples.length; n += 2) {
            // Box-Muller; 1 - u keeps the logarithm's argument in (0, 1].
            double radius = StrictMath.sqrt(-2.0 * StrictMath.log(1.0 - noise.nextDouble()));
            double angle = TWO_PI * noise.nextDouble();
            samples[n] += sigma * radius * StrictMath.cos(angle);
            if (n + 1 < samples.length) {
                samples[n + 1] += sigma * radius * StrictMath.sin(angle);
            }
        }
    }

    private double uniform(Random random, double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    private void requireRange(double min, double max, String band) {
        if (!(min > 0.0 && min < max)) {
            throw new IllegalArgumentException(band + " range must satisfy 0 < min < max, got: [" + min + ", "
                    + max + ")");
        }
        requireBelowNyquist(CHORD_RATIO * max);
    }

    private void requireBelowNyquist(double frequency) {
        if (!(frequency < NYQUIST)) {
            throw new IllegalArgumentException("a tone of " + frequency + " Hz reaches the " + NYQUIST
                    + " Hz Nyquist limit and would alias");
        }
    }

    /**
     * One generated window: its position in the split's sequences and its wave. It carries no label.
     *
     * @param sequence sequence index
     * @param position window index in the sequence
     * @param wave the samples with provenance
     */
    public record FixtureWindow(int sequence, int position, SampledWave wave) {

        /** Requires the wave. */
        public FixtureWindow {
            Objects.requireNonNull(wave, "wave must not be null");
        }
    }

    /**
     * One generated split.
     *
     * @param role split role
     * @param seed split seed
     * @param snrDb signal-to-noise ratio in decibels
     * @param windows waves in sequence-major order
     * @param labels separate ground truth, guarded for the test split
     */
    public record ConceptSplit(SplitRole role, long seed, double snrDb, List<FixtureWindow> windows,
                               SplitLabels labels) {

        /** Copies the windows. */
        public ConceptSplit {
            Objects.requireNonNull(role, "role must not be null");
            windows = List.copyOf(windows);
            Objects.requireNonNull(labels, "labels must not be null");
            if (labels.size() != windows.size()) {
                throw new IllegalArgumentException("one label per window is required");
            }
        }
    }
}
