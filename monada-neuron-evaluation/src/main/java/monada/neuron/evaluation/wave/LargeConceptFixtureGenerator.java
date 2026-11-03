package monada.neuron.evaluation.wave;

import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptNode;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.WaveProvenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Seeded synthetic fixture {@code ncm-concept-fixture-large-v1} for the large workload of contract
 * {@code ncm-wave-cognition/2} section 14.1 (issue #130): 256 concepts over depth 3, windows of {@value #SAMPLE_COUNT}
 * samples. Its purpose is bounds, latency, allocation and memory behavior at the contract's largest sizes; its
 * prediction figures are informational. Changing any parameter or the draw order bumps {@link #VERSION}.
 *
 * <ul>
 *   <li>Hierarchy: {@value #ROOTS} roots, {@value #MIDS_PER_ROOT} children each, {@value #LEAVES_PER_MID} leaves
 *       each ({@code 16 + 48 + 192 = 256} concepts). Ordinals list the roots, then the depth-1 concepts, then the
 *       leaves, parents first; concept code {@code 10_000 + ordinal}.</li>
 *   <li>{@value #SAMPLE_COUNT} samples at {@value #SAMPLE_RATE} Hz ({@link SampleUnit#HERTZ}), so DFT bin {@code k}
 *       is {@code k} Hz. Leaf {@code (r, m, l)} has a dominant tone {@code A·cos(2πft + φ)} with {@code f} uniform in
 *       {@code [s, s + 6)} Hz, {@code s = 16 + 120r + 40m + 10l}, {@code A ∈ [0.5, 2)}, {@code φ ∈ [0, 2π)}, plus a
 *       depth-1 signature tone {@code 0.3·A·cos(2π(16 + 120r + 40m + 36)t + φ₂)} shared by the four leaves of one
 *       depth-1 concept. Every tone stays below 1,940 Hz, under the 2,048 Hz Nyquist limit.</li>
 *   <li>Noise as in {@link ConceptFixtureGenerator}: Gaussian of variance {@code P / 10^(SNR/10)} by Box-Muller over
 *       {@link Random#nextDouble()} with {@code StrictMath}, from a stream seeded by {@link SeedDerivation#mixed}.</li>
 *   <li>Sequences start at a uniform leaf. From leaf {@code i}: with probability 0.5 a uniform leaf of the same
 *       depth-1 concept, 0.3 the fixed successor leaf {@code (i + 7) mod 192}, 0.2 a uniform leaf.</li>
 * </ul>
 *
 * <p>Labels are returned apart from the waves, as leaf ordinals in {@link GuardedLabels}; those of a test split are
 * guarded. Each window also carries the digest of its clean samples, as in the medium fixture.
 */
final class LargeConceptFixtureGenerator {

    /** Generator version; part of every window's provenance. */
    static final String VERSION = "ncm-concept-fixture-large-v1";

    /** Samples per window ({@code SampledWave.MAX_SAMPLES}). */
    static final int SAMPLE_COUNT = 4_096;

    /** Sample rate in hertz. */
    static final double SAMPLE_RATE = 4_096.0;

    static final int ROOTS = 16;
    static final int MIDS_PER_ROOT = 3;
    static final int LEAVES_PER_MID = 4;
    static final int MIDS = ROOTS * MIDS_PER_ROOT;
    static final int LEAVES = MIDS * LEAVES_PER_MID;

    /** Proposition domain of the hierarchy. */
    static final int DOMAIN = 130;

    private static final long CODE_BASE = 10_000L;
    private static final String ENCODER_ID = "ncm-concept-fixture-large";
    private static final long NOISE_SALT = 0x4C41_5247_455FL;
    private static final double TWO_PI = 2.0 * StrictMath.PI;
    private static final double NYQUIST = SAMPLE_RATE / 2.0;

    private final SeedDerivation seeds = new SeedDerivation();

    /** Returns the 256-concept hierarchy. */
    ConceptHierarchy hierarchy() {
        var nodes = new ArrayList<ConceptNode>(ROOTS + MIDS + LEAVES);
        for (int r = 0; r < ROOTS; r++) {
            nodes.add(new ConceptNode(r, CODE_BASE + r, ConceptNode.ROOT, 0));
        }
        for (int m = 0; m < MIDS; m++) {
            int ordinal = ROOTS + m;
            nodes.add(new ConceptNode(ordinal, CODE_BASE + ordinal, m / MIDS_PER_ROOT, 1));
        }
        for (int l = 0; l < LEAVES; l++) {
            int ordinal = ROOTS + MIDS + l;
            nodes.add(new ConceptNode(ordinal, CODE_BASE + ordinal, ROOTS + l / LEAVES_PER_MID, 2));
        }
        return ConceptHierarchy.of(DOMAIN, nodes);
    }

    /** Returns the leaf ordinal of leaf position {@code i}. */
    int leafOrdinal(int i) {
        return ROOTS + MIDS + i;
    }

    /**
     * Generates one split of {@code sequences × windowsPerSequence} windows in sequence-major order.
     *
     * @param role split role; a {@link SplitRole#TEST} split has guarded labels
     * @param seed split seed
     * @param snrDb finite signal-to-noise ratio in decibels
     * @param sequences number of sequences, at least 1
     * @param windowsPerSequence windows per sequence, at least 1
     * @return the waves and their separate labels
     */
    LargeSplit generate(SplitRole role, long seed, double snrDb, int sequences, int windowsPerSequence) {
        Objects.requireNonNull(role, "role must not be null");
        if (!Double.isFinite(snrDb) || sequences < 1 || windowsPerSequence < 1) {
            throw new IllegalArgumentException("a finite SNR and positive sizes are required");
        }
        var tones = new Random(seed);
        var noise = new Random(seeds.mixed(seed, NOISE_SALT, snrDb));
        double noiseScale = StrictMath.pow(10.0, -snrDb / 10.0);
        int total = Math.multiplyExact(sequences, windowsPerSequence);
        var waves = new ArrayList<SampledWave>(total);
        var cleanDigests = new ArrayList<String>(total);
        var labels = new int[total];
        for (int sequence = 0; sequence < sequences; sequence++) {
            int leaf = tones.nextInt(LEAVES);
            for (int position = 0; position < windowsPerSequence; position++) {
                if (position > 0) {
                    leaf = nextLeaf(leaf, tones);
                }
                double[] samples = cleanWindow(leaf, tones);
                cleanDigests.add(ConceptFixtureGenerator.digest(samples));
                addNoise(samples, noiseScale, noise);
                var provenance = new WaveProvenance(ENCODER_ID, 1, String.format(Locale.ROOT,
                        "%s/%s/seed-%x/snr-%s/s%d/w%d", VERSION, role, seed, snrDb, sequence, position),
                        (long) position * SAMPLE_COUNT);
                labels[waves.size()] = leafOrdinal(leaf);
                waves.add(new SampledWave(samples, SAMPLE_RATE, SampleUnit.HERTZ, provenance));
            }
        }
        return new LargeSplit(role, seed, waves, cleanDigests, new GuardedLabels(labels, role == SplitRole.TEST));
    }

    private int nextLeaf(int current, Random tones) {
        double draw = tones.nextDouble();
        if (draw < 0.5) {
            return current / LEAVES_PER_MID * LEAVES_PER_MID + tones.nextInt(LEAVES_PER_MID);
        }
        if (draw < 0.8) {
            return (current + 7) % LEAVES;
        }
        return tones.nextInt(LEAVES);
    }

    /** Draws A, f, φ, φ₂ in that order and synthesizes the clean window of a leaf position. */
    private double[] cleanWindow(int leaf, Random tones) {
        int mid = leaf / LEAVES_PER_MID;
        int root = mid / MIDS_PER_ROOT;
        double midBase = 16.0 + 120.0 * root + 40.0 * (mid % MIDS_PER_ROOT);
        double slot = midBase + 10.0 * (leaf % LEAVES_PER_MID);
        double amplitude = uniform(tones, 0.5, 2.0);
        double frequency = uniform(tones, slot, slot + 6.0);
        double phase = uniform(tones, 0.0, TWO_PI);
        double signaturePhase = uniform(tones, 0.0, TWO_PI);
        var samples = new double[SAMPLE_COUNT];
        addTone(samples, amplitude, frequency, phase);
        addTone(samples, 0.3 * amplitude, midBase + 36.0, signaturePhase);
        return samples;
    }

    private void addTone(double[] samples, double amplitude, double frequency, double phase) {
        if (!(frequency < NYQUIST)) {
            throw new IllegalStateException("a tone of " + frequency + " Hz reaches the Nyquist limit");
        }
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
            double radius = StrictMath.sqrt(-2.0 * StrictMath.log(1.0 - noise.nextDouble()));
            double angle = TWO_PI * noise.nextDouble();
            samples[n] += sigma * radius * StrictMath.cos(angle);
            samples[n + 1] += sigma * radius * StrictMath.sin(angle);
        }
    }

    private double uniform(Random random, double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    /**
     * One generated split.
     *
     * @param role split role
     * @param seed split seed
     * @param waves the noisy windows in sequence-major order
     * @param cleanDigests per window, the SHA-256 of its clean (pre-noise) samples, for the disjointness check
     * @param labels separate leaf-ordinal ground truth, guarded for a test split
     */
    record LargeSplit(SplitRole role, long seed, List<SampledWave> waves, List<String> cleanDigests,
                      GuardedLabels labels) {

        /** Copies the waves and digests. */
        LargeSplit {
            waves = List.copyOf(waves);
            cleanDigests = List.copyOf(cleanDigests);
            if (labels.size() != waves.size() || cleanDigests.size() != waves.size()) {
                throw new IllegalArgumentException("one label and one digest per window are required");
            }
        }
    }
}
