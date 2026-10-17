package monada.neuron.evaluation.knowledge;

import monada.neuron.resonance.adapter.OpaqueReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlKnowledgeIngestionTest {

    private final HtmlKnowledgeCorpus corpus = HtmlKnowledgeCorpus.bundled();

    @TempDir
    Path directory;

    @Test
    void importsEveryChunkThroughRememberAndVerifiesItByReadback() {
        var result = new HtmlKnowledgeIngestion(corpus).ingest(directory.resolve("store"));

        assertTrue(result.allPassed(), result.checks().toString());
        assertEquals(List.of("ingestion.manifestComplete", "ingestion.contentFidelity", "ingestion.readback"),
                result.checks().stream().map(HtmlKnowledgeIngestion.Check::name).toList());
        var manifest = result.manifest();
        assertEquals(corpus.version(), manifest.corpusVersion());
        assertEquals(corpus.corpusHash(), manifest.corpusHash());
        assertEquals(corpus.chunks().stream().map(CorpusChunk::chunkId).toList(),
                manifest.entries().stream().map(IngestionManifest.Entry::chunkId).toList());
        var references = new OpaqueReference();
        for (var entry : manifest.entries()) {
            assertEquals(entry.chunkId(), manifest.chunkIdForAtom(entry.atomId()).orElseThrow());
            assertEquals(entry.chunkId(), manifest.chunkIdForReference(entry.opaqueReference()).orElseThrow());
            assertEquals(references.of(entry.atomId()), entry.opaqueReference());
        }
        assertTrue(Files.exists(directory.resolve("store/manifest.json")));
    }

    @Test
    void separateImportsAgreeOnLabelsOrderAndFingerprintsThroughTheirOwnManifests() {
        var first = new HtmlKnowledgeIngestion(corpus).ingest(directory.resolve("first"));
        var second = new HtmlKnowledgeIngestion(HtmlKnowledgeCorpus.bundled()).ingest(directory.resolve("second"));

        // Atom ids are compared only through each root's manifest. The Store currently derives them from content,
        // but the harness does not rely on that: it never predicts an id, it records what remember returned.
        assertTrue(first.allPassed() && second.allPassed());
        assertEquals(chunkSequence(first.manifest()), chunkSequence(second.manifest()));
        assertEquals(first.manifest().corpusHash(), second.manifest().corpusHash());
        for (var manifest : List.of(first.manifest(), second.manifest())) {
            for (var entry : manifest.entries()) {
                assertEquals(entry.chunkId(), manifest.chunkIdForAtom(entry.atomId()).orElseThrow());
            }
        }
    }

    @Test
    void refusesASecondImportIntoTheSameRoot() {
        var root = directory.resolve("store");
        new HtmlKnowledgeIngestion(corpus).ingest(root);

        var failure = assertThrows(IllegalStateException.class, () -> new HtmlKnowledgeIngestion(corpus).ingest(root));
        assertTrue(failure.getMessage().contains("duplicate import refused"), failure.getMessage());
    }

    @Test
    void refusesARootThatAlreadyHoldsFilesOrIsAFile() throws IOException {
        var occupied = Files.createDirectories(directory.resolve("occupied"));
        Files.writeString(occupied.resolve("notes.txt"), "unrelated");
        var file = Files.writeString(directory.resolve("plain-file"), "x");

        assertThrows(IllegalStateException.class, () -> new HtmlKnowledgeIngestion(corpus).ingest(occupied));
        assertThrows(IllegalStateException.class, () -> new HtmlKnowledgeIngestion(corpus).ingest(file));
        assertFalse(Files.exists(occupied.resolve("manifest.json")));
    }

    @Test
    void manifestCarriesNoContentHashesAndRejectsAnAtomMappedTwice() {
        var manifest = new HtmlKnowledgeIngestion(corpus).ingest(directory.resolve("store")).manifest();
        var tsv = manifest.toTsv();

        assertTrue(tsv.contains(IngestionManifest.HEADER));
        assertTrue(corpus.chunks().stream().noneMatch(chunk -> tsv.contains(chunk.sha256())));
        var entry = manifest.entries().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new IngestionManifest("v", "h", List.of(entry,
                new IngestionManifest.Entry("chk:other#00", entry.atomId(), entry.opaqueReference()))));
    }

    private static List<String> chunkSequence(IngestionManifest manifest) {
        return manifest.entries().stream().map(IngestionManifest.Entry::chunkId).toList();
    }
}
