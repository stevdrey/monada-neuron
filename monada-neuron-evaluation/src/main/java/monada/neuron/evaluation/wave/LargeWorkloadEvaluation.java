package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.FeatureConceptScorer.FeatureSequence;
import monada.neuron.evaluation.wave.LargeConceptFixtureGenerator.LargeSplit;
import monada.neuron.evaluation.wave.SelectiveMetrics.Reliability;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Check;
import monada.neuron.reasoning.concept.AbstentionReason;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.ConceptModelTrainer;
import monada.neuron.reasoning.concept.ConceptQuery;
import monada.neuron.reasoning.concept.LabeledSequence;
import monada.neuron.reasoning.concept.NextConceptPredictor;
import monada.neuron.reasoning.concept.PredictionBudget;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import monada.neuron.spectral.WaveDecomposer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;

/**
 * The large workload of contract section 14.1 (issue #130): 256 concepts over depth 3, windows of 4,096 samples,
 * 1,024 held-out windows ({@link LargeConceptFixtureGenerator}). It exercises the bounds at the contract's largest
 * sizes and provides the models and windows for the large-workload resource measurements; its prediction figures are
 * informational and enter no gate.
 *
 * <p>Pre-registered in {@code docs/experiments/ncm-ablation-v1.md}: one SNR level; each model's temperature is chosen
 * on the training split from the G3 grid by the current-leaf negative log-likelihood; no thresholds are selected (no
 * threshold-selection split at this size), so C and D answer every window down to a leaf and the selective trade-off
 * is reported as a risk-coverage curve of the forced score. D uses an explicit {@code HaarWaveDecomposer} of depth
 * {@code log2 4096 = 12}.
 */
final class LargeWorkloadEvaluation {

    /** The pre-registered large protocol. */
    static final Protocol REGISTERED = new Protocol(0x1300_0001L, 0x1300_1001L, 20.0, 32, 16, 64, 12);

