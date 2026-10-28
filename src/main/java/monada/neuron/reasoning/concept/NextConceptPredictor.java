package monada.neuron.reasoning.concept;

import monada.neuron.reasoning.concept.ConceptPrediction.ConceptAbstention;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptCandidates;
import monada.neuron.spectral.SpectralPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntUnaryOperator;

/**
 * Bounded, deterministic, coarse-to-fine next-concept predictor (contract {@code ncm-wave-cognition/1} section 8,
 * ADR 0043). It proposes which known concept the evidence of the <b>next</b> window will support, given the spectral
 * evidence of the current window and the concept believed for the preceding one; it never names a concept the host
 * did not label.
 *
 * <p><b>Scoring.</b> With {@code f} the unit magnitude feature of the evidence ({@link ConceptFeatures}), {@code c_a}
 * the centroid of concept {@code a}, {@code T} the model temperature and {@code L} the number of leaves:
 * <pre>{@code
 * sim(a)      = clamp(f · c_a, 0, 1)                                 one comparison per concept with exemplars
 * e(a)        = exp((sim(a) − max sim over siblings) / T) / Σ siblings    per level, siblings normalized;
 *                                                                    0 without exemplars, uniform if none has any
 * prior(i)    = (n(prev → i) + 1) / (n(prev → ·) + L)                Laplace-smoothed transition row of the previous
 *                                                                    concept (an inner concept sums its leaves' rows),
 *               or (n(i) + 1) / (n + L) without one                  or the smoothed leaf marginal
 * q(i)        ∝ prior(i) · Π e(a) over the path root..i              current-window leaf posterior
 * p(j)        = Σ_i q(i) · (n(i → j) + 1) / (n(i → ·) + L)            next-window leaf distribution
 * mass(a)     = Σ p(j) over the leaves j of a's subtree
 * }</pre>
 *
 * <p><b>Descent</b> (contract section 8.2). Depth {@code 0} ranks the roots by {@code mass(a)}; a deeper depth ranks
 * the children of the selected concept by {@code mass(a) / mass(parent)}. Ranking is score descending, then lower
 * ordinal (fixture P16); at most {@code maxCandidatesPerLevel} candidates are kept, by a bounded selection rather than
 * a full sort. A depth fails when the top margin is below {@code τ_margin[d]} ({@link AbstentionReason#LOW_MARGIN},
 * checked first so exact ties land there) or the top score is below {@code τ_conf[d]}
 * ({@link AbstentionReason#LOW_CONFIDENCE}). A failure at depth {@code 0} abstains; a failure deeper returns a
 * coarse-only {@link ConceptCandidates} at the last confident depth.
 *
 * <p><b>Abstention before scoring.</b> {@link AbstentionReason#INCOMPATIBLE_INPUT} for another pattern version or
 * frame, {@link AbstentionReason#UNKNOWN_CONCEPT} for a previous concept outside the hierarchy (fixture N9),
 * {@link AbstentionReason#BUDGET_EXHAUSTED} when the model needs more comparisons than the budget allows, and
 * {@link AbstentionReason#LOW_CONFIDENCE} for a model without exemplars or silent evidence.
 *
 * <p><b>Cost.</b> O(bins) for the feature, O(S · bins) for S concepts with exemplars, O(L² + concepts · depth) for
 * the transition mix and masses, O(m · K) per visited depth for m siblings and K kept candidates, and
 * O(components · K') for the supporting components of each kept candidate. Six arrays of O(concepts + bins) are
 * allocated per call. Every sum runs in ascending index order with {@code StrictMath}; the same query and model give
 * a bit-identical result.
 */
public final class NextConceptPredictor {

    /** Largest number of supporting component positions per candidate. */
    public static final int MAX_SUPPORTING_COMPONENTS = 8;

    private final ConceptFeatures features = new ConceptFeatures();

    /** Creates a stateless predictor. */
    public NextConceptPredictor() {
    }

