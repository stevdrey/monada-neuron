package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.EvidenceTrace.Arm;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Contrast;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Hit;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Row;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Snapshot;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scorer is the only reader of gold labels; it scores synthetic trace rows here. */
class EvidenceTraceMetricsTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final HtmlKnowledgeQuerySet QUERY_SET = HtmlKnowledgeQuerySet.bundled(CORPUS);
    private static final String ANSWERABLE = "q:multi-evidence-02";
    private static final String UNANSWERABLE = "q:no-answer-01";

    @Test
    void perQueryValuesFollowTheExtension() {
        var relevant = List.copyOf(query(ANSWERABLE).relevant());
        var scores = EvidenceTraceMetrics.score(QUERY_SET, CORPUS, snapshot(Map.of(
                key(Arm.MEMORY, ANSWERABLE), row(Arm.MEMORY, ANSWERABLE, List.of(relevant.get(0), "chk:forms#00"),
                        2, EvidenceDecision.SUPPORTED, false),
                key(Arm.MEMORY, UNANSWERABLE), row(Arm.MEMORY, UNANSWERABLE, List.of("chk:forms#00"), 1,
                        EvidenceDecision.SUPPORTED, false))));
        var answerable = score(scores, Arm.MEMORY, ANSWERABLE);
        assertEquals(OptionalDouble.of(2.0 / 3), answerable.admittedFraction(), "2 of 3 recorded results admitted");
        assertEquals(OptionalDouble.of(1.0), answerable.usedFraction());
        assertEquals(OptionalDouble.of(1.0), answerable.relevantEvidenceUsed());
        assertTrue(answerable.memorySupportedRelevant());
        var unanswerable = score(scores, Arm.MEMORY, UNANSWERABLE);
        assertEquals(OptionalDouble.empty(), unanswerable.relevantEvidenceUsed(), "scored over A only");
        assertFalse(unanswerable.memorySupportedRelevant());
        assertEquals(1, scores.aggregates().get(Arm.MEMORY).supportedOnUnanswerable().count(),
                "SUPPORTED on a query with no relevant chunk is counted, never hidden");
        var empty = score(scores, Arm.MEMORY, "q:exact-term-02");
        assertEquals(OptionalDouble.empty(), empty.admittedFraction(), "nothing recorded: undefined, not 0 or 1");
        assertEquals(OptionalDouble.empty(), empty.usedFraction(), "nothing admitted: undefined");
        assertEquals(OptionalDouble.of(0.0), empty.relevantEvidenceUsed());
    }

    @Test
    void anAmbiguousTraceIsUndefinedAndNeverSupport() {
        var relevant = List.copyOf(query(ANSWERABLE).relevant());
        var scores = EvidenceTraceMetrics.score(QUERY_SET, CORPUS, snapshot(Map.of(key(Arm.MEMORY, ANSWERABLE),
                row(Arm.MEMORY, ANSWERABLE, relevant, relevant.size(), EvidenceDecision.SUPPORTED, true))));
        var score = score(scores, Arm.MEMORY, ANSWERABLE);
        assertEquals(OptionalDouble.empty(), score.usedFraction());
        assertEquals(OptionalDouble.empty(), score.relevantEvidenceUsed());
        assertFalse(score.memorySupportedRelevant());
    }

    @Test
    void memoryOffAndRemovedControlsCannotPassAsMemorySupport() {
        var relevant = List.copyOf(query(ANSWERABLE).relevant());
        var clean = EvidenceTraceMetrics.score(QUERY_SET, CORPUS, snapshot(Map.of()));
        assertEquals(List.of(true, true), clean.checks().stream().map(Check::passed).toList());
        assertEquals(List.of("q:exact-term-01", "q:multi-evidence-02", "q:paraphrase-01", "q:paraphrase-04"),
                clean.removedTargets());

        var leaked = EvidenceTraceMetrics.score(QUERY_SET, CORPUS, snapshot(Map.of(
                key(Arm.MEMORY_EMPTY, ANSWERABLE), row(Arm.MEMORY_EMPTY, ANSWERABLE, relevant, relevant.size(),
                        EvidenceDecision.SUPPORTED, false),
                key(Arm.REMOVED, ANSWERABLE), row(Arm.REMOVED, ANSWERABLE, relevant, relevant.size(),
                        EvidenceDecision.SUPPORTED, false))));
        assertEquals(List.of("control.memoryOffNotSupported", "control.removedRelevantNotSupported"),
                leaked.checks().stream().map(Check::name).toList());
        assertEquals(List.of(false, false), leaked.checks().stream().map(Check::passed).toList());
    }

    @Test
    void contrastIsCountedPerPopulation() {
        var scores = EvidenceTraceMetrics.score(QUERY_SET, CORPUS, snapshot(Map.of()));
        assertEquals(22, scores.contrastAnswerable().of());
        assertEquals(10, scores.contrastUnanswerable().of());
        assertEquals(0, scores.contrastAnswerable().count());
    }

    @Test
    void aSnapshotOfAnotherQuerySetOrWithAMissingRowIsRejected() {
        var rows = new ArrayList<>(snapshot(Map.of()).rows());
        assertThrows(IllegalArgumentException.class, () -> EvidenceTraceMetrics.score(QUERY_SET, CORPUS,
                new Snapshot("other", rows)));
        rows.removeLast();
        assertThrows(IllegalArgumentException.class, () -> EvidenceTraceMetrics.score(QUERY_SET, CORPUS,
                new Snapshot(QUERY_SET.queryHash(), rows)));
    }

    @Test
    void aSyntacticallyCompleteButInconsistentSnapshotIsRejected() {
        var rows = snapshot(Map.of()).rows();
        var duplicate = new ArrayList<>(rows);
        duplicate.set(1, rows.get(0));
        var foreign = new ArrayList<>(rows);
        var first = rows.get(0);
        foreign.set(0, new Row(first.arm(), first.queryId(), "q:unknown-01", first.inputSignals(),
                first.requestMatchesCodec(), first.recordedStatus(), first.recorded(), first.memoryStatus(),
                first.admitted(), first.usedBySignal(), first.attached(), first.selected(), first.traceAmbiguous(),
                first.stages(), first.termination(), first.signalBudgetExhausted(), first.outputSignals(),
                first.decision(), first.contrast(), first.recalledEqualsB2()));
        var noContrast = new ArrayList<>(rows);
        noContrast.set(0, withContrastAndB2(first, Optional.empty(), first.recalledEqualsB2()));
        var b2OnEmptyArm = new ArrayList<>(rows);
        var emptyIndex = QUERY_SET.queries().size();
        var empty = rows.get(emptyIndex);
        assertEquals(Arm.MEMORY_EMPTY, empty.arm());
        b2OnEmptyArm.set(emptyIndex, withContrastAndB2(empty, Optional.empty(), Optional.of(true)));
        var extra = new ArrayList<>(rows);
        extra.add(rows.getLast());
        var memoryIndex = 1;
        var unswapped = new ArrayList<>(rows);
        unswapped.set(memoryIndex, withInputAndStatus(rows.get(memoryIndex), rows.get(0).queryId(),
                rows.get(memoryIndex).recordedStatus()));
        var swappedIndex = 2 * QUERY_SET.queries().size();
        var swapped = rows.get(swappedIndex);
        assertEquals(Arm.SWAPPED, swapped.arm());
        var swappedOwnText = new ArrayList<>(rows);
        swappedOwnText.set(swappedIndex, withInputAndStatus(swapped, swapped.queryId(), swapped.recordedStatus()));
        var contrastOnSwapped = new ArrayList<>(rows);
        contrastOnSwapped.set(swappedIndex, withContrastAndB2(swapped,
                Optional.of(new Contrast(false, false, false, "none", 1, 1, false)), swapped.recalledEqualsB2()));
        for (var invalid : List.of(duplicate, foreign, noContrast, b2OnEmptyArm, extra, unswapped, swappedOwnText,
                contrastOnSwapped)) {
            assertThrows(IllegalArgumentException.class, () -> EvidenceTraceMetrics.score(QUERY_SET, CORPUS,
                    new Snapshot(QUERY_SET.queryHash(), invalid)));
        }
    }

    @Test
    void aFailedRecallLeavesOnlyThatRowUnscored() {
        var relevant = List.copyOf(query(ANSWERABLE).relevant());
        var failed = withInputAndStatus(row(Arm.MEMORY, ANSWERABLE, relevant, relevant.size(),
                EvidenceDecision.SUPPORTED, false), ANSWERABLE, "FAILED");
        var tight = withInputAndStatus(row(Arm.TIGHT_BUDGET, ANSWERABLE, List.of(), 0, EvidenceDecision.INCOMPLETE,
                false), ANSWERABLE, "PARTIAL");
        var scores = EvidenceTraceMetrics.score(QUERY_SET, CORPUS, snapshot(Map.of(key(Arm.MEMORY, ANSWERABLE),
                failed, key(Arm.TIGHT_BUDGET, ANSWERABLE), tight)));
        var score = score(scores, Arm.MEMORY, ANSWERABLE);
        assertFalse(score.scored());
        assertEquals(OptionalDouble.empty(), score.relevantEvidenceUsed());
        assertFalse(score.memorySupportedRelevant(), "an operational failure is never support");
        var memory = scores.aggregates().get(Arm.MEMORY);
        assertEquals(1, memory.unscored());
        assertEquals(21, memory.memorySupportedRelevant().of(), "excluded from the answerable population");
        assertEquals(21, memory.relevantEvidenceUsed().nDefined() + memory.relevantEvidenceUsed().nUndefined());
        assertEquals(31, memory.decisions().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(21, scores.contrastAnswerable().of(), "excluded from the contrast too");
        assertEquals(1, scores.aggregates().get(Arm.TIGHT_BUDGET).unscored(),
                "the tight budget truncates admission, not the port call");
        assertTrue(score(scores, Arm.MEMORY, "q:exact-term-02").scored(), "the other rows are still scored");
    }

    private static Row withInputAndStatus(Row row, String inputQueryId, String recordedStatus) {
        return new Row(row.arm(), row.queryId(), inputQueryId, row.inputSignals(), row.requestMatchesCodec(),
                recordedStatus, row.recorded(), row.memoryStatus(), row.admitted(), row.usedBySignal(),
                row.attached(), row.selected(), row.traceAmbiguous(), row.stages(), row.termination(),
                row.signalBudgetExhausted(), row.outputSignals(), row.decision(), row.contrast(),
                row.recalledEqualsB2());
    }

    /** The query whose text {@code arm} sends for slot {@code queryId}: its own, or the swapped one in set order. */
    static String input(Arm arm, String queryId) {
        var queries = QUERY_SET.queries();
        if (arm != Arm.SWAPPED) {
            return queryId;
        }
        for (var index = 0; index < queries.size(); index++) {
            if (queries.get(index).queryId().equals(queryId)) {
                return queries.get(HtmlKnowledgeCycleExperiment.swapIndex(index, queries.size())).queryId();
            }
        }
        throw new IllegalArgumentException(queryId);
    }

    private static Row withContrastAndB2(Row row, Optional<Contrast> contrast, Optional<Boolean> recalledEqualsB2) {
        return new Row(row.arm(), row.queryId(), row.inputQueryId(), row.inputSignals(), row.requestMatchesCodec(),
                row.recordedStatus(), row.recorded(), row.memoryStatus(), row.admitted(), row.usedBySignal(),
                row.attached(), row.selected(), row.traceAmbiguous(), row.stages(), row.termination(),
                row.signalBudgetExhausted(), row.outputSignals(), row.decision(), contrast, recalledEqualsB2);
    }

    /** Every arm and query with nothing recalled, except the given rows. */
    private static Snapshot snapshot(Map<String, Row> overrides) {
        var rows = new ArrayList<Row>();
        for (var arm : Arm.values()) {
            for (var query : QUERY_SET.queries()) {
                rows.add(overrides.getOrDefault(key(arm, query.queryId()),
                        row(arm, query.queryId(), List.of(), 0, EvidenceDecision.UNSUPPORTED, false)));
            }
        }
        return new Snapshot(QUERY_SET.queryHash(), rows);
    }

    /** A row whose {@code admitted} chunks are all carried, attached and selected, with one extra recorded result. */
    private static Row row(Arm arm, String queryId, List<String> admitted, int recordedAdmitted,
            EvidenceDecision decision, boolean ambiguous) {
        var recorded = new ArrayList<Hit>();
        admitted.subList(0, recordedAdmitted).forEach(chunk -> recorded.add(new Hit(chunk, 0.5)));
        if (!admitted.isEmpty()) {
            recorded.add(new Hit("chk:links#04", 0.1));
        }
        return new Row(arm, queryId, input(arm, queryId), 1, true, arm.expectedRecordedStatus(), recorded,
                "COMPLETE", admitted, admitted, admitted,
                admitted, ambiguous, List.of("MEMORY_RECALL", "REASONING", "EVALUATION"), "COMPLETED", false, 1,
                decision, arm == Arm.MEMORY ? Optional.of(new Contrast(false, false, false, "none", 1, 1, false))
                        : Optional.empty(), arm.recallsThroughAdapter() ? Optional.of(true) : Optional.empty());
    }

    private static String key(Arm arm, String queryId) {
        return arm + " " + queryId;
    }

    private static JudgedQuery query(String queryId) {
        return QUERY_SET.queries().stream().filter(query -> query.queryId().equals(queryId)).findFirst()
                .orElseThrow();
    }

    private static EvidenceTraceMetrics.QueryScore score(EvidenceTraceMetrics.Scores scores, Arm arm,
            String queryId) {
        return scores.byArm().get(arm).stream().filter(score -> score.query().queryId().equals(queryId)).findFirst()
                .orElseThrow();
    }
}
