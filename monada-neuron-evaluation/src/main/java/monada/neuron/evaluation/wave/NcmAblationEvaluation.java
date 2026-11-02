package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.wave.ConceptFixtureGenerator.ConceptSplit;
import monada.neuron.evaluation.wave.FeatureConceptScorer.FeatureSequence;
import monada.neuron.evaluation.wave.HostMemoryIndependence.Composition;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Selection;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Split;
import monada.neuron.evaluation.wave.RankStatistics.Interval;
import monada.neuron.evaluation.wave.SelectiveMetrics.Reliability;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Check;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.ConceptQuery;
import monada.neuron.reasoning.concept.ConceptThresholds;
import monada.neuron.reasoning.concept.NextConceptPredictor;
import monada.neuron.reasoning.concept.PredictionBudget;
import monada.neuron.reasoning.concept.WaveCognitionInput;
import monada.neuron.reasoning.concept.WaveCognitionLimits;
import monada.neuron.reasoning.concept.WaveWindow;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.WaveDecomposer;
import monada.neuron.spectral.WaveProvenance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Random;

import static monada.neuron.evaluation.wave.NextConceptG3Evaluation.format;

/**
 * Consolidated ablation and go/no-go evaluation of the NCM wave cognition experiment (issue #130, gate G5 of contract
 * {@code ncm-wave-cognition/2}), under the protocol {@value #PROTOCOL_ID} pre-registered in
 * {@code docs/experiments/ncm-ablation-v1.md} and mirrored by {@link #REGISTERED}.
 *
 * <p>It reuses, unchanged, the G3 harness (controls A, A-oracle, B, C, C-forced, C-flat; H3, H4, H6) and the G5
 * harness (control E; H5) with their registered protocols and margins, and adds:
 * <ul>
 *   <li>the feature arms over the same medium splits, through the reference scorer ({@link FeatureConceptScorer})
 *       whose bit-identity with the core predictor over DFT features is checked first: {@code D-haar} (control D's
 *       Haar features), {@code D-haar+coh} (control D: Haar and coherence features) and {@code C+coh} (C's DFT
 *       features plus coherence, isolating the phase-sensitive feature from the wavelet). Each arm's Haar depth,
 *       coherence weight and temperature are chosen on the training split by the G3 rule (current-leaf negative
 *       log-likelihood), its thresholds and flat thresholds on the threshold-selection split by the G3 grids;</li>
 *   <li>each feature arm judged by the registered H3 and H6 rule (margins 0.15, 0.05, minimum coverage 0.60, N11)
 *       as a candidate replacement for C, plus paired feature and hierarchy ablations against C;</li>
 *   <li>the tiny and large workloads, the host memory on/off check and the end-to-end resource measurements.</li>
 * </ul>
 *
 * <p>Verdict (pre-registered): {@code GO} if C passes the G3 rule in every repeat; {@code CONDITIONAL GO} if a feature
 * arm does (feedback over it, E-D, and a Haar-capable core model are then follow-ups); otherwise {@code NO-GO}. No arm
 * is enabled by default under any verdict. A failed semantic check makes the verdict untrustworthy.
 */
final class NcmAblationEvaluation {

    /** Protocol identifier recorded in every report. */
    static final String PROTOCOL_ID = "ncm-ablation/1";

    /** Contract identifier. */
    static final String CONTRACT = NextConceptG5Evaluation.CONTRACT;

    /** The pre-registered protocol. */
    static final Protocol REGISTERED = new Protocol(NextConceptG3Evaluation.REGISTERED,
            NextConceptG5Evaluation.REGISTERED, LargeWorkloadEvaluation.REGISTERED,
            List.of(1, 2, 3, 4, 5, 6, 7, 8), List.of(0.25, 0.5, 1.0), 0x1300_B001L, 3, 10, 128);

    /** Arm indices of the medium per-arm results: C, then the feature arms in {@link FeatureArm} order. */
    static final int C = 0;

    private static final long PERMUTATION_SALT = 0x5045_524DL;

    /** The feature arms, run through the reference scorer. */
    enum FeatureArm {
        /** C's DFT magnitudes plus coherence with the previous window: the phase ablation without the wavelet. */
        C_COH("C+coh"),
        /** Control D's Haar magnitudes: the wavelet ablation without coherence. */
        D_HAAR("D-haar"),
        /** Control D: Haar magnitudes plus coherence with the previous window. */
        D_HAAR_COH("D-haar+coh");

        final String label;

        FeatureArm(String label) {
            this.label = label;
        }

        /** Returns the candidate feature maps whose training likelihood selects this arm's map. */
        List<ConceptFeatureMap> candidates(Protocol protocol) {
            var maps = new ArrayList<ConceptFeatureMap>();
            switch (this) {
                case C_COH -> protocol.coherenceWeights().forEach(weight ->
                        maps.add(new ConceptFeatureMap(SpectralBasis.DFT, 0, weight)));
                case D_HAAR -> protocol.haarLevels().forEach(levels ->
                        maps.add(new ConceptFeatureMap(SpectralBasis.HAAR, levels, 0.0)));
                case D_HAAR_COH -> protocol.haarLevels().forEach(levels -> protocol.coherenceWeights().forEach(weight ->
                        maps.add(new ConceptFeatureMap(SpectralBasis.HAAR, levels, weight))));
            }
            return maps;
        }
    }

    /** Arm names in result order. */
    static final List<String> ARMS = arms();

    private final Protocol protocol;
    private final boolean quick;
    private final NextConceptG3Evaluation g3;
    private final NextConceptPredictor predictor = new NextConceptPredictor();
    private final FeatureConceptScorer scorer = new FeatureConceptScorer();
    private final SpectralResonanceDynamics dynamics = new SpectralResonanceDynamics();
    private final RankStatistics statistics = new RankStatistics();
    private final SelectiveMetrics selective = new SelectiveMetrics();
    private final SeedDerivation seeds = new SeedDerivation();
    private final HostMemoryIndependence host = new HostMemoryIndependence();
    private final ConceptHierarchy hierarchy;

    NcmAblationEvaluation(Protocol protocol, boolean quick) {
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.quick = quick;
        this.g3 = new NextConceptG3Evaluation(protocol.g3(), quick);
        this.hierarchy = g3.fixtureHierarchy();
    }

    private static List<String> arms() {
        var names = new ArrayList<String>();
        names.add("C");
        for (FeatureArm arm : FeatureArm.values()) {
            names.add(arm.label);
        }
        return List.copyOf(names);
    }

    /** Returns the run configuration recorded in the report. */
    RunConfiguration runConfiguration() {
        return new RunConfiguration(protocol.g3().testSeeds().getFirst(), quick, protocol.warmupIterations(),
                protocol.measurementIterations(), "quality: one deterministic pass per arm; resources: "
                + protocol.warmupIterations() + " warm-up and " + protocol.measurementIterations()
                + " measured iterations per scenario, thread-allocated bytes; no timing gate");
    }

