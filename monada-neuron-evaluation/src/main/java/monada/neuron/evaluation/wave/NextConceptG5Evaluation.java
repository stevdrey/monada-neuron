package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.wave.ConceptFixtureGenerator.ConceptSplit;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Decisions;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Selection;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Split;
import monada.neuron.evaluation.wave.RankStatistics.Interval;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Check;
import monada.neuron.reasoning.concept.ConceptFeedbackLimits;
import monada.neuron.reasoning.concept.ConceptFeedbackPolicy;
import monada.neuron.reasoning.concept.ConceptFeedbackRejection;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.ConceptModelTrainer;
import monada.neuron.reasoning.concept.ConceptModelUpdate;
import monada.neuron.reasoning.concept.ConceptOutcome;
import monada.neuron.reasoning.concept.ConceptPrediction;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptAbstention;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptCandidates;
import monada.neuron.reasoning.concept.ConceptPredictionReceipt;
import monada.neuron.reasoning.concept.ConceptQuery;
import monada.neuron.reasoning.concept.LabeledSequence;
import monada.neuron.reasoning.concept.NextConceptPredictor;
import monada.neuron.reasoning.concept.PredictionBudget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Random;

import static monada.neuron.evaluation.wave.NextConceptG3Evaluation.format;

/**
 * Hypothesis H5 of contract {@code ncm-wave-cognition/2} (gate G5, first part): does validated outcome feedback
 * (control E: C plus {@link ConceptFeedbackPolicy}) improve C's next-concept predictions on later held-out windows,
 * without label leakage?
 *
 * <p>The protocol is fixed in {@code docs/benchmarks/next-concept-g5.md} and mirrored by {@link #REGISTERED}. C is
 * trained and thresholded exactly as in G3 (the same {@link NextConceptG3Evaluation} code, seeds and grids). In each
 * repeat, E starts from C's snapshot and streams a disjoint FEEDBACK split in order: it predicts each window, then a
 * host verifier reveals the next window's label and pattern as a {@link ConceptOutcome}, and the policy returns the
 * snapshot used for the next step. The final snapshot is frozen and answers the held-out TEST split, which feedback
 * never reads: test labels are guarded and opened only by the scorer, after every arm has answered.
 *
 * <p>Arms: C (no feedback), E (validated feedback), E-shuffled (FEEDBACK labels permuted: wrong feedback), E-unverified
 * (every outcome {@code UNVERIFIED}: must equal C), C-replay (C retrained on training plus the full FEEDBACK split:
 * "more labeled data" without the feedback gate) and E at 25% and 50% of the feedback stream (drift curve). The H5
 * verdict is data: a negative result is a valid outcome, not a failed check.
 */
final class NextConceptG5Evaluation {

    /** Contract identifier recorded in every report. */
    static final String CONTRACT = "ncm-wave-cognition/2";

    /** Identity of the evaluation's trusted verifier. */
    static final String VERIFIER = "ncm-g5.fixture-verifier";

    /** The pre-registered protocol. */
    static final Protocol REGISTERED = new Protocol(0x1250_0001L, 0x1250_0002L,
            List.of(0x1290_F001L, 0x1290_F002L, 0x1290_F003L, 0x1290_F004L, 0x1290_F005L),
            List.of(0x1290_1001L, 0x1290_1002L, 0x1290_1003L, 0x1290_1004L, 0x1290_1005L),
            0x1290_B005L, 2_000, List.of(20.0, 10.0, 0.0),
            ConceptFixtureGenerator.SEQUENCES, ConceptFixtureGenerator.WINDOWS_PER_SEQUENCE,
            0.02, 0.60, 0.70, ConceptFeedbackLimits.DEFAULT_MAX_FEEDBACK_SHARE,
            ConceptFeedbackLimits.DEFAULT_MAX_EVIDENCE_AGE);

    /** Fractions of the feedback stream after which E answers the test split for the drift curve. */
    static final double[] CHECKPOINTS = {0.25, 0.50};

    private static final long SHUFFLE_SALT = 0x5348_5546L;
    private static final long PERMUTATION_SALT = 0x5045_524DL;
    private static final int NO_CONCEPT = -1;
    private static final String FEEDBACK_ROLE = "/" + SplitRole.FEEDBACK + "/";
    private static final String TEST_ROLE = "/" + SplitRole.TEST + "/";

