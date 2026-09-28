package monada.neuron.evaluation.baseline;

import monada.neuron.action.ActionCapability;
import monada.neuron.aeon.AeonCoordinationResult;
import monada.neuron.aeon.AeonParallelEligibility;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.BoundedParallelAeonCoordinator;
import monada.neuron.aeon.CognitiveAeonCoordinator;
import monada.neuron.aeon.ContextualParallelism;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.workload.DeterministicActionFixture;
import monada.neuron.evaluation.workload.DeterministicMemoryFixture;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.CognitiveCycleSetup;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.resonance.FrequencyStateBatch;
import monada.neuron.resonance.ScalarBatchResonanceEvaluator;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.resonance.VectorBatchResonanceEvaluator;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.graph.CompactSignalPropagationEngine;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.ResonanceThresholdRoutingPolicy;
import monada.neuron.runtime.selection.RuntimeBackendSelector;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

/**
 * Main baseline runner that executes deterministic, reproducible cognitive workloads
 * and produces structured JSON and Markdown diagnostic reports.
 */
public final class CognitiveBaselineRunner {

    private final DeterministicWorkloadGenerator generator;
    private final EvaluationMetricsCollector collector;
    private final long seed;
    private final boolean quickMode;

    public CognitiveBaselineRunner() {
        this(DeterministicWorkloadGenerator.DEFAULT_SEED, false);
    }

