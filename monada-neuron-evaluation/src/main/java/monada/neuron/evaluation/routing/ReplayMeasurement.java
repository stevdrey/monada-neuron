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
 * synthetic observations the timed runs reuse. The arm is then warmed up under its {@link Warmup} budget, so a light
 * arm runs as many replays as a heavy one runs time, up to a cap; its first and last runs are validated too. Every
 * timed iteration replays the whole fixture; the previous iteration's run is validated against the reference in the
 * collector's untimed setup (and the last one after the loop), against a state digest computed once. Latency is per
 * replay iteration; throughput and allocation are per decision.
 */
final class ReplayMeasurement {

    /** Rough retained bytes of one cohort (Node, key, map entry) and one ledger entry; a model, not a measurement. */
    static final long MODELED_COHORT_BYTES = 400L;
    static final long MODELED_LEDGER_ENTRY_BYTES = 350L;
    /** Rough retained bytes of one checkpoint cohort (record, key reference, list slot); a model, not a measurement. */
    static final long MODELED_CHECKPOINT_COHORT_BYTES = 64L;

    /**
     * How long an arm is warmed up before timing: at least {@code minIterations} replays and at least
     * {@code budgetMillis} of accumulated replay time, whichever takes more replays, but never more than
     * {@code maxIterations}. A fixed count cannot fit arms whose replays differ by two orders of magnitude: a few
     * iterations of a millisecond replay end before the compiler does; the cap bounds a replay so fast that the budget
     * would take an unreasonable number of them.
     *
     * @param minIterations the minimum number of warm-up replays
     * @param budgetMillis the minimum accumulated replay time, excluding validation
     * @param maxIterations the most warm-up replays, at least {@code minIterations}
     */
    record Warmup(int minIterations, long budgetMillis, int maxIterations) {
        /** Smoke mode: enough to exercise the budgeted loop, not to reach steady state. */
        static final Warmup QUICK = new Warmup(1, 50L, 100);
        /** The cap stays above what the budget needs for every current row (the Store rows need up to ~40,000). */
        static final Warmup FULL = new Warmup(3, 2_000L, 100_000);

        Warmup {
            if (minIterations < 0 || budgetMillis < 0L || maxIterations < minIterations) {
                throw new IllegalArgumentException("warm-up minimums must be non-negative and within the maximum");
            }
        }

        static Warmup of(boolean quick) {
            return quick ? QUICK : FULL;
        }

        /** States the policy for a run configuration. */
        String describe() {
            return "warm-up per arm runs at least " + minIterations + " replays and at least " + budgetMillis
                    + " ms of replay time, at most " + maxIterations + " replays, so the count differs per row and"
                    + " the reported warm-up iterations are the minimum (see each row's warmupIterations)";
        }
    }

    private final EvaluationMetricsCollector collector;
    private final ReplayDigests digests;
    private final Warmup warmup;
    private final int measurementIterations;