    private final Protocol protocol;
    private final boolean quick;
    private final NextConceptG3Evaluation g3;
    private final ConceptFeedbackPolicy policy;
    private final ConceptModelTrainer trainer = new ConceptModelTrainer();
    private final NextConceptPredictor predictor = new NextConceptPredictor();
    private final RankStatistics statistics = new RankStatistics();
    private final SeedDerivation seeds = new SeedDerivation();
    private final ConceptHierarchy hierarchy;

    NextConceptG5Evaluation(Protocol protocol, boolean quick) {
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.quick = quick;
        this.g3 = new NextConceptG3Evaluation(protocol.g3Protocol(), quick);
        this.hierarchy = g3.fixtureHierarchy();
        this.policy = new ConceptFeedbackPolicy(new ConceptFeedbackLimits(1, protocol.maxEvidenceAge(),
                protocol.maxFeedbackShare(), List.of(VERIFIER)));
    }

    /** Returns the run configuration recorded in the report. */
    RunConfiguration runConfiguration() {
        return new RunConfiguration(protocol.testSeeds().getFirst(), quick, 0, 1,
                "one deterministic pass; no timing gate");
    }

    /** Trains and selects C as G3 does, runs every repeat, the checks and the gate. */
    Outcome run() {
        List<Split> training = g3.splits(SplitRole.TRAINING, protocol.trainingSeed());
        Selection selection = g3.select(training);
        List<LabeledSequence> trainingSequences = g3.labeledSequences(training);

        var labelSets = new ArrayList<SplitLabels>();
        var repeats = new ArrayList<RepeatResult>();
        for (int r = 0; r < protocol.testSeeds().size(); r++) {
            repeats.add(repeat(r, selection, trainingSequences, labelSets));
        }
        RepeatResult first = repeats.getFirst();
        var checks = new ArrayList<EvaluationCheck>();
        checks.add(disjointnessCheck(training, repeats));
        checks.add(provenanceCheck(repeats));
        checks.add(unverifiedCheck(selection, repeats));
        checks.add(duplicateCheck(first));
        checks.add(replayCheck(first, selection));
        checks.add(counterfactualCheck(first, selection, labelSets));
        checks.add(determinismCheck(first, selection, trainingSequences, labelSets));
        int earlyReads = labelSets.stream().mapToInt(SplitLabels::earlyReads).sum();
        checks.add(new Check("leakage.test-labels", earlyReads == 0,
                labelSets.size() + " guarded held-out label sets; " + earlyReads
                        + " reads before the scorer opened them"));

        boolean holds = repeats.stream().allMatch(RepeatResult::gatePassed);
        return new Outcome(checks, selection, repeats, holds, metadata(selection, repeats, holds));
    }

    // ---------------------------------------------------------------- feedback stream

    /** How the evaluation's verifier reports each step of the feedback stream. */
    enum Feedback {
        /** The true next label and pattern, verified. */
        VERIFIED,
        /** A permuted label with the true pattern, verified: wrong feedback through a trusted verifier. */
        SHUFFLED,
        /** No label: every outcome {@code UNVERIFIED}. */
        UNVERIFIED
    }

