package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.routing.adaptation.RoutingStateStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;

/**
 * The one measurement of a replay arm, shared by the store-free and the Store evaluations so their rows carry the same
 * diagnostics and labels.
 *
 * <p>Before timing, an unrecorded run must equal the recorded run the checks verified; that run also generates the
 * synthetic observations the timed runs reuse. Every timed iteration replays the whole fixture; the previous
 * iteration's run is validated against the reference in the collector's untimed setup (and the last one after the
 * loop), against a state digest computed once. Latency is per replay iteration; throughput and allocation are per
 * decision.
 */
final class ReplayMeasurement {

    /** Rough retained bytes of one cohort (Node, key, map entry) and one ledger entry; a model, not a measurement. */
    static final long MODELED_COHORT_BYTES = 400L;
    static final long MODELED_LEDGER_ENTRY_BYTES = 350L;

    private final EvaluationMetricsCollector collector;
    private final ReplayDigests digests;
    private final int warmupIterations;
    private final int measurementIterations;

    ReplayMeasurement(EvaluationMetricsCollector collector, ReplayDigests digests, int warmupIterations,
            int measurementIterations) {
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
        this.digests = Objects.requireNonNull(digests, "digests must not be null");
        this.warmupIterations = warmupIterations;
        this.measurementIterations = measurementIterations;
    }

    /** What a measured iteration of {@code arm} times; recorded on every row. */
    static String timedWindow(ReplayArm arm) {
        return switch (arm) {
            case STATIC -> "the full replay against an empty preference; no state is constructed or bound;"
                    + " generated observations are reused; validation untimed";
            case DERIVED_NOT_CONSUMED -> "fresh state construction, cohort binding and the full replay, deriving"
                    + " feedback for every delivered observation without consuming it; generated observations are"
                    + " reused; validation untimed";
            case ADAPTIVE -> "fresh state construction, cohort binding and the full replay, deriving and consuming"
                    + " feedback (a rebuild reuses each observation's delivery-time derivation); generated observations"
                    + " are reused; validation untimed";
        };
    }

    /**
     * Measures one arm of a fixture.
     *
     * @param name the row name
     * @param fixture the replayed fixture
     * @param arm the arm
     * @param verified the recorded run of this arm and fixture that the semantic checks verified
     * @return the row, labelled with its latency scope
     * @throws IllegalStateException if a measured run differs from the verified one
     */
    BenchmarkRunResult measure(String name, ReplayFixture fixture, ReplayArm arm, ReplayRun verified) {
        var replay = new TemporalReplay(fixture);
        ReplayRun reference = replay.run(arm, false);
        String expectedState = verified.state().map(digests::state).orElse("none");
        // the unrecorded (timed) path must decide exactly as the recorded path the checks verified
        RoutingReplayEvaluation.requireSameReplay(reference, verified, expectedState, digests);
        int cohorts = reference.state().map(RoutingStateStore::cohortCount).orElse(0);
        int ledger = reference.state().map(s -> s.ledger().size()).orElse(0);
        int decisions = fixture.tasks().size();
        var diagnostics = new LinkedHashMap<String, String>();
        diagnostics.put("stateDigest", expectedState);
        diagnostics.put("decisionTraceDigest", digests.decisions(reference.decisions()));
        diagnostics.put("decisionsPerIteration", Integer.toString(decisions));
        diagnostics.put("deliveredObservations", Integer.toString(reference.delivered().size()));
        diagnostics.put("derivations", Integer.toString(reference.derivations().total()));
        diagnostics.put("cohorts", Integer.toString(cohorts));
        diagnostics.put("ledgerEntries", Integer.toString(ledger));
        diagnostics.put("modeledRetainedStateBytes", Long.toString(
                cohorts * MODELED_COHORT_BYTES + ledger * MODELED_LEDGER_ENTRY_BYTES));
        diagnostics.put("stateResetPerIteration", Boolean.toString(arm != ReplayArm.STATIC));
        diagnostics.put("timedWindow", timedWindow(arm));
        diagnostics.put("validationInWindow", "false");
        diagnostics.put("gcTelemetryScope", "includes the untimed validation of the previous run");
        var pending = new ReplayRun[1];
        BenchmarkRunResult result = collector.measure(name,
                "tasks=" + decisions + ",routes=" + fixture.tasks().stream()
                        .flatMap(t -> t.catalog().entries().stream()).map(e -> e.key()).distinct().count()
                        + ",cohorts=" + fixture.cohortKeys().size(),
                warmupIterations, measurementIterations, decisions,
                () -> {
                    if (pending[0] != null) {
                        RoutingReplayEvaluation.requireSameReplay(pending[0], reference, expectedState, digests);
                    }
                    pending[0] = null;
                },
                () -> pending[0] = replay.run(arm, false),
                diagnostics);
        // the collector runs no setup after its last iteration, so the final measured run is validated here
        RoutingReplayEvaluation.requireSameReplay(pending[0], reference, expectedState, digests);
        return withLatencyScope(result, decisions);
    }

    /**
     * The collector's latency distribution holds whole-iteration durations, while its throughput and allocation are
     * per operation. This labels the row explicitly and adds the derived mean per decision, without changing the shared
     * metrics types the other evaluations use.
     */
    static BenchmarkRunResult withLatencyScope(BenchmarkRunResult result, int decisionsPerIteration) {
        var diagnostics = new LinkedHashMap<>(result.diagnostics());
        diagnostics.put("latencyScope", "per replay iteration of " + decisionsPerIteration + " decisions");
        diagnostics.put("meanNanosPerDecision",
                String.format(Locale.ROOT, "%.1f", result.latency().meanNanos() / decisionsPerIteration));
        return new BenchmarkRunResult(result.benchmarkName(), result.workloadScale(), result.iterations(),
                result.latency(), result.allocation(), diagnostics);
    }
}
