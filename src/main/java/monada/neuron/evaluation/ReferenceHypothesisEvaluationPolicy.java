package monada.neuron.evaluation;

import monada.neuron.reasoning.Hypothesis;
import monada.neuron.reasoning.HypothesisSet;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic, conservative reference policy and semantic oracle for hypothesis evaluation.
 *
 * <p>With {@code S} the sum of supporting evidence weights, {@code C} the sum of contradicting
 * weights, and {@code R = resonanceWeight * resonance(h)} (zero by default):
 *
 * <pre>{@code
 * score = (S + R) / (S + R + C + 1)          score in [0, 1)
 * }</pre>
 *
 * <p>The constant 1 acts as one unit of ignorance: a candidate with no evidence and no resonance
 * contribution scores 0, thin evidence scores low, and contradiction always lowers the score.
 * Neutral evidence is counted and reported but does not change the score. Weights are summed in evidence-list order, so results are
 * bit-identical for identical inputs. Ties rank the lower {@code Hypothesis.sequence()} first.
 *
 * <p>Limitations: evidence is treated as independent, additive mass; the formula is a research
 * baseline rather than a calibrated probability, and it ignores evidence provenance and recency.
 * The policy is stateless between calls and never mutates Nodes, adaptation state, or its input.
 */
public final class ReferenceHypothesisEvaluationPolicy implements HypothesisEvaluationPolicy {

    private final HypothesisScoringConfig config;
    private final HypothesisSelector selector;

    /** Creates an evidence-only policy that selects with a bounded heap. */
    public ReferenceHypothesisEvaluationPolicy() {
        this(HypothesisScoringConfig.NONE, new BoundedHeapSelector());
    }

    /** Creates a policy with explicit scoring configuration and selection strategy. */
    public ReferenceHypothesisEvaluationPolicy(HypothesisScoringConfig config, HypothesisSelector selector) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    @Override
    public HypothesisEvaluation evaluate(HypothesisSet hypotheses, int maxSelected) {
        Objects.requireNonNull(hypotheses, "hypotheses must not be null");
        if (maxSelected < 0) {
            throw new IllegalArgumentException("maxSelected must not be negative, got: " + maxSelected);
        }
        var count = hypotheses.size();
        var scores = new double[count];
        var resonance = config.usesResonance() ? new double[count] : null;
        var accumulator = new EvidenceAccumulator();
        for (var i = 0; i < count; i++) {
            var hypothesis = hypotheses.get(i);
            var contribution = resonance == null ? 0.0 : resonanceContribution(hypothesis);
            if (resonance != null) {
                resonance[i] = contribution;
            }
            accumulator.accumulate(hypothesis);
            scores[i] = accumulator.score(contribution);
        }
        var ranked = selector.select(scores, maxSelected);
        var selected = new EvaluatedHypothesis[ranked.length];
        for (var rank = 0; rank < ranked.length; rank++) {
            var sequence = ranked[rank];
            var contribution = resonance == null ? 0.0 : resonance[sequence];
            accumulator.accumulate(hypotheses.get(sequence));
            selected[rank] = new EvaluatedHypothesis(sequence, accumulator.breakdown(contribution));
        }
        return new HypothesisEvaluation(List.of(selected), count, maxSelected);
    }

    private double resonanceContribution(Hypothesis hypothesis) {
        var value = config.resonance().resonance(hypothesis);
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                    "resonance must be finite and in [0, 1], got: " + value
                            + " for hypothesis " + hypothesis.sequence());
        }
        return config.resonanceWeight() * value;
    }

    /**
     * Per-call, reusable evidence accumulator: the one place where evidence is summed and the score
     * formula lives. One instance scores every candidate (no per-candidate allocation) and then
     * builds the breakdowns of the selected ones, so ranking and explanation cannot diverge.
     */
    private static final class EvidenceAccumulator {

        private double support;
        private double contradiction;
        private double neutral;
        private int supportCount;
        private int contradictionCount;
        private int neutralCount;

        /** Sums the candidate's evidence in list order, replacing any previous candidate. */
        void accumulate(Hypothesis hypothesis) {
            support = 0.0;
            contradiction = 0.0;
            neutral = 0.0;
            supportCount = 0;
            contradictionCount = 0;
            neutralCount = 0;
            var evidence = hypothesis.evidence();
            for (var i = 0; i < evidence.size(); i++) {
                var item = evidence.get(i);
                switch (item.relation()) {
                    case SUPPORTS -> {
                        support += item.weight();
                        supportCount++;
                    }
                    case CONTRADICTS -> {
                        contradiction += item.weight();
                        contradictionCount++;
                    }
                    case NEUTRAL -> {
                        neutral += item.weight();
                        neutralCount++;
                    }
                }
            }
        }

        /** Returns {@code (S + R) / (S + R + C + 1)} for the accumulated candidate. */
        double score(double resonanceContribution) {
            var supportWithResonance = support + resonanceContribution;
            return supportWithResonance / (supportWithResonance + contradiction + 1.0);
        }

        HypothesisScoreBreakdown breakdown(double resonanceContribution) {
            return new HypothesisScoreBreakdown(
                    support,
                    contradiction,
                    neutral,
                    supportCount,
                    contradictionCount,
                    neutralCount,
                    resonanceContribution,
                    score(resonanceContribution));
        }
    }
}
