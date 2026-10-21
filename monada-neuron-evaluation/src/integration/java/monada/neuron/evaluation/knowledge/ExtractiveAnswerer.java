package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.GroundedAnswer.Abstained;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Claim;

import java.util.List;
import java.util.Objects;

/**
 * The deterministic extractive answer baselines of Issue #109. Both take the first chunk of the evidence set, split its
 * Store-returned text with {@link EvidenceSegmenter}, and score each segment by the number of distinct query tokens
 * ({@link HtmlTokens}) it contains; the highest score wins, ties going to the earliest segment. The winning segment is
 * the single claim, citing its exact span, so a claim is always a verbatim quote of retrieved evidence and never a
 * fabricated tag, attribute or example.
 *
 * <ul>
 *   <li>{@link Rule#B3}: the protocol baseline. Abstains only when the evidence set is empty or the best score is
 *       {@code 0}.</li>
 *   <li>{@link Rule#B3C}: B3 with a coverage rule. It also abstains unless the winning segment contains at least half of
 *       the query's distinct tokens. The rule was chosen after seeing B3 answer almost every query of
 *       {@code html-kq-v1}, so every B3c result on that set is {@code in-sample (tuned)}.</li>
 * </ul>
 *
 * <p>Cost: one segmentation and one token pass over the first chunk, {@code O(chunkLength)}.
 */
final class ExtractiveAnswerer implements AnswerComponent {

    /** The two rules. */
    enum Rule {
        B3("B3", "none"),
        B3C("B3c", "in-sample (tuned): the 1/2 coverage rule was chosen after inspecting B3 on html-kq-v1");

        private final String label;
        private final String tuning;

        Rule(String label, String tuning) {
            this.label = label;
            this.tuning = tuning;
        }
    }

    private final Rule rule;
    private final EvidenceSegmenter segmenter = new EvidenceSegmenter();

    ExtractiveAnswerer(Rule rule) {
        this.rule = Objects.requireNonNull(rule, "rule must not be null");
    }

    @Override
    public String name() {
        return rule.label;
    }

    @Override
    public String description() {
        var base = "html-knowledge-protocol-v1 B3: best segment of the first evidence chunk by distinct query tokens,"
                + " earliest on ties; abstains on empty evidence or a best score of 0";
        return rule == Rule.B3 ? base
                : base + "; B3c also abstains unless the segment holds at least half of the distinct query tokens";
    }

    @Override
    public Kind kind() {
        return Kind.EXTRACTIVE_BASELINE;
    }

    @Override
    public String tuning() {
        return rule.tuning;
    }

    @Override
    public GroundedAnswer answer(String question, List<EvidenceChunk> evidence) {
        Objects.requireNonNull(question, "question must not be null");
        if (evidence.isEmpty()) {
            return Abstained.insufficient("the evidence set is empty");
        }
        var queryTokens = HtmlTokens.distinct(question);
        var first = evidence.getFirst();
        EvidenceSegmenter.Segment best = null;
        var bestScore = 0;
        for (var segment : segmenter.segments(first.text())) {
            var tokens = HtmlTokens.distinct(segment.text());
            var score = (int) queryTokens.stream().filter(tokens::contains).count();
            if (score > bestScore) {
                best = segment;
                bestScore = score;
            }
        }
        if (best == null) {
            return Abstained.insufficient("no segment of " + first.chunkId() + " contains a query token");
        }
        if (rule == Rule.B3C && 2 * bestScore < queryTokens.size()) {
            return Abstained.insufficient("the best segment of " + first.chunkId() + " holds " + bestScore + " of "
                    + queryTokens.size() + " distinct query tokens, under half");
        }
        return new Answered(List.of(new Claim(best.text(),
                List.of(new Citation(first.chunkId(), best.start(), best.end())))));
    }
}
