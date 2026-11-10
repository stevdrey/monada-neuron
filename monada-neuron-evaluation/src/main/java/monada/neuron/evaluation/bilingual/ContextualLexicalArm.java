package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseWindows.Window;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arm C, contextual lexical: multinomial naive Bayes per lemma over the window tokens (each window token occurrence
 * counts once). Token likelihoods use Laplace {@code alpha} over the lemma's training vocabulary of that window; the
 * class prior is the unsmoothed training-weight share, so a cluster with zero training weight has posterior 0; tokens
 * absent from training are ignored.
 *
 * <p>{@code k*} is the argmax posterior (ties to the lower ordinal) and {@code s} its posterior. Counts and
 * {@code alpha} are rational, so the posterior is computed exactly with integers: a sum of floating-point logarithms
 * depends on the order of its terms and would break exact ties and exact thresholds by rounding. Grid:
 * {@code alpha in {0.1, 0.5, 1}} x {@code w in {3, 5, S}} x {@code tau in {0, 0.55, 0.6, ..., 0.95}} (90 points).
 */
final class ContextualLexicalArm implements SenseArm {

    /** The registered {@code alpha} values, in tenths. */
    private static final int[] ALPHA_TENTHS = {1, 5, 10};
    private static final MathContext PRECISION = new MathContext(40);
    private static final double[] THRESHOLDS = {0.0, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95};

    /** Per window: the vocabulary and, in gold units, the token counts per cluster and the per-cluster totals. */
    private record WindowModel(Map<String, Integer> vocabulary, long[][] counts, long[] totals) {
    }

    private record LemmaModel(long[] priorUnits, long unitsPerItem, WindowModel[] windows) {
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
        return ALPHA_TENTHS.length * WINDOWS.length;
    }

    @Override
    public String describe(int configuration) {
        return "alpha=" + ALPHA_TENTHS[configuration / WINDOWS.length] / 10.0 + ", w="
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
                var unitCounts = new long[k][vocabulary.size()];
                var unitTotals = new long[k];
                for (int i = 0; i < windowsOfItems.length; i++) {
                    for (String token : windowsOfItems[i].tokens()) {
                        int id = vocabulary.get(token);
                        for (int c = 0; c < k; c++) {
                            unitCounts[c][id] += lemma.units()[i][c];
                            unitTotals[c] += lemma.units()[i][c];
                        }
                    }
                }
                perWindow[w] = new WindowModel(vocabulary, unitCounts, unitTotals);
            }
            models.put(entry.getKey(), new LemmaModel(lemma.clusterUnits(), lemma.unitsPerItem(), perWindow));
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
        long alphaTenths = ALPHA_TENTHS[configuration / WINDOWS.length];
        int w = configuration % WINDOWS.length;
        WindowModel windowModel = model.windows()[w];
        int k = model.priorUnits().length;
        long perItem = model.unitsPerItem();
        long vocabularySize = windowModel.vocabulary().size();
        // Each likelihood is (count + alpha) / (total + alpha V); multiplied through by 10 x perItem it is integral.
        var numerators = new BigInteger[k];
        var denominators = new BigInteger[k];
        for (int c = 0; c < k; c++) {
            numerators[c] = BigInteger.valueOf(model.priorUnits()[c]);
            denominators[c] = BigInteger.ONE;
        }
        for (String token : windows.window(item, WINDOWS[w]).tokens()) {
            Integer id = windowModel.vocabulary().get(token);
            if (id == null) {
                continue;
            }
            for (int c = 0; c < k; c++) {
                numerators[c] = numerators[c].multiply(BigInteger.valueOf(
                        10 * windowModel.counts()[c][id] + alphaTenths * perItem));
                denominators[c] = denominators[c].multiply(BigInteger.valueOf(
                        10 * windowModel.totals()[c] + alphaTenths * vocabularySize * perItem));
            }
        }
        // Over a common denominator the posterior of c is proportional to its numerator times the other denominators.
        var joint = new BigInteger[k];
        BigInteger sum = BigInteger.ZERO;
        int top = 0;
        for (int c = 0; c < k; c++) {
            joint[c] = numerators[c];
            for (int other = 0; other < k; other++) {
                if (other != c) {
                    joint[c] = joint[c].multiply(denominators[other]);
                }
            }
            sum = sum.add(joint[c]);
            if (joint[c].compareTo(joint[top]) > 0) {
                top = c;
            }
        }
        if (sum.signum() == 0) {
            return new Prediction(0, 0.0);
        }
        return new Prediction(top, new BigDecimal(joint[top]).divide(new BigDecimal(sum), PRECISION).doubleValue());
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
