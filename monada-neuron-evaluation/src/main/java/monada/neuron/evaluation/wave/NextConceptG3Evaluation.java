package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.wave.ConceptFixtureGenerator.ConceptSplit;
import monada.neuron.evaluation.wave.RankStatistics.Interval;
import monada.neuron.evaluation.wave.SelectiveMetrics.Reliability;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Check;
import monada.neuron.reasoning.concept.AbstentionReason;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.ConceptModelTrainer;
import monada.neuron.reasoning.concept.ConceptNode;
import monada.neuron.reasoning.concept.ConceptPrediction;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptAbstention;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptCandidates;
import monada.neuron.reasoning.concept.ConceptQuery;
import monada.neuron.reasoning.concept.ConceptScores;
import monada.neuron.reasoning.concept.ConceptThresholds;
import monada.neuron.reasoning.concept.LabeledSequence;
import monada.neuron.reasoning.concept.NextConceptPredictor;
import monada.neuron.reasoning.concept.PredictionBudget;
import monada.neuron.spectral.DecompositionResult;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import monada.neuron.spectral.WaveProvenance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Random;

/**
 * Hypotheses H3, H4 and H6 of contract {@code ncm-wave-cognition/1} (gate G3) on the medium workload: does the
 * hierarchical {@link NextConceptPredictor} (control C) predict the next concept better than the no-spectral baseline
 * A on the windows it answers, with enough coverage, and is its abstention informative?
 *
 * <p>The protocol is fixed in {@code docs/benchmarks/next-concept-g3.md} and mirrored by {@link #REGISTERED}. The
 * model is trained on the training split (every SNR level pooled), its temperature chosen on the training split and
 * its thresholds on the threshold-selection split; no test label is read before every arm has answered every test
 * window. Each arm runs its sequences in stream order and receives as previous concept only its <b>own</b> earlier
 * prediction (contract sections 8.2 and 12.3). The G3 verdict is data: a no-go is a valid outcome, not a failed
 * check.
 *
 * <p>Arms (contract section 14.2): A (most frequent training next concept), A-oracle (informational: the training
 * transition table applied to the true current label, read only after the labels are opened), B (flat nearest leaf
 * centroid of the current window), C (the predictor, without resonance features because G2 is no-go) and C-flat (C's
 * score table ranked flat over the leaves, its thresholds set on the threshold-selection split to reach C's leaf
 * coverage; the same predictor call, so the same features, scoring inputs and work).
 */
final class NextConceptG3Evaluation {

    /** Contract identifier recorded in every report. */
    static final String CONTRACT = SpectralResonanceH2Evaluation.CONTRACT;

    /** Proposition domain of the fixture hierarchy. */
    static final int DOMAIN = 127;

    /** The pre-registered protocol. */
    static final Protocol REGISTERED = new Protocol(0x1250_0001L, 0x1250_0002L,
            List.of(0x1270_1001L, 0x1270_1002L, 0x1270_1003L, 0x1270_1004L, 0x1270_1005L),
            0x1270_B007L, 2_000, List.of(20.0, 10.0, 0.0),
            ConceptFixtureGenerator.SEQUENCES, ConceptFixtureGenerator.WINDOWS_PER_SEQUENCE, 0x1270_0011L,
            0.15, 0.60, 0.05, 0.00, 0.70);

    /** Temperature grid, chosen on the training split by the negative log-likelihood of the current leaf. */
    static final double[] TEMPERATURES = {0.01, 0.02, 0.05, 0.1, 0.2, 0.5};
    /** Per-depth confidence grid of C. */
    static final double[] CONFIDENCE_GRID = {0.0, 0.6, 0.7, 0.8, 0.9};
    /** Per-depth margin grid of C. */
    static final double[] MARGIN_GRID = {0.0, 0.1, 0.2, 0.3};
    /** Confidence grid of C-flat: {@code 0.00, 0.05, ..., 0.95}. */
    static final double[] FLAT_CONFIDENCE_GRID = grid(20, 0.05);
    /** Margin grid of C-flat. */
    static final double[] FLAT_MARGIN_GRID = {0.0, 0.05, 0.1, 0.2};

