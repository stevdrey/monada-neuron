package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import monada.neuron.evaluation.knowledge.FeedbackEvents.Event;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackAssignerTest {

    private static final JudgedQuery ANSWERABLE = new JudgedQuery("q:exact-term-01", Category.EXACT_TERM,
            "What is the HTML a element for?", Set.of("chk:links#00"), Set.of("chk:links#01"), List.of(), List.of(),
            Set.of());

    @Test
    void relevantReturnedChunksArePositiveAndOtherReturnedChunksNegative() {
        var plan = FeedbackAssigner.derive(List.of(ANSWERABLE), Set.of(),
                snapshot("q:exact-term-01", "chk:forms#00", "chk:links#00", "chk:lists#00"));
        assertEquals(List.of(
                new Event("q:exact-term-01", ANSWERABLE.text(), "chk:forms#00", FeedbackSignal.NEGATIVE),
                new Event("q:exact-term-01", ANSWERABLE.text(), "chk:links#00", FeedbackSignal.POSITIVE),
                new Event("q:exact-term-01", ANSWERABLE.text(), "chk:lists#00", FeedbackSignal.NEGATIVE)),
                plan.events(), "chk:links#01 is relevant but was not returned, so it gets no event");
    }

    @Test
    void queriesWithoutRelevantChunksOrWithBlankTextGetNoEvent() {
        var noAnswer = new JudgedQuery("q:no-answer-01", Category.NO_ANSWER, "How do I build a table?", Set.of(),
                Set.of(), List.of(), List.of(), Set.of());
        var blank = new JudgedQuery("q:blank-01", Category.BLANK, "", Set.of("chk:forms#00"), Set.of(), List.of(),
                List.of(), Set.of());
        var snapshot = new Snapshot("hash", List.of(row("q:no-answer-01", 1, "chk:forms#00"),
                row("q:blank-01", 1, "chk:forms#00")));
        assertTrue(FeedbackAssigner.derive(List.of(noAnswer, blank), Set.of(), snapshot).events().isEmpty());
    }

    @Test
    void aHeldOutQueryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FeedbackAssigner.derive(List.of(ANSWERABLE),
                Set.of("q:exact-term-01"), snapshot("q:exact-term-01", "chk:links#00")));
    }

    @Test
    void aFailedOrUnmappedPreFeedbackListAbortsThePlan() {
        assertThrows(IllegalStateException.class, () -> FeedbackAssigner.derive(List.of(ANSWERABLE), Set.of(),
                snapshot("q:exact-term-01", HtmlKnowledgeRecall.FAILED)));
        assertThrows(IllegalStateException.class, () -> FeedbackAssigner.derive(List.of(ANSWERABLE), Set.of(),
                snapshot("q:exact-term-01", HtmlKnowledgeRecall.UNMAPPED)));
    }

    @Test
    void heldOutLabelsCannotChangeThePlan() {
        var queries = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled()).queries();
        var split = FeedbackQuerySplit.of(queries);
        var heldOutIds = Set.copyOf(split.heldOut().stream().map(JudgedQuery::queryId).toList());
        var relabeled = queries.stream().map(query -> heldOutIds.contains(query.queryId())
                ? new JudgedQuery(query.queryId(), query.category(), query.text(), Set.of("chk:forms#00"), Set.of(),
                        List.of(), List.of(), Set.of())
                : query).toList();
        var relabeledSplit = FeedbackQuerySplit.of(relabeled);
        var rows = new ArrayList<Row>();
        for (var query : queries) {
            rows.add(row(query.queryId(), 1, "chk:forms#00"));
            rows.add(row(query.queryId(), 2, "chk:links#00"));
        }
        var snapshot = new Snapshot("hash", rows);
        var plan = FeedbackAssigner.derive(split.calibration(), heldOutIds, snapshot);
        var relabeledPlan = FeedbackAssigner.derive(relabeledSplit.calibration(), heldOutIds, snapshot);
        assertEquals(plan.feedbackHash(), relabeledPlan.feedbackHash());
        assertTrue(plan.queryIds().stream().noneMatch(heldOutIds::contains));
    }

    @Test
    void eventsAreBoundedUniqueAndCanonical() {
        var duplicate = new Event("q:a", "text", "chk:a#00", FeedbackSignal.POSITIVE);
        assertThrows(IllegalArgumentException.class, () -> new FeedbackEvents(List.of(duplicate, duplicate)));
        var six = IntStream.range(0, 6).mapToObj(i -> new Event("q:a", "text", "chk:a#0" + i,
                FeedbackSignal.NEGATIVE)).toList();
        assertThrows(IllegalArgumentException.class, () -> new FeedbackEvents(six));
        var tooMany = IntStream.range(0, FeedbackEvents.MAX_EVENTS + 1).mapToObj(i -> new Event("q:" + i, "text",
                "chk:a#00", FeedbackSignal.NEGATIVE)).toList();
        assertThrows(IllegalArgumentException.class, () -> new FeedbackEvents(tooMany));
        var plan = new FeedbackEvents(List.of(new Event("q:b", "b", "chk:a#00", FeedbackSignal.POSITIVE),
                new Event("q:a", "a\tx", "chk:b#00", FeedbackSignal.NEGATIVE),
                new Event("q:a", "a\tx", "chk:a#00", FeedbackSignal.POSITIVE)));
        assertEquals(List.of("chk:a#00", "chk:b#00", "chk:a#00"), plan.events().stream().map(Event::chunkId).toList());
        assertEquals(plan, FeedbackEvents.parseTsv(plan.toTsv()));
    }

    @Test
    void aPlanFileThatIsTruncatedOrDoesNotMatchItsHashIsRejected() {
        var plan = new FeedbackEvents(List.of(new Event("q:a", "a", "chk:a#00", FeedbackSignal.POSITIVE)));
        var tsv = plan.toTsv();
        assertThrows(IllegalArgumentException.class, () -> FeedbackEvents.parseTsv(tsv.substring(0,
                tsv.lastIndexOf("end"))));
        assertThrows(IllegalArgumentException.class, () -> FeedbackEvents.parseTsv(tsv.replace("POSITIVE",
                "NEGATIVE")));
    }

    private static Snapshot snapshot(String queryId, String... chunks) {
        var rows = new ArrayList<Row>();
        for (var i = 0; i < chunks.length; i++) {
            rows.add(row(queryId, chunks[i].equals(HtmlKnowledgeRecall.FAILED) ? 0 : i + 1, chunks[i]));
        }
        return new Snapshot("hash", rows);
    }

    private static Row row(String queryId, int rank, String chunkId) {
        return new Row(queryId, Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT, rank, chunkId, 1.0 / (rank + 1));
    }
}
