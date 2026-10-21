package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entrypoint of one relevance-feedback phase, run by {@link HtmlKnowledgeFeedbackRunner} in its own JVM.
 *
 * <p>Arguments: {@code seed|feedback|reopen <arms-root> <work-dir>}; the arms root holds one Store root per
 * {@link FeedbackArm}.
 * <ul>
 *   <li>{@code seed} imports every arm, checks that every feedback log is empty and observes every arm before any
 *       event.</li>
 *   <li>{@code feedback} records the plan in each treatment arm, reads every arm's feedback log back and observes every
 *       arm again, before any restart.</li>
 *   <li>{@code reopen}, in a restarted process, reads every chunk and every feedback log back, then observes every arm
 *       with feedback-aware ranking on and again with it off: the Store directly (B1, B2c) and through the Neuron
 *       adapter (B2).</li>
 * </ul>
 *
 * <p>A phase never loads the query set: it reads only the labels-free workload and the labels-free feedback plan the
 * orchestrator wrote, so no gold label exists in a phase JVM. Exit status: {@code 0} all checks passed, {@code 1} a
 * semantic check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeFeedbackPhase {

    static final String SEED = "seed";
    static final String FEEDBACK = "feedback";
    static final String REOPEN = "reopen";

    /** Label suffix of an observation with feedback-aware ranking disabled. */
    static final String DISABLED = "-disabled";

    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeFeedback feedback;
    private final WorkDirectory work;

    private HtmlKnowledgeFeedbackPhase(Path work) {
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.feedback = new HtmlKnowledgeFeedback(corpus);
        this.work = new WorkDirectory(work);
    }

    /** Phase entrypoint. */
    public static void main(String[] args) {
        if (args.length != 3 || !List.of(SEED, FEEDBACK, REOPEN).contains(args[0])) {
            System.err.println("Usage: seed|feedback|reopen <arms-root> <work-dir>");
            System.exit(2);
            return;
        }
        int status;
        try {
            status = new HtmlKnowledgeFeedbackPhase(Path.of(args[2])).run(args[0], Path.of(args[1]));
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Feedback input rejected: " + e.getMessage());
            status = 2;
        }
        System.exit(status);
    }

    /** Returns the work file holding the labels-free feedback plan. */
    static Path plan(WorkDirectory work) {
        return work.path().resolve("feedback-plan.tsv");
    }

    /** Returns the work file holding the import manifest of {@code arm}. */
    static Path manifest(WorkDirectory work, FeedbackArm arm) {
        return work.manifest("arm-" + arm.label());
    }

    /** Returns the work file holding the snapshot of observation {@code label} in {@code phase}. */
    static Path snapshot(WorkDirectory work, String phase, String label) {
        return work.snapshot(phase + "-" + label);
    }

    /** Returns the work file holding the feedback log of {@code arm} as read back in {@code phase}. */
    static Path events(WorkDirectory work, String phase, FeedbackArm arm) {
        return work.path().resolve(phase + "-" + arm.label() + "-events.tsv");
    }

    private int run(String phase, Path armsRoot) {
        var workload = QueryText.parseTsv(work.read(work.workload()));
        var checks = new ArrayList<Check>();
        var metadata = new LinkedHashMap<String, String>();
        switch (phase) {
            case SEED -> seed(armsRoot, workload, checks, metadata);
            case FEEDBACK -> feedback(armsRoot, workload, checks, metadata);
            default -> reopen(armsRoot, workload, checks, metadata);
        }
        var result = new Result(checks, HtmlKnowledgePersistencePhase.environment(metadata));
        work.write(work.result(phase), result.render());
        return checks.stream().allMatch(Check::passed) ? 0 : 1;
    }

    private void seed(Path armsRoot, QueryText.Workload workload, List<Check> checks, Map<String, String> metadata) {
        for (var arm : FeedbackArm.values()) {
            var imported = feedback.ingest(armsRoot, arm);
            imported.checks().forEach(check -> checks.add(new Check(arm.label() + "." + check.name(), check.passed(),
                    check.detail())));
            work.write(manifest(work, arm), imported.manifest().toTsv());
            var log = FeedbackEventLog.read(HtmlKnowledgeFeedback.root(armsRoot, arm), imported.manifest());
            checks.add(new Check(arm.label() + ".feedback.logEmpty", log.events().isEmpty() && log.logBytes() == 0,
                    log.events().isEmpty() && log.logBytes() == 0
                            ? "the freshly imported root's feedback log exists and is empty"
                            : "the freshly imported root already holds " + log.events().size() + " events ("
                                    + log.logBytes() + " bytes)"));
            observe(SEED, armsRoot, arm, true, imported.manifest(), workload, false, checks, metadata);
        }
        checks.add(feedback.strategyNeutral(armsRoot));
    }

    private void feedback(Path armsRoot, QueryText.Workload workload, List<Check> checks,
            Map<String, String> metadata) {
        var plan = FeedbackEvents.parseTsv(work.read(plan(work)));
        var manifests = manifests();
        for (var arm : FeedbackArm.treatments()) {
            var durations = new ArrayList<Long>();
            checks.add(feedback.apply(armsRoot, arm, manifests.get(arm), plan, durations));
            LatencyRow.wallClock(LatencyRow.samples(durations), 1, "one MonadaMemory.feedback(query, atomId, signal)"
                    + " call (an append to the root's feedback log), on one handle per arm", false)
                    .describe(arm.label() + ".perf.feedbackWrite", metadata);
        }
        for (var arm : FeedbackArm.values()) {
            var log = FeedbackEventLog.read(HtmlKnowledgeFeedback.root(armsRoot, arm), manifests.get(arm));
            work.write(events(work, FEEDBACK, arm), log.toTsv());
            if (!arm.treatment()) {
                checks.add(new Check("control.feedback.none", log.events().isEmpty() && log.logBytes() == 0,
                        log.events().isEmpty() && log.logBytes() == 0
                                ? "the control root's feedback log is still empty after the treatment arms received"
                                        + " their events"
                                : "the control root holds " + log.events().size() + " events"));
            }
        }
        for (var arm : FeedbackArm.values()) {
            observe(FEEDBACK, armsRoot, arm, true, manifests.get(arm), workload, arm.treatment(), checks, metadata);
        }
    }

    private void reopen(Path armsRoot, QueryText.Workload workload, List<Check> checks, Map<String, String> metadata) {
        var manifests = manifests();
        for (var arm : FeedbackArm.values()) {
            checks.addAll(feedback.readback(armsRoot, arm, manifests.get(arm)));
            var log = FeedbackEventLog.read(HtmlKnowledgeFeedback.root(armsRoot, arm), manifests.get(arm));
            work.write(events(work, REOPEN, arm), log.toTsv());
        }
        // Direct Store paths and the adapter are observed together per arm (B1 and B2c are recorded before B2 for each
        // query); every arm with feedback-aware ranking first, then every arm with it disabled.
        for (var arm : FeedbackArm.values()) {
            observe(REOPEN, armsRoot, arm, true, manifests.get(arm), workload, arm.treatment(), checks, metadata);
        }
        for (var arm : FeedbackArm.values()) {
            observe(REOPEN, armsRoot, arm, false, manifests.get(arm), workload, false, checks, metadata);
        }
    }

    private void observe(String phase, Path armsRoot, FeedbackArm arm, boolean feedbackAware,
            IngestionManifest manifest, QueryText.Workload workload, boolean prefixDiagnostic, List<Check> checks,
            Map<String, String> metadata) {
        var label = arm.label() + (feedbackAware ? "" : DISABLED);
        var observed = feedback.observe(armsRoot, arm, feedbackAware, label, manifest, workload.queryHash(),
                workload.queries(), prefixDiagnostic);
        checks.addAll(observed.checks());
        metadata.putAll(observed.metadata());
        work.write(snapshot(work, phase, label), observed.observation().snapshot().toTsv());
    }

    private Map<FeedbackArm, IngestionManifest> manifests() {
        var manifests = new LinkedHashMap<FeedbackArm, IngestionManifest>();
        for (var arm : FeedbackArm.values()) {
            manifests.put(arm, IngestionManifest.parseTsv(work.read(manifest(work, arm)), corpus.version(),
                    corpus.corpusHash()).requireComplete(corpus));
        }
        return manifests;
    }
}
