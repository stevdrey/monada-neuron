package monada.neuron.evaluation.bilingual;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arm A, majority sense: per {@code (direction, lemma)}, the cluster with the largest training weight (ties to the
 * lower ordinal); {@code s} is its training share. Always answers (one grid point, {@code tau = 0}).
 *
 * <p>A lemma with an inventory but no training item has no defined majority; it gets the lowest ordinal with
 * {@code s = 0}. The step-7 gates exclude such a lemma from scoring.
 */
final class MajoritySenseArm implements SenseArm {

    private static final double[] ALWAYS = {0.0};

    private final Map<String, Prediction> majority = new HashMap<>();

    @Override
    public String name() {
        return "A";
    }

    @Override
    public int configurations() {
        return 1;
    }

    @Override
    public String describe(int configuration) {
        return "majority";
    }

    @Override
    public double[] thresholds() {
        return ALWAYS;
    }

    @Override
    public void fit(List<TrainingExample> training) {
        majority.clear();
        for (var entry : LemmaTraining.group(training).entrySet()) {
            long[] units = entry.getValue().clusterUnits();
            long total = 0;
            int best = 0;
            for (int c = 0; c < units.length; c++) {
                total += units[c];
                if (units[c] > units[best]) {
                    best = c;
                }
            }
            majority.put(entry.getKey(), new Prediction(best, (double) units[best] / total));
        }
    }

    @Override
    public Prediction predict(SenseItem item, int configuration) {
        if (!item.hasInventory()) {
            return Prediction.NO_INVENTORY;
        }
        return majority.getOrDefault(item.lemmaKey(), new Prediction(0, 0.0));
    }

    /** Returns the training-majority cluster of a lemma, or {@code -1} when it has no training item. */
    int majorityCluster(String lemmaKey) {
        Prediction prediction = majority.get(lemmaKey);
        return prediction == null ? -1 : prediction.choice();
    }

    @Override
    public long retainedBytes() {
        return majority.size() * 96L;
    }
}
