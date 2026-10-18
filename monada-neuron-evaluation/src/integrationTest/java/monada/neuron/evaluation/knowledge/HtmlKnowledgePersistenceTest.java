package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Row;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In-process behaviour of the persistence checks, including how they fail; persistence itself needs two JVMs. */
class HtmlKnowledgePersistenceTest {

    private final HtmlKnowledgeCorpus corpus = HtmlKnowledgeCorpus.bundled();
    private final HtmlKnowledgePersistence persistence = new HtmlKnowledgePersistence(corpus);

    @TempDir
    Path directory;

    @Test
    void seedObservesEveryChunkThroughBothPaths() {
        var seeded = persistence.seed(directory.resolve("store"));

        assertTrue(seeded.checks().stream().allMatch(Check::passed), seeded.checks().toString());
        var snapshot = seeded.observation().snapshot();
        var probes = persistence.probes().size();
        assertEquals(probes * HtmlKnowledgePersistence.TOP_K, snapshot.rows(HtmlKnowledgePersistence.DIRECT).size());
        assertEquals(snapshot.rows(HtmlKnowledgePersistence.DIRECT).stream().map(Row::chunkId).toList(),
                snapshot.rows(HtmlKnowledgePersistence.NEURON).stream().map(Row::chunkId).toList());
        assertTrue(snapshot.rows().stream().noneMatch(row -> row.chunkId().equals(HtmlKnowledgePersistence.UNMAPPED)));
        assertTrue(snapshot.rows().stream().allMatch(row -> Double.isFinite(row.score())));
    }

    @Test
    void aReadbackProbeRanksItsOwnChunkFirst() {
        // Not a quality claim: a chunk queried with its own exact text is the trivial upper bound of recall.
        var snapshot = persistence.seed(directory.resolve("store")).observation().snapshot();

        for (var chunk : corpus.chunks()) {
            var top = snapshot.rows(HtmlKnowledgePersistence.DIRECT).stream()
                    .filter(row -> row.probeId().equals("readback:" + chunk.chunkId()) && row.rank() == 0)
                    .findFirst().orElseThrow();
            assertEquals(chunk.chunkId(), top.chunkId());
        }
    }

    @Test
    void reopenReportsAChangedSnapshotWithItsFirstDifference() {
        var root = directory.resolve("store");
        var seeded = persistence.seed(root);
        var rows = new ArrayList<>(seeded.observation().snapshot().rows());
        var first = rows.getFirst();
        rows.set(0, new Row(first.probeId(), first.path(), first.rank(), first.chunkId(), first.score() + 1.0));
        var tampered = new Snapshot("another profile", "0".repeat(64), rows);

        var observation = persistence.reopen(root, seeded.manifest(), tampered);

        var recall = check(observation.checks(), "persistence.restartRecallEqual");
        assertFalse(recall.passed());
        assertTrue(recall.detail().contains("first difference at result 0"), recall.detail());
        assertFalse(check(observation.checks(), "persistence.restartProfileEqual").passed());
        assertFalse(check(observation.checks(), "persistence.restartProbeSetEqual").passed());
        assertTrue(check(observation.checks(), "neuron.restartEqual").passed());
        assertTrue(check(observation.checks(), "persistence.freshHandleConsistency").detail()
                .contains("not persistence evidence"));
    }

    @Test
    void refusesToObserveARootWithoutAStoreManifest() {
        var seeded = persistence.seed(directory.resolve("store"));
        var missing = directory.resolve("missing");

        assertThrows(IngestionInputException.class, () -> persistence.observe(missing, seeded.manifest()));
        assertFalse(Files.exists(missing), "the preflight must not create a store");
    }

    @Test
    void aRemovedAtomFailsTheCountAndReadbackWithItsChunkId() throws IOException {
        var root = directory.resolve("store");
        var seeded = persistence.seed(root);
        var target = seeded.manifest().entries().get(5);
        var log = root.resolve("atoms/segment-000001.log");
        var kept = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith(target.atomId() + "\t")).toList();
        Files.write(log, kept, StandardCharsets.UTF_8);

        var observation = persistence.observe(root, seeded.manifest());

        assertFalse(check(observation.checks(), "persistence.atomCount").passed());
        var readback = check(observation.checks(), "persistence.readback");
        assertFalse(readback.passed());
        assertTrue(readback.detail().contains(target.chunkId()), readback.detail());
    }

    @Test
    void aManifestOfAnotherImportFailsAttributionInsteadOfMatching() {
        var seeded = persistence.seed(directory.resolve("store"));
        var entries = seeded.manifest().entries();
        // Swap two chunk labels: references now point at the wrong chunk, and the decoded Signal disagrees.
        var swapped = new ArrayList<IngestionManifest.Entry>();
        for (var index = 0; index < entries.size(); index++) {
            var source = entries.get(index < 2 ? 1 - index : index);
            swapped.add(new IngestionManifest.Entry(entries.get(index).chunkId(), source.atomId(),
                    source.opaqueReference()));
        }
        var manifest = new IngestionManifest(corpus.version(), corpus.corpusHash(), swapped);

        var observation = persistence.observe(directory.resolve("store"), manifest);

        assertFalse(check(observation.checks(), "persistence.readback").passed());
        assertFalse(check(observation.checks(), "neuron.signalAttribution").passed());
    }

    @Test
    void anEmptyDefaultCodecResultIsAFailureNotEvidenceOfInsufficiency() throws IOException {
        var root = directory.resolve("store");
        var seeded = persistence.seed(root);
        // With every atom record gone the default adapter recalls nothing; disagreement must not count as a pass.
        Files.write(root.resolve("atoms/segment-000001.log"), new byte[0]);

        var check = persistence.defaultCodecInsufficiency(root, seeded.manifest(), seeded.observation().snapshot());

        assertFalse(check.passed());
        assertTrue(check.detail().contains("not COMPLETE or empty"), check.detail());
    }

    private static Check check(List<Check> checks, String name) {
        return checks.stream().filter(check -> check.name().equals(name)).findFirst().orElseThrow();
    }
}