    /**
     * Streams the FEEDBACK splits in level, sequence and window order from C's snapshot. At each window with a
     * successor in its sequence the current snapshot predicts (previous concept: its own earlier selection, as in
     * C); an answered prediction gets a receipt, the verifier reports the successor's label and pattern with the
     * current window's label as verified previous leaf, and the policy's result is the snapshot of the next step.
     * Abstentions get no receipt and no feedback: attribution needs a prediction.
     */
    FeedbackRun stream(ConceptModel start, List<Split> splits, Feedback mode, long feedbackSeed) {
        int length = protocol.windowsPerSequence();
        int steps = 0;
        for (Split split : splits) {
            steps += split.patterns().length / length * (length - 1);
        }
        int[] checkpointSteps = new int[CHECKPOINTS.length];
        for (int k = 0; k < CHECKPOINTS.length; k++) {
            checkpointSteps[k] = (int) StrictMath.floor(CHECKPOINTS[k] * steps);
        }
        var checkpoints = new ArrayList<ConceptModel>();
        var rejections = new int[ConceptFeedbackRejection.values().length];
        var appliedReferences = new ArrayList<String>();
        ConceptModel current = start;
        ConceptOutcome firstApplied = null;
        ConceptModel beforeFirst = null;
        long ordinal = 0;
        int step = 0;
        int outcomes = 0;
        int applied = 0;
        int positive = 0;
        long copiedBytes = 0;
        long maxCopiedBytes = 0;
        for (int s = 0; s < splits.size(); s++) {
            Split split = splits.get(s);
            SplitLabels labels = mode == Feedback.SHUFFLED
                    ? split.split().labels().permuted(new Random(seeds.derive(feedbackSeed, SHUFFLE_SALT, s)))
                    : split.split().labels();
            labels.openForScoring();
            for (int begin = 0; begin < split.patterns().length; begin += length) {
                OptionalLong forPrevious = OptionalLong.empty();
                OptionalLong forCurrent = OptionalLong.empty();
                for (int w = begin; w < begin + length - 1; w++) {
                    var query = new ConceptQuery(split.patterns()[w], forPrevious);
                    ConceptPrediction prediction = predictor.predict(query, current, PredictionBudget.DEFAULT);
                    forPrevious = forCurrent;
                    forCurrent = switch (prediction) {
                        case ConceptCandidates candidates ->
                                OptionalLong.of(hierarchy.node(candidates.selected().conceptOrdinal()).conceptCode());
                        case ConceptAbstention ignored -> OptionalLong.empty();
                    };
                    if (prediction instanceof ConceptCandidates candidates) {
                        var receipt = ConceptPredictionReceipt.of(current, query, candidates);
                        ConceptOutcome outcome = mode == Feedback.UNVERIFIED
                                ? ConceptOutcome.unverified(receipt, ConceptOutcome.Verification.UNVERIFIED, ordinal,
                                        VERIFIER)
                                : ConceptOutcome.verified(receipt, labels.label(w + 1).code(), split.patterns()[w + 1],
                                        OptionalLong.of(labels.label(w).code()), ordinal, VERIFIER);
                        ConceptModelUpdate update = policy.apply(current, List.of(outcome), ordinal + 1);
                        outcomes++;
                        for (ConceptModelUpdate.Rejected rejected : update.rejected()) {
                            rejections[rejected.reason().ordinal()]++;
                        }
                        if (update instanceof ConceptModelUpdate.Updated updated) {
                            if (firstApplied == null) {
                                firstApplied = outcome;
                                beforeFirst = current;
                            }
                            applied += updated.work().applied();
                            positive += updated.work().positive();
                            copiedBytes += updated.work().copiedBytes();
                            maxCopiedBytes = Math.max(maxCopiedBytes, updated.work().copiedBytes());
                            appliedReferences.add(outcome.receipt().sourceReference());
                            appliedReferences.add(outcome.observedEvidence().provenance().sourceReference());
                        }
                        current = update.current();
                    }
                    ordinal++;
                    step++;
                    for (int k = 0; k < checkpointSteps.length; k++) {
                        if (step == checkpointSteps[k]) {
                            checkpoints.add(current);
                        }
                    }
                }
            }
        }
        return new FeedbackRun(current, checkpoints, steps, outcomes, applied, positive, rejections, copiedBytes,
                maxCopiedBytes, appliedReferences, firstApplied, beforeFirst);
    }

    // ---------------------------------------------------------------- repeats and scoring

    private RepeatResult repeat(int r, Selection selection, List<LabeledSequence> trainingSequences,
                                List<SplitLabels> labelSets) {
        long feedbackSeed = protocol.feedbackSeeds().get(r);
        long testSeed = protocol.testSeeds().get(r);
        List<Split> feedback = g3.splits(SplitRole.FEEDBACK, feedbackSeed);
        ConceptModel c = selection.model();
        FeedbackRun e = stream(c, feedback, Feedback.VERIFIED, feedbackSeed);
        FeedbackRun shuffled = stream(c, feedback, Feedback.SHUFFLED, feedbackSeed);
        FeedbackRun unverified = stream(c, feedback, Feedback.UNVERIFIED, feedbackSeed);
        var replaySequences = new ArrayList<>(trainingSequences);
        replaySequences.addAll(g3.labeledSequences(feedback));
        ConceptModel replay = trainer.train(hierarchy, NextConceptG3Evaluation.FRAME, replaySequences,
                c.temperature()).withThresholds(c.thresholds());

        List<Split> test = g3.splits(SplitRole.TEST, testSeed);
        Answers answers = answer(test, c, e, shuffled, replay);
        for (Split split : test) {
            labelSets.add(split.split().labels());
            split.split().labels().openForScoring();
        }
        return score(r, feedbackSeed, testSeed, feedback, test, answers, e, shuffled, unverified, replay);
    }

