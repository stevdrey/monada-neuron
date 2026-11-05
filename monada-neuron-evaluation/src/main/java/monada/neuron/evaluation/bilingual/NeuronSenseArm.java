package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseWindows.Window;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Arm N, the Neuron hypothesis/evidence candidate: one candidate per inventory cluster, scored with the evidence formula
 * of {@code ReferenceHypothesisEvaluationPolicy} under {@code HypothesisScoringConfig.NONE},
 * {@code score(k) = S_k / (S_k + C_k + 1)}.
 *
 * <p>Evidence comes from the window tokens. For lemma {@code l} with {@code K} clusters and a training-vocabulary token
 * {@code c}, {@code n(c)} is the training weight of items whose window contains {@code c} and {@code n_k(c)} the part of
 * it with cluster {@code k}. Tokens with {@code n(c) < 2} give no evidence. Otherwise, with
 * {@code r_k(c) = (n_k(c) + 1) / (n(c) + K)}, {@code d = (K / (K - 1)) (r_k(c) - 1/K)},
 * {@code weight = min(1, lambda |d| log2(1 + n(c)))} and the relation is {@code SUPPORTS} for {@code d > 0},
 * {@code CONTRADICTS} for {@code d < 0} and none for {@code d = 0} ({@code d} is computed as
 * {@code (K (n_k + 1) - (n + K)) / ((K - 1)(n + K))}, so its sign is exact when the weights are).
 *
 * <p>Each window token position yields at most one cue per cluster. Per cluster the 16 largest weights are kept (the
 * bound of {@code HypothesisLimits.DEFAULT}), ties to the lower position, and summed in position order. Score ties rank
 * the lower ordinal first, as the policy does. The arm lives in the evaluation module only, follows ADR 0050's
 * {@code FeatureConceptScorer} pattern and adds no type to core; check {@code n.policy-equivalence} ties it to the core
 * policy. Grid: {@code lambda in {0.5, 1, 2}} x {@code w in {3, 5, S}} x {@code tau in {0, 0.1, ..., 0.9}} (90 points).
 */
final class NeuronSenseArm implements SenseArm {

    private static final double[] LAMBDAS = {0.5, 1.0, 2.0};
    private static final int MAX_CUES = HypothesisLimits.DEFAULT.maxEvidencePerCandidate();
    private static final double LOG_2 = Math.log(2.0);

    /**
     * One evaluation-side cue; no core type carries text or token positions.
     *
     * @param cluster cluster ordinal
     * @param position sentence token index of the window token
     * @param relation {@code SUPPORTS} or {@code CONTRADICTS}
     * @param weight weight in {@code (0, 1]}
     */
    record SenseCue(int cluster, int position, EvidenceRelation relation, double weight) {
    }

    /**
     * The scored candidates of one item.
     *
     * @param scores score of each cluster ordinal
     * @param cues the kept cues of each cluster, in position order (the summation order)
     * @param choice {@code k*}
     */
    record Scored(double[] scores, List<List<SenseCue>> cues, int choice) {
    }

    /** Per window: per vocabulary token, {@code n(c)} and {@code n_k(c)}. */
    private record WindowModel(Map<String, double[]> clusterCounts, Map<String, Double> totals) {
    }

    private record LemmaModel(int clusters, WindowModel[] windows) {
    }

    private final SenseWindows windows;
    private final Map<String, LemmaModel> models = new HashMap<>();

    NeuronSenseArm(SenseWindows windows) {
        this.windows = windows;
    }

    @Override
    public String name() {
        return "N";
    }

    @Override
    public int configurations() {
        return LAMBDAS.length * WINDOWS.length;
    }

