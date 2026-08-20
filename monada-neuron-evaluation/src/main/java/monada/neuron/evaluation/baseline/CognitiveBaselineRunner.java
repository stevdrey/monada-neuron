package monada.neuron.evaluation.baseline;

import monada.neuron.action.ActionCapability;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.CognitiveAeonCoordinator;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.workload.DeterministicActionFixture;
import monada.neuron.evaluation.workload.DeterministicMemoryFixture;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.ResonanceThresholdRoutingPolicy;
import monada.neuron.runtime.graph.SignalRoutingPolicy;
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

/**
 * Main baseline runner that executes deterministic, reproducible cognitive workloads
 * and produces structured JSON and Markdown diagnostic reports.
 */
public final class CognitiveBaselineRunner {

    private final DeterministicWorkloadGenerator generator;
    private final EvaluationMetricsCollector collector;
    private final boolean quickMode;

    public CognitiveBaselineRunner() {
        this(DeterministicWorkloadGenerator.DEFAULT_SEED, false);
    }

    public CognitiveBaselineRunner(long seed, boolean quickMode) {
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

        // 2. Sparse Graph Propagation
        results.addAll(benchmarkGraphPropagation());

        // 3. Aeon Coordination
        results.addAll(benchmarkAeonCoordination());

        // 4. Primary Monad Cognitive Cycles
        results.addAll(benchmarkCognitiveCycles());

        // 5. Adaptation Enabled vs No-Op
        results.addAll(benchmarkAdaptationComparison());

        return new EvaluationReport(
                Instant.now(),
                EnvironmentMetadata.current(),
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
            var routeAllConfig = PropagationConfig.routeAll(config.maxSteps(), config.maxHops());
            var thresholdConfig = new PropagationConfig(config.maxSteps(), config.maxHops(), resonancePolicy);

            // Route All Policy
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
                            "maxSteps", String.valueOf(config.maxSteps()),
                            "maxHops", String.valueOf(config.maxHops()),
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
                            "threshold", "0.5",
                            "routingPolicy", "ResonanceThresholdRoutingPolicy")));
        }
        return results;
    }

    private List<BenchmarkRunResult> benchmarkAeonCoordination() {
        var coordinator = new DeterministicAeonCoordinator(new DeterministicSignalPropagationEngine());
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

        var results = new ArrayList<BenchmarkRunResult>();

        // Deterministic Aeon Coordination (Direct)
        results.add(collector.measure(
                "AeonCoordinator.Direct",
                inputs.size() + " inputs, 100 members",
                warmups,
                iterations,
                () -> {
                    var res = coordinator.coordinate(
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
                        "memberCount", "100",
                        "contextual", "false")));

        // Cognitive Aeon Coordination (With CognitiveContext)
        results.add(collector.measure(
                "AeonCoordinator.Contextual",
                inputs.size() + " inputs, 100 members",
                warmups,
                iterations,
                () -> {
                    var budget = new CognitiveBudget(2_000, 2_000, 5_000);
                    var context = new CognitiveContext(budget);
                    try {
                        var res = coordinator.coordinate(
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
                        "memberCount", "100",
                        "contextual", "true")));

        return results;
    }

    private List<BenchmarkRunResult> benchmarkCognitiveCycles() {
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 15;

        var perceptionTop = generator.generateGraph(50, 3);
        var reasoningTop = generator.generateGraph(50, 3);
        var memoryPort = new DeterministicMemoryFixture();
        var actionCap = new DeterministicActionFixture();
        var policy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);

        var setup = generator.generateFullCycleSetup(perceptionTop, reasoningTop, policy, memoryPort, actionCap);
        var initialSignals = generator.generateSignals(quickMode ? 2 : 5);
        var budget = new CognitiveBudget(5_000, 5_000, 10_000);

        var results = new ArrayList<BenchmarkRunResult>();
        results.add(collector.measure(
                "DeterministicCognitiveCycle.FullCycle",
                "5 stages, 5 initial signals",
                warmups,
                iterations,
                () -> {
                    var snapshot = setup.cycle().execute(setup.monad(), initialSignals, budget);
                    if (snapshot.snapshot().traceEntries().isEmpty()) {
                        throw new IllegalStateException("empty cycle snapshot");
                    }
                },
                Map.of(
                        "stages", "PERCEPTION -> MEMORY_RECALL -> REASONING -> ADAPTATION -> ACTION",
                        "initialSignalCount", String.valueOf(initialSignals.size()),
                        "budgetSteps", "5000")));

        return results;
    }

    private List<BenchmarkRunResult> benchmarkAdaptationComparison() {
        int warmups = quickMode ? 2 : 5;
        int iterations = quickMode ? 5 : 15;

        var perceptionTop = generator.generateGraph(50, 3);
        var reasoningTop = generator.generateGraph(50, 3);
        var memoryPort = new DeterministicMemoryFixture();
        var actionCap = new DeterministicActionFixture();

        var noOpPolicy = NoOpAdaptationPolicy.INSTANCE;
        var baselinePolicy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);

        var noOpSetup = generator.generateFullCycleSetup(perceptionTop, reasoningTop, noOpPolicy, memoryPort, actionCap);
        var baselineSetup = generator.generateFullCycleSetup(perceptionTop, reasoningTop, baselinePolicy, memoryPort, actionCap);

        var initialSignals = generator.generateSignals(quickMode ? 2 : 5);
        var budget = new CognitiveBudget(5_000, 5_000, 10_000);

        var results = new ArrayList<BenchmarkRunResult>();

        // No-Op Adaptation Cycle
        results.add(collector.measure(
                "CognitiveCycle.Adaptation.NoOp",
                "50 target nodes",
                warmups,
                iterations,
                () -> {
                    var snapshot = noOpSetup.cycle().execute(noOpSetup.monad(), initialSignals, budget);
                    if (snapshot.snapshot().traceEntries().isEmpty()) {
                        throw new IllegalStateException("empty cycle snapshot");
                    }
                },
                Map.of(
                        "policy", "NoOpAdaptationPolicy",
                        "targetNodeCount", "50")));

        // Baseline Adaptation Policy Cycle
        results.add(collector.measure(
                "CognitiveCycle.Adaptation.BaselinePolicy",
                "50 target nodes",
                warmups,
                iterations,
                () -> {
                    var snapshot = baselineSetup.cycle().execute(baselineSetup.monad(), initialSignals, budget);
                    if (snapshot.snapshot().traceEntries().isEmpty()) {
                        throw new IllegalStateException("empty cycle snapshot");
                    }
                },
                Map.of(
                        "policy", "DeterministicBaselineAdaptationPolicy",
                        "targetNodeCount", "50",
                        "learningRate", String.valueOf(AdaptationConfig.DEFAULT.learningRate()))));

        return results;
    }
}
