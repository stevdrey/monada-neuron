package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Builds a {@link ConceptModel} from caller-owned, host-labeled sequences (contract {@code ncm-wave-cognition/1}
 * section 8.2, ADR 0043). Stateless and deterministic: the same sequences in the same order give a bit-identical
 * model, so a model can always be rebuilt by replaying its exemplars. Nothing is learned from unlabeled data, and no
 * label is invented.
 *
 * <p><b>Algorithm.</b> One pass over the windows in sequence order: each non-silent window adds its unit feature
 * ({@link ConceptFeatures}) to its leaf and every ancestor, every window counts towards its leaf marginal, and each
 * consecutive pair of a sequence counts one leaf-to-leaf transition. The centroid sums are then scaled to unit norm.
 * Cost: O(W · (bins · depth)) time for W windows, O(concepts · bins + leaves²) memory, one feature buffer.
 *
 * <p>Invalid training data is a caller bug ({@link IllegalArgumentException}): a non-DFT frame, a window whose
 * pattern has another version or frame, or a label that is not a leaf of the hierarchy.
 */
public final class ConceptModelTrainer {

    /** Largest number of training sequences. */
    public static final int MAX_SEQUENCES = 65_536;

    /** Creates a stateless trainer. */
    public ConceptModelTrainer() {
    }

    /**
     * Trains a snapshot with {@link ConceptThresholds#NONE}; thresholds are chosen separately on non-test data.
     *
     * @param hierarchy the concepts
     * @param frame the DFT frame every window and every later evidence pattern must have
     * @param sequences labeled training sequences, at most {@link #MAX_SEQUENCES}, possibly empty
     * @param temperature finite positive softmax temperature over centroid similarities
     * @return the model
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the data breaks a rule of this class
     */
    public ConceptModel train(ConceptHierarchy hierarchy, SpectralFrame frame, List<LabeledSequence> sequences,
                              double temperature) {
        Objects.requireNonNull(hierarchy, "hierarchy must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(sequences, "sequences must not be null");
        if (frame.basis() != SpectralBasis.DFT) {
            throw new IllegalArgumentException("the reference predictor reads DFT patterns, got: " + frame.basis());
        }
        if (sequences.size() > MAX_SEQUENCES) {
            throw new IllegalArgumentException("at most " + MAX_SEQUENCES + " sequences, got: " + sequences.size());
        }
        if (!Double.isFinite(temperature) || temperature <= 0.0) {
            throw new IllegalArgumentException("temperature must be finite and positive, got: " + temperature);
        }
        int concepts = hierarchy.size();
        int leaves = hierarchy.leafCount();
        int bins = frame.componentCapacity();
        double[] centroids = new double[concepts * bins];
        long[] exemplarCounts = new long[concepts];
        long[] transitions = new long[leaves * leaves];
        long[] leafCounts = new long[leaves];
        double[] feature = new double[bins];
        for (LabeledSequence sequence : sequences) {
            Objects.requireNonNull(sequence, "sequences must not contain null");
            int previousLeaf = -1;
            for (LabeledSequence.Window window : sequence.windows()) {
                int leaf = leafOf(hierarchy, window.conceptCode());
                requireFrame(window.pattern(), frame);
                int position = hierarchy.leafPosition(leaf);
                leafCounts[position]++;
                if (previousLeaf >= 0) {
                    transitions[previousLeaf * leaves + position]++;
                }
                previousLeaf = position;
                if (ConceptFeatures.extract(window.pattern(), feature)) {
                    for (int concept = leaf; concept >= 0; concept = hierarchy.node(concept).parentOrdinal()) {
                        int offset = concept * bins;
                        for (int k = 0; k < bins; k++) {
                            centroids[offset + k] += feature[k];
                        }
                        exemplarCounts[concept]++;
                    }
                }
            }
        }
        for (int concept = 0; concept < concepts; concept++) {
            normalize(centroids, concept * bins, bins);
        }
        return new ConceptModel(hierarchy, frame, centroids, exemplarCounts, transitions, leafCounts, temperature,
                ConceptThresholds.NONE);
    }

    private static int leafOf(ConceptHierarchy hierarchy, long code) {
        OptionalInt ordinal = hierarchy.ordinalOf(code);
        if (ordinal.isEmpty()) {
            throw new IllegalArgumentException("label " + code + " is not a concept of the hierarchy");
        }
        if (!hierarchy.isLeaf(ordinal.getAsInt())) {
            throw new IllegalArgumentException("label " + code + " is not a leaf concept");
        }
        return ordinal.getAsInt();
    }

    private static void requireFrame(SpectralPattern pattern, SpectralFrame frame) {
        if (pattern.version() != SpectralPattern.CURRENT_VERSION || !pattern.frame().equals(frame)) {
            throw new IllegalArgumentException("training pattern does not match the model frame: "
                    + pattern.frame() + " (version " + pattern.version() + "), expected " + frame);
        }
    }

    /** Scales one centroid sum to unit norm with a power-of-two prescale; a zero sum stays zero. */
    private static void normalize(double[] values, int offset, int length) {
        double peak = 0.0;
        for (int k = 0; k < length; k++) {
            peak = Math.max(peak, values[offset + k]);
        }
        if (peak == 0.0) {
            return;
        }
        int exponent = Math.getExponent(peak);
        double sum = 0.0;
        for (int k = 0; k < length; k++) {
            double scaled = Math.scalb(values[offset + k], -exponent);
            values[offset + k] = scaled;
            sum += scaled * scaled;
        }
        double norm = StrictMath.sqrt(sum);
        for (int k = 0; k < length; k++) {
            values[offset + k] /= norm;
        }
    }
}
