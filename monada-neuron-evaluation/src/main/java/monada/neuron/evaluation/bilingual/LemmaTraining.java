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
 * <p>Weights are exact integers in units of {@code 1 / unitsPerItem}, where {@code unitsPerItem} is divisible by every
 * possible gold set size. Summing {@code 1/m} as doubles depends on the order of the items and misses inclusive
 * boundaries and ties (18 thirds plus 4 sum to 9.999999999999998), and every registered gate and tie-break on these
 * weights must hold exactly.
 *
 * @param inventory the lemma's frozen inventory
 * @param examples the training items, in training-file order
 * @param unitsPerItem the total units of one item
 * @param units per item, the gold units of each cluster ordinal
 * @param clusterUnits per cluster, the total training units
 */
record LemmaTraining(SenseInventory inventory, List<TrainingExample> examples, long unitsPerItem, long[][] units,
                     long[] clusterUnits) {

    /** Returns the units of one item for a lemma with {@code clusters} clusters: {@code lcm(1..clusters)}. */
    static long unitsPerItem(int clusters) {
        long lcm = 1;
        for (int m = 2; m <= clusters; m++) {
            lcm = lcm / gcd(lcm, m) * m;
        }
        return lcm;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

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
            long perItem = unitsPerItem(k);
            var units = new long[examples.size()][k];
            var clusterUnits = new long[k];
            for (int i = 0; i < examples.size(); i++) {
                SenseGold.Answerable gold = examples.get(i).gold();
                if (Integer.numberOfTrailingZeros(Integer.highestOneBit(gold.mask())) >= k) {
                    throw new IllegalArgumentException("gold cluster outside the inventory of " + entry.getKey());
                }
                long share = perItem / gold.size();
                for (int c = 0; c < k; c++) {
                    if (gold.contains(c)) {
                        units[i][c] = share;
                        clusterUnits[c] += share;
                    }
                }
            }
            grouped.put(entry.getKey(), new LemmaTraining(inventory, examples, perItem, units, clusterUnits));
        }
        return grouped;
    }

    /** Returns the lowest-ordinal cluster with positive training weight, or 0 when none has any. */
    int lowestSupported() {
        for (int c = 0; c < clusterUnits.length; c++) {
            if (clusterUnits[c] > 0) {
                return c;
            }
        }
        return 0;
    }
}
