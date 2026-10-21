package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.ArmEffect;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.RecallPath;
import monada.neuron.evaluation.knowledge.FeedbackKeyVariants.Variant;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.Options;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.PhaseRun;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * CLI of the relevance-feedback experiment (Issue #110, protocol extension {@code feedback-v1}): compares the Store's
 * existing query-scoped relevance feedback with a no-feedback control on {@code html-kc-v1} and {@code html-kq-v1}.
 *
 * <p>Four independent Store roots are imported identically: the control, and one treatment arm per Store feedback key
 * strategy. The judged queries are split deterministically into calibration and held-out queries
 * ({@link FeedbackQuerySplit}); events are derived from calibration labels only ({@link FeedbackAssigner}) and recorded
 * only in the treatment arms. Three child JVMs run the phases ({@link HtmlKnowledgeFeedbackPhase}): {@code seed}
 * (import and pre-feedback observation), {@code feedback} (record, read back and observe before any restart) and
 * {@code reopen} (read back and observe after a restart, with feedback-aware ranking on and off). This orchestrator is
 * the only process that reads gold labels; it derives the plan between the seed and feedback phases and scores the
 * restarted observations afterwards.
 *
 * <p>Options: {@code --output-dir <dir>} (default {@code build/reports/benchmarks}) and {@code --store-root <dir>}
 * (keep the four arm roots under it instead of a deleted temporary directory; it must be absent or empty). Outputs:
 * {@code html-knowledge-feedback.json} (verdicts, run identity and metadata, and the scored payload under
 * {@code feedback}, {@code null} when nothing was scored), {@code html-knowledge-feedback.md}, and the raw files
 * {@code html-kq-v1-feedback-rows.tsv}, {@code html-kq-v1-feedback-events.tsv} and {@code html-kq-v1-feedback-split.tsv}.
 * Exit status: {@code 0} all checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected
 * input.
 */
public final class HtmlKnowledgeFeedbackRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge Relevance Feedback Report";

    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>]";
    private static final String REPORT_JSON = "html-knowledge-feedback.json";
    private static final String REPORT_MARKDOWN = "html-knowledge-feedback.md";

    /** The Store's documented default deltas of the three-argument {@code feedback} overload. */
    static final double POSITIVE_DELTA = 0.05;
    static final double NEGATIVE_DELTA = -0.05;

    private static final List<Layer> LAYERS = List.of(Layer.values());

    private final Options options;
    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeQuerySet querySet;
    private final PhaseJvm phaseJvm = new PhaseJvm();

    HtmlKnowledgeFeedbackRunner(Options options) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.querySet = HtmlKnowledgeQuerySet.bundled(corpus);
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata and environment
     * @param feedback the scored payload rendered as JSON and Markdown, when the reopen phase left every snapshot
     * @param phases every launched child process, in launch order
     * @param files further output file contents by file name
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<Rendered> feedback, List<PhaseRun> phases,
            Map<String, String> files, Path outputDir) {

        public Outcome {
            phases = List.copyOf(phases);
            files = Map.copyOf(files);
        }

        /** Returns the single JSON report: verdicts and metadata with the scored payload as {@code feedback}. */
        public String json() {
            return report.toJson(Map.of("feedback", feedback.map(Rendered::json).orElse("null")));
        }

        /** Returns the full Markdown report: verdicts and metadata, then the scored summary. */
        public String markdown() {
            return report.toMarkdown() + feedback.map(rendered -> "\n" + rendered.markdown()).orElse("");
        }
    }

    /** The scored results as JSON and Markdown. */
    public record Rendered(String json, String markdown) {
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Outcome outcome;
        try {
            outcome = run(options);
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Feedback input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Feedback outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write feedback outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic feedback checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the experiment. The temporary work directory, and the arm roots unless {@code --store-root} was given, are
     * deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept root is rejected
     * @throws CorpusValidationException when the corpus or the query set violates its contract
     */
    public static Outcome run(Options options) {
        return new HtmlKnowledgeFeedbackRunner(options).execute();
    }

    /** Writes the reports and the result files into the preflighted output directory. */
    public static List<Path> write(Outcome outcome) throws IOException {
        var outputDir = outcome.outputDir();
        var written = new ArrayList<Path>();
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_JSON), outcome.json()));
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_MARKDOWN), outcome.markdown()));
        for (var file : new TreeMap<>(outcome.files()).entrySet()) {
            written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(file.getKey()), file.getValue()));
        }
        return written;
    }

    private Outcome execute() {
        HtmlKnowledgeIngestionRunner.prepareOutput(options.outputDir(), outputFiles());
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);

        var work = new WorkDirectory(temporaryDirectory());
        try {
            var armsRoot = options.storeRoot().orElse(work.path().resolve("arms"));
            var split = FeedbackQuerySplit.of(querySet.queries());
            var variants = FeedbackKeyVariants.of(texts(split.calibration()));
            var workload = new ArrayList<>(texts(querySet.queries()));
            variants.forEach(variant -> workload.add(variant.queryText()));
            work.write(work.workload(), QueryText.renderTsv(querySet.queryHash(), workload));

            var checks = new ArrayList<Check>();
            var metadata = new TreeMap<String, String>();
            var files = new TreeMap<String, String>();
            var phases = new ArrayList<PhaseRun>();
            files.put(querySet.version() + "-feedback-split.tsv", split.toTsv());

            var seed = launch(HtmlKnowledgeFeedbackPhase.SEED, armsRoot, work, checks, metadata, phases);
            var seedSnapshots = snapshots(work, HtmlKnowledgeFeedbackPhase.SEED, "");
            Optional<Rendered> rendered = Optional.empty();
            Optional<FeedbackEvents> plan = Optional.empty();
            if (seedSnapshots.size() == FeedbackArm.values().length && manifestsWritten(work)) {
                checks.add(preFeedbackEqual(seedSnapshots));
                var validity = split.validity();
                checks.add(validity);
                var variantCheck = FeedbackKeyVariants.keyPreserving(variants, workload);
                checks.add(variantCheck);
                plan = derivePlan(split, seedSnapshots.get(FeedbackArm.TREATMENT_EXACT), checks);
                if (plan.isPresent() && checks.stream().allMatch(Check::passed)) {
                    work.write(HtmlKnowledgeFeedbackPhase.plan(work), plan.get().toTsv());
                    var scored = runFeedback(armsRoot, work, split, variants, plan.get(), seed, checks, metadata,
                            phases);
                    rendered = scored.map(value -> {
                        var report = new HtmlKnowledgeFeedbackReport(corpus, value);
                        files.put(querySet.version() + "-feedback-events.tsv", report.eventsTsv());
                        return new Rendered(report.toJson(), report.toMarkdown());
                    });
                } else {
                    checks.add(new Check("process.restart", false, "the feedback and reopen phases were not launched:"
                            + " a check before the first event failed, so no event was recorded"));
                }
            } else {
                checks.add(new Check("process.restart", false, "seed phase did not complete (exit "
                        + seed.exitStatus() + "; a snapshot or manifest is missing or unreadable); nothing was scored"
                        + " and no event was recorded: " + seed.stderr()));
            }

            describe(armsRoot, split, variants, plan, phases, metadata);
            var evaluation = new EvaluationReport(
                    Instant.now(),
                    EnvironmentMetadata.current(),
                    new RunConfiguration(0L, false, 0, 0, HtmlKnowledgePersistenceRunner.launchedPhases(
                            List.of(HtmlKnowledgeFeedbackPhase.SEED, HtmlKnowledgeFeedbackPhase.FEEDBACK,
                                    HtmlKnowledgeFeedbackPhase.REOPEN), phases)
                            + "; the environment above is the orchestrator's, each phase JVM's own is in <phase>.jvm.*; "
                            + LatencyRow.RUN_CONFIGURATION_NOTE),
                    List.of(),
                    TITLE);
            var report = new CheckedEvaluationReport(TITLE, "Feedback Metadata", evaluation, checks, metadata);
            var rows = rowsTsv(work, report.allPassed());
            if (!rows.isEmpty()) {
                files.put(querySet.version() + "-feedback-rows.tsv", rows);
            }
            return new Outcome(report, rendered, phases, files, options.outputDir());
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(work.path());
        }
    }

    /**
     * The feedback and reopen phases and every check after the plan; returns the scored payload when the reopen phase
     * left every snapshot it was asked for.
     */
    private Optional<HtmlKnowledgeFeedbackReport.Scored> runFeedback(Path armsRoot, WorkDirectory work,
            FeedbackQuerySplit split, List<Variant> variants, FeedbackEvents plan, PhaseRun seed, List<Check> checks,
            Map<String, String> metadata, List<PhaseRun> phases) {
        var feedbackRun = launch(HtmlKnowledgeFeedbackPhase.FEEDBACK, armsRoot, work, checks, metadata, phases);
        var feedbackSnapshots = snapshots(work, HtmlKnowledgeFeedbackPhase.FEEDBACK, "");
        var feedbackLogs = logs(work, HtmlKnowledgeFeedbackPhase.FEEDBACK);
        for (var arm : FeedbackArm.treatments()) {
            checks.add(persisted(arm, plan, Optional.ofNullable(feedbackLogs.get(arm))));
            checks.add(persistenceAudit(arm, plan, feedbackSnapshots));
        }
        if (feedbackSnapshots.size() != FeedbackArm.values().length) {
            checks.add(new Check("process.restart", false, "the feedback phase did not leave every snapshot (exit "
                    + feedbackRun.exitStatus() + "); no reopen was attempted: " + feedbackRun.stderr()));
            return Optional.empty();
        }
        var reopen = launch(HtmlKnowledgeFeedbackPhase.REOPEN, armsRoot, work, checks, metadata, phases);
        checks.add(restart(seed, feedbackRun, reopen, work));
        var reopenSnapshots = snapshots(work, HtmlKnowledgeFeedbackPhase.REOPEN, "");
        var disabledSnapshots = snapshots(work, HtmlKnowledgeFeedbackPhase.REOPEN, HtmlKnowledgeFeedbackPhase.DISABLED);
        var reopenLogs = logs(work, HtmlKnowledgeFeedbackPhase.REOPEN);
        for (var arm : FeedbackArm.values()) {
            checks.add(logUnchanged(arm, feedbackLogs.get(arm), reopenLogs.get(arm)));
            checks.add(restartEqual(arm, feedbackSnapshots.get(arm), reopenSnapshots.get(arm)));
        }
        var seedControl = snapshots(work, HtmlKnowledgeFeedbackPhase.SEED, "").get(FeedbackArm.CONTROL);
        checks.add(controlStable(seedControl, feedbackSnapshots.get(FeedbackArm.CONTROL),
                reopenSnapshots.get(FeedbackArm.CONTROL)));
        var control = reopenSnapshots.get(FeedbackArm.CONTROL);
        var heldOut = ids(split.heldOut());
        var withoutEvents = new LinkedHashSet<String>();
        split.calibration().stream().map(JudgedQuery::queryId).filter(id -> !plan.queryIds().contains(id))
                .forEach(withoutEvents::add);
        variants.stream().filter(variant -> withoutEvents.contains(variant.sourceId()))
                .map(Variant::variantId).toList().forEach(withoutEvents::add);
        var workloadTexts = new TreeMap<String, String>();
        querySet.queries().forEach(query -> workloadTexts.put(query.queryId(), query.text()));
        variants.forEach(variant -> workloadTexts.put(variant.variantId(), variant.text()));
        for (var arm : FeedbackArm.treatments()) {
            checks.add(disabledEqualsControl(arm, control, disabledSnapshots.get(arm)));
            checks.add(unchanged("feedback.heldOutUnchanged." + arm.label(), "held-out", arm, plan, heldOut,
                    workloadTexts, control, reopenSnapshots.get(arm)));
            checks.add(unchanged("feedback.calibrationWithoutEventsUnchanged." + arm.label(),
                    "calibration-without-events (and their variants)", arm, plan, withoutEvents, workloadTexts,
                    control, reopenSnapshots.get(arm)));
        }
        if (reopenSnapshots.size() != FeedbackArm.values().length || control == null) {
            return Optional.empty();
        }
        var effects = new ArrayList<ArmEffect>();
        for (var arm : FeedbackArm.treatments()) {
            effects.add(FeedbackEffectMetrics.compare(querySet, split, variants, plan, arm, control,
                    reopenSnapshots.get(arm)));
        }
        var diagnostics = new TreeMap<String, String>();
        diagnose(split, variants, seedControl, control, disabledSnapshots.get(FeedbackArm.CONTROL), effects, metadata,
                diagnostics);
        return Optional.of(new HtmlKnowledgeFeedbackReport.Scored(querySet, split, variants, plan, reopenLogs, effects,
                diagnostics, followUps(split, effects, metadata)));
    }

    /** Derives the plan from calibration labels only; any rejection fails {@code feedback.planDerived}. */
    private Optional<FeedbackEvents> derivePlan(FeedbackQuerySplit split, Snapshot preFeedback, List<Check> checks) {
        var heldOutIds = ids(split.heldOut());
        FeedbackEvents plan;
        try {
            plan = FeedbackAssigner.derive(split.calibration(), heldOutIds, preFeedback);
        } catch (IllegalArgumentException | IllegalStateException e) {
            checks.add(new Check("feedback.planDerived", false, "no plan: " + e.getMessage()));
            return Optional.empty();
        }
        var leaks = new ArrayList<String>();
        for (var query : split.heldOut()) {
            if (query.text().isBlank()) {
                continue;
            }
            for (var strategy : FeedbackArm.KeyStrategy.values()) {
                var key = strategy.effectiveKey(query.text());
                plan.events().stream().filter(event -> strategy.effectiveKey(event.text()).equals(key)).findFirst()
                        .ifPresent(event -> leaks.add(query.queryId() + " shares its " + strategy.label() + " key with "
                                + event.queryId()));
            }
        }
        var heldOutEvents = plan.queryIds().stream().filter(heldOutIds::contains).toList();
        var passed = leaks.isEmpty() && heldOutEvents.isEmpty() && !plan.events().isEmpty();
        checks.add(new Check("feedback.planDerived", passed, plan.events().size() + " events ("
                + plan.count(FeedbackSignal.POSITIVE) + " POSITIVE, " + plan.count(FeedbackSignal.NEGATIVE)
                + " NEGATIVE) on " + plan.queryIds().size() + " calibration queries, from the pre-feedback B1 Ret_5 of "
                + FeedbackArm.TREATMENT_EXACT.label() + "; bounds " + FeedbackEvents.MAX_EVENTS_PER_QUERY
                + " per query and " + FeedbackEvents.MAX_EVENTS + " in total; "
                + (leaks.isEmpty() && heldOutEvents.isEmpty()
                        ? "no event is keyed by a held-out query under any key strategy"
                        : "held-out leak: " + leaks + heldOutEvents)
                + (plan.events().isEmpty() ? "; the plan is empty, so nothing can be measured" : "")));
        return Optional.of(plan);
    }

    /** {@code arms.preFeedbackEqual}: every arm's seed snapshot is identical before any event. */
    private Check preFeedbackEqual(Map<FeedbackArm, Snapshot> seed) {
        var reference = seed.get(FeedbackArm.CONTROL);
        var different = new ArrayList<String>();
        for (var arm : FeedbackArm.treatments()) {
            if (!reference.equals(seed.get(arm))) {
                different.add(arm.label() + " (first difference at row "
                        + firstDifference(reference.rows(), seed.get(arm).rows()) + ")");
            }
        }
        return new Check("arms.preFeedbackEqual", different.isEmpty(), different.isEmpty()
                ? "before any event, all " + FeedbackArm.values().length + " arms returned identical results for all "
                        + reference.rows().size() + " rows (B0, B1, B2, B2c at limits 1, 3, 5 and 6, every query and"
                        + " variant): mapped chunk ids, order and bit-exact scores, each through its own arm options"
                : "arms differ from the control before any event: " + different);
    }

    /**
     * {@code feedback.persisted.<arm>}: the arm's feedback log, read back at the storage level, holds exactly the plan,
     * in plan order, each event with the key the arm's strategy computes, the planned chunk and signal and the Store's
     * default delta.
     */
    private Check persisted(FeedbackArm arm, FeedbackEvents plan, Optional<FeedbackEventLog> log) {
        var name = "feedback.persisted." + arm.label();
        if (log.isEmpty()) {
            return new Check(name, false, "the feedback phase left no readable log for " + arm.label());
        }
        var stored = log.get().events();
        var problems = new ArrayList<String>();
        if (stored.size() != plan.events().size()) {
            problems.add(stored.size() + " stored events, " + plan.events().size() + " planned");
        }
        for (var i = 0; i < Math.min(stored.size(), plan.events().size()); i++) {
            var event = plan.events().get(i);
            var actual = stored.get(i);
            var expectedDelta = event.signal() == FeedbackSignal.POSITIVE ? POSITIVE_DELTA : NEGATIVE_DELTA;
            if (!actual.query().equals(event.text())
                    || !actual.queryKey().equals(arm.strategy().effectiveKey(event.text()))
                    || !actual.chunkId().equals(event.chunkId()) || actual.signal() != event.signal()
                    || !HtmlKnowledgeRecall.sameScore(actual.delta(), expectedDelta)) {
                problems.add("event " + (i + 1) + " (" + event.queryId() + " " + event.chunkId() + ")");
            }
        }
        return new Check(name, problems.isEmpty(), problems.isEmpty()
                ? "the root's feedback log (" + log.get().logBytes() + " bytes) holds all " + stored.size()
                        + " planned events in order, each with its " + arm.strategy().label()
                        + " query key, chunk, signal and delta ±" + POSITIVE_DELTA
                : "stored log differs from the plan: " + String.join("; ", problems.subList(0,
                        Math.min(5, problems.size()))));
    }

    /**
     * {@code feedback.persistenceAudit.<arm>}: before the restart, at least one calibration query that received events
     * returns a different B1 result in the treatment than in the control. Otherwise persistence is
     * {@code unverified (no observable effect)}, which fails. After the restart, {@code determinism.restartEqual}
     * requires each arm to reproduce its own results.
     */
    private Check persistenceAudit(FeedbackArm arm, FeedbackEvents plan, Map<FeedbackArm, Snapshot> snapshots) {
        var name = "feedback.persistenceAudit." + arm.label();
        var control = snapshots.get(FeedbackArm.CONTROL);
        var treatment = snapshots.get(arm);
        if (control == null || treatment == null) {
            return new Check(name, false, "unverified: the feedback phase left no snapshot to compare");
        }
        var differing = plan.queryIds().stream().filter(id -> !sameRows(
                control.rows(id, Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT),
                treatment.rows(id, Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT))).toList();
        return new Check(name, !differing.isEmpty(), differing.isEmpty()
                ? "unverified (no observable effect): no calibration query with events returns a different B1 result"
                        + " than the control before the restart"
                : differing.size() + " of " + plan.queryIds().size() + " calibration queries with events return a"
                        + " different B1 limit-5 result (chunk ids, order or scores) than the control before the"
                        + " restart");
    }

    /** {@code process.restart}: four distinct processes, and every phase JVM finished its phase. */
    private Check restart(PhaseRun seed, PhaseRun feedback, PhaseRun reopen, WorkDirectory work) {
        var self = ProcessHandle.current().pid();
        var pids = new HashSet<>(List.of(seed.pid(), feedback.pid(), reopen.pid(), self));
        var separate = pids.size() == 4;
        var completed = Stream.of(seed, feedback, reopen).allMatch(run -> completed(run, work));
        return new Check("process.restart", separate && completed, Stream.of(seed, feedback, reopen)
                .map(run -> run.phase() + " JVM pid " + run.pid() + " exited " + run.exitStatus()
                        + (completed(run, work) ? " after its phase" : " without a result"))
                .reduce((a, b) -> a + "; " + b).orElse("") + "; orchestrator pid " + self
                + (separate ? "; " + pids.size() + " distinct processes" : "; processes are not distinct"));
    }

    private static boolean completed(PhaseRun run, WorkDirectory work) {
        if ((run.exitStatus() != 0 && run.exitStatus() != 1) || !Files.exists(work.result(run.phase()))) {
            return false;
        }
        try {
            HtmlKnowledgePersistencePhase.Result.parse(work.read(work.result(run.phase())));
            return true;
        } catch (IllegalArgumentException | UncheckedIOException e) {
            return false;
        }
    }

    /** {@code feedback.logUnchangedAcrossRestart.<arm>}: the restarted process reads the same log, byte for byte. */
    private Check logUnchanged(FeedbackArm arm, FeedbackEventLog before, FeedbackEventLog after) {
        var name = "feedback.logUnchangedAcrossRestart." + arm.label();
        if (before == null || after == null) {
            return new Check(name, false, "a phase left no readable feedback log for " + arm.label());
        }
        var equal = before.equals(after);
        return new Check(name, equal, equal
                ? "the restarted process read the same " + after.events().size() + " events and the same "
                        + after.logBytes() + " log bytes (SHA-256 " + after.logSha256().substring(0, 16) + "…)"
                : "the log changed across the restart: " + before.events().size() + " events / " + before.logBytes()
                        + " bytes before, " + after.events().size() + " / " + after.logBytes() + " after");
    }

    /** {@code determinism.restartEqual.<arm>}: the restarted process reproduces the arm's pre-restart results. */
    private Check restartEqual(FeedbackArm arm, Snapshot before, Snapshot after) {
        var name = "determinism.restartEqual." + arm.label();
        if (before == null || after == null) {
            return new Check(name, false, "a phase left no readable snapshot for " + arm.label());
        }
        var equal = before.equals(after);
        return new Check(name, equal, equal
                ? "all " + after.rows().size() + " rows of the feedback phase are identical in the restarted process"
                        + " (feedback-aware ranking, every layer, limit, query and variant; bit-exact scores)"
                : "first difference at row " + firstDifference(before.rows(), after.rows()));
    }

    /** {@code determinism.controlStable}: the control returns the same results in all three phases. */
    private Check controlStable(Snapshot seed, Snapshot feedback, Snapshot reopen) {
        if (seed == null || feedback == null || reopen == null) {
            return new Check("determinism.controlStable", false, "a phase left no control snapshot");
        }
        var equal = seed.equals(feedback) && feedback.equals(reopen);
        return new Check("determinism.controlStable", equal, equal
                ? "the control's " + seed.rows().size() + " rows are identical before the events, after them and"
                        + " after the restart"
                : "the control changed although it received no event");
    }

    /**
     * {@code feedback.disabledEqualsControl.<arm>}: the treatment root, reopened with feedback-aware ranking disabled,
     * reproduces the control bit for bit, including no-answer, out-of-domain and blank queries.
     */
    private Check disabledEqualsControl(FeedbackArm arm, Snapshot control, Snapshot disabled) {
        var name = "feedback.disabledEqualsControl." + arm.label();
        if (control == null || disabled == null) {
            return new Check(name, false, "the reopen phase left no snapshot to compare");
        }
        var equal = control.equals(disabled);
        return new Check(name, equal, equal
                ? "with feedback-aware ranking disabled, the treatment root reproduces all " + control.rows().size()
                        + " control rows (every query including no-answer and blank, every variant, B0, B1, B2 and"
                        + " B2c; bit-exact scores) although its log holds the events"
                : "first difference from the control at row " + firstDifference(control.rows(), disabled.rows()));
    }

    /**
     * Requires every query in {@code ids} to return the same results in the treatment as in the control, in every
     * layer and limit. Codec-direct rows (B2, B2c) of a query whose codec-emitted Store texts share a key with an event
     * under the arm's strategy are a {@code keyCollision}: reported, and left out of the comparison.
     */
    private Check unchanged(String name, String population, FeedbackArm arm, FeedbackEvents plan, Set<String> ids,
            Map<String, String> texts, Snapshot control, Snapshot treatment) {
        if (control == null || treatment == null) {
            return new Check(name, false, "the reopen phase left no snapshot to compare");
        }
        var eventKeys = FeedbackEffectMetrics.eventKeys(plan, arm);
        var codec = new TokenPackingSignalCodec();
        var differing = new ArrayList<String>();
        var collisions = new ArrayList<String>();
        for (var id : ids) {
            var collides = FeedbackEffectMetrics.codecTexts(codec, texts.get(id)).stream()
                    .filter(text -> !text.isBlank())
                    .anyMatch(text -> eventKeys.contains(arm.strategy().effectiveKey(text)));
            if (collides) {
                collisions.add(id);
            }
            for (var layer : LAYERS) {
                if (collides && (layer == Layer.B2 || layer == Layer.B2C)) {
                    continue;
                }
                if (!layerRows(control, id, layer).equals(layerRows(treatment, id, layer))) {
                    differing.add(id + " " + layer.label());
                }
            }
        }
        return new Check(name, differing.isEmpty(), (differing.isEmpty()
                ? "all " + ids.size() + " " + population + " queries return identical results in the treatment and"
                        + " the control (every layer and limit, bit-exact scores)"
                : "cross-key leak: " + differing.size() + " query layers differ from the control: "
                        + differing.subList(0, Math.min(5, differing.size())))
                + "; codec keyCollision (B2/B2c not compared): " + (collisions.isEmpty() ? "none" : collisions));
    }

    private static List<Row> layerRows(Snapshot snapshot, String id, Layer layer) {
        return snapshot.rows().stream().filter(row -> row.queryId().equals(id) && row.layer() == layer).toList();
    }

    /** Records every named observation that is never a check. */
    private void diagnose(FeedbackQuerySplit split, List<Variant> variants, Snapshot seedControl, Snapshot control,
            Snapshot controlDisabled, List<ArmEffect> effects, Map<String, String> metadata,
            Map<String, String> diagnostics) {
        var sameBase = 0;
        var differentBase = new ArrayList<String>();
        for (var variant : variants) {
            if (sameRows(seedControl.rows(variant.sourceId(), Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT),
                    seedControl.rows(variant.variantId(), Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT))) {
                sameBase++;
            } else {
                differentBase.add(variant.variantId());
            }
        }
        diagnostics.put("variants.baseRankingEqualsSource", sameBase + " of " + variants.size()
                + " variants return their source's B1 limit-5 result in the control (chunk ids, order, bit-exact"
                + " scores)" + (differentBase.isEmpty() ? "" : "; different: " + differentBase));
        diagnostics.put("control.disabledEqualsControl", controlDisabled != null && controlDisabled.equals(control)
                ? "the control with feedback-aware ranking disabled equals the control with it enabled"
                : "the control differs with feedback-aware ranking disabled");
        diagnostics.put("split.mergedGroups", split.mergedGroups().isEmpty() ? "none"
                : split.mergedGroups().stream().map(group -> group.members().toString()).toList().toString());
        metadata.entrySet().stream().filter(entry -> entry.getKey().contains(".diag.")
                && entry.getKey().endsWith(".prefixStability"))
                .forEach(entry -> diagnostics.put(entry.getKey(),
                        entry.getValue().length() > 300 ? entry.getValue().substring(0, 300) + "…"
                                : entry.getValue()));
        for (var effect : effects) {
            var exercised = effect.queries().stream().filter(query -> query.path() == RecallPath.NEURON
                    && query.population().withEvents() && query.exercisedKeys() > 0)
                    .count();
            var total = effect.queries().stream().filter(query -> query.path() == RecallPath.NEURON
                    && query.population().withEvents()).count();
            diagnostics.put("neuron." + effect.arm().label() + ".exercised", exercised + " of " + total
                    + " B2 queries in event populations send at least one codec token whose key received events");
        }
    }

    /** Suggests follow-up issues from what was observed; nothing here is tuned or filed automatically. */
    private List<String> followUps(FeedbackQuerySplit split, List<ArmEffect> effects, Map<String, String> metadata) {
        var followUps = new ArrayList<String>();
        if (metadata.entrySet().stream().anyMatch(entry -> entry.getKey().endsWith(".prefixStability")
                && entry.getKey().contains(".diag.") && entry.getValue().startsWith("unstable"))) {
            followUps.add("Store: feedback-aware ranking is not prefix-stable across topK (the candidate pool grows"
                    + " to topK*4 only with a positive adjustment), so a limit-1 list need not be the prefix of the"
                    + " limit-5 list for the same query");
        }
        effects.stream().filter(effect -> effect.arm() == FeedbackArm.TREATMENT_EXACT).findFirst().ifPresent(exact -> {
            var variantChanges = exact.populations().stream().filter(population -> population.path() == RecallPath.DIRECT
                    && population.population().name().startsWith("VARIANT")).mapToInt(
                            FeedbackEffectMetrics.PopulationEffect::changed).sum();
            if (variantChanges == 0) {
                followUps.add("Store: the default ExactQueryKeyStrategy does not carry feedback to a re-asked query"
                        + " that differs only in case, spacing or final punctuation");
            }
        });
        for (var effect : effects) {
            for (var population : effect.populations()) {
                if (population.regressions().stream().anyMatch(reason -> reason.contains("FALSE_EVIDENCE"))) {
                    followUps.add("Store: feedback turned a correct abstention into false evidence ("
                            + effect.arm().label() + ", " + population.path().label() + ", "
                            + population.population().label() + ")");
                }
            }
            var exercised = effect.queries().stream().anyMatch(query -> query.path() == RecallPath.NEURON
                    && query.population().withEvents() && query.exercisedKeys() > 0);
            if (!exercised) {
                followUps.add("Neuron: under the " + effect.arm().strategy().label() + " key strategy no B2 query"
                        + " reaches a key with events, because the adapter sends one Store query per codec token;"
                        + " query-scoped Store feedback cannot reach the Neuron path with this codec");
            }
        }
        if (!split.mergedGroups().isEmpty()) {
            followUps.add("Store: the lexically enriched key merges distinct queries " + split.mergedGroups().stream()
                    .map(group -> group.members().toString()).toList() + ", so feedback on one applies to the others");
        }
        followUps.add("Store (source review): every MonadaQuery.execute() re-reads the whole feedback log to adjust one"
                + " query, so query cost grows with the number of stored events");
        followUps.add("Store (source review): MonadaMemory has no public read of stored feedback, and the public"
                + " FileFeedbackStore constructor creates a missing log, so a read-only audit must check the file first");
        return followUps;
    }

    private PhaseRun launch(String phase, Path armsRoot, WorkDirectory work, List<Check> checks,
            Map<String, String> metadata, List<PhaseRun> phases) {
        var run = phaseJvm.launch(HtmlKnowledgeFeedbackPhase.class, phase, armsRoot, work);
        phases.add(run);
        HtmlKnowledgePersistenceRunner.collect(run, work, checks, metadata);
        return run;
    }

    /** Returns the snapshot of every arm observation of {@code phase} with label suffix {@code suffix} that parses. */
    private Map<FeedbackArm, Snapshot> snapshots(WorkDirectory work, String phase, String suffix) {
        var snapshots = new EnumMap<FeedbackArm, Snapshot>(FeedbackArm.class);
        if (!Files.exists(work.result(phase))) {
            return snapshots;
        }
        for (var arm : FeedbackArm.values()) {
            var file = HtmlKnowledgeFeedbackPhase.snapshot(work, phase, arm.label() + suffix);
            if (Files.exists(file)) {
                try {
                    snapshots.put(arm, Snapshot.parseTsv(work.read(file)));
                } catch (IllegalArgumentException | UncheckedIOException e) {
                    // A truncated or malformed snapshot is treated as missing; the checks that need it fail.
                }
            }
        }
        return snapshots;
    }

    /** Returns every arm's feedback log as read back in {@code phase}, for those that parse. */
    private Map<FeedbackArm, FeedbackEventLog> logs(WorkDirectory work, String phase) {
        var logs = new EnumMap<FeedbackArm, FeedbackEventLog>(FeedbackArm.class);
        for (var arm : FeedbackArm.values()) {
            var file = HtmlKnowledgeFeedbackPhase.events(work, phase, arm);
            if (Files.exists(file)) {
                try {
                    logs.put(arm, FeedbackEventLog.parseTsv(work.read(file)));
                } catch (IllegalArgumentException | UncheckedIOException e) {
                    // Treated as missing; the checks that need it fail.
                }
            }
        }
        return logs;
    }

    private boolean manifestsWritten(WorkDirectory work) {
        return Stream.of(FeedbackArm.values())
                .allMatch(arm -> Files.exists(HtmlKnowledgeFeedbackPhase.manifest(work, arm)));
    }

    /**
     * Renders the scored raw rows (the restarted process's limit-5 lists of B1 and B2, every arm, feedback-aware ranking
     * on and off), headed by the run's hashes and verdict. Earlier phases are covered by the restart and stability
     * checks and are not repeated here.
     */
    private String rowsTsv(WorkDirectory work, boolean allPassed) {
        var tsv = new StringBuilder("# raw scored rows, not a verified result on their own: corpusHash "
                + corpus.corpusHash() + ", queryHash " + querySet.queryHash() + ", allChecksPassed " + allPassed
                + " (see " + REPORT_JSON + ")\nobservation\tqueryId\tlayer\trank\tchunkId\tscore\n");
        var rows = 0;
        for (var suffix : List.of("", HtmlKnowledgeFeedbackPhase.DISABLED)) {
            for (var entry : snapshots(work, HtmlKnowledgeFeedbackPhase.REOPEN, suffix).entrySet()) {
                for (var row : entry.getValue().rows()) {
                    if (row.limit() != HtmlKnowledgeRecall.MAX_LIMIT
                            || (row.layer() != Layer.B1 && row.layer() != Layer.B2)) {
                        continue;
                    }
                    tsv.append(entry.getKey().label()).append(suffix).append('\t').append(row.queryId()).append('\t')
                            .append(row.layer().label()).append('\t').append(row.rank()).append('\t')
                            .append(row.chunkId()).append('\t').append(Double.toHexString(row.score())).append('\n');
                    rows++;
                }
            }
        }
        return rows == 0 ? "" : tsv.append("end\t").append(rows).append('\n').toString();
    }

    private void describe(Path armsRoot, FeedbackQuerySplit split, List<Variant> variants,
            Optional<FeedbackEvents> plan, List<PhaseRun> phases, Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("extensions", HtmlKnowledgeFeedbackReport.EXTENSION + " (refined by Issue #110: key-strategy"
                + " treatment arms, union-key split, surface variants, storage-level log read-back, feedback-disabled"
                + " reference)");
        metadata.put("reopenMode", "restart (seed, feedback and reopen in separate JVM processes)");
        metadata.put("corpus.version", corpus.version());
        metadata.put("corpus.hash", corpus.corpusHash());
        metadata.put("corpus.chunks", Integer.toString(corpus.chunks().size()));
        metadata.put("query.version", querySet.version());
        metadata.put("query.hash", querySet.queryHash());
        metadata.put("query.count", Integer.toString(querySet.queries().size()));
        metadata.put("query.heldOutHash", "none (the #110 calibration / held-out split is internal; see split.hash)");
        metadata.put("query.tuning", "not tuned: no parameter was chosen on any query; events come from calibration"
                + " labels only");
        metadata.put("split.version", FeedbackQuerySplit.VERSION);
        metadata.put("split.hash", split.splitHash());
        metadata.put("split.calibration", Integer.toString(split.calibration().size()));
        metadata.put("split.heldOut", Integer.toString(split.heldOut().size()));
        metadata.put("variants.version", FeedbackKeyVariants.VERSION);
        metadata.put("variants.hash", FeedbackKeyVariants.variantHash(variants));
        metadata.put("variants.count", Integer.toString(variants.size()));
        plan.ifPresent(value -> {
            metadata.put("feedback.version", FeedbackEvents.VERSION);
            metadata.put("feedback.hash", value.feedbackHash());
            metadata.put("feedback.events", Integer.toString(value.events().size()));
            metadata.put("feedback.positive", Long.toString(value.count(FeedbackSignal.POSITIVE)));
            metadata.put("feedback.negative", Long.toString(value.count(FeedbackSignal.NEGATIVE)));
        });
        metadata.put("feedback.maxEvents", FeedbackEvents.MAX_EVENTS + " (at most "
                + FeedbackEvents.MAX_EVENTS_PER_QUERY + " per query)");
        metadata.put("feedback.deltaSource", "Store default of the three-argument feedback overload (+" + POSITIVE_DELTA
                + " / " + NEGATIVE_DELTA + ")");
        metadata.put("feedback.arms", Stream.of(FeedbackArm.values()).map(arm -> arm.label() + "="
                + arm.strategy().label()).toList().toString());
        metadata.put("seed", "none (no randomness)");
        metadata.put("k", Integer.toString(FeedbackEffectMetrics.K));
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        metadata.put("baseline.B1", "MonadaMemory.resonate(text).topK(limit).threshold(t), each arm's options");
        metadata.put("baseline.B2", "ResonanceStoreMemoryAdapter.recall(codec.querySignals(text), limit) with"
                + " HashedSignalDecoder and each arm's options");
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("ordering.direct", "Store: adjusted score descending, then atom id ascending");
        metadata.put("phase.forwardedJvmArguments", String.join(" ", phaseJvm.forwardedJvmArguments()));
        for (var run : phases) {
            metadata.put("phase." + run.phase() + ".pid", Long.toString(run.pid()));
            metadata.put("phase." + run.phase() + ".exit", Integer.toString(run.exitStatus()));
            metadata.put("process." + run.phase() + ".wallNanos", Long.toString(run.nanos()));
        }
        HtmlKnowledgePersistenceRunner.describeStore(HtmlKnowledgeFeedback.root(armsRoot, FeedbackArm.CONTROL),
                options.storeRoot(), metadata);
        metadata.put("runMode", (options.storeRoot().isPresent() ? "kept Store roots" : "default (temporary Store roots)")
                + "; profile html-knowledge-protocol-v1 " + HtmlKnowledgeFeedbackReport.EXTENSION + " relevance feedback");
        var environment = new RunEnvironment();
        environment.describeHost(metadata);
        environment.describeStoreRoot("env.storeRoot", armsRoot, metadata);
    }

    private static boolean sameRows(List<Row> first, List<Row> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (var i = 0; i < first.size(); i++) {
            if (!first.get(i).chunkId().equals(second.get(i).chunkId())
                    || !HtmlKnowledgeRecall.sameScore(first.get(i).score(), second.get(i).score())) {
                return false;
            }
        }
        return true;
    }

    private static int firstDifference(List<Row> left, List<Row> right) {
        var index = 0;
        while (index < left.size() && index < right.size() && left.get(index).equals(right.get(index))) {
            index++;
        }
        return index;
    }

    private static List<QueryText> texts(List<JudgedQuery> queries) {
        return queries.stream().map(query -> new QueryText(query.queryId(), query.text())).toList();
    }

    private static Set<String> ids(List<JudgedQuery> queries) {
        var ids = new LinkedHashSet<String>();
        queries.forEach(query -> ids.add(query.queryId()));
        return ids;
    }

    private List<Path> outputFiles() {
        var outputDir = options.outputDir();
        return List.of(outputDir.resolve(REPORT_JSON), outputDir.resolve(REPORT_MARKDOWN),
                outputDir.resolve(querySet.version() + "-feedback-rows.tsv"),
                outputDir.resolve(querySet.version() + "-feedback-events.tsv"),
                outputDir.resolve(querySet.version() + "-feedback-split.tsv"));
    }

    private Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-kq-feedback-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