    ReplayMeasurement(EvaluationMetricsCollector collector, ReplayDigests digests, Warmup warmup,
            int measurementIterations) {
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
        this.digests = Objects.requireNonNull(digests, "digests must not be null");
        this.warmup = Objects.requireNonNull(warmup, "warmup must not be null");
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
     * @throws IllegalStateException if a warm-up or measured run differs from the verified one
     */
    BenchmarkRunResult measure(String name, ReplayFixture fixture, ReplayArm arm, ReplayRun verified) {
        return measure(name, fixture, arm, RebuildStrategy.SCRATCH, verified);
    }

    /**
     * Measures one arm of a fixture with a rebuild strategy.
     *
     * @param name the row name
     * @param fixture the replayed fixture
     * @param arm the arm
     * @param strategy where the adaptive arm's correction-triggered rebuilds start
     * @param verified the recorded run that the semantic checks verified; a checkpointed row is held to the
     *        from-scratch run, since both must decide, deliver, derive and learn identically
     * @return the row, labelled with its latency scope
     * @throws IllegalStateException if a warm-up or measured run differs from the verified one
     */
    BenchmarkRunResult measure(String name, ReplayFixture fixture, ReplayArm arm, RebuildStrategy strategy,
            ReplayRun verified) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        var replay = new TemporalReplay(fixture);
        long referenceStart = System.nanoTime();
        ReplayRun reference = replay.run(arm, false, strategy);
        long referenceNanos = System.nanoTime() - referenceStart;
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
        diagnostics.put("ledgerDigestEntries",
                Long.toString(reference.state().map(s -> s.ledgerDigest().entries()).orElse(0L)));
        ReplayRun.RebuildStats rebuilds = reference.rebuilds();
        diagnostics.put("rebuildStrategy", strategy.name());
        diagnostics.put("rebuilds", Integer.toString(rebuilds.rebuilds()));
        diagnostics.put("rebuildsFromCheckpoint", Integer.toString(rebuilds.fromCheckpoint()));
        diagnostics.put("replayedObservations", Long.toString(rebuilds.replayedObservations()));
        diagnostics.put("checkpoints", Integer.toString(rebuilds.checkpoints()));
        diagnostics.put("compactedEntries", Integer.toString(rebuilds.compactedEntries()));
        long retainedCheckpointBytes = strategy == RebuildStrategy.CHECKPOINT
                ? RebuildStrategy.RETAINED_CHECKPOINTS * cohorts * MODELED_CHECKPOINT_COHORT_BYTES : 0L;
        diagnostics.put("modeledRetainedStateBytes", Long.toString(
                cohorts * MODELED_COHORT_BYTES + ledger * MODELED_LEDGER_ENTRY_BYTES + retainedCheckpointBytes));
        diagnostics.put("stateResetPerIteration", Boolean.toString(arm != ReplayArm.STATIC));
        diagnostics.put("timedWindow", timedWindow(arm) + (strategy == RebuildStrategy.CHECKPOINT
                ? "; checkpoints every " + RebuildStrategy.CHECKPOINT_INTERVAL + " deliveries, "
                        + RebuildStrategy.RETAINED_CHECKPOINTS + " kept, ledger compacted to the oldest, and rebuilds"
                        + " started from the newest valid one are timed too"
                : ""));
        diagnostics.put("validationInWindow", "false");
        diagnostics.put("gcTelemetryScope", "includes the untimed validation of the previous run");
        // the reference run also generated the observations every later run reuses, so it is not a steady-state figure
        diagnostics.put("referenceRunMillis", millis(referenceNanos));
        int warmups = 0;
        long warmupNanos = 0L;
        long budgetNanos = warmup.budgetMillis() * 1_000_000L;
        ReplayRun last = null;
        while (warmups < warmup.maxIterations()
                && (warmups < warmup.minIterations() || warmupNanos < budgetNanos)) {
            long start = System.nanoTime();
            last = replay.run(arm, false, strategy);
            warmupNanos += System.nanoTime() - start;
            // the first warm-up run is validated at once; the rest share one validation of the last, so a fast arm's
            // warm-up is not dominated by digests (every timed run is still validated)
            if (warmups == 0) {
                RoutingReplayEvaluation.requireSameReplay(last, reference, expectedState, digests);
            }
            warmups++;
        }
        if (last != null && warmups > 1) {
            RoutingReplayEvaluation.requireSameReplay(last, reference, expectedState, digests);
        }
        diagnostics.put("warmupIterations", Integer.toString(warmups));
        diagnostics.put("warmupMinIterations", Integer.toString(warmup.minIterations()));
        diagnostics.put("warmupBudgetMillis", Long.toString(warmup.budgetMillis()));
        diagnostics.put("warmupMaxIterations", Integer.toString(warmup.maxIterations()));
        diagnostics.put("warmupReplayMillis", millis(warmupNanos));
        var pending = new ReplayRun[1];
        BenchmarkRunResult result = collector.measure(name,
                "tasks=" + decisions + ",routes=" + fixture.tasks().stream()
                        .flatMap(t -> t.catalog().entries().stream()).map(e -> e.key()).distinct().count()
                        + ",cohorts=" + fixture.cohortKeys().size(),
                0, measurementIterations, decisions,
                () -> {
                    if (pending[0] != null) {
                        RoutingReplayEvaluation.requireSameReplay(pending[0], reference, expectedState, digests);
                    }
                    pending[0] = null;
                },
                () -> pending[0] = replay.run(arm, false, strategy),
                diagnostics);
        // the collector runs no setup after its last iteration, so the final measured run is validated here
        RoutingReplayEvaluation.requireSameReplay(pending[0], reference, expectedState, digests);
        return withLatencyScope(result, decisions);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
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