    /** Every arm answers every test window in stream order; no label is read. */
    private Answers answer(List<Split> test, ConceptModel c, FeedbackRun e, FeedbackRun shuffled,
                           ConceptModel replay) {
        var models = new ArrayList<ConceptModel>();
        models.add(c);
        models.add(e.last());
        models.add(shuffled.last());
        models.add(replay);
        models.addAll(e.checkpoints());
        var decisions = new ArrayList<List<Decisions>>();
        for (ConceptModel model : models) {
            var perLevel = new ArrayList<Decisions>();
            for (Split split : test) {
                perLevel.add(g3.runC(split, model));
            }
            decisions.add(perLevel);
        }
        return new Answers(decisions);
    }

    private RepeatResult score(int r, long feedbackSeed, long testSeed, List<Split> feedback, List<Split> test,
                               Answers answers, FeedbackRun e, FeedbackRun shuffled, FeedbackRun unverified,
                               ConceptModel replay) {
        int levels = test.size();
        int perLevel = test.getFirst().patterns().length;
        int items = levels * perLevel;
        int arms = answers.arms().size();
        var answerable = new double[items];
        var correct = new double[arms][items];
        var covered = new double[arms][items];
        for (int s = 0; s < levels; s++) {
            Split split = test.get(s);
            for (int w = 0; w < perLevel; w++) {
                int next = g3.nextLabel(split, w);
                if (next == NO_CONCEPT) {
                    continue;
                }
                int item = s * perLevel + w;
                answerable[item] = 1.0;
                for (int arm = 0; arm < arms; arm++) {
                    int leaf = answers.arms().get(arm).get(s).leaf()[w];
                    covered[arm][item] = leaf != NO_CONCEPT ? 1.0 : 0.0;
                    correct[arm][item] = leaf != NO_CONCEPT && leaf == next ? 1.0 : 0.0;
                }
            }
        }
        double[][] numerators = {correct[Arm.E], correct[Arm.C], covered[Arm.E], correct[Arm.SHUFFLED],
                correct[Arm.REPLAY]};
        double[][] denominators = {answerable, answerable, answerable, answerable, answerable};
        double[][] pooled = statistics.bootstrapRatios(numerators, denominators, g3.clusters(levels, perLevel),
                protocol.resamples(), protocol.bootstrapSeed() + r + 1);
        Interval h5 = statistics.interval(statistics.difference(pooled[0], pooled[1]));
        Interval eCoverageInterval = statistics.interval(pooled[2]);
        Interval shuffledDelta = statistics.interval(statistics.difference(pooled[3], pooled[1]));
        Interval replayDelta = statistics.interval(statistics.difference(pooled[0], pooled[4]));

        var rate = new double[arms];
        var coverage = new double[arms];
        var coveredAccuracy = new double[arms];
        for (int arm = 0; arm < arms; arm++) {
            rate[arm] = statistics.ratio(correct[arm], answerable);
            coverage[arm] = statistics.ratio(covered[arm], answerable);
            coveredAccuracy[arm] = statistics.ratio(correct[arm], covered[arm]);
        }
        boolean gate = h5.lower() > protocol.h5Margin() && coverage[Arm.E] >= protocol.h5MinCoverage();
        return new RepeatResult(r + 1, feedbackSeed, testSeed, feedback, test, answers, pooled, h5,
                eCoverageInterval, shuffledDelta, replayDelta, rate, coverage, coveredAccuracy, e, shuffled,
                unverified, replay, gate);
    }

    // ---------------------------------------------------------------- checks

    /** Training, threshold-selection, every FEEDBACK and every TEST split have distinct seeds and no shared window. */
    private Check disjointnessCheck(List<Split> training, List<RepeatResult> repeats) {
        var seedList = new ArrayList<Long>();
        var splitList = new ArrayList<ConceptSplit>();
        seedList.add(protocol.trainingSeed());
        splitList.add(training.getFirst().split());
        seedList.add(protocol.thresholdSeed());
        splitList.add(new ConceptFixtureGenerator().generate(SplitRole.THRESHOLD_SELECTION, protocol.thresholdSeed(),
                protocol.snrLevels().getFirst(), protocol.sequences(), protocol.windowsPerSequence()));
        for (RepeatResult repeat : repeats) {
            seedList.add(repeat.feedbackSeed());
            splitList.add(repeat.feedback().getFirst().split());
            seedList.add(repeat.testSeed());
            splitList.add(repeat.test().getFirst().split());
        }
        Check check = new SpectralResonanceH2Evaluation(SpectralResonanceH2Evaluation.REGISTERED, true)
                .disjointnessCheck(seedList, splitList);
        return new Check(check.name(), check.passed(), check.detail().replace("test repeats",
                "feedback and test splits"));
    }

