package monada.neuron.evaluation.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One curated query of the judged query set with its gold labels (protocol section "Query set and gold labels").
 *
 * <p>Gold labels are read only by the scorer. A recall path receives {@link #text()} and nothing else.
 *
 * @param queryId {@code q:<category>-<nn>}
 * @param category the query category, which fixes {@link #answerability()}
 * @param text the query text exactly as sent to every system under test
 * @param required chunks that together contain the answer, or the refutation of a false premise
 * @param supporting relevant chunks that are not required
 * @param evidenceSpans expected supporting passages, sorted by {@code (chunkId, start, end)}
 * @param keyClaims pre-enumerated factual claims of a correct answer, in curated order (scored at L4 only)
 * @param avoidedTerms {@code paraphrase} only: the distinctive terms the text deliberately avoids
 */
public record JudgedQuery(
        String queryId,
        Category category,
        String text,
        Set<String> required,
        Set<String> supporting,
        List<EvidenceSpan> evidenceSpans,
        List<String> keyClaims,
        Set<String> avoidedTerms) {

    /** Answerability of a query; fixed by its category. */
    public enum Answerability {
        ANSWERABLE,
        NO_ANSWER_IN_CORPUS,
        OUT_OF_DOMAIN,
        FALSE_PREMISE,
        CONFLICTING,
        BLANK
    }

    /** Query categories, each reported on its own, with the answerability the protocol fixes for it. */
    public enum Category {
        EXACT_TERM("exact-term", Answerability.ANSWERABLE),
        PARAPHRASE("paraphrase", Answerability.ANSWERABLE),
        MULTI_EVIDENCE("multi-evidence", Answerability.ANSWERABLE),
        CODE_EXAMPLE("code-example", Answerability.ANSWERABLE),
        DISTRACTOR("distractor", Answerability.ANSWERABLE),
        NO_ANSWER("no-answer", Answerability.NO_ANSWER_IN_CORPUS),
        OUT_OF_DOMAIN("out-of-domain", Answerability.OUT_OF_DOMAIN),
        FALSE_PREMISE("false-premise", Answerability.FALSE_PREMISE),
        CONFLICTING_SOURCE("conflicting-source", Answerability.CONFLICTING),
        BLANK("blank", Answerability.BLANK);

        private final String label;
        private final Answerability answerability;

        Category(String label, Answerability answerability) {
            this.label = label;
            this.answerability = answerability;
        }

        /** Returns the label used in query ids, files and reports. */
        public String label() {
            return label;
        }

        /** Returns the answerability this category fixes. */
        public Answerability answerability() {
            return answerability;
        }
    }

    /**
     * A gold evidence passage: the half-open code point range {@code [start, end)} of the normalized chunk text and
     * the SHA-256 of that substring.
     */
    public record EvidenceSpan(String chunkId, int start, int end, String sha256) {

        public EvidenceSpan {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
        }
    }

    public JudgedQuery {
        Objects.requireNonNull(queryId, "queryId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(text, "text must not be null");
        required = Set.copyOf(required);
        supporting = Set.copyOf(supporting);
        evidenceSpans = List.copyOf(evidenceSpans);
        keyClaims = List.copyOf(keyClaims);
        avoidedTerms = Set.copyOf(avoidedTerms);
    }

    /** Returns the answerability fixed by the category. */
    public Answerability answerability() {
        return category.answerability();
    }

    /** Returns the relevant set {@code Rel(q) = required ∪ supporting}. */
    public Set<String> relevant() {
        var relevant = new LinkedHashSet<>(required);
        relevant.addAll(supporting);
        return Set.copyOf(relevant);
    }

    /** Returns whether the query has a non-empty relevant set (population {@code A}). */
    public boolean answerable() {
        return !required.isEmpty();
    }
}
