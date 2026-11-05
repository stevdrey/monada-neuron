package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseWindows.Window;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arm C, contextual lexical: multinomial naive Bayes per lemma over the window tokens (each window token occurrence
 * counts once). Token likelihoods use Laplace {@code alpha} over the lemma's training vocabulary of that window; the
 * class prior is the unsmoothed training-weight share, so a cluster with zero training weight has posterior 0; tokens
 * absent from training are ignored.
 *
 * <p>{@code k*} is the argmax posterior (ties to the lower ordinal) and {@code s} its posterior. Grid:
 * {@code alpha in {0.1, 0.5, 1}} x {@code w in {3, 5, S}} x {@code tau in {0, 0.55, 0.6, ..., 0.95}} (90 points).
 */
final class ContextualLexicalArm implements SenseArm {

    private static final double[] ALPHAS = {0.1, 0.5, 1.0};
    private static final double[] THRESHOLDS = {0.0, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95};

    /** Per window: the vocabulary, the weighted token counts per cluster and the per-cluster totals. */
    private record WindowModel(Map<String, Integer> vocabulary, double[][] counts, double[] totals) {
    }

    private record LemmaModel(double[] prior, WindowModel[] windows) {
    }

    private final SenseWindows windows;
    private final Map<String, LemmaModel> models = new HashMap<>();

    ContextualLexicalArm(SenseWindows windows) {
        this.windows = windows;
    }

    @Override
    public String name() {
        return "C";
    }

    @Override
    public int configurations() {
        return ALPHAS.length * WINDOWS.length;
    }

    @Override
    public String describe(int configuration) {
        return "alpha=" + ALPHAS[configuration / WINDOWS.length] + ", w="
                + SenseArm.windowLabel(configuration % WINDOWS.length);
    }

    @Override
    public double[] thresholds() {
        return THRESHOLDS;
    }

    @Override
    public void fit(List<TrainingExample> training) {
        models.clear();
        for (var entry : LemmaTraining.group(training).entrySet()) {
            LemmaTraining lemma = entry.getValue();
            int k = lemma.inventory().size();
            double total = 0.0;
            for (double weight : lemma.clusterWeights()) {
                total += weight;
            }
            var prior = new double[k];
            for (int c = 0; c < k; c++) {
                prior[c] = lemma.clusterWeights()[c] / total;
            }
            var perWindow = new WindowModel[WINDOWS.length];
            for (int w = 0; w < WINDOWS.length; w++) {
                var vocabulary = new HashMap<String, Integer>();
                var windowsOfItems = new Window[lemma.examples().size()];
                for (int i = 0; i < windowsOfItems.length; i++) {
                    windowsOfItems[i] = windows.window(lemma.examples().get(i).input(), WINDOWS[w]);
                    for (String token : windowsOfItems[i].tokens()) {
                        vocabulary.computeIfAbsent(token, _ -> vocabulary.size());
                    }
                }
                var counts = new double[k][vocabulary.size()];
                var totals = new double[k];
                for (int i = 0; i < windowsOfItems.length; i++) {
                    for (String token : windowsOfItems[i].tokens()) {
                        int id = vocabulary.get(token);
                        for (int c = 0; c < k; c++) {
                            double weight = lemma.weights()[i][c];
                            counts[c][id] += weight;
                            totals[c] += weight;
                        }
                    }
                }
                perWindow[w] = new WindowModel(vocabulary, counts, totals);
            }
            models.put(entry.getKey(), new LemmaModel(prior, perWindow));
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
        double alpha = ALPHAS[configuration / WINDOWS.length];
        int w = configuration % WINDOWS.length;
        WindowModel windowModel = model.windows()[w];
        int k = model.prior().length;
        int vocabularySize = windowModel.vocabulary().size();
        var log = new double[k];
        for (int c = 0; c < k; c++) {
            log[c] = model.prior()[c] > 0.0 ? Math.log(model.prior()[c]) : Double.NEGATIVE_INFINITY;
        }
        for (String token : windows.window(item, WINDOWS[w]).tokens()) {
            Integer id = windowModel.vocabulary().get(token);
            if (id == null) {
                continue;
            }
            for (int c = 0; c < k; c++) {
                if (log[c] != Double.NEGATIVE_INFINITY) {
                    log[c] += Math.log((windowModel.counts()[c][id] + alpha)
                            / (windowModel.totals()[c] + alpha * vocabularySize));
                }
            }
        }
        int top = 0;
        for (int c = 1; c < k; c++) {
            if (log[c] > log[top]) {
                top = c;
            }
        }
        if (log[top] == Double.NEGATIVE_INFINITY) {
            return new Prediction(0, 0.0);
        }
        double sum = 0.0;
        for (int c = 0; c < k; c++) {
            sum += Math.exp(log[c] - log[top]);
        }
        return new Prediction(top, 1.0 / sum);
    }

    @Override
    public long retainedBytes() {
        long bytes = 0;
        for (LemmaModel model : models.values()) {
            for (WindowModel window : model.windows()) {
                bytes += window.vocabulary().size() * 64L;
                bytes += 8L * window.counts().length * window.vocabulary().size() + 8L * window.totals().length;
            }
        }
        return bytes;
    }
}
