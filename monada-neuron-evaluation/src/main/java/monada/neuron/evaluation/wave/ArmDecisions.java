package monada.neuron.evaluation.wave;

import monada.neuron.reasoning.concept.ConceptHierarchy;

import java.util.Arrays;
import java.util.Objects;

/**
 * Per-window decisions of one ablation arm over one split (issue #130), recorded from {@link ScoredPrediction}s so
 * the core predictor and the reference scorer are decided and scored by one code path. It mirrors the decisions of
 * the G3 harness: the leaf answer (or none), the depth-0 answer, the forced top-1 and top-2 leaves by next-window
 * score, the previous concept each query carried and the prediction itself, for flat re-decisions and rank metrics.
 */
final class ArmDecisions {

    /** No concept: an abstention, a coarse-only answer at the leaf depth, or no previous concept. */
    static final int NONE = -1;

    final ConceptHierarchy hierarchy;
    final int[] leaf;
    final int[] root;
    final int[] forced;
    final int[] forcedSecond;
    final double[] leafScore;
    final double[] forcedScore;
    final double[] rootScore;
    final int[] previous;
    final ScoredPrediction[] predictions;
    int abstentions;
    int coarseOnly;
    long comparisons;
    long candidates;
    long levels;
    int maxComparisons;

    ArmDecisions(ConceptHierarchy hierarchy, int windows) {
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy must not be null");
        leaf = filled(windows);
        root = filled(windows);
        forced = filled(windows);
        forcedSecond = filled(windows);
        leafScore = new double[windows];
        forcedScore = new double[windows];
        rootScore = new double[windows];
        previous = filled(windows);
        predictions = new ScoredPrediction[windows];
    }

    /** One prediction step: the arm's answer for window {@code w} given the previous concept ordinal or NONE. */
    @FunctionalInterface
    interface Step {
        ScoredPrediction predict(int w, int previousOrdinal);
    }

    /**
     * Runs an arm over every sequence of a split in stream order, as the G3 harness runs C: the query for window
     * {@code p} carries the arm's own selection for window {@code p − 1} (made at step {@code p − 2}, coarse
     * included), none at a sequence start or after an abstention. No label is read.
     */
    static ArmDecisions run(ConceptHierarchy hierarchy, int windows, int windowsPerSequence, Step step) {
        var decisions = new ArmDecisions(hierarchy, windows);
        for (int start = 0; start < windows; start += windowsPerSequence) {
            int forPrevious = NONE;
            int forCurrent = NONE;
            for (int w = start; w < start + windowsPerSequence; w++) {
                ScoredPrediction prediction = step.predict(w, forPrevious);
                decisions.record(w, forPrevious, prediction);
                forPrevious = forCurrent;
                forCurrent = prediction.abstention().isEmpty() ? prediction.selected() : NONE;
            }
        }
        return decisions;
    }

    int windows() {
        return leaf.length;
    }

    void record(int w, int queried, ScoredPrediction prediction) {
        previous[w] = queried;
        predictions[w] = prediction;
        comparisons += prediction.comparisons();
        candidates += prediction.candidatesScored();
        levels += prediction.levelsVisited();
        maxComparisons = Math.max(maxComparisons, prediction.comparisons());
        if (prediction.abstention().isPresent()) {
            abstentions++;
        } else {
            root[w] = prediction.root();
            rootScore[w] = prediction.rootScore();
            if (prediction.coarseOnly()) {
                coarseOnly++;
            } else {
                leaf[w] = prediction.selected();
                leafScore[w] = prediction.next()[leaf[w]];
            }
        }
        if (prediction.scored()) {
            double[] next = prediction.next();
            for (int i = 0; i < hierarchy.leafCount(); i++) {
                int candidate = hierarchy.leaf(i);
                if (forced[w] == NONE || next[candidate] > next[forced[w]]) {
                    forcedSecond[w] = forced[w];
                    forced[w] = candidate;
                } else if (forcedSecond[w] == NONE || next[candidate] > next[forcedSecond[w]]) {
                    forcedSecond[w] = candidate;
                }
            }
            forcedScore[w] = next[forced[w]];
        }
    }

    /**
     * The flat control of an arm: the arm's own prediction of every window (same previous concept, score table and
     * work) ranked flat over the leaves; it answers the top leaf when its margin and score reach the flat thresholds.
     * It makes no prediction and feeds nothing back, so the comparison with the arm changes only the hierarchy.
     */
    ArmDecisions flat(double confidence, double margin) {
        var decisions = new ArmDecisions(hierarchy, windows());
        for (int w = 0; w < windows(); w++) {
            decisions.previous[w] = previous[w];
            decisions.predictions[w] = predictions[w];
            decisions.forced[w] = forced[w];
            decisions.forcedSecond[w] = forcedSecond[w];
            decisions.forcedScore[w] = forcedScore[w];
            if (!predictions[w].scored()) {
                continue;
            }
            double[] next = predictions[w].next();
            double top = next[forced[w]];
            double runnerUp = forcedSecond[w] == NONE ? 0.0 : next[forcedSecond[w]];
            if (top - runnerUp >= margin && top >= confidence) {
                decisions.leaf[w] = forced[w];
            }
            decisions.leafScore[w] = top;
        }
        decisions.comparisons = comparisons;
        decisions.candidates = candidates;
        decisions.levels = levels;
        decisions.maxComparisons = maxComparisons;
        return decisions;
    }

    /**
     * Returns the 1-based rank of leaf {@code truth} among the leaves by next-window score (descending, ties to the
     * lower ordinal), or {@code 0} when the window has no score table.
     */
    int rank(int w, int truth) {
        ScoredPrediction prediction = predictions[w];
        if (!prediction.scored()) {
            return 0;
        }
        double[] next = prediction.next();
        int rank = 1;
        for (int i = 0; i < hierarchy.leafCount(); i++) {
            int other = hierarchy.leaf(i);
            if (next[other] > next[truth] || (next[other] == next[truth] && other < truth)) {
                rank++;
            }
        }
        return rank;
    }

    /** Returns the ancestor of a concept at a depth (the concept itself at its own depth). */
    int ancestorAt(int ordinal, int depth) {
        int current = ordinal;
        while (hierarchy.node(current).depth() > depth) {
            current = hierarchy.node(current).parentOrdinal();
        }
        return current;
    }

    /** Bit-identical comparison of every decision and prediction. */
    boolean sameAs(ArmDecisions other) {
        if (!(Arrays.equals(leaf, other.leaf) && Arrays.equals(root, other.root)
                && Arrays.equals(forced, other.forced) && Arrays.equals(forcedSecond, other.forcedSecond)
                && Arrays.equals(leafScore, other.leafScore) && Arrays.equals(forcedScore, other.forcedScore)
                && Arrays.equals(rootScore, other.rootScore) && Arrays.equals(previous, other.previous))) {
            return false;
        }
        for (int w = 0; w < windows(); w++) {
            if (!predictions[w].sameAs(other.predictions[w])) {
                return false;
            }
        }
        return true;
    }

    private static int[] filled(int windows) {
        var values = new int[windows];
        Arrays.fill(values, NONE);
        return values;
    }
}