    static final SpectralFrame FRAME = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE,
            LargeConceptFixtureGenerator.SAMPLE_COUNT, LargeConceptFixtureGenerator.SAMPLE_RATE, SampleUnit.HERTZ);

    /** Top-k of the large workload's rank metric. */
    static final int TOP_K = 5;

    private final Protocol protocol;
    private final LargeConceptFixtureGenerator generator = new LargeConceptFixtureGenerator();
    private final ConceptModelTrainer trainer = new ConceptModelTrainer();
    private final NextConceptPredictor predictor = new NextConceptPredictor();
    private final FeatureConceptScorer scorer = new FeatureConceptScorer();
    private final SpectralResonanceDynamics dynamics = new SpectralResonanceDynamics();
    private final SelectiveMetrics selective = new SelectiveMetrics();
    private final ConceptHierarchy hierarchy = generator.hierarchy();

    LargeWorkloadEvaluation(Protocol protocol) {
        this.protocol = protocol;
    }

    ConceptHierarchy hierarchy() {
        return hierarchy;
    }

    /** Generates, trains, answers and scores the large workload. */
    Result run() {
        LargeSplit training = generator.generate(SplitRole.TRAINING, protocol.trainingSeed(), protocol.snrDb(),
                protocol.trainingSequences(), protocol.windowsPerSequence());
        LargeSplit test = generator.generate(SplitRole.TEST, protocol.testSeed(), protocol.snrDb(),
                protocol.testSequences(), protocol.windowsPerSequence());
        ConceptFeatureMap dft = ConceptFeatureMap.dft();
        ConceptFeatureMap haar = new ConceptFeatureMap(SpectralBasis.HAAR, protocol.haarLevels(), 0.0);

        SpectralPattern[] trainingPatterns = decompose(dft, training);
        var sequences = new ArrayList<LabeledSequence>();
        int length = protocol.windowsPerSequence();
        for (int start = 0; start < trainingPatterns.length; start += length) {
            var windows = new ArrayList<LabeledSequence.Window>(length);
            for (int w = start; w < start + length; w++) {
                windows.add(new LabeledSequence.Window(trainingPatterns[w],
                        hierarchy.node(training.labels().ordinal(w)).conceptCode()));
            }
            sequences.add(new LabeledSequence(windows));
        }
        ConceptModel c = null;
        double cNll = Double.POSITIVE_INFINITY;
        for (double temperature : NextConceptG3Evaluation.TEMPERATURES) {
            ConceptModel candidate = trainer.train(hierarchy, FRAME, sequences, temperature);
            double nll = 0.0;
            for (int w = 0; w < trainingPatterns.length; w++) {
                int truth = training.labels().ordinal(w);
                double q = predictor.predict(ConceptQuery.of(trainingPatterns[w]), candidate, PredictionBudget.DEFAULT)
                        .scores().map(scores -> scores.currentScore(truth)).orElse(0.0);
                nll -= StrictMath.log(Math.max(q, Double.MIN_NORMAL));
            }
            if (nll < cNll) {
                cNll = nll;
                c = candidate;
            }
        }
        trainingPatterns = null;

        double[][] trainingFeatures = features(haar, training.waves());
        var featureSequences = new ArrayList<FeatureSequence>();
        for (int start = 0; start < trainingFeatures.length; start += length) {
            var leaves = new int[length];
            for (int p = 0; p < length; p++) {
                leaves[p] = training.labels().ordinal(start + p);
            }
            featureSequences.add(new FeatureSequence(Arrays.copyOfRange(trainingFeatures, start,
                    start + length), leaves));
        }
        FeatureConceptModel d = null;
        double dNll = Double.POSITIVE_INFINITY;
        int dimension = haar.dimension(LargeConceptFixtureGenerator.SAMPLE_COUNT);
        for (double temperature : NextConceptG3Evaluation.TEMPERATURES) {
            FeatureConceptModel candidate = scorer.train(hierarchy, haar, dimension, featureSequences, temperature);
            double nll = 0.0;
            for (int w = 0; w < trainingFeatures.length; w++) {
                ScoredPrediction prediction = scorer.predict(trainingFeatures[w], ArmDecisions.NONE, candidate,
                        PredictionBudget.DEFAULT);
                double q = prediction.scored() ? prediction.current()[training.labels().ordinal(w)] : 0.0;
                nll -= StrictMath.log(Math.max(q, Double.MIN_NORMAL));
            }
            if (nll < dNll) {
                dNll = nll;
                d = candidate;
            }
        }
        trainingFeatures = null;
        int mostFrequentNext = mostFrequentNext(c);

        SpectralPattern[] testPatterns = decompose(dft, test);
        double[][] testFeatures = features(haar, test.waves());
        ConceptModel cModel = c;
        FeatureConceptModel dModel = d;
        ArmDecisions cDecisions = ArmDecisions.run(hierarchy, testPatterns.length, length, (w, previous) ->
                ScoredPrediction.of(predictor.predict(new ConceptQuery(testPatterns[w], previous < 0
                        ? OptionalLong.empty() : OptionalLong.of(hierarchy.node(previous).conceptCode())), cModel,
                        PredictionBudget.DEFAULT)));
        ArmDecisions dDecisions = ArmDecisions.run(hierarchy, testFeatures.length, length, (w, previous) ->
                scorer.predict(testFeatures[w], previous, dModel, PredictionBudget.DEFAULT));
        var b = new int[testPatterns.length];
        for (int w = 0; w < b.length; w++) {
            b[w] = nearestLeaf(ScoredPrediction.of(predictor.predict(ConceptQuery.of(testPatterns[w]), cModel,
                    PredictionBudget.DEFAULT)));
        }
        test.labels().openForScoring();

        ArmScore cScore = score(cDecisions, test);
        ArmScore dScore = score(dDecisions, test);
        int answerable = 0;
        int aRight = 0;
        int bRight = 0;
        for (int w = 0; w < b.length; w++) {
            int next = nextLabel(test, w);
            if (next == ArmDecisions.NONE) {
                continue;
            }
            answerable++;
            aRight += mostFrequentNext == next ? 1 : 0;
            bRight += b[w] == next ? 1 : 0;
        }
        int exhausted = 0;
        for (ArmDecisions decisions : List.of(cDecisions, dDecisions)) {
            for (ScoredPrediction prediction : decisions.predictions) {
                exhausted += prediction.abstention().filter(AbstentionReason.BUDGET_EXHAUSTED::equals).isPresent()
                        ? 1 : 0;
            }
        }
        int maxComparisons = Math.max(cDecisions.maxComparisons, dDecisions.maxComparisons);
        boolean bounded = exhausted == 0 && maxComparisons <= PredictionBudget.DEFAULT.maxComparisons()
                && c.scorableConcepts() <= PredictionBudget.DEFAULT.maxComparisons();
        var trainingDigests = new HashSet<>(training.cleanDigests());
        int shared = 0;
        for (String digest : test.cleanDigests()) {
            shared += trainingDigests.contains(digest) ? 1 : 0;
        }
        boolean disjoint = protocol.trainingSeed() != protocol.testSeed() && shared == 0;
        int earlyReads = test.labels().earlyReads();
        var check = new Check("large.bounds", bounded && disjoint && earlyReads == 0, hierarchy.size()
                + " concepts, depth " + hierarchy.levels() + ", " + test.waves().size() + " held-out windows of "
                + LargeConceptFixtureGenerator.SAMPLE_COUNT + " samples: at most " + maxComparisons
                + " comparisons per prediction (budget " + PredictionBudget.DEFAULT.maxComparisons() + "), "
                + exhausted + " BUDGET_EXHAUSTED; training and test seeds "
                + (protocol.trainingSeed() != protocol.testSeed() ? "distinct" : "EQUAL") + ", " + shared + " of "
                + test.cleanDigests().size() + " held-out clean windows shared with training; " + earlyReads + " held-out label reads before scoring");
        return new Result(check, c, d, cScore, dScore, answerable == 0 ? 0.0 : aRight / (double) answerable,
                answerable == 0 ? 0.0 : bRight / (double) answerable, answerable, Arrays.copyOf(testPatterns, length),
                test.waves().subList(0, length), test.seed());
    }

    /** Leaf of highest centroid similarity, ties to the lower ordinal (control B). */
    private int nearestLeaf(ScoredPrediction prediction) {
        if (!prediction.scored()) {
            return ArmDecisions.NONE;
        }
        int best = ArmDecisions.NONE;
        for (int i = 0; i < hierarchy.leafCount(); i++) {
            int leaf = hierarchy.leaf(i);
            if (best == ArmDecisions.NONE || prediction.similarity()[leaf] > prediction.similarity()[best]) {
                best = leaf;
            }
        }
        return best;
    }

    private int mostFrequentNext(ConceptModel model) {
        int best = ArmDecisions.NONE;
        long bestCount = -1;
        for (int to = 0; to < hierarchy.leafCount(); to++) {
            long count = 0;
            for (int from = 0; from < hierarchy.leafCount(); from++) {
                count += model.transitionCount(hierarchy.leaf(from), hierarchy.leaf(to));
            }
            if (count > bestCount) {
                bestCount = count;
                best = hierarchy.leaf(to);
            }
        }
        return best;
    }

    private ArmScore score(ArmDecisions decisions, LargeSplit test) {
        int answerable = 0;
        int leafCovered = 0;
        int leafRight = 0;
        int rootRight = 0;
        int midRight = 0;
        int forcedRight = 0;
        int topK = 0;
        double reciprocal = 0.0;
        int n = decisions.windows();
        var scores = new double[n];
        var correct = new boolean[n];
        var included = new boolean[n];
        for (int w = 0; w < n; w++) {
            int next = nextLabel(test, w);
            if (next == ArmDecisions.NONE) {
                continue;
            }
            answerable++;
            included[w] = true;
            if (decisions.leaf[w] != ArmDecisions.NONE) {
                leafCovered++;
                leafRight += decisions.leaf[w] == next ? 1 : 0;
                midRight += decisions.ancestorAt(decisions.leaf[w], 1) == decisions.ancestorAt(next, 1) ? 1 : 0;
            }
            rootRight += decisions.root[w] != ArmDecisions.NONE && decisions.root[w] == decisions.ancestorAt(next, 0)
                    ? 1 : 0;
            forcedRight += decisions.forced[w] == next ? 1 : 0;
            int rank = decisions.rank(w, next);
            topK += rank > 0 && rank <= TOP_K ? 1 : 0;
            reciprocal += rank > 0 ? 1.0 / rank : 0.0;
            scores[w] = decisions.forcedScore[w];
            correct[w] = decisions.forced[w] == next;
        }
        Reliability reliability = selective.reliability(scores, correct, included);
        double windows = answerable;
        return new ArmScore(leafCovered / windows, leafCovered == 0 ? 0.0 : leafRight / (double) leafCovered,
                midRight / windows, rootRight / windows, forcedRight / windows, topK / windows,
                reciprocal / windows, reliability.ece(), selective.riskCoverage(scores, correct, included),
                decisions.comparisons / (double) n, decisions.maxComparisons);
    }

    private int nextLabel(LargeSplit split, int w) {
        return (w + 1) % protocol.windowsPerSequence() == 0 ? ArmDecisions.NONE : split.labels().ordinal(w + 1);
    }

    SpectralPattern[] decompose(ConceptFeatureMap map, LargeSplit split) {
        WaveDecomposer decomposer = map.decomposer();
        var patterns = new SpectralPattern[split.waves().size()];
        for (int w = 0; w < patterns.length; w++) {
            patterns[w] = map.decompose(decomposer, split.waves().get(w));
        }
        return patterns;
    }

    /** Extracts every window's feature, streaming the patterns so only the current and previous are retained. */
    double[][] features(ConceptFeatureMap map, List<SampledWave> waves) {
        WaveDecomposer decomposer = map.decomposer();
        int dimension = map.dimension(LargeConceptFixtureGenerator.SAMPLE_COUNT);
        var features = new double[waves.size()][];
        SpectralPattern previous = null;
        for (int w = 0; w < features.length; w++) {
            SpectralPattern pattern = map.decompose(decomposer, waves.get(w));
            var feature = new double[dimension];
            boolean start = w % protocol.windowsPerSequence() == 0;
            features[w] = map.extract(pattern, start ? null : previous, dynamics, feature) ? feature : null;
            previous = pattern;
        }
        return features;
    }

    /**
     * Prediction figures of one arm on the large held-out split, over windows with a successor.
     *
     * @param leafCoverage share of windows answered down to a leaf
     * @param leafAccuracy accuracy of the descent's leaf on covered windows
     * @param midAccuracy share of windows whose leaf answer lies under the true next leaf's depth-1 concept
     * @param rootAccuracy share of windows whose depth-0 answer is the true next leaf's root
     * @param forcedAccuracy top-1 accuracy of the flat argmax over the leaves' next-window scores
     * @param topK share of windows whose true next leaf ranks within {@link #TOP_K}
     * @param mrr mean reciprocal rank of the true next leaf
     * @param ece expected calibration error of the forced score
     * @param riskCoverage forced accuracy at 10% to 100% coverage by forced score
     * @param comparisonsPerWindow mean centroid comparisons per prediction
     * @param maxComparisons largest comparisons of one prediction
     */
    record ArmScore(double leafCoverage, double leafAccuracy, double midAccuracy, double rootAccuracy,
                    double forcedAccuracy, double topK, double mrr, double ece, double[] riskCoverage,
                    double comparisonsPerWindow, int maxComparisons) {
    }

    /**
     * Result of the large workload.
     *
     * @param check the bounds, disjointness and leakage check
     * @param c control C's trained model
     * @param d control D's trained model
     * @param cScore C's figures
     * @param dScore D's figures
     * @param aAccuracy control A's accuracy on every window with a successor
     * @param bAccuracy control B's accuracy on every window with a successor
     * @param answerable windows with a successor
     * @param headPatterns the DFT patterns of the first held-out sequence, for the resource measurements
     * @param headWaves the windows of the first held-out sequence, for the resource measurements
     * @param testSeed the held-out split seed
     */
    record Result(Check check, ConceptModel c, FeatureConceptModel d, ArmScore cScore, ArmScore dScore,
                  double aAccuracy, double bAccuracy, int answerable, SpectralPattern[] headPatterns,
                  List<SampledWave> headWaves, long testSeed) {
    }

    /**
     * Seeds and sizes of the large workload.
     *
     * @param trainingSeed training split seed
     * @param testSeed held-out split seed
     * @param snrDb the one SNR level in decibels
     * @param trainingSequences training sequences
     * @param testSequences held-out sequences
     * @param windowsPerSequence windows per sequence
     * @param haarLevels Haar depth of control D
     */
    record Protocol(long trainingSeed, long testSeed, double snrDb, int trainingSequences, int testSequences,
                    int windowsPerSequence, int haarLevels) {

        /** Returns the smoke-mode protocol: four training and two held-out sequences of 16 windows. */
        Protocol quick() {
            return new Protocol(trainingSeed, testSeed, snrDb, Math.min(4, trainingSequences),
                    Math.min(2, testSequences), Math.min(16, windowsPerSequence), haarLevels);
        }
    }
}