    /** Runs every workload, arm, check and measurement, then the verdict. */
    Outcome run() {
        var checks = new ArrayList<EvaluationCheck>();
        var tiny = new TinyWorkload();
        checks.add(tiny.check());

        NextConceptG3Evaluation.Outcome g3Outcome = g3.run();
        prefixed(checks, "g3.", g3Outcome.checks());
        NextConceptG5Evaluation.Outcome g5Outcome = new NextConceptG5Evaluation(protocol.g5(), quick).run();
        prefixed(checks, "g5.", g5Outcome.checks());

        Selection cSelection = g3Outcome.selection();
        List<Split> training = g3.splits(SplitRole.TRAINING, protocol.g3().trainingSeed());
        List<Split> thresholdSplits = g3.splits(SplitRole.THRESHOLD_SELECTION, protocol.g3().thresholdSeed());
        var selections = new ArrayList<FeatureSelection>();
        for (FeatureArm arm : FeatureArm.values()) {
            selections.add(select(arm, training, thresholdSplits));
        }
        checks.add(equivalenceCheck(cSelection, training, thresholdSplits));

        List<SampledWave> noise = noiseWaves();
        var noiseUncovered = new double[ARMS.size()];
        noiseUncovered[C] = uncoveredRate(runC(dftPatterns(noise), cSelection.model()));
        for (int a = 1; a < ARMS.size(); a++) {
            FeatureSelection selection = selections.get(a - 1);
            noiseUncovered[a] = uncoveredRate(runFeature(features(noise, selection.map()), selection.model()));
        }

        var labelSets = new ArrayList<SplitLabels>();
        var repeats = new ArrayList<RepeatResult>();
        for (int r = 0; r < protocol.g3().testSeeds().size(); r++) {
            List<Split> splits = g3.splits(SplitRole.TEST, protocol.g3().testSeeds().get(r));
            splits.forEach(split -> labelSets.add(split.split().labels()));
            Answers answers = answer(splits, cSelection, selections);
            splits.forEach(split -> split.split().labels().openForScoring());
            repeats.add(score(r, splits, answers, cSelection, noiseUncovered));
        }
        checks.add(reproductionCheck(g3Outcome, repeats, noiseUncovered[C]));
        checks.add(pairedInputsCheck(repeats.getFirst(), selections));
        checks.add(counterfactualCheck(repeats.getFirst(), cSelection, selections, labelSets));
        checks.add(determinismCheck(repeats.getFirst(), cSelection, selections, labelSets, noiseUncovered));
        int earlyReads = labelSets.stream().mapToInt(SplitLabels::earlyReads).sum();
        checks.add(new Check("leakage.test-labels", earlyReads == 0, labelSets.size()
                + " guarded held-out label sets of the feature arms; " + earlyReads
                + " reads before the scorer opened them"));

        List<SampledWave> memoryWindows = repeats.getFirst().splits().getFirst().split().windows().stream()
                .limit(protocol.memoryWindows()).map(ConceptFixtureGenerator.FixtureWindow::wave).toList();
        checks.add(host.check(cSelection.model(), memoryWindows));

        LargeWorkloadEvaluation largeEvaluation = new LargeWorkloadEvaluation(protocol.large());
        LargeWorkloadEvaluation.Result large = largeEvaluation.run();
        checks.add(large.check());

        List<BenchmarkRunResult> resources = resources(tiny, cSelection, selections.get(FeatureArm.D_HAAR.ordinal()),
                repeats.getFirst(), large);

        var armGo = new boolean[ARMS.size()];
        for (int a = 0; a < ARMS.size(); a++) {
            int arm = a;
            armGo[a] = repeats.stream().allMatch(repeat -> repeat.arms().get(arm).gatePassed());
        }
        boolean featureGo = false;
        for (int a = 1; a < ARMS.size(); a++) {
            featureGo |= armGo[a];
        }
        String verdict = armGo[C] ? "GO" : featureGo ? "CONDITIONAL GO" : "NO-GO";
        return new Outcome(checks, g3Outcome, g5Outcome, cSelection, selections, repeats, noiseUncovered, armGo,
                large, resources, verdict, metadata(g3Outcome, g5Outcome, cSelection, selections, repeats,
                noiseUncovered, armGo, large, verdict));
    }

    private void prefixed(List<EvaluationCheck> checks, String prefix, List<EvaluationCheck> source) {
        for (EvaluationCheck check : source) {
            checks.add(new Check(prefix + check.name(), check.passed(), check.detail()));
        }
    }

    // ---------------------------------------------------------------- features and selection

    /** Extracts the features of every window of each split; patterns are streamed, one previous window retained. */
    double[][] features(Split split, ConceptFeatureMap map) {
        if (map.basis() == SpectralBasis.DFT) {
            return features(split.patterns(), map);
        }
        return features(split.split().windows().stream().map(ConceptFixtureGenerator.FixtureWindow::wave).toList(),
                map);
    }

    private double[][] features(List<SampledWave> waves, ConceptFeatureMap map) {
        WaveDecomposer decomposer = map.decomposer();
        var patterns = new SpectralPattern[waves.size()];
        for (int w = 0; w < patterns.length; w++) {
            patterns[w] = map.decompose(decomposer, waves.get(w));
        }
        return features(patterns, map);
    }

    private double[][] features(SpectralPattern[] patterns, ConceptFeatureMap map) {
        int dimension = map.dimension(ConceptFixtureGenerator.SAMPLE_COUNT);
        int length = protocol.g3().windowsPerSequence();
        var features = new double[patterns.length][];
        for (int w = 0; w < patterns.length; w++) {
            var feature = new double[dimension];
            SpectralPattern previous = w % length == 0 ? null : patterns[w - 1];
            features[w] = map.extract(patterns[w], previous, dynamics, feature) ? feature : null;
        }
        return features;
    }

    private List<FeatureSequence> featureSequences(List<Split> splits, List<double[][]> features) {
        int length = protocol.g3().windowsPerSequence();
        var sequences = new ArrayList<FeatureSequence>();
        for (int s = 0; s < splits.size(); s++) {
            double[][] split = features.get(s);
            for (int start = 0; start < split.length; start += length) {
                var leaves = new int[length];
                for (int p = 0; p < length; p++) {
                    leaves[p] = g3.label(splits.get(s), start + p);
                }
                sequences.add(new FeatureSequence(Arrays.copyOfRange(split, start, start + length), leaves));
            }
        }
        return sequences;
    }

