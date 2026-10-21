package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.GroundedAnswer.Abstained;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Claim;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 *   <li>{@link Rule#B3S}: a conservative, evaluation-only demonstration of safe abstention. It answers only with the
 *       first segment, scanning every chunk of {@code E(q)} in rank order, that contains <em>every</em> content token of
 *       the question: its distinct tokens minus a fixed list of generic English function words
 *       ({@link #FUNCTION_WORDS}), which names no HTML term. Otherwise it returns {@code INSUFFICIENT_EVIDENCE}. It was
 *       designed after review of the B3 results on {@code html-kq-v1}, so its results there are in-sample too.</li>
 * </ul>
 *
 * <p>Cost: one segmentation and one token pass over the first chunk, {@code O(chunkLength)}; B3s scans every chunk of
 * {@code E(q)}, {@code O(K * chunkLength)}.
 */
final class ExtractiveAnswerer implements AnswerComponent {

    /** The two rules. */
    enum Rule {
        B3("B3", "none"),
        B3C("B3c", "in-sample (tuned): the 1/2 coverage rule was chosen after inspecting B3 on html-kq-v1"),
        B3S("B3s", "in-sample (designed after review of B3 on html-kq-v1): full coverage of the question's content"
                + " tokens with a fixed generic function-word list; no threshold, no gold label");

        private final String label;
        private final String tuning;

        Rule(String label, String tuning) {
            this.label = label;
            this.tuning = tuning;
        }
    }

    /**
     * Generic English function words that B3s does not require a quoted segment to contain: articles, auxiliaries,
     * pronouns, prepositions, conjunctions and interrogatives. Fixed, and naming no HTML term.
     */
    static final Set<String> FUNCTION_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "been", "do",
            "does", "did", "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "for", "of", "to",
            "in", "on", "at", "by", "with", "from", "into", "about", "and", "or", "not", "can", "could", "should",
            "would", "will", "shall", "may", "might", "must", "i", "me", "my", "you", "your", "it", "its", "this", "that",
            "these", "those", "there", "their", "they", "them", "we", "our", "us");

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
        return switch (rule) {
            case B3 -> base;
            case B3C -> base + "; B3c also abstains unless the segment holds at least half of the distinct query tokens";
            case B3S -> "B3s: the first segment, over every chunk of E(q) in rank order, that holds every distinct query"
                    + " token outside a fixed generic function-word list; otherwise INSUFFICIENT_EVIDENCE";
        };
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
        if (rule == Rule.B3S) {
            return conservative(question, evidence);
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

    /** B3s: the first segment in rank and source order that holds every content token of the question. */
    private GroundedAnswer conservative(String question, List<EvidenceChunk> evidence) {
        var content = new LinkedHashSet<>(HtmlTokens.distinct(question));
        content.removeAll(FUNCTION_WORDS);
        if (content.isEmpty()) {
            return Abstained.insufficient("the question has no content token outside the function-word list");
        }
        for (var chunk : evidence) {
            for (var segment : segmenter.segments(chunk.text())) {
                if (HtmlTokens.distinct(segment.text()).containsAll(content)) {
                    return new Answered(List.of(new Claim(segment.text(),
                            List.of(new Citation(chunk.chunkId(), segment.start(), segment.end())))));
                }
            }
        }
        return Abstained.insufficient("no segment of the " + evidence.size() + " retrieved chunks holds every content"
                + " token " + content);
    }
}
