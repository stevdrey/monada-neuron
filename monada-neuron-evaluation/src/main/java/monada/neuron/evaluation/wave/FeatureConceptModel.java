package monada.neuron.evaluation.wave;

import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptThresholds;

import java.util.Objects;

/**
 * Immutable snapshot of the ablation's reference scorer ({@link FeatureConceptScorer}): the content of the core
 * {@code ConceptModel} (unit centroid per concept, exemplar counts, leaf transition and marginal counts, temperature,
 * per-depth thresholds) over any {@link ConceptFeatureMap}, not only DFT magnitudes. It lives in the evaluation module
 * because the core model is DFT-only by design (ADR 0043, ADR 0044); a Haar-capable core model would need its own
 * decision and gate.
 *
 * <p>Arrays are owned and never exposed; layouts are flat and row-major ({@code concepts × dimension} centroids,
 * {@code leaves × leaves} transitions by leaf position), as in the core model.
 */
final class FeatureConceptModel {

    private final ConceptHierarchy hierarchy;
    private final ConceptFeatureMap features;
    private final int dimension;
    private final double[] centroids;
    private final long[] exemplarCounts;
    private final long[] transitions;
    private final long[] leafCounts;
    private final double temperature;
    private final ConceptThresholds thresholds;
    private final int scorable;

    FeatureConceptModel(ConceptHierarchy hierarchy, ConceptFeatureMap features, int dimension, double[] centroids,
                        long[] exemplarCounts, long[] transitions, long[] leafCounts, double temperature,
                        ConceptThresholds thresholds) {
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy must not be null");
        this.features = Objects.requireNonNull(features, "features must not be null");
        this.dimension = dimension;
        this.centroids = centroids;
        this.exemplarCounts = exemplarCounts;
        this.transitions = transitions;
        this.leafCounts = leafCounts;
        this.temperature = temperature;
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds must not be null");
        int count = 0;
        for (long exemplars : exemplarCounts) {
            count += exemplars > 0 ? 1 : 0;
        }
        this.scorable = count;
    }

    /** Returns a copy with other thresholds, sharing the immutable arrays. */
    FeatureConceptModel withThresholds(ConceptThresholds other) {
        return new FeatureConceptModel(hierarchy, features, dimension, centroids, exemplarCounts, transitions,
                leafCounts, temperature, other);
    }

    ConceptHierarchy hierarchy() {
        return hierarchy;
    }

    ConceptFeatureMap features() {
        return features;
    }

    int dimension() {
        return dimension;
    }

    double temperature() {
        return temperature;
    }

    ConceptThresholds thresholds() {
        return thresholds;
    }

    /** Returns the number of concepts with at least one exemplar: the comparisons of one prediction. */
    int scorableConcepts() {
        return scorable;
    }

    long exemplarCount(int ordinal) {
        return exemplarCounts[ordinal];
    }

    /** Returns the training count of transitions between two leaves, by ordinal. */
    long transitionCount(int from, int to) {
        int leaves = hierarchy.leafCount();
        return transitions[hierarchy.leafPosition(from) * leaves + hierarchy.leafPosition(to)];
    }

    /** Returns the centroid component of a concept; read-only access for the scorer. */
    double centroid(int ordinal, int k) {
        return centroids[ordinal * dimension + k];
    }

    /** Returns the transition count by leaf position; read-only access for the scorer. */
    long transitionAt(int fromPosition, int toPosition) {
        return transitions[fromPosition * hierarchy.leafCount() + toPosition];
    }

    /** Returns the training count of a leaf by position. */
    long leafCountAt(int position) {
        return leafCounts[position];
    }

    /** Returns the bytes of the retained arrays (8 per element), the snapshot's footprint without object headers. */
    long retainedBytes() {
        return 8L * (centroids.length + exemplarCounts.length + transitions.length + leafCounts.length);
    }
}
