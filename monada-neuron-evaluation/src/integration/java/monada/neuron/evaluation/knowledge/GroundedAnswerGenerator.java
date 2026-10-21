package monada.neuron.evaluation.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * The optional, evaluation-only generator seam of Issue #109: a provider-neutral port that takes a question and bounded
 * source excerpts and proposes a response with citations. No provider implementation ships with #109, and nothing here
 * opens a network connection; a real local or remote model is future opt-in work under the protocol's fixture, cost and
 * privacy rules.
 *
 * <p>An implementation declares its {@link AnswerComponent.Kind}. A {@link AnswerComponent.Kind#TEST_DOUBLE} is never
 * judged and never scored for answer quality: its rows report only the mechanical citation checks, so a canned answer
 * can never be read as evidence of model quality.
 */
interface GroundedAnswerGenerator {

    /** At most this many excerpts reach a generator: the largest {@code answerK}. */
    int MAX_EXCERPTS = HtmlKnowledgeRecall.MAX_LIMIT;

    /** At most this many code points of one chunk reach a generator; the excerpt is a prefix of the chunk text. */
    int MAX_EXCERPT_CODE_POINTS = 4096;

    /**
     * One bounded excerpt. Citations index the full chunk text, so a citation inside the excerpt prefix stays valid.
     *
     * @param chunkId the evidence chunk
     * @param text a prefix of at most {@link #MAX_EXCERPT_CODE_POINTS} code points of its Store-returned text
     */
    record Excerpt(String chunkId, String text) {

        public Excerpt {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(text, "text must not be null");
            if (text.codePointCount(0, text.length()) > MAX_EXCERPT_CODE_POINTS) {
                throw new IllegalArgumentException("excerpt of " + chunkId + " exceeds " + MAX_EXCERPT_CODE_POINTS
                        + " code points");
            }
        }

        /** The excerpt of one evidence chunk: its first {@link #MAX_EXCERPT_CODE_POINTS} code points. */
        static Excerpt of(AnswerComponent.EvidenceChunk chunk) {
            var text = chunk.text();
            var length = text.codePointCount(0, text.length());
            var end = length <= MAX_EXCERPT_CODE_POINTS ? text.length()
                    : text.offsetByCodePoints(0, MAX_EXCERPT_CODE_POINTS);
            return new Excerpt(chunk.chunkId(), text.substring(0, end));
        }
    }

    /** Returns the report name. */
    String name();

    /** Returns the version and parameters, and any model, provider, prompt template and decoding settings. */
    String description();

    /** Returns what kind of generator this is. */
    AnswerComponent.Kind kind();

    /** Proposes a response from {@code excerpts} alone. */
    GroundedAnswer propose(String question, List<Excerpt> excerpts);

    /** Returns this generator as an answer component that bounds its excerpts. */
    default AnswerComponent asComponent() {
        var generator = this;
        return new AnswerComponent() {
            @Override
            public String name() {
                return generator.name();
            }

            @Override
            public String description() {
                return generator.description();
            }

            @Override
            public Kind kind() {
                return generator.kind();
            }

            @Override
            public String tuning() {
                return "none";
            }

            @Override
            public GroundedAnswer answer(String question, List<EvidenceChunk> evidence) {
                var excerpts = evidence.stream().limit(MAX_EXCERPTS).map(Excerpt::of).toList();
                return generator.propose(question, excerpts);
            }
        };
    }
}