    public CognitiveBaselineRunner(long seed, boolean quickMode) {
        this.seed = seed;
        this.generator = new DeterministicWorkloadGenerator(seed);
        this.collector = new EvaluationMetricsCollector();
        this.quickMode = quickMode;
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        boolean quick = false;
        long seed = DeterministicWorkloadGenerator.DEFAULT_SEED;
        Path outputDir = Path.of("build/reports/benchmarks");

        for (int i = 0; i < args.length; i++) {
            if ("--quick".equals(args[i])) {
                quick = true;
            } else if ("--seed".equals(args[i]) && i + 1 < args.length) {
                seed = Long.parseLong(args[++i]);
            } else if ("--output-dir".equals(args[i]) && i + 1 < args.length) {
                outputDir = Path.of(args[++i]);
            }
        }

        var runner = new CognitiveBaselineRunner(seed, quick);
        var report = runner.runBaselineSuite();

        System.out.println(report.toMarkdown());

        try {
            Files.createDirectories(outputDir);
            Path jsonPath = outputDir.resolve("cognitive-baseline.json");
            Path mdPath = outputDir.resolve("cognitive-baseline.md");
            Files.writeString(jsonPath, report.toJson());
            Files.writeString(mdPath, report.toMarkdown());
            System.out.println("Benchmark reports saved to:\n  " + jsonPath.toAbsolutePath() + "\n  " + mdPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write benchmark reports: " + e.getMessage());
        }
    }

    /** Executes the entire baseline suite and returns the aggregated report. */
    public EvaluationReport runBaselineSuite() {
        var results = new ArrayList<BenchmarkRunResult>();

        // 1. Scalar Resonance Batches
        results.addAll(benchmarkScalarResonance());

        // 1b. Batch Resonance (Scalar vs Vector SIMD on Contiguous SoA)
        results.addAll(benchmarkBatchResonance());

        // 2. Sparse Graph Propagation
        results.addAll(benchmarkGraphPropagation());

        // 3. Aeon Coordination (Direct vs Contextual with Workload Equivalence Guarantee)
        results.addAll(benchmarkAeonCoordination());

        // 4. Primary Monad Cognitive Cycles (with Per-Iteration State Isolation)
        results.addAll(benchmarkCognitiveCycles());

        // 5. Adaptation Policy Comparison (with Per-Iteration State Isolation)
        results.addAll(benchmarkAdaptationComparison());

        // 6. Isolated object / heap-SoA / FFM state-layout comparison
        results.addAll(new NodeStateLayoutExperiment(seed, quickMode, collector).run());

        // 7. Runtime Backend Selection Control-Plane Diagnostics
        results.addAll(benchmarkBackendSelection());

        var runConfig = quickMode
                ? RunConfiguration.defaultQuick(seed)
                : RunConfiguration.defaultFull(seed);

        return new EvaluationReport(
                Instant.now(),
                EnvironmentMetadata.current(),
                runConfig,
                results);
    }

    private List<BenchmarkRunResult> benchmarkScalarResonance() {
        var metric = new ScalarResonanceMetric();
        int[] scales = quickMode ? new int[]{100, 1000} : new int[]{100, 1_000, 10_000, 100_000};
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 20;

        var results = new ArrayList<BenchmarkRunResult>(scales.length);
        for (int scale : scales) {
            var pairs = generator.generateFrequencyStatePairs(scale);
            var result = collector.measure(
                    "ScalarResonanceMetric.score",
                    scale + " pairs",
                    warmups,
                    iterations,
                    scale,
                    () -> {
                        double sum = 0.0;
                        for (var pair : pairs) {
                            sum += metric.score(pair.first(), pair.second());
                        }
                        if (Double.isNaN(sum)) {
                            throw new IllegalStateException("NaN score encountered");
                        }
                    },
                    Map.of(
                            "pairCount", String.valueOf(scale),
                            "formula", "amplitudeSimilarity * frequencySimilarity * phaseSimilarity"));
            results.add(result);
        }
        return results;
    }

    private List<BenchmarkRunResult> benchmarkBatchResonance() {
        var scalarEvaluator = ScalarBatchResonanceEvaluator.INSTANCE;
        var vectorEvaluator = VectorBatchResonanceEvaluator.INSTANCE;
        int[] scales = quickMode ? new int[]{100, 1000} : new int[]{100, 1_000, 10_000, 100_000};
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 20;

        var results = new ArrayList<BenchmarkRunResult>(scales.length * 2);
        for (int scale : scales) {
            var batchPair = generator.generateFrequencyStateBatches(scale);
            var resultsBuffer = new double[scale];

            // 1. Scalar Batch on Contiguous SoA
            results.add(collector.measure(
                    "ScalarBatchResonance.SoA",
                    scale + " pairs",
                    warmups,
                    iterations,
                    scale,
                    () -> {
                        scalarEvaluator.scoreBatch(batchPair.first(), batchPair.second(), resultsBuffer, 0, scale);
                        if (Double.isNaN(resultsBuffer[0])) {
                            throw new IllegalStateException("NaN score encountered");
                        }
                    },
                    Map.of(
                            "backend", "ScalarBatchResonanceEvaluator",
                            "layout", "Structure-of-Arrays (SoA)",
                            "pairCount", String.valueOf(scale))));

            // 2. Vector SIMD Batch on Contiguous SoA
            results.add(collector.measure(
                    "VectorBatchResonance.SoA",
                    scale + " pairs",
                    warmups,
                    iterations,
                    scale,
                    () -> {
                        vectorEvaluator.scoreBatch(batchPair.first(), batchPair.second(), resultsBuffer, 0, scale);
                        if (Double.isNaN(resultsBuffer[0])) {
                            throw new IllegalStateException("NaN score encountered");
                        }
                    },
                    Map.of(
                            "backend", "VectorBatchResonanceEvaluator",
                            "layout", "Structure-of-Arrays (SoA)",
                            "pairCount", String.valueOf(scale),
                            "vectorApiAvailable", String.valueOf(vectorEvaluator.isAvailable()),
                            "vectorWidth", String.valueOf(vectorEvaluator.vectorWidth()),
                            "vectorSpecies", String.valueOf(vectorEvaluator.species()))));
        }
        return results;
    }

    private List<BenchmarkRunResult> benchmarkGraphPropagation() {
        var engine = new DeterministicSignalPropagationEngine();
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 15;

        record GraphConfig(String name, int nodes, int degree, int maxSteps, int maxHops) {}
        GraphConfig[] configs = quickMode
                ? new GraphConfig[]{new GraphConfig("Small", 50, 3, 200, 5)}
                : new GraphConfig[]{
                        new GraphConfig("Small", 50, 3, 200, 5),
                        new GraphConfig("Medium", 500, 5, 2_000, 8),
                        new GraphConfig("Large", 2_000, 8, 10_000, 10)};

        var results = new ArrayList<BenchmarkRunResult>();
        NodeProcessor processor = (node, input) -> {
            var emitted = new Signal(
                    SignalKind.INTERMEDIATE,
                    input.frequencyState());
            return new NodeProcessingResult(List.of(emitted));
        };

        var initialSignal = generator.generateSignals(1).getFirst();
        var resonancePolicy = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 0.5);

        for (var config : configs) {
            var topology = generator.generateGraph(config.nodes(), config.degree());
            var compactSnapshot = CompactGraphSnapshot.compile(topology.nodes());
            var compactEngine = new CompactSignalPropagationEngine(compactSnapshot);
            var routeAllConfig = PropagationConfig.routeAll(config.maxSteps(), config.maxHops());
            var thresholdConfig = new PropagationConfig(config.maxSteps(), config.maxHops(), resonancePolicy);

            // Sample propagation runs to record exact work volume diagnostics
            var sampleRouteAll = engine.propagate(topology.entryNode(), initialSignal, processor, routeAllConfig);
            var sampleThreshold = engine.propagate(topology.entryNode(), initialSignal, processor, thresholdConfig);
            var compactRouteAll = compactEngine.propagate(
                    topology.entryNode(), initialSignal, processor, routeAllConfig);
            var compactThreshold = compactEngine.propagate(
                    topology.entryNode(), initialSignal, processor, thresholdConfig);
            if (!sampleRouteAll.equals(compactRouteAll) || !sampleThreshold.equals(compactThreshold)) {
                throw new IllegalStateException("compact graph propagation diverged from reference result");
            }

            long estimatedRetainedBytes = DeterministicWorkloadGenerator.estimateRetainedHeapBytes(
                    config.nodes(),
                    topology.totalEdges());
            long compactSnapshotEstimatedBytes =
                    DeterministicWorkloadGenerator.estimateCompactSnapshotHeapBytes(
                            config.nodes(), topology.totalEdges());
            long combinedEstimatedBytes = Math.addExact(
                    estimatedRetainedBytes, compactSnapshotEstimatedBytes);

            // Unbounded (RouteAll) Policy
            results.add(collector.measure(
                    "GraphPropagation.RouteAll",
                    config.name() + " (" + config.nodes() + " nodes, deg " + config.degree() + ")",
                    warmups,
                    iterations,
                    () -> {
                        var res = engine.propagate(
                                topology.entryNode(),
                                initialSignal,
                                processor,
                                routeAllConfig);
                        if (res.emittedSignals().isEmpty()) {
                            throw new IllegalStateException("empty propagation");
                        }
                    },

                    Map.of(
                            "nodeCount", String.valueOf(config.nodes()),
                            "totalEdges", String.valueOf(topology.totalEdges()),
                            "estimatedRetainedBytes", String.valueOf(estimatedRetainedBytes),
                            "maxSteps", String.valueOf(config.maxSteps()),
                            "maxHops", String.valueOf(config.maxHops()),
                            "processedSteps", String.valueOf(sampleRouteAll.processedSteps()),
                            "emittedSignals", String.valueOf(sampleRouteAll.emittedSignals().size()),
                            "stepLimitReached", String.valueOf(sampleRouteAll.stepLimitReached()),
                            "hopLimitReached", String.valueOf(sampleRouteAll.hopLimitReached()),
                            "routingPolicy", "RouteAll")));

            // Resonance Threshold Policy
            results.add(collector.measure(
                    "GraphPropagation.ThresholdRouting",
                    config.name() + " (" + config.nodes() + " nodes, deg " + config.degree() + ")",
                    warmups,
                    iterations,
                    () -> {
                        var res = engine.propagate(
                                topology.entryNode(),
                                initialSignal,
                                processor,
                                thresholdConfig);
                        if (res.emittedSignals().isEmpty()) {
                            throw new IllegalStateException("empty propagation");
                        }
                    },
                    Map.of(
                            "nodeCount", String.valueOf(config.nodes()),
                            "totalEdges", String.valueOf(topology.totalEdges()),
                            "estimatedRetainedBytes", String.valueOf(estimatedRetainedBytes),
                            "threshold", "0.5",
                            "processedSteps", String.valueOf(sampleThreshold.processedSteps()),
                            "emittedSignals", String.valueOf(sampleThreshold.emittedSignals().size()),
                            "stepLimitReached", String.valueOf(sampleThreshold.stepLimitReached()),
                            "hopLimitReached", String.valueOf(sampleThreshold.hopLimitReached()),
                            "routingPolicy", "ResonanceThresholdRoutingPolicy")));

            results.add(collector.measure(
                    "CompactGraphPropagation.RouteAll",
                    config.name() + " (" + config.nodes() + " nodes, deg " + config.degree() + ")",
                    warmups,
                    iterations,
                    () -> {
                        var res = compactEngine.propagate(
                                topology.entryNode(),
                                initialSignal,
                                processor,
                                routeAllConfig);
                        if (res.emittedSignals().isEmpty()) {
                            throw new IllegalStateException("empty compact propagation");
                        }
                    },
                    Map.ofEntries(
                            Map.entry("backend", "CompactSignalPropagationEngine"),
                            Map.entry("representation", "CSR snapshot"),
                            Map.entry("nodeCount", String.valueOf(config.nodes())),
                            Map.entry("totalEdges", String.valueOf(topology.totalEdges())),
                            Map.entry("objectTopologyEstimatedRetainedBytes", String.valueOf(estimatedRetainedBytes)),
                            Map.entry("compactSnapshotEstimatedRetainedBytes", String.valueOf(compactSnapshotEstimatedBytes)),
                            Map.entry("combinedEstimatedRetainedBytes", String.valueOf(combinedEstimatedBytes)),
                            Map.entry("maxSteps", String.valueOf(config.maxSteps())),
                            Map.entry("maxHops", String.valueOf(config.maxHops())),
                            Map.entry("processedSteps", String.valueOf(compactRouteAll.processedSteps())),
                            Map.entry("emittedSignals", String.valueOf(compactRouteAll.emittedSignals().size())),
                            Map.entry("stepLimitReached", String.valueOf(compactRouteAll.stepLimitReached())),
                            Map.entry("hopLimitReached", String.valueOf(compactRouteAll.hopLimitReached())),
                            Map.entry("routingPolicy", "RouteAll"))));

            results.add(collector.measure(
                    "CompactGraphPropagation.ThresholdRouting",
                    config.name() + " (" + config.nodes() + " nodes, deg " + config.degree() + ")",
                    warmups,
                    iterations,
                    () -> {
                        var res = compactEngine.propagate(
                                topology.entryNode(),
                                initialSignal,
                                processor,
                                thresholdConfig);
                        if (res.emittedSignals().isEmpty()) {
                            throw new IllegalStateException("empty compact propagation");
                        }
                    },
                    Map.ofEntries(
                            Map.entry("backend", "CompactSignalPropagationEngine"),
                            Map.entry("representation", "CSR snapshot"),
                            Map.entry("nodeCount", String.valueOf(config.nodes())),
                            Map.entry("totalEdges", String.valueOf(topology.totalEdges())),
                            Map.entry("objectTopologyEstimatedRetainedBytes", String.valueOf(estimatedRetainedBytes)),
                            Map.entry("compactSnapshotEstimatedRetainedBytes", String.valueOf(compactSnapshotEstimatedBytes)),
                            Map.entry("combinedEstimatedRetainedBytes", String.valueOf(combinedEstimatedBytes)),
                            Map.entry("maxSteps", String.valueOf(config.maxSteps())),
                            Map.entry("maxHops", String.valueOf(config.maxHops())),
                            Map.entry("processedSteps", String.valueOf(compactThreshold.processedSteps())),
                            Map.entry("emittedSignals", String.valueOf(compactThreshold.emittedSignals().size())),
                            Map.entry("stepLimitReached", String.valueOf(compactThreshold.stepLimitReached())),
                            Map.entry("hopLimitReached", String.valueOf(compactThreshold.hopLimitReached())),
                            Map.entry("routingPolicy", "ResonanceThresholdRoutingPolicy"))));

            results.add(collector.measure(
                    "CompactGraphCompilation",
                    config.name() + " (" + config.nodes() + " nodes, deg " + config.degree() + ")",
                    warmups,
                    iterations,
                    () -> {
                        var compiled = CompactGraphSnapshot.compile(topology.nodes());
                        if (compiled.edgeCount() != topology.totalEdges()) {
                            throw new IllegalStateException("compact snapshot edge count mismatch");
                        }
                    },
                    Map.ofEntries(
                            Map.entry("backend", "CompactGraphSnapshot"),
                            Map.entry("operation", "compile"),
                            Map.entry("nodeCount", String.valueOf(config.nodes())),
                            Map.entry("totalEdges", String.valueOf(topology.totalEdges())),
                            Map.entry("objectTopologyEstimatedRetainedBytes", String.valueOf(estimatedRetainedBytes)),
                            Map.entry("compactSnapshotEstimatedRetainedBytes", String.valueOf(compactSnapshotEstimatedBytes)),
                            Map.entry("combinedEstimatedRetainedBytes", String.valueOf(combinedEstimatedBytes)))));
        }
        return results;
    }

