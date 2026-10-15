package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.feedback.ScoreRule;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.UsageKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Temporal replay evaluation of Forge routing quality, cost and adaptation controls (Issue #67).
 *
 * <p>Three arms ({@link ReplayArm}) replay identical synthetic fixtures ({@link SyntheticRoutingFixtures}) and a
 * store-like chosen-route-only fixture chronologically. Correctness verdicts ({@link Check}) are semantic and
 * deterministic: determinism, leakage, controls, eligibility, rebuild equivalence, faithful reporting of edge cases,
 * visibility of both an improvement and a degradation, and bounds. Every figure is a count over a fixture with known
 * outcomes or over chosen-route-only observations; no figure is a causal or online-improvement claim. Latency,
 * allocation and GC are exploratory diagnostics measured after verification, and never influence a verdict.
 */
public final class RoutingReplayEvaluation {

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

    /** Scenario sizes are fixed so every check sees the same fixture in quick and full mode. */
    static final int SCENARIO_TASKS = 24;
    static final int NEUTRAL_TASKS = 16;
    static final int CHOSEN_ROUTE_TASKS = 16;
    /**
     * Leakage threshold: the decision of task 6 of the improvement scenario, before the adaptive arm has enough
     * support to move, so flipping later outcomes can still change later decisions (the check's guard).
     */
    static final int LEAKAGE_TASK = 6;
    /** Late correction of task 5 of the improvement scenario, visible six decision slots after the original. */
    static final int LATE_CORRECTION_TASK = 5;
    static final int LATE_CORRECTION_SLOTS = 6;

    private final boolean quick;
    private final int workloadTasks;
    private final int warmupIterations;
    private final int measurementIterations;
    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();
    private final ReplayDigests digests = new ReplayDigests();
    private final ReplayMeasurement measurement;
    private final Map<String, String> metadata = new TreeMap<>();

    /** Creates the evaluation; quick mode uses a smaller workload suitable for tests. */
    public RoutingReplayEvaluation(boolean quick) {
        this.quick = quick;
        this.workloadTasks = quick ? 240 : 960;
        this.warmupIterations = quick ? 1 : 3;
        this.measurementIterations = quick ? 3 : 10;
        this.measurement = new ReplayMeasurement(new EvaluationMetricsCollector(), digests, warmupIterations,
                measurementIterations);
    }

    /**
     * Returns the run configuration this evaluation actually uses, so a report cannot state different iteration
     * counts than were measured. The fixtures are enumerated, so there is no seed.
     */
    public RunConfiguration runConfiguration() {
        return new RunConfiguration(0L, quick, warmupIterations, measurementIterations,
                "An iteration replays the whole workload fixture under one arm; the timed window includes the full"
                        + " replay and, for the arms that keep state, constructing and binding a fresh state (see each"
                        + " row's timedWindow); synthetic observations are generated once and reused, and only"
                        + " validation of the previous run is untimed; latency (mean and percentiles) is per replay"
                        + " iteration, while throughput and allocation are per replayed decision (see each row's"
                        + " latencyScope and meanNanosPerDecision); fixtures are enumerated, so the seed is unused");
    }

    /**
     * Runs every semantic check, then the measurements of the verified workload runs. A measured run that differs
     * from its verified run is a failed {@code measurement.<arm>} check, never a crash.
     */
    public Outcome run() {
        metadata.clear();
        Verified verified = verifyAll();
        var checks = new ArrayList<>(verified.checks());
        var results = new ArrayList<BenchmarkRunResult>();
        for (ReplayArm arm : ReplayArm.values()) {
            try {
                results.add(measurement.measure(arm.benchmarkName(), verified.workload(), arm,
                        verified.runs().get(arm)));
            } catch (IllegalStateException failure) {
                checks.add(new Check("measurement." + arm.benchmarkName(), false, failure.getMessage()));
            }
        }
        return new Outcome(checks, results, metadata());
    }

    /** Every semantic check; package-private so tests can run it without measuring. */
    List<Check> verify() {
        return verifyAll().checks();
    }

    /** The checks, the workload and its verified runs, which the measurements reuse. */
    private record Verified(List<Check> checks, ReplayFixture workload, Map<ReplayArm, ReplayRun> runs) {
    }

    private Verified verifyAll() {
        var checks = new ArrayList<Check>();
        ReplayFixture workload = fixtures.workload(workloadTasks);
        Map<ReplayArm, ReplayRun> runs = runAll(workload);
        Map<String, String> fingerprint = fingerprint(workload, runs);
        // an independently built fixture, replayed and digested itself
        ReplayFixture rebuiltWorkload = fixtures.workload(workloadTasks);
        checks.add(determinismCheck(fingerprint, fingerprint(rebuiltWorkload, runAll(rebuiltWorkload))));

        ReplayFixture improvement = fixtures.improvement(SCENARIO_TASKS);
        ReplayFixture degradation = fixtures.degradation(SCENARIO_TASKS);
        ReplayFixture neutral = fixtures.neutral(NEUTRAL_TASKS);
        ReplayFixture cases = fixtures.cases();
        ReplayFixture chosen = fixtures.chosenRouteOnly(CHOSEN_ROUTE_TASKS);
        Map<ReplayArm, ReplayRun> improvementRuns = runAll(improvement);
        Map<ReplayArm, ReplayRun> degradationRuns = runAll(degradation);
        Map<ReplayArm, ReplayRun> neutralRuns = runAll(neutral);
        Map<ReplayArm, ReplayRun> caseRuns = runAll(cases);
        Map<ReplayArm, ReplayRun> chosenRuns = runAll(chosen);
        var all = List.of(runs, improvementRuns, degradationRuns, neutralRuns, caseRuns, chosenRuns);

        long leakageCutoff = fixtures.cutoff(LEAKAGE_TASK);
        checks.add(futureLeakageCheck(improvementRuns,
                runAll(fixtures.futureFlipped(improvement, leakageCutoff)),
                runAll(fixtures.truncated(improvement, leakageCutoff)), leakageCutoff));
        ReplayFixture corrected = fixtures.lateCorrection(improvement, LATE_CORRECTION_TASK, LATE_CORRECTION_SLOTS);
        checks.add(lateCorrectionCheck(improvementRuns, runAll(corrected), correctionPosition(corrected)));
        checks.add(watermarkCheck(all));

        checks.add(derivedNotConsumedCheck(List.of(runs, improvementRuns, degradationRuns, caseRuns, chosenRuns)));
        checks.add(staticWiringCheck(workload, runs.get(ReplayArm.STATIC)));
        checks.add(neutralCheck(neutralRuns));
        checks.add(eligibilityCheck(all));
        checks.add(costCompletenessCheck(all));

        var rebuilt = new ArrayList<RebuildEvidence>();
        for (var scenario : List.of(new Scenario("cases", cases, caseRuns),
                new Scenario("improvement", improvement, improvementRuns), new Scenario("workload", workload, runs))) {
            rebuilt.add(rebuildEvidence(scenario));
        }
        checks.add(rebuildCheck(rebuilt));

        checks.add(cheapInvalidCheck(caseRuns));
        checks.add(repairedAcceptedCheck(caseRuns));
        checks.add(unknownUsageCheck(caseRuns));
        checks.add(pairingCheck(caseRuns));
        checks.add(currencyCheck(caseRuns));
        checks.add(abstentionCheck(caseRuns));
        checks.add(missingCounterfactualCheck(chosenRuns));
        checks.add(visibilityCheck(ReplaySummary.of(improvementRuns.get(ReplayArm.STATIC)),
                ReplaySummary.of(improvementRuns.get(ReplayArm.ADAPTIVE)),
                ReplaySummary.of(degradationRuns.get(ReplayArm.STATIC)),
                ReplaySummary.of(degradationRuns.get(ReplayArm.ADAPTIVE))));
        checks.add(boundsCheck(workload, runs.get(ReplayArm.ADAPTIVE)));

        recordMetadata(workload, runs, fingerprint, Map.of("improvement", improvementRuns, "degradation",
                degradationRuns, "neutral", neutralRuns, "cases", caseRuns, "chosen-route-only", chosenRuns),
                List.of(improvement, degradation, neutral, cases, chosen));
        return new Verified(checks, workload, runs);
    }

    /** A named fixture and its runs. */
    private record Scenario(String name, ReplayFixture fixture, Map<ReplayArm, ReplayRun> runs) {
    }

    /**
     * Whether a scenario's incrementally built adaptive state equals a rebuild from scratch and has examined the
     * fixture through its horizon.
     *
     * @param scenario the scenario
     * @param equal incremental and scratch state digests are equal
     * @param processedCutoff the incremental state's watermark
     * @param horizon the fixture's horizon
     * @param ledger the incremental state's ledger entries
     * @param rebuilds correction-triggered rebuilds during the run
     * @param failure why the scratch rebuild failed, if it did
     */
    record RebuildEvidence(String scenario, boolean equal, long processedCutoff, long horizon, int ledger,
            int rebuilds, String failure) {

        boolean passed() {
            return equal && processedCutoff == horizon;
        }

        @Override
        public String toString() {
            return scenario + ":" + passed() + ":processedCutoff=" + processedCutoff + "/horizon=" + horizon
                    + ":ledger=" + ledger + ":rebuilds=" + rebuilds + (failure.isEmpty() ? "" : ":" + failure);
        }
    }

    private RebuildEvidence rebuildEvidence(Scenario scenario) {
        ReplayRun adaptive = scenario.runs().get(ReplayArm.ADAPTIVE);
        RoutingStateStore state = adaptive.state().orElseThrow();
        String incremental = digests.state(state);
        boolean equal;
        String failure = "";
        switch (new TemporalReplay(scenario.fixture()).rebuildFromScratch(adaptive)) {
            case RebuildResult.Rebuilt done -> equal = incremental.equals(digests.state(done.store()));
            case RebuildResult.Failed failed -> {
                equal = false;
                failure = "failed at " + failed.observation().experienceRef();
            }
        }
        return new RebuildEvidence(scenario.name(), equal, state.processedCutoff(), scenario.fixture().horizon(),
                state.ledger().size(), count(adaptive, e -> e.update() == FeedbackEvent.Update.REBUILT), failure);
    }

    private Map<ReplayArm, ReplayRun> runAll(ReplayFixture fixture) {
        var replay = new TemporalReplay(fixture);
        var runs = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        for (ReplayArm arm : ReplayArm.values()) {
            runs.put(arm, replay.run(arm));
        }
        return runs;
    }

    /** Every digest of one replay of every arm: fixture, decisions, feedback, learned state and report. */
    private Map<String, String> fingerprint(ReplayFixture fixture, Map<ReplayArm, ReplayRun> runs) {
        var fingerprint = new TreeMap<String, String>();
        fingerprint.put("fixture", digests.fixture(fixture));
        var summaries = new ArrayList<ReplaySummary>();
        for (ReplayRun run : runs.values()) {
            String arm = run.arm().benchmarkName();
            fingerprint.put(arm + ".decisions", digests.decisions(run));
            fingerprint.put(arm + ".feedback", digests.feedback(run));
            run.state().ifPresent(state -> fingerprint.put(arm + ".state", digests.state(state)));
            fingerprint.put(arm + ".samples", Integer.toString(run.samples().size()));
            summaries.add(ReplaySummary.of(run));
        }
        fingerprint.put("report", digests.report(summaries));
        return fingerprint;
    }

    // ---- checks (package-private and static so tests can feed them synthetic inputs) ----

    static Check determinismCheck(Map<String, String> first, Map<String, String> second) {
        // Equal empty fingerprints are vacuous: something must have been replayed.
        boolean replayed = first.entrySet().stream()
                .anyMatch(e -> e.getKey().endsWith(".samples") && !e.getValue().equals("0"));
        boolean passed = replayed && first.equals(second);
        return new Check("replay.deterministic", passed, passed
                ? first.size() + " digests identical across two replays (fixture, decisions, feedback, state, report "
                        + first.get("report") + ")"
                : "replayed=" + replayed + ", differing=" + differing(first, second));
    }

    static Check futureLeakageCheck(Map<ReplayArm, ReplayRun> original, Map<ReplayArm, ReplayRun> flipped,
            Map<ReplayArm, ReplayRun> truncated, long cutoff) {
        var failures = new ArrayList<String>();
        boolean laterChanged = false;
        for (ReplayArm arm : ReplayArm.values()) {
            List<ReplaySample> base = original.get(arm).samples();
            List<ReplaySample> mutated = flipped.get(arm).samples();
            List<ReplaySample> prefix = truncated.get(arm).samples();
            List<ReplaySample> before = base.stream().filter(s -> s.cutoff() < cutoff).toList();
            if (!projections(before).equals(projections(mutated.stream().filter(s -> s.cutoff() < cutoff).toList()))
                    || !watermarks(before).equals(watermarks(mutated.stream().filter(s -> s.cutoff() < cutoff)
                            .toList()))) {
                failures.add(arm + ": flipping outcomes visible at or after " + cutoff
                        + " changed an earlier decision");
            }
            if (!prefix.equals(before)) {
                failures.add(arm + ": the truncated replay differs from the prefix of the full replay");
            }
            laterChanged |= !projections(base).equals(projections(mutated));
        }
        // Equal earlier decisions are vacuous if the mutation could not change any decision at all.
        if (!laterChanged) {
            failures.add("the mutation changed no later decision, so the comparison proves nothing");
        }
        return new Check("leakage.future-outcomes", failures.isEmpty(), failures.isEmpty()
                ? "decisions and snapshot watermarks before " + cutoff + " are unchanged by flipped future outcomes and"
                        + " equal a replay truncated there; later adaptive decisions did change"
                : String.join("; ", failures));
    }

    static Check lateCorrectionCheck(Map<ReplayArm, ReplayRun> original, Map<ReplayArm, ReplayRun> corrected,
            long position) {
        var failures = new ArrayList<String>();
        for (ReplayArm arm : ReplayArm.values()) {
            var before = original.get(arm).samples().stream().filter(s -> s.cutoff() < position).toList();
            var after = corrected.get(arm).samples().stream().filter(s -> s.cutoff() < position).toList();
            if (!projections(before).equals(projections(after))) {
                failures.add(arm + ": a correction visible at " + position + " changed an earlier decision");
            }
        }
        // The correction must actually have been processed, or nothing was tested.
        int rebuilds = count(corrected.get(ReplayArm.ADAPTIVE), e -> e.update() == FeedbackEvent.Update.REBUILT);
        if (rebuilds == 0) {
            failures.add("the adaptive arm never rebuilt, so the correction was not processed");
        }
        return new Check("leakage.late-correction", failures.isEmpty(), failures.isEmpty()
                ? "decisions before the correction at " + position + " are unchanged; the adaptive arm rebuilt "
                        + rebuilds + " time(s) when it arrived"
                : String.join("; ", failures));
    }

    static Check watermarkCheck(List<Map<ReplayArm, ReplayRun>> all) {
        int decisions = 0;
        var failures = new ArrayList<String>();
        for (var runs : all) {
            for (ReplayRun run : runs.values()) {
                for (ReplaySample sample : run.samples()) {
                    decisions++;
                    // the cutoff is inclusive: a snapshot may have examined up to, never beyond, the decision cutoff;
                    // the adaptive state is the state as of the cutoff, the other arms read no learned state
                    long expected = run.arm() == ReplayArm.ADAPTIVE ? sample.cutoff() : 0L;
                    if (sample.stateCutoff() > sample.cutoff() || sample.stateCutoff() != expected) {
                        failures.add(run.arm() + " " + sample.decisionRef().executionId() + ": state watermark "
                                + sample.stateCutoff() + " for decision cutoff " + sample.cutoff() + ", expected "
                                + expected);
                    }
                }
            }
        }
        boolean passed = decisions > 0 && failures.isEmpty();
        return new Check("leakage.snapshot-watermark", passed, passed
                ? "every one of " + decisions + " decisions read a snapshot that examined nothing after its"
                        + " (inclusive) cutoff; every adaptive snapshot is the state as of its cutoff"
                : decisions == 0 ? "no decision was replayed" : String.join("; ", failures));
    }

    static Check derivedNotConsumedCheck(List<Map<ReplayArm, ReplayRun>> all) {
        int derived = 0;
        int differing = 0;
        for (var runs : all) {
            ReplayRun control = runs.get(ReplayArm.STATIC);
            ReplayRun derivedOnly = runs.get(ReplayArm.DERIVED_NOT_CONSUMED);
            derived += count(derivedOnly, e -> e.rule().isPresent());
            if (!projections(control.samples()).equals(projections(derivedOnly.samples()))) {
                differing++;
            }
        }
        // Identical decisions alone are vacuous: they also hold when no feedback was derived.
        boolean passed = differing == 0 && derived > 0;
        return new Check("control.derived-not-consumed-equals-static", passed, "fixtures=" + all.size()
                + ", differing=" + differing + ", derivedFeedback=" + derived);
    }

    /**
     * The static arm is wired as the cold-start control: it makes the decisions the policy makes over an empty
     * preference, builds and reads no state, delivers and derives nothing, and selects only by cold-start or host
     * priority. This checks the harness's wiring, not #62's behaviour, which the expected decisions are computed from
     * and which #62's own tests cover.
     */
    static Check staticWiringCheck(ReplayFixture fixture, ReplayRun staticRun) {
        var expected = new ArrayList<String>();
        for (ReplayTask task : fixture.tasks()) {
            var decision = fixture.policy().decide(task.request(), task.catalog(),
                    RoutingPreference.empty(task.request(), fixture.definition().state(),
                            fixture.policy().policyId(), fixture.policy().policyVersion()));
            expected.add(switch (decision) {
                case RoutingDecision.Selected s -> "SELECTED|" + s.route() + "|" + s.basis()
                        + "|-";
                case RoutingDecision.Abstain a -> "ABSTAINED|-|-|" + a.reason();
                case RoutingDecision.NoEligibleRoute ignored -> "NO_ELIGIBLE_ROUTE|-|-|-";
            });
        }
        List<String> actual = projections(staticRun.samples());
        var failures = new ArrayList<String>();
        if (expected.isEmpty() || !expected.equals(actual)) {
            failures.add("decisions differ at " + firstDifference(expected, actual));
        }
        if (staticRun.state().isPresent() || !staticRun.delivered().isEmpty() || !staticRun.feedback().isEmpty()
                || !staticRun.derivations().equals(Derivations.none())) {
            failures.add("the static arm kept state, delivered or derived feedback");
        }
        if (staticRun.samples().stream().anyMatch(s -> s.stateCutoff() != 0L)) {
            failures.add("a static snapshot examined something");
        }
        if (staticRun.samples().stream().flatMap(s -> s.basis().stream())
                .anyMatch(b -> b != Basis.COLD_START && b != Basis.HOST_PRIORITY)) {
            failures.add("a static selection used a basis other than cold start or host priority");
        }
        boolean passed = failures.isEmpty();
        return new Check("control.static-wiring", passed, passed
                ? expected.size() + " static decisions equal the policy's decisions over an empty preference, with no"
                        + " state, delivery or derivation and only cold-start or host-priority selections (a wiring"
                        + " check of the control arm)"
                : String.join("; ", failures));
    }

    Check neutralCheck(Map<ReplayArm, ReplayRun> runs) {
        ReplayRun adaptive = runs.get(ReplayArm.ADAPTIVE);
        RoutingStateStore state = adaptive.state().orElseThrow();
        boolean unchanged = digests.cohorts(state).equals(digests.cohorts(state.resetCopy()));
        boolean sameDecisions = projections(runs.get(ReplayArm.STATIC).samples())
                .equals(projections(adaptive.samples()));
        int neutral = count(adaptive, e -> e.rule().map(r -> r.disposition() == FeedbackDisposition.NEUTRAL)
                .orElse(false));
        int nonNeutral = count(adaptive, e -> e.rule().map(r -> r.disposition() != FeedbackDisposition.NEUTRAL)
                .orElse(false));
        // Unchanged state is vacuous if nothing neutral was ever consumed.
        boolean passed = unchanged && sameDecisions && neutral > 0 && nonNeutral == 0;
        return new Check("control.neutral-feedback-leaves-state-unchanged", passed, "neutralConsumed=" + neutral
                + ", nonNeutral=" + nonNeutral + ", cohortsEqualBaseline=" + unchanged + ", decisionsEqualStatic="
                + sameDecisions + ", ledgerEntries=" + state.ledger().size());
    }

    static Check eligibilityCheck(List<Map<ReplayArm, ReplayRun>> all) {
        var violations = new TreeMap<String, Integer>();
        int credited = 0;
        int events = 0;
        for (var runs : all) {
            for (ReplayRun run : runs.values()) {
                for (FeedbackEvent event : run.feedback()) {
                    events++;
                    if (run.arm() == ReplayArm.ADAPTIVE && event.credits()) {
                        credited++;
                    }
                    if (event.eligibilityViolation()) {
                        violations.merge(run.arm() + "." + event.stageKind(), 1, Integer::sum);
                    }
                }
            }
        }
        // Zero violations are vacuous if no feedback ever credited a cohort.
        boolean passed = violations.isEmpty() && credited > 0;
        return new Check("eligibility.violations-zero", passed, "events=" + events + ", adaptiveCredits=" + credited
                + ", violationsByArmAndStage=" + violations);
    }

    /**
     * No observed sample whose routed stage usage is incomplete ({@link ReplaySample#stageUsageIncomplete()}: unknown,
     * not-measured or unrecorded {@code MEASURED} usage) reports a complete cost: the routed stage is part of the chain,
     * so the chain cannot be fully covered (execution-memory contract v1 section 8). {@code NO_BILLABLE_USAGE} is
     * complete.
     */
    static Check costCompletenessCheck(List<Map<ReplayArm, ReplayRun>> all) {
        int uncovered = 0;
        var failures = new ArrayList<String>();
        for (var runs : all) {
            for (ReplayRun run : runs.values()) {
                for (ReplaySample sample : run.samples()) {
                    if (!sample.stageUsageIncomplete()) {
                        continue;
                    }
                    uncovered++;
                    if (sample.chainCost().map(ReplayCost::status).orElse(null) == ReplayCost.Status.AVAILABLE) {
                        failures.add(run.arm() + " " + sample.decisionRef().executionId());
                    }
                }
            }
        }
        // Consistency is vacuous if no sample with incomplete usage was ever observed.
        boolean passed = uncovered > 0 && failures.isEmpty();
        return new Check("cost.completeness-consistent", passed, passed
                ? uncovered + " observed samples with unknown, not-measured or unrecorded stage usage, none with an"
                        + " AVAILABLE cost"
                : uncovered == 0 ? "no observed sample had incomplete usage, so nothing was checked"
                        : failures.size() + " complete costs despite incomplete usage: "
                                + failures.subList(0, Math.min(5, failures.size())));
    }

    static Check rebuildCheck(List<RebuildEvidence> results) {
        boolean equal = !results.isEmpty() && results.stream().allMatch(RebuildEvidence::passed);
        boolean exercised = results.stream().anyMatch(r -> r.rebuilds() > 0);
        boolean passed = equal && exercised;
        return new Check("state.incremental-equals-rebuild", passed,
                String.join(", ", results.stream().map(RebuildEvidence::toString).toList())
                        + (exercised ? "" : " (no correction-triggered rebuild was exercised)"));
    }

    static Check cheapInvalidCheck(Map<ReplayArm, ReplayRun> runs) {
        var failures = new ArrayList<String>();
        for (ReplayRun run : runs.values()) {
            ReplaySample cheap = sampleOf(run, "task-cheap");
            if (!cheap.provenFailures().contains(QualityDimension.SECURITY)
                    || cheap.acceptance().orElse(null) != DerivedAcceptance.NOT_ACCEPTED
                    || cheap.chainCost().map(ReplayCost::status).orElse(null) != ReplayCost.Status.UNAVAILABLE) {
                failures.add(run.arm() + ": " + cheap);
            }
            for (FeedbackEvent event : eventsOf(run, cheap)) {
                if (event.rule().orElse(null) != ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE) {
                    failures.add(run.arm() + " feedback " + event.rule() + event.noUpdate());
                }
            }
        }
        boolean derived = !eventsOf(runs.get(ReplayArm.ADAPTIVE), sampleOf(runs.get(ReplayArm.ADAPTIVE),
                "task-cheap")).isEmpty();
        boolean passed = failures.isEmpty() && derived;
        return new Check("cases.cheap-invalid", passed, passed
                ? "a few-token route with a proven security failure is a gate failure, NOT_ACCEPTED, cost UNAVAILABLE"
                        + " and penalized; never rewarded for its low usage"
                : derived ? String.join("; ", failures) : "no feedback was derived for the cheap invalid task");
    }

    static Check repairedAcceptedCheck(Map<ReplayArm, ReplayRun> runs) {
        var failures = new ArrayList<String>();
        BigDecimal expected = new BigDecimal("0.0735");
        for (ReplayRun run : runs.values()) {
            ReplaySample repaired = sampleOf(run, "task-repair");
            boolean costOk = repaired.chainCost().filter(c -> c.status() == ReplayCost.Status.AVAILABLE
                    && c.amounts().size() == 1 && c.amounts().getFirst().amount().compareTo(expected) == 0).isPresent();
            if (repaired.attempts() != 2 || !repaired.validatedAccepted() || !costOk) {
                failures.add(run.arm() + ": " + repaired);
            }
            for (FeedbackEvent event : eventsOf(run, repaired)) {
                if (event.noUpdate().orElse(null) != NoUpdateReason.NO_STAGE_ATTRIBUTION || event.credits()) {
                    failures.add(run.arm() + " credited a chain-only repair: " + event);
                }
            }
            ReplaySummary summary = ReplaySummary.of(run);
            // the chain is counted once per execution, however many of its stages were routed
            long attempts = ReplaySummary.firstObservedPerExecution(run.samples()).stream()
                    .mapToLong(ReplaySample::attempts).sum();
            if (summary.chain().attempts() != attempts || summary.chain().rework() != 1) {
                failures.add(run.arm() + ": chain attempts " + summary.chain().attempts() + " / rework "
                        + summary.chain().rework() + " are not counted once per execution");
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("cases.repaired-accepted", passed, passed
                ? "2 attempts, validated acceptance, chain cost 0.0735 USD counted once, no credit (chain-only)"
                : String.join("; ", failures));
    }

    /** The known input tokens of the unknown-usage case at the list prices: the only part of its cost known. */
    private static final BigDecimal UNKNOWN_USAGE_LOWER_BOUND = SyntheticRoutingFixtures.INPUT_PER_THOUSAND
            .multiply(BigDecimal.valueOf(SyntheticRoutingFixtures.UNKNOWN_USAGE_INPUT_TOKENS)).movePointLeft(3);

    static Check unknownUsageCheck(Map<ReplayArm, ReplayRun> runs) {
        var failures = new ArrayList<String>();
        for (ReplayRun run : runs.values()) {
            ReplaySample sample = sampleOf(run, "task-unknown-usage");
            boolean unknownKept = sample.stageUsage().stream().anyMatch(c -> c.kind() == UsageKind.OUTPUT_TOKENS
                    && c.value() instanceof ResourceValue.Unknown)
                    && sample.stageUsage().stream().anyMatch(c -> c.kind() == UsageKind.REASONING_TOKENS
                            && c.value() instanceof ResourceValue.NotMeasured);
            ReplaySummary summary = ReplaySummary.of(run);
            var output = summary.total().tokens().get(UsageKind.OUTPUT_TOKENS.name());
            var reasoning = summary.total().tokens().get(UsageKind.REASONING_TOKENS.name());
            boolean partial = sample.chainCost().map(ReplayCost::status).orElse(null) == ReplayCost.Status.PARTIAL
                    && summary.chain().partialLowerBound().getOrDefault("USD", BigDecimal.ZERO)
                            .compareTo(UNKNOWN_USAGE_LOWER_BOUND) == 0;
            if (!unknownKept || output == null || output.unknown() != 1 || reasoning == null
                    || reasoning.notMeasured() != 1 || !partial) {
                failures.add(run.arm() + ": unknownKept=" + unknownKept + ", output=" + output + ", reasoning="
                        + reasoning + ", partial=" + partial);
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("cases.unknown-usage", passed, passed
                ? "unknown and not-measured counters are counted as such (never as zero); the cost is a separate"
                        + " PARTIAL lower bound of " + UNKNOWN_USAGE_LOWER_BOUND.toPlainString() + " USD"
                : String.join("; ", failures));
    }

    static Check pairingCheck(Map<ReplayArm, ReplayRun> runs) {
        var failures = new ArrayList<String>();
        for (ReplayRun run : runs.values()) {
            ReplaySummary.Pairing pairing = ReplaySummary.of(run).pairing();
            if (pairing.comparable() != 1 || pairing.incomparable() != 2
                    || !pairing.incomparableBy().equals(Map.of("context", 1, "stageKind", 1))) {
                failures.add(run.arm() + ": " + pairing);
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("cases.incomparable-context-and-stage", passed, passed
                ? "the same task under two context fingerprints (context) and routed for two stages (stageKind) is"
                        + " reported as incomparable; identical keys form the only directly comparable pair"
                : String.join("; ", failures));
    }

    static Check currencyCheck(Map<ReplayArm, ReplayRun> runs) {
        var failures = new ArrayList<String>();
        for (ReplayRun run : runs.values()) {
            var available = ReplaySummary.of(run).chain().available();
            if (available.size() != 2 || available.getOrDefault("EUR", BigDecimal.ZERO)
                    .compareTo(new BigDecimal("0.0390")) != 0) {
                failures.add(run.arm() + ": " + available);
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("cases.currencies-not-summed", passed, passed
                ? "complete costs are summed per currency (USD, EUR) and never across currencies"
                : String.join("; ", failures));
    }

    static Check abstentionCheck(Map<ReplayArm, ReplayRun> runs) {
        var failures = new ArrayList<String>();
        for (ReplayRun run : runs.values()) {
            ReplaySummary.StageFigures total = ReplaySummary.of(run).total();
            if (total.abstained(AbstainReason.POLICY_TRADEOFF_UNRESOLVED) != 1 || total.noEligibleRoute() != 1
                    || total.coverage(ReplaySample.Coverage.NOT_EXECUTED) != 2) {
                failures.add(run.arm() + ": abstained=" + total.abstained() + ", noEligible="
                        + total.noEligibleRoute());
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("cases.abstention-and-no-eligible-route", passed, passed
                ? "a tier-capped stage abstains and an unserved stage has no eligible route; both are NOT_EXECUTED"
                        + " with no outcome"
                : String.join("; ", failures));
    }

    static Check missingCounterfactualCheck(Map<ReplayArm, ReplayRun> runs) {
        var failures = new ArrayList<String>();
        ReplaySummary control = ReplaySummary.of(runs.get(ReplayArm.STATIC));
        ReplaySummary adaptive = ReplaySummary.of(runs.get(ReplayArm.ADAPTIVE));
        int missing = adaptive.total().coverage(ReplaySample.Coverage.MISSING_COUNTERFACTUAL);
        if (control.total().coverage(ReplaySample.Coverage.MISSING_COUNTERFACTUAL) != 0) {
            failures.add("the static arm replays the logging policy, so it cannot diverge from the log");
        }
        if (missing == 0) {
            failures.add("the adaptive arm never diverged from the logged route, so nothing was tested");
        }
        for (ReplaySample sample : runs.get(ReplayArm.ADAPTIVE).samples()) {
            if (sample.coverage() == ReplaySample.Coverage.MISSING_COUNTERFACTUAL
                    && (sample.acceptance().isPresent() || sample.chainCost().isPresent() || sample.attempts() != 0)) {
                failures.add("an outcome was invented for " + sample.decisionRef().executionId());
            }
        }
        if (adaptive.chain().executions() != ReplaySummary.firstObservedPerExecution(
                runs.get(ReplayArm.ADAPTIVE).samples()).size()) {
            failures.add("chain figures do not count each observed execution exactly once");
        }
        if (adaptive.total().coverage(ReplaySample.Coverage.UNOBSERVED) + control.total().coverage(
                ReplaySample.Coverage.UNOBSERVED) == 0) {
            failures.add("an executed but unexported outcome was not reported as unobserved");
        }
        boolean passed = failures.isEmpty();
        return new Check("cases.missing-counterfactual", passed, passed
                ? missing + " adaptive choices of a route the log never executed are reported as missing"
                        + " counterfactuals with no outcome, cost or attempts; coverage is reported, not imputed"
                : String.join("; ", failures));
    }

    static Check visibilityCheck(ReplaySummary improvementStatic, ReplaySummary improvementAdaptive,
            ReplaySummary degradationStatic, ReplaySummary degradationAdaptive) {
        int gain = improvementAdaptive.total().validatedAccepted() - improvementStatic.total().validatedAccepted();
        int loss = degradationAdaptive.total().validatedAccepted() - degradationStatic.total().validatedAccepted();
        // The harness must be able to show both directions on fixtures whose direction is known; the measured
        // workload carries no such requirement.
        boolean passed = gain > 0 && loss < 0;
        return new Check("adaptation.improvement-and-degradation-visible", passed,
                "improvement fixture: adaptive " + improvementAdaptive.total().validatedAccepted() + " vs static "
                        + improvementStatic.total().validatedAccepted() + " validated acceptances (" + signed(gain)
                        + "); drift fixture: adaptive " + degradationAdaptive.total().validatedAccepted()
                        + " vs static " + degradationStatic.total().validatedAccepted() + " (" + signed(loss) + ")");
    }

    static Check boundsCheck(ReplayFixture fixture, ReplayRun adaptive) {
        RoutingStateStore state = adaptive.state().orElseThrow();
        var failures = new ArrayList<String>();
        if (fixture.tasks().size() > ReplayFixture.MAX_TASKS) {
            failures.add("tasks " + fixture.tasks().size());
        }
        if (state.cohortCount() > RoutingStateStore.MAX_COHORTS) {
            failures.add("cohorts " + state.cohortCount());
        }
        if (state.ledger().size() > RoutingStateStore.MAX_LEDGER) {
            failures.add("ledger " + state.ledger().size());
        }
        if (adaptive.delivered().size() > RebuildOrder.MAX_OBSERVATIONS) {
            failures.add("observations " + adaptive.delivered().size());
        }
        String oversized;
        try {
            new SyntheticRoutingFixtures().workload(ReplayFixture.MAX_TASKS + 1);
            oversized = "accepted";
            failures.add("an oversized fixture was accepted");
        } catch (IllegalArgumentException expected) {
            oversized = "rejected";
        }
        boolean passed = failures.isEmpty();
        return new Check("bounds.respected", passed, "tasks=" + fixture.tasks().size() + "/" + ReplayFixture.MAX_TASKS
                + ", cohorts=" + state.cohortCount() + "/" + RoutingStateStore.MAX_COHORTS + ", ledger="
                + state.ledger().size() + "/" + RoutingStateStore.MAX_LEDGER + ", observations="
                + adaptive.delivered().size() + "/" + RebuildOrder.MAX_OBSERVATIONS + ", oversizedFixture=" + oversized
                + (passed ? "" : ", exceeded: " + failures));
    }

    // ---- validation of measured runs ----

    /**
     * Requires a replay to equal a reference outside any timed window: the same decision trace (route compared by full
     * id and version, kind, basis, abstention, coverage and snapshot watermark of every decision), the same delivered
     * observations in the same order (compared by value, with the decision and request each answers) and the same
     * learned state. {@code O(T + P)} outside the timed window.
     *
     * @throws IllegalStateException naming the first difference
     */
    static void requireSameReplay(ReplayRun run, ReplayRun reference, ReplayDigests digests) {
        requireSameReplay(run, reference, reference.state().map(digests::state).orElse("none"), digests);
    }

    /** As {@link #requireSameReplay(ReplayRun, ReplayRun, ReplayDigests)}, against a precomputed state digest. */
    static void requireSameReplay(ReplayRun run, ReplayRun reference, String expectedState, ReplayDigests digests) {
        if (run == null) {
            throw new IllegalStateException(reference.arm() + ": no measured replay to validate");
        }
        int differs = run.decisions().firstDifference(reference.decisions());
        if (differs >= 0) {
            throw new IllegalStateException(run.arm() + " replay made a different decision at index " + differs + ": "
                    + (differs < run.decisions().size() ? run.decisions().line(differs) : "none") + " vs "
                    + (differs < reference.decisions().size() ? reference.decisions().line(differs) : "none"));
        }
        if (!run.delivered().equals(reference.delivered())) {
            // value equality of every delivered observation and the decision and request it answers, not only a count
            int index = 0;
            int common = Math.min(run.delivered().size(), reference.delivered().size());
            while (index < common && run.delivered().get(index).equals(reference.delivered().get(index))) {
                index++;
            }
            throw new IllegalStateException(run.arm() + " replay delivered " + run.delivered().size()
                    + " observations against " + reference.delivered().size() + "; first difference at index " + index
                    + ": " + describe(run.delivered(), index) + " vs " + describe(reference.delivered(), index));
        }
        if (!run.derivations().equals(reference.derivations())) {
            // the measured run must have derived what its reference derived, even where nothing consumes it
            throw new IllegalStateException(run.arm() + " replay derived " + run.derivations() + " against "
                    + reference.derivations());
        }
        String state = run.state().map(digests::state).orElse("none");
        if (!state.equals(expectedState)) {
            throw new IllegalStateException(run.arm() + " replay state " + state + " differs from " + expectedState);
        }
    }

    private static String describe(List<ReplayRun.Delivered> delivered, int index) {
        if (index >= delivered.size()) {
            return "none";
        }
        var observation = delivered.get(index).observation();
        return observation.experienceRef() + "@" + observation.revision();
    }

    // ---- metadata ----

    private void recordMetadata(ReplayFixture workload, Map<ReplayArm, ReplayRun> runs,
            Map<String, String> fingerprint, Map<String, Map<ReplayArm, ReplayRun>> scenarios,
            List<ReplayFixture> scenarioFixtures) {
        metadata.put("fixture.version", SyntheticRoutingFixtures.VERSION);
        metadata.put("fixture.workload.digest", fingerprint.get("fixture"));
        metadata.put("fixture.workload.tasks", Integer.toString(workload.tasks().size()));
        metadata.put("fixture.workload.cohorts", Integer.toString(workload.cohortKeys().size()));
        for (ReplayFixture fixture : scenarioFixtures) {
            metadata.put("fixture." + fixture.fixtureId().substring(SyntheticRoutingFixtures.VERSION.length() + 1)
                    + ".digest", digests.fixture(fixture));
        }
        metadata.put("policy", workload.policy().policyId() + "@" + workload.policy().policyVersion() + " "
                + workload.policy().parameters());
        metadata.put("state.definition", workload.definition().mappingVersion() + " (reference values, untuned)");
        metadata.put("evidence.policy", workload.evidencePolicy().id() + "@" + workload.evidencePolicy().version()
                + " mandatory=" + workload.evidencePolicy().mandatoryDimensions());
        metadata.put("replay.ordering", "decisions by (cutoff, requestOrdinal, then scope, task, execution, attempt and"
                + " stage ids); observations delivered before a decision iff ledgerSequence <= cutoff (inclusive"
                + " cutoff), by (ledgerSequence, experienceRef, revision); the adaptive state then examines through the"
                + " cutoff");
        metadata.put("replay.semantics", "synthetic: outcome known for every route; chosen-route-only: logged route"
                + " only, other routes reported as missing counterfactuals; figures are counts, not causal claims");
        metadata.put("cost.basis", ReplayCost.BASIS + "; per currency, never summed across currencies; PARTIAL is a"
                + " lower bound; chain cost counted once per execution, never per stage or route");
        for (ReplayRun run : runs.values()) {
            String arm = run.arm().benchmarkName();
            ReplaySummary.of(run).lines().forEach((k, v) -> metadata.put("workload." + k, v));
            if (fingerprint.containsKey(arm + ".state")) {
                metadata.put("workload." + arm + ".stateDigest", fingerprint.get(arm + ".state"));
            }
            metadata.put("workload." + arm + ".decisionDigest", fingerprint.get(arm + ".decisions"));
        }
        metadata.put("workload.reportDigest", fingerprint.get("report"));
        comparison(runs, "workload");
        scenarios.forEach((name, scenario) -> {
            for (ReplayRun run : scenario.values()) {
                ReplaySummary summary = ReplaySummary.of(run);
                String prefix = "scenario." + name + "." + run.arm().benchmarkName() + ".";
                metadata.put(prefix + "validatedAccepted", Integer.toString(summary.total().validatedAccepted()));
                metadata.put(prefix + "coverage", summary.total().coverage().toString());
                metadata.put(prefix + "cost.status", summary.chain().costStatus().toString());
            }
            comparison(scenario, "scenario." + name);
        });
    }

    /** Per-task paired comparison against the static arm: same tasks, same keys, so directly comparable. */
    private void comparison(Map<ReplayArm, ReplayRun> runs, String prefix) {
        List<ReplaySample> control = runs.get(ReplayArm.STATIC).samples();
        for (ReplayArm arm : List.of(ReplayArm.DERIVED_NOT_CONSUMED, ReplayArm.ADAPTIVE)) {
            List<ReplaySample> other = runs.get(arm).samples();
            int changed = 0;
            int gained = 0;
            int lost = 0;
            int unscored = 0;
            for (int i = 0; i < control.size(); i++) {
                ReplaySample a = control.get(i);
                ReplaySample b = other.get(i);
                if (a.decisionProjection().equals(b.decisionProjection())) {
                    continue;
                }
                changed++;
                if (a.coverage() != ReplaySample.Coverage.OBSERVED || b.coverage() != ReplaySample.Coverage.OBSERVED) {
                    unscored++;
                } else if (b.validatedAccepted() && !a.validatedAccepted()) {
                    gained++;
                } else if (a.validatedAccepted() && !b.validatedAccepted()) {
                    lost++;
                }
            }
            String key = prefix + "." + arm.benchmarkName() + ".vsStatic";
            metadata.put(key, "changed=" + changed + ",gainedAcceptance=" + gained + ",lostAcceptance=" + lost
                    + ",unscored=" + unscored);
        }
    }

    private Map<String, String> metadata() {
        var all = new TreeMap<>(metadata);
        all.put("workload.mode", quick ? "quick" : "full");
        all.put("ownership", "caller-owned state per run; no session, background task, persistence, clock or"
                + " randomness inside the replay");
        return all;
    }

    // ---- helpers ----

    private static long correctionPosition(ReplayFixture fixture) {
        return fixture.tasks().stream()
                .filter(t -> t.request().requestOrdinal() == LATE_CORRECTION_TASK)
                .map(t -> ((OutcomeOracle.KnownForEveryRoute) t.oracle()).outcomes().values())
                .flatMap(outcomes -> outcomes.stream().map(o -> o.correction().orElseThrow().observedAt()))
                .min(Long::compare).orElseThrow();
    }

    static List<String> projections(List<ReplaySample> samples) {
        return samples.stream().map(ReplaySample::decisionProjection).toList();
    }

    private static List<Long> watermarks(List<ReplaySample> samples) {
        return samples.stream().map(ReplaySample::stateCutoff).toList();
    }

    private static int count(ReplayRun run, Predicate<FeedbackEvent> predicate) {
        return (int) run.feedback().stream().filter(predicate).count();
    }

    private static ReplaySample sampleOf(ReplayRun run, String taskId) {
        return run.samples().stream().filter(s -> s.decisionRef().taskId().equals(taskId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no sample for " + taskId));
    }

    private static List<FeedbackEvent> eventsOf(ReplayRun run, ReplaySample sample) {
        return run.feedback().stream().filter(e -> e.decisionRef().equals(sample.decisionRef())).toList();
    }

    private static String differing(Map<String, String> first, Map<String, String> second) {
        var keys = new TreeMap<String, String>();
        first.forEach((k, v) -> {
            if (!v.equals(second.get(k))) {
                keys.put(k, v);
            }
        });
        return keys.keySet().toString();
    }

    private static String firstDifference(List<String> expected, List<String> actual) {
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "index " + i + ": expected " + expected.get(i) + ", got " + actual.get(i);
            }
        }
        return "sizes " + expected.size() + " vs " + actual.size();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }
}