    /**
     * Predicts the next concept, coarse to fine, or abstains.
     *
     * @param query current evidence and the concept believed for the preceding window
     * @param model caller-owned model snapshot
     * @param budget work bounds of the call
     * @return the ranked candidates or a typed abstention
     * @throws NullPointerException if an argument is {@code null}
     */
    public ConceptPrediction predict(ConceptQuery query, ConceptModel model, PredictionBudget budget) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        ConceptHierarchy hierarchy = model.hierarchy();
        SpectralPattern evidence = query.evidence();
        if (evidence.version() != SpectralPattern.CURRENT_VERSION || !evidence.frame().equals(model.frame())) {
            return ConceptAbstention.early(AbstentionReason.INCOMPATIBLE_INPUT, "evidence " + evidence.frame()
                    + " (version " + evidence.version() + ") does not match the model frame " + model.frame());
        }
        int previous = -1;
        if (query.previousConceptCode().isPresent()) {
            OptionalInt ordinal = hierarchy.ordinalOf(query.previousConceptCode().getAsLong());
            if (ordinal.isEmpty()) {
                return ConceptAbstention.early(AbstentionReason.UNKNOWN_CONCEPT, "previous concept "
                        + query.previousConceptCode().getAsLong() + " is not in the hierarchy");
            }
            previous = ordinal.getAsInt();
        }
        if (model.scorableConcepts() > budget.maxComparisons()) {
            return ConceptAbstention.early(AbstentionReason.BUDGET_EXHAUSTED, "the model needs "
                    + model.scorableConcepts() + " comparisons, the budget allows " + budget.maxComparisons());
        }
        if (model.scorableConcepts() == 0) {
            return ConceptAbstention.early(AbstentionReason.LOW_CONFIDENCE, "the model has no exemplars");
        }
        double[] feature = new double[model.bins()];
        if (!features.extract(evidence, feature)) {
            return ConceptAbstention.early(AbstentionReason.LOW_CONFIDENCE, "the evidence carries no spectral energy");
        }
        ConceptScores scores = score(hierarchy, model, feature, previous);
        return descend(hierarchy, model, budget, evidence, feature, scores);
    }

    private ConceptScores score(ConceptHierarchy hierarchy, ConceptModel model, double[] feature,
                                       int previous) {
        int concepts = hierarchy.size();
        int leaves = hierarchy.leafCount();
        int bins = model.bins();
        double[] centroids = model.centroidsView();
        double[] similarity = new double[concepts];
        for (int a = 0; a < concepts; a++) {
            if (model.exemplarCount(a) > 0) {
                similarity[a] = Math.clamp(features.dot(feature, centroids, a * bins), 0.0, 1.0);
            }
        }
        double[] path = new double[concepts];
        double temperature = model.temperature();
        evidenceGroup(model, similarity, path, temperature, hierarchy.rootCount(), k -> hierarchy.root(k));
        for (int a = 0; a < concepts; a++) {
            int parent = a;
            if (hierarchy.childCount(parent) > 0) {
                evidenceGroup(model, similarity, path, temperature, hierarchy.childCount(parent),
                        k -> hierarchy.child(parent, k));
            }
            int up = hierarchy.node(a).parentOrdinal();
            if (up >= 0) {
                path[a] *= path[up];
            }
        }
        long[] transitions = model.transitionsView();
        double[] current = new double[concepts];
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
        double[] next = new double[concepts];
        for (int i = 0; i < leaves; i++) {
            double q = current[hierarchy.leaf(i)] / total;
            current[hierarchy.leaf(i)] = q;
            long fromSum = 0;
            for (int j = 0; j < leaves; j++) {
                fromSum += transitions[i * leaves + j];
            }
            double denominator = fromSum + (double) leaves;
            for (int j = 0; j < leaves; j++) {
                next[hierarchy.leaf(j)] += q * ((transitions[i * leaves + j] + 1.0) / denominator);
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
        return new ConceptScores(similarity, current, next);
    }

    /** Writes the sibling-normalized evidence weights of one group into {@code path}. */
    private void evidenceGroup(ConceptModel model, double[] similarity, double[] path, double temperature,
                                      int size, IntUnaryOperator member) {
        double best = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < size; k++) {
            int a = member.applyAsInt(k);
            if (model.exemplarCount(a) > 0) {
                best = Math.max(best, similarity[a]);
            }
        }
        if (best == Double.NEGATIVE_INFINITY) {
            for (int k = 0; k < size; k++) {
                path[member.applyAsInt(k)] = 1.0 / size;
            }
            return;
        }
        double sum = 0.0;
        for (int k = 0; k < size; k++) {
            int a = member.applyAsInt(k);
            double weight = model.exemplarCount(a) > 0 ? StrictMath.exp((similarity[a] - best) / temperature) : 0.0;
            path[a] = weight;
            sum += weight;
        }
        for (int k = 0; k < size; k++) {
            path[member.applyAsInt(k)] /= sum;
        }
    }

    /** Returns the transition-count row of the previous concept (summed over its leaves), or the leaf marginal. */
    private long[] priorRow(ConceptHierarchy hierarchy, ConceptModel model, int previous) {
        int leaves = hierarchy.leafCount();
        if (previous < 0) {
            return model.leafCountsView().clone();
        }
        long[] transitions = model.transitionsView();
        long[] row = new long[leaves];
        for (int i = 0; i < leaves; i++) {
            if (hierarchy.inSubtree(hierarchy.leaf(i), previous)) {
                for (int j = 0; j < leaves; j++) {
                    row[j] += transitions[i * leaves + j];
                }
            }
        }
        return row;
    }

    private ConceptPrediction descend(ConceptHierarchy hierarchy, ConceptModel model, PredictionBudget budget,
                                             SpectralPattern evidence, double[] feature, ConceptScores scores) {
        ConceptThresholds thresholds = model.thresholds();
        List<ConceptLevel> levels = new ArrayList<>(hierarchy.levels());
        int comparisons = model.scorableConcepts();
        int scored = 0;
        int selected = -1;
        for (int depth = 0; ; depth++) {
            int parent = selected;
            int size = parent < 0 ? hierarchy.rootCount() : hierarchy.childCount(parent);
            double parentMass = 0.0;
            for (int k = 0; k < size; k++) {
                parentMass += scores.nextScore(member(hierarchy, parent, k));
            }
            ConceptLevel level = level(hierarchy, model, budget, evidence, feature, scores, depth, parent, size,
                    parentMass);
            scored += size;
            PredictionWork work = new PredictionWork(comparisons, scored, depth + 1);
            ConceptCandidate top = level.top();
            AbstentionReason failure = null;
            if (top.margin() < thresholds.margin(depth)) {
                failure = AbstentionReason.LOW_MARGIN;
            } else if (top.score() < thresholds.confidence(depth)) {
                failure = AbstentionReason.LOW_CONFIDENCE;
            }
            if (failure != null) {
                if (depth == 0) {
                    return new ConceptAbstention(failure, List.of(level), Optional.of(scores), work, failure
                            + " at depth 0: top score " + top.score() + ", margin " + top.margin());
                }
                return new ConceptCandidates(levels, Optional.of(failure), Optional.of(level), scores, work);
            }
            levels.add(level);
            selected = top.conceptOrdinal();
            if (hierarchy.isLeaf(selected)) {
                return new ConceptCandidates(levels, Optional.empty(), Optional.empty(), scores, work);
            }
            if (depth + 1 >= budget.maxLevels()) {
                return new ConceptCandidates(levels, Optional.of(AbstentionReason.BUDGET_EXHAUSTED),
                        Optional.empty(), scores, work);
            }
        }
    }

    private int member(ConceptHierarchy hierarchy, int parent, int k) {
        return parent < 0 ? hierarchy.root(k) : hierarchy.child(parent, k);
    }

    /**
     * Ranks one group with a bounded insertion selection of the top {@code K} (at least two, so the runner-up is
     * known even when one candidate is reported), then builds the reported candidates.
     */
    private ConceptLevel level(ConceptHierarchy hierarchy, ConceptModel model, PredictionBudget budget,
                                      SpectralPattern evidence, double[] feature, ConceptScores scores, int depth,
                                      int parent, int size, double parentMass) {
        int keep = Math.min(size, Math.max(2, budget.maxCandidatesPerLevel()));
        int report = Math.min(keep, budget.maxCandidatesPerLevel());
        int[] top = new int[keep];
        double[] topScores = new double[keep];
        int filled = 0;
        for (int k = 0; k < size; k++) {
            int concept = member(hierarchy, parent, k);
            double score = parentMass > 0.0 ? Math.clamp(scores.nextScore(concept) / parentMass, 0.0, 1.0) : 0.0;
            int at = filled;
            while (at > 0 && before(score, concept, topScores[at - 1], top[at - 1])) {
                at--;
            }
            if (at >= keep) {
                continue;
            }
            int last = Math.min(filled, keep - 1);
            System.arraycopy(top, at, top, at + 1, last - at);
            System.arraycopy(topScores, at, topScores, at + 1, last - at);
            top[at] = concept;
            topScores[at] = score;
            filled = Math.min(filled + 1, keep);
        }
        double runnerUp = keep > 1 ? topScores[1] : 0.0;
        List<ConceptCandidate> ranked = new ArrayList<>(report);
        for (int r = 0; r < report; r++) {
            double margin = r == 0 ? topScores[0] - runnerUp : topScores[r] - topScores[0];
            ranked.add(new ConceptCandidate(top[r], depth, topScores[r], margin,
                    supporting(model, evidence, feature, top[r])));
        }
        return new ConceptLevel(depth, ranked);
    }

    private boolean before(double score, int concept, double otherScore, int otherConcept) {
        int byScore = Double.compare(score, otherScore);
        return byScore > 0 || (byScore == 0 && concept < otherConcept);
    }

    /**
     * Returns the positions of the evidence components with the largest positive contribution
     * {@code f_k · c_k} to the concept's similarity, strongest first, ties to the lower position.
     */
    private List<Integer> supporting(ConceptModel model, SpectralPattern evidence, double[] feature,
                                            int concept) {
        if (model.exemplarCount(concept) == 0) {
            return List.of();
        }
        double[] centroids = model.centroidsView();
        int offset = concept * model.bins();
        int[] positions = new int[MAX_SUPPORTING_COMPONENTS];
        double[] contributions = new double[MAX_SUPPORTING_COMPONENTS];
        int filled = 0;
        for (int i = 0; i < evidence.componentCount(); i++) {
            int bin = evidence.index(i);
            double contribution = feature[bin] * centroids[offset + bin];
            if (!(contribution > 0.0)) {
                continue;
            }
            int at = filled;
            while (at > 0 && contribution > contributions[at - 1]) {
                at--;
            }
            if (at >= MAX_SUPPORTING_COMPONENTS) {
                continue;
            }
            int last = Math.min(filled, MAX_SUPPORTING_COMPONENTS - 1);
            System.arraycopy(positions, at, positions, at + 1, last - at);
            System.arraycopy(contributions, at, contributions, at + 1, last - at);
            positions[at] = i;
            contributions[at] = contribution;
            filled = Math.min(filled + 1, MAX_SUPPORTING_COMPONENTS);
        }
        Integer[] boxed = new Integer[filled];
        for (int r = 0; r < filled; r++) {
            boxed[r] = positions[r];
        }
        return List.of(boxed);
    }

    /**
     * Orders two candidates of one level: negative when {@code first} ranks before {@code second} (score descending,
     * then lower ordinal). Class-level: it is the contract's ordering of candidate values (section 8.4), shared with
     * {@link ConceptLevel}'s validation, and reads no predictor state.
     */
    static int rank(ConceptCandidate first, ConceptCandidate second) {
        int byScore = Double.compare(second.score(), first.score());
        return byScore != 0 ? byScore : Integer.compare(first.conceptOrdinal(), second.conceptOrdinal());
    }
}
