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
        var common = new int[sets.length];
        var union = new int[sets.length];
        var similarity = new double[sets.length];
        for (int i = 0; i < sets.length; i++) {
            common[i] = common(query, sets[i]);
            union[i] = query.length + unknown.size() + sets[i].length - common[i];
            similarity[i] = union[i] == 0 ? 0.0 : (double) common[i] / union[i];
        }
        int[] nearest = nearest(similarity, k);
        LemmaTraining training = model.training();
        var votes = new long[training.inventory().size()];
        long total = 0;
        int highest = nearest[0];
        for (int i : nearest) {
            for (int c = 0; c < votes.length; c++) {
                votes[c] += training.units()[i][c];
                total += training.units()[i][c];
            }
        }
        if (similarity[highest] == 0.0) {
            return new Prediction(training.lowestSupported(), 0.0);
        }
        int top = 0;
        for (int c = 1; c < votes.length; c++) {
            if (votes[c] > votes[top]) {
                top = c;
            }
        }
        // One division of exact integers, so s equals a registered threshold exactly when the rational does.
        return new Prediction(top, (double) (votes[top] * common[highest]) / (total * union[highest]));
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

    /** Returns the number of ids shared by two sorted, duplicate-free arrays. */
    private int common(int[] known, int[] training) {
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
        return common;
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
            bytes += 8L * model.training().units().length * model.training().inventory().size();
        }
        return bytes;
    }
}
