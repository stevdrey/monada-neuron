package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Entrypoint of one judged-recall phase, run by {@link HtmlKnowledgeRecallRunner} in its own JVM so that the
 * {@code reopen} phase shares no heap, handle or cache with the {@code seed} phase that wrote the root.
 *
 * <p>Arguments: {@code seed|reopen|replica <store-root> <work-dir>}. {@code replica} does what {@code seed} does in its
 * own fresh root, so the orchestrator can compare two independent imports. {@code seed} imports the corpus into the fresh root,
 * writes the manifest, then runs every query and writes its recall snapshot and result. {@code reopen} reads the
 * manifest, reads every corpus chunk back from the existing root (the protocol's ingestion readback, repeated in the
 * restarted process), runs every query with new handles and writes its own snapshot and result.
 *
 * <p>A phase never loads the query set: it reads only the labels-free workload ({@code queryHash}, query ids and
 * texts) that the orchestrator wrote to the work directory, so no gold label exists in a phase JVM. Exit status:
 * {@code 0} all checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeRecallPhase {

    static final String SEED = "seed";
    static final String REOPEN = "reopen";

    /** A second, independent import into its own fresh root, compared with the seed for fresh-root determinism. */
    static final String REPLICA = "replica";

    private final HtmlKnowledgeCorpus corpus;
    private final WorkDirectory work;

    private HtmlKnowledgeRecallPhase(Path work) {
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.work = new WorkDirectory(work);
    }

    /** Phase entrypoint. */
    public static void main(String[] args) {
        if (args.length != 3 || !List.of(SEED, REOPEN, REPLICA).contains(args[0])) {
            System.err.println("Usage: seed|reopen|replica <store-root> <work-dir>");
            System.exit(2);
            return;
        }
        int status;
        try {
            status = new HtmlKnowledgeRecallPhase(Path.of(args[2])).run(args[0], Path.of(args[1]));
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Recall input rejected: " + e.getMessage());
            status = 2;
        }
        System.exit(status);
    }

    private int run(String phase, Path root) {
        var workload = QueryText.parseTsv(work.read(work.workload()));
        var checks = new ArrayList<Check>();
        var timings = new LinkedHashMap<String, String>();
        IngestionManifest manifest;
        if (!phase.equals(REOPEN)) {
            var imported = new HtmlKnowledgeIngestion(corpus).ingest(root);
            checks.addAll(imported.checks());
            manifest = imported.manifest();
            work.write(work.manifest(phase), manifest.toTsv());
            var importTimings = new HtmlKnowledgeIngestion(corpus).timings(imported);
            importTimings.describe("perf", "ingestReadback", "env.timingScratch", timings);
            checks.add(importTimings.check());
        } else {
            manifest = IngestionManifest.parseTsv(work.read(work.manifest()), corpus.version(), corpus.corpusHash())
                    .requireComplete(corpus);
        }
        var recall = new HtmlKnowledgeRecall(corpus);
        if (phase.equals(REOPEN)) {
            // The seed's ingestion already read every chunk back; the restarted process repeats that for itself.
            var samples = new ArrayList<Long>();
            List<Check> reported = null;
            for (var repetition = 0; repetition < LatencyRow.REPETITIONS; repetition++) {
                var start = System.nanoTime();
                var readback = recall.readback(root, manifest);
                var elapsed = System.nanoTime() - start;
                var passed = readback.stream().allMatch(Check::passed);
                if (passed) {
                    samples.add(elapsed);
                }
                // Every repetition is a readback of its own: the first failing one is reported, else the first.
                if (reported == null || (!passed && reported.stream().allMatch(Check::passed))) {
                    reported = readback;
                }
            }
            checks.addAll(reported);
            LatencyRow.wallClock(samples.stream().mapToLong(Long::longValue).toArray(), corpus.chunks().size(),
                    "Store open plus one resonate per chunk, with the content hash check in the window; every"
                            + " repetition is checked and a failed one is excluded", true)
                    .describe("perf.reopenReadback", timings);
        }
        var observation = recall.observe(root, manifest, workload.queryHash(), workload.queries());
        checks.addAll(observation.checks());
        work.write(work.snapshot(phase), observation.snapshot().toTsv());
        timings.putAll(observation.metadata());
        var result = new Result(checks, HtmlKnowledgePersistencePhase.environment(timings));
        work.write(work.result(phase), result.render());
        return checks.stream().allMatch(Check::passed) ? 0 : 1;
    }
}
