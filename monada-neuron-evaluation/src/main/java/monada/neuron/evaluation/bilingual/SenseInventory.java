package monada.neuron.evaluation.bilingual;

import java.util.List;
import java.util.Objects;

/**
 * The frozen inventory of one {@code (direction, lemma)}: its translation clusters in ordinal order, each listing its
 * acceptable target expressions. Every tie-break of the protocol uses the lower ordinal.
 *
 * @param direction language direction
 * @param lemma the candidate-table lemma
 * @param clusters acceptable target expressions per cluster, indexed by cluster ordinal; at least two clusters
 */
record SenseInventory(Direction direction, String lemma, List<List<String>> clusters) {

    /** The largest cluster count a gold mask can hold. */
    static final int MAX_CLUSTERS = 31;

    /** Validates the cluster count and copies the clusters. */
    SenseInventory {
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(lemma, "lemma must not be null");
        clusters = clusters.stream().map(List::copyOf).toList();
        if (clusters.size() < 2 || clusters.size() > MAX_CLUSTERS) {
            throw new IllegalArgumentException("an inventory needs 2.." + MAX_CLUSTERS + " clusters, got "
                    + clusters.size() + " for " + lemma);
        }
    }

    /** Returns the number of clusters {@code K}. */
    int size() {
        return clusters.size();
    }
}
