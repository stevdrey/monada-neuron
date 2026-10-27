package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.wave.ConceptFixtureGenerator.ConceptSplit;
import monada.neuron.evaluation.wave.RankStatistics.Interval;
import monada.neuron.resonance.BandContribution;
import monada.neuron.resonance.ComparisonResult;
import monada.neuron.resonance.ComparisonResult.Incompatible;
import monada.neuron.resonance.ComparisonResult.SpectralComparison;
import monada.neuron.resonance.DominantComponentProjection;
import monada.neuron.resonance.IncompatibilityReason;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.spectral.DecompositionResult;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralPhase;
import monada.neuron.spectral.SpectralScale;
import monada.neuron.spectral.WaveProvenance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Hypothesis H2 of contract {@code ncm-wave-cognition/1} (gate G2): does the {@link SpectralResonanceDynamics}
 * aggregate separate same-concept from different-concept window pairs better than {@link ScalarResonanceMetric} on the
 * {@link DominantComponentProjection}, by the pre-registered margin?
 *
 * <p>The protocol is fixed in {@code docs/benchmarks/spectral-resonance-h2.md} and mirrored by {@link #REGISTERED}.
 * For each repeat, the held-out windows are paired disjointly with a seeded shuffle; every arm scores every pair from
 * the waves alone; only then does the scorer open the labels. The gate verdict is reported as data: a no-go is a
 * valid outcome, not a failed check. The checks cover what must hold for the verdict to be trusted: the contract
 * fixtures, split disjointness, label leakage and determinism.
 */
final class SpectralResonanceH2Evaluation {

    /** Contract identifier recorded in every report. */
    static final String CONTRACT = "ncm-wave-cognition/1";

    /** The pre-registered protocol. */
    static final Protocol REGISTERED = new Protocol(0x1250_0001L, 0x1250_0002L,
            List.of(0x1250_1001L, 0x1250_1002L, 0x1250_1003L, 0x1250_1004L, 0x1250_1005L),
            0x1250_B007L, 2_000, List.of(20.0, 10.0, 0.0),
            ConceptFixtureGenerator.SEQUENCES, ConceptFixtureGenerator.WINDOWS_PER_SEQUENCE, 0.10);

    /** Bins a frequency-shifted pattern is moved up by. */
    static final int FREQUENCY_SHIFT = 32;

    private static final long PAIRING_SALT = 0x5041_4952L;
    private static final long PHASE_SALT = 0x5048_4153_45L;
    private static final long PERMUTATION_SALT = 0x5045_524DL;
    private static final double FIXTURE_TOLERANCE = 1e-12;
    private static final WaveProvenance FIXTURE_PROVENANCE = new WaveProvenance("encoder.fixture", 1, "window-0",
            0L);
    private static final List<H2Arm> ARMS = List.of(H2Arm.values());

    private final Protocol protocol;
    private final boolean quick;
    private final ConceptFixtureGenerator generator = new ConceptFixtureGenerator();
    private final DftWaveDecomposer decomposer = new DftWaveDecomposer();
    private final SpectralResonanceDynamics dynamics = new SpectralResonanceDynamics();
    private final DominantComponentProjection projection = new DominantComponentProjection();
    private final ScalarResonanceMetric scalar = new ScalarResonanceMetric();
    private final RankStatistics statistics = new RankStatistics();
    private final SeedDerivation seeds = new SeedDerivation();

    SpectralResonanceH2Evaluation(Protocol protocol, boolean quick) {
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.quick = quick;
    }

    /** Returns the run configuration recorded in the report. */
    RunConfiguration runConfiguration() {
        return new RunConfiguration(protocol.testSeeds().getFirst(), quick, 0, 1,
                "one deterministic pass; no timing gate");
    }

    /** Runs every repeat, the checks and the gate. */
    Outcome run() {
        var checks = new ArrayList<EvaluationCheck>();
        checks.add(fixtureCheck());

        var repeats = new ArrayList<RepeatResult>();
        var labelSets = new ArrayList<SplitLabels>();
        for (int r = 0; r < protocol.testSeeds().size(); r++) {
            repeats.add(repeat(r, labelSets));
        }
        checks.add(disjointnessCheck(repeats));
        checks.add(counterfactualCheck(repeats.getFirst(), labelSets));
        checks.add(determinismCheck(repeats.getFirst()));
        int earlyReads = labelSets.stream().mapToInt(SplitLabels::earlyReads).sum();
        checks.add(new Check("leakage.test-labels", earlyReads == 0,
                labelSets.size() + " guarded held-out label sets; " + earlyReads
                        + " reads before the scorer opened them"));

        boolean go = repeats.stream().allMatch(repeat -> repeat.difference().lower() > protocol.margin());
        return new Outcome(checks, repeats, go, metadata(repeats, go));
    }

    /** Scores, labels and summarizes one repeat; adds its guarded label sets to {@code labelSets}. */
    private RepeatResult repeat(int r, List<SplitLabels> labelSets) {
        long seed = protocol.testSeeds().get(r);
        var splits = new ArrayList<ConceptSplit>();
        for (double snr : protocol.snrLevels()) {
            splits.add(generator.generate(SplitRole.TEST, seed, snr, protocol.sequences(),
                    protocol.windowsPerSequence()));
        }
        int[] pairing = pairing(seed, splits.getFirst().windows().size());
        int pairsPerLevel = pairing.length / 2;
        int levels = splits.size();

        // Every arm scores every pair before any label is read.
        var scores = new double[ARMS.size()][levels * pairsPerLevel];
        var patterns = new ArrayList<SpectralPattern[]>();
        for (int s = 0; s < levels; s++) {
            patterns.add(patterns(splits.get(s)));
            double[][] levelScores = score(splits.get(s), patterns.getLast(), pairing, phaseSeed(seed, s));
            for (int arm = 0; arm < ARMS.size(); arm++) {
                System.arraycopy(levelScores[arm], 0, scores[arm], s * pairsPerLevel, pairsPerLevel);
            }
        }

        var positive = new boolean[levels * pairsPerLevel];
        for (int s = 0; s < levels; s++) {
            SplitLabels labels = splits.get(s).labels();
            labelSets.add(labels);
            labels.openForScoring();
            for (int i = 0; i < pairsPerLevel; i++) {
                positive[s * pairsPerLevel + i] = labels.label(pairing[2 * i]) == labels.label(pairing[2 * i + 1]);
            }
        }
        // Only the first repeat's patterns are kept: the counterfactual check re-scores it without decomposing again.
        return summarize(r, seed, splits, r == 0 ? patterns : List.of(), pairing, scores, positive);
    }

    private RepeatResult summarize(int r, long seed, List<ConceptSplit> splits, List<SpectralPattern[]> patterns,
                                   int[] pairing, double[][] scores, boolean[] positive) {
        int pairsPerLevel = pairing.length / 2;
        int levels = splits.size();
        long bootstrapSeed = protocol.bootstrapSeed() + r + 1;
        double[][] pooled = statistics.bootstrap(scores, positive, pooledClusters(pairsPerLevel, levels),
                protocol.resamples(), bootstrapSeed);

        var arms = new ArrayList<ArmResult>();
        for (H2Arm arm : ARMS) {
            var perLevel = new double[levels];
            for (int s = 0; s < levels; s++) {
                perLevel[s] = statistics.auc(scores[arm.ordinal()], positive, levelPairs(s, pairsPerLevel));
            }
            arms.add(new ArmResult(arm, statistics.auc(scores[arm.ordinal()], positive),
                    statistics.interval(pooled[arm.ordinal()]), perLevel));
        }
        Interval difference = statistics.interval(statistics.difference(pooled[H2Arm.AGGREGATE.ordinal()],
                pooled[H2Arm.SCALAR.ordinal()]));

        var levelDifferences = new ArrayList<Interval>();
        double[][] gateScores = {scores[H2Arm.AGGREGATE.ordinal()], scores[H2Arm.SCALAR.ordinal()]};
        for (int s = 0; s < levels; s++) {
            var clusters = new int[pairsPerLevel][1];
            for (int i = 0; i < pairsPerLevel; i++) {
                clusters[i][0] = s * pairsPerLevel + i;
            }
            double[][] level = statistics.bootstrap(gateScores, positive, clusters, protocol.resamples(),
                    bootstrapSeed);
            levelDifferences.add(statistics.interval(statistics.difference(level[0], level[1])));
        }

        int positives = 0;
        for (int i = 0; i < pairsPerLevel; i++) {
            positives += positive[i] ? 1 : 0;
        }
        return new RepeatResult(r + 1, seed, splits, patterns, pairing, scores, positive, pooled, arms, difference,
                levelDifferences, positives, pairsPerLevel);
    }

    /**
     * A pair slot is one cluster: it contributes its pair at every SNR level, whose clean windows are shared. Pair
     * {@code i} of level {@code s} is at {@code s · pairsPerLevel + i}.
     */
    private int[][] pooledClusters(int pairsPerLevel, int levels) {
        var clusters = new int[pairsPerLevel][levels];
        for (int i = 0; i < pairsPerLevel; i++) {
            for (int s = 0; s < levels; s++) {
                clusters[i][s] = s * pairsPerLevel + i;
            }
        }
        return clusters;
    }

    /** Decomposes every window of one split, in window order. */
    private SpectralPattern[] patterns(ConceptSplit split) {
        var patterns = new SpectralPattern[split.windows().size()];
        for (int w = 0; w < patterns.length; w++) {
            patterns[w] = decompose(split.windows().get(w).wave());
        }
        return patterns;
    }

    /**
     * Scores every pair of one split on every arm from its decomposed {@code patterns}. The split, labels included,
     * is passed so that a label read by an arm would be counted by its guard.
     */
    private double[][] score(ConceptSplit split, SpectralPattern[] patterns, int[] pairing, long phaseSeed) {
        if (patterns.length != split.windows().size()) {
            throw new IllegalArgumentException("one pattern per window is required");
        }
        var randomized = new SpectralPattern[patterns.length];
        var phases = new Random(phaseSeed);
        for (int w = 0; w < patterns.length; w++) {
            randomized[w] = withRandomPhases(patterns[w], phases);
        }
        int pairs = pairing.length / 2;
        var scores = new double[ARMS.size()][pairs];
        for (int i = 0; i < pairs; i++) {
            SpectralPattern first = patterns[pairing[2 * i]];
            SpectralPattern second = patterns[pairing[2 * i + 1]];
            SpectralComparison comparison = compared(first, second);
            scores[H2Arm.AGGREGATE.ordinal()][i] = comparison.aggregate();
            scores[H2Arm.SCALAR.ordinal()][i] = scalar.score(projection.project(first), projection.project(second));
            scores[H2Arm.COHERENCE.ordinal()][i] = comparison.coherence();
            scores[H2Arm.MAGNITUDE.ordinal()][i] = magnitudeCosine(comparison);
            scores[H2Arm.AGGREGATE_PHASE_RANDOMIZED.ordinal()][i] =
                    compared(randomized[pairing[2 * i]], randomized[pairing[2 * i + 1]]).aggregate();
            scores[H2Arm.AGGREGATE_FREQUENCY_SHIFTED.ordinal()][i] =
                    compared(first, frequencyShifted(second)).aggregate();
        }
        return scores;
    }

    private SpectralPattern decompose(SampledWave wave) {
        return switch (decomposer.decompose(wave)) {
            case DecompositionResult.Decomposed decomposed -> decomposed.pattern();
            case DecompositionResult.DecompositionRejected rejected ->
                    throw new IllegalStateException("a fixture window was rejected: " + rejected);
        };
    }

    private SpectralComparison compared(SpectralPattern first, SpectralPattern second) {
        ComparisonResult result = dynamics.compare(first, second);
        if (result instanceof SpectralComparison comparison) {
            return comparison;
        }
        throw new IllegalStateException("fixture patterns share a frame but compared as " + result);
    }

    private double magnitudeCosine(SpectralComparison comparison) {
        double norms = comparison.firstNorm() * comparison.secondNorm();
        if (norms == 0.0) {
            return 0.0;
        }
        double sum = 0.0;
        for (BandContribution band : comparison.bands()) {
            sum += band.firstMagnitude() * band.secondMagnitude();
        }
        return Math.min(1.0, sum / norms);
    }

    /** Replaces every complex-bin phase; the real DC and Nyquist bins keep phase 0 or π. */
    private SpectralPattern withRandomPhases(SpectralPattern pattern, Random random) {
        double[] phases = pattern.phases();
        for (int i = 0; i < phases.length; i++) {
            if (complexBin(pattern, i)) {
                phases[i] = SpectralPhase.wrap(2.0 * StrictMath.PI * random.nextDouble());
            }
        }
        return SpectralPattern.of(pattern.frame(), pattern.sourceEnergy(), pattern.provenance(), pattern.levels(),
                pattern.indices(), pattern.magnitudes(), phases);
    }

    /** Moves every complex bin {@code k} to {@code k + FREQUENCY_SHIFT}, dropping bins that leave the complex range. */
    private SpectralPattern frequencyShifted(SpectralPattern pattern) {
        int lastComplex = (pattern.sampleCount() - 1) / 2;
        var indices = new int[pattern.componentCount()];
        var magnitudes = new double[indices.length];
        var phases = new double[indices.length];
        int count = 0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            int shifted = pattern.index(i) + FREQUENCY_SHIFT;
            if (complexBin(pattern, i) && shifted <= lastComplex) {
                indices[count] = shifted;
                magnitudes[count] = pattern.magnitude(i);
                phases[count] = pattern.phase(i);
                count++;
            }
        }
        return SpectralPattern.of(pattern.frame(), pattern.sourceEnergy(), pattern.provenance(), new int[count],
                Arrays.copyOf(indices, count), Arrays.copyOf(magnitudes, count), Arrays.copyOf(phases, count));
    }

    private boolean complexBin(SpectralPattern pattern, int i) {
        int k = pattern.index(i);
        return k > 0 && 2 * k != pattern.sampleCount();
    }

    /** Seeded disjoint pairing: a Fisher-Yates shuffle of the window indices, paired consecutively. */
    private int[] pairing(long seed, int windows) {
        var order = new int[windows - windows % 2];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        var random = new Random(seeds.derive(seed, PAIRING_SALT, 0));
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        return order;
    }

    private long phaseSeed(long seed, int level) {
        return seeds.derive(seed, PHASE_SALT, level);
    }

    private int[] levelPairs(int level, int pairsPerLevel) {
        var pairs = new int[pairsPerLevel];
        for (int i = 0; i < pairsPerLevel; i++) {
            pairs[i] = level * pairsPerLevel + i;
        }
        return pairs;
    }

    /**
     * Re-scores the first repeat with every label permuted. The scores must be bit-identical (the arms cannot have
     * read a label) and at least one AUC must change (the labels decide the scorer, so the check is not vacuous).
     */
    private Check counterfactualCheck(RepeatResult first, List<SplitLabels> labelSets) {
        int pairsPerLevel = first.pairsPerLevel();
        boolean scoresIdentical = true;
        var permutedPositive = new boolean[first.positive().length];
        for (int s = 0; s < first.splits().size(); s++) {
            ConceptSplit split = first.splits().get(s);
            SplitLabels permuted = split.labels().permuted(new Random(seeds.derive(first.seed(), PERMUTATION_SALT,
                    s)));
            var counterfactual = new ConceptSplit(split.role(), split.seed(), split.snrDb(), split.windows(),
                    permuted);
            labelSets.add(permuted);
            double[][] scores = score(counterfactual, first.patterns().get(s), first.pairing(),
                    phaseSeed(first.seed(), s));
            for (int arm = 0; arm < ARMS.size(); arm++) {
                scoresIdentical &= Arrays.equals(scores[arm],
                        Arrays.copyOfRange(first.scores()[arm], s * pairsPerLevel, (s + 1) * pairsPerLevel));
            }
            permuted.openForScoring();
            for (int i = 0; i < pairsPerLevel; i++) {
                permutedPositive[s * pairsPerLevel + i] = permuted.label(first.pairing()[2 * i])
                        == permuted.label(first.pairing()[2 * i + 1]);
            }
        }
        boolean aucChanged = false;
        for (H2Arm arm : ARMS) {
            aucChanged |= statistics.auc(first.scores()[arm.ordinal()], permutedPositive)
                    != first.arm(arm).auc();
        }
        return new Check("leakage.counterfactual", scoresIdentical && aucChanged,
                "repeat 1 re-scored with permuted held-out labels: scores " + (scoresIdentical ? "bit-identical"
                        : "CHANGED") + "; AUC " + (aucChanged ? "changed" : "unchanged (vacuous)"));
    }

    /** Re-runs the first repeat's pooled bootstrap and requires bit-identical resample values. */
    private Check determinismCheck(RepeatResult first) {
        double[][] again = statistics.bootstrap(first.scores(), first.positive(),
                pooledClusters(first.pairsPerLevel(), first.splits().size()), protocol.resamples(),
                protocol.bootstrapSeed() + 1);
        boolean identical = Arrays.deepEquals(again, first.pooled());
        return new Check("determinism", identical, "repeat 1 bootstrap re-run with the same seed is "
                + (identical ? "bit-identical" : "DIFFERENT") + "; re-scoring is covered by leakage.counterfactual");
    }

    /**
     * Checks that the training, threshold-selection and test splits use distinct seeds and share no clean window. The
     * test splits are the repeats' own (their first SNR level; clean windows do not depend on the SNR).
     */
    private Check disjointnessCheck(List<RepeatResult> repeats) {
        var seeds = new ArrayList<Long>();
        seeds.add(protocol.trainingSeed());
        seeds.add(protocol.thresholdSeed());
        double snr = protocol.snrLevels().getFirst();
        var splits = new ArrayList<ConceptSplit>();
        splits.add(generator.generate(SplitRole.TRAINING, protocol.trainingSeed(), snr, protocol.sequences(),
                protocol.windowsPerSequence()));
        splits.add(generator.generate(SplitRole.THRESHOLD_SELECTION, protocol.thresholdSeed(), snr,
                protocol.sequences(), protocol.windowsPerSequence()));
        for (RepeatResult repeat : repeats) {
            seeds.add(repeat.seed());
            splits.add(repeat.splits().getFirst());
        }
        return disjointnessCheck(seeds, splits);
    }

    /**
     * Checks that {@code seeds} are distinct and that no clean window digest of one split appears in another; equal
     * clean content is caught whatever noise each split adds.
     */
    Check disjointnessCheck(List<Long> seeds, List<ConceptSplit> splits) {
        boolean distinctSeeds = seeds.stream().distinct().count() == seeds.size();
        var owners = new HashMap<String, Integer>();
        int shared = 0;
        int windows = 0;
        for (int i = 0; i < splits.size(); i++) {
            for (var window : splits.get(i).windows()) {
                windows++;
                Integer previous = owners.putIfAbsent(window.cleanDigest(), i);
                if (previous != null && previous != i) {
                    shared++;
                }
            }
        }
        return new Check("splits.disjoint", distinctSeeds && shared == 0,
                splits.size() + " splits (training, threshold selection, " + (splits.size() - 2)
                        + " test repeats) with " + (distinctSeeds ? "distinct" : "REPEATED") + " seeds; "
                        + windows + " clean windows, " + shared + " shared across splits");
    }

    /** Recomputes the contract fixtures owned by #125 that gate G2 names (P10, P11, P12, N6), section 12.4. */
    private Check fixtureCheck() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 8.0, SampleUnit.HERTZ);
        var p4 = SpectralPattern.of(frame, 5.0, FIXTURE_PROVENANCE, new int[5], new int[] {0, 1, 2, 3, 4},
                new double[] {0.0, 1.0, 0.0, 0.5, 0.0}, new double[] {0.0, 0.0, 0.0, StrictMath.PI / 4, 0.0});
        SpectralComparison p10 = compared(p4, p4);
        SpectralComparison p11 = compared(p2(frame, 0.0), p2(frame, StrictMath.PI));
        SpectralComparison p12 = compared(p2(frame, 0.0), p2(frame, StrictMath.PI / 2));
        var sixteenHertz = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 16.0, SampleUnit.HERTZ);
        var haar = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(3), 8, 8.0, SampleUnit.HERTZ);
        ComparisonResult rate = dynamics.compare(p2(frame, 0.0), p2(sixteenHertz, 0.0));
        ComparisonResult basis = dynamics.compare(p2(frame, 0.0), SpectralPattern.of(haar, 1.0,
                FIXTURE_PROVENANCE, new int[0], new int[0], new double[0], new double[0]));

        boolean pass = near(p10.aggregate(), 1.0) && near(p10.coherence(), 1.0)
                && p10.bands().stream().allMatch(b -> near(b.interference(), b.firstMagnitude() * b.firstMagnitude()))
                && near(p11.bands().get(1).interference(), -1.0) && near(p11.aggregate(), 0.0)
                && near(p12.bands().get(1).interference(), 0.0) && near(p12.aggregate(), 0.5)
                && near(p12.coherence(), 1.0)
                && rate instanceof Incompatible(IncompatibilityReason reasonA, String ignoredA)
                && reasonA == IncompatibilityReason.SAMPLE_RATE
                && basis instanceof Incompatible(IncompatibilityReason reasonB, String ignoredB)
                && reasonB == IncompatibilityReason.BASIS;
        return new Check("fixtures.p10-p12-n6", pass, String.format(Locale.ROOT,
                "P10 aggregate %.15f coherence %.15f; P11 interference %.15f aggregate %.15f; P12 interference %.3e "
                        + "aggregate %.15f coherence %.15f; N6 %s and %s; tolerance %.0e",
                p10.aggregate(), p10.coherence(), p11.bands().get(1).interference(), p11.aggregate(),
                p12.bands().get(1).interference(), p12.aggregate(), p12.coherence(), reasonOf(rate),
                reasonOf(basis), FIXTURE_TOLERANCE));
    }

    private SpectralPattern p2(SpectralFrame frame, double phase) {
        return SpectralPattern.of(frame, 4.0, FIXTURE_PROVENANCE, new int[5], new int[] {0, 1, 2, 3, 4},
                new double[] {0.0, 1.0, 0.0, 0.0, 0.0}, new double[] {0.0, phase, 0.0, 0.0, 0.0});
    }

    private boolean near(double actual, double expected) {
        return Math.abs(actual - expected) <= FIXTURE_TOLERANCE;
    }

    private String reasonOf(ComparisonResult result) {
        return result instanceof Incompatible incompatible ? incompatible.reason().name() : "COMPARED";
    }

    private Map<String, String> metadata(List<RepeatResult> repeats, boolean go) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("contract", CONTRACT);
        metadata.put("generator.version", ConceptFixtureGenerator.VERSION);
        metadata.put("protocol.margin", format(protocol.margin()));
        metadata.put("protocol.gateRule", "go iff every repeat's 95% paired-bootstrap lower bound of "
                + "AUC(aggregate) - AUC(scalar), pooled over SNR levels, exceeds the margin");
        metadata.put("protocol.bootstrapResamples", Integer.toString(protocol.resamples()));
        metadata.put("protocol.bootstrapSeed", hex(protocol.bootstrapSeed()) + " + repeat");
        metadata.put("protocol.snrLevelsDb", protocol.snrLevels().toString());
        metadata.put("protocol.trainingSeed", hex(protocol.trainingSeed()));
        metadata.put("protocol.thresholdSeed", hex(protocol.thresholdSeed()));
        metadata.put("protocol.testSeeds", protocol.testSeeds().stream().map(SpectralResonanceH2Evaluation::hex)
                .toList().toString());
        metadata.put("protocol.windows", protocol.sequences() + " sequences x " + protocol.windowsPerSequence()
                + " windows");
        metadata.put("protocol.registered", Boolean.toString(protocol.equals(REGISTERED)));
        for (RepeatResult repeat : repeats) {
            String prefix = "h2.repeat" + repeat.number() + ".";
            metadata.put(prefix + "pairsPerLevel", repeat.pairsPerLevel() + " (" + repeat.positives()
                    + " same-concept)");
            for (ArmResult arm : repeat.arms()) {
                metadata.put(prefix + "auc." + arm.arm().label(), format(arm.auc()) + " " + format(arm.interval()));
            }
            metadata.put(prefix + "difference", format(repeat.arm(H2Arm.AGGREGATE).auc()
                    - repeat.arm(H2Arm.SCALAR).auc()) + " " + format(repeat.difference()));
        }
        metadata.put("g2.verdict", go ? "GO" : "NO-GO (spectral resonance stays diagnostics-only)");
        return metadata;
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    static String format(Interval interval) {
        return "[" + format(interval.lower()) + ", " + format(interval.upper()) + "]";
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(value).toUpperCase(Locale.ROOT);
    }

    /**
     * Seeds, sizes and the margin of one H2 run.
     *
     * @param trainingSeed training split seed (generated for disjointness only)
     * @param thresholdSeed threshold-selection split seed (generated for disjointness only)
     * @param testSeeds one held-out seed per repeat
     * @param bootstrapSeed base bootstrap seed; repeat {@code r} (1-based) uses {@code bootstrapSeed + r}
     * @param resamples bootstrap resamples {@code B}
     * @param snrLevels SNR levels in decibels
     * @param sequences sequences per split
     * @param windowsPerSequence windows per sequence
     * @param margin pre-registered AUC margin
     */
    record Protocol(long trainingSeed, long thresholdSeed, List<Long> testSeeds, long bootstrapSeed, int resamples,
                    List<Double> snrLevels, int sequences, int windowsPerSequence, double margin) {

        /** Copies the lists and requires at least one repeat and one SNR level. */
        Protocol {
            testSeeds = List.copyOf(testSeeds);
            snrLevels = List.copyOf(snrLevels);
            if (testSeeds.isEmpty() || snrLevels.isEmpty() || resamples < 1) {
                throw new IllegalArgumentException("a protocol needs a repeat, an SNR level and a resample");
            }
        }

        /** Returns the smoke-mode protocol: the first repeat only, 8 sequences and 200 resamples. */
        Protocol quick() {
            return new Protocol(trainingSeed, thresholdSeed, testSeeds.subList(0, 1), bootstrapSeed, 200, snrLevels,
                    Math.min(8, sequences), windowsPerSequence, margin);
        }
    }

    /** One semantic check. */
    record Check(String name, boolean passed, String detail) implements EvaluationCheck {
    }

    /**
     * Pooled AUC, its interval and the per-SNR AUCs of one arm in one repeat.
     *
     * @param arm the arm
     * @param auc pooled AUC over every SNR level
     * @param interval 95% paired-bootstrap interval of the pooled AUC
     * @param levelAucs AUC per SNR level, in protocol order
     */
    record ArmResult(H2Arm arm, double auc, Interval interval, double[] levelAucs) {
    }

    /**
     * Everything one repeat produced; scores and labels stay in memory for the checks only. {@code patterns} holds the
     * decomposed windows per SNR level for the first repeat, which the counterfactual check re-scores, and is empty
     * for the others.
     */
    record RepeatResult(int number, long seed, List<ConceptSplit> splits, List<SpectralPattern[]> patterns,
                        int[] pairing, double[][] scores,
                        boolean[] positive, double[][] pooled, List<ArmResult> arms, Interval difference,
                        List<Interval> levelDifferences, int positives, int pairsPerLevel) {

        /** Returns the result of one arm. */
        ArmResult arm(H2Arm arm) {
            return arms.get(arm.ordinal());
        }
    }

    /**
     * Result of a run.
     *
     * @param checks semantic checks
     * @param repeats per-repeat results
     * @param go the G2 verdict
     * @param metadata report metadata
     */
    record Outcome(List<EvaluationCheck> checks, List<RepeatResult> repeats, boolean go,
                   Map<String, String> metadata) {
    }
}
