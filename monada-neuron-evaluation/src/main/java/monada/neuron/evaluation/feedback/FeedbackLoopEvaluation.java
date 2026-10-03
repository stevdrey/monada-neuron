package monada.neuron.evaluation.feedback;

import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.action.ActionCognitiveStageResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.workload.DeterministicActionFixture;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.GraphTopology;
import monada.neuron.evolution.AdaptationCognitiveStage;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.DeterministicOutcomeFeedbackPolicy;
import monada.neuron.evolution.FeedbackAdaptationCognitiveStage;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.evolution.NoOpOutcomeFeedbackPolicy;
import monada.neuron.evolution.OutcomeFeedback;
import monada.neuron.evolution.OutcomeFeedbackPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.monad.AeonCognitiveStage;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.ResonanceThresholdRoutingPolicy;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Repeated-cycle evaluation of the explicit cross-cycle feedback handoff (ADR 0021, Issue #34).
 *
 * <p>Three arms run the same bounded sequence of cognitive cycles from identical initial state:
 * <ul>
 *   <li><b>control</b>: no feedback is derived or consumed (the current no-op adaptation reference);</li>
 *   <li><b>derived-not-consumed</b>: feedback is derived every cycle but never handed to the next one;</li>
 *   <li><b>consumed</b>: the caller hands each derived artifact to the next cycle's adaptation stage.</li>
 * </ul>
 * Correctness verdicts ({@link Check}) are semantic and deterministic. Latency, allocation, and GC
 * numbers are exploratory diagnostics and never influence a verdict. Verification runs happen outside
 * every measured window.
 */
public final class FeedbackLoopEvaluation {

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

    private enum Arm {
        CONTROL("FeedbackLoop.control"),
        DERIVED_NOT_CONSUMED("FeedbackLoop.derived-not-consumed"),
        CONSUMED("FeedbackLoop.consumed");

        private final String benchmarkName;

        Arm(String benchmarkName) {
            this.benchmarkName = benchmarkName;
        }
    }

    /** Everything one arm run produces; the measured windows discard it. */
    private record Run(
            List<double[]> fingerprints,
            List<OutcomeFeedback> derived,
            int maxNodeHistorySize,
            boolean allCyclesReachedAction,
            double meanTargetAmplitude,
            double[] last) {
    }

    private static final int AVG_DEGREE = 3;
    // Route-all propagation never quiesces on a cyclic graph and always ends perception on its bound
    // (STAGE_LIMIT_REACHED), which would stop the cycle before ADAPTATION and ACTION. Threshold routing
    // lets the Aeon stage finish, as in the Resonance Store integration evaluation.
    private static final PropagationConfig PROPAGATION = new PropagationConfig(
            2_000, 8, new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 0.5));
    private static final CognitiveBudget BUDGET = new CognitiveBudget(100_000, 100_000, 4_096);
    private static final int MAX_OBSERVATIONS = 5;
    private static final int INPUT_SIGNALS = 3;
    private static final int FINGERPRINT_STRIDE = 5;

    private final long seed;
    private final boolean quick;
    private final int nodeCount;
    private final int targetCount;
    private final int cycles;
    private final int warmupIterations;
    private final int measurementIterations;
    private final EvaluationMetricsCollector collector = new EvaluationMetricsCollector();

    /** Creates the evaluation; quick mode uses a small sequence suitable for tests. */
    public FeedbackLoopEvaluation(long seed, boolean quick) {
        this.seed = seed;
        this.quick = quick;
        this.nodeCount = quick ? 64 : 512;
        this.targetCount = quick ? 16 : 32;
        // Full mode exceeds Node.DEFAULT_HISTORY_LIMIT so the history bound is actually exercised.
        this.cycles = quick ? 40 : Node.DEFAULT_HISTORY_LIMIT + 44;
        this.warmupIterations = quick ? 1 : 3;
        this.measurementIterations = quick ? 3 : 10;
    }

    /** Runs verification and measurement. */
    public Outcome run() {
        var checks = verify();
        var results = new ArrayList<BenchmarkRunResult>();
        for (var arm : Arm.values()) {
            results.add(measure(arm));
        }
        return new Outcome(checks, results, metadata());
    }

    // ---------------------------------------------------------------------------------------------
    // Verification (outside every measured window)
    // ---------------------------------------------------------------------------------------------

    private List<Check> verify() {
        var control = execute(Arm.CONTROL, null);
        var derivedOnly = execute(Arm.DERIVED_NOT_CONSUMED, null);
        var consumed = execute(Arm.CONSUMED, null);
        var replay = execute(Arm.CONSUMED, null);
        var neutralControl = execute(Arm.CONTROL, ActionStatus.TIMED_OUT);
        var neutralConsumed = execute(Arm.CONSUMED, ActionStatus.TIMED_OUT);
        var reward = execute(Arm.CONSUMED, ActionStatus.SUCCEEDED);
        var penalty = execute(Arm.CONSUMED, ActionStatus.FAILED);
        var initialAmplitude = meanTargetAmplitude(newTopology().nodes().subList(0, targetCount));

        var checks = new ArrayList<Check>();
        var allReached = control.allCyclesReachedAction() && derivedOnly.allCyclesReachedAction()
                && consumed.allCyclesReachedAction() && reward.allCyclesReachedAction()
                && penalty.allCyclesReachedAction();
        checks.add(new Check("cycles.complete-through-action", allReached,
                "every cycle of every arm terminated COMPLETED and produced an ACTION outcome"));

        checks.add(new Check("ab.derived-not-consumed-matches-control",
                sameSequence(control, derivedOnly) && derivedOnly.derived().size() == cycles,
                "feedback derived in " + derivedOnly.derived().size() + " cycles without being consumed leaves"
                        + " every state fingerprint identical to the control"));

        var divergesAfterFirstConsumption = consumed.fingerprints().size() > 2
                && Arrays.equals(control.fingerprints().get(0), consumed.fingerprints().get(0))
                && !Arrays.equals(control.fingerprints().get(1), consumed.fingerprints().get(1))
                && !Arrays.equals(control.last(), consumed.last());
        checks.add(new Check("ab.consumed-diverges-after-first-consumption", divergesAfterFirstConsumption,
                "cycle 1 has no prior feedback and matches the control; cycle 2 onward diverges"));

        checks.add(new Check("replay.consumed-is-deterministic",
                sameSequence(consumed, replay) && consumed.derived().equals(replay.derived()),
                "two independent runs from the same initial state yield bit-identical state sequences"
                        + " and equal feedback artifacts"));

        checks.add(new Check("neutral.environmental-outcome-matches-control",
                sameSequence(neutralControl, neutralConsumed),
                "TIMED_OUT outcomes derive neutral feedback; consuming it never moves a Node"));

        var rewarded = reward.meanTargetAmplitude() > initialAmplitude;
        var penalized = penalty.meanTargetAmplitude() < initialAmplitude;
        checks.add(new Check("direction.reward-raises-and-penalty-lowers-amplitude", rewarded && penalized,
                "mean target amplitude: initial " + initialAmplitude + ", SUCCEEDED " + reward.meanTargetAmplitude()
                        + ", FAILED " + penalty.meanTargetAmplitude()));

        var maxEntries = consumed.derived().stream().mapToInt(feedback -> feedback.entries().size()).max().orElse(0);
        var feedbackBounded = maxEntries <= targetCount && maxEntries <= OutcomeFeedback.MAX_ENTRIES;
        checks.add(new Check("feedback.bounded", feedbackBounded,
                "largest artifact had " + maxEntries + " entries (targets " + targetCount + ", hard cap "
                        + OutcomeFeedback.MAX_ENTRIES + ")"));

        var expectedHistory = Math.min(cycles - 1, Node.DEFAULT_HISTORY_LIMIT);
        var historyBounded = consumed.maxNodeHistorySize() == expectedHistory
                && control.maxNodeHistorySize() == 0
                && derivedOnly.maxNodeHistorySize() == 0;
        checks.add(new Check("history.bounded", historyBounded,
                "max Node history " + consumed.maxNodeHistorySize() + " after " + (cycles - 1)
                        + " consumed cycles (limit " + Node.DEFAULT_HISTORY_LIMIT + "); unconsumed arms retain 0"));
        return checks;
    }

    private static boolean sameSequence(Run a, Run b) {
        if (a.fingerprints().size() != b.fingerprints().size()) {
            return false;
        }
        for (var i = 0; i < a.fingerprints().size(); i++) {
            if (!Arrays.equals(a.fingerprints().get(i), b.fingerprints().get(i))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Measurement
    // ---------------------------------------------------------------------------------------------

    private BenchmarkRunResult measure(Arm arm) {
        var holder = new GraphTopology[1];
        var diagnostics = new LinkedHashMap<String, String>();
        var reference = execute(arm, null);
        diagnostics.put("stateDigest", digest(reference.last()));
        diagnostics.put("maxNodeHistorySize", Integer.toString(reference.maxNodeHistorySize()));
        diagnostics.put("derivedFeedbackArtifacts", Integer.toString(reference.derived().size()));
        diagnostics.put("modeledFeedbackBytesPerCycle", Long.toString(modeledFeedbackBytes(
                reference.derived().isEmpty() ? 0 : reference.derived().getLast().entries().size())));
        diagnostics.put("modeledNodeHistoryBytes", Long.toString(
                (long) reference.maxNodeHistorySize() * targetCount * 44L));
        diagnostics.put("cyclesPerIteration", Integer.toString(cycles));
        diagnostics.put("stateResetPerIteration", "true");
        return collector.measure(
                arm.benchmarkName,
                "N=" + nodeCount + ",targets=" + targetCount + ",cycles=" + cycles,
                warmupIterations,
                measurementIterations,
                cycles,
                () -> holder[0] = newTopology(),
                () -> {
                    var run = sequence(holder[0], arm, null, false);
                    if (!run.allCyclesReachedAction()) {
                        throw new IllegalStateException("feedback-loop workload stopped before ACTION");
                    }
                },
                diagnostics);
    }

    /**
     * Modeled retained size of one artifact on 64-bit HotSpot with compressed references: record 48 B,
     * list overhead 16 B, 4 B per element reference, and 32 B per entry record. An estimate, not a
     * measurement; attributions are omitted from the baseline.
     */
    private static long modeledFeedbackBytes(int entries) {
        return 64L + 36L * entries;
    }

    // ---------------------------------------------------------------------------------------------
    // Workload
    // ---------------------------------------------------------------------------------------------

    private GraphTopology newTopology() {
        return new DeterministicWorkloadGenerator(seed).generateGraph(nodeCount, AVG_DEGREE);
    }

    private Run execute(Arm arm, ActionStatus status) {
        return sequence(newTopology(), arm, status, true);
    }

    /**
     * Runs the bounded cycle sequence for one arm over a fresh topology. Per-cycle state fingerprints
     * are only recorded for verification runs so the measured windows do not allocate them.
     *
     * @param status fixed action status for every cycle, or {@code null} to alternate SUCCEEDED and
     *     FAILED by cycle ordinal. Alternation keeps Node state moving; a constant reward would saturate
     *     the amplitude bound after a few dozen cycles, after which the baseline policy no longer
     *     transitions and Node history stops growing.
     */
    private Run sequence(GraphTopology topology, Arm arm, ActionStatus status, boolean recordFingerprints) {
        var generator = new DeterministicWorkloadGenerator(seed);
        var targets = topology.nodes().subList(0, targetCount);
        var targetIds = targets.stream().map(Node::getId).toList();
        var perceptionAeon = generator.generateAeon(AeonPurpose.PERCEPTION, topology);
        var monad = new PrimaryMonad(new UUID(seed, 0xFEEDBACL));
        monad.registerAeon(perceptionAeon);
        var coordinator = new DeterministicAeonCoordinator(new DeterministicSignalPropagationEngine());
        var succeeding = new DeterministicActionFixture(status == null ? ActionStatus.SUCCEEDED : status);
        var failing = new DeterministicActionFixture(status == null ? ActionStatus.FAILED : status);
        var derivation = arm == Arm.CONTROL
                ? (OutcomeFeedbackPolicy) NoOpOutcomeFeedbackPolicy.INSTANCE
                : new DeterministicOutcomeFeedbackPolicy();
        var baseline = new DeterministicBaselineAdaptationPolicy();
        var inputs = generator.generateSignals(INPUT_SIGNALS);
        NodeProcessor forwarding = (node, input) -> new NodeProcessingResult(List.of(new Signal(
                SignalKind.INTERMEDIATE,
                new FrequencyState(
                        Math.min(1.0, input.frequencyState().amplitude() * 0.9 + 0.1),
                        input.frequencyState().frequency(),
                        input.frequencyState().phase() + 0.1))));

        var fingerprints = new ArrayList<double[]>();
        var derived = new ArrayList<OutcomeFeedback>();
        var reachedAction = true;
        Optional<OutcomeFeedback> pending = Optional.empty();
        for (long ordinal = 0; ordinal < cycles; ordinal++) {
            var stages = new ArrayList<CognitiveStage>(3);
            stages.add(new AeonCognitiveStage(
                    CognitiveStageKind.PERCEPTION,
                    perceptionAeon,
                    topology.entryNode().getId(),
                    coordinator,
                    forwarding,
                    PROPAGATION));
            stages.add(pending.isPresent()
                    ? new FeedbackAdaptationCognitiveStage(baseline, targets, pending.get())
                    : new AdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, targets));
            stages.add(new ActionCognitiveStage(ordinal % 2 == 0 ? succeeding : failing, MAX_OBSERVATIONS));
            var result = new DeterministicCognitiveCycle(stages).execute(monad, inputs, BUDGET);
            reachedAction &= reachedAction(result);

            var next = derivation.derive(result, targetIds, ordinal);
            next.ifPresent(derived::add);
            pending = arm == Arm.CONSUMED ? next : Optional.empty();
            if (recordFingerprints) {
                fingerprints.add(fingerprint(targets));
            }
        }
        var maxHistory = targets.stream().mapToInt(node -> node.getHistory().size()).max().orElse(0);
        return new Run(fingerprints, derived, maxHistory, reachedAction, meanTargetAmplitude(targets), fingerprint(targets));
    }

    private static boolean reachedAction(CognitiveCycleResult result) {
        return result.termination() == CognitiveCycleTermination.COMPLETED
                && result.stageResults().stream().anyMatch(ActionCognitiveStageResult.class::isInstance);
    }

    private static double[] fingerprint(List<Node> targets) {
        var values = new double[targets.size() * FINGERPRINT_STRIDE];
        var index = 0;
        for (var node : targets) {
            var state = node.getFrequencyState();
            values[index++] = state.amplitude();
            values[index++] = state.frequency();
            values[index++] = state.phase();
            values[index++] = node.getEnergy();
            values[index++] = node.getHistory().size();
        }
        return values;
    }

    private static double meanTargetAmplitude(List<Node> targets) {
        return targets.stream().mapToDouble(node -> node.getFrequencyState().amplitude()).average().orElse(0.0);
    }

    /** FNV-1a over the exact bit patterns, so equal digests mean bit-identical fingerprints. */
    private static String digest(double[] fingerprint) {
        var hash = 0xcbf29ce484222325L;
        for (var value : fingerprint) {
            hash ^= Double.doubleToLongBits(value);
            hash *= 0x100000001b3L;
        }
        return String.format("%016x", hash);
    }

    private Map<String, String> metadata() {
        var metadata = new TreeMap<String, String>();
        metadata.put("workload.seed", Long.toString(seed));
        metadata.put("workload.mode", quick ? "quick" : "full");
        metadata.put("workload.nodes", Integer.toString(nodeCount));
        metadata.put("workload.targets", Integer.toString(targetCount));
        metadata.put("workload.cyclesPerSequence", Integer.toString(cycles));
        metadata.put("workload.pipeline", "PERCEPTION(Aeon) -> ADAPTATION(arm) -> ACTION(deterministic fixture)");
        metadata.put("workload.actionStatuses", "alternating SUCCEEDED/FAILED by cycle ordinal");
        metadata.put("node.historyLimit", Integer.toString(Node.DEFAULT_HISTORY_LIMIT));
        metadata.put("feedback.semantics", "SUCCEEDED +1.0, PARTIALLY_COMPLETED +0.5, REJECTED -0.25,"
                + " FAILED -1.0, UNAVAILABLE/TIMED_OUT neutral (ADR 0021)");
        metadata.put("feedback.ownership", "caller-owned; no queue, session, or persistence inside Neuron");
        return metadata;
    }
}
