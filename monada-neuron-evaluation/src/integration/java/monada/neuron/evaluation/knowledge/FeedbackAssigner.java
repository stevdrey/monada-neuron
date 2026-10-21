package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Derives the feedback plan of the relevance-feedback experiment (Issue #110, protocol extension {@code feedback-v1},
 * "Design" item 3). Besides {@link RecallMetrics}, it is the only component that reads gold labels, and it reads only
 * the labels of calibration queries: it rejects any query on the held-out side, so held-out labels and results can
 * never choose an event.
 *
 * <p>For each calibration query with a non-empty relevant set and a non-blank text, its B1 {@code Ret_5} in the
 * pre-feedback snapshot of a treatment arm is inspected once: every relevant returned chunk gets {@code POSITIVE} and
 * every non-relevant returned chunk gets {@code NEGATIVE}. Relevant chunks that were not returned get nothing, so at
 * most five events are recorded per query.
 */
final class FeedbackAssigner {

    private FeedbackAssigner() {
    }

    /**
     * Returns the plan for {@code calibration}.
     *
     * @param calibration the calibration queries with their labels
     * @param heldOutIds every held-out query id; none may appear in {@code calibration}
     * @param preFeedback the B1 snapshot of a treatment arm, recorded before any event
     * @throws IllegalArgumentException when a calibration query is held out
     * @throws IllegalStateException when a calibration query's pre-feedback B1 list failed or holds an unmapped atom
     */
    static FeedbackEvents derive(List<JudgedQuery> calibration, Set<String> heldOutIds, Snapshot preFeedback) {
        Objects.requireNonNull(calibration, "calibration must not be null");
        Objects.requireNonNull(heldOutIds, "heldOutIds must not be null");
        Objects.requireNonNull(preFeedback, "preFeedback must not be null");
        var events = new ArrayList<FeedbackEvents.Event>();
        for (var query : calibration.stream().sorted(Comparator.comparing(JudgedQuery::queryId)).toList()) {
            if (heldOutIds.contains(query.queryId())) {
                throw new IllegalArgumentException(query.queryId() + " is held out; its labels cannot choose events");
            }
            if (query.relevant().isEmpty() || query.text().isBlank()) {
                continue;
            }
            var relevant = query.relevant();
            for (var row : preFeedback.rows(query.queryId(), Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT)) {
                if (row.chunkId().equals(HtmlKnowledgeRecall.FAILED)
                        || row.chunkId().equals(HtmlKnowledgeRecall.UNMAPPED)) {
                    throw new IllegalStateException(query.queryId() + " has no usable pre-feedback B1 list ("
                            + row.chunkId() + ")");
                }
                events.add(new FeedbackEvents.Event(query.queryId(), query.text(), row.chunkId(),
                        relevant.contains(row.chunkId()) ? FeedbackSignal.POSITIVE : FeedbackSignal.NEGATIVE));
            }
        }
        return new FeedbackEvents(events);
    }
}