    /**
     * Selects one feature arm on non-test data: the feature map and temperature of least current-leaf negative
     * log-likelihood on the training split (first in grid order on ties), then C's threshold grid and selection
     * coverage on the threshold-selection split, then the flat thresholds closest to the arm's coverage.
     */
    FeatureSelection select(FeatureArm arm, List<Split> training, List<Split> thresholdSplits) {
        FeatureConceptModel best = null;
        double bestNll = Double.POSITIVE_INFINITY;
        int tried = 0;
        for (ConceptFeatureMap map : arm.candidates(protocol)) {
            var features = new ArrayList<double[][]>();
            for (Split split : training) {
                features.add(features(split, map));
            }
            List<FeatureSequence> sequences = featureSequences(training, features);
            int dimension = map.dimension(ConceptFixtureGenerator.SAMPLE_COUNT);
            for (double temperature : NextConceptG3Evaluation.TEMPERATURES) {
                FeatureConceptModel candidate = scorer.train(hierarchy, map, dimension, sequences, temperature);
                double nll = 0.0;
                for (int s = 0; s < training.size(); s++) {
                    for (int w = 0; w < features.get(s).length; w++) {
                        ScoredPrediction prediction = scorer.predict(features.get(s)[w], ArmDecisions.NONE,
                                candidate, PredictionBudget.DEFAULT);
                        double q = prediction.scored() ? prediction.current()[g3.label(training.get(s), w)] : 0.0;
                        nll -= StrictMath.log(Math.max(q, Double.MIN_NORMAL));
                    }
                }
                tried++;
                if (nll < bestNll) {
                    bestNll = nll;
                    best = candidate;
                }
            }
        }
        ConceptFeatureMap map = best.features();
        var thresholdFeatures = new ArrayList<double[][]>();
        for (Split split : thresholdSplits) {
            thresholdFeatures.add(features(split, map));
        }
        ConceptThresholds chosen = ConceptThresholds.NONE;
        double chosenAccuracy = -1.0;
        double chosenCoverage = -1.0;
        boolean reached = false;
        for (double c0 : NextConceptG3Evaluation.CONFIDENCE_GRID) {
            for (double m0 : NextConceptG3Evaluation.MARGIN_GRID) {
                for (double c1 : NextConceptG3Evaluation.CONFIDENCE_GRID) {
                    for (double m1 : NextConceptG3Evaluation.MARGIN_GRID) {
                        var thresholds = ConceptThresholds.of(new double[] {c0, c1}, new double[] {m0, m1});
                        double[] covered = selectionScore(thresholdSplits,
                                runFeatures(thresholdFeatures, best.withThresholds(thresholds)));
                        if (covered[0] >= protocol.g3().selectionCoverage() && (!reached || covered[1] > chosenAccuracy
                                || (covered[1] == chosenAccuracy && covered[0] > chosenCoverage))) {
                            reached = true;
                            chosen = thresholds;
                            chosenAccuracy = covered[1];
                            chosenCoverage = covered[0];
                        }
                    }
                }
            }
        }
        if (!reached) {
            double[] none = selectionScore(thresholdSplits, runFeatures(thresholdFeatures, best));
            chosenCoverage = none[0];
            chosenAccuracy = none[1];
        }
        FeatureConceptModel model = best.withThresholds(chosen);
        List<ArmDecisions> chosenDecisions = runFeatures(thresholdFeatures, model);
        double flatConfidence = 0.0;
        double flatMargin = 0.0;
        double flatCoverage = -1.0;
        double flatAccuracy = 0.0;
        double gap = Double.POSITIVE_INFINITY;
        for (double c : NextConceptG3Evaluation.FLAT_CONFIDENCE_GRID) {
            for (double m : NextConceptG3Evaluation.FLAT_MARGIN_GRID) {
                var flats = new ArrayList<ArmDecisions>();
                for (ArmDecisions decisions : chosenDecisions) {
                    flats.add(decisions.flat(c, m));
                }
                double[] covered = selectionScore(thresholdSplits, flats);
                double distance = Math.abs(covered[0] - chosenCoverage);
                if (distance < gap) {
                    gap = distance;
                    flatConfidence = c;
                    flatMargin = m;
                    flatCoverage = covered[0];
                    flatAccuracy = covered[1];
                }
            }
        }
        return new FeatureSelection(arm, map, model, bestNll, tried, chosenCoverage, chosenAccuracy, reached,
                flatConfidence, flatMargin, flatCoverage, flatAccuracy);
    }

    /** Returns {@code [leaf coverage, accuracy on covered]} of decisions on non-test splits (the G3 rule). */
    private double[] selectionScore(List<Split> splits, List<ArmDecisions> decisions) {
        int covered = 0;
        int correct = 0;
        int answerable = 0;
        for (int s = 0; s < splits.size(); s++) {
            int[] leaves = decisions.get(s).leaf;
            for (int w = 0; w < leaves.length; w++) {
                int next = g3.nextLabel(splits.get(s), w);
                if (next == ArmDecisions.NONE) {
                    continue;
                }
                answerable++;
                if (leaves[w] != ArmDecisions.NONE) {
                    covered++;
                    correct += leaves[w] == next ? 1 : 0;
                }
            }
        }
        return new double[] {covered / (double) answerable, covered == 0 ? 0.0 : correct / (double) covered};
    }

    // ---------------------------------------------------------------- arms

    ArmDecisions runC(SpectralPattern[] patterns, ConceptModel model) {
        return ArmDecisions.run(hierarchy, patterns.length, protocol.g3().windowsPerSequence(), (w, previous) ->
                ScoredPrediction.of(predictor.predict(new ConceptQuery(patterns[w], previous < 0
                        ? OptionalLong.empty() : OptionalLong.of(hierarchy.node(previous).conceptCode())), model,
                        PredictionBudget.DEFAULT)));
    }

    ArmDecisions runFeature(double[][] features, FeatureConceptModel model) {
        return ArmDecisions.run(hierarchy, features.length, protocol.g3().windowsPerSequence(), (w, previous) ->
                scorer.predict(features[w], previous, model, PredictionBudget.DEFAULT));
    }

    private List<ArmDecisions> runFeatures(List<double[][]> features, FeatureConceptModel model) {
        var decisions = new ArrayList<ArmDecisions>(features.size());
        for (double[][] split : features) {
            decisions.add(runFeature(split, model));
        }
        return decisions;
    }

    /** Every arm answers every window of every SNR level of one repeat; no label is read. */
    private Answers answer(List<Split> splits, Selection cSelection, List<FeatureSelection> selections) {
        var arms = new ArrayList<List<ArmDecisions>>();
        var flats = new ArrayList<List<ArmDecisions>>();
        var c = new ArrayList<ArmDecisions>();
        var cFlat = new ArrayList<ArmDecisions>();
        for (Split split : splits) {
            ArmDecisions decisions = runC(split.patterns(), cSelection.model());
            c.add(decisions);
            cFlat.add(decisions.flat(cSelection.flatConfidence(), cSelection.flatMargin()));
        }
        arms.add(c);
        flats.add(cFlat);
        for (FeatureSelection selection : selections) {
            var arm = new ArrayList<ArmDecisions>();
            var flat = new ArrayList<ArmDecisions>();
            for (Split split : splits) {
                ArmDecisions decisions = runFeature(features(split, selection.map()), selection.model());
                arm.add(decisions);
                flat.add(decisions.flat(selection.flatConfidence(), selection.flatMargin()));
            }
            arms.add(arm);
            flats.add(flat);
        }
        return new Answers(arms, flats);
    }

    // ---------------------------------------------------------------- scoring

