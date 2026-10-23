package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.RecallDiagnosisLadder.Rung;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallDiagnosisLadderTest {

    private static final HtmlKnowledgeCorpus BASE = HtmlKnowledgeCorpus.bundled();
    private static final HtmlKnowledgeQuerySet QUERY_SET = HtmlKnowledgeQuerySet.bundled(BASE);
    private static final long SEED = ScalingDistractorGenerator.DEFAULT_SEED;

    @Test
    void theDistractorStudyKeepsTheCompleteBaseAtEveryPoint() {
        var ladder = RecallDiagnosisLadder.build(BASE, QUERY_SET, List.of(Rung.values()), SEED);
        var base = BASE.chunks().size();

        assertEquals(List.of(25, 33, 100, 500), ladder.stream().map(point -> point.point().size()).toList());
        assertEquals(List.of(25, base, base, base), ladder.stream().map(point -> point.point().baseChunks()).toList());
        assertEquals(List.of(0, 0, 67, 467), ladder.stream().map(point -> point.point().generatedChunks()).toList());
        for (var point : ladder.subList(1, ladder.size())) {
            var ids = point.point().corpus().chunks().stream().map(CorpusChunk::chunkId).toList();
            assertTrue(ids.containsAll(BASE.chunks().stream().map(CorpusChunk::chunkId).toList()),
                    point.rung().label());
        }
    }

    @Test
    void sharedPointsAreTheScalingProfilesOwn() {
        var ladder = RecallDiagnosisLadder.build(BASE, QUERY_SET, List.of(Rung.BASE_25, Rung.N100, Rung.N500), SEED);
        var family = HtmlKnowledgeScalingCorpus.of(BASE, QUERY_SET, HtmlKnowledgeScalingCorpus.SIZES, SEED);

        for (var i = 0; i < ladder.size(); i++) {
            assertEquals(family.points().get(i).corpus().corpusHash(), ladder.get(i).point().corpus().corpusHash(),
                    ladder.get(i).rung().label() + " is #111's point of the same size");
        }
    }

    @Test
    void theCurveCohortIsEligibleAtEveryPoint() {
        var family = HtmlKnowledgeScalingCorpus.of(BASE, QUERY_SET, HtmlKnowledgeScalingCorpus.SIZES, SEED);

        for (var point : RecallDiagnosisLadder.build(BASE, QUERY_SET, List.of(Rung.values()), SEED)) {
            assertTrue(point.point().eligible().containsAll(family.curve()), point.rung().label());
        }
        assertEquals(27, family.curve().size());
    }

    @Test
    void theStudiesChangeOneVariableEach() {
        assertEquals(List.of(Rung.BASE_25, Rung.BASE_33), RecallDiagnosisLadder.BASE_GROWTH);
        assertEquals(List.of(Rung.BASE_33, Rung.N100, Rung.N500), RecallDiagnosisLadder.DISTRACTOR_GROWTH);
    }

    @Test
    void rungsMustBeKnownDistinctAndOrdered() {
        assertEquals(Rung.N100, Rung.ofLabel("n100"));
        assertThrows(IllegalArgumentException.class, () -> Rung.ofLabel("n200"));
        assertThrows(IllegalArgumentException.class,
                () -> RecallDiagnosisLadder.build(BASE, QUERY_SET, List.of(Rung.N100, Rung.BASE_33), SEED));
        assertThrows(IllegalArgumentException.class,
                () -> RecallDiagnosisLadder.build(BASE, QUERY_SET, List.of(Rung.N100, Rung.N100), SEED));
        assertThrows(IllegalArgumentException.class, () -> RecallDiagnosisLadder.build(BASE, QUERY_SET, List.of(),
                SEED));
    }
}
