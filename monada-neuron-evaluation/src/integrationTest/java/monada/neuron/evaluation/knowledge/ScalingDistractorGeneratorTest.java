package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalingDistractorGeneratorTest {

    private final ScalingDistractorGenerator generator = new ScalingDistractorGenerator();

    @Test
    void sameSeedGivesTheSameChunksAndSmallerCountsArePrefixes() {
        var large = generator.generate(ScalingDistractorGenerator.MAX_COUNT, ScalingDistractorGenerator.DEFAULT_SEED);
        assertEquals(large, generator.generate(ScalingDistractorGenerator.MAX_COUNT,
                ScalingDistractorGenerator.DEFAULT_SEED));
        assertEquals(large.subList(0, 67), generator.generate(67, ScalingDistractorGenerator.DEFAULT_SEED));
        assertTrue(generator.generate(0, 1L).isEmpty());
    }

    @Test
    void differentSeedsGiveDifferentTexts() {
        var first = texts(generator.generate(50, 1L));
        var second = texts(generator.generate(50, 2L));
        assertNotEquals(first, second);
    }

    @Test
    void everyChunkHasItsOwnDocumentLabelsAndBounds() {
        var distractors = generator.generate(ScalingDistractorGenerator.MAX_COUNT, 7L);
        var digests = new HashSet<String>();
        for (var i = 0; i < distractors.size(); i++) {
            var document = distractors.get(i).document();
            var chunk = distractors.get(i).chunk();
            var slug = String.format(Locale.ROOT, "synthetic-%04d", i + 1);
            assertEquals("doc:" + slug, document.documentId());
            assertEquals("chk:" + slug + "#00", chunk.chunkId());
            assertEquals("generated:html-kd-v1:7", chunk.source());
            assertEquals("Apache-2.0", chunk.license());
            assertEquals(document.text(), chunk.text(), "one chunk spans the whole document");
            assertEquals(CorpusDigests.sha256(chunk.text()), chunk.sha256());
            assertTrue(chunk.text().getBytes(StandardCharsets.UTF_8).length <= CorpusBounds.SCALING.maxChunkBytes());
            assertTrue(chunk.conflict().isEmpty());
            assertTrue(digests.add(chunk.sha256()), "texts are unique");
        }
        assertThrows(IllegalArgumentException.class, () -> generator.generate(-1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(ScalingDistractorGenerator.MAX_COUNT + 1, 1L));
    }

    @Test
    void noTemplateStatesAKeyClaimOrEvidenceOrNamesANonAnswerableTopic() {
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        var sentences = ScalingDistractorGenerator.templateSentences().stream()
                .map(sentence -> sentence.toLowerCase(Locale.ROOT)).toList();
        for (var query : querySet.queries()) {
            for (var claim : query.keyClaims()) {
                var lower = claim.toLowerCase(Locale.ROOT);
                assertFalse(sentences.stream().anyMatch(sentence -> sentence.contains(lower)
                        || lower.contains(sentence)), query.queryId() + " key claim appears in a template");
            }
        }
        // The topics of the no-answer, out-of-domain and false-premise queries must stay unanswerable.
        for (var term : List.of("rowspan", "colspan", "canvas", "favicon", "viewport", "mobile", "boiling", "mona",
                "python", "sort", "twice as fast", "heading", "alt ", "caption", "hyperlink", "list")) {
            assertFalse(sentences.stream().anyMatch(sentence -> sentence.contains(term)), term);
        }
    }

    private static List<String> texts(List<ScalingDistractorGenerator.Distractor> distractors) {
        return distractors.stream().map(distractor -> distractor.chunk().text()).toList();
    }
}