    /**
     * The leakage check of contract section 12.3 for feedback: every applied outcome's receipt and evidence come from
     * a FEEDBACK split, none from a TEST split; a test label feeding an update fails the run.
     */
    private Check provenanceCheck(List<RepeatResult> repeats) {
        int references = 0;
        int foreign = 0;
        int test = 0;
        for (RepeatResult repeat : repeats) {
            for (FeedbackRun run : List.of(repeat.e(), repeat.shuffled())) {
                for (String reference : run.appliedReferences()) {
                    references++;
                    foreign += reference.contains(FEEDBACK_ROLE) ? 0 : 1;
                    test += reference.contains(TEST_ROLE) ? 1 : 0;
                }
            }
        }
        return new Check("leakage.feedback-provenance", references > 0 && foreign == 0 && test == 0,
                references + " receipt and evidence references of applied outcomes (E and E-shuffled); " + foreign
                        + " outside FEEDBACK splits, " + test + " from TEST splits");
    }

    /** Unknown outcomes abstain: E-unverified applies nothing and keeps C's exact snapshot in every repeat. */
    private Check unverifiedCheck(Selection selection, List<RepeatResult> repeats) {
        int identical = 0;
        int applied = 0;
        int outcomes = 0;
        for (RepeatResult repeat : repeats) {
            FeedbackRun run = repeat.unverified();
            identical += run.last() == selection.model() ? 1 : 0;
            applied += run.applied();
            outcomes += run.outcomes();
        }
        return new Check("feedback.unverified-abstains", identical == repeats.size() && applied == 0 && outcomes > 0,
                outcomes + " UNVERIFIED outcomes, " + applied + " applied; C's snapshot kept in " + identical + "/"
                        + repeats.size() + " repeats");
    }

    /**
     * Repeated feedback cannot amplify: the first applied outcome of repeat 1, sent twice to its snapshot, is applied
     * once ({@code DUPLICATE}), and replayed onto the snapshot it produced is {@code MODEL_MISMATCH}.
     */
    private Check duplicateCheck(RepeatResult first) {
        ConceptOutcome outcome = first.e().firstApplied();
        ConceptModel before = first.e().beforeFirst();
        if (outcome == null) {
            return new Check("feedback.duplicate-rejected", false, "no applied outcome in repeat 1");
        }
        var limits = new ConceptFeedbackLimits(2, protocol.maxEvidenceAge(), protocol.maxFeedbackShare(),
                List.of(VERIFIER));
        var twice = new ConceptFeedbackPolicy(limits);
        long at = outcome.originCycleOrdinal() + 1;
        ConceptModelUpdate doubled = twice.apply(before, List.of(outcome, outcome), at);
        ConceptModelUpdate replayed = twice.apply(doubled.current(), List.of(outcome), at);
        boolean once = doubled.work().applied() == 1 && doubled.rejected().size() == 1
                && doubled.rejected().getFirst().reason() == ConceptFeedbackRejection.DUPLICATE;
        boolean mismatch = replayed.current() == doubled.current() && replayed.rejected().size() == 1
                && replayed.rejected().getFirst().reason() == ConceptFeedbackRejection.MODEL_MISMATCH;
        return new Check("feedback.duplicate-rejected", once && mismatch, "first applied outcome of repeat 1: sent "
                + "twice " + (once ? "applied once (DUPLICATE)" : "NOT DEDUPLICATED") + "; replayed onto its "
                + "result " + (mismatch ? "MODEL_MISMATCH" : "APPLIED AGAIN"));
    }

