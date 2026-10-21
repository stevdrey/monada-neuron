package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.EvidenceSegmenter.Segment;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Abstained;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswerGenerator.Excerpt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The B3 segment rule, the two extractive baselines and the generator test double. */
class ExtractiveAnswererTest {

    private final EvidenceSegmenter segmenter = new EvidenceSegmenter();
    private final ExtractiveAnswerer b3 = new ExtractiveAnswerer(ExtractiveAnswerer.Rule.B3);
    private final ExtractiveAnswerer b3c = new ExtractiveAnswerer(ExtractiveAnswerer.Rule.B3C);

    @Test
    void proseIsSplitAfterTerminalPunctuationFollowedByWhitespaceAndAtBlankLines() {
        assertEquals(List.of("A b.", "C d?", "E!", "F g"), texts(segmenter.segments("A b. C d? E!\n\n  F g  ")));
        assertEquals(List.of("Call form.submit() now."), texts(segmenter.segments("Call form.submit() now.")));
        assertEquals(List.of("## Heading", "Body text."), texts(segmenter.segments("## Heading\n\nBody text.\n")));
        assertEquals(List.of(), segmenter.segments(" \n\n \t"));
    }

    @Test
    void aFencedCodeBlockIsOneSegmentWithItsFences() {
        var text = "Intro. More\n```\n<a href=\"x\">x.</a> y\n```\nAfter it.";
        assertEquals(List.of("Intro.", "More", "```\n<a href=\"x\">x.</a> y\n```", "After it."),
                texts(segmenter.segments(text)));
        assertEquals(List.of("Open.", "```\nno close. still code"),
                texts(segmenter.segments("Open.\n```\nno close. still code")));
    }

    @Test
    void spansAreCodePointOffsetsThatCiteExactlyTheSegmentText() {
        var text = "😀 smile. Then 🚀 rocket!";
        var segments = segmenter.segments(text);
        assertEquals(List.of(new Segment(0, 8, "😀 smile."), new Segment(9, 23, "Then 🚀 rocket!")),
                segments);
        var codePoints = text.codePoints().toArray();
        for (var segment : segments) {
            assertEquals(segment.text(), new String(codePoints, segment.start(), segment.end() - segment.start()));
        }
    }

    @Test
    void b3QuotesTheBestSegmentOfTheFirstChunkWithTiesGoingToTheEarliest() {
        var evidence = List.of(chunk("chk:a#00", "Links go here. The a element creates a hyperlink. A element again."),
                chunk("chk:b#00", "The a element creates a hyperlink to another page entirely."));
        var answer = assertInstanceOf(Answered.class, b3.answer("What does the a element create?", evidence));
        assertEquals(1, answer.claims().size());
        var claim = answer.claims().getFirst();
        assertEquals("The a element creates a hyperlink.", claim.text());
        assertEquals(List.of(new Citation("chk:a#00", 15, 49)), claim.citations());

        var tie = assertInstanceOf(Answered.class, b3.answer("element", List.of(chunk("chk:a#00",
                "One element. Two element."))));
        assertEquals("One element.", tie.claims().getFirst().text());
    }

    @Test
    void b3AbstainsOnlyOnEmptyEvidenceOrAZeroScore() {
        assertEquals(GroundedAnswer.INSUFFICIENT_EVIDENCE,
                assertInstanceOf(Abstained.class, b3.answer("anything", List.of())).status());
        assertInstanceOf(Abstained.class, b3.answer("zebra", List.of(chunk("chk:a#00", "Nothing matches here."))));
        assertInstanceOf(Abstained.class, b3.answer("   ", List.of(chunk("chk:a#00", "Nothing matches here."))));
        assertInstanceOf(Answered.class, b3.answer("the boiling point of water",
                List.of(chunk("chk:a#00", "The img element embeds a picture."))));
    }

    @Test
    void b3cAnswersOnlyWhenTheSegmentHoldsAtLeastHalfOfTheDistinctQueryTokens() {
        var evidence = List.of(chunk("chk:a#00", "The img element embeds a picture."));
        assertInstanceOf(Answered.class, b3c.answer("img embeds zebra lion", evidence), "2 of 4 is exactly half");
        assertInstanceOf(Abstained.class, b3c.answer("img zebra lion tiger", evidence), "1 of 4 is under half");
        assertInstanceOf(Abstained.class, b3c.answer("the boiling point of water", evidence));
        assertTrue(b3c.tuning().startsWith("in-sample (tuned)"));
        assertEquals("none", b3.tuning());
        assertEquals(AnswerComponent.Kind.EXTRACTIVE_BASELINE, b3c.kind());
    }

    @Test
    void theTestDoubleEchoesTheFirstSegmentAndIsLabelled() {
        var generator = new TestDoubleAnswerGenerator().asComponent();
        assertEquals(AnswerComponent.Kind.TEST_DOUBLE, generator.kind());
        assertTrue(generator.description().startsWith("TEST_DOUBLE"));
        var answer = assertInstanceOf(Answered.class, generator.answer("ignored",
                List.of(chunk("chk:a#00", "First one. Second one."), chunk("chk:b#00", "Other."))));
        assertEquals("First one.", answer.claims().getFirst().text());
        assertEquals(List.of(new Citation("chk:a#00", 0, 10)), answer.claims().getFirst().citations());
        assertInstanceOf(Abstained.class, generator.answer("ignored", List.of()));
    }

    @Test
    void excerptsAreBoundedPrefixes() {
        var longText = "x".repeat(GroundedAnswerGenerator.MAX_EXCERPT_CODE_POINTS + 10);
        var excerpt = Excerpt.of(chunk("chk:a#00", longText));
        assertEquals(GroundedAnswerGenerator.MAX_EXCERPT_CODE_POINTS, excerpt.text().length());
        assertThrows(IllegalArgumentException.class, () -> new Excerpt("chk:a#00", longText));
        var emoji = "😀".repeat(GroundedAnswerGenerator.MAX_EXCERPT_CODE_POINTS + 1);
        var bounded = Excerpt.of(chunk("chk:a#00", emoji));
        assertEquals(GroundedAnswerGenerator.MAX_EXCERPT_CODE_POINTS,
                bounded.text().codePointCount(0, bounded.text().length()));
    }

    @Test
    void citationsRenderAndParseAndRejectInvalidRanges() {
        var citation = new Citation("chk:links#00", 3, 9);
        assertEquals("chk:links#00:3:9", citation.render());
        assertEquals(citation, Citation.parse(citation.render()));
        assertThrows(IllegalArgumentException.class, () -> new Citation("chk:a#00", 5, 4));
        assertThrows(IllegalArgumentException.class, () -> new Citation("chk:a#00", 5, 5), "an empty span cites nothing");
        assertThrows(IllegalArgumentException.class, () -> Citation.parse("chk:a#00:3:3"));
        assertThrows(IllegalArgumentException.class, () -> new Citation("chk:a#00", -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new Answered(List.of()));
    }

    private static EvidenceChunk chunk(String chunkId, String text) {
        return new EvidenceChunk(chunkId, 1, 1.0, text);
    }

    private static List<String> texts(List<Segment> segments) {
        return segments.stream().map(Segment::text).toList();
    }
}
