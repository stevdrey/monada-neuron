package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses a small synthetic stand-in written for this test; no MDN text is stored in the repository. */
class MdnSnapshotSourceTest {

    static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    /** Synthetic snapshot in the mdn/content layout: YAML front matter, a macro line and sections. */
    static final String SYNTHETIC_SNAPSHOT = """
            ---
            title: "Synthetic stand-in"
            slug: Learn_web_development/Getting_started/Your_first_website/Creating_the_content
            ---

            {{LearnSidebar}}

            Synthetic preamble sentence for the snapshot test.

            ## Synthetic section one

            The zebra-marker paragraph appears only in this synthetic test file.

            ```html
            <p>synthetic</p>
            ```

            ## Synthetic section two

            ### Nested heading

            A second synthetic paragraph.
            """;

    private final MdnSnapshotSource source = new MdnSnapshotSource();

    @TempDir
    Path directory;

    @Test
    void missingSnapshotFailsExplicitly() {
        var failure = assertThrows(CorpusValidationException.class,
                () -> source.load(directory.resolve("index.md"), REVISION, "0".repeat(64)));

        assertTrue(failure.getMessage().contains("requested external MDN snapshot is missing"), failure.getMessage());
    }

    @Test
    void rejectsADifferentDigestOrAnInvalidPin() throws IOException {
        var snapshot = write(SYNTHETIC_SNAPSHOT);

        assertTrue(assertThrows(CorpusValidationException.class,
                () -> source.load(snapshot, REVISION, "0".repeat(64))).getMessage().contains("differs from the pinned"));
        assertThrows(CorpusValidationException.class, () -> source.load(snapshot, "main", sha256(snapshot)));
        assertThrows(CorpusValidationException.class, () -> source.load(snapshot, REVISION, "ABC"));
    }

    @Test
    void rejectsUnterminatedFrontMatter() throws IOException {
        var snapshot = write("---\ntitle: open\n\nBody.");

        assertTrue(assertThrows(CorpusValidationException.class,
                () -> source.load(snapshot, REVISION, sha256(snapshot))).getMessage().contains("front matter"));
    }

    @Test
    void chunksTheSnapshotWithLicenseAttributionAndPinnedSource() throws IOException {
        var snapshot = write(SYNTHETIC_SNAPSHOT);

        var corpus = source.load(snapshot, REVISION, sha256(snapshot));

        assertEquals(MdnSnapshotSource.VERSION, corpus.version());
        assertEquals(3, corpus.chunks().size());
        assertEquals("chk:mdn-creating-the-content#00", corpus.chunks().getFirst().chunkId());
        assertEquals(CorpusChunker.PREAMBLE, corpus.chunks().getFirst().section());
        assertEquals("Nested heading", corpus.chunks().get(2).section());
        assertFalse(corpus.chunks().getFirst().text().contains("title:"));
        for (var chunk : corpus.chunks()) {
            assertEquals(MdnSnapshotSource.LICENSE, chunk.license());
            assertEquals(MdnSnapshotSource.ATTRIBUTION, chunk.attribution());
            assertTrue(chunk.source().contains(MdnSnapshotSource.PAGE_URL));
            assertTrue(chunk.source().contains("mdn/content@" + REVISION));
        }
        assertFalse(corpus.labelsTsv().contains("zebra-marker"));
        var ingested = new HtmlKnowledgeIngestion(corpus).ingest(directory.resolve("store"));
        assertTrue(ingested.allPassed(), ingested.checks().toString());
    }

    private Path write(String content) throws IOException {
        return Files.writeString(directory.resolve("index.md"), content);
    }

    static String sha256(Path file) throws IOException {
        return CorpusDigests.sha256(Files.readAllBytes(file));
    }
}