    private RepeatResult score(int r, List<Split> splits, Answers answers, Selection cSelection,
                               double[] noiseUncovered) {
        int levels = splits.size();
        int perLevel = splits.getFirst().patterns().length;
        int items = levels * perLevel;
        int arms = ARMS.size();
        int ratios = 6;
        var numerators = new double[arms * ratios][items];
        var denominators = new double[arms * ratios][items];
        var included = new boolean[items];
        var next = new int[items];
        for (int s = 0; s < levels; s++) {
            for (int w = 0; w < perLevel; w++) {
                int item = s * perLevel + w;
                next[item] = g3.nextLabel(splits.get(s), w);
                included[item] = next[item] != ArmDecisions.NONE;
            }
        }
        int aAnswer = cSelection.mostFrequentNext();
        for (int a = 0; a < arms; a++) {
            for (int s = 0; s < levels; s++) {
                ArmDecisions decisions = answers.arms().get(a).get(s);
                ArmDecisions flat = answers.flats().get(a).get(s);
                for (int w = 0; w < perLevel; w++) {
                    int item = s * perLevel + w;
                    if (!included[item]) {
                        continue;
                    }
                    double covered = decisions.leaf[w] != ArmDecisions.NONE ? 1.0 : 0.0;
                    double correct = decisions.leaf[w] == next[item] ? 1.0 : 0.0;
                    int base = a * ratios;
                    numerators[base][item] = correct;
                    denominators[base][item] = covered;
                    numerators[base + 1][item] = covered == 1.0 && aAnswer == next[item] ? 1.0 : 0.0;
                    denominators[base + 1][item] = covered;
                    numerators[base + 2][item] = decisions.forced[w] == next[item] ? 1.0 : 0.0;
                    denominators[base + 2][item] = 1.0;
                    numerators[base + 3][item] = flat.leaf[w] == next[item] ? 1.0 : 0.0;
                    denominators[base + 3][item] = flat.leaf[w] != ArmDecisions.NONE ? 1.0 : 0.0;
                    numerators[base + 4][item] = covered;
                    denominators[base + 4][item] = 1.0;
                    numerators[base + 5][item] = correct;
                    denominators[base + 5][item] = 1.0;
                }
            }
        }
        double[][] pooled = statistics.bootstrapRatios(numerators, denominators, g3.clusters(levels, perLevel),
                protocol.g3().resamples(), protocol.bootstrapSeed() + r + 1);
        var results = new ArrayList<ArmResult>(arms);
        for (int a = 0; a < arms; a++) {
            results.add(armResult(a, splits, answers, numerators, denominators, pooled, included, next,
                    noiseUncovered[a]));
        }
        return new RepeatResult(r + 1, protocol.g3().testSeeds().get(r), splits, answers, pooled, results);
    }

    private ArmResult armResult(int a, List<Split> splits, Answers answers, double[][] numerators,
                                double[][] denominators, double[][] pooled, boolean[] included, int[] next,
                                double noiseUncovered) {
        int base = a * 6;
        int cBase = C * 6;
        Interval h3 = statistics.interval(statistics.difference(pooled[base], pooled[base + 1]));
        Interval h6 = statistics.interval(statistics.difference(pooled[base], pooled[base + 2]));
        Interval h4 = statistics.interval(statistics.difference(pooled[base], pooled[base + 3]));
        Interval coverage = statistics.interval(pooled[base + 4]);
        Interval forcedVsC = statistics.interval(statistics.difference(pooled[base + 2], pooled[cBase + 2]));
        Interval rateVsC = statistics.interval(statistics.difference(pooled[base + 5], pooled[cBase + 5]));

        int levels = splits.size();
        int perLevel = splits.getFirst().patterns().length;
        int items = levels * perLevel;
        var forcedScores = new double[items];
        var coveredScores = new double[items];
        var forcedRight = new boolean[items];
        var leafRight = new boolean[items];
        var coveredIncluded = new boolean[items];
        var uncovered = new boolean[items];
        var forcedWrong = new boolean[items];
        var coverageByLevel = new double[levels];
        var accuracyByLevel = new double[levels];
        int top2 = 0;
        int rootCovered = 0;
        int rootRight = 0;
        double reciprocal = 0.0;
        int answerable = 0;
        long comparisons = 0;
        int abstentions = 0;
        int coarseOnly = 0;
        for (int s = 0; s < levels; s++) {
            ArmDecisions decisions = answers.arms().get(a).get(s);
            comparisons += decisions.comparisons;
            abstentions += decisions.abstentions;
            coarseOnly += decisions.coarseOnly;
            int levelAnswerable = 0;
            int levelCovered = 0;
            int levelRight = 0;
            for (int w = 0; w < perLevel; w++) {
                int item = s * perLevel + w;
                if (!included[item]) {
                    continue;
                }
                answerable++;
                levelAnswerable++;
                boolean covered = decisions.leaf[w] != ArmDecisions.NONE;
                levelCovered += covered ? 1 : 0;
                levelRight += covered && decisions.leaf[w] == next[item] ? 1 : 0;
                forcedScores[item] = decisions.forcedScore[w];
                coveredScores[item] = decisions.leafScore[w];
                forcedRight[item] = decisions.forced[w] == next[item];
                leafRight[item] = covered && decisions.leaf[w] == next[item];
                coveredIncluded[item] = covered;
                uncovered[item] = !covered;
                forcedWrong[item] = !forcedRight[item];
                top2 += decisions.forced[w] == next[item] || decisions.forcedSecond[w] == next[item] ? 1 : 0;
                int rank = decisions.rank(w, next[item]);
                reciprocal += rank > 0 ? 1.0 / rank : 0.0;
                if (decisions.root[w] != ArmDecisions.NONE) {
                    rootCovered++;
                    rootRight += decisions.root[w] == decisions.ancestorAt(next[item], 0) ? 1 : 0;
                }
            }
            coverageByLevel[s] = levelCovered / (double) levelAnswerable;
            accuracyByLevel[s] = levelCovered == 0 ? 0.0 : levelRight / (double) levelCovered;
        }
        Reliability forced = selective.reliability(forcedScores, forcedRight, included);
        Reliability covered = selective.reliability(coveredScores, leafRight, coveredIncluded);
        double cleanUncovered = 1.0 - coverageByLevel[protocol.g3().snrLevels().indexOf(
                NextConceptG3Evaluation.CLEAN_SNR_DB)];
        double coverageValue = statistics.ratio(numerators[base + 4], denominators[base + 4]);
        boolean gate = h3.lower() > protocol.g3().h3Margin() && coverageValue >= protocol.g3().h3MinCoverage()
                && h6.lower() > protocol.g3().h6Margin() && noiseUncovered > cleanUncovered;
        return new ArmResult(ARMS.get(a), h3, h6, h4, coverage, forcedVsC, rateVsC, coverageValue,
                statistics.ratio(numerators[base], denominators[base]),
                statistics.ratio(numerators[base + 1], denominators[base + 1]),
                statistics.ratio(numerators[base + 2], denominators[base + 2]),
                statistics.ratio(numerators[base + 3], denominators[base + 3]),
                statistics.ratio(numerators[base + 5], denominators[base + 5]), top2 / (double) answerable,
                reciprocal / answerable, rootCovered / (double) answerable,
                rootCovered == 0 ? 0.0 : rootRight / (double) rootCovered, forced.ece(), covered.ece(),
                selective.riskCoverage(forcedScores, forcedRight, included), rate(forcedWrong, uncovered, included),
                rate(uncovered, forcedWrong, included), coverageByLevel, accuracyByLevel, cleanUncovered,
                noiseUncovered, abstentions, coarseOnly, comparisons / (double) items, gate);
    }