    private List<BenchmarkRunResult> benchmarkAeonCoordination() {
        var engine = new DeterministicSignalPropagationEngine();
        var sequentialCoordinator = new DeterministicAeonCoordinator(engine);
        var parallelCoordinator = new BoundedParallelAeonCoordinator(
                engine,
                ForkJoinPool.commonPool(),
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                1,
                AeonParallelEligibility.INDEPENDENT_READ_ONLY,
                ContextualParallelism.EXPERIMENTAL_PARALLEL);
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 15;

        var topology = generator.generateGraph(100, 4);
        var aeon = generator.generateAeon(AeonPurpose.REASONING, topology);
        var signals = generator.generateSignals(quickMode ? 3 : 10);
        var inputs = signals.stream()
                .map(s -> new monada.neuron.aeon.AeonInput(topology.entryNode().getId(), s))
                .toList();

        var propConfig = PropagationConfig.routeAll(500, 6);
        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(input));

        // Ensure all runs have equal global step capacity (budget >= 10 * 500 = 5,000 steps, > 25,000 signals/traces)
        var cognitiveBudget = new CognitiveBudget(50_000, 100_000, 100_000);

        // Pre-validate semantic equivalence of work volume across coordinators
        var directSample = sequentialCoordinator.coordinate(aeon, inputs, processor, propConfig);
        var testContext = new CognitiveContext(cognitiveBudget);
        var contextualSample = sequentialCoordinator.coordinate(aeon, inputs, processor, propConfig, testContext);
        testContext.close();

