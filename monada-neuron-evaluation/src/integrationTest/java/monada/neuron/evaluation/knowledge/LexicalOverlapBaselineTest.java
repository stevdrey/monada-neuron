package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.LexicalOverlapBaseline.Hit;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalOverlapBaselineTest {

    private final HtmlKnowledgeCorpus corpus = HtmlKnowledgeCorpus.bundled();
    private final LexicalOverlapBaseline baseline = new LexicalOverlapBaseline(corpus);

    @Test
    void scoresDistinctQueryTokensAndBreaksTiesByChunkId() {
        var hits = baseline.search("fieldset fieldset legend", 5);

        assertEquals(new Hit("chk:forms#05", 2), hits.getFirst());
        var expected = hits.stream().sorted(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(Hit::chunkId)).toList();
        assertEquals(expected, hits);
    }

    @Test
    void dropsZeroScoresAndTruncatesToTheLimit() {
        assertEquals(List.of(), baseline.search("zzzz qqqq", 5));
        assertEquals(List.of(), baseline.search("   ", 5));
        var many = baseline.search("the", 5);
        assertEquals(5, many.size());
        assertTrue(many.stream().allMatch(hit -> hit.score() == 1));
        assertEquals(many.stream().map(Hit::chunkId).sorted().toList(), many.stream().map(Hit::chunkId).toList());
        assertEquals(many.subList(0, 3), baseline.search("the", 3));
        assertThrows(IllegalArgumentException.class, () -> baseline.search("the", 0));
    }
}
