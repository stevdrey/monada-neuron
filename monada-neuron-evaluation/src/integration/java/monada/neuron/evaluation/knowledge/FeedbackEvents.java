package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.SequencedSet;

/**
 * The labels-free feedback plan of the relevance-feedback experiment (Issue #110): what a treatment arm records through
 * {@code MonadaMemory.feedback(query, atomId, signal)}, as query ids, exact query texts, chunk ids and signals. It is
 * what the orchestrator hands to the feedback phase JVM, so no gold label reaches that process; the opaque reference
 * of each chunk is resolved there, through each arm's own import manifest.
 *
 * <p>Bounds: at most {@value #MAX_EVENTS_PER_QUERY} events per query (one per returned chunk of its {@code Ret_5}), at
 * most {@value #MAX_EVENTS} in total, and at most one event per {@code (queryId, chunkId)}. Events are kept in canonical
 * order, {@code (queryId, chunkId)} in code point order, which is also the order they are applied in.
 *
 * @param events the events in canonical order
 */
record FeedbackEvents(List<Event> events) {

    /** Version string of {@link #feedbackHash()}, as fixed by the protocol's extension {@code feedback-v1}. */
    static final String VERSION = "html-kf-v1";

    /** Largest number of events per query: one per result of a limit-5 list. */
    static final int MAX_EVENTS_PER_QUERY = HtmlKnowledgeRecall.MAX_LIMIT;

    /** Largest number of events of one run. */
    static final int MAX_EVENTS = 80;

    private static final String HEADER = "queryId\ttext\tchunkId\tsignal";

    private static final Comparator<Event> CANONICAL = Comparator.comparing(Event::queryId)
            .thenComparing(Event::chunkId);

    /**
     * One planned event.
     *
     * @param queryId the calibration query
     * @param text its exact text, the query passed to {@code feedback}
     * @param chunkId the chunk whose atom receives the signal
     * @param signal {@code POSITIVE} for a relevant returned chunk, {@code NEGATIVE} for a non-relevant one
     */
    record Event(String queryId, String text, String chunkId, FeedbackSignal signal) {

        Event {
            Objects.requireNonNull(queryId, "queryId must not be null");
            Objects.requireNonNull(text, "text must not be null");
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
            if (text.isBlank()) {
                throw new IllegalArgumentException("event of " + queryId + " has a blank query text");
            }
        }
    }

    /**
     * Sorts the events canonically and enforces the bounds.
     *
     * @throws IllegalArgumentException on a duplicate {@code (queryId, chunkId)}, more than
     *     {@value #MAX_EVENTS_PER_QUERY} events for one query, or more than {@value #MAX_EVENTS} events
     */
    FeedbackEvents {
        events = events.stream().sorted(CANONICAL).toList();
        if (events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException(events.size() + " events exceed the bound of " + MAX_EVENTS);
        }
        var seen = new HashSet<String>();
        for (var event : events) {
            if (!seen.add(event.queryId() + "\u0000" + event.chunkId())) {
                throw new IllegalArgumentException("duplicate event " + event.queryId() + " " + event.chunkId());
            }
        }
        for (var queryId : queryIds(events)) {
            var count = events.stream().filter(event -> event.queryId().equals(queryId)).count();
            if (count > MAX_EVENTS_PER_QUERY) {
                throw new IllegalArgumentException(queryId + " has " + count + " events, more than "
                        + MAX_EVENTS_PER_QUERY);
            }
        }
    }

    /** Returns the ids of the queries that received an event, in canonical order. */
    SequencedSet<String> queryIds() {
        return queryIds(events);
    }

    private static SequencedSet<String> queryIds(List<Event> events) {
        var ids = new LinkedHashSet<String>();
        events.forEach(event -> ids.add(event.queryId()));
        return ids;
    }

    /** Returns the distinct query texts that received an event, as workload entries in canonical order. */
    List<QueryText> queries() {
        var queries = new ArrayList<QueryText>();
        var seen = new HashSet<String>();
        events.forEach(event -> {
            if (seen.add(event.queryId())) {
                queries.add(new QueryText(event.queryId(), event.text()));
            }
        });
        return queries;
    }

    /** Returns the number of events with {@code signal}. */
    long count(FeedbackSignal signal) {
        return events.stream().filter(event -> event.signal() == signal).count();
    }

    /**
     * Returns {@code feedbackHash}: the {@link CanonicalRecordDigest} of {@value #VERSION}, the event count, then each
     * event in canonical order as query id, query key (the exact query text), chunk id, signal name and an absent
     * delta, since the three-argument {@code feedback} overload is used.
     */
    String feedbackHash() {
        var digest = new CanonicalRecordDigest().field(VERSION).field(events.size());
        events.forEach(event -> digest.field(event.queryId()).field(event.text()).field(event.chunkId())
                .field(event.signal().name()).absent());
        return digest.hex();
    }

    /** Renders the plan with escaped texts, terminated by {@code end\t<count>} so truncation is detected. */
    String toTsv() {
        var tsv = new StringBuilder("# feedbackHash ").append(feedbackHash()).append('\n').append(HEADER).append('\n');
        events.forEach(event -> tsv.append(event.queryId()).append('\t').append(QueryText.escape(event.text()))
                .append('\t').append(event.chunkId()).append('\t').append(event.signal().name()).append('\n'));
        return tsv.append("end\t").append(events.size()).append('\n').toString();
    }

    /**
     * Parses {@link #toTsv()} output strictly; the recorded hash must match the parsed events.
     *
     * @throws IllegalArgumentException when the plan is malformed, truncated or does not match its hash
     */
    static FeedbackEvents parseTsv(String tsv) {
        var lines = tsv.lines().toList();
        if (lines.size() < 3 || !lines.getFirst().startsWith("# feedbackHash ") || !lines.get(1).equals(HEADER)) {
            throw new IllegalArgumentException("not a feedback plan");
        }
        if (!lines.getLast().equals("end\t" + (lines.size() - 3))) {
            throw new IllegalArgumentException("feedback plan is truncated: last line " + lines.getLast());
        }
        var events = new ArrayList<Event>();
        for (var index = 2; index < lines.size() - 1; index++) {
            var cells = lines.get(index).split("\t", -1);
            if (cells.length != 4) {
                throw new IllegalArgumentException("malformed feedback plan row " + (index + 1));
            }
            events.add(new Event(cells[0], QueryText.unescape("plan:" + (index + 1), cells[1]), cells[2],
                    FeedbackSignal.valueOf(cells[3])));
        }
        var plan = new FeedbackEvents(events);
        if (!lines.getFirst().equals("# feedbackHash " + plan.feedbackHash())) {
            throw new IllegalArgumentException("feedback plan does not match its recorded feedbackHash");
        }
        return plan;
    }
}
