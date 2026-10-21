package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.GroundedAnswer.Abstained;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Claim;

import java.util.List;

/**
 * The deterministic {@link AnswerComponent.Kind#TEST_DOUBLE} behind {@link GroundedAnswerGenerator} (Issue #109). It
 * ignores the question and echoes the first segment of the first excerpt, citing it, or abstains when there is no
 * excerpt. It exists to exercise the seam and the citation checks; it is not a language model, and its rows are never
 * judged or scored for answer quality.
 */
final class TestDoubleAnswerGenerator implements GroundedAnswerGenerator {

    private final EvidenceSegmenter segmenter = new EvidenceSegmenter();

    @Override
    public String name() {
        return "generator-test-double";
    }

    @Override
    public String description() {
        return "TEST_DOUBLE: echoes the first segment of the first excerpt with its citation; no model, no provider, no"
                + " prompt, no network";
    }

    @Override
    public AnswerComponent.Kind kind() {
        return AnswerComponent.Kind.TEST_DOUBLE;
    }

    @Override
    public GroundedAnswer propose(String question, List<Excerpt> excerpts) {
        if (excerpts.isEmpty()) {
            return Abstained.insufficient("no excerpt");
        }
        var first = excerpts.getFirst();
        var segments = segmenter.segments(first.text());
        if (segments.isEmpty()) {
            return Abstained.insufficient("the first excerpt has no segment");
        }
        var segment = segments.getFirst();
        return new Answered(List.of(new Claim(segment.text(),
                List.of(new Citation(first.chunkId(), segment.start(), segment.end())))));
    }
}
