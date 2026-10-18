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
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The recall side of the judged recall experiment (Issue #107): runs every query text through B0, B1, B2 and the B2c
 * control over one persisted Store root and records what each returned, mapped to chunk ids through the import
 * manifest. It never sees gold labels: its input is {@link QueryText}, query id and text only (read in a phase JVM
 * from a labels-free workload file), and its output is a {@link Snapshot} that the scorer ({@link RecallMetrics})
 * judges afterwards. This class references neither the query set nor its labels.
 *
 * <ul>
 *   <li><b>B0</b>: {@link LexicalOverlapBaseline} over the corpus chunks.</li>
 *   <li><b>B1</b>: {@code MonadaMemory.resonate(text).topK(limit).threshold(t)}; the returned content must hash to
 *       the mapped chunk's {@code sha256} (source fidelity).</li>
 *   <li><b>B2</b>: the production {@link ResonanceStoreMemoryAdapter} with {@link TokenPackingSignalCodec} as query
 *       encoder and the production {@link HashedSignalDecoder}; it must answer {@code COMPLETE} and every reference
 *       must map through the manifest (reference fidelity).</li>
 *   <li><b>B2c</b>: the codec-direct control. The same codec emits the query texts, each goes directly to the Store
 *       with the same limit and {@code t}, and the adapter's merge rule is applied here; B2 must equal B2c exactly.</li>
 * </ul>
 *
 * <p>Every system is scored on its limit-{@value #MAX_LIMIT} list. B1 and B2 are also queried with limits 1 and 3,
 * which must return the prefix of that list except inside a score tie at the cutoff, and every system is queried once
 * more with the lookahead limit {@value #LOOKAHEAD_LIMIT}, used only to detect a tie that straddles the k = 5 cutoff
 * and checked the same way. Before the measured pass, one unmeasured warm-up pass sends every query once through B1
 * and B2. Any operational or fidelity failure of a query in a layer, at any limit, is a failed check and leaves a
 * {@link #FAILED} marker, so that query is never scored for that layer and never counted as an abstention.
 */
final class HtmlKnowledgeRecall {

    /** Largest evaluated limit; every system is scored on the prefixes of its limit-5 list. */
    static final int MAX_LIMIT = 5;

    /** Limits queried for the prefix-stability check, in addition to {@link #MAX_LIMIT}. */
    static final List<Integer> PREFIX_LIMITS = List.of(1, 3);

    /** Lookahead limit, used only to flag a score tie straddling the k = 5 cutoff; never part of {@code Ret_k}. */
    static final int LOOKAHEAD_LIMIT = MAX_LIMIT + 1;

    /** Threshold {@code t} passed explicitly to B1, B2 and B2c; the Store's {@code MonadaQuery} default. */
    static final double THRESHOLD = 0.0;

    /** Chunk id recorded for a result that maps to no manifest entry. */
    static final String UNMAPPED = "<unmapped>";

    /**
     * Chunk id of the single rank-0 marker row recorded when any call of a query in a layer failed, operationally or
     * by fidelity: the scorer gives that query no outcome for that layer, so a failure is never scored as a result.
     */
    static final String FAILED = "<failed>";

    private static final int MAX_DETAILS = 5;
    private static final int MAX_WORKLOAD_BYTES = 64 * 1024;

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

        private static final String HASH_PREFIX = "# queryHash ";
        private static final String HEADER = "queryId\ttext";

        QueryText {
            Objects.requireNonNull(queryId, "queryId must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }

        /**
         * Renders the labels-free workload a phase JVM reads: the {@code queryHash} line, a header and one
         * {@code queryId\ttext} row per query, with {@code \\}, {@code \t} and {@code \n} escaped.
         */
        static String renderTsv(String queryHash, List<QueryText> queries) {
            var tsv = new StringBuilder(HASH_PREFIX).append(queryHash).append('\n').append(HEADER).append('\n');
            for (var query : queries) {
                tsv.append(query.queryId()).append('\t').append(escape(query.text())).append('\n');
            }
            return tsv.toString();
        }

        /** A parsed workload: the {@code queryHash} it was rendered for and its queries in run order. */
        record Workload(String queryHash, List<QueryText> queries) {

            Workload {
                Objects.requireNonNull(queryHash, "queryHash must not be null");
                queries = List.copyOf(queries);
            }
        }

        /**
         * Parses {@link #renderTsv} output strictly; at most 64 KiB.
         *
         * @throws IngestionInputException when the workload is malformed or oversize
         */
        static Workload parseTsv(String tsv) {
            if (tsv.length() > MAX_WORKLOAD_BYTES) {
                throw new IngestionInputException("workload exceeds " + MAX_WORKLOAD_BYTES + " characters");
            }
            var lines = tsv.split("\n", -1);
            if (lines.length < 3 || !lines[0].startsWith(HASH_PREFIX) || !lines[1].equals(HEADER)
                    || !lines[lines.length - 1].isEmpty()) {
                throw new IngestionInputException("not a query workload");
            }
            var queries = new ArrayList<QueryText>();
            var ids = new HashSet<String>();
            for (var i = 2; i < lines.length - 1; i++) {
                var cells = lines[i].split("\t", -1);
                if (cells.length != 2 || cells[0].isEmpty() || !ids.add(cells[0])) {
                    throw new IngestionInputException("malformed or duplicate workload row " + (i + 1));
                }
                queries.add(new QueryText(cells[0], unescape("workload:" + (i + 1), cells[1])));
            }
            return new Workload(lines[0].substring(HASH_PREFIX.length()), queries);
        }

        /** Escapes {@code \\}, tab and line feed so a text fits one TSV cell. */
        static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
        }

        /**
         * Decodes the escapes {@code \\}, {@code \t} and {@code \n}; any other backslash sequence is rejected.
         *
         * @throws CorpusValidationException naming {@code where} on an invalid escape
         */
        static String unescape(String where, String cell) {
            var text = new StringBuilder(cell.length());
            for (var i = 0; i < cell.length(); i++) {
                var c = cell.charAt(i);
                if (c != '\\') {
                    text.append(c);
                    continue;
                }
                if (i + 1 == cell.length()) {
                    throw new CorpusValidationException(where, "text ends with a lone backslash");
                }
                text.append(switch (cell.charAt(++i)) {
                    case '\\' -> '\\';
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    default -> throw new CorpusValidationException(where,
                            "unknown text escape '\\" + cell.charAt(i) + "'");
                });
            }
            return text.toString();
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
        HtmlKnowledgePersistence.requireExistingStore(root);
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        try (var adapter = ResonanceStoreMemoryAdapter.open(root, adapterConfig())) {
            return observe(memory, adapter, manifest, queryHash, queries);
        }
    }

    /**
     * Runs every query through an open {@code MonadaMemory} (B1, B2c) and an open adapter (B2), which may be bound to
     * any store-side recall; the caller owns both.
     */
    Observation observe(MonadaMemory memory, ResonanceStoreMemoryAdapter adapter, IngestionManifest manifest,
            String queryHash, List<QueryText> queries) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(queryHash, "queryHash must not be null");
        var run = new Run(manifest, queries.size());
        warmUp(memory, adapter, queries);
        var b1Nanos = new long[queries.size()];
        var b2Nanos = new long[queries.size()];
        for (var index = 0; index < queries.size(); index++) {
            var query = queries.get(index);
            lexical(run, query);
            var start = System.nanoTime();
            var b1 = direct(run, memory, query, MAX_LIMIT);
            b1Nanos[index] = System.nanoTime() - start;
            for (var limit : PREFIX_LIMITS) {
                run.prefix(query, Layer.B1, limit, b1, direct(run, memory, query, limit));
            }
            run.prefix(query, Layer.B1, MAX_LIMIT, direct(run, memory, query, LOOKAHEAD_LIMIT), b1);
            start = System.nanoTime();
            var b2 = neuron(run, adapter, query, MAX_LIMIT);
            b2Nanos[index] = System.nanoTime() - start;
            for (var limit : PREFIX_LIMITS) {
                run.prefix(query, Layer.B2, limit, rowsOf(b2), rowsOf(neuron(run, adapter, query, limit)));
            }
            var b2Lookahead = neuron(run, adapter, query, LOOKAHEAD_LIMIT);
            run.prefix(query, Layer.B2, MAX_LIMIT, rowsOf(b2Lookahead), rowsOf(b2));
            var b2c = codecDirect(run, memory, query, MAX_LIMIT);
            var b2cLookahead = codecDirect(run, memory, query, LOOKAHEAD_LIMIT);
            run.prefix(query, Layer.B2C, MAX_LIMIT, rowsOf(b2cLookahead), rowsOf(b2c));
            run.compareControl(query, MAX_LIMIT, b2, b2c);
            run.compareControl(query, LOOKAHEAD_LIMIT, b2Lookahead, b2cLookahead);
        }
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("perf.warmupQueries", Integer.toString(queries.size()));
        metadata.put("perf.b1.recallLimit5.medianNanos", Long.toString(percentile(b1Nanos, 50)));
        metadata.put("perf.b1.recallLimit5.p95Nanos", Long.toString(percentile(b1Nanos, 95)));
        metadata.put("perf.b2.recallLimit5.medianNanos", Long.toString(percentile(b2Nanos, 50)));
        metadata.put("perf.b2.recallLimit5.p95Nanos", Long.toString(percentile(b2Nanos, 95)));
        metadata.put("perf.sampling", "warm: one unmeasured pass of every query through B1 and B2, then one measured"
                + " limit-5 call per query and layer on the same open handles; single samples, exploratory, never"
                + " part of a verdict");
        return new Observation(new Snapshot(queryHash, run.rowsWithFailureMarkers()), run.checks(), metadata);
    }

    /**
     * Sends every query once through B1 and B2 at limit 5 and discards the results, so the measured pass does not
     * time class loading, JIT compilation or first Store reads. Failures are ignored here; the measured pass records
     * them. Recall writes nothing ({@code feedback} is never called), so warming cannot change a later result.
     */
    private void warmUp(MonadaMemory memory, ResonanceStoreMemoryAdapter adapter, List<QueryText> queries) {
        for (var query : queries) {
            try {
                memory.resonate(query.text()).topK(MAX_LIMIT).threshold(THRESHOLD).execute();
                adapter.recall(new ResonanceMemoryRequest(codec.querySignals(query.text()), MAX_LIMIT));
            } catch (RuntimeException ignored) {
                // Recorded by the measured pass.
            }
        }
    }

    private void lexical(Run run, QueryText query) {
        var hits = lexical.search(query.text(), LOOKAHEAD_LIMIT);
        for (var limit : List.of(MAX_LIMIT, LOOKAHEAD_LIMIT)) {
            for (var rank = 0; rank < Math.min(limit, hits.size()); rank++) {
                var hit = hits.get(rank);
                run.rows.add(new Row(query.queryId(), Layer.B0, limit, rank + 1, hit.chunkId(), hit.score()));
            }
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
                var chunkId = run.mapReference(query, Layer.B2, result.reference());
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
     * descending, then query Signal index, then Store rank; best candidate per atom; cut to {@code limit}.
     */
    private List<Referenced> codecDirect(Run run, MonadaMemory memory, QueryText query, int limit) {
        var best = new HashMap<String, Candidate>();
        try {
            var signals = codec.querySignals(query.text());
            for (var signalIndex = 0; signalIndex < signals.size(); signalIndex++) {
                var text = codec.encode(signals.get(signalIndex));
                var results = memory.resonate(text).topK(limit).threshold(THRESHOLD).execute().results();
                for (var rank = 0; rank < results.size(); rank++) {
                    var candidate = new Candidate(results.get(rank), signalIndex, rank);
                    best.merge(candidate.result().atom().id(), candidate,
                            (left, right) -> MERGE_ORDER.compare(left, right) <= 0 ? left : right);
                }
            }
        } catch (RuntimeException e) {
            run.failure(query, Layer.B2C, limit, "threw " + e);
            return null;
        }
        var merged = best.values().stream().sorted(MERGE_ORDER).limit(limit).toList();
        var referenced = new ArrayList<Referenced>(merged.size());
        for (var rank = 0; rank < merged.size(); rank++) {
            var result = merged.get(rank).result();
            var chunkId = run.mapAtom(query, Layer.B2C, result);
            var row = new Row(query.queryId(), Layer.B2C, limit, rank + 1, chunkId, result.score());
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

    /** A query and layer left without an outcome. */
    private record FailedLayer(String queryId, Layer layer) {
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
        private final int queryCount;
        private final List<Row> rows = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final List<String> sourceFailures = new ArrayList<>();
        private final List<String> referenceFailures = new ArrayList<>();
        private final List<String> controlFailures = new ArrayList<>();
        private final List<String> prefixFailures = new ArrayList<>();
        private final List<String> ties = new ArrayList<>();
        private final Set<FailedLayer> failed = new LinkedHashSet<>();
        private int controlComparisons;
        private int prefixComparisons;

        Run(IngestionManifest manifest, int queryCount) {
            this.manifest = manifest;
            this.queryCount = queryCount;
        }

        void failure(QueryText query, Layer layer, int limit, String detail) {
            failures.add(query.queryId() + " " + layer.label() + " limit " + limit + ": " + detail);
            markFailed(query, layer);
        }

        /** Leaves {@code query} without an outcome in {@code layer}; the single path every failure goes through. */
        void markFailed(QueryText query, Layer layer) {
            failed.add(new FailedLayer(query.queryId(), layer));
        }

        String mapAtom(QueryText query, Layer layer, ResonanceResult result) {
            var chunkId = manifest.chunkIdForAtom(result.atom().id());
            if (chunkId.isEmpty()) {
                sourceFailures.add(query.queryId() + " " + layer.label() + ": atom " + result.atom().id()
                        + " is not in the manifest");
                markFailed(query, layer);
                return UNMAPPED;
            }
            var expected = corpus.chunk(chunkId.get()).orElseThrow().sha256();
            if (!CorpusDigests.sha256(result.atom().content()).equals(expected)) {
                sourceFailures.add(query.queryId() + " " + layer.label() + ": content of " + chunkId.get()
                        + " does not hash to its corpus sha256");
                markFailed(query, layer);
            }
            return chunkId.get();
        }

        String mapReference(QueryText query, Layer layer, String reference) {
            var chunkId = manifest.chunkIdForReference(reference);
            if (chunkId.isEmpty()) {
                referenceFailures.add(query.queryId() + ": reference " + reference + " is not in the manifest");
                markFailed(query, layer);
                return UNMAPPED;
            }
            return chunkId.get();
        }

        /** Returns the recorded rows followed by one {@link #FAILED} marker per failed query and layer. */
        List<Row> rowsWithFailureMarkers() {
            var all = new ArrayList<>(rows);
            failed.forEach(value -> all.add(new Row(value.queryId(), value.layer(), MAX_LIMIT, 0, FAILED, 0.0)));
            return all;
        }

        /**
         * Checks that the limit-{@code limit} list is the prefix of the longer {@code fullRows} list, up to a tie at
         * the cutoff.
         */
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

        void compareControl(QueryText query, int limit, List<Referenced> neuron, List<Referenced> control) {
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
                controlFailures.add(query.queryId() + " limit " + limit + ": B2 " + chunks(neuron) + " vs B2c "
                        + chunks(control));
            }
        }

        List<Check> checks() {
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
                                    ? controlComparisons + " comparisons (limits 5 and 6): B2 equals the codec-direct"
                                            + " control (atoms, order, bit-exact scores)"
                                    : "adapter defect: " + first(controlFailures)),
                    new Check("recall.prefixStability", prefixFailures.isEmpty() && prefixComparisons > 0,
                            (prefixFailures.isEmpty()
                                    ? prefixComparisons + " shorter lists (B1/B2 limits 1, 3, 5; B2c limit 5) equal the"
                                            + " prefix of the next longer list"
                                    : first(prefixFailures))
                                    + "; tie substitutions at the cutoff: " + (ties.isEmpty() ? "none" : first(ties))
                                    + " (" + queryCount + " queries)"));
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
