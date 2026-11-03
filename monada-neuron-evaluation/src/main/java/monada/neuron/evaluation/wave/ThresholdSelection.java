package monada.neuron.evaluation.wave;

import monada.neuron.reasoning.concept.ConceptThresholds;

import java.util.function.Function;

/**
 * The pre-registered threshold selection rule of the G3 protocol ({@code docs/benchmarks/next-concept-g3.md}), shared
 * by every arm that selects thresholds on the threshold-selection split, so all of them are chosen by one rule.
 *
 * <ul>
 *   <li>{@link #select}: the four-way grid {@link #CONFIDENCE_GRID} × {@link #MARGIN_GRID} at depths 0 and 1, in
 *       that nesting order; the best covered accuracy among points whose leaf coverage reaches the target, ties to
 *       the higher coverage, the first in grid order otherwise; {@link ConceptThresholds#NONE} when no point reaches
 *       the target.</li>
 *   <li>{@link #matchFlat}: the flat grid point ({@link #FLAT_CONFIDENCE_GRID} × {@link #FLAT_MARGIN_GRID}) whose
 *       coverage is closest to a given coverage, the first in grid order on ties.</li>
 * </ul>
 * A scoring function returns {@code [leaf coverage, accuracy on covered]} on the threshold-selection split.
 */
final class ThresholdSelection {

    /** Per-depth confidence grid. */
    static final double[] CONFIDENCE_GRID = {0.0, 0.6, 0.7, 0.8, 0.9};
    /** Per-depth margin grid. */
    static final double[] MARGIN_GRID = {0.0, 0.1, 0.2, 0.3};
    /** Flat confidence grid: {@code 0.00, 0.05, ..., 0.95}. */
    static final double[] FLAT_CONFIDENCE_GRID = grid(20, 0.05);
    /** Flat margin grid. */
    static final double[] FLAT_MARGIN_GRID = {0.0, 0.05, 0.1, 0.2};

    /** Creates the stateless rule. */
    ThresholdSelection() {
    }

    /**
     * Applies the grid rule.
     *
     * @param score {@code [coverage, covered accuracy]} under candidate thresholds; also scores
     *     {@link ConceptThresholds#NONE} when no point reaches the target
     * @param target the leaf coverage a point must reach
     */
    Chosen select(Function<ConceptThresholds, double[]> score, double target) {
        ConceptThresholds chosen = ConceptThresholds.NONE;
        double chosenAccuracy = -1.0;
        double chosenCoverage = -1.0;
        boolean reached = false;
        for (double c0 : CONFIDENCE_GRID) {
            for (double m0 : MARGIN_GRID) {
                for (double c1 : CONFIDENCE_GRID) {
                    for (double m1 : MARGIN_GRID) {
                        var thresholds = ConceptThresholds.of(new double[] {c0, c1}, new double[] {m0, m1});
                        double[] covered = score.apply(thresholds);
                        if (covered[0] >= target && (!reached || covered[1] > chosenAccuracy
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
            double[] open = score.apply(ConceptThresholds.NONE);
            chosenCoverage = open[0];
            chosenAccuracy = open[1];
        }
        return new Chosen(chosen, chosenCoverage, chosenAccuracy, reached);
    }

    /**
     * Returns the flat grid point closest in coverage to {@code coverage}.
     *
     * @param score {@code [coverage, covered accuracy]} of the flat decision at {@code (confidence, margin)}
     */
    Flat matchFlat(FlatScore score, double coverage) {
        double flatConfidence = 0.0;
        double flatMargin = 0.0;
        double flatCoverage = -1.0;
        double flatAccuracy = 0.0;
        double gap = Double.POSITIVE_INFINITY;
        for (double c : FLAT_CONFIDENCE_GRID) {
            for (double m : FLAT_MARGIN_GRID) {
                double[] covered = score.apply(c, m);
                double distance = Math.abs(covered[0] - coverage);
                if (distance < gap) {
                    gap = distance;
                    flatConfidence = c;
                    flatMargin = m;
                    flatCoverage = covered[0];
                    flatAccuracy = covered[1];
                }
            }
        }
        return new Flat(flatConfidence, flatMargin, flatCoverage, flatAccuracy);
    }

    private static double[] grid(int count, double step) {
        var values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = StrictMath.rint(i * step * 100.0) / 100.0;
        }
        return values;
    }

    /** Scores a flat decision at a confidence and margin threshold. */
    @FunctionalInterface
    interface FlatScore {
        double[] apply(double confidence, double margin);
    }

    /** The selected thresholds with their threshold-split coverage and accuracy, and whether the target was met. */
    record Chosen(ConceptThresholds thresholds, double coverage, double accuracy, boolean reached) {
    }

    /** The selected flat thresholds with their threshold-split coverage and accuracy. */
    record Flat(double confidence, double margin, double coverage, double accuracy) {
    }
}
