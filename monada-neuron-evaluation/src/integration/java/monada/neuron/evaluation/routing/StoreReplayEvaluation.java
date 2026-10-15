package monada.neuron.evaluation.routing;

import com.monada.evaluation.execution.CostToAcceptedOutcome;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.routing.RoutingReplayEvaluation.Check;
import monada.neuron.evaluation.routing.RoutingReplayEvaluation.Outcome;
import monada.neuron.resonance.adapter.experience.ExperienceRejection;
import monada.neuron.resonance.adapter.experience.RejectionReason;
import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.outcome.RoutingObservation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The routing replay over a real Resonance Store export (Issue #67, optional integration arm).
 *
 * <p>A deterministic logged history is recorded into a temporary execution memory, exported through the adapter and
 * the Store's evaluation tooling, and replayed under every arm. Only the logged route's outcome exists, so the checks
 * are about honest coverage, not about which arm is better: the import is complete and explained, two independent
 * recordings export identical fixtures, controls hold, no credit breaks the quality gates, divergent choices are
 * missing counterfactuals, and the Store's cost reaches the report unchanged. A failure in any phase is recorded as a
 * failed check, so a diagnosable report is always produced and a missing prerequisite is never a silent pass.
 */
public final class StoreReplayEvaluation {

    /** Logged executions; the last one never finishes. */
    static final int TASKS = 16;

    private final boolean quick;
    private final ReplayMeasurement.Warmup warmup;
    private final int measurementIterations;
    private final ReplayDigests digests = new ReplayDigests();
    private final ReplayMeasurement measurement;

    /** Creates the evaluation; quick mode measures fewer iterations. */
    public StoreReplayEvaluation(boolean quick) {
        this.quick = quick;
        this.warmup = ReplayMeasurement.Warmup.of(quick);
        this.measurementIterations = quick ? 3 : 10;
        this.measurement = new ReplayMeasurement(new EvaluationMetricsCollector(), digests, warmup,
                measurementIterations);
    }

    /**
     * Returns the run configuration this evaluation actually uses; the warm-up is budgeted, so its reported count is the
     * minimum and each row records the actual count.
     */
    public RunConfiguration runConfiguration() {
        return new RunConfiguration(0L, quick, warmup.minIterations(), measurementIterations,
                "An iteration replays the exported fixture under one arm; the timed window includes the full replay"
                        + " and, for the arms that keep state, constructing and binding a fresh state (see each row's"
                        + " timedWindow); only validation of the previous run is untimed; latency (mean and"
                        + " percentiles) is per replay iteration, while throughput and allocation are per replayed"
                        + " decision; " + warmup.describe() + "; recording and export are not timed");
    }

    /** Records, exports, verifies and measures; never throws for a failing phase. */
    public Outcome run() {
        var checks = new ArrayList<Check>();
        var results = new ArrayList<BenchmarkRunResult>();
        var metadata = new TreeMap<String, String>();
        metadata.put("store.tasks", Integer.toString(TASKS));
        metadata.put("store.loggingPolicy", "static #62 rule over an empty preference");
        metadata.put("store.prices", "USD per 1000 tokens: input " + StoreReplaySource.INPUT_PER_THOUSAND
                + ", output " + StoreReplaySource.OUTPUT_PER_THOUSAND);
        StoreReplaySource.Export export = null;
        StoreReplaySource.Export second = null;
        // two independent temporary stores; each is closed and its directory checked, whatever failed before
        var stores = new ArrayList<StoreReplaySource>(2);
        try {
            StoreReplaySource first = StoreReplaySource.open();
            stores.add(first);
            export = first.recordAndExport(TASKS);
            StoreReplaySource again = StoreReplaySource.open();
            stores.add(again);
            second = again.recordAndExport(TASKS);
        } catch (RuntimeException | AssertionError failure) {
            checks.add(new Check("store.setup", false, describe(failure)));
        } finally {
            checks.add(cleanupCheck(stores));
        }
        if (export == null || second == null) {
            return new Outcome(checks, results, metadata);
        }
        StoreReplaySource.Export exported = export;
        StoreReplaySource.Export repeated = second;
        metadata.put("store.exportCutoff", Long.toString(exported.cutoff()));
        metadata.put("store.exportDigest", exported.exportDigest());
        metadata.put("store.observations", Integer.toString(exported.imported().observations().size()));
        metadata.put("store.rejections", exported.imported().rejections().stream()
                .map(r -> r.executionRef() + ":" + r.reason()).sorted().toList().toString());
        metadata.put("store.diagnostics", exported.imported().diagnostics().toString());

        Map<ReplayArm, ReplayRun> runs = new EnumMap<>(ReplayArm.class);
        check(checks, "store.import-complete-and-explained", () -> importCheck(exported));
        check(checks, "store.export-reproducible", () -> {
            String a = digests.fixture(exported.fixture());
            String b = digests.fixture(repeated.fixture());
            metadata.put("store.fixtureDigest", a);
            require(a.equals(b), "two recordings exported different fixtures: " + a + " vs " + b);
            require(exported.exportDigest().equals(repeated.exportDigest()), "Store export digests differ");
            return "two independent temporary stores export the same fixture " + a + " and Store export digest "
                    + exported.exportDigest();
        });
        check(checks, "store.replay-deterministic", () -> {
            var replay = new TemporalReplay(exported.fixture());
            for (ReplayArm arm : ReplayArm.values()) {
                runs.put(arm, replay.run(arm));
            }
            var again = new TemporalReplay(repeated.fixture());
            for (ReplayArm arm : ReplayArm.values()) {
                ReplayRun rerun = again.run(arm);
                require(digests.decisions(runs.get(arm)).equals(digests.decisions(rerun)), arm + " decisions differ");
                require(digests.feedback(runs.get(arm)).equals(digests.feedback(rerun)), arm + " feedback differs");
                String state = runs.get(arm).state().map(digests::state).orElse("none");
                String repeatedState = rerun.state().map(digests::state).orElse("none");
                require(state.equals(repeatedState), arm + " learned state differs: " + state + " vs "
                        + repeatedState);
            }
            return "decision, feedback and learned-state digests (and state presence) are identical across both"
                    + " exports for every arm";
        });
        if (runs.size() == ReplayArm.values().length) {
            shared(checks, "store.derived-not-consumed-equals-static",
                    () -> RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(runs)));
            shared(checks, "store.eligibility-violations-zero",
                    () -> RoutingReplayEvaluation.eligibilityCheck(List.of(runs)));
            shared(checks, "store.snapshot-watermark", () -> RoutingReplayEvaluation.watermarkCheck(List.of(runs)));
            shared(checks, "store.missing-counterfactual",
                    () -> RoutingReplayEvaluation.missingCounterfactualCheck(runs));
            check(checks, "store.cost-from-store-tooling", () -> costCheck(exported, runs));
            check(checks, "store.incremental-equals-rebuild", () -> {
                ReplayRun adaptive = runs.get(ReplayArm.ADAPTIVE);
                var rebuilt = new TemporalReplay(exported.fixture()).rebuildFromScratch(adaptive);
                require(rebuilt instanceof RebuildResult.Rebuilt, "rebuild failed: " + rebuilt);
                String incremental = digests.state(adaptive.state().orElseThrow());
                String scratch = digests.state(((RebuildResult.Rebuilt) rebuilt).store());
                require(incremental.equals(scratch), "incremental " + incremental + " vs scratch " + scratch);
                long processed = adaptive.state().orElseThrow().processedCutoff();
                require(processed == exported.cutoff(), "the state examined through " + processed
                        + ", not through the export cutoff " + exported.cutoff());
                return "state " + incremental + " equals a rebuild from scratch and has examined the export through its"
                        + " cutoff " + exported.cutoff();
            });
            var summaries = new ArrayList<ReplaySummary>();
            for (ReplayRun run : runs.values()) {
                ReplaySummary summary = ReplaySummary.of(run);
                summaries.add(summary);
                summary.lines().forEach((k, v) -> metadata.put("store." + k, v));
            }
            metadata.put("store.reportDigest", digests.report(summaries));
            for (ReplayArm arm : ReplayArm.values()) {
                try {
                    results.add(measurement.measure("Store" + arm.benchmarkName(), exported.fixture(), arm,
                            runs.get(arm)));
                } catch (RuntimeException | AssertionError failure) {
                    checks.add(new Check("store.measurement." + arm, false, describe(failure)));
                }
            }
        }
        return new Outcome(checks, results, metadata);
    }

    /**
     * Every finished execution contributes exactly one observation (revision 0, no correction was recorded), answering
     * exactly its own logged decision; the unfinished one is rejected with a reason. A total count alone would hide a
     * dropped execution compensated by a duplicate.
     */
    static String importCheck(StoreReplaySource.Export export) {
        int observations = export.imported().observations().size();
        List<ExperienceRejection> rejections = export.imported().rejections();
        var perDecision = new TreeMap<String, List<Long>>();
        for (RoutingObservation observation : export.imported().observations()) {
            perDecision.computeIfAbsent(observation.outcome().decisionRef().executionId(), ignored -> new ArrayList<>())
                    .add(observation.revision());
        }
        for (ReplayTask task : export.fixture().tasks()) {
            String execution = task.request().executionId();
            var logged = (OutcomeOracle.ChosenRouteOnly) task.oracle();
            boolean finished = !execution.equals("exe-" + (TASKS - 1));
            List<Long> revisions = perDecision.getOrDefault(execution, List.of());
            require(revisions.equals(finished ? List.of(0L) : List.of()), execution + " imported revisions "
                    + revisions + ", expected " + (finished ? "[0]" : "none"));
            require(logged.observed().size() == revisions.size()
                            && logged.observed().stream().allMatch(o -> o.outcome().decisionRef()
                                    .equals(logged.loggedDecision().provenance().decisionRef())),
                    execution + " fixture observations do not answer its own logged decision");
        }
        require(perDecision.keySet().stream().allMatch(e -> export.fixture().tasks().stream()
                .anyMatch(t -> t.request().executionId().equals(e))), "observations for unknown executions: "
                + perDecision.keySet());
        require(observations == TASKS - 1, "expected " + (TASKS - 1) + " observations, got " + observations);
        // the reference is the Store's encoded experience reference, which embeds the length-prefixed execution id
        require(rejections.size() == 1 && rejections.getFirst().executionRef().contains(":exe-" + (TASKS - 1) + "|"),
                "expected exactly the unfinished execution to be rejected, got " + rejections);
        require(rejections.getFirst().reason() == RejectionReason.MISSING_OUTCOME, "the unfinished execution was"
                + " rejected as " + rejections.getFirst().reason() + ", not MISSING_OUTCOME");
        require(export.imported().diagnostics().isEmpty(), "diagnostics: " + export.imported().diagnostics());
        return observations + " observations imported, exactly one (revision 0) per finished execution exe-0..exe-"
                + (TASKS - 2) + "; exe-" + (TASKS - 1) + " rejected as "
                + rejections.getFirst().reason() + " (no outcome to export)";
    }

    /**
     * The Store's cost reaches every observed sample unchanged, compared field by field with the Store's own
     * {@code CostToAcceptedOutcome} (status, every currency amount and every reason code), not through the mapping the
     * export used: an accepted {@code sub-b} execution costs {@code 1500 x 0.015 / 1000 + 400 x 0.080 / 1000 =
     * 0.0545 USD}, recomputed here from the token table and list prices, a rejection is unavailable for lack of a
     * validated acceptance, and a choice without an observed outcome carries no cost at all. Counts are over the
     * static arm's observed samples, so an execution the Store exported but no sample reached is not counted.
     */
    static String costCheck(StoreReplaySource.Export export, Map<ReplayArm, ReplayRun> runs) {
        BigDecimal accepted = SyntheticRoutingFixtures.listPrice(
                SyntheticRoutingFixtures.inputTokens(SyntheticRoutingFixtures.SUB_B),
                SyntheticRoutingFixtures.outputTokens(SyntheticRoutingFixtures.SUB_B));
        int available = 0;
        int unavailable = 0;
        for (ReplayRun run : runs.values()) {
            for (ReplaySample sample : run.samples()) {
                String execution = sample.decisionRef().executionId();
                if (sample.coverage() != ReplaySample.Coverage.OBSERVED) {
                    require(sample.chainCost().isEmpty(), run.arm() + " " + execution
                            + " reports a cost without an observed outcome");
                    continue;
                }
                CostToAcceptedOutcome store = export.costs().get(execution);
                require(store != null, run.arm() + " " + execution + " has no Store cost");
                ReplayCost reported = sample.chainCost().orElseThrow();
                require(reported.status().name().equals(store.status().name()), run.arm() + " " + execution
                        + " reports " + reported.status() + ", the Store " + store.status());
                require(reported.amounts().size() == store.amounts().size() && store.amounts().stream()
                        .allMatch(c -> reported.amounts().stream().anyMatch(m -> m.currency()
                                .equals(c.currency().getCurrencyCode()) && m.amount().compareTo(c.amount()) == 0)),
                        run.arm() + " " + execution + " reports " + reported.amounts() + ", the Store "
                                + store.amounts());
                require(reported.reasons().equals(store.reasons().stream().map(r -> r.code().name()).distinct()
                        .sorted().toList()), run.arm() + " " + execution + " reports reasons " + reported.reasons()
                        + ", the Store " + store.reasons());
                // the Store and the replay must agree that a priced chain ended in a validated acceptance
                require(reported.status() == ReplayCost.Status.UNAVAILABLE || sample.validatedAccepted(),
                        run.arm() + " " + execution + " has a " + reported.status()
                                + " cost but no validated acceptance");
                if (run.arm() != ReplayArm.STATIC) {
                    continue;
                }
                switch (reported.status()) {
                    case AVAILABLE -> {
                        require(reported.amounts().size() == 1
                                && reported.amounts().getFirst().amount().compareTo(accepted) == 0,
                                execution + " costs " + reported.amounts() + ", expected " + accepted + " USD");
                        available++;
                    }
                    case UNAVAILABLE -> unavailable++;
                    case PARTIAL -> throw new IllegalStateException(execution + " has a partial cost: " + store);
                }
            }
        }
        require(available > 0 && unavailable > 0,
                "both an available and an unavailable cost must reach an observed sample");
        return available + " AVAILABLE (" + accepted.toPlainString() + " USD each) and " + unavailable
                + " UNAVAILABLE Store costs reach the observed samples unchanged in every arm; unobserved choices"
                + " carry none";
    }

    /** Both temporary stores closed and their directories deleted; a store never opened is not reported. */
    private static Check cleanupCheck(List<StoreReplaySource> stores) {
        var failures = new ArrayList<String>();
        for (int i = 0; i < stores.size(); i++) {
            try {
                stores.get(i).close();
                if (stores.get(i).exists()) {
                    failures.add("store " + (i + 1) + ": directory still exists");
                }
            } catch (RuntimeException | AssertionError failure) {
                failures.add("store " + (i + 1) + ": " + describe(failure));
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("store.temporary-store-cleanup", passed, passed
                ? stores.size() + " temporary execution memories closed and deleted" : String.join("; ", failures));
    }

    /** Records a shared check under this arm's name, with its own verdict and detail; a crash is a failed check. */
    private static void shared(List<Check> checks, String name, Supplier<Check> check) {
        try {
            Check verdict = check.get();
            checks.add(new Check(name, verdict.passed(), verdict.detail()));
        } catch (RuntimeException | AssertionError failure) {
            checks.add(new Check(name, false, describe(failure)));
        }
    }

    private static void check(List<Check> checks, String name, Verification verification) {
        try {
            checks.add(new Check(name, true, verification.verify()));
        } catch (RuntimeException | AssertionError failure) {
            checks.add(new Check(name, false, describe(failure)));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    @FunctionalInterface
    private interface Verification {
        String verify();
    }
}
