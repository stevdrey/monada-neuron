package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import monada.neuron.evaluation.knowledge.RecallMetrics.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallMetricsTest {

    private static final Set<String> REL = Set.of("a", "b");

    @Test
    void perQueryFormulasFollowTheProtocol() {
        assertEquals(OptionalDouble.of(0.5), RecallMetrics.recall(REL, List.of("x", "a", "y")));
        assertEquals(OptionalDouble.of(1.0 / 3), RecallMetrics.precision(REL, List.of("x", "a", "y"), 3));
        assertEquals(OptionalDouble.of(1.0 / 3), RecallMetrics.precision(REL, List.of("a"), 3),
                "the fixed denominator k does not reward returning fewer results");
        assertEquals(OptionalDouble.of(1.0), RecallMetrics.hitPrecision(REL, List.of("a")));
        assertEquals(OptionalDouble.of(0.5), RecallMetrics.reciprocalRank(REL, List.of("x", "b", "a")));
        assertEquals(OptionalDouble.of(0.0), RecallMetrics.reciprocalRank(REL, List.of("x")));
        assertEquals(OptionalDouble.of(1.0), RecallMetrics.coverage(REL, List.of("x", "b")));
        assertEquals(OptionalDouble.of(0.0), RecallMetrics.fullEvidence(REL, List.of("x", "b")));
        assertEquals(OptionalDouble.of(1.0), RecallMetrics.fullEvidence(REL, List.of("b", "a")));
    }

    @Test
    void zeroDenominatorsAreUndefinedNeverZero() {
        assertEquals(OptionalDouble.empty(), RecallMetrics.recall(Set.of(), List.of("a")));
        assertEquals(OptionalDouble.empty(), RecallMetrics.hitPrecision(REL, List.of()));
        assertEquals(OptionalDouble.of(0.0), RecallMetrics.recall(REL, List.of()), "zero hits is a defined 0");
        var mean = RecallMetrics.mean(List.of(OptionalDouble.of(1.0), OptionalDouble.empty(), OptionalDouble.of(0.0)));
        assertEquals(OptionalDouble.of(0.5), mean.value());
        assertEquals(2, mean.nDefined());
        assertEquals(1, mean.nUndefined());
        assertEquals(OptionalDouble.empty(), RecallMetrics.mean(List.of(OptionalDouble.empty())).value());
        assertEquals(OptionalDouble.empty(), RecallMetrics.mean(List.of()).value());
    }

    @Test
    void outcomesUseTheFirstApplicableRow() {
        assertEquals(Outcome.HIT, RecallMetrics.outcome(Set.of("a"), REL, List.of("x", "a")));
        assertEquals(Outcome.PARTIAL, RecallMetrics.outcome(REL, REL, List.of("a")));
        assertEquals(Outcome.PARTIAL, RecallMetrics.outcome(Set.of("a"), REL, List.of("b")),
                "a supporting chunk alone is partial, not a hit");
        assertEquals(Outcome.MISS_WITH_RESULTS, RecallMetrics.outcome(REL, REL, List.of("x")));
        assertEquals(Outcome.NO_EVIDENCE, RecallMetrics.outcome(REL, REL, List.of()));
        assertEquals(Outcome.CORRECT_ABSTENTION, RecallMetrics.outcome(Set.of(), Set.of(), List.of()));
        assertEquals(Outcome.FALSE_EVIDENCE, RecallMetrics.outcome(Set.of(), Set.of(), List.of("x")));
    }

    @Test
    void tiesAtTheCutoffAreFlagged() {
        assertTrue(RecallMetrics.tieAtCutoff(List.of(0.9, 0.5, 0.5), 2));
        assertFalse(RecallMetrics.tieAtCutoff(List.of(0.9, 0.5, 0.5), 1));
        assertFalse(RecallMetrics.tieAtCutoff(List.of(0.9, 0.5), 2), "nothing beyond the cutoff");
    }

    @Test
    void scoringTruncatesToKAndKeepsFailuresAndEmptyGoldVisible() {
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        var rows = new ArrayList<Row>();
        // q:exact-term-01 requires chk:links#00: found at rank 2 of 5.
        rows.add(new Row("q:exact-term-01", Layer.B1, 5, 1, "chk:links#01", 0.9));
        rows.add(new Row("q:exact-term-01", Layer.B1, 5, 2, "chk:links#00", 0.8));
        rows.add(new Row("q:exact-term-01", Layer.B1, 5, 3, "chk:forms#00", 0.8));
        // q:no-answer-01 has no relevant chunk and returned one.
        rows.add(new Row("q:no-answer-01", Layer.B1, 5, 1, "chk:forms#00", 0.1));
        // q:exact-term-02 failed operationally in B1.
        rows.add(new Row("q:exact-term-02", Layer.B1, 5, 0, HtmlKnowledgeRecall.FAILED, 0.0));
        var scores = RecallMetrics.score(querySet, new Snapshot(querySet.queryHash(), rows));

        var hit = scores.queries().stream().filter(score -> score.layer() == Layer.B1
                && score.query().queryId().equals("q:exact-term-01")).findFirst().orElseThrow();
        assertEquals(Outcome.MISS_WITH_RESULTS, hit.byK().get(1).outcome());
        assertEquals(Outcome.HIT, hit.byK().get(3).outcome());
        assertTrue(hit.byK().get(1).tieAtCutoff() == false && hit.byK().get(3).tieAtCutoff() == false);
        assertTrue(hit.byK().get(1).metrics().get("Recall@1").isPresent());
        assertEquals(0.5, hit.byK().get(3).metrics().get("MRR@3").getAsDouble());

        var falseEvidence = scores.queries().stream().filter(score -> score.layer() == Layer.B1
                && score.query().queryId().equals("q:no-answer-01")).findFirst().orElseThrow();
        assertEquals(Outcome.FALSE_EVIDENCE, falseEvidence.byK().get(5).outcome());
        assertTrue(falseEvidence.byK().get(5).metrics().isEmpty(), "U queries have no A metrics");

        var failed = scores.queries().stream().filter(score -> score.layer() == Layer.B1
                && score.query().queryId().equals("q:exact-term-02")).findFirst().orElseThrow();
        assertFalse(failed.completed());

        var b1 = scores.aggregates().stream().filter(aggregate -> aggregate.layer() == Layer.B1
                && aggregate.k() == 5).findFirst().orElseThrow();
        assertEquals(21, b1.scoredA(), "22 answerable queries minus the failed one");
        assertEquals(10, b1.scoredU());
        assertEquals(OptionalDouble.of(0.1), b1.noAnswerFalsePositiveRate());
        assertEquals(1, b1.outcomes().get(Category.EXACT_TERM).get(Outcome.HIT));
        var abstention = scores.abstentions().stream().filter(value -> value.layer() == Layer.B1).findFirst()
                .orElseThrow();
        assertEquals(1, abstention.unscored());
        assertEquals(OptionalDouble.of(0.9), abstention.abstentionRecall());
        assertEquals(OptionalDouble.of(9.0 / 29), abstention.abstentionPrecision());
    }

    @Test
    void aSnapshotOfAnotherQuerySetIsRefused() {
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        assertThrows(IllegalArgumentException.class,
                () -> RecallMetrics.score(querySet, new Snapshot("0".repeat(64), List.of())));
    }
}