        var parallelDirectSample = parallelCoordinator.coordinate(aeon, inputs, processor, propConfig);
        var parTestContext = new CognitiveContext(cognitiveBudget);
        var parallelContextualSample = parallelCoordinator.coordinate(aeon, inputs, processor, propConfig, parTestContext);
        parTestContext.close();

        int directTotalSteps = directSample.inputResults().stream()
                .mapToInt(ir -> ir.propagationResult().processedSteps())
                .sum();
        int directTotalEmitted = directSample.inputResults().stream()
                .mapToInt(ir -> ir.propagationResult().emittedSignals().size())
                .sum();

        int contextualTotalSteps = contextualSample.inputResults().stream()
                .mapToInt(ir -> ir.propagationResult().processedSteps())
                .sum();
        int contextualTotalEmitted = contextualSample.inputResults().stream()
                .mapToInt(ir -> ir.propagationResult().emittedSignals().size())
                .sum();

        if (directTotalSteps != contextualTotalSteps
                || directTotalEmitted != contextualTotalEmitted
                || directSample.inputResults().size() != contextualSample.inputResults().size()
                || !directSample.equals(parallelDirectSample)
                || !contextualSample.equals(parallelContextualSample)) {
            throw new IllegalStateException(String.format(
                    "Workload equivalence violation between Direct, Contextual, Sequential, and Parallel Aeon coordination: "
                            + "steps=(%d vs %d), emitted=(%d vs %d), results=(%d vs %d)",
                    directTotalSteps, contextualTotalSteps,
                    directTotalEmitted, contextualTotalEmitted,
                    directSample.inputResults().size(), contextualSample.inputResults().size()));
        }

