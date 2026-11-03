package monada.neuron.evaluation.wave;

import java.util.Objects;
import java.util.Random;

/**
 * Ground-truth leaf ordinals of one split, kept apart from its waves, with the leak guard every fixture shares.
 *
 * <p>Guarded labels (held-out splits) count every read before {@link #openForScoring()} as a leak; an evaluation
 * fails its leakage check when {@link #earlyReads()} is not zero. Only the scorer opens them, after every arm has
 * answered. Training and threshold-selection labels are open from the start. A guarded read still returns the label,
 * so a leak is reported rather than hidden by an exception.
 */
final class GuardedLabels {

    private final int[] ordinals;
    private boolean open;
    private int earlyReads;

    GuardedLabels(int[] ordinals, boolean guarded) {
        this.ordinals = ordinals.clone();
        this.open = !guarded;
    }

    int size() {
        return ordinals.length;
    }

    /** Returns the label ordinal of a window, counting a leak when the labels are still guarded. */
    int ordinal(int window) {
        Objects.checkIndex(window, ordinals.length);
        if (!open) {
            earlyReads++;
        }
        return ordinals[window];
    }

    void openForScoring() {
        open = true;
    }

    int earlyReads() {
        return earlyReads;
    }

    /**
     * Returns guarded labels with the same multiset of values in a seeded random order, for the counterfactual
     * leakage check: arm answers must not change when the labels do.
     */
    GuardedLabels permuted(Random random) {
        int[] shuffled = ordinals.clone();
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = swap;
        }
        return new GuardedLabels(shuffled, true);
    }
}
