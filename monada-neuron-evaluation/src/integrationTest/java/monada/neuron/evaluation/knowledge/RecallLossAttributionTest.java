package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.ChunkFate;
import monada.neuron.evaluation.knowledge.RecallLossAttribution.Reason;
import monada.neuron.evaluation.knowledge.SignalHitCapture.Hit;
import monada.neuron.evaluation.knowledge.SignalHitCapture.QueryCapture;
import monada.neuron.resonance.adapter.OpaqueReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallLossAttributionTest {

    private static final OpaqueReference REFERENCES = new OpaqueReference();

    /**
     * Full per-token lists; pool {@code p} is the prefix of length {@code p} of each. Relevant: r1 retrieved, r3 tied
     * at the k = 3 cutoff, r2 below it, r4 only beyond rank 5, r5 nowhere and r6 not in the corpus.
     */
    private static final String[][] FULL = {
            {"r1:0.9", "x:0.8", "y:0.7", "w:0.2", "v:0.15", "r4:0.1"},
            {"chk:synthetic-0001#00:0.7", "r3:0.7", "r2:0.3"}};

    private static final JudgedQuery ANSWERABLE = query("q:multi-evidence-01", Category.MULTI_EVIDENCE, "alpha beta",
            Set.of("r1", "r2", "r3"), Set.of("r4", "r5", "r6"));
    private static final JudgedQuery NEGATIVE = query("q:no-answer-01", Category.NO_ANSWER, "gamma", Set.of(),
            Set.of());
    private static final JudgedQuery FAILING = query("q:exact-term-01", Category.EXACT_TERM, "delta", Set.of("r1"),
            Set.of());

    private static JudgedQuery query(String id, Category category, String text, Set<String> required,
            Set<String> supporting) {
        return new JudgedQuery(id, category, text, required, supporting, List.of(), List.of(), Set.of());
    }

    private static QueryCapture capture(String queryId, List<String> tokens, String[][] full) {
        var lists = new LinkedHashMap<Integer, List<List<Hit>>>();
        for (var pool : SignalHitCapture.POOLS) {
            var perSignal = new ArrayList<List<Hit>>();
            for (var signal = 0; signal < full.length; signal++) {
                var hits = new ArrayList<Hit>();
                for (var rank = 0; rank < Math.min(pool, full[signal].length); rank++) {
                    var cell = full[signal][rank];
                    var split = cell.lastIndexOf(':');
                    var chunk = cell.substring(0, split);
                    hits.add(new Hit(signal, rank, chunk, REFERENCES.of(chunk),
                            Double.parseDouble(cell.substring(split + 1))));
                }
                perSignal.add(hits);
            }
            lists.put(pool, perSignal);
        }
        return new QueryCapture(queryId, tokens, lists, Optional.empty());
    }

    private static List<Row> rows(String queryId, Layer layer, List<String> chunks) {
        var rows = new ArrayList<Row>();
        for (var i = 0; i < chunks.size(); i++) {
            rows.add(new Row(queryId, layer, HtmlKnowledgeRecall.MAX_LIMIT, i + 1, chunks.get(i), 1.0 - i * 0.1));
        }
        return rows;
    }

    private static RecallLossAttribution attribution() {
        var snapshotRows = new ArrayList<Row>();
        snapshotRows.addAll(rows(ANSWERABLE.queryId(), Layer.B1, List.of("r5", "r1", "r2", "r3", "r4")));
        snapshotRows.addAll(rows(ANSWERABLE.queryId(), Layer.B2, List.of("r1", "x", "y", "chk:synthetic-0001#00",
                "r3")));
        snapshotRows.addAll(rows(NEGATIVE.queryId(), Layer.B1, List.of("x")));
        snapshotRows.addAll(rows(NEGATIVE.queryId(), Layer.B2, List.of()));
        for (var layer : List.of(Layer.B1, Layer.B2)) {
            snapshotRows.add(new Row(FAILING.queryId(), layer, HtmlKnowledgeRecall.MAX_LIMIT, 0,
                    HtmlKnowledgeRecall.FAILED, 0.0));
        }
        var captures = List.of(capture(ANSWERABLE.queryId(), List.of("alpha", "beta"), FULL),
                capture(NEGATIVE.queryId(), List.of("gamma"), new String[][] {{}}),
                new QueryCapture(FAILING.queryId(), List.of("delta"), Map.of(), Optional.of("the Store threw")));
        return new RecallLossAttribution(new Snapshot("hash", snapshotRows), captures,
                Map.of("alpha", 2, "beta", 9, "gamma", 1, "delta", 1),
                Set.of("r1", "r2", "r3", "r4", "r5", "x", "y", "w", "v", "chk:synthetic-0001#00"));
    }

    private static Map<String, Reason> reasons(List<ChunkFate> fates) {
        return fates.stream().collect(Collectors.toMap(ChunkFate::chunkId, ChunkFate::reason));
    }

    @Test
    void everyRelevantChunkGetsExactlyOneReason() {
        var fates = attribution().fates(ANSWERABLE, 3);

        assertEquals(Map.of("r1", Reason.RETRIEVED, "r2", Reason.OUTRANKED_IN_MERGE, "r3", Reason.TIE_BROKEN_OUT,
                "r4", Reason.RECOVERABLE_WITH_LARGER_POOL, "r5", Reason.ABSENT_FROM_ALL_TOKEN_LISTS,
                "r6", Reason.MISSING_FROM_CORPUS), reasons(fates));
        var r3 = fates.stream().filter(fate -> fate.chunkId().equals("r3")).findFirst().orElseThrow();
        assertEquals(0.7, r3.cutoffScore().getAsDouble(), "the cutoff is B2's rank-3 raw score");
        assertEquals(Optional.of("beta"), r3.bestToken());
        assertTrue(r3.required());
        var r4 = fates.stream().filter(fate -> fate.chunkId().equals("r4")).findFirst().orElseThrow();
        assertEquals(6, r4.bestRankExpanded().getAsInt(), "found at Store rank 6 of the expanded pool only");
        assertEquals(0, r4.signalsAtPoolK());
        var r5 = fates.stream().filter(fate -> fate.chunkId().equals("r5")).findFirst().orElseThrow();
        assertTrue(r5.inB1(), "the full-text contrast is recorded per chunk");
        assertTrue(r5.bestToken().isEmpty());
    }

    @Test
    void theAttributionDependsOnTheCutoff() {
        var attribution = attribution();

        // At k = 5 the pool-5 lists still miss r4, and B2's top 5 now holds r3.
        var atFive = reasons(attribution.fates(ANSWERABLE, 5));
        assertEquals(Reason.RETRIEVED, atFive.get("r3"));
        assertEquals(Reason.OUTRANKED_IN_MERGE, atFive.get("r2"));
        assertEquals(Reason.RECOVERABLE_WITH_LARGER_POOL, atFive.get("r4"));
        // At k = 1 only the top hit of each token is a candidate.
        var atOne = reasons(attribution.fates(ANSWERABLE, 1));
        assertEquals(Reason.RETRIEVED, atOne.get("r1"));
        assertEquals(Reason.RECOVERABLE_WITH_LARGER_POOL, atOne.get("r3"));
    }

    @Test
    void negativeAndFailedQueriesStayVisibleAndAreNeverScoredAsZeros() {
        var scores = attribution().score(List.of(ANSWERABLE, NEGATIVE, FAILING));

        assertEquals(List.of(NEGATIVE.queryId()), scores.negativeQueries());
        assertEquals(List.of(FAILING.queryId()), scores.failedQueries());
        assertTrue(scores.fates().stream().allMatch(fate -> fate.queryId().equals(ANSWERABLE.queryId())));
        assertEquals(6 * 3, scores.fates().size(), "six relevant chunks at three k");
        var b2 = scores.systems().stream().filter(score -> score.system().equals("B2") && score.k() == 5)
                .findFirst().orElseThrow();
        assertEquals(1, b2.failed(), "the failed query has no outcome");
        assertEquals(1, b2.answerable());
        assertEquals(1, b2.unanswerable());
        assertEquals(0, b2.falseEvidence(), "B2 returned nothing for the negative query");
        var b1 = scores.systems().stream().filter(score -> score.system().equals("B1") && score.k() == 5)
                .findFirst().orElseThrow();
        assertEquals(1, b1.falseEvidence(), "B1 returned a chunk for the negative query");
        assertEquals(1, b1.failed(), "a failed snapshot layer has no outcome either");
        assertTrue(scores.systems().stream().filter(score -> !score.pool().equals("full-text")
                && !score.system().equals("B2")).allMatch(score -> score.failed() == 1), "nor any replay");
    }

    @Test
    void aQueryOutsideTheObservationHasNoOutcome() {
        var unobserved = query("q:paraphrase-01", Category.PARAPHRASE, "omega", Set.of("r1"), Set.of());
        var scores = attribution().score(List.of(ANSWERABLE, unobserved));

        assertEquals(List.of(unobserved.queryId()), scores.failedQueries());
        assertTrue(scores.systems().stream().allMatch(score -> score.failed() == 1),
                "no capture and no rows is a missing outcome for every system, never an empty result");
        assertTrue(scores.fates().stream().noneMatch(fate -> fate.queryId().equals(unobserved.queryId())));
    }

    @Test
    void aSupportingOnlyQueryIsAnswerableEverywhere() {
        var supportingOnly = query("q:multi-evidence-01", Category.MULTI_EVIDENCE, "alpha beta", Set.of(),
                Set.of("r1", "r2", "r3", "r4", "r5", "r6"));
        var scores = attribution().score(List.of(supportingOnly));

        var b2 = scores.systems().stream().filter(score -> score.system().equals("B2") && score.k() == 5)
                .findFirst().orElseThrow();
        assertEquals(1, b2.answerable());
        assertEquals(5, scores.comparability().slots(), "its B2 slots are counted");
        assertEquals(1, scores.ceilings().getFirst().unionRecall().nDefined(), "and it enters the ceiling");
        assertTrue(scores.negativeQueries().isEmpty());
    }

    @Test
    void theMedianDfUsesOnlyTokensWithHits() {
        var withStopToken = new QueryCapture(ANSWERABLE.queryId(), List.of("alpha", "beta", "the"),
                appendEmptySignal(capture(ANSWERABLE.queryId(), List.of("alpha", "beta"), FULL)), Optional.empty());
        var scores = new RecallLossAttribution(new Snapshot("hash", rows(ANSWERABLE.queryId(), Layer.B2,
                List.of("r1"))), List.of(withStopToken), Map.of("alpha", 2, "beta", 9, "the", 30), Set.of("r1"))
                .score(List.of(ANSWERABLE));

        assertEquals(5.5, scores.comparability().medianDf(), "the empty-list stop token (df 30) is left out");
        assertEquals(2, scores.comparability().pairs());
    }

    private static Map<Integer, List<List<Hit>>> appendEmptySignal(QueryCapture capture) {
        var lists = new LinkedHashMap<Integer, List<List<Hit>>>();
        capture.lists().forEach((pool, perSignal) -> {
            var extended = new ArrayList<>(perSignal);
            extended.add(List.of());
            lists.put(pool, extended);
        });
        return lists;
    }

    @Test
    void displacersNameTheWinningTokenAndDistractors() {
        var scores = attribution().score(List.of(ANSWERABLE));
        var displacers = scores.displacers();

        assertEquals(List.of("x", "y", "chk:synthetic-0001#00"), displacers.stream()
                .map(RecallLossAttribution.Displacer::chunkId).toList());
        var distractor = displacers.get(2);
        assertTrue(distractor.distractor());
        assertEquals("beta", distractor.token());
        assertEquals(9, distractor.tokenDf());
        assertFalse(displacers.getFirst().distractor());
        assertEquals(5, scores.comparability().slots());
        assertEquals(3, scores.comparability().nonRelevantSlots());
        assertEquals(1, scores.comparability().distractorSlots());
        assertEquals(1, scores.comparability().aboveMedianDfSlots(), "only beta (df 9) exceeds the median 5.5");
    }

    @Test
    void theCandidateCeilingBoundsEveryMerge() {
        var scores = attribution().score(List.of(ANSWERABLE));
        var atThree = scores.ceilings().stream().filter(ceiling -> ceiling.k() == 3 && ceiling.pool() == 3)
                .findFirst().orElseThrow();
        var expanded = scores.ceilings().stream().filter(ceiling -> ceiling.k() == 3
                && ceiling.pool() == SignalHitCapture.EXPANDED_POOL).findFirst().orElseThrow();

        assertEquals(3.0 / 6, atThree.unionRecall().value().getAsDouble(), 1e-12, "r1, r2 and r3 of six");
        assertEquals(4.0 / 6, expanded.unionRecall().value().getAsDouble(), 1e-12, "plus r4");
        for (var score : scores.systems()) {
            if (score.k() == 3 && score.pool().equals("k")) {
                assertTrue(score.means().get("Recall@3").value().getAsDouble() <= atThree.unionRecall().value()
                        .getAsDouble() + 1e-12, score.system());
            }
        }
    }

    @Test
    void spearmanUsesAverageRanksAndIsUndefinedWithoutVariation() {
        assertEquals(1.0, RecallLossAttribution.spearman(new double[] {1, 2, 3}, new double[] {10, 20, 30})
                .getAsDouble(), 1e-12);
        assertEquals(-1.0, RecallLossAttribution.spearman(new double[] {1, 2, 3}, new double[] {3, 2, 1})
                .getAsDouble(), 1e-12);
        assertEquals(1.5 / Math.sqrt(3.0), RecallLossAttribution.spearman(new double[] {1, 1, 2},
                new double[] {1, 2, 3}).getAsDouble(), 1e-12, "tied x values share rank 1.5");
        assertTrue(RecallLossAttribution.spearman(new double[] {1, 1}, new double[] {1, 2}).isEmpty());
        assertTrue(RecallLossAttribution.spearman(new double[] {1}, new double[] {1}).isEmpty());
    }
}