        var results = new ArrayList<BenchmarkRunResult>();

        // Deterministic Aeon Coordination (Direct)
        results.add(collector.measure(
                "AeonCoordinator.Direct",
                inputs.size() + " inputs, 100 members",
                warmups,
                iterations,
                () -> {
                    var res = sequentialCoordinator.coordinate(
                            aeon,
                            inputs,
                            processor,
                            propConfig);
                    if (res.inputResults().isEmpty()) {
                        throw new IllegalStateException("empty coordination");
                    }
                },
                Map.of(
                        "inputCount", String.valueOf(inputs.size()),
                        "memberCount", String.valueOf(topology.nodes().size()),
                        "totalProcessedSteps", String.valueOf(directTotalSteps),
                        "totalEmittedSignals", String.valueOf(directTotalEmitted),
                        "inputResultCount", String.valueOf(directSample.inputResults().size()),
                        "workloadEquivalent", "true",
                        "contextual", "false")));

        // Cognitive Aeon Coordination (With CognitiveContext)
        results.add(collector.measure(
                "AeonCoordinator.Contextual",
                inputs.size() + " inputs, 100 members",
                warmups,
                iterations,
                () -> {
                    var context = new CognitiveContext(cognitiveBudget);
                    try {
                        var res = sequentialCoordinator.coordinate(
                                aeon,
                                inputs,
                                processor,
                                propConfig,
                                context);
                        if (res.inputResults().isEmpty()) {
                            throw new IllegalStateException("empty coordination");
                        }
                    } finally {
                        context.close();
                    }
                },
                Map.of(
                        "inputCount", String.valueOf(inputs.size()),
                        "memberCount", String.valueOf(topology.nodes().size()),
                        "totalProcessedSteps", String.valueOf(contextualTotalSteps),
                        "totalEmittedSignals", String.valueOf(contextualTotalEmitted),
                        "inputResultCount", String.valueOf(contextualSample.inputResults().size()),
                        "workloadEquivalent", "true",
                        "contextBudgetExhausted", "false",
                        "contextual", "true")));

