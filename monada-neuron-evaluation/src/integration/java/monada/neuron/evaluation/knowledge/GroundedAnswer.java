package monada.neuron.evaluation.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * The answer an L4 answer component gives one question (Issue #109, protocol extension {@code l4-answer-v1}): either an
 * abstention or a non-empty list of claims, each citing spans of the Store-returned evidence. An answer carries no
 * chunk text of its own beyond its claims, and nothing in it is read from gold labels.
 */
sealed interface GroundedAnswer permits GroundedAnswer.Abstained, GroundedAnswer.Answered {

    /** The abstention status every component reports when the evidence does not support an answer. */
    String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";

    /**
     * A non-empty half-open {@code [start, end)} range of code points into the normalized text of one evidence chunk; an
     * empty range cites no evidence and is rejected.
     *
     * @param chunkId the cited chunk
     * @param start first cited code point (inclusive)
     * @param end last cited code point (exclusive)
     */
    record Citation(String chunkId, int start, int end) {

        public Citation {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid citation range [" + start + ", " + end + ")");
            }
        }

        /** Renders {@code chunkId:start:end}. */
        String render() {
            return chunkId + ":" + start + ":" + end;
        }

        /** Parses {@link #render()} output. */
        static Citation parse(String value) {
            var end = value.lastIndexOf(':');
            var start = value.lastIndexOf(':', end - 1);
            if (start <= 0 || end <= start) {
                throw new IllegalArgumentException("not a citation: " + value);
            }
            return new Citation(value.substring(0, start), Integer.parseInt(value.substring(start + 1, end)),
                    Integer.parseInt(value.substring(end + 1)));
        }

        /** Whether this citation and {@code other} name the same chunk and share at least one code point. */
        boolean overlaps(String otherChunkId, int otherStart, int otherEnd) {
            return chunkId.equals(otherChunkId) && start < otherEnd && otherStart < end;
        }
    }

    /**
     * One emitted claim.
     *
     * @param text the claim text exactly as emitted
     * @param citations the spans it cites, in emitted order; may be empty, which makes the claim unsupported
     */
    record Claim(String text, List<Citation> citations) {

        public Claim {
            Objects.requireNonNull(text, "text must not be null");
            citations = List.copyOf(citations);
        }
    }

    /**
     * An abstention.
     *
     * @param status always {@link #INSUFFICIENT_EVIDENCE} for the components of #109
     * @param reason why the component abstained, for diagnostics only
     */
    record Abstained(String status, String reason) implements GroundedAnswer {

        public Abstained {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        /** An {@link #INSUFFICIENT_EVIDENCE} abstention. */
        static Abstained insufficient(String reason) {
            return new Abstained(INSUFFICIENT_EVIDENCE, reason);
        }
    }

    /**
     * An answer.
     *
     * @param claims at least one claim, in emitted order
     */
    record Answered(List<Claim> claims) implements GroundedAnswer {

        public Answered {
            claims = List.copyOf(claims);
            if (claims.isEmpty()) {
                throw new IllegalArgumentException("an answer has at least one claim; abstain instead");
            }
        }
    }
}
