package monada.neuron.evaluation.integration;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.evaluation.integration.ResonanceStoreFixtureCorpus.Query;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.workload.DeterministicActionFixture;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.CognitiveCycleSetup;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryStageResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterException;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.ResonanceThresholdRoutingPolicy;
import monada.neuron.signal.Signal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * End-to-end evaluation of a Primary Monad cycle recalling from a real, isolated Resonance Store.
 *
 * <p>Correctness verdicts ({@link Check}) are semantic and deterministic. Latency, allocation, and GC
 * numbers are exploratory diagnostics and never influence a verdict. Store construction and seeding
 * are measured outside every recall and cycle window.
 */
public final class ResonanceStoreIntegrationEvaluation {

    /** One named semantic verdict. */
    public record Check(String name, boolean passed, String detail) {
    }

    /** Complete evaluation output. */
    public record Outcome(List<Check> checks, List<BenchmarkRunResult> results, Map<String, String> metadata) {
        public Outcome {
            checks = List.copyOf(checks);
            results = List.copyOf(results);
            metadata = Map.copyOf(metadata);
        }

        /** Returns whether every semantic check passed. */
        public boolean allPassed() {
            return checks.stream().allMatch(Check::passed);
        }
    }

    private interface Verification {
        String verify() throws Exception;
    }