    @Override
    public String describe(int configuration) {
        return "lambda=" + LAMBDAS[configuration / WINDOWS.length] + ", w="
                + SenseArm.windowLabel(configuration % WINDOWS.length);
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
            int k = lemma.inventory().size();
            var perWindow = new WindowModel[WINDOWS.length];
            for (int w = 0; w < WINDOWS.length; w++) {
                var clusterCounts = new HashMap<String, double[]>();
                var totals = new HashMap<String, Double>();
                for (int i = 0; i < lemma.examples().size(); i++) {
                    Window window = windows.window(lemma.examples().get(i).input(), WINDOWS[w]);
                    var distinct = new HashSet<String>();
                    for (String token : window.tokens()) {
                        if (distinct.add(token)) {
                            double[] counts = clusterCounts.computeIfAbsent(token, _ -> new double[k]);
                            double itemWeight = 0.0;
                            for (int c = 0; c < k; c++) {
                                counts[c] += lemma.weights()[i][c];
                                itemWeight += lemma.weights()[i][c];
                            }
                            totals.merge(token, itemWeight, Double::sum);
                        }
                    }
                }
                perWindow[w] = new WindowModel(clusterCounts, totals);
            }
            models.put(entry.getKey(), new LemmaModel(k, perWindow));
        }
    }

    @Override
    public Prediction predict(SenseItem item, int configuration) {
        if (!item.hasInventory()) {
            return Prediction.NO_INVENTORY;
        }
        Scored scored = score(item, configuration);
        return new Prediction(scored.choice(), scored.scores()[scored.choice()]);
    }

    /** Scores every cluster of an item under a configuration, with the cues that produced each score. */
    Scored score(SenseItem item, int configuration) {
        int w = configuration % WINDOWS.length;
        return score(item, configuration, windows.window(item, WINDOWS[w]));
    }

    /** Scores an item with every window token masked (check {@code nc.context-mask}). */
    Scored scoreMasked(SenseItem item, int configuration) {
        return score(item, configuration, Window.EMPTY);
    }

    private Scored score(SenseItem item, int configuration, Window window) {
        int k = item.inventory().size();
        LemmaModel model = models.get(item.lemmaKey());
        double lambda = LAMBDAS[configuration / WINDOWS.length];
        var candidates = new ArrayList<List<SenseCue>>(k);
        for (int c = 0; c < k; c++) {
            candidates.add(new ArrayList<>());
        }
        if (model != null) {
            WindowModel windowModel = model.windows()[configuration % WINDOWS.length];
            for (int p = 0; p < window.tokens().length; p++) {
                String token = window.tokens()[p];
                Double n = windowModel.totals().get(token);
                if (n == null || n < 2.0) {
                    continue;
                }
                double[] counts = windowModel.clusterCounts().get(token);
                double strength = lambda * Math.log(1.0 + n) / LOG_2;
                for (int c = 0; c < k; c++) {
                    double numerator = k * (counts[c] + 1.0) - (n + k);
                    if (numerator == 0.0) {
                        continue;
                    }
                    double d = numerator / ((k - 1.0) * (n + k));
                    double weight = Math.min(1.0, strength * Math.abs(d));
                    if (weight > 0.0) {
                        candidates.get(c).add(new SenseCue(c, window.positions()[p],
                                d > 0.0 ? EvidenceRelation.SUPPORTS : EvidenceRelation.CONTRADICTS, weight));
                    }
                }
            }
        }
        var scores = new double[k];
        var kept = new ArrayList<List<SenseCue>>(k);
        int choice = 0;
        for (int c = 0; c < k; c++) {
            List<SenseCue> cues = keep(candidates.get(c));
            double support = 0.0;
            double contradiction = 0.0;
            for (SenseCue cue : cues) {
                if (cue.relation() == EvidenceRelation.SUPPORTS) {
                    support += cue.weight();
                } else {
                    contradiction += cue.weight();
                }
            }
            scores[c] = support / (support + contradiction + 1.0);
            kept.add(cues);
            if (scores[c] > scores[choice]) {
                choice = c;
            }
        }
        return new Scored(scores, List.copyOf(kept), choice);
    }

    /** Keeps the {@link #MAX_CUES} largest weights (ties to the lower position), returned in position order. */
    private List<SenseCue> keep(List<SenseCue> cues) {
        if (cues.size() <= MAX_CUES) {
            return List.copyOf(cues);
        }
        var byWeight = new ArrayList<>(cues);
        byWeight.sort(Comparator.comparingDouble(SenseCue::weight).reversed()
                .thenComparingInt(SenseCue::position));
        var top = new ArrayList<>(byWeight.subList(0, MAX_CUES));
        top.sort(Comparator.comparingInt(SenseCue::position));
        return List.copyOf(top);
    }

    @Override
    public long retainedBytes() {
        long bytes = 0;
        for (LemmaModel model : models.values()) {
            for (WindowModel window : model.windows()) {
                bytes += window.totals().size() * (64L + 24L + 16L + 8L * model.clusters());
            }
        }
        return bytes;
    }
}
