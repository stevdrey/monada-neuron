package monada.neuron.evaluation.routing;

import com.monada.evaluation.execution.CostToAcceptedOutcome;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.resonance.adapter.experience.ExperienceRejection;
import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.outcome.RoutingObservation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /** One named semantic verdict. */
    public record Check(String name, boolean passed, String detail) implements EvaluationCheck {
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

    /** Logged executions; the last one never finishes. */
    static final int TASKS = 16;

    private final boolean quick;
    private final int warmupIterations;
    private final int measurementIterations;
    private final ReplayDigests digests = new ReplayDigests();
    private final EvaluationMetricsCollector collector = new EvaluationMetricsCollector();

    /** Creates the evaluation; quick mode measures fewer iterations. */
    public StoreReplayEvaluation(boolean quick) {
        this.quick = quick;
        this.warmupIterations = quick ? 1 : 3;
        this.measurementIterations = quick ? 3 : 10;
    }

    /** Returns the run configuration this evaluation actually uses. */
    public RunConfiguration runConfiguration() {
        return new RunConfiguration(0L, quick, warmupIterations, measurementIterations,
                "One op is one replayed decision; an iteration replays the exported fixture under one arm from a fresh"
                        + " state (setup excluded from timing); recording and export are not timed");
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
        StoreReplaySource first = null;
        try {
            first = StoreReplaySource.open();
            export = first.recordAndExport(TASKS);
            try (StoreReplaySource again = StoreReplaySource.open()) {
                second = again.recordAndExport(TASKS);
            }
        } catch (RuntimeException failure) {
            checks.add(new Check("store.setup", false, describe(failure)));
        } finally {
            if (first != null) {
                try {
                    first.close();
                    boolean deleted = !first.exists();
                    checks.add(new Check("store.temporary-store-cleanup", deleted,
                            deleted ? "temporary execution memory deleted" : "directory still exists"));
                } catch (RuntimeException failure) {
                    checks.add(new Check("store.temporary-store-cleanup", false, describe(failure)));
                }
            }
        }
        if (export == null || second == null) {
            return new Outcome(checks, results, metadata);
        }
        StoreReplaySource.Export exported = export;
        StoreReplaySource.Export repeated = second;
        metadata.put("store.exportCutoff", Long.toString(exported.cutoff()));
        metadata.put("store.exportDigest", exported.exportDigest());
        metadata.put("store.fixtureDigest", digests.fixture(exported.fixture()));
        metadata.put("store.observations", Integer.toString(exported.imported().observations().size()));
        metadata.put("store.rejections", exported.imported().rejections().stream()
                .map(r -> r.executionRef() + ":" + r.reason()).sorted().toList().toString());
        metadata.put("store.diagnostics", exported.imported().diagnostics().toString());

        Map<ReplayArm, ReplayRun> runs = new EnumMap<>(ReplayArm.class);
        check(checks, "store.import-complete-and-explained", () -> importCheck(exported));
        check(checks, "store.export-reproducible", () -> {
            String a = digests.fixture(exported.fixture());
            String b = digests.fixture(repeated.fixture());
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
            }
            return "decision and feedback digests are identical across both exports for every arm";
        });
        if (runs.size() == ReplayArm.values().length) {
            check(checks, "store.derived-not-consumed-equals-static", () -> {
                var control = RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(runs));
                require(control.passed(), control.detail());
                return control.detail();
            });
            check(checks, "store.eligibility-violations-zero", () -> {
                var eligibility = RoutingReplayEvaluation.eligibilityCheck(List.of(runs));
                require(eligibility.passed(), eligibility.detail());
                return eligibility.detail();
            });
            check(checks, "store.snapshot-watermark", () -> {
                var watermark = RoutingReplayEvaluation.watermarkCheck(List.of(runs));
                require(watermark.passed(), watermark.detail());
                return watermark.detail();
            });
            check(checks, "store.missing-counterfactual", () -> {
                var missing = RoutingReplayEvaluation.missingCounterfactualCheck(runs);
                require(missing.passed(), missing.detail());
                return missing.detail();
            });
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
                    results.add(measure(exported.fixture(), arm));
                } catch (RuntimeException failure) {
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
        require(export.imported().diagnostics().isEmpty(), "diagnostics: " + export.imported().diagnostics());
        return observations + " observations imported, exactly one (revision 0) per finished execution exe-0..exe-"
                + (TASKS - 2) + "; exe-" + (TASKS - 1) + " rejected as "
                + rejections.getFirst().reason() + " (no outcome to export)";
    }

    /**
     * The Store's cost reaches every observed sample unchanged: an accepted {@code sub-b} execution costs
     * {@code 1500 x 0.015 / 1000 + 400 x 0.080 / 1000 = 0.0545 USD}, a rejection is unavailable for lack of a validated
     * acceptance, and a missing counterfactual carries no cost at all.
     */
    static String costCheck(StoreReplaySource.Export export, Map<ReplayArm, ReplayRun> runs) {
        var expected = new BigDecimal("0.0545");
        int available = 0;
        int unavailable = 0;
        for (Map.Entry<String, CostToAcceptedOutcome> entry : new TreeMap<>(export.costs()).entrySet()) {
            CostToAcceptedOutcome cost = entry.getValue();
            switch (cost.status()) {
                case AVAILABLE -> {
                    require(cost.amounts().size() == 1 && cost.amounts().getFirst().amount().compareTo(expected) == 0,
                            entry.getKey() + " costs " + cost.amounts() + ", expected 0.0545 USD");
                    available++;
                }
                case UNAVAILABLE -> unavailable++;
                case PARTIAL -> throw new IllegalStateException(entry.getKey() + " has a partial cost: " + cost);
            }
        }
        for (ReplayRun run : runs.values()) {
            for (ReplaySample sample : run.samples()) {
                if (sample.coverage() != ReplaySample.Coverage.OBSERVED) {
                    require(sample.chainCost().isEmpty(), run.arm() + " " + sample.decisionRef().executionId()
                            + " reports a cost without an observed outcome");
                    continue;
                }
                CostToAcceptedOutcome store = export.costs().get(sample.decisionRef().executionId());
                require(store != null && StoreReplaySource.replayCost(store).equals(sample.chainCost().orElseThrow()),
                        run.arm() + " " + sample.decisionRef().executionId() + " cost differs from the Store's");
            }
        }
        require(available > 0 && unavailable > 0, "both an available and an unavailable cost must be exercised");
        return available + " AVAILABLE (0.0545 USD each) and " + unavailable
                + " UNAVAILABLE Store costs reach the samples unchanged; unobserved choices carry none";
    }

    private BenchmarkRunResult measure(ReplayFixture fixture, ReplayArm arm) {
        var replay = new TemporalReplay(fixture);
        ReplayRun reference = replay.run(arm, false);
        RoutingReplayEvaluation.requireSameReplay(reference, replay.run(arm), digests);
        var diagnostics = new LinkedHashMap<String, String>();
        diagnostics.put("stateDigest", reference.state().map(digests::state).orElse("none"));
        diagnostics.put("decisionTraceDigest", digests.decisions(reference.decisions()));
        diagnostics.put("decisionsPerIteration", Integer.toString(fixture.tasks().size()));
        diagnostics.put("deliveredObservations", Integer.toString(reference.delivered().size()));
        diagnostics.put("validationInWindow", "false");
        var pending = new ReplayRun[1];
        BenchmarkRunResult result = collector.measure("Store" + arm.benchmarkName(), "tasks=" + fixture.tasks().size(),
                warmupIterations, measurementIterations, fixture.tasks().size(),
                () -> {
                    if (pending[0] != null) {
                        RoutingReplayEvaluation.requireSameReplay(pending[0], reference, digests);
                    }
                    pending[0] = null;
                },
                () -> pending[0] = replay.run(arm, false),
                diagnostics);
        // the collector runs no setup after its last iteration, so the final measured run is validated here
        RoutingReplayEvaluation.requireSameReplay(pending[0], reference, digests);
        return result;
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
