package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseWindows.Window;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Arm B, nearest example: the Jaccard similarity of the window token sets (target excluded) to every training item of
 * the lemma; a vote of the {@code k} most similar, ties by the lower item in training-file order. Each neighbour votes
 * with its gold weights ({@code 1/m} per cluster of its gold set), so the votes of {@code k} neighbours sum to
 * {@code k}.
 *
 * <p>{@code k*} is the top vote (ties to the lower ordinal) and {@code s = (top vote share) x (highest similarity)}.
 * When every neighbour has similarity 0, {@code k*} is the lowest-ordinal cluster with positive training weight and
 * {@code s = 0}. Grid: {@code k in {1, 3, 5}} x {@code w in {3, 5, S}} x {@code tau in {0, 0.1, ..., 0.9}} (90 points).
 */
final class NearestExampleArm implements SenseArm {

    private static final int[] NEIGHBOURS = {1, 3, 5};

    /** Training sets of one lemma: per window, per item, the sorted distinct vocabulary ids. */
    private record LemmaModel(LemmaTraining training, Map<String, Integer> vocabulary, int[][][] sets) {
    }

    private final SenseWindows windows;
    private final Map<String, LemmaModel> models = new HashMap<>();

    NearestExampleArm(SenseWindows windows) {
        this.windows = windows;
    }

    @Override
    public String name() {
        return "B";
    }

    @Override
    public int configurations() {
        return NEIGHBOURS.length * WINDOWS.length;
    }

    @Override
    public String describe(int configuration) {
        return "k=" + NEIGHBOURS[configuration / WINDOWS.length] + ", w=" + SenseArm.windowLabel(configuration % WINDOWS.length);
    }

    @Override
    public double[] thresholds() {
        return TENTHS;
    }

    @Override
    public void fit(List<TrainingExample> training) {
        models.clear();
        for (var entry : LemmaTraining.group(training).entrySet()) {
            LemmaTraining lemma = entry.getValue();
            var vocabulary = new HashMap<String, Integer>();
            var sets = new int[WINDOWS.length][lemma.examples().size()][];
            for (int w = 0; w < WINDOWS.length; w++) {
                for (int i = 0; i < lemma.examples().size(); i++) {
                    Window window = windows.window(lemma.examples().get(i).input(), WINDOWS[w]);
                    sets[w][i] = Arrays.stream(window.tokens())
                            .mapToInt(token -> vocabulary.computeIfAbsent(token, _ -> vocabulary.size()))
                            .distinct().sorted().toArray();
                }
            }
            models.put(entry.getKey(), new LemmaModel(lemma, vocabulary, sets));
        }
    }

    @Override
    public Prediction predict(SenseItem item, int configuration) {
        if (!item.hasInventory()) {
            return Prediction.NO_INVENTORY;
        }
        LemmaModel model = models.get(item.lemmaKey());
        if (model == null) {
            return new Prediction(0, 0.0);
        }
        int k = NEIGHBOURS[configuration / WINDOWS.length];
        int w = configuration % WINDOWS.length;
        Window window = windows.window(item, WINDOWS[w]);
        var unknown = new HashSet<String>();
        int[] query = Arrays.stream(window.tokens()).mapToInt(token -> {
            Integer id = model.vocabulary().get(token);
            if (id == null) {
                unknown.add(token);
                return -1;
            }
            return id;
        }).filter(id -> id >= 0).distinct().sorted().toArray();

        int[][] sets = model.sets()[w];
        var similarity = new double[sets.length];
        for (int i = 0; i < sets.length; i++) {
            similarity[i] = jaccard(query, unknown.size(), sets[i]);
        }
        int[] nearest = nearest(similarity, k);
        LemmaTraining training = model.training();
        var votes = new double[training.inventory().size()];
        double highest = 0.0;
        double total = 0.0;
        for (int i : nearest) {
            highest = Math.max(highest, similarity[i]);
            for (int c = 0; c < votes.length; c++) {
                votes[c] += training.weights()[i][c];
                total += training.weights()[i][c];
            }
        }
        if (highest == 0.0) {
            return new Prediction(training.lowestSupported(), 0.0);
        }
        int top = 0;
        for (int c = 1; c < votes.length; c++) {
            if (votes[c] > votes[top]) {
                top = c;
            }
        }
        return new Prediction(top, votes[top] / total * highest);
    }

    /** Returns the indices of the {@code k} most similar items, ties by the lower index (training-file order). */
    private int[] nearest(double[] similarity, int k) {
        int n = Math.min(k, similarity.length);
        var chosen = new int[n];
        var taken = new boolean[similarity.length];
        for (int r = 0; r < n; r++) {
            int best = -1;
            for (int i = 0; i < similarity.length; i++) {
                if (!taken[i] && (best < 0 || similarity[i] > similarity[best])) {
                    best = i;
                }
            }
            taken[best] = true;
            chosen[r] = best;
        }
        return chosen;
    }

    /**
     * Jaccard similarity of a query set (known ids plus {@code unknown} distinct tokens absent from training) and a
     * training set; 0 when both are empty.
     */
    private double jaccard(int[] known, int unknown, int[] training) {
        int i = 0;
        int j = 0;
        int common = 0;
        while (i < known.length && j < training.length) {
            if (known[i] == training[j]) {
                common++;
                i++;
                j++;
            } else if (known[i] < training[j]) {
                i++;
            } else {
                j++;
            }
        }
        int union = known.length + unknown + training.length - common;
        return union == 0 ? 0.0 : (double) common / union;
    }

    @Override
    public long retainedBytes() {
        long bytes = 0;
        for (LemmaModel model : models.values()) {
            bytes += model.vocabulary().size() * 64L;
            for (int[][] perWindow : model.sets()) {
                for (int[] set : perWindow) {
                    bytes += 16L + 4L * set.length;
                }
            }
            bytes += 8L * model.training().weights().length * model.training().inventory().size();
        }
        return bytes;
    }
}
