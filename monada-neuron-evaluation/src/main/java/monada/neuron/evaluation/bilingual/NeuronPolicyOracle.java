package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.EvaluatedHypothesis;
import monada.neuron.evaluation.HypothesisEvaluation;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.evaluation.bilingual.NeuronSenseArm.Scored;
import monada.neuron.evaluation.bilingual.NeuronSenseArm.SenseCue;
import monada.neuron.reasoning.Evidence;
import monada.neuron.reasoning.Hypothesis;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.SignalEvidence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Check {@code n.policy-equivalence}: passes Arm N's cue weights, outside any cognitive cycle, to the core
 * {@link ReferenceHypothesisEvaluationPolicy} (evidence only) as a {@link HypothesisSet} built in cluster-ordinal order,
 * so the {@link Hypothesis} sequence equals the ordinal, whose {@link SignalEvidence} entries carry the opaque indices
 * {@code 0..m-1}, {@code m} the cue count of that hypothesis. This is the only place {@code SignalEvidence} appears, and it requires bit-identical scores and
 * ranking.
 */
final class NeuronPolicyOracle {

    /** Proposition namespace of the oracle's hypotheses; opaque to the policy. */
    private static final int DOMAIN = 0;

    private final ReferenceHypothesisEvaluationPolicy policy = new ReferenceHypothesisEvaluationPolicy();

    /** Returns {@code null} when N's scores and ranking equal the policy's bit for bit, else the first difference. */
    String mismatch(Scored scored) {
        int k = scored.scores().length;
        var hypotheses = new ArrayList<Hypothesis>(k);
        for (int c = 0; c < k; c++) {
            long index = 0;
            var evidence = new ArrayList<Evidence>();
            for (SenseCue cue : scored.cues().get(c)) {
                evidence.add(new SignalEvidence(index++, cue.relation(), cue.weight()));
            }
            hypotheses.add(new Hypothesis(c, new Proposition(DOMAIN, c), evidence));
        }
        HypothesisEvaluation evaluation = policy.evaluate(new HypothesisSet(hypotheses, HypothesisLimits.DEFAULT), k);
        List<EvaluatedHypothesis> ranked = evaluation.selected();
        if (ranked.size() != k) {
            return "the policy ranked " + ranked.size() + " of " + k + " candidates";
        }
        for (EvaluatedHypothesis candidate : ranked) {
            double expected = scored.scores()[candidate.sequence()];
            if (Double.doubleToRawLongBits(candidate.breakdown().score()) != Double.doubleToRawLongBits(expected)) {
                return "cluster " + candidate.sequence() + ": policy " + candidate.breakdown().score() + ", N "
                        + expected;
            }
        }
        if (ranked.getFirst().sequence() != scored.choice()) {
            return "policy top " + ranked.getFirst().sequence() + ", N top " + scored.choice();
        }
        int[] order = rankOf(scored.scores());
        for (int r = 0; r < k; r++) {
            if (ranked.get(r).sequence() != order[r]) {
                return "rank " + r + ": policy " + ranked.get(r).sequence() + ", N " + order[r];
            }
        }
        return null;
    }

    /** N's full ranking: score descending, ties to the lower ordinal. */
    private int[] rankOf(double[] scores) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int byScore = Double.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });
        return Arrays.stream(order).mapToInt(Integer::intValue).toArray();
    }
}