    /** Returns {@code P(event | condition)} over the included items, {@code 0} when the condition never holds. */
    private double rate(boolean[] event, boolean[] condition, boolean[] included) {
        int count = 0;
        int hits = 0;
        for (int i = 0; i < event.length; i++) {
            if (included[i] && condition[i]) {
                count++;
                hits += event[i] ? 1 : 0;
            }
        }
        return count == 0 ? 0.0 : hits / (double) count;
    }

    /** The fraction of windows with a successor left uncovered at the leaf depth (the G3 N11 rule). */
    private double uncoveredRate(ArmDecisions decisions) {
        int length = protocol.g3().windowsPerSequence();
        int count = 0;
        int uncovered = 0;
        for (int w = 0; w < decisions.windows(); w++) {
            if ((w + 1) % length == 0) {
                continue;
            }
            count++;
            uncovered += decisions.leaf[w] == ArmDecisions.NONE ? 1 : 0;
        }
        return uncovered / (double) count;
    }

    // ---------------------------------------------------------------- data

    /** The N11 noise windows, drawn exactly as the G3 harness draws them. */
    List<SampledWave> noiseWaves() {
        var random = new Random(protocol.g3().noiseSeed());
        int n = protocol.g3().sequences() * protocol.g3().windowsPerSequence();
        var waves = new ArrayList<SampledWave>(n);
        for (int w = 0; w < n; w++) {
            var samples = new double[ConceptFixtureGenerator.SAMPLE_COUNT];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = random.nextGaussian();
            }
            waves.add(new SampledWave(samples, ConceptFixtureGenerator.SAMPLE_RATE, SampleUnit.HERTZ,
                    new WaveProvenance("ncm-noise-n11", 1, "seed-" + Long.toHexString(protocol.g3().noiseSeed())
                            + "/w" + w, (long) w * samples.length)));
        }
        return waves;
    }

    private SpectralPattern[] dftPatterns(List<SampledWave> waves) {
        ConceptFeatureMap dft = ConceptFeatureMap.dft();
        WaveDecomposer decomposer = dft.decomposer();
        var patterns = new SpectralPattern[waves.size()];
        for (int w = 0; w < patterns.length; w++) {
            patterns[w] = dft.decompose(decomposer, waves.get(w));
        }
        return patterns;
    }

    // ---------------------------------------------------------------- checks

    /**
     * The reference scorer over DFT features, trained on the training split with C's temperature and thresholds,
     * must reproduce the core predictor bit for bit on every threshold-selection window along each arm's own
     * autoregressive path (centroids, score tables, selections, abstention reasons and work).
     */
    private Check equivalenceCheck(Selection cSelection, List<Split> training, List<Split> thresholdSplits) {
        ConceptModel model = cSelection.model();
        ConceptFeatureMap dft = ConceptFeatureMap.dft();
        var trainingFeatures = new ArrayList<double[][]>();
        for (Split split : training) {
            trainingFeatures.add(features(split, dft));
        }
        FeatureConceptModel reference = scorer.train(hierarchy, dft, dft.dimension(ConceptFixtureGenerator.SAMPLE_COUNT),
                featureSequences(training, trainingFeatures), model.temperature()).withThresholds(model.thresholds());
        boolean centroids = true;
        for (int a = 0; a < hierarchy.size(); a++) {
            double[] core = model.centroid(a);
            for (int k = 0; k < core.length; k++) {
                centroids &= Double.compare(core[k], reference.centroid(a, k)) == 0;
            }
        }
        int windows = 0;
        int identical = 0;
        for (Split split : thresholdSplits) {
            ArmDecisions core = runC(split.patterns(), model);
            ArmDecisions mirrored = runFeature(features(split, dft), reference);
            for (int w = 0; w < core.windows(); w++) {
                windows++;
                identical += core.predictions[w].sameAs(mirrored.predictions[w]) && core.previous[w] == mirrored.previous[w]
                        ? 1 : 0;
            }
        }
        return new Check("d.reference-equivalence", centroids && identical == windows,
                "reference scorer over DFT features vs the core predictor (C's temperature and thresholds): "
                        + "centroids " + (centroids ? "bit-identical" : "DIFFERENT") + "; " + identical + "/" + windows
                        + " threshold-selection predictions bit-identical (scores, selection, reason, work)");
    }

    /**
     * The ablation's C is the G3 harness's C: per repeat and SNR level the same leaf answers, the same gate, and on
     * N11 the same non-coverage.
     */
    private Check reproductionCheck(NextConceptG3Evaluation.Outcome g3Outcome, List<RepeatResult> repeats,
                                    double noiseUncovered) {
        int levels = 0;
        int matched = 0;
        int gates = 0;
        for (int r = 0; r < repeats.size(); r++) {
            NextConceptG3Evaluation.RepeatResult g3Repeat = g3Outcome.repeats().get(r);
            for (int s = 0; s < repeats.get(r).splits().size(); s++) {
                levels++;
                matched += Arrays.equals(repeats.get(r).answers().arms().get(C).get(s).leaf,
                        g3Repeat.answers().c().get(s).leaf()) ? 1 : 0;
            }
            gates += repeats.get(r).arms().get(C).gatePassed() == g3Repeat.gatePassed() ? 1 : 0;
        }
        boolean noise = Double.compare(noiseUncovered, g3Outcome.noiseUncovered()) == 0;
        return new Check("c.reproduces-g3", matched == levels && gates == repeats.size() && noise, matched + "/"
                + levels + " repeat x SNR splits with C's leaf answers equal to the G3 harness; " + gates + "/"
                + repeats.size() + " equal gate outcomes; N11 non-coverage " + format(noiseUncovered)
                + (noise ? " equal" : " DIFFERENT from " + format(g3Outcome.noiseUncovered())));
    }

    /**
     * Every flat arm shares its arm's previous concepts and predictions on every window of the first repeat, and each
     * window where the two answer differently re-scores to an equal next-window table with no thresholds: the
     * hierarchy ablation changes only the decision.
     */
    private Check pairedInputsCheck(RepeatResult first, List<FeatureSelection> selections) {
        int windows = 0;
        int shared = 0;
        int audited = 0;
        int matched = 0;
        for (int a = 1; a < ARMS.size(); a++) {
            FeatureSelection selection = selections.get(a - 1);
            FeatureConceptModel open = selection.model().withThresholds(ConceptThresholds.NONE);
            for (int s = 0; s < first.splits().size(); s++) {
                ArmDecisions arm = first.answers().arms().get(a).get(s);
                ArmDecisions flat = first.answers().flats().get(a).get(s);
                double[][] features = null;
                for (int w = 0; w < arm.windows(); w++) {
                    windows++;
                    shared += arm.predictions[w] == flat.predictions[w] && arm.previous[w] == flat.previous[w] ? 1 : 0;
                    if (arm.leaf[w] == flat.leaf[w]) {
                        continue;
                    }
                    if (features == null) {
                        features = features(first.splits().get(s), selection.map());
                    }
                    audited++;
                    ScoredPrediction again = scorer.predict(features[w], arm.previous[w], open,
                            PredictionBudget.DEFAULT);
                    matched += again.scored() && Arrays.equals(again.next(), arm.predictions[w].next()) ? 1 : 0;
                }
            }
        }
        return new Check("d.paired-inputs", shared == windows && matched == audited, "repeat 1, feature arms: the "
                + "flat control shares the arm's previous concept and prediction on " + shared + "/" + windows
                + " windows; " + matched + "/" + audited + " windows with different answers re-scored identically");
    }

    /**
     * Re-answers the first repeat with permuted held-out labels: every arm's answers must be bit-identical, and
     * D-haar's covered accuracy must change (the labels decide the scorer, so the check is not vacuous).
     */
    private Check counterfactualCheck(RepeatResult first, Selection cSelection, List<FeatureSelection> selections,
                                      List<SplitLabels> labelSets) {
        var permuted = new ArrayList<Split>();
        for (int s = 0; s < first.splits().size(); s++) {
            Split split = first.splits().get(s);
            SplitLabels labels = split.split().labels().permuted(new Random(seeds.derive(first.seed(),
                    PERMUTATION_SALT, s)));
            labelSets.add(labels);
            ConceptSplit original = split.split();
            permuted.add(new Split(new ConceptSplit(original.role(), original.seed(), original.snrDb(),
                    original.windows(), labels), split.patterns()));
        }
        Answers again = answer(permuted, cSelection, selections);
        boolean identical = first.answers().sameAs(again);
        permuted.forEach(split -> split.split().labels().openForScoring());
        int arm = 1 + FeatureArm.D_HAAR.ordinal();
        boolean changed = Double.compare(coveredAccuracy(permuted, again.arms().get(arm)),
                first.arms().get(arm).coveredAccuracy()) != 0;
        return new Check("leakage.counterfactual", identical && changed,
                "repeat 1 re-answered with permuted held-out labels: every arm's answers " + (identical
                        ? "bit-identical" : "CHANGED") + "; D-haar covered accuracy " + (changed ? "changed"
                        : "unchanged (vacuous)"));
    }

    private double coveredAccuracy(List<Split> splits, List<ArmDecisions> decisions) {
        return selectionScore(splits, decisions)[1];
    }

    /** Regenerates and re-answers the first repeat and re-runs its bootstrap; everything must be bit-identical. */
    private Check determinismCheck(RepeatResult first, Selection cSelection, List<FeatureSelection> selections,
                                   List<SplitLabels> labelSets, double[] noiseUncovered) {
        List<Split> splits = g3.splits(SplitRole.TEST, first.seed());
        splits.forEach(split -> labelSets.add(split.split().labels()));
        Answers again = answer(splits, cSelection, selections);
        splits.forEach(split -> split.split().labels().openForScoring());
        RepeatResult rerun = score(first.number() - 1, splits, again, cSelection, noiseUncovered);
        boolean identical = first.answers().sameAs(again) && Arrays.deepEquals(first.pooled(), rerun.pooled());
        return new Check("determinism", identical, "repeat 1 regenerated, every arm re-answered and re-bootstrapped: "
                + (identical ? "bit-identical" : "DIFFERENT"));
    }

    // ---------------------------------------------------------------- resources

    /**
     * End-to-end measurements per workload: decomposition plus prediction per window (one sequence per iteration),
     * prediction alone, control D's Haar path, and one opt-in runtime execution of eight windows through the wave
     * cognition stage. Thread-allocated bytes exclude nothing but the iteration setup.
     */
    List<BenchmarkRunResult> resources(TinyWorkload tiny, Selection cSelection, FeatureSelection dSelection,
                                       RepeatResult first, LargeWorkloadEvaluation.Result large) {
        var collector = new EvaluationMetricsCollector();
        int warmup = protocol.warmupIterations();
        int measured = protocol.measurementIterations();
        var results = new ArrayList<BenchmarkRunResult>();

        ConceptModel tinyModel = tiny.model();
        results.add(collector.measure("ncm.tiny.C.window", "tiny", warmup, measured, 2, () -> {
            for (int ordinal = 0; ordinal < 2; ordinal++) {
                tiny.predictC(tinyModel, ordinal);
            }
        }, diagnostics(2, tinyModel.retainedBytes(), "DFT decomposition + core prediction of " + TinyWorkload.N
                + "-sample windows")));

        int length = protocol.g3().windowsPerSequence();
        List<SampledWave> medium = first.splits().getFirst().split().windows().stream().limit(length)
                .map(ConceptFixtureGenerator.FixtureWindow::wave).toList();
        SpectralPattern[] mediumPatterns = dftPatterns(medium);
        ConceptModel cModel = cSelection.model();
        results.add(collector.measure("ncm.medium.C.window", "medium", warmup, measured, medium.size(),
                () -> runC(dftPatterns(medium), cModel), diagnostics(medium.size(), cModel.retainedBytes(),
                        "DFT decomposition + core prediction, own previous concept")));
        results.add(collector.measure("ncm.medium.C.predict", "medium", warmup, measured, medium.size(),
                () -> runC(mediumPatterns, cModel), diagnostics(medium.size(), cModel.retainedBytes(),
                        "core prediction of decomposed windows")));
        results.add(collector.measure("ncm.medium.D.window", "medium", warmup, measured, medium.size(),
                () -> runFeature(features(medium, dSelection.map()), dSelection.model()),
                diagnostics(medium.size(), dSelection.model().retainedBytes(), dSelection.map().label()
                        + " decomposition + feature + reference scorer")));
        results.add(stage("ncm.medium.stage.execution", "medium", collector, cModel, medium));

        int largeLength = protocol.large().windowsPerSequence();
        SpectralPattern[] largePatterns = Arrays.copyOf(large.testPatterns(), largeLength);
        List<SampledWave> largeWaves = large.test().waves().subList(0, largeLength);
        LargeWorkloadEvaluation largeEvaluation = new LargeWorkloadEvaluation(protocol.large());
        ConceptHierarchy largeHierarchy = largeEvaluation.hierarchy();
        ConceptModel largeC = large.c();
        ArmDecisions.Step largeStep = (w, previous) -> ScoredPrediction.of(predictor.predict(new ConceptQuery(
                largePatterns[w], previous < 0 ? OptionalLong.empty()
                : OptionalLong.of(largeHierarchy.node(previous).conceptCode())), largeC, PredictionBudget.DEFAULT));
        results.add(collector.measure("ncm.large.C.window", "large", warmup, measured, largeLength, () -> {
            ConceptFeatureMap dft = ConceptFeatureMap.dft();
            WaveDecomposer decomposer = dft.decomposer();
            for (SampledWave wave : largeWaves) {
                dft.decompose(decomposer, wave);
            }
            ArmDecisions.run(largeHierarchy, largeLength, largeLength, largeStep);
        }, diagnostics(largeLength, largeC.retainedBytes(), "DFT decomposition + core prediction, own previous "
                + "concept")));
        results.add(collector.measure("ncm.large.C.predict", "large", warmup, measured, largeLength,
                () -> ArmDecisions.run(largeHierarchy, largeLength, largeLength, largeStep),
                diagnostics(largeLength, largeC.retainedBytes(), "core prediction of decomposed windows")));
        FeatureConceptModel largeD = large.d();
        LargeConceptFixtureGenerator.LargeSplit largeHead = new LargeConceptFixtureGenerator.LargeSplit(
                SplitRole.TEST, large.test().seed(), largeWaves, new LargeConceptFixtureGenerator.LeafLabels(
                new int[largeLength], false));
        results.add(collector.measure("ncm.large.D.window", "large", warmup, measured, largeLength, () -> {
            double[][] features = largeEvaluation.features(largeD.features(), largeHead);
            ArmDecisions.run(largeHierarchy, largeLength, largeLength,
                    (w, previous) -> scorer.predict(features[w], previous, largeD, PredictionBudget.DEFAULT));
        }, diagnostics(largeLength, largeD.retainedBytes(), largeD.features().label()
                + " decomposition + feature + reference scorer")));
        results.add(stage("ncm.large.stage.execution", "large", collector, largeC, largeWaves));
        return results;
    }

    private Map<String, String> diagnostics(int windows, long retainedBytes, String path) {
        var diagnostics = new LinkedHashMap<String, String>();
        diagnostics.put("windowsPerIteration", Integer.toString(windows));
        diagnostics.put("snapshotRetainedBytes", Long.toString(retainedBytes));
        diagnostics.put("path", path);
        return diagnostics;
    }

    /** One runtime execution of the first eight windows through the opt-in stage, with no memory port. */
    private BenchmarkRunResult stage(String name, String scale, EvaluationMetricsCollector collector,
                                     ConceptModel model, List<SampledWave> waves) {
        int windows = Math.min(WaveCognitionLimits.DEFAULT.maxWindows(), waves.size());
        var input = new ArrayList<WaveWindow>(windows);
        for (int w = 0; w < windows; w++) {
            input.add(new WaveWindow(waves.get(w), OptionalLong.empty()));
        }
        Map<String, WaveCognitionInput> inputs = new HashMap<>();
        inputs.put("stage", new WaveCognitionInput(input));
        NeuronRuntime runtime = host.runtime(Composition.NO_MEMORY, model, inputs, WaveCognitionLimits.DEFAULT);
        String outcome = host.stageResult(runtime.execute(host.input("stage"))).report().outcome().name();
        var diagnostics = diagnostics(windows, model.retainedBytes(), "NeuronRuntime execution: wave cognition "
                + "stage + hypothesis evaluation, default limits");
        diagnostics.put("stageOutcome", outcome);
        return collector.measure(name, scale, protocol.warmupIterations(), protocol.measurementIterations(),
                windows, () -> runtime.execute(host.input("stage")), diagnostics);
    }

    // ---------------------------------------------------------------- report

    private Map<String, String> metadata(NextConceptG3Evaluation.Outcome g3Outcome,
                                         NextConceptG5Evaluation.Outcome g5Outcome, Selection cSelection,
                                         List<FeatureSelection> selections, List<RepeatResult> repeats,
                                         double[] noiseUncovered, boolean[] armGo,
                                         LargeWorkloadEvaluation.Result large, String verdict) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("contract", CONTRACT);
        metadata.put("protocol.id", PROTOCOL_ID);
        metadata.put("protocol.registered", Boolean.toString(protocol.equals(REGISTERED)));
        metadata.put("protocol.verdictRule", "GO iff C passes the registered G3 rule (H3 margin "
                + format(protocol.g3().h3Margin()) + ", minimum leaf coverage " + format(protocol.g3().h3MinCoverage())
                + ", H6 margin " + format(protocol.g3().h6Margin()) + ", N11 above 20 dB non-coverage) in every "
                + "repeat; CONDITIONAL GO iff a feature arm does instead; otherwise NO-GO. No margin is added.");
        metadata.put("generator.medium", ConceptFixtureGenerator.VERSION);
        metadata.put("generator.large", LargeConceptFixtureGenerator.VERSION);
        metadata.put("workload.tiny", TinyWorkload.N + " samples, 2 concepts, depth 1, fixed temperature "
                + TinyWorkload.TEMPERATURE + ", D Haar depth " + TinyWorkload.HAAR_LEVELS);
        metadata.put("workload.medium", protocol.g3().sequences() + " sequences x "
                + protocol.g3().windowsPerSequence() + " windows of " + ConceptFixtureGenerator.SAMPLE_COUNT
                + " samples per split and SNR level, SNR " + protocol.g3().snrLevels() + " dB, "
                + protocol.g3().testSeeds().size() + " repeats, " + hierarchy.size() + " concepts, depth "
                + hierarchy.levels());
        metadata.put("workload.large", protocol.large().trainingSequences() + " training and "
                + protocol.large().testSequences() + " held-out sequences x " + protocol.large().windowsPerSequence()
                + " windows of " + LargeConceptFixtureGenerator.SAMPLE_COUNT + " samples, SNR "
                + protocol.large().snrDb() + " dB, " + large.c().hierarchy().size() + " concepts, depth "
                + large.c().hierarchy().levels() + ", seeds " + hex(protocol.large().trainingSeed()) + "/"
                + hex(protocol.large().testSeed()));
        metadata.put("seeds.medium", "training " + hex(protocol.g3().trainingSeed()) + ", threshold "
                + hex(protocol.g3().thresholdSeed()) + ", test " + protocol.g3().testSeeds().stream()
                .map(NcmAblationEvaluation::hex).toList() + ", N11 " + hex(protocol.g3().noiseSeed())
                + ", bootstrap " + hex(protocol.bootstrapSeed()) + " + repeat (B=" + protocol.g3().resamples() + ")");
        metadata.put("grids.featureArms", "Haar depth " + protocol.haarLevels() + ", coherence weight "
                + protocol.coherenceWeights() + ", temperature " + Arrays.toString(NextConceptG3Evaluation.TEMPERATURES)
                + "; thresholds on the G3 grids");
        metadata.put("selection.C", "temperature " + cSelection.model().temperature() + ", thresholds "
                + cSelection.model().thresholds() + ", flat confidence " + format(cSelection.flatConfidence())
                + " margin " + format(cSelection.flatMargin()) + " (G3 selection)");
        for (FeatureSelection selection : selections) {
            metadata.put("selection." + selection.arm().label, selection.map().label() + " (of " + selection.tried()
                    + " map x temperature candidates, training NLL " + format(selection.nll()) + "), temperature "
                    + selection.model().temperature() + ", thresholds " + selection.model().thresholds()
                    + ", threshold split coverage " + format(selection.coverage()) + " accuracy "
                    + format(selection.accuracy()) + (selection.reached() ? "" : " (target coverage NOT reached)")
                    + ", flat confidence " + format(selection.flatConfidence()) + " margin "
                    + format(selection.flatMargin()) + ", retained " + selection.model().retainedBytes() + " bytes");
        }
        for (int a = 0; a < ARMS.size(); a++) {
            metadata.put("n11." + ARMS.get(a), format(noiseUncovered[a]));
            metadata.put("gate." + ARMS.get(a), armGo[a] ? "pass in every repeat" : "fail");
        }
        metadata.put("g3.verdict", g3Outcome.go() ? "GO" : "NO-GO");
        metadata.put("g5.h5", g5Outcome.holds() ? "holds" : "negative");
        metadata.put("controlE", "C + validated feedback (G5 harness, registered H5 margin "
                + format(protocol.g5().h5Margin()) + ", minimum coverage " + format(protocol.g5().h5MinCoverage())
                + ")");
        metadata.put("controlE-D", armGo[1 + FeatureArm.D_HAAR.ordinal()] || armGo[1 + FeatureArm.D_HAAR_COH.ordinal()]
                ? "not run in " + PROTOCOL_ID + ": a D arm passed; feedback over D is a required follow-up"
                : "excluded: no D arm passes the G3 rule, so feedback over D cannot change the verdict");
        metadata.put("controlD.excluded", "none (G1h go: Haar in scope)");
        metadata.put("verdict", verdict);
        return metadata;
    }

    static String hex(long value) {
        return NextConceptG5Evaluation.hex(value);
    }

    // ---------------------------------------------------------------- values

    /**
     * Training and selection of one feature arm.
     *
     * @param arm the arm
     * @param map the selected feature map
     * @param model the trained snapshot with the selected thresholds
     * @param nll training current-leaf negative log-likelihood of the selected map and temperature
     * @param tried map and temperature candidates compared
     */
    record FeatureSelection(FeatureArm arm, ConceptFeatureMap map, FeatureConceptModel model, double nll, int tried,
                            double coverage, double accuracy, boolean reached, double flatConfidence,
                            double flatMargin, double flatCoverage, double flatAccuracy) {
    }

    /** Per arm in {@link #ARMS} order, the decisions and flat decisions per SNR level. */
    record Answers(List<List<ArmDecisions>> arms, List<List<ArmDecisions>> flats) {

        boolean sameAs(Answers other) {
            for (int a = 0; a < arms.size(); a++) {
                for (int s = 0; s < arms.get(a).size(); s++) {
                    if (!arms.get(a).get(s).sameAs(other.arms().get(a).get(s))
                            || !flats.get(a).get(s).sameAs(other.flats().get(a).get(s))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * One arm's figures in one repeat.
     *
     * @param h3 acc(arm covered) − acc(A on the arm's covered windows), the registered H3 estimand
     * @param h6 acc(arm covered) − acc(arm forced top-1 on all windows), the registered H6 estimand
     * @param h4 acc(arm covered) − acc(arm-flat covered), the hierarchy ablation
     * @param coverageInterval leaf coverage
     * @param forcedVsC forced top-1 accuracy of the arm minus C's, paired on all windows: the feature ablation
     * @param rateVsC covered-and-correct rate of the arm minus C's, paired on all windows
     * @param rootCoverage share of windows answered at depth 0
     * @param rootAccuracy depth-0 accuracy on those windows
     * @param abstentionPrecision P(forced answer wrong | leaf uncovered)
     * @param abstentionRecall P(leaf uncovered | forced answer wrong)
     * @param coverageByLevel leaf coverage per SNR level
     * @param accuracyByLevel covered accuracy per SNR level
     * @param cleanUncovered leaf non-coverage at 20 dB
     * @param noiseUncovered leaf non-coverage on N11
     * @param comparisonsPerWindow mean centroid comparisons per window
     * @param gatePassed whether the registered G3 rule holds for this arm in this repeat
     */
    record ArmResult(String name, Interval h3, Interval h6, Interval h4, Interval coverageInterval, Interval forcedVsC,
                     Interval rateVsC, double coverage, double coveredAccuracy, double aOnCovered,
                     double forcedAccuracy, double flatAccuracy, double rate, double top2, double mrr,
                     double rootCoverage, double rootAccuracy, double forcedEce, double coveredEce,
                     double[] riskCoverage, double abstentionPrecision, double abstentionRecall,
                     double[] coverageByLevel, double[] accuracyByLevel, double cleanUncovered,
                     double noiseUncovered, int abstentions, int coarseOnly, double comparisonsPerWindow,
                     boolean gatePassed) {
    }

    /** Everything one repeat produced. */
    record RepeatResult(int number, long seed, List<Split> splits, Answers answers, double[][] pooled,
                        List<ArmResult> arms) {
    }

    /**
     * Result of a run.
     *
     * @param armGo per arm, whether it passes the registered G3 rule in every repeat
     * @param verdict {@code GO}, {@code CONDITIONAL GO} or {@code NO-GO}
     */
    record Outcome(List<EvaluationCheck> checks, NextConceptG3Evaluation.Outcome g3, NextConceptG5Evaluation.Outcome g5,
                   Selection cSelection, List<FeatureSelection> selections, List<RepeatResult> repeats,
                   double[] noiseUncovered, boolean[] armGo, LargeWorkloadEvaluation.Result large,
                   List<BenchmarkRunResult> resources, String verdict, Map<String, String> metadata) {
    }

    /**
     * The ablation protocol: the registered G3 and G5 protocols unchanged, the large workload, the feature-arm grids,
     * the ablation's bootstrap seed and the resource iterations.
     *
     * @param g3 the G3 protocol (medium workload, controls A to C-flat, H3, H4, H6 margins)
     * @param g5 the G5 protocol (control E, H5 margin)
     * @param large the large workload
     * @param haarLevels Haar depths the D arms choose from
     * @param coherenceWeights coherence weights the coherence arms choose from
     * @param bootstrapSeed base bootstrap seed of the feature-arm intervals; repeat {@code r} uses {@code + r}
     * @param warmupIterations resource warm-up iterations
     * @param measurementIterations resource measured iterations
     * @param memoryWindows medium windows of the host memory on/off check
     */
    record Protocol(NextConceptG3Evaluation.Protocol g3, NextConceptG5Evaluation.Protocol g5,
                    LargeWorkloadEvaluation.Protocol large, List<Integer> haarLevels, List<Double> coherenceWeights,
                    long bootstrapSeed, int warmupIterations, int measurementIterations, int memoryWindows) {

        /** Copies the grids. */
        Protocol {
            haarLevels = List.copyOf(haarLevels);
            coherenceWeights = List.copyOf(coherenceWeights);
            if (haarLevels.isEmpty() || coherenceWeights.isEmpty() || measurementIterations < 1
                    || warmupIterations < 0 || memoryWindows < 1) {
                throw new IllegalArgumentException("a protocol needs grids, a measured iteration and memory windows");
            }
        }

        /** Returns the smoke-mode protocol: the quick G3, G5 and large protocols and one warm-up iteration. */
        Protocol quick() {
            return new Protocol(g3.quick(), g5.quick(), large.quick(), haarLevels, coherenceWeights, bootstrapSeed, 1,
                    2, Math.min(32, memoryWindows));
        }
    }
}
