package monada.neuron.evaluation.wave;

import java.util.Objects;
import java.util.Random;

/**
 * Ground-truth leaf labels of one split, kept apart from its waves.
 *
 * <p>Labels of a {@link SplitRole#TEST} split are guarded: every read before {@link #openForScoring()} is counted as
 * a leak, and the evaluation fails its leakage check when {@link #earlyReads()} is not zero. Only the scorer opens
 * them, after every arm has scored. Training and threshold-selection labels are open from the start.
 */
public final class SplitLabels {

    private final FixtureConcept[] labels;
    private boolean open;
    private int earlyReads;

    SplitLabels(FixtureConcept[] labels, boolean guarded) {
        this.labels = labels.clone();
        this.open = !guarded;
    }

    /** Returns the number of labelled windows. */
    public int size() {
        return labels.length;
    }

    /**
     * Returns the leaf label of a window. A read of guarded labels before {@link #openForScoring()} is counted as a
     * leak; the label is still returned so the leak is reported rather than hidden by an exception.
     *
     * @param window window index in the split
     * @return the generating leaf concept
     */
    public FixtureConcept label(int window) {
        Objects.checkIndex(window, labels.length);
        if (!open) {
            earlyReads++;
        }
        return labels[window];
    }

    /** Opens guarded labels for the scorer. */
    public void openForScoring() {
        open = true;
    }

    /** Returns the number of guarded reads made before the labels were opened. */
    public int earlyReads() {
        return earlyReads;
    }

    /**
     * Returns guarded labels with the same multiset of values in a seeded random order, for the counterfactual
     * leakage check: arm scores must not change when the labels do.
     */
    SplitLabels permuted(Random random) {
        FixtureConcept[] shuffled = labels.clone();
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            FixtureConcept swap = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = swap;
        }
        return new SplitLabels(shuffled, true);
    }
}
