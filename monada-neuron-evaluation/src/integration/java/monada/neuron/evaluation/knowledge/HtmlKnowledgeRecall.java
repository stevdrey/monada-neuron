package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.ResonanceResult;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.resonance.adapter.HashedSignalDecoder;
import monada.neuron.resonance.adapter.OpaqueReference;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterConfig;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The recall side of the judged recall experiment (Issue #107): runs every query text through B0, B1, B2 and the B2c
 * control over one persisted Store root and records what each returned, mapped to chunk ids through the import
 * manifest. It never sees gold labels: its input is {@link QueryText}, query id and text only, and its output is a
 * {@link Snapshot} that the scorer ({@link RecallMetrics}) judges afterwards.
 *
 * <ul>
 *   <li><b>B0</b>: {@link LexicalOverlapBaseline} over the corpus chunks, limit {@value #MAX_LIMIT}.</li>
 *   <li><b>B1</b>: {@code MonadaMemory.resonate(text).topK(limit).threshold(t)}, limits 1, 3 and 5; the returned
 *       content must hash to the mapped chunk's {@code sha256} (source fidelity).</li>
 *   <li><b>B2</b>: the production {@link ResonanceStoreMemoryAdapter} with {@link TokenPackingSignalCodec} as query
 *       encoder and the production {@link HashedSignalDecoder}, limits 1, 3 and 5; it must answer
 *       {@code COMPLETE} and every reference must map through the manifest (reference fidelity).</li>
 *   <li><b>B2c</b>: the codec-direct control. The same codec emits the query texts, each goes directly to the Store
 *       with limit 5 and {@code t}, and the adapter's merge rule is applied here; B2 must equal B2c exactly.</li>
 * </ul>
 *
 * <p>Limits 1 and 3 must return the prefix of the limit-5 list, except inside a score tie at the cutoff. Every
 * operational failure is a failed check and leaves the query without rows for that layer, so it can never be scored
 * as an abstention.
 */
final class HtmlKnowledgeRecall {

    /** Largest evaluated limit; every system is scored on the prefixes of its limit-5 list. */
    static final int MAX_LIMIT = 5;

    /** Limits queried for the prefix-stability check, in addition to {@link #MAX_LIMIT}. */
    static final List<Integer> PREFIX_LIMITS = List.of(1, 3);

    /** Threshold {@code t} passed explicitly to B1, B2 and B2c; the Store's {@code MonadaQuery} default. */
    static final double THRESHOLD = 0.0;

    /** Chunk id recorded for a result that maps to no manifest entry. */
    static final String UNMAPPED = "<unmapped>";

    /**
     * Chunk id of the single rank-0 marker row recorded when a layer's limit-5 call failed: the scorer gives that query
     * no outcome for that layer, so a failure is never scored as an empty result.
     */
    static final String FAILED = "<failed>";

    private static final int MAX_DETAILS = 5;

    /** The systems under test and the control. */
    enum Layer {
        B0("B0"),
        B1("B1"),
        B2("B2"),
        B2C("B2c");

        private final String label;

        Layer(String label) {
            this.label = label;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }

        /** Parses a report label. */
        static Layer ofLabel(String label) {
            return Arrays.stream(values()).filter(layer -> layer.label.equals(label)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown layer " + label));
        }
    }

    /** What a recall path may know about a query: its id and its text, never its labels. */
    record QueryText(String queryId, String text) {

        QueryText {
            Objects.requireNonNull(queryId, "queryId must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }

        /** Returns the id and text of every query of {@code querySet}, in run order. */
        static List<QueryText> of(HtmlKnowledgeQuerySet querySet) {
            return querySet.queries().stream().map(query -> new QueryText(query.queryId(), query.text())).toList();
        }
    }

    /**
     * One returned result.
     *
     * @param queryId the query
     * @param layer the system that returned it
     * @param limit the limit it was queried with
     * @param rank 1-based position in the returned order
     * @param chunkId the chunk mapped through the manifest, or {@link #UNMAPPED}
     * @param score the score as returned (B0: the token overlap count)
     */
    record Row(String queryId, Layer layer, int limit, int rank, String chunkId, double score) {
    }

    /**
     * Every returned result of one observation, in query run order, then layer, limit and rank.
     *
     * @param queryHash the {@code queryHash} of the query set that was run
     * @param rows the results
     */
    record Snapshot(String queryHash, List<Row> rows) {

        private static final String HEADER = "queryId\tlayer\tlimit\trank\tchunkId\tscore";

        Snapshot {
            Objects.requireNonNull(queryHash, "queryHash must not be null");
            rows = List.copyOf(rows);
        }

        /** Returns the results of {@code queryId} from {@code layer} at {@code limit}, in rank order. */
        List<Row> rows(String queryId, Layer layer, int limit) {
            return rows.stream().filter(row -> row.queryId().equals(queryId) && row.layer() == layer
                    && row.limit() == limit).toList();
        }

        /** Renders the snapshot as TSV with bit-exact hexadecimal scores. */
        String toTsv() {
            var sb = new StringBuilder("# queryHash ").append(queryHash).append('\n').append(HEADER).append('\n');
            for (var row : rows) {
                sb.append(row.queryId()).append('\t').append(row.layer().label()).append('\t').append(row.limit())
                        .append('\t').append(row.rank()).append('\t').append(row.chunkId()).append('\t')
                        .append(Double.toHexString(row.score())).append('\n');
            }
            return sb.toString();
        }

        /** Parses {@link #toTsv} output strictly. */
        static Snapshot parseTsv(String tsv) {
            var lines = tsv.lines().toList();
            if (lines.size() < 2 || !lines.get(0).startsWith("# queryHash ") || !lines.get(1).equals(HEADER)) {
                throw new IllegalArgumentException("not a recall snapshot");
            }
            var rows = new ArrayList<Row>();
            for (var line : lines.subList(2, lines.size())) {
                var cells = line.split("\t", -1);
                if (cells.length != 6) {
                    throw new IllegalArgumentException("malformed recall snapshot row: " + line);
                }
                rows.add(new Row(cells[0], Layer.ofLabel(cells[1]), Integer.parseInt(cells[2]),
                        Integer.parseInt(cells[3]), cells[4], Double.parseDouble(cells[5])));
            }
            return new Snapshot(lines.get(0).substring("# queryHash ".length()), rows);
        }
    }

    /**
     * One observation of a root.
     *
     * @param snapshot every returned result
     * @param checks semantic verdicts, in a fixed order
     * @param metadata exploratory timings
     */
    record Observation(Snapshot snapshot, List<Check> checks, Map<String, String> metadata) {

        Observation {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }
    }

    private final HtmlKnowledgeCorpus corpus;
    private final LexicalOverlapBaseline lexical;
    private final TokenPackingSignalCodec codec = new TokenPackingSignalCodec();
    private final OpaqueReference opaqueReference = new OpaqueReference();

    /** Creates the recall side for {@code corpus}. */
    HtmlKnowledgeRecall(HtmlKnowledgeCorpus corpus) {
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.lexical = new LexicalOverlapBaseline(corpus);
    }

    /** Returns the adapter configuration of B2: the L2 codec, the production decoder, default Store options. */
    ResonanceStoreAdapterConfig adapterConfig() {
        return new ResonanceStoreAdapterConfig(codec, new HashedSignalDecoder(), MonadaMemoryOptions.defaults(),
                THRESHOLD);
    }

    /**
     * Runs every query against the existing Store at {@code root}, through one {@code MonadaMemory} handle (B1, B2c)
     * and one separately opened adapter (B2).
     *
     * @param queryHash recorded in the snapshot so a comparison can tell which query set ran
     * @throws IngestionInputException when {@code root} holds no Store manifest
     */
    Observation observe(Path root, IngestionManifest manifest, String queryHash, List<QueryText> queries) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        HtmlKnowledgePersistence.requireExistingStore(root);
        var run = new Run(manifest);
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        var b1Nanos = new long[queries.size()];
        var b2Nanos = new long[queries.size()];
        try (var adapter = ResonanceStoreMemoryAdapter.open(root, adapterConfig())) {
            for (var index = 0; index < queries.size(); index++) {
                var query = queries.get(index);
                lexical(run, query);
                var start = System.nanoTime();
                var b1 = direct(run, memory, query, MAX_LIMIT);
                b1Nanos[index] = System.nanoTime() - start;
                for (var limit : PREFIX_LIMITS) {
                    run.prefix(query, Layer.B1, limit, b1, direct(run, memory, query, limit));
                }
                start = System.nanoTime();
                var b2 = neuron(run, adapter, query, MAX_LIMIT);
                b2Nanos[index] = System.nanoTime() - start;
                for (var limit : PREFIX_LIMITS) {
                    run.prefix(query, Layer.B2, limit, rowsOf(b2), rowsOf(neuron(run, adapter, query, limit)));
                }
                run.compareControl(query, b2, codecDirect(run, memory, query));
            }
        }
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("perf.b1.recallLimit5.medianNanos", Long.toString(percentile(b1Nanos, 50)));
        metadata.put("perf.b1.recallLimit5.p95Nanos", Long.toString(percentile(b1Nanos, 95)));
        metadata.put("perf.b2.recallLimit5.medianNanos", Long.toString(percentile(b2Nanos, 50)));
        metadata.put("perf.b2.recallLimit5.p95Nanos", Long.toString(percentile(b2Nanos, 95)));
        metadata.put("perf.sampling", "one warm call per query and layer on one open handle; exploratory, never"
                + " part of a verdict");
        return new Observation(new Snapshot(queryHash, run.rows), run.checks(), metadata);
    }

    private void lexical(Run run, QueryText query) {
        var hits = lexical.search(query.text(), MAX_LIMIT);
        for (var rank = 0; rank < hits.size(); rank++) {
            var hit = hits.get(rank);
            run.rows.add(new Row(query.queryId(), Layer.B0, MAX_LIMIT, rank + 1, hit.chunkId(), hit.score()));
        }
    }

    /** B1 at {@code limit}; returns its mapped rows, or {@code null} after a recorded failure. */
    private List<Row> direct(Run run, MonadaMemory memory, QueryText query, int limit) {
        List<ResonanceResult> results;
        try {
            results = memory.resonate(query.text()).topK(limit).threshold(THRESHOLD).execute().results();
        } catch (RuntimeException e) {
            run.failure(query, Layer.B1, limit, "threw " + e);
            return null;
        }
        var rows = new ArrayList<Row>(results.size());
        for (var rank = 0; rank < results.size(); rank++) {
            var result = results.get(rank);
            var chunkId = run.mapAtom(query, Layer.B1, result);
            rows.add(new Row(query.queryId(), Layer.B1, limit, rank + 1, chunkId, result.score()));
        }
        run.rows.addAll(rows);
        return rows;
    }

    /** B2 at {@code limit}; returns its mapped rows with references, or {@code null} after a recorded failure. */
    private List<Referenced> neuron(Run run, ResonanceStoreMemoryAdapter adapter, QueryText query, int limit) {
        try {
            var response = adapter.recall(new ResonanceMemoryRequest(codec.querySignals(query.text()), limit));
            if (response.status() != ResonanceMemoryStatus.COMPLETE) {
                run.failure(query, Layer.B2, limit, "answered " + response.status());
                return null;
            }
            var referenced = new ArrayList<Referenced>(response.results().size());
            for (var rank = 0; rank < response.results().size(); rank++) {
                var result = response.results().get(rank);
                var chunkId = run.mapReference(query, result.reference());
                var row = new Row(query.queryId(), Layer.B2, limit, rank + 1, chunkId, result.score());
                run.rows.add(row);
                referenced.add(new Referenced(result.reference(), row));
            }
            return referenced;
        } catch (RuntimeException e) {
            run.failure(query, Layer.B2, limit, "threw " + e);
            return null;
        }
    }

    /**
     * B2c: the codec's query texts sent directly to the Store, merged by the adapter's documented rule: score
     * descending, then query Signal index, then Store rank; best candidate per atom; cut to {@value #MAX_LIMIT}.
     */
    private List<Referenced> codecDirect(Run run, MonadaMemory memory, QueryText query) {
        var best = new HashMap<String, Candidate>();
        try {
            var signals = codec.querySignals(query.text());
            for (var signalIndex = 0; signalIndex < signals.size(); signalIndex++) {
                var text = codec.encode(signals.get(signalIndex));
                var results = memory.resonate(text).topK(MAX_LIMIT).threshold(THRESHOLD).execute().results();
                for (var rank = 0; rank < results.size(); rank++) {
                    var candidate = new Candidate(results.get(rank), signalIndex, rank);
                    best.merge(candidate.result().atom().id(), candidate,
                            (left, right) -> MERGE_ORDER.compare(left, right) <= 0 ? left : right);
                }
            }
        } catch (RuntimeException e) {
            run.failure(query, Layer.B2C, MAX_LIMIT, "threw " + e);
            return null;
        }
        var merged = best.values().stream().sorted(MERGE_ORDER).limit(MAX_LIMIT).toList();
        var referenced = new ArrayList<Referenced>(merged.size());
        for (var rank = 0; rank < merged.size(); rank++) {
            var result = merged.get(rank).result();
            var chunkId = run.mapAtom(query, Layer.B2C, result);
            var row = new Row(query.queryId(), Layer.B2C, MAX_LIMIT, rank + 1, chunkId, result.score());
            run.rows.add(row);
            referenced.add(new Referenced(opaqueReference.of(result.atom().id()), row));
        }
        return referenced;
    }

    private static final Comparator<Candidate> MERGE_ORDER = Comparator
            .comparingDouble((Candidate candidate) -> candidate.result().score()).reversed()
            .thenComparingInt(Candidate::signalIndex)
            .thenComparingInt(Candidate::rank);

    private record Candidate(ResonanceResult result, int signalIndex, int rank) {
    }

    /** A mapped row with the opaque reference it came from, for the B2 = B2c comparison. */
    private record Referenced(String reference, Row row) {
    }

    /** Returns the rows of {@code referenced}, or {@code null} after a recorded failure. */
    private static List<Row> rowsOf(List<Referenced> referenced) {
        return referenced == null ? null : referenced.stream().map(Referenced::row).toList();
    }

    private static long percentile(long[] values, int percent) {
        if (values.length == 0) {
            return 0L;
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        var index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }

    /** Mutable state of one observation: the rows and the evidence behind each check. */
    private final class Run {

        private final IngestionManifest manifest;
        private final List<Row> rows = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final List<String> sourceFailures = new ArrayList<>();
        private final List<String> referenceFailures = new ArrayList<>();
        private final List<String> controlFailures = new ArrayList<>();
        private final List<String> prefixFailures = new ArrayList<>();
        private final List<String> ties = new ArrayList<>();
        private int controlComparisons;
        private int prefixComparisons;

        Run(IngestionManifest manifest) {
            this.manifest = manifest;
        }

        void failure(QueryText query, Layer layer, int limit, String detail) {
            failures.add(query.queryId() + " " + layer.label() + " limit " + limit + ": " + detail);
            if (limit == MAX_LIMIT) {
                rows.add(new Row(query.queryId(), layer, MAX_LIMIT, 0, FAILED, 0.0));
            }
        }

        String mapAtom(QueryText query, Layer layer, ResonanceResult result) {
            var chunkId = manifest.chunkIdForAtom(result.atom().id());
            if (chunkId.isEmpty()) {
                sourceFailures.add(query.queryId() + " " + layer.label() + ": atom " + result.atom().id()
                        + " is not in the manifest");
                return UNMAPPED;
            }
            var expected = corpus.chunk(chunkId.get()).orElseThrow().sha256();
            if (!CorpusDigests.sha256(result.atom().content()).equals(expected)) {
                sourceFailures.add(query.queryId() + " " + layer.label() + ": content of " + chunkId.get()
                        + " does not hash to its corpus sha256");
            }
            return chunkId.get();
        }

        String mapReference(QueryText query, String reference) {
            var chunkId = manifest.chunkIdForReference(reference);
            if (chunkId.isEmpty()) {
                referenceFailures.add(query.queryId() + ": reference " + reference + " is not in the manifest");
                return UNMAPPED;
            }
            return chunkId.get();
        }

        /** Checks that a limit-{@code limit} list is the prefix of the limit-5 list, up to a tie at the cutoff. */
        void prefix(QueryText query, Layer layer, int limit, List<Row> fullRows, List<Row> limitedRows) {
            if (fullRows == null || limitedRows == null) {
                return;
            }
            prefixComparisons++;
            var expectedSize = Math.min(limit, fullRows.size());
            var where = query.queryId() + " " + layer.label() + " limit " + limit;
            if (limitedRows.size() != expectedSize) {
                prefixFailures.add(where + ": " + limitedRows.size() + " results, expected " + expectedSize);
                return;
            }
            for (var i = 0; i < expectedSize; i++) {
                var got = limitedRows.get(i);
                var want = fullRows.get(i);
                if (got.chunkId().equals(want.chunkId()) && got.score() == want.score()) {
                    continue;
                }
                var cutoffScore = fullRows.get(expectedSize - 1).score();
                if (got.score() == want.score() && want.score() == cutoffScore) {
                    ties.add(where + " rank " + (i + 1));
                } else {
                    prefixFailures.add(where + " rank " + (i + 1) + ": " + got.chunkId() + " " + got.score()
                            + " instead of " + want.chunkId() + " " + want.score());
                }
            }
        }

        void compareControl(QueryText query, List<Referenced> neuron, List<Referenced> control) {
            if (neuron == null || control == null) {
                return;
            }
            controlComparisons++;
            var equal = neuron.size() == control.size();
            for (var i = 0; equal && i < neuron.size(); i++) {
                equal = neuron.get(i).reference().equals(control.get(i).reference())
                        && neuron.get(i).row().score() == control.get(i).row().score();
            }
            if (!equal) {
                controlFailures.add(query.queryId() + ": B2 " + chunks(neuron) + " vs B2c " + chunks(control));
            }
        }

        List<Check> checks() {
            var queries = rows.stream().map(Row::queryId).distinct().count();
            return List.of(
                    new Check("recall.completed", failures.isEmpty(), failures.isEmpty()
                            ? "every B1/B2c Store call returned and every B2 recall answered COMPLETE"
                            : failures.size() + " operational failures, never scored as abstentions: "
                                    + first(failures)),
                    new Check("fidelity.source", sourceFailures.isEmpty(), sourceFailures.isEmpty()
                            ? "every B1 and B2c atom maps through the manifest and its returned content hashes to"
                                    + " the corpus sha256 as returned"
                            : first(sourceFailures)),
                    new Check("fidelity.reference", referenceFailures.isEmpty(), referenceFailures.isEmpty()
                            ? "every B2 reference maps through the manifest to a chunk"
                            : first(referenceFailures)),
                    new Check("neuron.b2EqualsB2c", controlFailures.isEmpty() && controlComparisons > 0,
                            controlFailures.isEmpty()
                                    ? controlComparisons + " queries: B2 equals the codec-direct control (atoms, order,"
                                            + " bit-exact scores)"
                                    : "adapter defect: " + first(controlFailures)),
                    new Check("recall.prefixStability", prefixFailures.isEmpty() && prefixComparisons > 0,
                            (prefixFailures.isEmpty()
                                    ? prefixComparisons + " limit-1/3 lists equal the limit-5 prefix"
                                    : first(prefixFailures))
                                    + "; tie substitutions at the cutoff: " + (ties.isEmpty() ? "none" : first(ties))
                                    + " (" + queries + " queries)"));
        }

        private String chunks(List<Referenced> referenced) {
            return referenced.stream().map(value -> value.row().chunkId()).collect(Collectors.joining(", ", "[", "]"));
        }

        private String first(List<String> details) {
            var shown = String.join("; ", details.subList(0, Math.min(MAX_DETAILS, details.size())));
            return details.size() > MAX_DETAILS ? shown + "; and " + (details.size() - MAX_DETAILS) + " more" : shown;
        }
    }
}
