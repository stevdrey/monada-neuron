package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Abstained;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeCycleExperiment.Run;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScalingCorpus.Family;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScalingCorpus.Point;
import monada.neuron.evaluation.knowledge.RecallMetrics.LayerAbstention;
import monada.neuron.evaluation.knowledge.RecallMetrics.LayerAggregate;
import monada.neuron.evaluation.knowledge.RecallMetrics.QueryScore;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import monada.neuron.signal.Signal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One point of the L6 scaling profile (Issue #111, extension {@code scaling-v1}): imports the point corpus into a fresh
 * Store root, takes its verified semantic snapshot, then measures every phase as its own row:
 *
 * <ol>
 *   <li>{@code setup.corpus}: building and validating the point corpus (setup, timed separately);</li>
 *   <li>{@code ingest.remember}: {@code MonadaMemory.open} plus one {@code remember} per chunk into a fresh scratch
 *       root per sample;</li>
 *   <li>{@code open.firstRecall.b1} and {@code open.firstRecall.b2}: a freshly opened handle and its first recall, in
 *       this already warm JVM (the protocol's "cold"); process-cold is not measured;</li>
 *   <li>{@code warm.b1}: warm direct {@code MonadaMemory.resonate} at each {@code k};</li>
 *   <li>{@code warm.b2c} and {@code warm.b2}: the Store-side work the adapter must do (one Store call per codec Signal
 *       plus the documented merge) and warm {@code ResonanceStoreMemoryAdapter.recall}; their difference is the
 *       Neuron wrapper, never {@code B2 - B1};</li>
 *   <li>{@code warm.cycle}: the bounded cycle {@code MEMORY_RECALL -> REASONING -> EVALUATION} over the adapter;</li>
 *   <li>{@code warm.qa.b3} and {@code warm.qa.b1+b3}: the extractive answer alone over verified B1 evidence, and
 *       verified B1 evidence retrieval plus the answer.</li>
 * </ol>
 *
 * <p>Semantics are judged outside every timed window: the ingestion checks of #105, a fresh-handle readback, the full
 * #107 recall observation (B0, B1, B2, B2c with fidelity, prefix stability and B2 = B2c) over the point's eligible
 * queries, the #108 trace integrity of every verified cycle and the mechanical citation check of #109. Every timed
 * sample, warm-up included, keeps its result, and after the window each one must equal the verified result (mapped
 * chunk ids, order and bit-exact scores; equal cycle runs; equal answers). A mismatch or a throw fails
 * {@code perf.matchesVerified} and the row is withheld (listed as invalidated or unavailable), so no published latency
 * ever describes a degraded path. Latency and allocation never decide a
 * verdict.
 */
final class HtmlKnowledgeScaling {

    /** Evaluated {@code k}, as in #107. */
    static final List<Integer> KS = RecallMetrics.KS;

    /** Layers scored at every point: the direct Store and the Neuron port. */
    static final List<Layer> SCORED_LAYERS = List.of(Layer.B1, Layer.B2);

    /** Populations scored at every point. */
    static final String CURVE = "curve";
    static final String POINT = "point";

    /** How every scaling row is timed. */
    static final String MEASUREMENT_METHOD = "EvaluationMetricsCollector: System.nanoTime around the workload, thread"
            + " allocated bytes from ThreadMXBean around the same window; LatencyDistribution percentiles (linear"
            + " interpolation, so p95 is not comparable to the nearest-rank LatencyRow values of #106-#110); not JMH";

    /** The reason process-cold rows are absent. */
    static final String PROCESS_COLD = "unavailable (not measured: every open runs in this already warm JVM;"
            + " restart persistence is covered by #106 and #107)";

    /**
     * Iteration counts.
     *
     * @param warmups unmeasured iterations before a warm row
     * @param iterations measured iterations of a warm row
     * @param coldIterations measured iterations of a setup, ingestion or first-recall row, each on fresh state
     * @param ingestWarmups unmeasured ingestions before the ingestion row
     */
    record Sampling(int warmups, int iterations, int coldIterations, int ingestWarmups) {

        static final Sampling FULL = new Sampling(3, 10, 5, 1);
        static final Sampling QUICK = new Sampling(1, 3, 2, 0);

        Sampling {
            if (warmups < 0 || iterations < 1 || coldIterations < 1 || ingestWarmups < 0) {
                throw new IllegalArgumentException("invalid sampling");
            }
        }
    }

    /**
     * Mechanical answer counts of B3 over one layer's evidence at one {@code answerK}; {@code A} and {@code U} split
     * queries by a non-empty relevant set.
     *
     * @param answeredA answerable queries answered
     * @param abstainedA answerable queries abstained on
     * @param answeredU non-answerable queries answered
     * @param abstainedU non-answerable queries abstained on
     * @param citesRelevant answered answerable queries with an eligible citation of a relevant chunk
     * @param citations emitted citations
     * @param ineligible citations outside {@code E(q)} or out of bounds
     * @param exactQuotes citations quoting their claim exactly
     * @param failed queries whose evidence recall failed (not scored)
     */
    record AnswerCounts(int answeredA, int abstainedA, int answeredU, int abstainedU, int citesRelevant, int citations,
            int ineligible, int exactQuotes, int failed) {
    }

    /**
     * One B3 answer of the point population, the claim-level artifact behind {@link AnswerCounts}.
     *
     * @param query the judged query
     * @param layer the evidence layer
     * @param answerK the evidence cut
     * @param evidence {@code E(q)}, empty when the recall failed
     * @param answer the answer, empty when the recall failed
     * @param citations the mechanical check of every citation, per claim in claim order
     * @param failure why the evidence recall failed, when it did
     */
    record AnswerRow(JudgedQuery query, Layer layer, int answerK, List<EvidenceChunk> evidence,
            Optional<GroundedAnswer> answer, List<List<CitedSpan>> citations, Optional<String> failure) {

        AnswerRow {
            evidence = List.copyOf(evidence);
            citations = citations.stream().map(List::copyOf).toList();
        }
    }

    /**
     * Everything measured and scored at one point.
     *
     * @param point the point
     * @param checks semantic verdicts, prefixed {@code n<N>.}
     * @param rows the measured rows
     * @param unavailable rows that could not be measured, with the reason
     * @param quality recall aggregates by {@code population/layer/k}
     * @param abstention set-level abstention by {@code population/layer}
     * @param queryScores the per-query recall rows of the point population, B1 then B2, in query-set order
     * @param answers B3 counts by {@code layer/k} over the point population
     * @param answerRows the per-query B3 rows behind {@code answers}, by layer, {@code answerK} and query
     * @param footprint persisted-atom and on-disk figures
     * @param overhead the wrapper overhead per {@code k}: B2 minus B2c median nanoseconds per query, when both rows exist
     * @param storeCallsPerRecall mean Store calls one B2 recall makes (codec Signals per query)
     * @param metadata further descriptive values, prefixed {@code n<N>.}
     */
    record PointResult(Point point, List<Check> checks, List<BenchmarkRunResult> rows, Map<String, String> unavailable,
            Map<String, LayerAggregate> quality, Map<String, LayerAbstention> abstention, List<QueryScore> queryScores,
            Map<String, AnswerCounts> answers, List<AnswerRow> answerRows, Footprint footprint,
            Map<Integer, Optional<Double>> overhead, double storeCallsPerRecall, Map<String, String> metadata) {

        PointResult {
            checks = List.copyOf(checks);
            rows = List.copyOf(rows);
            unavailable = Map.copyOf(unavailable);
            quality = Map.copyOf(quality);
            abstention = Map.copyOf(abstention);
            queryScores = List.copyOf(queryScores);
            answers = Map.copyOf(answers);
            answerRows = List.copyOf(answerRows);
            overhead = Map.copyOf(overhead);
            metadata = Map.copyOf(metadata);
        }
    }

    /**
     * Persisted footprint of the root under test.
     *
     * @param persistedAtoms distinct atoms the fresh-handle readback returned
     * @param rootBytes total size of the regular files under the root
     * @param rootFiles number of regular files under the root
     */
    record Footprint(int persistedAtoms, long rootBytes, long rootFiles) {
    }

    private final Sampling sampling;
    private final UnaryOperator<ResonanceMemoryPort> timedPort;
    private final EvaluationMetricsCollector collector = new EvaluationMetricsCollector();
    private final TokenPackingSignalCodec codec = new TokenPackingSignalCodec();
    private final HtmlKnowledgeCycleExperiment cycle = new HtmlKnowledgeCycleExperiment();
    private final HtmlKnowledgeQaExperiment qa = new HtmlKnowledgeQaExperiment();
    private final ExtractiveAnswerer answerer = new ExtractiveAnswerer(ExtractiveAnswerer.Rule.B3);

    HtmlKnowledgeScaling(Sampling sampling) {
        this(sampling, UnaryOperator.identity());
    }

    /**
     * Creates the profile; {@code timedPort} wraps the adapter used inside timed windows only, so a test can inject a
     * degraded port and observe {@code perf.matchesVerified} fail. Verified results never go through it.
     */
    HtmlKnowledgeScaling(Sampling sampling, UnaryOperator<ResonanceMemoryPort> timedPort) {
        this.sampling = Objects.requireNonNull(sampling, "sampling must not be null");
        this.timedPort = Objects.requireNonNull(timedPort, "timedPort must not be null");
    }

    /** Collects the rows, unavailable rows and mismatches of one point. */
    private final class Rows {

        final Point point;
        final List<BenchmarkRunResult> measured = new ArrayList<>();
        final Map<String, String> unavailable = new TreeMap<>();
        final List<String> mismatches = new ArrayList<>();

        Rows(Point point) {
            this.point = point;
        }

        /**
         * Measures {@code workload}, keeping every result, then validates each one after the window: {@code check}
         * returns why a result differs from the verified one, or empty. The row is published only when every result
         * matches; otherwise it is recorded as invalidated, with the first difference.
         */
        <T> Optional<BenchmarkRunResult> measure(String name, Optional<Integer> k, int warmups, int iterations,
                int operations, Runnable setup, Supplier<T> workload, Function<T, Optional<String>> check,
                String timed, String validation) {
            var label = name + k.map(value -> ".k" + value).orElse("");
            var results = new ArrayList<T>(warmups + iterations);
            var diagnostics = new TreeMap<String, String>();
            diagnostics.put("size", Integer.toString(point.size()));
            diagnostics.put("corpusVersion", point.corpus().version());
            k.ifPresent(value -> diagnostics.put("k", Integer.toString(value)));
            diagnostics.put("warmupIterations", Integer.toString(warmups));
            diagnostics.put("measurementIterations", Integer.toString(iterations));
            diagnostics.put("operationsPerIteration", Integer.toString(operations));
            diagnostics.put("measurementMethod", MEASUREMENT_METHOD);
            diagnostics.put("timed", timed);
            diagnostics.put("validationInWindow", "false");
            diagnostics.put("validation", validation + "; every warm-up and measured result is compared after the"
                    + " window");
            diagnostics.put("gcTelemetryScope", setup == null
                    ? "thread-allocated bytes: workload only (plus one list append per sample); GC and RSS deltas are"
                    + " JVM-wide over the measured iterations"
                    : "thread-allocated bytes: workload only; GC and RSS deltas are JVM-wide and include the"
                    + " per-iteration setup");
            BenchmarkRunResult row;
            try {
                row = collector.measure("scaling." + name, scale(k), warmups, iterations, operations, setup,
                        () -> results.add(workload.get()), diagnostics);
            } catch (RuntimeException e) {
                unavailable.put(label, "unavailable (the workload threw " + e + ")");
                mismatches.add(label + " threw " + e);
                return Optional.empty();
            }
            for (var i = 0; i < results.size(); i++) {
                var difference = check.apply(results.get(i));
                if (difference.isPresent()) {
                    // A row that timed a degraded path is never published, only named as invalidated.
                    var detail = label + " sample " + (i + 1) + ": " + difference.get();
                    mismatches.add(detail);
                    unavailable.put(label, "invalidated (a timed sample differs from the verified result: " + detail
                            + ")");
                    return Optional.empty();
                }
            }
            measured.add(row);
            return Optional.of(row);
        }

        private String scale(Optional<Integer> k) {
            return "N=" + point.size() + k.map(value -> " k=" + value).orElse("");
        }
    }

    /**
     * Measures one point into {@code directory}, which must not exist yet or be empty: the root under test is
     * {@code directory/root}, scratch ingestion roots sit beside it on the same storage and are deleted here.
     */
    PointResult measure(Family family, Point point, Path directory) {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(point, "point must not be null");
        var corpus = point.corpus();
        var prefix = "n" + point.size() + ".";
        var checks = new ArrayList<Check>();
        var metadata = new TreeMap<String, String>();
        var rows = new Rows(point);

        rows.measure("setup.corpus", Optional.empty(), 0, sampling.coldIterations(), 1, null,
                () -> HtmlKnowledgeScalingCorpus.corpus(family.base(), point.size(), family.seed()),
                built -> built.corpusHash().equals(corpus.corpusHash()) ? Optional.empty()
                        : Optional.of("corpusHash " + built.corpusHash() + " differs"),
                "generating the distractors, building and validating " + corpus.version() + " (every label, span,"
                        + " digest, bound and the conflict pair) and hashing it",
                "the built corpusHash equals the point's");

        // The root under test: its import and readback are semantic checks (#105).
        var root = directory.resolve("root");
        var ingestion = new HtmlKnowledgeIngestion(corpus);
        var imported = ingestion.ingest(root);
        imported.checks().forEach(check -> checks.add(prefixed(prefix, check)));
        var manifest = imported.manifest();

        measureIngestion(point, directory, rows, metadata, prefix);

        var sweep = ingestion.readbackSweep(MonadaMemory.open(root), manifest);
        var atomIds = manifest.entries().stream().map(IngestionManifest.Entry::atomId).collect(Collectors.toSet());
        checks.add(new Check(prefix + "store.freshHandleReadback", sweep.unread().isEmpty()
                && sweep.recalledAtomIds().equals(atomIds), sweep.unread().isEmpty()
                && sweep.recalledAtomIds().equals(atomIds)
                ? "a freshly opened handle in this process read back all " + atomIds.size() + " chunks, and the"
                        + " readback returned exactly the manifest's atoms (fresh-handle consistency, not persistence"
                        + " evidence)"
                : "unread " + sweep.unread() + "; recalled " + sweep.recalledAtomIds().size() + " distinct atoms, "
                        + atomIds.size() + " in the manifest"));
        var footprint = footprint(root, sweep.recalledAtomIds().size());

        // The verified recall snapshot over the point's eligible queries (#107), labels-free.
        var queries = texts(point.eligible());
        var recall = new HtmlKnowledgeRecall(corpus);
        var observation = recall.observe(root, manifest, family.querySet().queryHash(), queries);
        observation.checks().forEach(check -> checks.add(prefixed(prefix, check)));
        var snapshot = observation.snapshot();
        var quality = new LinkedHashMap<String, LayerAggregate>();
        var abstention = new LinkedHashMap<String, LayerAbstention>();
        score(family.curve(), CURVE, snapshot, quality, abstention);
        var queryScores = score(point.eligible(), POINT, snapshot, quality, abstention);

        var memory = MonadaMemory.open(root);
        var config = recall.adapterConfig();
        var sample = HtmlKnowledgeRecall.firstWithToken(texts(family.curve())).orElseThrow();
        measureFirstRecall(point, root, manifest, snapshot, sample, rows);
        metadata.put(prefix + "perf.open.processCold", PROCESS_COLD);

        var evidence = new RecalledEvidence(corpus, manifest);
        var answers = new LinkedHashMap<String, AnswerCounts>();
        var answerRows = new ArrayList<AnswerRow>();
        var overhead = new LinkedHashMap<Integer, Optional<Double>>();
        var cycleViolations = new ArrayList<String>();
        double storeCalls;
        try (var adapter = ResonanceStoreMemoryAdapter.open(root, config)) {
            var timed = timedPort.apply(adapter);
            var signals = queries.stream().map(query -> codec.querySignals(query.text())).toList();
            storeCalls = signals.stream().mapToInt(List::size).average().orElse(0.0);
            var b1Evidence = queries.stream().map(query -> evidence.direct(memory, query.text())).toList();
            var b2Evidence = queries.stream().map(query -> evidence.neuron(adapter, memory, query.text())).toList();
            for (var k : KS) {
                measureRecall(point, k, queries, signals, memory, timed, manifest, snapshot, rows);
                measureCycle(point, k, queries, adapter, timed, manifest, snapshot, rows, cycleViolations);
                measureAnswers(point, k, queries, b1Evidence, evidence, memory, rows);
                for (var layer : SCORED_LAYERS) {
                    var rowsAtK = answerRows(point.eligible(), layer == Layer.B1 ? b1Evidence : b2Evidence, layer, k);
                    answerRows.addAll(rowsAtK);
                    answers.put(layer.label() + "/" + k, counts(rowsAtK));
                }
                overhead.put(k, overhead(rows, k));
            }
        }
        checks.add(new Check(prefix + "cycle.traceIntegrity", cycleViolations.isEmpty(), cycleViolations.isEmpty()
                ? "every verified cycle at every k completed with one port call, admitted a prefix of the recorded"
                        + " response, attached only admitted references, and admitted exactly the verified B2 rows"
                : cycleViolations.size() + " violations: " + head(cycleViolations)));
        var ineligible = answers.entrySet().stream().filter(entry -> entry.getValue().ineligible() > 0)
                .map(Map.Entry::getKey).toList();
        var failedEvidence = answers.entrySet().stream().filter(entry -> entry.getValue().failed() > 0)
                .map(Map.Entry::getKey).toList();
        checks.add(new Check(prefix + "answer.citationsEligible", ineligible.isEmpty() && failedEvidence.isEmpty(),
                ineligible.isEmpty() && failedEvidence.isEmpty()
                        ? "every B3 citation over B1 and B2 evidence at every answerK names a chunk of E(q) within"
                                + " bounds, and every evidence recall completed with verified Store content"
                        : "ineligible citations at " + ineligible + "; failed evidence at " + failedEvidence));
        checks.add(new Check(prefix + "perf.matchesVerified", rows.mismatches.isEmpty(), rows.mismatches.isEmpty()
                ? "every timed sample of all " + rows.measured.size() + " rows reproduced the verified result outside"
                        + " the window, so no row timed a degraded path"
                : rows.mismatches.size() + " rows timed a result that differs from the verified one or threw: "
                        + head(rows.mismatches)));
        metadata.put(prefix + "queries.eligible", Integer.toString(point.eligible().size()));
        metadata.put(prefix + "queries.removed", point.removed().isEmpty() ? "none"
                : String.join(", ", point.removed()));
        metadata.put(prefix + "perf.storeCallsPerB2Recall", String.format(Locale.ROOT, "%.2f", storeCalls));
        return new PointResult(point, checks, rows.measured, rows.unavailable, quality, abstention, queryScores, answers,
                answerRows, footprint, overhead, storeCalls, metadata);
    }

    private void measureIngestion(Point point, Path directory, Rows rows, Map<String, String> metadata,
            String prefix) {
        var corpus = point.corpus();
        var scratch = new ArrayList<Path>();
        var leftovers = new ArrayList<String>();
        var counter = new int[1];
        Runnable freshRoot = () -> {
            scratch.forEach(path -> HtmlKnowledgeIngestionRunner.deleteTreeQuietly(path).ifPresent(leftovers::add));
            scratch.clear();
            var next = directory.resolve("scratch-" + ++counter[0]);
            try {
                Files.createDirectories(next);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            scratch.add(next);
        };
        rows.measure("ingest.remember", Optional.empty(), sampling.ingestWarmups(), sampling.coldIterations(),
                corpus.chunks().size(), freshRoot, () -> {
                    var memory = MonadaMemory.open(scratch.getFirst());
                    var atoms = new ArrayList<KnowledgeAtom>(corpus.chunks().size());
                    for (var chunk : corpus.chunks()) {
                        atoms.add(memory.remember(chunk.text()));
                    }
                    return atoms;
                }, atoms -> faithfulImport(corpus, atoms),
                "MonadaMemory.open on a fresh empty scratch root plus one remember per chunk in chunkId order;"
                        + " operations are chunks, so throughput is chunks per second",
                "one distinct atom per chunk whose content hashes to the chunk sha256");
        scratch.forEach(path -> HtmlKnowledgeIngestionRunner.deleteTreeQuietly(path).ifPresent(leftovers::add));
        metadata.put(prefix + "perf.ingest.scratchLeftovers", leftovers.isEmpty() ? "none" : String.join("; ",
                leftovers));
    }

    private static Optional<String> faithfulImport(HtmlKnowledgeCorpus corpus, List<KnowledgeAtom> atoms) {
        if (atoms.size() != corpus.chunks().size()) {
            return Optional.of(atoms.size() + " atoms for " + corpus.chunks().size() + " chunks");
        }
        var ids = new HashSet<String>();
        for (var i = 0; i < atoms.size(); i++) {
            var chunk = corpus.chunks().get(i);
            if (!ids.add(atoms.get(i).id())
                    || !CorpusDigests.sha256(atoms.get(i).content()).equals(chunk.sha256())) {
                return Optional.of("atom of " + chunk.chunkId() + " is repeated or its content differs");
            }
        }
        return Optional.empty();
    }

    private void measureFirstRecall(Point point, Path root, IngestionManifest manifest, Snapshot snapshot,
            QueryText sample, Rows rows) {
        var limit = HtmlKnowledgeRecall.MAX_LIMIT;
        var cold = "on a handle opened inside the window, in this already warm JVM, for " + sample.queryId()
                + " at limit " + limit;
        rows.measure("open.firstRecall.b1", Optional.empty(), 0, sampling.coldIterations(), 1, null,
                () -> MonadaMemory.open(root).resonate(sample.text()).topK(limit)
                        .threshold(HtmlKnowledgeRecall.THRESHOLD).execute().results(),
                results -> sameRows(atomRows(manifest, results), snapshot.rows(sample.queryId(), Layer.B1, limit)),
                "MonadaMemory.open plus its first resonate " + cold,
                "mapped chunk ids and bit-exact scores equal the verified B1 rows");
        var request = new ResonanceMemoryRequest(codec.querySignals(sample.text()), limit);
        var config = new HtmlKnowledgeRecall(point.corpus()).adapterConfig();
        rows.measure("open.firstRecall.b2", Optional.empty(), 0, sampling.coldIterations(), 1, null, () -> {
            try (var adapter = ResonanceStoreMemoryAdapter.open(root, config)) {
                return timedPort.apply(adapter).recall(request);
            }
        }, response -> referenceRows(manifest, response, snapshot.rows(sample.queryId(), Layer.B2, limit)),
                "ResonanceStoreMemoryAdapter.open plus its first recall " + cold
                        + "; the request is built before the window",
                "status COMPLETE; mapped references and bit-exact scores equal the verified B2 rows");
    }

    private void measureRecall(Point point, int k, List<QueryText> queries, List<List<Signal>> signals,
            MonadaMemory memory, ResonanceMemoryPort timed, IngestionManifest manifest, Snapshot snapshot,
            Rows rows) {
        var size = queries.size();
        rows.measure("warm.b1", Optional.of(k), sampling.warmups(), sampling.iterations(), size, null, () -> {
            var all = new ArrayList<List<ResonanceResult>>(size);
            for (var query : queries) {
                all.add(memory.resonate(query.text()).topK(k).threshold(HtmlKnowledgeRecall.THRESHOLD).execute()
                        .results());
            }
            return all;
        }, all -> firstDifference(queries, i -> sameRows(atomRows(manifest, all.get(i)),
                snapshot.rows(queries.get(i).queryId(), Layer.B1, k))),
                "one MonadaMemory.resonate(text).topK(k).threshold(0.0) per eligible query on one warm handle",
                "mapped chunk ids and bit-exact scores equal the verified B1 rows");
        rows.measure("warm.b2c", Optional.of(k), sampling.warmups(), sampling.iterations(), size, null, () -> {
            var all = new ArrayList<List<ResonanceResult>>(size);
            for (var querySignals : signals) {
                var perSignal = new ArrayList<List<ResonanceResult>>(querySignals.size());
                for (var signal : querySignals) {
                    perSignal.add(memory.resonate(codec.encode(signal)).topK(k)
                            .threshold(HtmlKnowledgeRecall.THRESHOLD).execute().results());
                }
                all.add(HtmlKnowledgeRecall.mergeCodecResults(perSignal, k));
            }
            return all;
        }, all -> firstDifference(queries, i -> sameRows(atomRows(manifest, all.get(i)),
                snapshot.rows(queries.get(i).queryId(), Layer.B2C, k))),
                "the Store-side work of one B2 recall per eligible query: one resonate per codec Signal (Signals"
                        + " built before the window, decoded to text inside it) plus the adapter's documented merge",
                "mapped chunk ids and bit-exact scores equal the verified B2c rows");
        var requests = signals.stream().map(querySignals -> new ResonanceMemoryRequest(querySignals, k)).toList();
        rows.measure("warm.b2", Optional.of(k), sampling.warmups(), sampling.iterations(), size, null, () -> {
            var all = new ArrayList<ResonanceMemoryResponse>(size);
            for (var request : requests) {
                all.add(timed.recall(request));
            }
            return all;
        }, all -> firstDifference(queries, i -> referenceRows(manifest, all.get(i),
                snapshot.rows(queries.get(i).queryId(), Layer.B2, k))),
                "one ResonanceStoreMemoryAdapter.recall per eligible query on one warm adapter; requests built"
                        + " before the window",
                "status COMPLETE; mapped references and bit-exact scores equal the verified B2 rows");
    }

    private void measureCycle(Point point, int k, List<QueryText> queries, ResonanceMemoryPort adapter,
            ResonanceMemoryPort timed, IngestionManifest manifest, Snapshot snapshot, Rows rows,
            List<String> violations) {
        var verified = new ArrayList<Run>(queries.size());
        for (var query : queries) {
            var run = cycle.execute(adapter, query.text(), HtmlKnowledgeCycleExperiment.BUDGET, k);
            verified.add(run);
            var where = "N=" + point.size() + " k=" + k + " " + query.queryId() + ": ";
            if (run.failure().isPresent()) {
                violations.add(where + "the cycle failed: " + run.failure().get());
                continue;
            }
            cycle.integrityViolations(run).forEach(violation -> violations.add(where + violation));
            var admitted = run.memory().map(memory -> memory.response().results()).orElse(List.of());
            var mapped = new ArrayList<Row>(admitted.size());
            for (var rank = 0; rank < admitted.size(); rank++) {
                var result = admitted.get(rank);
                mapped.add(new Row(query.queryId(), Layer.B2, k, rank + 1,
                        manifest.chunkIdForReference(result.reference()).orElse(HtmlKnowledgeRecall.UNMAPPED),
                        result.score()));
            }
            sameRows(mapped, snapshot.rows(query.queryId(), Layer.B2, k))
                    .ifPresent(difference -> violations.add(where + "admitted results differ from B2: " + difference));
        }
        rows.measure("warm.cycle", Optional.of(k), sampling.warmups(), sampling.iterations(), queries.size(), null,
                () -> {
                    var runs = new ArrayList<Run>(queries.size());
                    for (var query : queries) {
                        runs.add(cycle.execute(timed, query.text(), HtmlKnowledgeCycleExperiment.BUDGET, k));
                    }
                    return runs;
                }, runs -> firstDifference(queries, i -> HtmlKnowledgeCycleExperiment.sameRun(verified.get(i),
                        runs.get(i)) ? Optional.empty() : Optional.of("the run differs from the verified run")),
                "one fresh DeterministicCognitiveCycle MEMORY_RECALL -> REASONING -> EVALUATION per eligible query"
                        + " over the warm adapter, memory and reasoning limit k; codec Signals built inside",
                "each run equals the verified run of the same query (HtmlKnowledgeCycleExperiment.sameRun)");
    }

    private void measureAnswers(Point point, int k, List<QueryText> queries, List<RecalledEvidence.Recall> b1Evidence,
            RecalledEvidence evidence, MonadaMemory memory, Rows rows) {
        var verified = new ArrayList<Optional<GroundedAnswer>>(queries.size());
        var bound = new ArrayList<List<EvidenceChunk>>(queries.size());
        for (var i = 0; i < queries.size(); i++) {
            var chunks = evidenceAt(b1Evidence.get(i), k);
            var text = queries.get(i).text();
            bound.add(chunks.orElse(List.of()));
            verified.add(chunks.map(set -> answerer.answer(text, set)));
        }
        Function<List<Optional<GroundedAnswer>>, Optional<String>> same = answers -> firstDifference(queries,
                i -> answers.get(i).equals(verified.get(i)) ? Optional.empty()
                        : Optional.of("the answer differs from the verified answer"));
        rows.measure("warm.qa.b3", Optional.of(k), sampling.warmups(), sampling.iterations(), queries.size(), null,
                () -> {
                    var answers = new ArrayList<Optional<GroundedAnswer>>(queries.size());
                    for (var i = 0; i < queries.size(); i++) {
                        answers.add(verified.get(i).isEmpty() ? Optional.empty()
                                : Optional.of(answerer.answer(queries.get(i).text(), bound.get(i))));
                    }
                    return answers;
                }, same,
                "one B3 answer per eligible query over its verified B1 evidence E(q) at answerK k, recall excluded",
                "each answer equals the verified B3 answer");
        rows.measure("warm.qa.b1+b3", Optional.of(k), sampling.warmups(), sampling.iterations(), queries.size(),
                null, () -> {
                    var answers = new ArrayList<Optional<GroundedAnswer>>(queries.size());
                    for (var query : queries) {
                        answers.add(evidenceAt(evidence.direct(memory, query.text()), k)
                                .map(set -> answerer.answer(query.text(), set)));
                    }
                    return answers;
                }, same,
                "per eligible query: the verified B1 evidence recall at limit 5 (Store call, manifest mapping and one"
                        + " SHA-256 per returned chunk), cut to answerK k, plus one B3 answer",
                "each answer equals the verified B3 answer");
    }

    private List<AnswerRow> answerRows(List<JudgedQuery> queries, List<RecalledEvidence.Recall> recalls, Layer layer,
            int k) {
        var rows = new ArrayList<AnswerRow>(queries.size());
        for (var i = 0; i < queries.size(); i++) {
            var query = queries.get(i);
            var chunks = evidenceAt(recalls.get(i), k);
            if (chunks.isEmpty()) {
                var reason = recalls.get(i) instanceof RecalledEvidence.Failed failed ? failed.reason() : "no evidence";
                rows.add(new AnswerRow(query, layer, k, List.of(), Optional.empty(), List.of(), Optional.of(reason)));
                continue;
            }
            var answer = answerer.answer(query.text(), chunks.get());
            var citations = answer instanceof Answered answered ? answered.claims().stream()
                    .map(claim -> claim.citations().stream()
                            .map(citation -> qa.verify(claim.text(), citation, chunks.get())).toList())
                    .toList() : List.<List<CitedSpan>>of();
            rows.add(new AnswerRow(query, layer, k, chunks.get(), Optional.of(answer), citations, Optional.empty()));
        }
        return rows;
    }

    private static AnswerCounts counts(List<AnswerRow> rows) {
        int answeredA = 0, abstainedA = 0, answeredU = 0, abstainedU = 0, citesRelevant = 0, citations = 0;
        int ineligible = 0, exact = 0, failed = 0;
        for (var row : rows) {
            if (row.answer().isEmpty()) {
                failed++;
                continue;
            }
            var relevantSet = row.query().relevant();
            var answerable = !relevantSet.isEmpty();
            if (row.answer().get() instanceof Abstained) {
                abstainedA += answerable ? 1 : 0;
                abstainedU += answerable ? 0 : 1;
                continue;
            }
            answeredA += answerable ? 1 : 0;
            answeredU += answerable ? 0 : 1;
            var relevant = false;
            for (var claim : row.citations()) {
                for (var span : claim) {
                    citations++;
                    ineligible += span.eligible() ? 0 : 1;
                    exact += span.exactQuote() ? 1 : 0;
                    relevant |= span.eligible() && relevantSet.contains(span.citation().chunkId());
                }
            }
            citesRelevant += answerable && relevant ? 1 : 0;
        }
        return new AnswerCounts(answeredA, abstainedA, answeredU, abstainedU, citesRelevant, citations, ineligible,
                exact, failed);
    }

    private static Optional<List<EvidenceChunk>> evidenceAt(RecalledEvidence.Recall recall, int k) {
        return recall instanceof RecalledEvidence.Retrieved retrieved
                ? Optional.of(retrieved.chunks().subList(0, Math.min(k, retrieved.chunks().size())))
                : Optional.empty();
    }

    private static Optional<Double> overhead(Rows rows, int k) {
        var b2 = row(rows, "scaling.warm.b2", k);
        var b2c = row(rows, "scaling.warm.b2c", k);
        if (b2.isEmpty() || b2c.isEmpty()) {
            return Optional.empty();
        }
        var operations = Integer.parseInt(b2.get().diagnostics().get("operationsPerIteration"));
        return Optional.of((b2.get().latency().medianNanos() - b2c.get().latency().medianNanos()) / operations);
    }

    private static Optional<BenchmarkRunResult> row(Rows rows, String name, int k) {
        return rows.measured.stream().filter(row -> row.benchmarkName().equals(name)
                && Integer.toString(k).equals(row.diagnostics().get("k"))).findFirst();
    }

    /** Scores {@code population} into {@code quality} and {@code abstention} and returns its per-query rows. */
    private static List<QueryScore> score(List<JudgedQuery> population, String name, Snapshot snapshot,
            Map<String, LayerAggregate> quality, Map<String, LayerAbstention> abstention) {
        var all = new ArrayList<QueryScore>();
        for (var layer : SCORED_LAYERS) {
            var scores = new ArrayList<QueryScore>(population.size());
            for (var query : population) {
                scores.add(RecallMetrics.layerScore(query, layer,
                        snapshot.rows(query.queryId(), layer, HtmlKnowledgeRecall.MAX_LIMIT),
                        snapshot.rows(query.queryId(), layer, HtmlKnowledgeRecall.LOOKAHEAD_LIMIT)));
            }
            for (var k : KS) {
                quality.put(name + "/" + layer.label() + "/" + k, RecallMetrics.aggregate(layer, k, scores));
            }
            abstention.put(name + "/" + layer.label(), RecallMetrics.abstention(layer, scores));
            all.addAll(scores);
        }
        return all;
    }

    private static List<Row> atomRows(IngestionManifest manifest, List<ResonanceResult> results) {
        var rows = new ArrayList<Row>(results.size());
        for (var rank = 0; rank < results.size(); rank++) {
            var result = results.get(rank);
            rows.add(new Row("", Layer.B1, 0, rank + 1,
                    manifest.chunkIdForAtom(result.atom().id()).orElse(HtmlKnowledgeRecall.UNMAPPED), result.score()));
        }
        return rows;
    }

    private static Optional<String> referenceRows(IngestionManifest manifest, ResonanceMemoryResponse response,
            List<Row> expected) {
        if (response.status() != ResonanceMemoryStatus.COMPLETE) {
            return Optional.of("answered " + response.status());
        }
        var rows = new ArrayList<Row>(response.results().size());
        for (var rank = 0; rank < response.results().size(); rank++) {
            var result = response.results().get(rank);
            rows.add(new Row("", Layer.B2, 0, rank + 1,
                    manifest.chunkIdForReference(result.reference()).orElse(HtmlKnowledgeRecall.UNMAPPED),
                    result.score()));
        }
        return sameRows(rows, expected);
    }

    /** Compares chunk ids, order and bit-exact scores; query id, layer and limit are not compared. */
    static Optional<String> sameRows(List<Row> actual, List<Row> expected) {
        if (actual.size() != expected.size()) {
            return Optional.of(actual.size() + " results, verified " + expected.size());
        }
        for (var i = 0; i < actual.size(); i++) {
            if (!actual.get(i).chunkId().equals(expected.get(i).chunkId())
                    || !HtmlKnowledgeRecall.sameScore(actual.get(i).score(), expected.get(i).score())) {
                return Optional.of("rank " + (i + 1) + " is " + actual.get(i).chunkId() + ", verified "
                        + expected.get(i).chunkId());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstDifference(List<QueryText> queries,
            Function<Integer, Optional<String>> difference) {
        for (var i = 0; i < queries.size(); i++) {
            var found = difference.apply(i);
            if (found.isPresent()) {
                return Optional.of(queries.get(i).queryId() + ": " + found.get());
            }
        }
        return Optional.empty();
    }

    private static Footprint footprint(Path root, int persistedAtoms) {
        try (Stream<Path> paths = Files.walk(root)) {
            var files = paths.filter(Files::isRegularFile).toList();
            var bytes = 0L;
            for (var file : files) {
                bytes += Files.size(file);
            }
            return new Footprint(persistedAtoms, bytes, files.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<QueryText> texts(List<JudgedQuery> queries) {
        return queries.stream().map(query -> new QueryText(query.queryId(), query.text())).toList();
    }

    private static Check prefixed(String prefix, Check check) {
        return new Check(prefix + check.name(), check.passed(), check.detail());
    }

    private static String head(List<String> values) {
        return String.join("; ", values.subList(0, Math.min(5, values.size()))) + (values.size() > 5 ? "; ..." : "");
    }
}
