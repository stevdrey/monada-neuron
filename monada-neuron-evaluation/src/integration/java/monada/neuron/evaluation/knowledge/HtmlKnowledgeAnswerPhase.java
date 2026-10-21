package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Entrypoint of one grounded-answer phase (Issue #109), run by {@link HtmlKnowledgeAnswerRunner} in its own JVM, so the
 * {@code answer} phase shares no heap, handle or cache with the {@code seed} phase that wrote the roots.
 *
 * <p>Arguments: {@code seed|answer <store-root> <work-dir>}. As in #108, the store root holds {@code full}, with all of
 * {@code html-kc-v1}, and {@code removed}, imported without {@link HtmlKnowledgeQaExperiment#REMOVED_DOCUMENT}.
 * {@code seed} imports both and writes their manifests. {@code answer} reopens both, reads every chunk back from each,
 * runs {@link HtmlKnowledgeQaExperiment} over the labels-free workload and writes its answer snapshot and result.
 *
 * <p>A phase never loads the query set or the judgments. Exit status: {@code 0} all checks passed, {@code 1} a semantic
 * check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeAnswerPhase {

    static final String SEED = "seed";
    static final String ANSWER = "answer";
    static final String FULL = HtmlKnowledgeEvidenceTracePhase.FULL;
    static final String REMOVED = HtmlKnowledgeEvidenceTracePhase.REMOVED;

    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeCorpus removedCorpus;
    private final WorkDirectory work;

    private HtmlKnowledgeAnswerPhase(Path work) {
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.removedCorpus = HtmlKnowledgeEvidenceTracePhase.withoutDocument(corpus,
                HtmlKnowledgeQaExperiment.REMOVED_DOCUMENT);
        this.work = new WorkDirectory(work);
    }

    /** Phase entrypoint. */
    public static void main(String[] args) {
        if (args.length != 3 || !List.of(SEED, ANSWER).contains(args[0])) {
            System.err.println("Usage: seed|answer <store-root> <work-dir>");
            System.exit(2);
            return;
        }
        int status;
        try {
            status = new HtmlKnowledgeAnswerPhase(Path.of(args[2])).run(args[0], Path.of(args[1]));
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Answer input rejected: " + e.getMessage());
            status = 2;
        }
        System.exit(status);
    }

    private int run(String phase, Path storeRoot) {
        var checks = new ArrayList<Check>();
        var metadata = new LinkedHashMap<String, String>();
        if (phase.equals(SEED)) {
            seed(FULL, corpus, storeRoot, checks);
            seed(REMOVED, removedCorpus, storeRoot, checks);
        } else {
            var workload = QueryText.parseTsv(work.read(work.workload()));
            var roots = List.of(reopen(FULL, corpus, storeRoot, checks),
                    reopen(REMOVED, removedCorpus, storeRoot, checks));
            var observation = new HtmlKnowledgeQaExperiment().run(roots, workload.queryHash(), workload.queries());
            checks.addAll(observation.checks());
            metadata.putAll(observation.metadata());
            work.write(work.snapshot(ANSWER), observation.snapshot().toTsv());
        }
        var result = new Result(checks, HtmlKnowledgePersistencePhase.environment(metadata));
        work.write(work.result(phase), result.render());
        return checks.stream().allMatch(Check::passed) ? 0 : 1;
    }

    private void seed(String name, HtmlKnowledgeCorpus source, Path storeRoot, List<Check> checks) {
        var imported = new HtmlKnowledgeIngestion(source).ingest(HtmlKnowledgeEvidenceTracePhase.root(storeRoot, name));
        imported.checks().forEach(check -> checks.add(new Check(name + "." + check.name(), check.passed(),
                check.detail())));
        work.write(HtmlKnowledgeEvidenceTracePhase.manifestFile(work, name), imported.manifest().toTsv());
    }

    /** Reopens root {@code name} with the shared verified readback, then opens the answer handles. */
    private HtmlKnowledgeQaExperiment.Root reopen(String name, HtmlKnowledgeCorpus source, Path storeRoot,
            List<Check> checks) {
        var reopened = HtmlKnowledgeEvidenceTracePhase.verifiedReopen(name, source, storeRoot, work, checks);
        return new HtmlKnowledgeQaExperiment.Root(name, MonadaMemory.open(reopened.root(),
                MonadaMemoryOptions.defaults()), ResonanceStoreMemoryAdapter.open(reopened.root(),
                new HtmlKnowledgeRecall(source).adapterConfig()), new RecalledEvidence(source, reopened.manifest()));
    }

    /** Returns the phase names, in launch order. */
    static List<String> phases() {
        return List.of(SEED, ANSWER);
    }
}
