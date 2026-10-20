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
    void theKEqualsFiveTieIsReadFromTheLookaheadList() {
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        var rows = new ArrayList<Row>();
        var ids = List.of("chk:links#00", "chk:links#01", "chk:links#02", "chk:links#03", "chk:links#04",
                "chk:forms#00");
        for (var rank = 1; rank <= 6; rank++) {
            var score = rank <= 4 ? 1.0 - rank * 0.1 : 0.5;
            if (rank <= 5) {
                rows.add(new Row("q:exact-term-01", Layer.B0, 5, rank, ids.get(rank - 1), score));
            }
            rows.add(new Row("q:exact-term-01", Layer.B0, 6, rank, ids.get(rank - 1), score));
        }
        var score = RecallMetrics.score(querySet, new Snapshot(querySet.queryHash(), rows)).queries().stream()
                .filter(value -> value.layer() == Layer.B0 && value.query().queryId().equals("q:exact-term-01"))
                .findFirst().orElseThrow();

        assertTrue(score.byK().get(5).tieAtCutoff(), "ranks 5 and 6 share score 0.5");
        assertFalse(score.byK().get(3).tieAtCutoff());
        assertEquals(5, score.returned().orElseThrow().size(), "the lookahead row is never scored");
    }

    @Test
    void metricAggregatesAreReportedPerCategory() {
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        var rows = List.of(
                new Row("q:exact-term-01", Layer.B1, 5, 1, "chk:links#00", 0.9),
                new Row("q:exact-term-02", Layer.B1, 5, 1, "chk:links#00", 0.9),
                new Row("q:out-of-domain-01", Layer.B1, 5, 1, "chk:forms#00", 0.1));
        var b1 = RecallMetrics.score(querySet, new Snapshot(querySet.queryHash(), rows)).aggregates().stream()
                .filter(aggregate -> aggregate.layer() == Layer.B1 && aggregate.k() == 5).findFirst().orElseThrow();

        var exact = b1.byCategory().get(Category.EXACT_TERM);
        assertEquals(5, exact.scoredA());
        assertEquals(OptionalDouble.of(0.2), exact.answerable().get("Recall@5").value(), "1 of 5 exact-term hits");
        assertEquals(OptionalDouble.empty(), exact.noAnswerFalsePositiveRate(), "no U query in exact-term");
        var outOfDomain = b1.byCategory().get(Category.OUT_OF_DOMAIN);
        assertEquals(0, outOfDomain.scoredA());
        assertEquals(OptionalDouble.empty(), outOfDomain.answerable().get("Recall@5").value(), "undefined, never 0");
        assertEquals(OptionalDouble.of(1.0 / 3), outOfDomain.noAnswerFalsePositiveRate());
        assertEquals(OptionalDouble.of(0.0), b1.byCategory().get(Category.MULTI_EVIDENCE).fullEvidence().value(),
                "multi-evidence queries without results have FullEvidence 0");
        assertEquals(OptionalDouble.empty(), exact.fullEvidence().value(), "only multi-evidence carries FullEvidence");
    }

    @Test
    void theCodecEffectComparesOnlyQueriesWhoseLayersBothCompleted() {
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        var rows = List.of(
                new Row("q:exact-term-01", Layer.B1, 5, 0, HtmlKnowledgeRecall.FAILED, 0.0),
                new Row("q:exact-term-02", Layer.B2C, 5, 0, HtmlKnowledgeRecall.FAILED, 0.0),
                new Row("q:exact-term-03", Layer.B1, 5, 1, "chk:links#00", 0.9),
                new Row("q:exact-term-03", Layer.B2C, 5, 1, "chk:links#00", 0.9));
        var report = new HtmlKnowledgeRecallReport(querySet, RecallMetrics.score(querySet,
                new Snapshot(querySet.queryHash(), rows)));

        var json = report.toJson();
        assertTrue(json.contains("{ \"queryId\": \"q:exact-term-01\", \"comparable\": false, \"failedLayers\": [\"B1\"],"
                + " \"equalChunkOrder\": null }"), json);
        assertTrue(json.contains("{ \"queryId\": \"q:exact-term-02\", \"comparable\": false, \"failedLayers\":"
                + " [\"B2c\"], \"equalChunkOrder\": null }"), json);
        assertTrue(json.contains("{ \"queryId\": \"q:exact-term-03\", \"comparable\": true, \"B1\": [\"chk:links#00\"],"
                + " \"B2c\": [\"chk:links#00\"], \"equalChunkOrder\": true }"), json);
        assertTrue(report.toMarkdown().contains("for 0 of 30 comparable queries (2 not comparable because B1 or B2c"
                + " failed)"), report.toMarkdown());
    }

    @Test
    void aSnapshotOfAnotherQuerySetIsRefused() {
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        assertThrows(IllegalArgumentException.class,
                () -> RecallMetrics.score(querySet, new Snapshot("0".repeat(64), List.of())));
    }
}
