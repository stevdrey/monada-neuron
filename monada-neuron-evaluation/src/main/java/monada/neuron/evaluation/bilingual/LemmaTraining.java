package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseArm.TrainingExample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code ANS} training items of one {@code (direction, lemma)} in training-file order, with their per-cluster gold
 * weights: an item with a gold set of {@code m} clusters contributes {@code 1/m} to each of them.
 *
 * @param inventory the lemma's frozen inventory
 * @param examples the training items, in training-file order
 * @param weights per item, the gold weight of each cluster ordinal
 * @param clusterWeights per cluster, the total training weight
 */
record LemmaTraining(SenseInventory inventory, List<TrainingExample> examples, double[][] weights,
                     double[] clusterWeights) {

    /** Groups training items by {@link SenseItem#lemmaKey()}, keeping training-file order within each lemma. */
    static Map<String, LemmaTraining> group(List<TrainingExample> training) {
        var byLemma = new LinkedHashMap<String, List<TrainingExample>>();
        for (TrainingExample example : training) {
            if (!example.input().hasInventory()) {
                throw new IllegalArgumentException("a training item needs an inventory: " + example.input().lemma());
            }
            byLemma.computeIfAbsent(example.input().lemmaKey(), _ -> new ArrayList<>()).add(example);
        }
        var grouped = new LinkedHashMap<String, LemmaTraining>();
        for (var entry : byLemma.entrySet()) {
            List<TrainingExample> examples = List.copyOf(entry.getValue());
            SenseInventory inventory = examples.getFirst().input().inventory();
            int k = inventory.size();
            var weights = new double[examples.size()][k];
            var clusterWeights = new double[k];
            for (int i = 0; i < examples.size(); i++) {
                SenseGold.Answerable gold = examples.get(i).gold();
                double share = 1.0 / gold.size();
                for (int c = 0; c < k; c++) {
                    if (gold.contains(c)) {
                        weights[i][c] = share;
                        clusterWeights[c] += share;
                    }
                }
                if (Integer.numberOfTrailingZeros(Integer.highestOneBit(gold.mask())) >= k) {
                    throw new IllegalArgumentException("gold cluster outside the inventory of " + entry.getKey());
                }
            }
            grouped.put(entry.getKey(), new LemmaTraining(inventory, examples, weights, clusterWeights));
        }
        return grouped;
    }

    /** Returns the lowest-ordinal cluster with positive training weight, or 0 when none has any. */
    int lowestSupported() {
        for (int c = 0; c < clusterWeights.length; c++) {
            if (clusterWeights[c] > 0.0) {
                return c;
            }
        }
        return 0;
    }
}
