package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusChunkerTest {

    private final CorpusChunker chunker = new CorpusChunker(1024);

    @Test
    void chunksAtHeadingsAndMergesHeadingOnlySectionsIntoTheNext() {
        var document = "# Title\n\n## Alpha\n\nAlpha body.\n\n## Beta\nBeta body.";

        var spans = chunker.chunk(document, "doc");

        assertEquals(2, spans.size());
        assertEquals("Alpha", spans.get(0).section());
        assertEquals("# Title\n\n## Alpha\n\nAlpha body.", spans.get(0).text());
        assertEquals("Beta", spans.get(1).section());
        assertEquals("## Beta\nBeta body.", spans.get(1).text());
    }

    @Test
    void textBeforeTheFirstHeadingIsAPreambleChunk() {
        var spans = chunker.chunk("Intro text.\n\n## Topic\n\nBody.", "doc");

        assertEquals(CorpusChunker.PREAMBLE, spans.getFirst().section());
        assertEquals("Intro text.", spans.getFirst().text());
        assertEquals("## Topic\n\nBody.", spans.get(1).text());
    }

    @Test
    void neverTreatsFenceContentAsHeadingsOrParagraphBreaks() {
        var document = "## Code\n\n```\n# not a heading\n\n<p>still code</p>\n```\n\nAfter the code.";

        var spans = chunker.chunk(document, "doc");

        assertEquals(1, spans.size());
        assertEquals(document, spans.getFirst().text());
    }

    @Test
    void splitsAnOversizeSectionAtParagraphsThenSentencesButKeepsFencesWhole() {
        var small = new CorpusChunker(60);
        var document = "## S\n\nFirst short paragraph.\n\n"
                + "Sentence one is here. Sentence two is also here. Sentence three ends it.\n\n"
                + "```\n<b>x</b>\n```";

        var spans = small.chunk(document, "doc");

        assertEquals(3, spans.size());
        assertEquals("## S\n\nFirst short paragraph.\n\nSentence one is here.", spans.get(0).text());
        assertEquals("Sentence two is also here. Sentence three ends it.", spans.get(1).text());
        assertEquals("```\n<b>x</b>\n```", spans.get(2).text());
        assertTrue(spans.stream().allMatch(span -> span.section().equals("S")));
        assertTrue(spans.stream().allMatch(span -> span.text().getBytes(StandardCharsets.UTF_8).length <= 60));
        assertSpansMatch(document, spans);
    }

    @Test
    void keepsAHeadingWithTheFirstSentencesWhenItsWholeParagraphDoesNotFitWithIt() {
        var document = "## Heading\n\nFirst short one. Second sentence here.";

        var spans = new CorpusChunker(40).chunk(document, "doc");

        assertEquals(List.of("## Heading\n\nFirst short one.", "Second sentence here."),
                spans.stream().map(CorpusChunker.Span::text).toList());
        assertSpansMatch(document, spans);
    }

    @Test
    void emitsHeadingsAsTheirOwnChunkWhenNothingAfterThemFits() {
        var code = "## Code sample\n\n```\n<p>x</p>\n```";
        var sentence = "## Heading one\n\nA single sentence of words.";
        var headings = "# Top level title\n## Second heading\nBody.";

        assertEquals(List.of("## Code sample", "```\n<p>x</p>\n```"),
                new CorpusChunker(30).chunk(code, "code").stream().map(CorpusChunker.Span::text).toList());
        assertEquals(List.of("## Heading one", "A single sentence of words."),
                new CorpusChunker(30).chunk(sentence, "sentence").stream().map(CorpusChunker.Span::text).toList());
        var headingSpans = new CorpusChunker(20).chunk(headings, "headings");
        assertEquals(List.of("# Top level title", "## Second heading", "Body."),
                headingSpans.stream().map(CorpusChunker.Span::text).toList());
        assertTrue(headingSpans.stream().allMatch(span -> span.section().equals("Second heading")));
        assertSpansMatch(code, new CorpusChunker(30).chunk(code, "code"));
        assertSpansMatch(headings, headingSpans);
    }

    @Test
    void measuresSentencesAndBlocksWithoutTheirSurroundingWhitespace() {
        var word = "b".repeat(18) + ".";
        var sentences = "A. " + word;
        var trailing = "## H\n\nFits.   \nTwo.      ";

        assertEquals(List.of("A.", word),
                new CorpusChunker(19).chunk(sentences, "sentences").stream().map(CorpusChunker.Span::text).toList());
        var spans = new CorpusChunker(20).chunk("## H\n\nFits.   \nTwo.      \n\n## Next\n\nMore.", "trailing");
        assertEquals("## H\n\nFits.   \nTwo.", spans.getFirst().text());
        assertEquals(2, spans.size());
        assertTrue(trailing.getBytes(StandardCharsets.UTF_8).length > 20);
    }

    @Test
    void reportsCodePointOffsetsForNonBmpText() {
        var document = "## Emoji \uD83D\uDE00\n\nSmile \uD83D\uDE00 text.\n\n## Next\n\nMore.";

        var spans = chunker.chunk(document, "doc");

        assertEquals("Emoji \uD83D\uDE00", spans.getFirst().section());
        var secondStart = document.codePointCount(0, document.indexOf("## Next"));
        assertEquals(secondStart, spans.get(1).start());
        assertSpansMatch(document, spans);
    }

    @Test
    void rejectsUnsplittableOrMalformedInput() {
        var small = new CorpusChunker(16);

        assertTrue(assertThrows(CorpusValidationException.class,
                () -> small.chunk("## C\n\n```\n0123456789abcdef\n```", "code.md")).getMessage()
                .contains("code example"));
        assertTrue(assertThrows(CorpusValidationException.class,
                () -> small.chunk("## C\n\nOne sentence that is far too long.", "long.md")).getMessage()
                .contains("sentence"));
        assertTrue(assertThrows(CorpusValidationException.class,
                () -> chunker.chunk("## C\n\n```\nopen fence", "open.md")).getMessage()
                .contains("unterminated fenced code block"));
        assertTrue(assertThrows(CorpusValidationException.class,
                () -> chunker.chunk("  \n ", "empty.md")).getMessage().contains("empty"));
    }

    private void assertSpansMatch(String document, List<CorpusChunker.Span> spans) {
        for (var span : spans) {
            var start = document.offsetByCodePoints(0, span.start());
            var end = document.offsetByCodePoints(0, span.end());
            assertEquals(span.text(), document.substring(start, end));
        }
    }
}
