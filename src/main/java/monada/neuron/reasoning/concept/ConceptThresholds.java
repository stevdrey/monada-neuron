package monada.neuron.reasoning.concept;

import java.util.Arrays;
import java.util.Objects;

/**
 * Per-depth confidence and margin thresholds {@code τ_conf[d]} and {@code τ_margin[d]} of the coarse-to-fine
 * descent (contract {@code ncm-wave-cognition/1} sections 8.2 and 8.4, ADR 0043).
 *
 * <p>The thresholds are snapshot parameters chosen by the caller on training or threshold-selection data only, never
 * on held-out data, and reported with every evaluation. They are per depth because the scores of different depths
 * are on different scales: a depth with two candidates has a top score of at least {@code 0.5}, one with sixteen
 * may have far less. Depths beyond the given arrays use the last entry.
 */
public final class ConceptThresholds {

    /** Thresholds that never abstain: confidence and margin {@code 0} at every depth. */
    public static final ConceptThresholds NONE = of(new double[] {0.0}, new double[] {0.0});

    private final double[] confidence;
    private final double[] margin;

    private ConceptThresholds(double[] confidence, double[] margin) {
        this.confidence = confidence;
        this.margin = margin;
    }

    /**
     * Builds thresholds from copies of the arrays.
     *
     * @param confidence minimum top score per depth, each in {@code [0, 1]}
     * @param margin minimum top-minus-runner-up difference per depth, each in {@code [0, 1]}
     * @return the thresholds
     * @throws IllegalArgumentException if the arrays differ in length, have {@code 0} or more than
     *     {@link ConceptHierarchy#MAX_DEPTH} entries, or hold a value outside {@code [0, 1]}
     */
    public static ConceptThresholds of(double[] confidence, double[] margin) {
        Objects.requireNonNull(confidence, "confidence must not be null");
        Objects.requireNonNull(margin, "margin must not be null");
        if (confidence.length != margin.length || confidence.length == 0
                || confidence.length > ConceptHierarchy.MAX_DEPTH) {
            throw new IllegalArgumentException("thresholds need 1.." + ConceptHierarchy.MAX_DEPTH
                    + " entries of equal length, got: " + confidence.length + " and " + margin.length);
        }
        double[] confidenceCopy = confidence.clone();
        double[] marginCopy = margin.clone();
        for (int d = 0; d < confidenceCopy.length; d++) {
            requireUnit(confidenceCopy[d], "confidence", d);
            requireUnit(marginCopy[d], "margin", d);
        }
        return new ConceptThresholds(confidenceCopy, marginCopy);
    }

    private static void requireUnit(double value, String name, int depth) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " at depth " + depth + " must be in [0, 1], got: " + value);
        }
    }

    /** Returns {@code τ_conf} at a depth. */
    public double confidence(int depth) {
        return confidence[Math.min(requireDepth(depth), confidence.length - 1)];
    }

    /** Returns {@code τ_margin} at a depth. */
    public double margin(int depth) {
        return margin[Math.min(requireDepth(depth), margin.length - 1)];
    }

    private int requireDepth(int depth) {
        return Objects.checkIndex(depth, ConceptHierarchy.MAX_DEPTH);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConceptThresholds that && Arrays.equals(confidence, that.confidence)
                && Arrays.equals(margin, that.margin);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(confidence) + Arrays.hashCode(margin);
    }

    @Override
    public String toString() {
        return "ConceptThresholds[confidence=" + Arrays.toString(confidence) + ", margin="
                + Arrays.toString(margin) + "]";
    }
}
