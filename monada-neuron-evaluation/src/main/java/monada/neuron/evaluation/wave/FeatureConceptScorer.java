package monada.neuron.evaluation.wave;

import monada.neuron.reasoning.concept.AbstentionReason;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptNode;
import monada.neuron.reasoning.concept.ConceptThresholds;
import monada.neuron.reasoning.concept.PredictionBudget;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reference re-implementation, in the evaluation module, of the documented training and scoring rules of the core
 * {@code ConceptModelTrainer} and {@code NextConceptPredictor} (ADR 0043, contract section 8) over any
 * {@link ConceptFeatureMap}. It exists so control D (Haar and coherence features, contract section 14.2) changes only
 * the feature while the hierarchy, softmax, transition mix, descent, thresholds and tie rules stay those of control C;
 * the core predictor is DFT-only by design and is not modified (issue #130).
 *
 * <p>Every sum runs in the core's order with the core's arithmetic ({@code StrictMath.exp}, {@code Math.clamp},
 * the same normalization), so over {@link ConceptFeatureMap#dft()} features the scorer reproduces the core predictor
 * bit for bit: the ablation checks this on the medium workload ({@code d.reference-equivalence}) before trusting any
 * D figure. Supporting components and reported candidate lists are not reproduced: the ablation scores neither.
 *
 * <p>Training: one pass in sequence order; each non-silent window adds its unit feature to its leaf and every
 * ancestor, every window counts towards its leaf marginal and each consecutive pair counts one transition; the sums
 * are then scaled to unit norm. Cost per prediction: O(S · dimension) for S concepts with exemplars plus
 * O(L² + concepts · depth), as in the core.
 */
final class FeatureConceptScorer {

    /** Creates a stateless scorer. */
    FeatureConceptScorer() {
    }

    /**
     * Trains a snapshot with {@link ConceptThresholds#NONE}.
     *
     * @param hierarchy the concepts
     * @param features the feature map the windows were extracted with
     * @param dimension feature dimension
     * @param sequences labeled training sequences in order
     * @param temperature finite positive softmax temperature
     * @return the model
     */
    FeatureConceptModel train(ConceptHierarchy hierarchy, ConceptFeatureMap features, int dimension,
                              List<FeatureSequence> sequences, double temperature) {
        Objects.requireNonNull(hierarchy, "hierarchy must not be null");
        if (!Double.isFinite(temperature) || temperature <= 0.0) {
            throw new IllegalArgumentException("temperature must be finite and positive, got: " + temperature);
        }
        int concepts = hierarchy.size();
        int leaves = hierarchy.leafCount();
        var centroids = new double[concepts * dimension];
        var exemplarCounts = new long[concepts];
        var transitions = new long[leaves * leaves];
        var leafCounts = new long[leaves];
        for (FeatureSequence sequence : sequences) {
            int previousLeaf = -1;
            for (int w = 0; w < sequence.leaves().length; w++) {
                int leaf = sequence.leaves()[w];
                if (!hierarchy.isLeaf(leaf)) {
                    throw new IllegalArgumentException("label ordinal " + leaf + " is not a leaf");
                }
                int position = hierarchy.leafPosition(leaf);
                leafCounts[position]++;
                if (previousLeaf >= 0) {
                    transitions[previousLeaf * leaves + position]++;
                }
                previousLeaf = position;
                double[] feature = sequence.features()[w];
                if (feature != null) {
                    for (int concept = leaf; concept >= 0; concept = hierarchy.node(concept).parentOrdinal()) {
                        int offset = concept * dimension;
                        for (int k = 0; k < dimension; k++) {
                            centroids[offset + k] += feature[k];
                        }
                        exemplarCounts[concept]++;
                    }
                }
            }
        }
        for (int concept = 0; concept < concepts; concept++) {
            features.normalize(centroids, concept * dimension, dimension);
        }
        return new FeatureConceptModel(hierarchy, features, dimension, centroids, exemplarCounts, transitions,
                leafCounts, temperature, ConceptThresholds.NONE);
    }

    /**
     * Predicts the next concept, coarse to fine, or abstains, by the core predictor's rules.
     *
     * @param feature the unit feature of the current window, or {@code null} for a silent window
     * @param previous ordinal of the concept believed for the preceding window, or {@code -1}
     * @param model the snapshot
     * @param budget work bounds
     * @return the prediction
     */
    ScoredPrediction predict(double[] feature, int previous, FeatureConceptModel model, PredictionBudget budget) {
        if (model.scorableConcepts() > budget.maxComparisons()) {
            return early(AbstentionReason.BUDGET_EXHAUSTED);
        }
        if (model.scorableConcepts() == 0 || feature == null) {
            return early(AbstentionReason.LOW_CONFIDENCE);
        }
        ConceptHierarchy hierarchy = model.hierarchy();
        int concepts = hierarchy.size();
        int leaves = hierarchy.leafCount();
        int dimension = model.dimension();
        var similarity = new double[concepts];
        for (int a = 0; a < concepts; a++) {
            if (model.exemplarCount(a) > 0) {
                double sum = 0.0;
                for (int k = 0; k < dimension; k++) {
                    sum += feature[k] * model.centroid(a, k);
                }
                similarity[a] = Math.clamp(sum, 0.0, 1.0);
            }
        }
        var path = new double[concepts];
        double temperature = model.temperature();
        evidenceGroup(hierarchy, model, similarity, path, temperature, ConceptNode.ROOT);
        for (int a = 0; a < concepts; a++) {
            if (hierarchy.childCount(a) > 0) {
                evidenceGroup(hierarchy, model, similarity, path, temperature, a);
            }
            int up = hierarchy.node(a).parentOrdinal();
            if (up >= 0) {
                path[a] *= path[up];
            }
        }
        var current = new double[concepts];
        double total = 0.0;
        long[] row = priorRow(hierarchy, model, previous);
        long rowSum = 0;
        for (long count : row) {
            rowSum += count;
        }
        for (int i = 0; i < leaves; i++) {
            double prior = (row[i] + 1.0) / (rowSum + (double) leaves);
            double mass = prior * path[hierarchy.leaf(i)];
            current[hierarchy.leaf(i)] = mass;
            total += mass;
        }
        var next = new double[concepts];
        for (int i = 0; i < leaves; i++) {
            double q = current[hierarchy.leaf(i)] / total;
            current[hierarchy.leaf(i)] = q;
            long fromSum = 0;
            for (int j = 0; j < leaves; j++) {
                fromSum += model.transitionAt(i, j);
            }
            double denominator = fromSum + (double) leaves;
            for (int j = 0; j < leaves; j++) {
                next[hierarchy.leaf(j)] += q * ((model.transitionAt(i, j) + 1.0) / denominator);
            }
        }
        for (int a = concepts - 1; a >= 0; a--) {
            int parent = hierarchy.node(a).parentOrdinal();
            if (parent >= 0) {
                current[parent] += current[a];
                next[parent] += next[a];
            }
        }
        for (int a = 0; a < concepts; a++) {
            current[a] = Math.clamp(current[a], 0.0, 1.0);
            next[a] = Math.clamp(next[a], 0.0, 1.0);
        }
        return descend(hierarchy, model, budget, similarity, current, next);
    }

    private ScoredPrediction early(AbstentionReason reason) {
        return new ScoredPrediction(Optional.of(reason), -1, 0.0, -1, false, null, null, null, 0, 0, 0);
    }

    private void evidenceGroup(ConceptHierarchy hierarchy, FeatureConceptModel model, double[] similarity,
                               double[] path, double temperature, int parent) {
        int size = parent < 0 ? hierarchy.rootCount() : hierarchy.childCount(parent);
        double best = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < size; k++) {
            int a = member(hierarchy, parent, k);
            if (model.exemplarCount(a) > 0) {
                best = Math.max(best, similarity[a]);
            }
        }
        if (best == Double.NEGATIVE_INFINITY) {
            for (int k = 0; k < size; k++) {
                path[member(hierarchy, parent, k)] = 1.0 / size;
            }
            return;
        }
        double sum = 0.0;
        for (int k = 0; k < size; k++) {
            int a = member(hierarchy, parent, k);
            double weight = model.exemplarCount(a) > 0 ? StrictMath.exp((similarity[a] - best) / temperature) : 0.0;
            path[a] = weight;
            sum += weight;
        }
        for (int k = 0; k < size; k++) {
            path[member(hierarchy, parent, k)] /= sum;
        }
    }

    private long[] priorRow(ConceptHierarchy hierarchy, FeatureConceptModel model, int previous) {
        int leaves = hierarchy.leafCount();
        var row = new long[leaves];
        if (previous < 0) {
            for (int i = 0; i < leaves; i++) {
                row[i] = model.leafCountAt(i);
            }
            return row;
        }
        for (int i = 0; i < leaves; i++) {
            if (hierarchy.inSubtree(hierarchy.leaf(i), previous)) {
                for (int j = 0; j < leaves; j++) {
                    row[j] += model.transitionAt(i, j);
                }
            }
        }
        return row;
    }

    /**
     * The core descent: per depth, the children of the selected concept (the roots at depth 0) ranked by
     * {@code next / parent mass}, score descending then lower ordinal; a margin below {@code τ_margin} fails first,
     * then a score below {@code τ_conf}; a failure at depth 0 abstains, deeper it answers coarse-only.
     */
    private ScoredPrediction descend(ConceptHierarchy hierarchy, FeatureConceptModel model, PredictionBudget budget,
                                     double[] similarity, double[] current, double[] next) {
        ConceptThresholds thresholds = model.thresholds();
        int comparisons = model.scorableConcepts();
        int scored = 0;
        int selected = -1;
        int root = -1;
        double rootScore = 0.0;
        for (int depth = 0; ; depth++) {
            int parent = selected;
            int size = parent < 0 ? hierarchy.rootCount() : hierarchy.childCount(parent);
            double parentMass = 0.0;
            for (int k = 0; k < size; k++) {
                parentMass += next[member(hierarchy, parent, k)];
            }
            int top = -1;
            double topScore = 0.0;
            int second = -1;
            double secondScore = 0.0;
            for (int k = 0; k < size; k++) {
                int concept = member(hierarchy, parent, k);
                double score = parentMass > 0.0 ? Math.clamp(next[concept] / parentMass, 0.0, 1.0) : 0.0;
                if (top < 0 || ranksBefore(score, concept, topScore, top)) {
                    second = top;
                    secondScore = topScore;
                    top = concept;
                    topScore = score;
                } else if (second < 0 || ranksBefore(score, concept, secondScore, second)) {
                    second = concept;
                    secondScore = score;
                }
            }
            double margin = topScore - (second < 0 ? 0.0 : secondScore);
            scored += size;
            AbstentionReason failure = null;
            if (margin < thresholds.margin(depth)) {
                failure = AbstentionReason.LOW_MARGIN;
            } else if (topScore < thresholds.confidence(depth)) {
                failure = AbstentionReason.LOW_CONFIDENCE;
            }
            if (failure != null) {
                if (depth == 0) {
                    return new ScoredPrediction(Optional.of(failure), -1, 0.0, -1, false, similarity, current, next,
                            comparisons, scored, depth + 1);
                }
                return new ScoredPrediction(Optional.empty(), root, rootScore, selected, true, similarity, current,
                        next, comparisons, scored, depth + 1);
            }
            selected = top;
            if (depth == 0) {
                root = top;
                rootScore = topScore;
            }
            if (hierarchy.isLeaf(selected)) {
                return new ScoredPrediction(Optional.empty(), root, rootScore, selected, false, similarity, current,
                        next, comparisons, scored, depth + 1);
            }
            if (depth + 1 >= budget.maxLevels()) {
                return new ScoredPrediction(Optional.empty(), root, rootScore, selected, true, similarity, current,
                        next, comparisons, scored, depth + 1);
            }
        }
    }

    private int member(ConceptHierarchy hierarchy, int parent, int k) {
        return parent < 0 ? hierarchy.root(k) : hierarchy.child(parent, k);
    }

    /** Score descending, then lower ordinal (contract section 8.4). */
    private boolean ranksBefore(double score, int ordinal, double otherScore, int otherOrdinal) {
        int byScore = Double.compare(otherScore, score);
        return byScore != 0 ? byScore < 0 : ordinal < otherOrdinal;
    }

    /**
     * One labeled training sequence in extracted form.
     *
     * @param features per window, the unit feature, or {@code null} for a silent window
     * @param leaves per window, the leaf ordinal of its label
     */
    record FeatureSequence(double[][] features, int[] leaves) {

        /** Requires one label per window. */
        FeatureSequence {
            if (features.length != leaves.length) {
                throw new IllegalArgumentException("one label per window is required");
            }
        }
    }
}
