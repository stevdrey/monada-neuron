package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeCycleExperiment.Root;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Entrypoint of one evidence-trace phase (Issue #108), run by {@link HtmlKnowledgeEvidenceTraceRunner} in its own JVM,
 * so the {@code trace} phase shares no heap, handle or cache with the {@code seed} phase that wrote the roots.
 *
 * <p>Arguments: {@code seed|trace <store-root> <work-dir>}. The store root holds two Store roots: {@code full}, with
 * all of {@code html-kc-v1}, and {@code removed}, imported without {@link HtmlKnowledgeCycleExperiment#REMOVED_DOCUMENT}.
 * {@code seed} imports both and writes their manifests. {@code trace} reopens both, reads every chunk back from each
 * (the resolvers trust only chunks read back with matching bytes in this process), runs
 * {@link HtmlKnowledgeCycleExperiment} and writes its trace snapshot and result.
 *
 * <p>A phase never loads the query set: it reads only the labels-free workload the orchestrator wrote. Exit status:
 * {@code 0} all checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeEvidenceTracePhase {

    static final String SEED = "seed";
    static final String TRACE = "trace";
    static final String FULL = "full";
    static final String REMOVED = "removed";

    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeCorpus removedCorpus;
    private final WorkDirectory work;

    private HtmlKnowledgeEvidenceTracePhase(Path work) {
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.removedCorpus = withoutDocument(corpus, HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT);
        this.work = new WorkDirectory(work);
    }

    /** Phase entrypoint. */
    public static void main(String[] args) {
        if (args.length != 3 || !List.of(SEED, TRACE).contains(args[0])) {
            System.err.println("Usage: seed|trace <store-root> <work-dir>");
            System.exit(2);
            return;
        }
        int status;
        try {
            status = new HtmlKnowledgeEvidenceTracePhase(Path.of(args[2])).run(args[0], Path.of(args[1]));
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Evidence trace input rejected: " + e.getMessage());
            status = 2;
        }
        System.exit(status);
    }

    /**
     * Returns {@code corpus} without {@code documentId} and its chunks, as its own corpus version
     * {@code <version>-without-<slug>}; every corpus invariant is checked again.
     */
    static HtmlKnowledgeCorpus withoutDocument(HtmlKnowledgeCorpus corpus, String documentId) {
        var documents = corpus.documents().stream().filter(document -> !document.documentId().equals(documentId))
                .toList();
        if (documents.size() != corpus.documents().size() - 1) {
            throw new IllegalArgumentException(documentId + " is not a document of " + corpus.version());
        }
        var chunks = corpus.chunks().stream().filter(chunk -> !chunk.documentId().equals(documentId)).toList();
        return HtmlKnowledgeCorpus.of(corpus.version() + "-without-" + documentId.substring("doc:".length()),
                corpus.bounds(), documents, chunks);
    }

    /** Returns the root of {@code name} inside the phase's store root. */
    static Path root(Path storeRoot, String name) {
        return storeRoot.resolve(name);
    }

    /** Returns the work file of the manifest of root {@code name}. */
    static Path manifestFile(WorkDirectory work, String name) {
        return work.path().resolve(name + "-manifest.tsv");
    }

    private int run(String phase, Path storeRoot) {
        var checks = new ArrayList<Check>();
        var metadata = new LinkedHashMap<String, String>();
        if (phase.equals(SEED)) {
            seed(FULL, corpus, storeRoot, checks);
            seed(REMOVED, removedCorpus, storeRoot, checks);
        } else {
            var workload = QueryText.parseTsv(work.read(work.workload()));
            var full = reopen(FULL, corpus, storeRoot, checks);
            var removed = reopen(REMOVED, removedCorpus, storeRoot, checks);
            var config = new HtmlKnowledgeRecall(corpus).adapterConfig();
            var closed = ResonanceStoreMemoryAdapter.open(root(storeRoot, FULL), config);
            closed.close();
            var observation = new HtmlKnowledgeCycleExperiment().run(full, removed, closed, workload.queryHash(),
                    workload.queries());
            checks.addAll(observation.checks());
            metadata.putAll(observation.metadata());
            work.write(work.snapshot(TRACE), observation.snapshot().toTsv());
        }
        var result = new Result(checks, HtmlKnowledgePersistencePhase.environment(metadata));
        work.write(work.result(phase), result.render());
        return checks.stream().allMatch(Check::passed) ? 0 : 1;
    }

    private void seed(String name, HtmlKnowledgeCorpus source, Path storeRoot, List<Check> checks) {
        var imported = new HtmlKnowledgeIngestion(source).ingest(root(storeRoot, name));
        imported.checks().forEach(check -> checks.add(prefixed(name, check)));
        work.write(manifestFile(work, name), imported.manifest().toTsv());
    }

    /**
     * Reopens root {@code name}, reads every chunk back through a new handle and builds its resolver from what was read
     * back, then opens the production adapter that serves the cycles.
     */
    private Root reopen(String name, HtmlKnowledgeCorpus source, Path storeRoot, List<Check> checks) {
        var reopened = verifiedReopen(name, source, storeRoot, work, checks);
        var resolver = new RecalledReferenceResolver(source, reopened.manifest(), reopened.unread());
        var adapter = ResonanceStoreMemoryAdapter.open(reopened.root(),
                new HtmlKnowledgeRecall(source).adapterConfig());
        return new Root(name, adapter, resolver);
    }

    /**
     * A reopened root.
     *
     * @param root the Store root
     * @param manifest its import manifest, complete for its corpus
     * @param unread the chunks not read back with matching bytes in this process
     */
    record Reopened(Path root, IngestionManifest manifest, Set<String> unread) {

        Reopened {
            unread = Set.copyOf(unread);
        }
    }

    /**
     * Reopens root {@code name} of a seeded store root and reads every chunk back through a new handle, adding the
     * {@code <name>.persistence.readback} and {@code <name>.persistence.noUnmappedAtoms} checks. Shared by the
     * evidence-trace (#108) and grounded-answer (#109) phases.
     */
    static Reopened verifiedReopen(String name, HtmlKnowledgeCorpus source, Path storeRoot, WorkDirectory work,
            List<Check> checks) {
        var root = root(storeRoot, name);
        HtmlKnowledgePersistence.requireExistingStore(root);
        var manifest = IngestionManifest.parseTsv(work.read(manifestFile(work, name)), source.version(),
                source.corpusHash()).requireComplete(source);
        var readback = new HtmlKnowledgeIngestion(source)
                .readbackSweep(MonadaMemory.open(root, MonadaMemoryOptions.defaults()), manifest);
        var unmapped = new HashSet<>(readback.recalledAtomIds());
        manifest.entries().forEach(entry -> unmapped.remove(entry.atomId()));
        checks.add(new Check(name + ".persistence.readback", readback.unread().isEmpty(), readback.unread().isEmpty()
                ? "every " + source.version() + " chunk was read back from the reopened root with matching bytes"
                : "not read back: " + readback.unread()));
        checks.add(new Check(name + ".persistence.noUnmappedAtoms", unmapped.isEmpty(), unmapped.isEmpty()
                ? "every atom the readback returned maps through the manifest"
                : unmapped.size() + " atoms are not in the manifest"));
        return new Reopened(root, manifest, new HashSet<>(readback.unread()));
    }

    private static Check prefixed(String name, Check check) {
        return new Check(name + "." + check.name(), check.passed(), check.detail());
    }

    /** Returns the phase names, in launch order. */
    static List<String> phases() {
        return List.of(SEED, TRACE);
    }
}
