package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import com.monada.storage.feedback.FeedbackStore;
import com.monada.storage.feedback.FileFeedbackStore;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The feedback events a Store root has persisted, read back at the storage level (Issue #110). {@code MonadaMemory} has
 * no public read of stored feedback, so this reads the root's append-only feedback log through the Store's public
 * {@link FileFeedbackStore}, after checking that the log exists: that constructor creates a missing log, and a read must
 * never write. Each event's atom id is mapped to its chunk id through the root's own import manifest and replaced by
 * that chunk id and the adapter's opaque reference, so Store-owned atom ids never leave the phase JVM; the event's
 * creation time is dropped, so two reads of an unchanged log render identically.
 *
 * @param events the stored events in log (append) order
 * @param logSha256 SHA-256 of the raw log bytes
 * @param logBytes size of the raw log
 */
record FeedbackEventLog(List<StoredEvent> events, String logSha256, long logBytes) {

    /** Version string of {@link #eventLogHash()}. */
    static final String VERSION = "html-kf-log-v1";

    private static final String HEADER = "query\tqueryKey\tchunkId\topaqueReference\tsignal\tdelta";

    /**
     * One stored event.
     *
     * @param query the query text as stored
     * @param queryKey the key the Store computed when it was written
     * @param chunkId the chunk its atom maps to, or {@link HtmlKnowledgeRecall#UNMAPPED}
     * @param opaqueReference the adapter reference of that atom, or {@link HtmlKnowledgeRecall#UNMAPPED}
     * @param signal the signal
     * @param delta the stored score adjustment
     */
    record StoredEvent(String query, String queryKey, String chunkId, String opaqueReference, FeedbackSignal signal,
            double delta) {

        StoredEvent {
            Objects.requireNonNull(query, "query must not be null");
            Objects.requireNonNull(queryKey, "queryKey must not be null");
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(opaqueReference, "opaqueReference must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
        }
    }

    FeedbackEventLog {
        events = List.copyOf(events);
        Objects.requireNonNull(logSha256, "logSha256 must not be null");
    }

    /** Returns the log file of {@code root}: the Store's default feedback segment, which every new root uses. */
    static Path logFile(Path root) {
        return root.resolve(FeedbackStore.DEFAULT_SEGMENT);
    }

    /**
     * Reads the persisted events of {@code root}.
     *
     * @throws IngestionInputException when the root has no feedback log
     * @throws UncheckedIOException when the log cannot be read
     */
    static FeedbackEventLog read(Path root, IngestionManifest manifest) {
        var file = logFile(root);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IngestionInputException("Store root " + root + " has no feedback log at "
                    + FeedbackStore.DEFAULT_SEGMENT);
        }
        try {
            var bytes = Files.readAllBytes(file);
            var events = new ArrayList<StoredEvent>();
            for (var event : new FileFeedbackStore(root).findAll()) {
                var chunkId = manifest.chunkIdForAtom(event.atomId());
                var reference = chunkId.flatMap(manifest::entryForChunk).map(IngestionManifest.Entry::opaqueReference);
                events.add(new StoredEvent(event.query(), event.queryKey(),
                        chunkId.orElse(HtmlKnowledgeRecall.UNMAPPED), reference.orElse(HtmlKnowledgeRecall.UNMAPPED),
                        event.signal(), event.delta()));
            }
            return new FeedbackEventLog(events, CorpusDigests.sha256(bytes), bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the feedback log of " + root, e);
        }
    }

    /**
     * Returns the {@link CanonicalRecordDigest} of {@value #VERSION}, the event count, then each event in log order as
     * query, query key, chunk id, signal name and the hexadecimal delta; creation times and atom ids are excluded.
     */
    String eventLogHash() {
        var digest = new CanonicalRecordDigest().field(VERSION).field(events.size());
        events.forEach(event -> digest.field(event.query()).field(event.queryKey()).field(event.chunkId())
                .field(event.signal().name()).field(Double.toHexString(event.delta())));
        return digest.hex();
    }

    /** Renders the log with escaped texts and hexadecimal deltas, terminated by {@code end\t<count>}. */
    String toTsv() {
        var tsv = new StringBuilder("# log ").append(logSha256).append(' ').append(logBytes).append('\n')
                .append(HEADER).append('\n');
        for (var event : events) {
            tsv.append(QueryText.escape(event.query())).append('\t').append(QueryText.escape(event.queryKey()))
                    .append('\t').append(event.chunkId()).append('\t').append(event.opaqueReference()).append('\t')
                    .append(event.signal().name()).append('\t').append(Double.toHexString(event.delta())).append('\n');
        }
        return tsv.append("end\t").append(events.size()).append('\n').toString();
    }

    /**
     * Parses {@link #toTsv()} output strictly.
     *
     * @throws IllegalArgumentException when the text is malformed or truncated
     */
    static FeedbackEventLog parseTsv(String tsv) {
        var lines = tsv.lines().toList();
        if (lines.size() < 3 || !lines.getFirst().startsWith("# log ") || !lines.get(1).equals(HEADER)) {
            throw new IllegalArgumentException("not a feedback log rendering");
        }
        if (!lines.getLast().equals("end\t" + (lines.size() - 3))) {
            throw new IllegalArgumentException("feedback log rendering is truncated: last line " + lines.getLast());
        }
        var head = lines.getFirst().substring("# log ".length()).split(" ", -1);
        if (head.length != 2) {
            throw new IllegalArgumentException("malformed feedback log header");
        }
        var events = new ArrayList<StoredEvent>();
        for (var index = 2; index < lines.size() - 1; index++) {
            var cells = lines.get(index).split("\t", -1);
            if (cells.length != 6) {
                throw new IllegalArgumentException("malformed feedback log row " + (index + 1));
            }
            var where = "log:" + (index + 1);
            events.add(new StoredEvent(QueryText.unescape(where, cells[0]), QueryText.unescape(where, cells[1]),
                    cells[2], cells[3], FeedbackSignal.valueOf(cells[4]), Double.parseDouble(cells[5])));
        }
        return new FeedbackEventLog(events, head[0], Long.parseLong(head[1]));
    }
}