    /** Re-streams repeat 1's feedback from C's snapshot; every snapshot must have the same fingerprint. */
    private Check replayCheck(RepeatResult first, Selection selection) {
        List<Split> feedback = g3.splits(SplitRole.FEEDBACK, first.feedbackSeed());
        FeedbackRun again = stream(selection.model(), feedback, Feedback.VERIFIED, first.feedbackSeed());
        boolean same = again.last().fingerprint().equals(first.e().last().fingerprint())
                && again.last().revision() == first.e().last().revision();
        return new Check("feedback.replay-deterministic", same, "repeat 1 feedback replayed from C: revision "
                + again.last().revision() + ", fingerprint " + (same ? "identical" : "DIFFERENT"));
    }

    /**
     * Re-answers repeat 1 with permuted held-out labels after re-streaming its feedback: E's final fingerprint and
     * every arm's answers must be bit-identical, and E's rate must change, so the check is not vacuous.
     */
    private Check counterfactualCheck(RepeatResult first, Selection selection, List<SplitLabels> labelSets) {
        var permuted = new ArrayList<Split>();
        for (int s = 0; s < first.test().size(); s++) {
            Split split = first.test().get(s);
            SplitLabels labels = split.split().labels().permuted(new Random(seeds.derive(first.testSeed(),
                    PERMUTATION_SALT, s)));
            labelSets.add(labels);
            ConceptSplit original = split.split();
            permuted.add(new Split(new ConceptSplit(original.role(), original.seed(), original.snrDb(),
                    original.windows(), labels), split.patterns()));
        }
        List<Split> feedback = g3.splits(SplitRole.FEEDBACK, first.feedbackSeed());
        FeedbackRun e = stream(selection.model(), feedback, Feedback.VERIFIED, first.feedbackSeed());
        FeedbackRun shuffled = stream(selection.model(), feedback, Feedback.SHUFFLED, first.feedbackSeed());
        Answers again = answer(permuted, selection.model(), e, shuffled, first.replay());
        boolean identical = again.sameAs(first.answers())
                && e.last().fingerprint().equals(first.e().last().fingerprint());
        for (Split split : permuted) {
            split.split().labels().openForScoring();
        }
        RepeatResult counterfactual = score(0, first.feedbackSeed(), first.testSeed(), feedback, permuted, again, e,
                shuffled, first.unverified(), first.replay());
        boolean changed = counterfactual.rate()[Arm.E] != first.rate()[Arm.E];
        return new Check("leakage.counterfactual", identical && changed,
                "repeat 1 re-answered with permuted held-out labels: E snapshot and answers "
                        + (identical ? "bit-identical" : "CHANGED") + "; E rate "
                        + (changed ? "changed" : "unchanged (vacuous)"));
    }

    /** Regenerates repeat 1, re-streams, re-answers and re-bootstraps it; everything must be bit-identical. */
    private Check determinismCheck(RepeatResult first, Selection selection, List<LabeledSequence> trainingSequences,
                                   List<SplitLabels> labelSets) {
        RepeatResult rerun = repeat(0, selection, trainingSequences, labelSets);
        boolean identical = first.answers().sameAs(rerun.answers()) && Arrays.deepEquals(first.pooled(),
                rerun.pooled()) && first.e().last().fingerprint().equals(rerun.e().last().fingerprint())
                && first.shuffled().last().fingerprint().equals(rerun.shuffled().last().fingerprint());
        return new Check("determinism", identical, "repeat 1 regenerated, re-streamed, re-answered and "
                + "re-bootstrapped: " + (identical ? "bit-identical" : "DIFFERENT"));
    }

    // ---------------------------------------------------------------- report