    static final SpectralFrame FRAME = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE,
            ConceptFixtureGenerator.SAMPLE_COUNT, ConceptFixtureGenerator.SAMPLE_RATE, SampleUnit.HERTZ);

    /** SNR level whose leaf non-coverage the N11 noise windows must exceed (gate rule). */
    static final double CLEAN_SNR_DB = 20.0;

    private static final long PERMUTATION_SALT = 0x5045_524DL;
    private static final int NO_CONCEPT = -1;
    private static final List<String> ARMS = List.of("C", "A", "A-oracle", "B", "C-forced", "C-flat", "A-all");

    private final Protocol protocol;
    private final boolean quick;
    private final ConceptFixtureGenerator generator = new ConceptFixtureGenerator();
    private final DftWaveDecomposer decomposer = new DftWaveDecomposer();
    private final ConceptModelTrainer trainer = new ConceptModelTrainer();
    private final NextConceptPredictor predictor = new NextConceptPredictor();
    private final RankStatistics statistics = new RankStatistics();
    private final SelectiveMetrics selective = new SelectiveMetrics();
    private final SeedDerivation seeds = new SeedDerivation();
    private final ConceptHierarchy hierarchy = fixtureHierarchy();

    NextConceptG3Evaluation(Protocol protocol, boolean quick) {
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.quick = quick;
    }

    /** Returns the hierarchy of contract section 12.3: ordinal = {@link FixtureConcept} ordinal. */
    private ConceptHierarchy fixtureHierarchy() {
        var nodes = new ArrayList<ConceptNode>();
        for (FixtureConcept concept : FixtureConcept.values()) {
            nodes.add(new ConceptNode(concept.ordinal(), concept.code(), concept.parentOrdinal(), concept.depth()));
        }
        return ConceptHierarchy.of(DOMAIN, nodes);
    }

    /** Returns the run configuration recorded in the report. */
    RunConfiguration runConfiguration() {
        return new RunConfiguration(protocol.testSeeds().getFirst(), quick, 0, 1,
                "one deterministic pass; no timing gate");
    }

    /** Trains, selects, runs every repeat, the checks and the gate. */
    Outcome run() {
        List<Split> training = splits(SplitRole.TRAINING, protocol.trainingSeed());
        Selection selection = select(training);
        ConceptModel model = selection.model();

        var checks = new ArrayList<EvaluationCheck>();
        checks.add(fixtureCheck(model));

        Decisions noise = runC(noiseSplit(), model);
        double noiseUncovered = uncoveredRate(noise);

        var labelSets = new ArrayList<SplitLabels>();
        var repeats = new ArrayList<RepeatResult>();
        for (int r = 0; r < protocol.testSeeds().size(); r++) {
            repeats.add(repeat(r, selection, labelSets, noiseUncovered));
        }
        checks.add(disjointnessCheck(training, repeats));
        checks.add(counterfactualCheck(repeats.getFirst(), selection, labelSets));
        checks.add(pairedInputsCheck(repeats.getFirst(), selection));
        checks.add(determinismCheck(repeats.getFirst(), selection, labelSets, noiseUncovered));
        int earlyReads = labelSets.stream().mapToInt(SplitLabels::earlyReads).sum();
        checks.add(new Check("leakage.test-labels", earlyReads == 0,
                labelSets.size() + " guarded held-out label sets; " + earlyReads
                        + " reads before the scorer opened them"));

        boolean go = repeats.stream().allMatch(RepeatResult::gatePassed);
        boolean h4 = repeats.stream().allMatch(repeat -> repeat.h4().lower() > protocol.h4Margin());
        return new Outcome(checks, selection, repeats, noise.windows(), noiseUncovered, go, h4,
                metadata(selection, repeats, noise, noiseUncovered, go, h4));
    }

    // ---------------------------------------------------------------- training and selection

    /** Chooses the temperature on the training split, then C's and C-flat's thresholds on the selection split. */
    private Selection select(List<Split> training) {
        List<LabeledSequence> sequences = labeledSequences(training);
        ConceptModel best = null;
        double bestNll = Double.POSITIVE_INFINITY;
        var nlls = new double[TEMPERATURES.length];
        for (int t = 0; t < TEMPERATURES.length; t++) {
            ConceptModel candidate = trainer.train(hierarchy, FRAME, sequences, TEMPERATURES[t]);
            nlls[t] = currentLeafNll(training, candidate);
            if (nlls[t] < bestNll) {
                bestNll = nlls[t];
                best = candidate;
            }
        }
        int mostFrequentNext = mostFrequentNext(best);

        List<Split> thresholdSplits = splits(SplitRole.THRESHOLD_SELECTION, protocol.thresholdSeed());
        ConceptThresholds chosen = ConceptThresholds.NONE;
        double chosenAccuracy = -1.0;
        double chosenCoverage = -1.0;
        boolean reached = false;
        for (double c0 : CONFIDENCE_GRID) {
            for (double m0 : MARGIN_GRID) {
                for (double c1 : CONFIDENCE_GRID) {
                    for (double m1 : MARGIN_GRID) {
                        var thresholds = ConceptThresholds.of(new double[] {c0, c1}, new double[] {m0, m1});
                        double[] covered = selectionScore(thresholdSplits,
                                runC(thresholdSplits, best.withThresholds(thresholds)));
                        boolean meets = covered[0] >= protocol.selectionCoverage();
                        if (meets && (!reached || covered[1] > chosenAccuracy
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
            double[] none = selectionScore(thresholdSplits, runC(thresholdSplits, best));
            chosenCoverage = none[0];
            chosenAccuracy = none[1];
        }
        ConceptModel model = best.withThresholds(chosen);
        List<Decisions> chosenC = runC(thresholdSplits, model);

        double flatConfidence = 0.0;
        double flatMargin = 0.0;
        double flatCoverage = -1.0;
        double flatAccuracy = 0.0;
        double gap = Double.POSITIVE_INFINITY;
        for (double c : FLAT_CONFIDENCE_GRID) {
            for (double m : FLAT_MARGIN_GRID) {
                double[] covered = selectionScore(thresholdSplits, flat(chosenC, c, m));
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
        return new Selection(model, nlls, mostFrequentNext, chosenCoverage, chosenAccuracy, reached, flatConfidence,
                flatMargin, flatCoverage, flatAccuracy);
    }

    /** Returns {@code [leaf coverage, accuracy on covered]} of one arm's decisions on non-test splits. */
    private double[] selectionScore(List<Split> splits, List<Decisions> decisions) {
        int covered = 0;
        int correct = 0;
        int answerable = 0;
        for (int s = 0; s < splits.size(); s++) {
            Split split = splits.get(s);
            int[] leaves = decisions.get(s).leaf();
            for (int w = 0; w < leaves.length; w++) {
                int next = nextLabel(split, w);
                if (next == NO_CONCEPT) {
                    continue;
                }
                answerable++;
                if (leaves[w] != NO_CONCEPT) {
                    covered++;
                    correct += leaves[w] == next ? 1 : 0;
                }
            }
        }
        return new double[] {covered / (double) answerable, covered == 0 ? 0.0 : correct / (double) covered};
    }

    /**
     * Negative log-likelihood of the true current leaf under the spectral posterior, no previous concept. As
     * pre-registered it is measured on the training windows the centroids were fitted on, so it is in-sample and
     * favours low temperatures; the limitation is recorded in {@code docs/benchmarks/next-concept-g3.md}.
     */
    private double currentLeafNll(List<Split> training, ConceptModel model) {
        double nll = 0.0;
        for (Split split : training) {
            for (int w = 0; w < split.patterns().length; w++) {
                ConceptPrediction prediction = predictor.predict(ConceptQuery.of(split.patterns()[w]), model,
                        PredictionBudget.DEFAULT);
                int truth = label(split, w);
                double q = prediction.scores().map(s -> s.currentScore(truth)).orElse(0.0);
                nll -= StrictMath.log(Math.max(q, Double.MIN_NORMAL));
            }
        }
        return nll;
    }

    /** Control A: the most frequent next leaf of the training transitions, ties to the lower ordinal. */
    private int mostFrequentNext(ConceptModel model) {
        int best = NO_CONCEPT;
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

    /** A-oracle: the most frequent training successor of a leaf, ties to the lower ordinal. */
    private int oracleNext(ConceptModel model, int leaf) {
        int best = NO_CONCEPT;
        long bestCount = -1;
        for (int to = 0; to < hierarchy.leafCount(); to++) {
            long count = model.transitionCount(leaf, hierarchy.leaf(to));
            if (count > bestCount) {
                bestCount = count;
                best = hierarchy.leaf(to);
            }
        }
        return best;
    }

    private List<LabeledSequence> labeledSequences(List<Split> training) {
        var sequences = new ArrayList<LabeledSequence>();
        for (Split split : training) {
            int length = protocol.windowsPerSequence();
            for (int start = 0; start < split.patterns().length; start += length) {
                var windows = new ArrayList<LabeledSequence.Window>(length);
                for (int w = start; w < start + length; w++) {
                    windows.add(new LabeledSequence.Window(split.patterns()[w],
                            FixtureConcept.values()[label(split, w)].code()));
                }
                sequences.add(new LabeledSequence(windows));
            }
        }
        return sequences;
    }

    // ---------------------------------------------------------------- arms

    /**
     * Runs C over every sequence of a split in stream order. The query for window {@code p} carries C's own
     * selection for window {@code p − 1} (made at step {@code p − 2}, coarse included); none at a sequence start or
     * after an abstention. It is the only autoregressive loop: C-flat reads the score tables recorded here.
     */
    Decisions runC(Split split, ConceptModel model) {
        int n = split.patterns().length;
        var decisions = new Decisions(n);
        int length = protocol.windowsPerSequence();
        for (int start = 0; start < n; start += length) {
            OptionalLong forPrevious = OptionalLong.empty();
            OptionalLong forCurrent = OptionalLong.empty();
            for (int w = start; w < start + length; w++) {
                ConceptPrediction prediction = predictor.predict(new ConceptQuery(split.patterns()[w], forPrevious),
                        model, PredictionBudget.DEFAULT);
                decisions.record(w, forPrevious, prediction, hierarchy);
                forPrevious = forCurrent;
                forCurrent = switch (prediction) {
                    case ConceptCandidates candidates ->
                            OptionalLong.of(hierarchy.node(candidates.selected().conceptOrdinal()).conceptCode());
                    case ConceptAbstention ignored -> OptionalLong.empty();
                };
            }
        }
        return decisions;
    }

    private List<Decisions> runC(List<Split> splits, ConceptModel model) {
        var decisions = new ArrayList<Decisions>(splits.size());
        for (Split split : splits) {
            decisions.add(runC(split, model));
        }
        return decisions;
    }

    /**
     * C-flat: C's own score table of every window (the same predictor call, hence the same previous concept and
     * work), ranked flat over the leaves; it answers the top leaf when its margin and score reach the flat thresholds.
     * It makes no predictor call and feeds nothing back, so H4 changes only the decision over identical inputs.
     */
    Decisions flat(Decisions c, double confidence, double margin) {
        var decisions = new Decisions(c.windows());
        for (int w = 0; w < c.windows(); w++) {
            decisions.previous[w] = c.previous[w];
            decisions.scores[w] = c.scores[w];
            ConceptScores scores = c.scores[w];
            if (scores == null) {
                continue;
            }
            int[] ranked = rankedLeaves(scores, false);
            double top = scores.nextScore(ranked[0]);
            double runnerUp = ranked.length > 1 ? scores.nextScore(ranked[1]) : 0.0;
            if (top - runnerUp >= margin && top >= confidence) {
                decisions.leaf[w] = ranked[0];
            }
            decisions.leafScore[w] = top;
        }
        decisions.comparisons = c.comparisons;
        return decisions;
    }

    private List<Decisions> flat(List<Decisions> c, double confidence, double margin) {
        var decisions = new ArrayList<Decisions>(c.size());
        for (Decisions arm : c) {
            decisions.add(flat(arm, confidence, margin));
        }
        return decisions;
    }

    /** Runs B: the leaf whose centroid is nearest to the current window, as the next concept; it always answers. */
    int[] runB(Split split, ConceptModel model) {
        ConceptModel unthresholded = model.withThresholds(ConceptThresholds.NONE);
        var answers = new int[split.patterns().length];
        for (int w = 0; w < answers.length; w++) {
            ConceptPrediction prediction = predictor.predict(ConceptQuery.of(split.patterns()[w]), unthresholded,
                    PredictionBudget.DEFAULT);
            answers[w] = prediction.scores().map(scores -> rankedLeaves(scores, true)[0]).orElse(NO_CONCEPT);
        }
        return answers;
    }

    /** Leaves ranked by next score (or similarity) descending, ties to the lower ordinal. */
    private int[] rankedLeaves(ConceptScores scores, boolean bySimilarity) {
        var ranked = new int[hierarchy.leafCount()];
        for (int i = 0; i < ranked.length; i++) {
            int leaf = hierarchy.leaf(i);
            double value = bySimilarity ? scores.similarity(leaf) : scores.nextScore(leaf);
            int at = i;
            while (at > 0) {
                int other = ranked[at - 1];
                double otherValue = bySimilarity ? scores.similarity(other) : scores.nextScore(other);
                if (value > otherValue) {
                    ranked[at] = other;
                    at--;
                } else {
                    break;
                }
            }
            ranked[at] = leaf;
        }
        return ranked;
    }

    // ---------------------------------------------------------------- repeats and scoring

    private RepeatResult repeat(int r, Selection selection, List<SplitLabels> labelSets, double noiseUncovered) {
        long seed = protocol.testSeeds().get(r);
        List<Split> splits = splits(SplitRole.TEST, seed);
        Answers answers = answer(splits, selection);
        for (Split split : splits) {
            labelSets.add(split.split().labels());
            split.split().labels().openForScoring();
        }
        return score(r, seed, splits, answers, selection, noiseUncovered);
    }

    /** Every arm answers every window of every SNR level; no label is read. */
    private Answers answer(List<Split> splits, Selection selection) {
        var c = new ArrayList<Decisions>();
        var flat = new ArrayList<Decisions>();
        var b = new ArrayList<int[]>();
        for (Split split : splits) {
            Decisions arm = runC(split, selection.model());
            c.add(arm);
            flat.add(flat(arm, selection.flatConfidence(), selection.flatMargin()));
            b.add(runB(split, selection.model()));
        }
        return new Answers(c, flat, b);
    }

    private RepeatResult score(int r, long seed, List<Split> splits, Answers answers, Selection selection,
                               double noiseUncovered) {
        int levels = splits.size();
        int perLevel = splits.getFirst().patterns().length;
        int items = levels * perLevel;
        var answerable = new double[items];
        var cCovered = new double[items];
        var cCorrect = new double[items];
        var aOnCovered = new double[items];
        var forcedCorrect = new double[items];
        var flatCovered = new double[items];
        var flatCorrect = new double[items];
        var correctness = new boolean[ARMS.size()][items];
        var rootCovered = new boolean[items];
        var rootCorrect = new boolean[items];
        var top2Correct = new boolean[items];
        var forcedScores = new double[items];
        var coveredScores = new double[items];
        var rootScores = new double[items];
        var included = new boolean[items];
        var coveredIncluded = new boolean[items];
        var rootIncluded = new boolean[items];
        var uncoveredLeaf = new boolean[items];
        var forcedWrong = new boolean[items];
        for (int s = 0; s < levels; s++) {
            Split split = splits.get(s);
            Decisions c = answers.c().get(s);
            Decisions flat = answers.flat().get(s);
            int[] b = answers.b().get(s);
            for (int w = 0; w < perLevel; w++) {
                int item = s * perLevel + w;
                int next = nextLabel(split, w);
                if (next == NO_CONCEPT) {
                    continue;
                }
                int current = label(split, w);
                answerable[item] = 1.0;
                included[item] = true;
                boolean covered = c.leaf[w] != NO_CONCEPT;
                boolean aRight = selection.mostFrequentNext() == next;
                cCovered[item] = covered ? 1.0 : 0.0;
                cCorrect[item] = covered && c.leaf[w] == next ? 1.0 : 0.0;
                aOnCovered[item] = covered && aRight ? 1.0 : 0.0;
                forcedCorrect[item] = c.forced[w] == next ? 1.0 : 0.0;
                flatCovered[item] = flat.leaf[w] != NO_CONCEPT ? 1.0 : 0.0;
                flatCorrect[item] = flat.leaf[w] == next ? 1.0 : 0.0;
                correctness[0][item] = cCorrect[item] == 1.0;
                correctness[1][item] = aRight;
                correctness[2][item] = oracleNext(selection.model(), current) == next;
                correctness[3][item] = b[w] == next;
                correctness[4][item] = forcedCorrect[item] == 1.0;
                correctness[5][item] = flatCorrect[item] == 1.0;
                correctness[6][item] = aRight;
                rootCovered[item] = c.root[w] != NO_CONCEPT;
                rootIncluded[item] = rootCovered[item];
                rootCorrect[item] = c.root[w] == rootOf(next);
                rootScores[item] = c.rootScore[w];
                top2Correct[item] = c.forced[w] == next || c.forcedSecond[w] == next;
                forcedScores[item] = c.forcedScore[w];
                coveredScores[item] = c.leafScore[w];
                coveredIncluded[item] = covered;
                uncoveredLeaf[item] = !covered;
                forcedWrong[item] = forcedCorrect[item] == 0.0;
            }
        }
        double[][] numerators = {cCorrect, aOnCovered, forcedCorrect, flatCorrect, cCovered};
        double[][] denominators = {cCovered, cCovered, answerable, flatCovered, answerable};
        long bootstrapSeed = protocol.bootstrapSeed() + r + 1;
        double[][] pooled = statistics.bootstrapRatios(numerators, denominators, clusters(levels, perLevel),
                protocol.resamples(), bootstrapSeed);
        Interval h3 = statistics.interval(statistics.difference(pooled[0], pooled[1]));
        Interval h6 = statistics.interval(statistics.difference(pooled[0], pooled[2]));
        Interval h4 = statistics.interval(statistics.difference(pooled[0], pooled[3]));
        Interval coverageInterval = statistics.interval(pooled[4]);

        double cAccuracy = statistics.ratio(cCorrect, cCovered);
        double aAccuracy = statistics.ratio(aOnCovered, cCovered);
        double forcedAccuracy = statistics.ratio(forcedCorrect, answerable);
        double flatAccuracy = statistics.ratio(flatCorrect, flatCovered);
        double coverage = statistics.ratio(cCovered, answerable);
        double flatCoverage = statistics.ratio(flatCovered, answerable);

        var armAccuracy = new double[ARMS.size()][levels + 1];
        for (int arm = 0; arm < ARMS.size(); arm++) {
            for (int s = 0; s <= levels; s++) {
                // C and A are paired on C's covered windows (the H3 estimand); A-all is A on every window.
                armAccuracy[arm][s] = arm <= 1 ? accuracyOn(correctness[arm], coveredIncluded, s, perLevel, levels)
                        : arm == 5 ? accuracyOn(correctness[5], positive(flatCovered), s, perLevel, levels)
                        : accuracyOn(correctness[arm], included, s, perLevel, levels);
            }
        }
        var coverageByLevel = new double[levels + 1];
        var rootCoverageByLevel = new double[levels + 1];
        var rootAccuracyByLevel = new double[levels + 1];
        var top2ByLevel = new double[levels + 1];
        for (int s = 0; s <= levels; s++) {
            coverageByLevel[s] = accuracyOn(coveredIncluded, included, s, perLevel, levels);
            rootCoverageByLevel[s] = accuracyOn(rootCovered, included, s, perLevel, levels);
            rootAccuracyByLevel[s] = accuracyOn(rootCorrect, rootIncluded, s, perLevel, levels);
            top2ByLevel[s] = accuracyOn(top2Correct, included, s, perLevel, levels);
        }
        double cleanUncovered = 1.0 - coverageByLevel[protocol.snrLevels().indexOf(CLEAN_SNR_DB)];
        int abstained = 0;
        int coarseOnly = 0;
        long comparisons = 0;
        long candidates = 0;
        long levelsVisited = 0;
        for (Decisions c : answers.c()) {
            abstained += c.abstentions;
            coarseOnly += c.coarseOnly;
            comparisons += c.comparisons;
            candidates += c.candidates;
            levelsVisited += c.levels;
        }
        Reliability forcedReliability = selective.reliability(forcedScores, correctness[4], included);
        Reliability coveredReliability = selective.reliability(coveredScores, correctness[0], coveredIncluded);
        Reliability rootReliability = selective.reliability(rootScores, rootCorrect, rootIncluded);
        double[] riskCoverage = selective.riskCoverage(forcedScores, correctness[4], included);
        double abstentionPrecision = rate(forcedWrong, uncoveredLeaf, included);
        double abstentionRecall = rate(uncoveredLeaf, forcedWrong, included);

        boolean gate = h3.lower() > protocol.h3Margin() && coverage >= protocol.h3MinCoverage()
                && h6.lower() > protocol.h6Margin() && noiseUncovered > cleanUncovered;
        return new RepeatResult(r + 1, seed, splits, answers, pooled, h3, h4, h6, coverageInterval, cAccuracy,
                aAccuracy, forcedAccuracy, flatAccuracy, coverage, flatCoverage, armAccuracy, coverageByLevel,
                rootCoverageByLevel, rootAccuracyByLevel, top2ByLevel, cleanUncovered, abstained, coarseOnly,
                comparisons, candidates, levelsVisited, forcedReliability, coveredReliability, rootReliability,
                riskCoverage, abstentionPrecision, abstentionRecall, gate);
    }

    /** Returns the depth-0 ancestor of a concept (the concept itself for a root). */
    private int rootOf(int ordinal) {
        int current = ordinal;
        while (!hierarchy.node(current).isRoot()) {
            current = hierarchy.node(current).parentOrdinal();
        }
        return current;
    }

    /** Accuracy of {@code correct} over {@code included} items of level {@code s} ({@code s == levels}: pooled). */
    private double accuracyOn(boolean[] correct, boolean[] included, int s, int perLevel, int levels) {
        int from = s == levels ? 0 : s * perLevel;
        int to = s == levels ? levels * perLevel : (s + 1) * perLevel;
        int count = 0;
        int right = 0;
        for (int i = from; i < to; i++) {
            if (included[i]) {
                count++;
                right += correct[i] ? 1 : 0;
            }
        }
        return count == 0 ? 0.0 : right / (double) count;
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

    private boolean[] positive(double[] values) {
        var flags = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            flags[i] = values[i] > 0.0;
        }
        return flags;
    }

    /**
     * A sequence slot is one cluster: it contributes its windows at every SNR level, whose clean windows are shared.
     * Window {@code w} of level {@code s} is item {@code s · perLevel + w}.
     */
    private int[][] clusters(int levels, int perLevel) {
        int length = protocol.windowsPerSequence();
        int sequences = perLevel / length;
        var clusters = new int[sequences][levels * length];
        for (int q = 0; q < sequences; q++) {
            int at = 0;
            for (int s = 0; s < levels; s++) {
                for (int p = 0; p < length; p++) {
                    clusters[q][at++] = s * perLevel + q * length + p;
                }
            }
        }
        return clusters;
    }

    /** Leaf ordinal of a window's ground truth; reads the (possibly guarded) labels. */
    private int label(Split split, int w) {
        return split.split().labels().label(w).ordinal();
    }

    /** Leaf ordinal of the next window in the same sequence, or {@link #NO_CONCEPT} for a sequence's last window. */
    private int nextLabel(Split split, int w) {
        return (w + 1) % protocol.windowsPerSequence() == 0 ? NO_CONCEPT : label(split, w + 1);
    }

    /** The fraction of windows with a successor that C leaves uncovered at the leaf depth (N11 needs no labels). */
    private double uncoveredRate(Decisions decisions) {
        int count = 0;
        int uncovered = 0;
        for (int w = 0; w < decisions.leaf.length; w++) {
            if ((w + 1) % protocol.windowsPerSequence() == 0) {
                continue;
            }
            count++;
            uncovered += decisions.leaf[w] == NO_CONCEPT ? 1 : 0;
        }
        return uncovered / (double) count;
    }

    // ---------------------------------------------------------------- data

    private List<Split> splits(SplitRole role, long seed) {
        var splits = new ArrayList<Split>();
        for (double snr : protocol.snrLevels()) {
            ConceptSplit split = generator.generate(role, seed, snr, protocol.sequences(),
                    protocol.windowsPerSequence());
            splits.add(new Split(split, patterns(split)));
        }
        return splits;
    }

    private SpectralPattern[] patterns(ConceptSplit split) {
        var patterns = new SpectralPattern[split.windows().size()];
        for (int w = 0; w < patterns.length; w++) {
            patterns[w] = decompose(split.windows().get(w).wave());
        }
        return patterns;
    }

    /**
     * Fixture N11: windows of seeded unit-variance Gaussian noise with no concept, in the medium workload's sequence
     * layout. They carry no labels.
     */
    private Split noiseSplit() {
        var random = new Random(protocol.noiseSeed());
        int n = protocol.sequences() * protocol.windowsPerSequence();
        var patterns = new SpectralPattern[n];
        for (int w = 0; w < n; w++) {
            var samples = new double[ConceptFixtureGenerator.SAMPLE_COUNT];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = random.nextGaussian();
            }
            patterns[w] = decompose(new SampledWave(samples, ConceptFixtureGenerator.SAMPLE_RATE, SampleUnit.HERTZ,
                    new WaveProvenance("ncm-noise-n11", 1, "seed-" + Long.toHexString(protocol.noiseSeed()) + "/w"
                            + w, (long) w * samples.length)));
        }
        return new Split(null, patterns);
    }

    private SpectralPattern decompose(SampledWave wave) {
        return switch (decomposer.decompose(wave)) {
            case DecompositionResult.Decomposed decomposed -> decomposed.pattern();
            case DecompositionResult.DecompositionRejected rejected ->
                    throw new IllegalStateException("a fixture window was rejected: " + rejected);
        };
    }

    // ---------------------------------------------------------------- checks

    /**
     * Fixtures of #127 on the trained model: P15 (a clean window of each leaf, dominant tone 12 Hz or 72 Hz and chord
     * tone 18 Hz or 108 Hz, so a chord tone lies outside its pure band; previous concept that leaf, no thresholds;
     * predicts the leaf's category then the leaf), N9 (an unknown previous code abstains with
     * {@code UNKNOWN_CONCEPT}) and N12 (a cyclic hierarchy is rejected). P16 and N10 are unit-tested in core.
     */
    private Check fixtureCheck(ConceptModel model) {
        ConceptModel open = model.withThresholds(ConceptThresholds.NONE);
        double[][] tones = {{12.0}, {12.0, 18.0}, {72.0}, {72.0, 108.0}};
        int p15 = 0;
        var outcomes = new ArrayList<String>();
        for (int i = 0; i < tones.length; i++) {
            int leaf = hierarchy.leaf(i);
            var samples = new double[ConceptFixtureGenerator.SAMPLE_COUNT];
            for (int n = 0; n < samples.length; n++) {
                for (int t = 0; t < tones[i].length; t++) {
                    samples[n] += (t == 0 ? 1.0 : 0.6) * StrictMath.cos(2.0 * StrictMath.PI * tones[i][t] * n
                            / ConceptFixtureGenerator.SAMPLE_RATE);
                }
            }
            SpectralPattern pattern = decompose(new SampledWave(samples, ConceptFixtureGenerator.SAMPLE_RATE,
                    SampleUnit.HERTZ, new WaveProvenance("ncm-fixture-p15", 1, "leaf-" + i, 0L)));
            ConceptPrediction prediction = predictor.predict(new ConceptQuery(pattern,
                    OptionalLong.of(hierarchy.node(leaf).conceptCode())), open, PredictionBudget.DEFAULT);
            if (prediction instanceof ConceptCandidates candidates && !candidates.coarseOnly()
                    && candidates.levels().get(0).top().conceptOrdinal() == hierarchy.node(leaf).parentOrdinal()
                    && candidates.selected().conceptOrdinal() == leaf) {
                p15++;
            }
            outcomes.add(FixtureConcept.values()[leaf].name() + " -> " + switch (prediction) {
                case ConceptCandidates candidates -> candidates.levels().stream()
                        .map(level -> FixtureConcept.values()[level.top().conceptOrdinal()].name()).toList()
                        .toString();
                case ConceptAbstention abstention -> abstention.reason().name();
            });
        }
        ConceptPrediction unknown = predictor.predict(new ConceptQuery(decompose(new SampledWave(
                new double[ConceptFixtureGenerator.SAMPLE_COUNT], ConceptFixtureGenerator.SAMPLE_RATE,
                SampleUnit.HERTZ, new WaveProvenance("ncm-fixture-n9", 1, "silent", 0L))), OptionalLong.of(99)),
                open, PredictionBudget.DEFAULT);
        boolean n9 = unknown instanceof ConceptAbstention abstention
                && abstention.reason() == AbstentionReason.UNKNOWN_CONCEPT;
        boolean n12;
        try {
            ConceptHierarchy.of(DOMAIN, List.of(new ConceptNode(0, 1, ConceptNode.ROOT, 0),
                    new ConceptNode(1, 2, 2, 1), new ConceptNode(2, 3, 1, 1)));
            n12 = false;
        } catch (IllegalArgumentException expected) {
            n12 = true;
        }
        return new Check("fixtures.p15-n9-n12", p15 == tones.length && n9 && n12, "P15 " + p15 + "/"
                + tones.length + " leaves predicted coarse then fine " + outcomes + "; N9 " + (n9 ? "UNKNOWN_CONCEPT" : "NOT ABSTAINED")
                + "; N12 cyclic hierarchy " + (n12 ? "rejected" : "ACCEPTED"));
    }

    /**
     * Training, threshold-selection and test splits have distinct seeds and share no clean window. The generator
     * draws a split's tones independently of its SNR, so one seed gives the same clean windows at every SNR level and
     * the first level covers all clean content; the label-free N11 windows come from their own seed and carry none.
     */
    private Check disjointnessCheck(List<Split> training, List<RepeatResult> repeats) {
        var seedList = new ArrayList<Long>();
        var splitList = new ArrayList<ConceptSplit>();
        seedList.add(protocol.trainingSeed());
        splitList.add(training.getFirst().split());
        seedList.add(protocol.thresholdSeed());
        splitList.add(generator.generate(SplitRole.THRESHOLD_SELECTION, protocol.thresholdSeed(),
                protocol.snrLevels().getFirst(), protocol.sequences(), protocol.windowsPerSequence()));
        for (RepeatResult repeat : repeats) {
            seedList.add(repeat.seed());
            splitList.add(repeat.splits().getFirst().split());
        }
        return new SpectralResonanceH2Evaluation(SpectralResonanceH2Evaluation.REGISTERED, true)
                .disjointnessCheck(seedList, splitList);
    }

    /**
     * Re-answers the first repeat with permuted held-out labels. Every arm's answers must be bit-identical (no arm
     * read a label) and C's covered accuracy must change (the labels decide the scorer, so the check is not vacuous).
     */
    private Check counterfactualCheck(RepeatResult first, Selection selection, List<SplitLabels> labelSets) {
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
        Answers again = answer(permuted, selection);
        boolean identical = first.answers().sameAs(again);
        for (Split split : permuted) {
            split.split().labels().openForScoring();
        }
        RepeatResult counterfactual = score(0, first.seed(), permuted, again, selection, 0.0);
        boolean changed = counterfactual.cAccuracy() != first.cAccuracy();
        return new Check("leakage.counterfactual", identical && changed,
                "repeat 1 re-answered with permuted held-out labels: answers " + (identical ? "bit-identical"
                        : "CHANGED") + "; C covered accuracy " + (changed ? "changed" : "unchanged (vacuous)"));
    }

    /**
     * H4 changes only the decision: on the first repeat, C-flat must carry C's previous concept and score table on
     * every window. Independently, every window where the two arms answer differently is re-scored by a fresh
     * unthresholded predictor call with C's previous concept, and must give an equal score table.
     */
    private Check pairedInputsCheck(RepeatResult first, Selection selection) {
        ConceptModel unthresholded = selection.model().withThresholds(ConceptThresholds.NONE);
        int windows = 0;
        int shared = 0;
        int audited = 0;
        int matched = 0;
        for (int s = 0; s < first.splits().size(); s++) {
            Split split = first.splits().get(s);
            Decisions c = first.answers().c().get(s);
            Decisions flat = first.answers().flat().get(s);
            for (int w = 0; w < c.windows(); w++) {
                windows++;
                shared += c.scores[w] == flat.scores[w] && c.previous[w].equals(flat.previous[w]) ? 1 : 0;
                if (c.leaf[w] == flat.leaf[w]) {
                    continue;
                }
                audited++;
                ConceptPrediction again = predictor.predict(new ConceptQuery(split.patterns()[w], c.previous[w]),
                        unthresholded, PredictionBudget.DEFAULT);
                matched += again.scores().orElse(null) instanceof ConceptScores scores && scores.equals(c.scores[w])
                        ? 1 : 0;
            }
        }
        return new Check("h4.paired-inputs", shared == windows && matched == audited,
                "repeat 1: C-flat shares C's previous concept and score table on " + shared + "/" + windows
                        + " windows; " + matched + "/" + audited + " windows with different answers re-scored "
                        + "identically");
    }

    /** Regenerates and re-answers the first repeat and re-runs its bootstrap; everything must be bit-identical. */
    private Check determinismCheck(RepeatResult first, Selection selection, List<SplitLabels> labelSets,
                                   double noiseUncovered) {
        List<Split> splits = splits(SplitRole.TEST, first.seed());
        for (Split split : splits) {
            labelSets.add(split.split().labels());
        }
        Answers again = answer(splits, selection);
        for (Split split : splits) {
            split.split().labels().openForScoring();
        }
        RepeatResult rerun = score(0, first.seed(), splits, again, selection, noiseUncovered);
        boolean identical = first.answers().sameAs(again) && Arrays.deepEquals(first.pooled(), rerun.pooled());
        return new Check("determinism", identical, "repeat 1 regenerated, re-answered and re-bootstrapped: "
                + (identical ? "bit-identical" : "DIFFERENT"));
    }

    // ---------------------------------------------------------------- report

    private Map<String, String> metadata(Selection selection, List<RepeatResult> repeats, Decisions noise,
                                         double noiseUncovered, boolean go, boolean h4) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("contract", CONTRACT);
        metadata.put("generator.version", ConceptFixtureGenerator.VERSION);
        metadata.put("protocol.margins", "H3 " + format(protocol.h3Margin()) + " (min leaf coverage "
                + format(protocol.h3MinCoverage()) + "), H6 " + format(protocol.h6Margin()) + ", H4 "
                + format(protocol.h4Margin()) + " (informational)");
        metadata.put("protocol.gateRule", "go iff in every repeat the 95% paired-bootstrap lower bounds of "
                + "acc(C) - acc(A) on C-covered windows and acc(C covered) - acc(C forced) exceed their margins, "
                + "C's pooled leaf coverage reaches the minimum, and N11 leaves more windows uncovered than 20 dB");
        metadata.put("protocol.selectionCoverage", format(protocol.selectionCoverage()));
        metadata.put("protocol.bootstrapResamples", Integer.toString(protocol.resamples()));
        metadata.put("protocol.bootstrapSeed", hex(protocol.bootstrapSeed()) + " + repeat");
        metadata.put("protocol.snrLevelsDb", protocol.snrLevels().toString());
        metadata.put("protocol.trainingSeed", hex(protocol.trainingSeed()));
        metadata.put("protocol.thresholdSeed", hex(protocol.thresholdSeed()));
        metadata.put("protocol.testSeeds", protocol.testSeeds().stream().map(NextConceptG3Evaluation::hex)
                .toList().toString());
        metadata.put("protocol.noiseSeed", hex(protocol.noiseSeed()));
        metadata.put("protocol.windows", protocol.sequences() + " sequences x " + protocol.windowsPerSequence()
                + " windows");
        metadata.put("protocol.registered", Boolean.toString(protocol.equals(REGISTERED)));
        metadata.put("c.features", "phase-blind unit DFT magnitude spectrum; no resonance features (G2 no-go)");
        metadata.put("selection.temperature", Double.toString(selection.model().temperature()));
        metadata.put("selection.temperatureNll", Arrays.toString(selection.temperatureNll()));
        metadata.put("selection.thresholds", selection.model().thresholds().toString());
        metadata.put("selection.thresholdSplit", "leaf coverage " + format(selection.coverage()) + ", accuracy "
                + format(selection.accuracy()) + (selection.reached() ? "" : " (target coverage NOT reached)"));
        metadata.put("selection.flat", "confidence " + format(selection.flatConfidence()) + ", margin "
                + format(selection.flatMargin()) + ", coverage " + format(selection.flatCoverage()) + ", accuracy "
                + format(selection.flatAccuracy()));
        metadata.put("controlA.answer", FixtureConcept.values()[selection.mostFrequentNext()].name());
        metadata.put("model.retainedBytes", Long.toString(selection.model().retainedBytes()));
        metadata.put("n11.uncoveredRate", format(noiseUncovered) + " of " + noise.windows() + " noise windows ("
                + noise.abstentions + " abstentions, " + noise.coarseOnly + " coarse-only; any leaf answer is an error)");
        for (RepeatResult repeat : repeats) {
            String prefix = "g3.repeat" + repeat.number() + ".";
            metadata.put(prefix + "h3", format(repeat.cAccuracy() - repeat.aAccuracy()) + " "
                    + format(repeat.h3()));
            metadata.put(prefix + "h6", format(repeat.cAccuracy() - repeat.forcedAccuracy()) + " "
                    + format(repeat.h6()));
            metadata.put(prefix + "h4", format(repeat.cAccuracy() - repeat.flatAccuracy()) + " "
                    + format(repeat.h4()));
            metadata.put(prefix + "coverage", format(repeat.coverage()) + " " + format(repeat.coverageInterval()));
            metadata.put(prefix + "gate", repeat.gatePassed() ? "pass" : "fail");
        }
        metadata.put("h4.verdict", h4 ? "holds" : "not supported (informational)");
        metadata.put("g3.verdict", go ? "GO" : "NO-GO (stop before #128)");
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

    private static double[] grid(int count, double step) {
        var values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = StrictMath.rint(i * step * 100.0) / 100.0;
        }
        return values;
    }

    // ---------------------------------------------------------------- values

    /** One generated split and its decomposed windows; {@code split} is {@code null} for the label-free N11 set. */
    record Split(ConceptSplit split, SpectralPattern[] patterns) {
    }

    /**
     * Per-window decisions of C, with the previous concept each query carried and the score table it returned
     * ({@code null} for an early abstention). C-flat fills only {@code leaf} and {@code leafScore}, and shares C's
     * previous concepts, score tables and work.
     */
    static final class Decisions {
        final int[] leaf;
        final int[] root;
        final int[] forced;
        final int[] forcedSecond;
        final double[] leafScore;
        final double[] forcedScore;
        final double[] rootScore;
        final OptionalLong[] previous;
        final ConceptScores[] scores;
        int abstentions;
        int coarseOnly;
        long comparisons;
        long candidates;
        long levels;

        Decisions(int windows) {
            leaf = new int[windows];
            root = new int[windows];
            forced = new int[windows];
            forcedSecond = new int[windows];
            leafScore = new double[windows];
            forcedScore = new double[windows];
            rootScore = new double[windows];
            Arrays.fill(leaf, NO_CONCEPT);
            Arrays.fill(root, NO_CONCEPT);
            Arrays.fill(forced, NO_CONCEPT);
            Arrays.fill(forcedSecond, NO_CONCEPT);
            previous = new OptionalLong[windows];
            scores = new ConceptScores[windows];
            Arrays.fill(previous, OptionalLong.empty());
        }

        int windows() {
            return leaf.length;
        }

        int[] leaf() {
            return leaf;
        }

        void record(int w, OptionalLong queried, ConceptPrediction prediction, ConceptHierarchy hierarchy) {
            previous[w] = queried;
            scores[w] = prediction.scores().orElse(null);
            comparisons += prediction.work().comparisons();
            candidates += prediction.work().candidatesScored();
            levels += prediction.work().levelsVisited();
            switch (prediction) {
                case ConceptCandidates c -> {
                    root[w] = c.levels().getFirst().top().conceptOrdinal();
                    rootScore[w] = c.levels().getFirst().top().score();
                    if (c.coarseOnly()) {
                        coarseOnly++;
                    } else {
                        leaf[w] = c.selected().conceptOrdinal();
                        leafScore[w] = c.scoreTable().nextScore(leaf[w]);
                    }
                }
                case ConceptAbstention ignored -> abstentions++;
            }
            if (prediction.scores().isPresent()) {
                ConceptScores scores = prediction.scores().get();
                for (int i = 0; i < hierarchy.leafCount(); i++) {
                    int candidate = hierarchy.leaf(i);
                    double score = scores.nextScore(candidate);
                    if (forced[w] == NO_CONCEPT || score > scores.nextScore(forced[w])) {
                        forcedSecond[w] = forced[w];
                        forced[w] = candidate;
                    } else if (forcedSecond[w] == NO_CONCEPT || score > scores.nextScore(forcedSecond[w])) {
                        forcedSecond[w] = candidate;
                    }
                }
                forcedScore[w] = scores.nextScore(forced[w]);
            }
        }

        boolean sameAs(Decisions other) {
            return Arrays.equals(leaf, other.leaf) && Arrays.equals(root, other.root)
                    && Arrays.equals(forced, other.forced) && Arrays.equals(forcedSecond, other.forcedSecond)
                    && Arrays.equals(leafScore, other.leafScore) && Arrays.equals(forcedScore, other.forcedScore)
                    && Arrays.equals(rootScore, other.rootScore) && Arrays.equals(previous, other.previous)
                    && Arrays.equals(scores, other.scores);
        }
    }

    /** Every arm's answers for one repeat, per SNR level (A and A-oracle are fixed rules and need no storage). */
    record Answers(List<Decisions> c, List<Decisions> flat, List<int[]> b) {

        boolean sameAs(Answers other) {
            for (int s = 0; s < c.size(); s++) {
                if (!c.get(s).sameAs(other.c().get(s)) || !flat.get(s).sameAs(other.flat().get(s))
                        || !Arrays.equals(b.get(s), other.b().get(s))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Outcome of training and selection on non-test data.
     *
     * @param model trained model with C's thresholds
     * @param temperatureNll training negative log-likelihood per {@link #TEMPERATURES} entry
     * @param mostFrequentNext control A's leaf ordinal
     * @param coverage C's leaf coverage on the threshold-selection split
     * @param accuracy C's covered leaf accuracy on the threshold-selection split
     * @param reached whether a grid point reached the selection coverage
     * @param flatConfidence C-flat confidence threshold
     * @param flatMargin C-flat margin threshold
     * @param flatCoverage C-flat coverage on the threshold-selection split
     * @param flatAccuracy C-flat covered accuracy on the threshold-selection split
     */
    record Selection(ConceptModel model, double[] temperatureNll, int mostFrequentNext, double coverage,
                     double accuracy, boolean reached, double flatConfidence, double flatMargin, double flatCoverage,
                     double flatAccuracy) {
    }

    /**
     * Everything one repeat produced.
     *
     * @param armAccuracy per arm in {@code ARMS} order, accuracy per SNR level and pooled (last); C on its covered
     *     windows, C-flat on its covered windows, the others on every answerable window
     */
    record RepeatResult(int number, long seed, List<Split> splits, Answers answers, double[][] pooled,
                        Interval h3, Interval h4, Interval h6, Interval coverageInterval, double cAccuracy,
                        double aAccuracy, double forcedAccuracy, double flatAccuracy, double coverage,
                        double flatCoverage, double[][] armAccuracy, double[] coverageByLevel,
                        double[] rootCoverageByLevel, double[] rootAccuracyByLevel, double[] top2ByLevel,
                        double cleanUncovered, int abstentions, int coarseOnly, long comparisons, long candidates,
                        long levelsVisited, Reliability forcedReliability, Reliability coveredReliability,
                        Reliability rootReliability, double[] riskCoverage, double abstentionPrecision,
                        double abstentionRecall, boolean gatePassed) {
    }

    /**
     * Result of a run.
     *
     * @param checks semantic checks
     * @param selection training and selection outcome
     * @param repeats per-repeat results
     * @param noiseWindows N11 windows
     * @param noiseUncovered N11 leaf non-coverage rate
     * @param go the G3 verdict
     * @param h4 whether H4 holds (informational)
     * @param metadata report metadata
     */
    record Outcome(List<EvaluationCheck> checks, Selection selection, List<RepeatResult> repeats, int noiseWindows,
                   double noiseUncovered, boolean go, boolean h4, Map<String, String> metadata) {
    }

    /**
     * Seeds, sizes, margins and the selection target of one G3 run.
     *
     * @param trainingSeed training split seed
     * @param thresholdSeed threshold-selection split seed
     * @param testSeeds one held-out seed per repeat
     * @param bootstrapSeed base bootstrap seed; repeat {@code r} (1-based) uses {@code bootstrapSeed + r}
     * @param resamples bootstrap resamples {@code B}
     * @param snrLevels SNR levels in decibels
     * @param sequences sequences per split
     * @param windowsPerSequence windows per sequence
     * @param noiseSeed seed of the N11 noise windows
     * @param h3Margin pre-registered H3 accuracy margin
     * @param h3MinCoverage pre-registered H3 minimum leaf coverage
     * @param h6Margin pre-registered H6 accuracy margin
     * @param h4Margin pre-registered H4 accuracy margin (informational)
     * @param selectionCoverage leaf coverage C's thresholds must reach on the threshold-selection split
     */
    record Protocol(long trainingSeed, long thresholdSeed, List<Long> testSeeds, long bootstrapSeed, int resamples,
                    List<Double> snrLevels, int sequences, int windowsPerSequence, long noiseSeed, double h3Margin,
                    double h3MinCoverage, double h6Margin, double h4Margin, double selectionCoverage) {

        /**
         * Copies the lists and requires at least one repeat, the {@link #CLEAN_SNR_DB} level the N11 rule compares
         * against, one resample and sequences of at least two windows.
         */
        Protocol {
            testSeeds = List.copyOf(testSeeds);
            snrLevels = List.copyOf(snrLevels);
            if (testSeeds.isEmpty() || !snrLevels.contains(CLEAN_SNR_DB) || resamples < 1 || windowsPerSequence < 2) {
                throw new IllegalArgumentException("a protocol needs a repeat, the " + CLEAN_SNR_DB + " dB level, a "
                        + "resample and sequences of at least two windows");
            }
        }

        /** Returns the smoke-mode protocol: the first repeat only, 8 sequences and 200 resamples. */
        Protocol quick() {
            return new Protocol(trainingSeed, thresholdSeed, testSeeds.subList(0, 1), bootstrapSeed, 200, snrLevels,
                    Math.min(8, sequences), windowsPerSequence, noiseSeed, h3Margin, h3MinCoverage, h6Margin,
                    h4Margin, selectionCoverage);
        }
    }
}
