package monada.neuron.evaluation.wave;

import java.util.Random;

/**
 * Ground-truth leaf labels of one split of the medium fixture, kept apart from its waves.
 *
 * <p>Labels of a {@link SplitRole#TEST} split are guarded: every read before {@link #openForScoring()} is counted as
 * a leak, and the evaluation fails its leakage check when {@link #earlyReads()} is not zero. Only the scorer opens
 * them, after every arm has scored. Training and threshold-selection labels are open from the start. The guard is
 * {@link GuardedLabels}, shared with the large fixture.
 */
public final class SplitLabels {

    private final GuardedLabels labels;

    SplitLabels(FixtureConcept[] labels, boolean guarded) {
        var ordinals = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            ordinals[i] = labels[i].ordinal();
        }
        this.labels = new GuardedLabels(ordinals, guarded);
    }

    private SplitLabels(GuardedLabels labels) {
        this.labels = labels;
    }

    /** Returns the number of labelled windows. */
    public int size() {
        return labels.size();
    }

    /**
     * Returns the leaf label of a window. A read of guarded labels before {@link #openForScoring()} is counted as a
     * leak; the label is still returned so the leak is reported rather than hidden by an exception.
     *
     * @param window window index in the split
     * @return the generating leaf concept
     */
    public FixtureConcept label(int window) {
        return FixtureConcept.values()[labels.ordinal(window)];
    }

    /** Opens guarded labels for the scorer. */
    public void openForScoring() {
        labels.openForScoring();
    }

    /** Returns the number of guarded reads made before the labels were opened. */
    public int earlyReads() {
        return labels.earlyReads();
    }

    /**
     * Returns guarded labels with the same multiset of values in a seeded random order, for the counterfactual
     * leakage check: arm scores must not change when the labels do.
     */
    SplitLabels permuted(Random random) {
        return new SplitLabels(labels.permuted(random));
    }
}