    private Map<String, String> metadata(Selection selection, List<RepeatResult> repeats, boolean holds) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("contract", CONTRACT);
        metadata.put("generator.version", ConceptFixtureGenerator.VERSION);
        metadata.put("protocol.margins", "H5 " + format(protocol.h5Margin()) + " (min E leaf coverage "
                + format(protocol.h5MinCoverage()) + ")");
        metadata.put("protocol.gateRule", "H5 holds iff in every repeat the 95% paired-bootstrap lower bound of "
                + "rate(E) - rate(C) exceeds the margin and E's pooled leaf coverage reaches the minimum; rate = "
                + "covered and correct next leaf over windows with a successor");
        metadata.put("protocol.selection", "C trained and thresholded as G3 (training seed "
                + hex(protocol.trainingSeed()) + ", threshold seed " + hex(protocol.thresholdSeed())
                + ", selection coverage " + format(protocol.selectionCoverage()) + ")");
        metadata.put("protocol.feedbackLimits", "one outcome per update, max evidence age "
                + protocol.maxEvidenceAge() + ", max feedback share " + format(protocol.maxFeedbackShare())
                + ", trusted verifier " + VERIFIER);
        metadata.put("protocol.bootstrapResamples", Integer.toString(protocol.resamples()));
        metadata.put("protocol.bootstrapSeed", hex(protocol.bootstrapSeed()) + " + repeat");
        metadata.put("protocol.snrLevelsDb", protocol.snrLevels().toString());
        metadata.put("protocol.feedbackSeeds", protocol.feedbackSeeds().stream().map(NextConceptG5Evaluation::hex)
                .toList().toString());
        metadata.put("protocol.testSeeds", protocol.testSeeds().stream().map(NextConceptG5Evaluation::hex)
                .toList().toString());
        metadata.put("protocol.windows", protocol.sequences() + " sequences x " + protocol.windowsPerSequence()
                + " windows per split and SNR level");
        metadata.put("protocol.registered", Boolean.toString(protocol.equals(REGISTERED)));
        metadata.put("selection.temperature", Double.toString(selection.model().temperature()));
        metadata.put("selection.thresholds", selection.model().thresholds().toString());
        metadata.put("model.fingerprint", selection.model().fingerprint());
        metadata.put("model.retainedBytes", Long.toString(selection.model().retainedBytes()));
        for (RepeatResult repeat : repeats) {
            String prefix = "g5.repeat" + repeat.number() + ".";
            metadata.put(prefix + "h5", format(repeat.rate()[Arm.E] - repeat.rate()[Arm.C]) + " "
                    + format(repeat.h5()));
            metadata.put(prefix + "eCoverage", format(repeat.coverage()[Arm.E]) + " "
                    + format(repeat.eCoverageInterval()));
            metadata.put(prefix + "feedback", repeat.e().applied() + " applied of " + repeat.e().outcomes()
                    + " outcomes, final revision " + repeat.e().last().revision());
            metadata.put(prefix + "gate", repeat.gatePassed() ? "pass" : "fail");
        }
        metadata.put("h5.verdict", holds ? "holds" : "negative");
        metadata.put("g5.part1", holds ? "H5 holds" : "H5 reported negative");
        return metadata;
    }

    static String hex(long value) {
        return "0x" + Long.toHexString(value).toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- values

    /** Arm indices of {@link Answers#arms()} and of the per-arm arrays of a {@link RepeatResult}. */
    static final class Arm {
        static final int C = 0;
        static final int E = 1;
        static final int SHUFFLED = 2;
        static final int REPLAY = 3;
        /** The first drift-curve checkpoint; one arm per {@link #CHECKPOINTS} entry follows. */
        static final int CHECKPOINT = 4;
        static final List<String> NAMES = List.of("C (no feedback)", "E (validated feedback)",
                "E-shuffled (permuted feedback labels)", "C-replay (retrained on training + feedback)",
                "E after 25% of feedback", "E after 50% of feedback");

        private Arm() {
        }
    }

    /**
     * One stream of feedback.
     *
     * @param last the snapshot after the whole stream
     * @param checkpoints the snapshots after each {@link #CHECKPOINTS} fraction of the stream
     * @param steps windows with a successor
     * @param outcomes outcomes sent (answered windows)
     * @param applied outcomes applied
     * @param positive applied outcomes whose verified leaf lies in the predicted subtree
     * @param rejections rejected outcomes per {@link ConceptFeedbackRejection} ordinal
     * @param copiedBytes bytes copied by every update
     * @param maxCopiedBytes bytes copied by the largest update
     * @param appliedReferences receipt and evidence source references of every applied outcome
     * @param firstApplied the first applied outcome, or {@code null}
     * @param beforeFirst the snapshot it was applied to, or {@code null}
     */
    record FeedbackRun(ConceptModel last, List<ConceptModel> checkpoints, int steps, int outcomes, int applied,
                       int positive, int[] rejections, long copiedBytes, long maxCopiedBytes,
                       List<String> appliedReferences, ConceptOutcome firstApplied, ConceptModel beforeFirst) {
    }

    /** Per arm in {@link Arm} order, the decisions per SNR level. */
    record Answers(List<List<Decisions>> arms) {

        boolean sameAs(Answers other) {
            for (int arm = 0; arm < arms.size(); arm++) {
                for (int s = 0; s < arms.get(arm).size(); s++) {
                    if (!arms.get(arm).get(s).sameAs(other.arms().get(arm).get(s))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * Everything one repeat produced.
     *
     * @param rate per arm, covered-and-correct next leaf over windows with a successor
     * @param coverage per arm, leaf coverage
     * @param coveredAccuracy per arm, accuracy on covered windows
     */
    record RepeatResult(int number, long feedbackSeed, long testSeed, List<Split> feedback, List<Split> test,
                        Answers answers, double[][] pooled, Interval h5, Interval eCoverageInterval,
                        Interval shuffledDelta, Interval replayDelta, double[] rate, double[] coverage,
                        double[] coveredAccuracy, FeedbackRun e, FeedbackRun shuffled, FeedbackRun unverified,
                        ConceptModel replay, boolean gatePassed) {
    }

    /**
     * Result of a run.
     *
     * @param checks semantic checks
     * @param selection C's training and selection outcome
     * @param repeats per-repeat results
     * @param holds whether H5 holds
     * @param metadata report metadata
     */
    record Outcome(List<EvaluationCheck> checks, Selection selection, List<RepeatResult> repeats, boolean holds,
                   Map<String, String> metadata) {
    }

    /**
     * Seeds, sizes, margins and feedback bounds of one G5 run.
     *
     * @param trainingSeed training split seed (G3's)
     * @param thresholdSeed threshold-selection split seed (G3's)
     * @param feedbackSeeds one FEEDBACK seed per repeat
     * @param testSeeds one held-out seed per repeat
     * @param bootstrapSeed base bootstrap seed; repeat {@code r} (1-based) uses {@code bootstrapSeed + r}
     * @param resamples bootstrap resamples {@code B}
     * @param snrLevels SNR levels in decibels
     * @param sequences sequences per split
     * @param windowsPerSequence windows per sequence
     * @param h5Margin pre-registered H5 margin on {@code rate(E) - rate(C)}
     * @param h5MinCoverage pre-registered minimum leaf coverage of E
     * @param selectionCoverage leaf coverage C's thresholds must reach on the threshold-selection split (G3's)
     * @param maxFeedbackShare the policy's feedback share per training window
     * @param maxEvidenceAge the policy's evidence age bound
     */
    record Protocol(long trainingSeed, long thresholdSeed, List<Long> feedbackSeeds, List<Long> testSeeds,
                    long bootstrapSeed, int resamples, List<Double> snrLevels, int sequences, int windowsPerSequence,
                    double h5Margin, double h5MinCoverage, double selectionCoverage, double maxFeedbackShare,
                    long maxEvidenceAge) {

        /** Copies the lists and requires one feedback seed per repeat, a resample and sequences of two windows. */
        Protocol {
            feedbackSeeds = List.copyOf(feedbackSeeds);
            testSeeds = List.copyOf(testSeeds);
            snrLevels = List.copyOf(snrLevels);
            if (testSeeds.isEmpty() || feedbackSeeds.size() != testSeeds.size() || resamples < 1
                    || windowsPerSequence < 2) {
                throw new IllegalArgumentException("a protocol needs one feedback seed per repeat, a resample and "
                        + "sequences of at least two windows");
            }
        }

        /** Returns the G3 protocol whose training and selection C reuses; its G3 margins are unused here. */
        NextConceptG3Evaluation.Protocol g3Protocol() {
            return new NextConceptG3Evaluation.Protocol(trainingSeed, thresholdSeed, testSeeds, bootstrapSeed,
                    resamples, snrLevels, sequences, windowsPerSequence,
                    NextConceptG3Evaluation.REGISTERED.noiseSeed(), NextConceptG3Evaluation.REGISTERED.h3Margin(),
                    NextConceptG3Evaluation.REGISTERED.h3MinCoverage(), NextConceptG3Evaluation.REGISTERED.h6Margin(),
                    NextConceptG3Evaluation.REGISTERED.h4Margin(), selectionCoverage);
        }

        /** Returns the smoke-mode protocol: the first repeat only, 8 sequences and 200 resamples. */
        Protocol quick() {
            return new Protocol(trainingSeed, thresholdSeed, feedbackSeeds.subList(0, 1), testSeeds.subList(0, 1),
                    bootstrapSeed, 200, snrLevels, Math.min(8, sequences), windowsPerSequence, h5Margin,
                    h5MinCoverage, selectionCoverage, maxFeedbackShare, maxEvidenceAge);
        }
    }
}
