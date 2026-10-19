package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Entrypoint of one judged-recall phase, run by {@link HtmlKnowledgeRecallRunner} in its own JVM so that the
 * {@code reopen} phase shares no heap, handle or cache with the {@code seed} phase that wrote the root.
 *
 * <p>Arguments: {@code seed|reopen <store-root> <work-dir>}. {@code seed} imports the corpus into the fresh root,
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

    private final HtmlKnowledgeCorpus corpus;
    private final WorkDirectory work;

    private HtmlKnowledgeRecallPhase(Path work) {
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.work = new WorkDirectory(work);
    }

    /** Phase entrypoint. */
    public static void main(String[] args) {
        if (args.length != 3 || !(args[0].equals(SEED) || args[0].equals(REOPEN))) {
            System.err.println("Usage: seed|reopen <store-root> <work-dir>");
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
        IngestionManifest manifest;
        if (phase.equals(SEED)) {
            var imported = new HtmlKnowledgeIngestion(corpus).ingest(root);
            checks.addAll(imported.checks());
            manifest = imported.manifest();
            work.write(work.manifest(), manifest.toTsv());
        } else {
            manifest = IngestionManifest.parseTsv(work.read(work.manifest()), corpus.version(), corpus.corpusHash());
        }
        var recall = new HtmlKnowledgeRecall(corpus);
        if (phase.equals(REOPEN)) {
            // The seed's ingestion already read every chunk back; the restarted process repeats that for itself.
            checks.addAll(recall.readback(root, manifest));
        }
        var observation = recall.observe(root, manifest, workload.queryHash(), workload.queries());
        checks.addAll(observation.checks());
        work.write(work.snapshot(phase), observation.snapshot().toTsv());
        var result = new Result(checks, HtmlKnowledgePersistencePhase.environment(observation.metadata()));
        work.write(work.result(phase), result.render());
        return checks.stream().allMatch(Check::passed) ? 0 : 1;
    }
}
