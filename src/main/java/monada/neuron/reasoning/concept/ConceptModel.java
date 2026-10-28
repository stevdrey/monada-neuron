package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SpectralFrame;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, caller-owned model snapshot of the next-concept predictor (contract {@code ncm-wave-cognition/1}
 * sections 3.3 and 8.2, ADR 0043). It is built only by {@link ConceptModelTrainer} from host-labeled sequences and is
 * never updated in place: Neuron keeps nothing after a call, and later updates (#129) produce a new snapshot.
 *
 * <p><b>Content.</b>
 * <ul>
 *   <li>the {@link ConceptHierarchy} and the expected DFT {@link SpectralFrame}: evidence with any other frame or
 *       pattern version is {@link AbstentionReason#INCOMPATIBLE_INPUT};</li>
 *   <li>one spectral centroid per concept (an inner concept pools every exemplar of its leaves), unit-normalized, in
 *       one contiguous {@code double[concepts × bins]} array, and the number of exemplars behind each; a concept with
 *       no exemplar has a zero centroid and is never scored;</li>
 *   <li>leaf-to-leaf transition counts ({@code long[leaves × leaves]}, row = current leaf) and the leaf marginal
 *       counts, from consecutive labeled windows;</li>
 *   <li>the softmax temperature over centroid similarities and the per-depth {@link ConceptThresholds}.</li>
 * </ul>
 *
 * <p>Memory is {@code 8 · concepts · bins} bytes for the centroids plus {@code 8 · leaves²} for the transitions:
 * about 4.2 MB at the largest bounds (256 concepts, 2,049 bins), 6.2 kB for the medium workload. {@link
 * #retainedBytes()} reports the payload.
 */
public final class ConceptModel {

    private final ConceptHierarchy hierarchy;
    private final SpectralFrame frame;
    private final int bins;
    private final double[] centroids;
    private final long[] exemplarCounts;
    private final long[] transitions;
    private final long[] leafCounts;
    private final int scorableConcepts;
    private final double temperature;
    private final ConceptThresholds thresholds;

    ConceptModel(ConceptHierarchy hierarchy, SpectralFrame frame, double[] centroids, long[] exemplarCounts,
                 long[] transitions, long[] leafCounts, double temperature, ConceptThresholds thresholds) {
        this.hierarchy = hierarchy;
        this.frame = frame;
        this.bins = frame.componentCapacity();
        this.centroids = centroids;
        this.exemplarCounts = exemplarCounts;
        this.transitions = transitions;
        this.leafCounts = leafCounts;
        int scorable = 0;
        for (long count : exemplarCounts) {
            if (count > 0) {
                scorable++;
            }
        }
        this.scorableConcepts = scorable;
        this.temperature = temperature;
        this.thresholds = thresholds;
    }

    /**
     * Returns this snapshot with other thresholds; the trained data is shared, not copied.
     *
     * @param thresholds thresholds chosen on non-test data
     * @return the new snapshot
     */
    public ConceptModel withThresholds(ConceptThresholds thresholds) {
        Objects.requireNonNull(thresholds, "thresholds must not be null");
        return new ConceptModel(hierarchy, frame, centroids, exemplarCounts, transitions, leafCounts, temperature,
                thresholds);
    }

    /** Returns the hierarchy. */
    public ConceptHierarchy hierarchy() {
        return hierarchy;
    }

    /** Returns the expected spectral frame of the evidence. */
    public SpectralFrame frame() {
        return frame;
    }

    /** Returns the softmax temperature over centroid similarities. */
    public double temperature() {
        return temperature;
    }

    /** Returns the per-depth thresholds. */
    public ConceptThresholds thresholds() {
        return thresholds;
    }

    /** Returns the number of non-silent exemplars behind a concept's centroid. */
    public long exemplarCount(int ordinal) {
        return exemplarCounts[Objects.checkIndex(ordinal, exemplarCounts.length)];
    }

    /** Returns the number of concepts with at least one exemplar: the comparisons of one prediction. */
    public int scorableConcepts() {
        return scorableConcepts;
    }

    /** Returns the number of labeled windows of a leaf, including silent ones. */
    public long leafCount(int leafOrdinal) {
        return leafCounts[requireLeaf(leafOrdinal)];
    }

    /** Returns how often leaf {@code to} followed leaf {@code from} in the training sequences. */
    public long transitionCount(int from, int to) {
        return transitions[requireLeaf(from) * hierarchy.leafCount() + requireLeaf(to)];
    }

    /** Returns a copy of a concept's unit centroid, zero for a concept without exemplars. */
    public double[] centroid(int ordinal) {
        int offset = Objects.checkIndex(ordinal, hierarchy.size()) * bins;
        return Arrays.copyOfRange(centroids, offset, offset + bins);
    }

    /** Returns the bytes of the trained arrays (centroids, exemplar, transition and leaf counts). */
    public long retainedBytes() {
        return 8L * (centroids.length + exemplarCounts.length + transitions.length + leafCounts.length);
    }

    int bins() {
        return bins;
    }

    double[] centroidsView() {
        return centroids;
    }

    long[] transitionsView() {
        return transitions;
    }

    long[] leafCountsView() {
        return leafCounts;
    }

    private int requireLeaf(int ordinal) {
        int position = hierarchy.leafPosition(ordinal);
        if (position < 0) {
            throw new IllegalArgumentException("concept " + ordinal + " is not a leaf");
        }
        return position;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConceptModel that && hierarchy.equals(that.hierarchy) && frame.equals(that.frame)
                && Arrays.equals(centroids, that.centroids) && Arrays.equals(exemplarCounts, that.exemplarCounts)
                && Arrays.equals(transitions, that.transitions) && Arrays.equals(leafCounts, that.leafCounts)
                && Double.compare(temperature, that.temperature) == 0 && thresholds.equals(that.thresholds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hierarchy, frame, Arrays.hashCode(centroids), Arrays.hashCode(transitions), temperature,
                thresholds);
    }

    @Override
    public String toString() {
        return "ConceptModel[" + hierarchy + ", frame=" + frame + ", temperature=" + temperature + ", "
                + thresholds + "]";
    }
}
