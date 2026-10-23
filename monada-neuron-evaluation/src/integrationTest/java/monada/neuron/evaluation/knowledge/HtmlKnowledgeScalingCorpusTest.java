package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlKnowledgeScalingCorpusTest {

    private static final HtmlKnowledgeCorpus BASE = HtmlKnowledgeCorpus.bundled();
    private static final HtmlKnowledgeQuerySet QUERIES = HtmlKnowledgeQuerySet.bundled(BASE);
    private static final long SEED = ScalingDistractorGenerator.DEFAULT_SEED;

    @Test
    void everyPointHasItsOwnStableHashAndTheRequestedSize() {
        var family = HtmlKnowledgeScalingCorpus.of(BASE, QUERIES, HtmlKnowledgeScalingCorpus.SIZES, SEED);
        var hashes = new HashSet<String>();
        for (var point : family.points()) {
            assertEquals("html-kc-v1-scale-" + point.size(), point.corpus().version());
            assertEquals(point.size(), point.corpus().chunks().size());
            assertEquals(point.size(), point.baseChunks() + point.generatedChunks());
            assertEquals(point.corpus().corpusHash(),
                    HtmlKnowledgeScalingCorpus.corpus(BASE, point.size(), SEED).corpusHash(), "reproducible");
            assertTrue(hashes.add(point.corpus().corpusHash()));
        }
        assertTrue(!hashes.contains(BASE.corpusHash()));
        assertNotEquals(HtmlKnowledgeScalingCorpus.corpus(BASE, 100, SEED).corpusHash(),
                HtmlKnowledgeScalingCorpus.corpus(BASE, 100, SEED + 1).corpusHash());
    }

    @Test
    void basePrefixIsKeptInChunkIdOrderWithTheConflictPair() {
        var small = HtmlKnowledgeScalingCorpus.corpus(BASE, 25, SEED);
        assertEquals(BASE.chunks().subList(0, 25), small.chunks());
        assertEquals(2, small.chunks().stream().filter(chunk -> chunk.conflict().isPresent()).count());
        var large = HtmlKnowledgeScalingCorpus.corpus(BASE, 500, SEED);
        var base = BASE.chunks().size();
        assertEquals(BASE.chunks(), large.chunks().subList(0, base));
        assertTrue(large.chunks().subList(base, 500).stream()
                .allMatch(chunk -> chunk.chunkId().startsWith("chk:synthetic-")));
    }

    @Test
    void queriesMissingARelevantChunkAreRemovedFromThePointAndTheCurve() {
        var family = HtmlKnowledgeScalingCorpus.of(BASE, QUERIES, HtmlKnowledgeScalingCorpus.SIZES, SEED);
        var expected = List.of("q:code-example-02", "q:exact-term-03", "q:exact-term-04", "q:exact-term-05",
                "q:false-premise-01");
        var small = family.points().getFirst();
        assertEquals(expected, small.removed());
        assertEquals(QUERIES.queries().size() - expected.size(), small.eligible().size());
        assertEquals(List.of(), family.points().get(1).removed());
        assertEquals(List.of(), family.points().get(2).removed());
        assertEquals(expected, family.excludedFromCurve());
        assertEquals(small.eligible(), family.curve());
        // A subset run without N = 25 keeps the same curve population.
        var subset = HtmlKnowledgeScalingCorpus.of(BASE, QUERIES, List.of(100, 500), SEED);
        assertEquals(expected, subset.excludedFromCurve());
        assertEquals(family.curve(), subset.curve());
    }

    @Test
    void onlyProtocolSizesInAscendingOrderAreAccepted() {
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingCorpus.of(BASE, QUERIES, List.of(50), SEED));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingCorpus.of(BASE, QUERIES, List.of(100, 25), SEED));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingCorpus.of(BASE, QUERIES, List.of(), SEED));
    }
}
