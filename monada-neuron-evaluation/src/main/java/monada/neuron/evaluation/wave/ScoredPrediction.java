package monada.neuron.evaluation.wave;

import monada.neuron.reasoning.concept.AbstentionReason;
import monada.neuron.reasoning.concept.ConceptPrediction;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptAbstention;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptCandidates;
import monada.neuron.reasoning.concept.ConceptScores;

import java.util.Arrays;
import java.util.Optional;

/**
 * The parts of one next-concept prediction the ablation scores, in one shape for the core predictor (control C, via
 * {@link #of}) and for the evaluation's reference scorer (controls D and C+coh), so every arm is decided and scored by
 * the same code.
 *
 * @param abstention the abstention reason, empty when the prediction answered (leaf or coarse-only)
 * @param root the selected depth-0 concept, {@code -1} for an abstention
 * @param rootScore the selected depth-0 concept's score, {@code 0} for an abstention
 * @param selected the selected concept (a leaf, or the deepest confident level of a coarse-only answer), {@code -1}
 *     for an abstention
 * @param coarseOnly whether the descent stopped above a leaf
 * @param similarity per concept, the centroid similarity; {@code null} for an early abstention without scores
 * @param current per concept, the current-window subtree mass; {@code null} without scores
 * @param next per concept, the next-window subtree mass; {@code null} without scores
 * @param comparisons centroid comparisons of the call
 * @param candidatesScored candidates ranked over the visited depths
 * @param levelsVisited depths visited
 */
record ScoredPrediction(Optional<AbstentionReason> abstention, int root, double rootScore, int selected,
                        boolean coarseOnly, double[] similarity, double[] current, double[] next, int comparisons,
                        int candidatesScored, int levelsVisited) {

    /** Returns the shape of a core prediction, copying its score table. */
    static ScoredPrediction of(ConceptPrediction prediction) {
        double[][] tables = prediction.scores().map(ScoredPrediction::tables).orElse(new double[3][]);
        int comparisons = prediction.work().comparisons();
        int candidates = prediction.work().candidatesScored();
        int levels = prediction.work().levelsVisited();
        return switch (prediction) {
            case ConceptCandidates c -> new ScoredPrediction(Optional.empty(),
                    c.levels().getFirst().top().conceptOrdinal(), c.levels().getFirst().top().score(),
                    c.selected().conceptOrdinal(), c.coarseOnly(), tables[0], tables[1], tables[2], comparisons,
                    candidates, levels);
            case ConceptAbstention a -> new ScoredPrediction(Optional.of(a.reason()), -1, 0.0, -1, false, tables[0],
                    tables[1], tables[2], comparisons, candidates, levels);
        };
    }

    private static double[][] tables(ConceptScores scores) {
        var tables = new double[3][scores.size()];
        for (int a = 0; a < scores.size(); a++) {
            tables[0][a] = scores.similarity(a);
            tables[1][a] = scores.currentScore(a);
            tables[2][a] = scores.nextScore(a);
        }
        return tables;
    }

    /** Returns whether the prediction selected a leaf. */
    boolean answeredLeaf() {
        return abstention.isEmpty() && !coarseOnly;
    }

    /** Returns whether a score table is present. */
    boolean scored() {
        return next != null;
    }

    /** Bit-identical comparison of every component, arrays by content. */
    boolean sameAs(ScoredPrediction other) {
        return abstention.equals(other.abstention) && root == other.root
                && Double.compare(rootScore, other.rootScore) == 0 && selected == other.selected
                && coarseOnly == other.coarseOnly && Arrays.equals(similarity, other.similarity)
                && Arrays.equals(current, other.current) && Arrays.equals(next, other.next)
                && comparisons == other.comparisons && candidatesScored == other.candidatesScored
                && levelsVisited == other.levelsVisited;
    }
}