        // Bounded Parallel Aeon Coordination (Direct)
        results.add(collector.measure(
                "BoundedParallelAeonCoordinator.Direct",
                inputs.size() + " inputs, 100 members",
                warmups,
                iterations,
                () -> {
                    var res = parallelCoordinator.coordinate(
                            aeon,
                            inputs,
                            processor,
                            propConfig);
                    if (res.inputResults().isEmpty()) {
                        throw new IllegalStateException("empty parallel coordination");
                    }
                },
                Map.of(
                        "backend", "BoundedParallelAeonCoordinator",
                        "inputCount", String.valueOf(inputs.size()),
                        "memberCount", String.valueOf(topology.nodes().size()),
                        "totalProcessedSteps", String.valueOf(directTotalSteps),
                        "totalEmittedSignals", String.valueOf(directTotalEmitted),
                        "inputResultCount", String.valueOf(parallelDirectSample.inputResults().size()),
                        "workloadEquivalent", "true",
                        "contextual", "false")));

        // Bounded Parallel Cognitive Aeon Coordination (Contextual)
        results.add(collector.measure(
                "BoundedParallelAeonCoordinator.Contextual",
                inputs.size() + " inputs, 100 members",
                warmups,
                iterations,
                () -> {
                    var context = new CognitiveContext(cognitiveBudget);
                    try {
                        var res = parallelCoordinator.coordinate(
                                aeon,
                                inputs,
                                processor,
                                propConfig,
                                context);
                        if (res.inputResults().isEmpty()) {
                            throw new IllegalStateException("empty parallel coordination");
                        }
                    } finally {
                        context.close();
                    }
                },
                Map.of(
                        "backend", "BoundedParallelAeonCoordinator",
                        "inputCount", String.valueOf(inputs.size()),
                        "memberCount", String.valueOf(topology.nodes().size()),
                        "totalProcessedSteps", String.valueOf(contextualTotalSteps),
                        "totalEmittedSignals", String.valueOf(contextualTotalEmitted),
                        "inputResultCount", String.valueOf(parallelContextualSample.inputResults().size()),
                        "workloadEquivalent", "true",
                        "contextBudgetExhausted", "false",
                        "contextual", "true")));

