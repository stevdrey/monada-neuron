package monada.neuron.evaluation.bilingual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Gold labels of one split, kept apart from the items, with the leak guard of {@code wave.GuardedLabels}.
 *
 * <p>Guarded gold (the held-out split) counts every read before {@link #openForScoring()} as a leak; check
 * {@code leakage.test-labels} fails when {@link #earlyReads()} is not zero. A guarded read still returns the label, so a
 * leak is reported rather than hidden by an exception.
 */
final class GuardedGold {

    private final List<SenseGold> labels;
    private boolean open;
    private int earlyReads;

    GuardedGold(List<SenseGold> labels, boolean guarded) {
        this.labels = List.copyOf(labels);
        this.open = !guarded;
    }

    int size() {
        return labels.size();
    }

    /** Returns the gold of item {@code index}, counting a leak when the labels are still guarded. */
    SenseGold get(int index) {
        Objects.checkIndex(index, labels.size());
        if (!open) {
            earlyReads++;
        }
        return labels.get(index);
    }

    /** Validates every label against its item's inventory; returns nothing, so it reveals no label and is no leak. */
    void requireWithinInventory(List<SenseDataset.DatasetItem> items) {
        for (int i = 0; i < labels.size(); i++) {
            SenseDataset.requireWithinInventory(items.get(i), labels.get(i));
        }
    }

    void openForScoring() {
        open = true;
    }

    boolean isOpen() {
        return open;
    }

    int earlyReads() {
        return earlyReads;
    }

    /**
     * Returns guarded gold with the labels permuted within each group (one group per {@code (direction, lemma)}), for
     * check {@code nc.heldout-counterfactual}: arm outputs must not change when the labels do. The permutation is a
     * Fisher–Yates shuffle driven by {@code h(permute, <group>|<i>)}.
     *
     * @param groups the group key of each item
     */
    GuardedGold permutedWithin(List<String> groups, KeyedHash hash) {
        if (groups.size() != labels.size()) {
            throw new IllegalArgumentException("one group per label is required");
        }
        var members = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < groups.size(); i++) {
            members.computeIfAbsent(groups.get(i), _ -> new ArrayList<>()).add(i);
        }
        var permuted = new ArrayList<>(labels);
        for (var entry : members.entrySet()) {
            int[] order = hash.permutation(entry.getKey(), entry.getValue().size());
            List<Integer> indices = entry.getValue();
            for (int i = 0; i < indices.size(); i++) {
                permuted.set(indices.get(i), labels.get(indices.get(order[i])));
            }
        }
        return new GuardedGold(permuted, true);
    }
}
