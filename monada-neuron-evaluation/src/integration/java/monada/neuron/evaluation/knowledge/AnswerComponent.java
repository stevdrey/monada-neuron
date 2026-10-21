package monada.neuron.evaluation.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * An explicit, optional L4 answer component (Issue #109): it answers one question from the evidence set
 * {@code E(q)}, the first {@code answerK} chunks one retrieval layer returned, and from nothing else. A component never
 * reads gold labels, the corpus or the Store; its only inputs are the question text and that evidence.
 */
interface AnswerComponent {

    /** What kind of component produced an answer, so a report can never present a test double as model quality. */
    enum Kind {
        /** A deterministic extractive baseline whose answers are judged and scored. */
        EXTRACTIVE_BASELINE,
        /** A canned test double behind the generator seam: never judged, never scored for answer quality. */
        TEST_DOUBLE
    }

    /**
     * One chunk of an evidence set, with the text the Store returned for it.
     *
     * @param chunkId the chunk the returned atom maps to through the root's manifest
     * @param rank the 1-based rank in the layer's result list
     * @param score the score the layer returned
     * @param text the Store-returned content, whose UTF-8 bytes hash to the corpus {@code sha256} of the chunk
     */
    record EvidenceChunk(String chunkId, int rank, double score, String text) {

        public EvidenceChunk {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    /** Returns the report name of the component. */
    String name();

    /** Returns its version and parameters, as the protocol's required report fields ask. */
    String description();

    /** Returns its kind. */
    Kind kind();

    /** Returns the label of a parameter chosen by looking at results on {@code html-kq-v1}, or {@code "none"}. */
    String tuning();

    /** Answers {@code question} from {@code evidence} alone, never throwing for an empty evidence set. */
    GroundedAnswer answer(String question, List<EvidenceChunk> evidence);
}