        return results;
    }

    private List<BenchmarkRunResult> benchmarkCognitiveCycles() {
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 15;

        var memoryPort = new DeterministicMemoryFixture();
        var actionCap = new DeterministicActionFixture();
        var policy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);
        var initialSignals = generator.generateSignals(quickMode ? 2 : 5);
        var budget = new CognitiveBudget(5_000, 5_000, 10_000);

        // Pre-generate sample for diagnostic inspection
        var samplePerception = generator.generateGraph(50, 3);
        var sampleReasoning = generator.generateGraph(50, 3);
        var sampleSetup = generator.generateFullCycleSetup(samplePerception, sampleReasoning, policy, memoryPort, actionCap);
        var sampleSnapshot = sampleSetup.cycle().execute(sampleSetup.monad(), initialSignals, budget);

        // Holder to store fresh setup recreated before each iteration outside measurement interval
        var setupHolder = new CognitiveCycleSetup[1];
        Runnable iterationSetup = () -> {
            var perceptionTop = generator.generateGraph(50, 3);
            var reasoningTop = generator.generateGraph(50, 3);
            setupHolder[0] = generator.generateFullCycleSetup(perceptionTop, reasoningTop, policy, memoryPort, actionCap);
        };

        var results = new ArrayList<BenchmarkRunResult>();
        results.add(collector.measure(
                "DeterministicCognitiveCycle.FullCycle",
                "5 stages, 5 initial signals",
                warmups,
                iterations,
                1,
                iterationSetup,
                () -> {
                    var setup = setupHolder[0];
                    var snapshot = setup.cycle().execute(setup.monad(), initialSignals, budget);
                    if (snapshot.snapshot().traceEntries().isEmpty()) {
                        throw new IllegalStateException("empty cycle snapshot");
                    }
                },
                Map.of(
                        "stages", "PERCEPTION -> MEMORY_RECALL -> REASONING -> ADAPTATION -> ACTION",
                        "initialSignalCount", String.valueOf(initialSignals.size()),
                        "budgetSteps", "5000",
                        "traceEntriesCount", String.valueOf(sampleSnapshot.snapshot().traceEntries().size()),
                        "stateResetPerIteration", "true")));

        return results;
    }

    private List<BenchmarkRunResult> benchmarkAdaptationComparison() {
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 15;

        var memoryPort = new DeterministicMemoryFixture();
        var actionCap = new DeterministicActionFixture();

        var noOpPolicy = NoOpAdaptationPolicy.INSTANCE;
        var baselinePolicy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);

        var initialSignals = generator.generateSignals(quickMode ? 2 : 5);
        var budget = new CognitiveBudget(5_000, 5_000, 10_000);

        var noOpSetupHolder = new CognitiveCycleSetup[1];
        Runnable noOpIterationSetup = () -> {
            var pTop = generator.generateGraph(50, 3);
            var rTop = generator.generateGraph(50, 3);
            noOpSetupHolder[0] = generator.generateFullCycleSetup(pTop, rTop, noOpPolicy, memoryPort, actionCap);
        };

        var baselineSetupHolder = new CognitiveCycleSetup[1];
        Runnable baselineIterationSetup = () -> {
            var pTop = generator.generateGraph(50, 3);
            var rTop = generator.generateGraph(50, 3);
            baselineSetupHolder[0] = generator.generateFullCycleSetup(pTop, rTop, baselinePolicy, memoryPort, actionCap);
        };

        var results = new ArrayList<BenchmarkRunResult>();

        // No-Op Adaptation Cycle
        results.add(collector.measure(
                "CognitiveCycle.Adaptation.NoOp",
                "50 target nodes",
                warmups,
                iterations,
                1,
                noOpIterationSetup,
                () -> {
                    var setup = noOpSetupHolder[0];
                    var snapshot = setup.cycle().execute(setup.monad(), initialSignals, budget);
                    if (snapshot.snapshot().traceEntries().isEmpty()) {
                        throw new IllegalStateException("empty cycle snapshot");
                    }
                },
                Map.of(
                        "policy", "NoOpAdaptationPolicy",
                        "targetNodeCount", "50",
                        "stateResetPerIteration", "true")));

        // Baseline Adaptation Policy Cycle
        results.add(collector.measure(
                "CognitiveCycle.Adaptation.BaselinePolicy",
                "50 target nodes",
                warmups,
                iterations,
                1,
                baselineIterationSetup,
                () -> {
                    var setup = baselineSetupHolder[0];
                    var snapshot = setup.cycle().execute(setup.monad(), initialSignals, budget);
                    if (snapshot.snapshot().traceEntries().isEmpty()) {
                        throw new IllegalStateException("empty cycle snapshot");
                    }
                },
                Map.of(
                        "policy", "DeterministicBaselineAdaptationPolicy",
                        "targetNodeCount", "50",
                        "learningRate", String.valueOf(AdaptationConfig.DEFAULT.learningRate()),
                        "stateResetPerIteration", "true")));

        return results;
    }

    private List<BenchmarkRunResult> benchmarkBackendSelection() {
        var autoSelector = RuntimeBackendSelector.autoSelector();
        var refSelector = RuntimeBackendSelector.referenceSelector();
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 20;
        int operationsPerIteration = quickMode ? 1_000 : 10_000;

        var topology = generator.generateGraph(50, 3);
        var snapshot = CompactGraphSnapshot.compile(topology.nodes());

        var results = new ArrayList<BenchmarkRunResult>();

        // Resonance Selection (AUTO vs REFERENCE)
        results.add(collector.measure(
                "RuntimeBackendSelector.Resonance.Auto",
                "100 pairs",
                warmups,
                iterations,
                operationsPerIteration,
                () -> {
                    for (int i = 0; i < operationsPerIteration; i++) {
                        var sel = autoSelector.selectResonance(100);
                        if (sel.backend() == null) {
                            throw new IllegalStateException("null backend");
                        }
                    }
                },
                Map.of(
                        "preference", "AUTO",
                        "scale", "100",
                        "selectedBackend", autoSelector.selectResonance(100).diagnostic().selectedBackendId().name(),
                        "reason", autoSelector.selectResonance(100).diagnostic().reason().name())));

        results.add(collector.measure(
                "RuntimeBackendSelector.Resonance.Reference",
                "100 pairs",
                warmups,
                iterations,
                operationsPerIteration,
                () -> {
                    for (int i = 0; i < operationsPerIteration; i++) {
                        var sel = refSelector.selectResonance(100);
                        if (sel.backend() == null) {
                            throw new IllegalStateException("null backend");
                        }
                    }
                },
                Map.of(
                        "preference", "REFERENCE",
                        "scale", "100",
                        "selectedBackend", refSelector.selectResonance(100).diagnostic().selectedBackendId().name(),
                        "reason", refSelector.selectResonance(100).diagnostic().reason().name())));

        // Graph Selection (AUTO)
        results.add(collector.measure(
                "RuntimeBackendSelector.Graph.Auto",
                "50 nodes",
                warmups,
                iterations,
                operationsPerIteration,
                () -> {
                    for (int i = 0; i < operationsPerIteration; i++) {
                        var sel = autoSelector.selectGraphPropagation(snapshot, false);
                        if (sel.backend() == null) {
                            throw new IllegalStateException("null backend");
                        }
                    }
                },
                Map.of(
                        "preference", "AUTO",
                        "selectedBackend", autoSelector.selectGraphPropagation(snapshot, false).diagnostic().selectedBackendId().name(),
                        "reason", autoSelector.selectGraphPropagation(snapshot, false).diagnostic().reason().name())));

        // Aeon Selection (AUTO)
        results.add(collector.measure(
                "RuntimeBackendSelector.Aeon.Auto",
                "10 inputs",
                warmups,
                iterations,
                operationsPerIteration,
                () -> {
                    for (int i = 0; i < operationsPerIteration; i++) {
                        var sel = autoSelector.selectAeonCoordinator(10, false, true);
                        if (sel.backend() == null) {
                            throw new IllegalStateException("null backend");
                        }
                    }
                },
                Map.of(
                        "preference", "AUTO",
                        "selectedBackend", autoSelector.selectAeonCoordinator(10, false, true).diagnostic().selectedBackendId().name(),
                        "reason", autoSelector.selectAeonCoordinator(10, false, true).diagnostic().reason().name())));

        return results;
    }
}