    private static final List<CognitiveStageKind> FULL_CYCLE_ORDER = List.of(
            CognitiveStageKind.PERCEPTION,
            CognitiveStageKind.MEMORY_RECALL,
            CognitiveStageKind.REASONING,
            CognitiveStageKind.ADAPTATION,
            CognitiveStageKind.ACTION);
    private static final int MEMORY_STAGE_LIMIT = 5;
    private static final CognitiveBudget FULL_CYCLE_BUDGET = new CognitiveBudget(20_000, 20_000, 40_000);
    // The default route-all bound truncates perception on the standard topologies and ends the cycle
    // before memory recall. Threshold routing lets both Aeon stages finish so every stage executes.
    private static final PropagationConfig PROPAGATION = new PropagationConfig(
            2_000, 8, new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 0.5));
    private static final CognitiveBudget ROOMY_BUDGET = new CognitiveBudget(100, 100, 200);

    private final long seed;
    private final boolean quickMode;
    private final FixtureSignalCodec codec = new FixtureSignalCodec();
    private final EvaluationMetricsCollector collector = new EvaluationMetricsCollector();
    private final DeterministicWorkloadGenerator generator;
    private final List<Check> checks = new ArrayList<>();
    private final List<BenchmarkRunResult> results = new ArrayList<>();
    private final Map<String, String> metadata = new TreeMap<>();

    public ResonanceStoreIntegrationEvaluation(long seed, boolean quickMode) {
        this.seed = seed;
        this.quickMode = quickMode;
        this.generator = new DeterministicWorkloadGenerator(seed);
    }

    /** Runs all semantic checks and measurements over fresh temporary stores. */
    public Outcome run() {
        TemporaryResonanceStore primary = null;
        try {
            primary = TemporaryResonanceStore.seeded(codec);
            var adapter = primary.openAdapter();
            recordFixtureMetadata(primary);

            recallChecks(adapter);
            memoryOnlyCycleChecks(adapter);
            fullCycleChecks(adapter);
            failureChecks();

            measureSetup(primary);
            measureRecall(primary, adapter);
            measureCycles(adapter);
        } finally {
            if (primary != null) {
                primary.close();
                var deleted = !primary.exists();
                checks.add(new Check("temporary-store-cleanup", deleted,
                        deleted ? "primary store directory deleted" : "directory still exists"));
            }
        }
        return new Outcome(checks, results, metadata);
    }

    // ---------------------------------------------------------------- semantic checks

    private void recallChecks(ResonanceStoreMemoryAdapter adapter) {
        for (var query : ResonanceStoreFixtureCorpus.queries()) {
            check("recall." + query.id(), () -> verifyQuery(adapter, query));
        }
        check("recall.bounded-prefix-stability", () -> {
            var wide = adapter.recall(request(List.of(ResonanceStoreFixtureCorpus.query("solar-energy").signal()), 3));
            var narrow = adapter.recall(request(List.of(ResonanceStoreFixtureCorpus.query("solar-energy").signal()), 1));
            require(narrow.results().size() == 1, "limit 1 must return exactly one result");
            require(narrow.results().getFirst().equals(wide.results().getFirst()),
                    "limit 1 result must equal the head of the limit 3 result");
            return "limit 1 head equals limit 3 head";
        });
        check("recall.multi-signal-batch", () -> {
            var ocean = ResonanceStoreFixtureCorpus.query("ocean-tide").signal();
            var garden = ResonanceStoreFixtureCorpus.query("garden-compost").signal();
            var response = adapter.recall(request(List.of(ocean, garden), 3));
            requireInvariants(response, 3);
            var labels = labels(response);
            require(labels.contains("ocean-tide") && labels.contains("garden-soil"),
                    "batch must retain the best hit of each query signal, got " + labels);
            return "labels=" + labels;
        });
        check("recall.deterministic-repeat", () -> {
            var signals = List.of(ResonanceStoreFixtureCorpus.query("solar-energy").signal(),
                    ResonanceStoreFixtureCorpus.query("ocean-tide").signal());
            var first = adapter.recall(request(signals, 4));
            var second = adapter.recall(request(signals, 4));
            require(first.equals(second), "repeated recall must be identical");
            return "identical over " + first.results().size() + " results";
        });
    }

    private String verifyQuery(ResonanceStoreMemoryAdapter adapter, Query query) {
        var response = adapter.recall(request(List.of(query.signal()), query.maxResults()));
        requireInvariants(response, query.maxResults());
        var labels = labels(response);
        var expected = query.expectedTopLabels();
        if (query.exact()) {
            if (query.ordered()) {
                require(labels.equals(expected), "expected " + expected + " but got " + labels);
            } else {
                require(new HashSet<>(labels).equals(new HashSet<>(expected)) && labels.size() == expected.size(),
                        "expected set " + expected + " but got " + labels);
            }
        } else if (query.ordered()) {
            require(labels.size() >= expected.size() && labels.subList(0, expected.size()).equals(expected),
                    "expected prefix " + expected + " but got " + labels);
        } else {
            require(labels.containsAll(expected), "expected members " + expected + " but got " + labels);
        }
        return "labels=" + labels;
    }

    private void memoryOnlyCycleChecks(ResonanceStoreMemoryAdapter adapter) {
        var inputs = List.of(
                ResonanceStoreFixtureCorpus.query("ocean-tide").signal(),
                ResonanceStoreFixtureCorpus.query("garden-compost").signal());
        var monadId = new UUID(seed, 0xCAFEBABEL);
        var cycle = new DeterministicCognitiveCycle(List.of(new ResonanceMemoryCognitiveStage(adapter, 3)));

        check("cycle.memory-only.preserves-input-order-then-recalled", () -> {
            var result = cycle.execute(new PrimaryMonad(monadId), inputs, ROOMY_BUDGET);
            require(result.termination() == CognitiveCycleTermination.COMPLETED, "termination " + result.termination());
            require(result.stageResults().size() == 1, "one stage result expected");
            var stage = memoryStage(result);
            require(stage.response().status() == ResonanceMemoryStatus.COMPLETE, "status " + stage.response().status());
            var outputs = stage.outputSignals();
            require(outputs.subList(0, inputs.size()).equals(inputs), "inputs must lead the outputs in order");
            var recalled = stage.response().results().stream().map(r -> r.signal()).toList();
            require(outputs.subList(inputs.size(), outputs.size()).equals(recalled),
                    "recalled signals must follow inputs in adapter order");
            require(result.outputSignals().equals(outputs), "cycle output must equal the memory stage output");
            return "inputs=" + inputs.size() + " recalled=" + recalled.size();
        });
        check("cycle.memory-only.deterministic-replay", () -> {
            var first = cycle.execute(new PrimaryMonad(monadId), inputs, ROOMY_BUDGET);
            var second = cycle.execute(new PrimaryMonad(monadId), inputs, ROOMY_BUDGET);
            require(first.equals(second), "replayed cycle results must be equal");
            return "equal";
        });
        check("cycle.memory-only.signal-budget-truncates-recalled-prefix", () -> {
            var single = List.of(ResonanceStoreFixtureCorpus.query("solar-energy").signal());
            var unbounded = memoryStage(cycle.execute(new PrimaryMonad(monadId), single, ROOMY_BUDGET));
            // Budget: one admitted input, one forwarded input, one recalled Signal.
            var tight = cycle.execute(new PrimaryMonad(monadId), single, new CognitiveBudget(10, 3, 10));
            require(tight.termination() == CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED,
                    "termination " + tight.termination());
            var truncated = memoryStage(tight);
            require(truncated.response().results().size() == 1, "expected one admitted recalled result, got "
                    + truncated.response().results().size());
            require(truncated.response().results().getFirst().equals(unbounded.response().results().getFirst()),
                    "admitted result must be the head of the unbounded response");
            return "admitted 1 of " + unbounded.response().results().size();
        });
    }

    private void fullCycleChecks(ResonanceStoreMemoryAdapter adapter) {
        var inputs = fullCycleInputs();
        check("cycle.full.stage-order-and-budget", () -> {
            var result = executeFullCycle(adapter, inputs);
            var kinds = result.stageResults().stream().map(stage -> stage.kind()).toList();
            require(kinds.equals(FULL_CYCLE_ORDER), "stage order " + kinds);
            require(result.termination() == CognitiveCycleTermination.COMPLETED, "termination " + result.termination());
            var snapshot = result.snapshot();
            require(!snapshot.stepBudgetExhausted() && !snapshot.signalBudgetExhausted(), "budget must not be exhausted");
            var memory = memoryStage(result);
            require(memory.response().status() == ResonanceMemoryStatus.COMPLETE, "status " + memory.response().status());
            require(!memory.response().results().isEmpty(), "real store must recall at least one fixture document");
            require(memory.response().results().size() <= MEMORY_STAGE_LIMIT, "recall exceeded stage limit");
            memory.response().results().forEach(r -> require(codec.labelOf(r.signal()).isPresent(),
                    "recalled signal must decode to a fixture document"));
            return "stages=" + kinds + " steps=" + snapshot.processedSteps() + " signals=" + snapshot.acceptedSignals();
        });
        check("cycle.full.deterministic-replay", () -> {
            var first = executeFullCycle(adapter, inputs);
            var second = executeFullCycle(adapter, inputs);
            require(first.equals(second), "independent replays over the same store must be equal");
            return "equal";
        });
        check("cycle.full.replayed-response-equals-real", () -> {
            var recorder = new ReplayMemoryPort.Recorder(adapter);
            var real = executeFullCycle(recorder, inputs);
            var replayed = executeFullCycle(recorder.replay(), inputs);
            require(real.equals(replayed), "cycle over replayed responses must equal the real cycle result");
            return "identical cycle result with no-I/O replay of the recorded store responses";
        });
    }

    private void failureChecks() {
        var probe = List.of(ResonanceStoreFixtureCorpus.query("solar-energy").signal());
        check("failure.closed-adapter-unavailable", () -> {
            try (var store = TemporaryResonanceStore.seeded(codec)) {
                var adapter = store.openAdapter();
                adapter.close();
                var response = adapter.recall(request(probe, 2));
                require(response.status() == ResonanceMemoryStatus.UNAVAILABLE, "status " + response.status());
                require(response.results().isEmpty() && response.resultLimit() == 2, "empty response echoing limit");
                var stage = memoryStage(memoryOnlyCycle(adapter).execute(new PrimaryMonad(new UUID(seed, 1L)), probe, ROOMY_BUDGET));
                require(stage.response().status() == ResonanceMemoryStatus.UNAVAILABLE, "cycle must surface UNAVAILABLE");
                require(stage.outputSignals().equals(probe), "unavailable memory must forward inputs unchanged");
                return "UNAVAILABLE, inputs forwarded";
            }
        });
        check("failure.storage-loss-failed", () -> {
            try (var store = TemporaryResonanceStore.seeded(codec)) {
                var adapter = store.openAdapter();
                store.deleteVectorSegments();
                var response = adapter.recall(request(probe, 2));
                require(response.status() == ResonanceMemoryStatus.FAILED, "status " + response.status());
                require(response.results().isEmpty(), "failed recall must carry no results");
                var result = memoryOnlyCycle(adapter).execute(new PrimaryMonad(new UUID(seed, 2L)), probe, ROOMY_BUDGET);
                require(memoryStage(result).response().status() == ResonanceMemoryStatus.FAILED,
                        "cycle must surface FAILED without throwing");
                return "FAILED, no exception reached the cycle";
            }
        });
        check("failure.open-failure-translated", () -> {
            try (var store = TemporaryResonanceStore.empty(codec)) {
                var notADirectory = store.path().resolve("not-a-directory");
                Files.writeString(notADirectory, "x");
                var thrown = expectOpenFailure(notADirectory, store);
                return "non-directory path -> " + thrown.getClass().getSimpleName();
            }
        });
        check("failure.incompatible-manifest-translated", () -> {
            try (var store = TemporaryResonanceStore.empty(codec)) {
                Files.writeString(store.path().resolve("manifest.json"), "{ \"version\": \"99.0\" }");
                var thrown = expectOpenFailure(store.path(), store);
                return "unsupported manifest -> " + thrown.getClass().getSimpleName();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private ResonanceStoreAdapterException expectOpenFailure(Path path, TemporaryResonanceStore store) {
        try {
            ResonanceStoreMemoryAdapter.open(path, TemporaryResonanceStore.adapterConfig(codec));
        } catch (ResonanceStoreAdapterException expected) {
            return expected;
        } catch (RuntimeException leaked) {
            throw new IllegalStateException("open failure leaked " + leaked.getClass().getName(), leaked);
        }
        throw new IllegalStateException("open unexpectedly succeeded for " + store.path().getFileName());
    }

    // ---------------------------------------------------------------- measurements


    private BenchmarkRunResult measure(
            String name,
            String scale,
            int warmups,
            int iterations,
            int operations,
            Runnable workload,
            Map<String, String> diagnostics) {
        return measure(name, scale, warmups, iterations, operations, null, workload, diagnostics);
    }

    private BenchmarkRunResult measure(
            String name,
            String scale,
            int warmups,
            int iterations,
            int operations,
            Runnable iterationSetup,
            Runnable workload,
            Map<String, String> diagnostics) {
        var annotated = new TreeMap<>(diagnostics);
        annotated.put("warmupIterations", String.valueOf(warmups));
        annotated.put("measurementIterations", String.valueOf(iterations));
        return collector.measure(name, scale, warmups, iterations, operations, iterationSetup, workload, annotated);
    }

    private void measureSetup(TemporaryResonanceStore primary) {
        int warmups = quickMode ? 1 : 2;
        int iterations = quickMode ? 3 : 10;
        var created = new TemporaryResonanceStore[1];
        results.add(measure(
                "ResonanceStore.SeedFixtureCorpus",
                ResonanceStoreFixtureCorpus.documents().size() + " documents",
                warmups, iterations, 1,
                () -> closeStore(created[0]),
                () -> created[0] = TemporaryResonanceStore.seeded(codec),
                Map.of("phase", "setup",
                        "measures", "temp directory creation, store open, and remembering every document",
                        "fixtureVersion", ResonanceStoreFixtureCorpus.VERSION,
                        "primaryStoreSeedNanos", String.valueOf(primary.seedNanos()))));
        closeStore(created[0]);

        var opened = new ResonanceStoreMemoryAdapter[1];
        results.add(measure(
                "ResonanceStore.OpenExistingStore",
                ResonanceStoreFixtureCorpus.documents().size() + " documents",
                warmups, iterations, 1,
                () -> releaseAdapter(primary, opened[0]),
                () -> opened[0] = primary.openAdapter(),
                Map.of("phase", "setup", "measures", "adapter open on a persisted store")));
        releaseAdapter(primary, opened[0]);
    }

    private static void closeStore(TemporaryResonanceStore store) {
        if (store != null) {
            store.close();
        }
    }

    private static void releaseAdapter(TemporaryResonanceStore store, ResonanceStoreMemoryAdapter adapter) {
        if (adapter != null) {
            store.release(adapter);
        }
    }

    private void measureRecall(TemporaryResonanceStore store, ResonanceStoreMemoryAdapter warmAdapter) {
        int warmups = quickMode ? 3 : 10;
        int iterations = quickMode ? 10 : 50;
        var signals = probeSignals();
        var request = request(signals, MEMORY_STAGE_LIMIT);
        var sample = warmAdapter.recall(request);
        var adapterHolder = new ResonanceStoreMemoryAdapter[1];

        results.add(measure(
                "ResonanceStore.FirstRecall.FreshlyOpenedAdapter",
                signals.size() + " query signals, limit " + MEMORY_STAGE_LIMIT,
                0, iterations, 1,
                () -> {
                    releaseAdapter(store, adapterHolder[0]);
                    adapterHolder[0] = store.openAdapter();
                },
                () -> requireComplete(adapterHolder[0].recall(request)),
                recallDiagnostics("first recall on an adapter opened in the untimed iteration setup", sample)));
        releaseAdapter(store, adapterHolder[0]);

        results.add(measure(
                "ResonanceStore.Recall.Warm",
                signals.size() + " query signals, limit " + MEMORY_STAGE_LIMIT,
                warmups, iterations, 1,
                () -> requireComplete(warmAdapter.recall(request)),
                recallDiagnostics("repeated recall on one open adapter", sample)));

        var stage = new ResonanceMemoryCognitiveStage(warmAdapter, MEMORY_STAGE_LIMIT);
        var monad = new PrimaryMonad(new UUID(seed, 0xCAFEBABEL));
        results.add(measure(
                "NeuronStage.MemoryRecall.Warm",
                signals.size() + " input signals, limit " + MEMORY_STAGE_LIMIT,
                warmups, iterations, 1,
                () -> {
                    var stageResult = stage.execute(monad, signals, new CognitiveContext(ROOMY_BUDGET));
                    if (stageResult.outputSignals().size() < signals.size()) {
                        throw new IllegalStateException("memory stage dropped input signals");
                    }
                },
                recallDiagnostics("ResonanceMemoryCognitiveStage over the real adapter", sample)));
    }

    private void measureCycles(ResonanceStoreMemoryAdapter adapter) {
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 15;
        var inputs = fullCycleInputs();
        var recorder = new ReplayMemoryPort.Recorder(adapter);
        var sample = executeFullCycle(recorder, inputs);
        var sampleMemory = memoryStage(sample);
        var diagnostics = new TreeMap<String, String>();
        diagnostics.put("stages", String.join(" -> ", sample.stageResults().stream().map(s -> s.kind().name()).toList()));
        diagnostics.put("initialSignalCount", String.valueOf(inputs.size()));
        diagnostics.put("memoryStageInputSignals", String.valueOf(sampleMemory.outputSignals().size()
                - sampleMemory.response().results().size()));
        diagnostics.put("memoryStageResultLimit", String.valueOf(MEMORY_STAGE_LIMIT));
        diagnostics.put("memoryStageRecalledResults", String.valueOf(sampleMemory.response().results().size()));
        diagnostics.put("memoryStageStatus", sampleMemory.response().status().name());
        diagnostics.put("termination", sample.termination().name());
        diagnostics.put("budgetMaxSteps", String.valueOf(FULL_CYCLE_BUDGET.maxSteps()));
        diagnostics.put("budgetMaxSignals", String.valueOf(FULL_CYCLE_BUDGET.maxSignals()));
        diagnostics.put("processedSteps", String.valueOf(sample.snapshot().processedSteps()));
        diagnostics.put("acceptedSignals", String.valueOf(sample.snapshot().acceptedSignals()));
        diagnostics.put("stateResetPerIteration", "true");

        results.add(measureCycle("NeuronCycle.FullCycle.RealResonanceStore", adapter, inputs, warmups, iterations,
                withMemory(diagnostics, "ResonanceStoreMemoryAdapter (embedded, real store)")));

        var replay = recorder.replay();
        var replayed = executeFullCycle(replay, inputs);
        if (!replayed.equals(sample)) {
            throw new IllegalStateException("replayed memory responses diverged from the real cycle result");
        }
        results.add(measureCycle("NeuronCycle.FullCycle.ReplayedMemoryResponse", replay, inputs, warmups, iterations,
                withMemory(diagnostics, "ReplayMemoryPort (no I/O; responses recorded from the real store)")));
    }

    private BenchmarkRunResult measureCycle(
            String name,
            ResonanceMemoryPort port,
            List<Signal> inputs,
            int warmups,
            int iterations,
            Map<String, String> diagnostics) {
        var holder = new CognitiveCycleSetup[1];
        return measure(
                name,
                "5 stages, " + inputs.size() + " initial signals",
                warmups, iterations, 1,
                () -> holder[0] = newFullCycleSetup(port),
                () -> {
                    var setup = holder[0];
                    var result = setup.cycle().execute(setup.monad(), inputs, FULL_CYCLE_BUDGET);
                    if (result.snapshot().traceEntries().isEmpty()) {
                        throw new IllegalStateException("empty cycle snapshot");
                    }
                },
                diagnostics);
    }

    private static Map<String, String> withMemory(Map<String, String> base, String memory) {
        var copy = new TreeMap<>(base);
        copy.put("memoryBackend", memory);
        return copy;
    }

    private static Map<String, String> recallDiagnostics(String measures, ResonanceMemoryResponse sample) {
        return Map.of(
                "phase", "recall",
                "measures", measures,
                "resultLimit", String.valueOf(sample.resultLimit()),
                "resultCount", String.valueOf(sample.results().size()),
                "status", sample.status().name());
    }

    // ---------------------------------------------------------------- helpers

    private CognitiveCycleSetup newFullCycleSetup(ResonanceMemoryPort port) {
        var policy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);
        return generator.generateFullCycleSetup(
                generator.generateGraph(50, 3),
                generator.generateGraph(50, 3),
                policy,
                port,
                new DeterministicActionFixture(),
                PROPAGATION);
    }

    private CognitiveCycleResult executeFullCycle(ResonanceMemoryPort port, List<Signal> inputs) {
        var setup = newFullCycleSetup(port);
        return setup.cycle().execute(setup.monad(), inputs, FULL_CYCLE_BUDGET);
    }

    private static DeterministicCognitiveCycle memoryOnlyCycle(ResonanceMemoryPort port) {
        return new DeterministicCognitiveCycle(List.of(new ResonanceMemoryCognitiveStage(port, 3)));
    }

    private List<Signal> fullCycleInputs() {
        return List.of(
                ResonanceStoreFixtureCorpus.query("solar-energy").signal(),
                ResonanceStoreFixtureCorpus.query("ocean-tide").signal());
    }

    private List<Signal> probeSignals() {
        return List.of(
                ResonanceStoreFixtureCorpus.query("solar-energy").signal(),
                ResonanceStoreFixtureCorpus.query("ocean-tide").signal(),
                ResonanceStoreFixtureCorpus.query("garden-compost").signal());
    }

    private static ResonanceMemoryRequest request(List<Signal> signals, int limit) {
        return new ResonanceMemoryRequest(signals, limit);
    }

    private static ResonanceMemoryStageResult memoryStage(CognitiveCycleResult result) {
        for (var stage : result.stageResults()) {
            if (stage instanceof ResonanceMemoryStageResult memory) {
                return memory;
            }
        }
        throw new IllegalStateException("cycle has no memory-recall stage result");
    }

    private List<String> labels(ResonanceMemoryResponse response) {
        return response.results().stream()
                .map(result -> codec.labelOf(result.signal()).orElse("?"))
                .toList();
    }

    private void requireInvariants(ResonanceMemoryResponse response, int limit) {
        require(response.status() == ResonanceMemoryStatus.COMPLETE, "status " + response.status());
        require(response.resultLimit() == limit, "resultLimit " + response.resultLimit());
        require(response.results().size() <= limit, "more results than limit");
        var references = new HashSet<String>();
        double previous = Double.POSITIVE_INFINITY;
        for (var result : response.results()) {
            require(Double.isFinite(result.score()), "non-finite score");
            require(result.score() <= previous, "scores must be non-increasing");
            previous = result.score();
            require(references.add(result.reference()), "duplicate reference");
            require(codec.labelOf(result.signal()).isPresent(), "unknown decoded signal");
        }
    }

    private static void requireComplete(ResonanceMemoryResponse response) {
        if (response.status() != ResonanceMemoryStatus.COMPLETE) {
            throw new IllegalStateException("recall not complete: " + response.status());
        }
    }

    private void recordFixtureMetadata(TemporaryResonanceStore store) {
        metadata.put("fixtureVersion", ResonanceStoreFixtureCorpus.VERSION);
        metadata.put("fixtureDocumentCount", String.valueOf(ResonanceStoreFixtureCorpus.documents().size()));
        metadata.put("fixtureQueryCount", String.valueOf(ResonanceStoreFixtureCorpus.queries().size()));
        metadata.put("adapterScoreThreshold", String.valueOf(TemporaryResonanceStore.FIXTURE_THRESHOLD));
        metadata.put("memoryStageResultLimit", String.valueOf(MEMORY_STAGE_LIMIT));
        metadata.put("signalCodec", "FixtureSignalCodec (query text keyed by Signal frequency)");
        metadata.put("storeIsolation", "fresh temporary directory per run, deleted on close");
        StoreMetadata.manifestFields(store.manifestJson()).forEach((key, value) -> metadata.put("store.manifest." + key, value));
    }

    private void check(String name, Verification verification) {
        try {
            checks.add(new Check(name, true, verification.verify()));
        } catch (Exception | AssertionError failure) {
            checks.add(new Check(name, false, failure.getClass().getSimpleName() + ": " + failure.getMessage()));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
