package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HtmlKnowledgeIngestionRunnerTest {

    @TempDir
    Path directory;

    @Test
    void defaultRunPassesReportsExternalAsUnavailableAndWritesOutputs() throws IOException {
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {"--output-dir", directory.toString()});

        var outcome = HtmlKnowledgeIngestionRunner.run(options);
        var written = HtmlKnowledgeIngestionRunner.write(outcome);

        assertTrue(outcome.report().allPassed(), outcome.report().toMarkdown());
        assertTrue(outcome.external().isEmpty());
        assertEquals(4, written.size());
        var json = Files.readString(directory.resolve("html-knowledge-ingestion.json"));
        assertTrue(json.contains("\"external.mdn\": \"unavailable (not supplied)\""));
        assertTrue(json.contains("\"corpus.hash\": \"" + HtmlKnowledgeCorpus.bundled().corpusHash() + "\""));
        assertTrue(json.contains("\"protocolVersion\": \"html-knowledge-protocol-v1\""));
        assertTrue(json.contains("\"store.root\": \"temporary (deleted)\""));
        assertTrue(Files.readString(directory.resolve("html-kc-v1-manifest.tsv")).contains(IngestionManifest.HEADER));
        assertTrue(Files.readString(directory.resolve("html-kc-v1-chunks.tsv")).contains("conflict:img-alt"));
        assertFalse(Files.exists(outcome.authored().result().root()));
    }

    @Test
    void keepsARequestedStoreRootAndRefusesToImportIntoItAgain() {
        var root = directory.resolve("kept");
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", root.toString(), "--output-dir", directory.resolve("reports").toString()});

        HtmlKnowledgeIngestionRunner.run(options);

        assertTrue(Files.exists(root.resolve("manifest.json")));
        assertThrows(IngestionInputException.class, () -> HtmlKnowledgeIngestionRunner.run(options));
    }

    @Test
    void aRejectedRunLeavesNoOutputOfAnEarlierRun() throws IOException {
        var output = directory.resolve("reports");
        var occupied = Files.createDirectories(directory.resolve("occupied"));
        Files.writeString(occupied.resolve("notes.txt"), "unrelated");
        HtmlKnowledgeIngestionRunner.write(HtmlKnowledgeIngestionRunner.run(
                HtmlKnowledgeIngestionRunner.Options.parse(new String[] {"--output-dir", output.toString()})));
        assertTrue(Files.exists(output.resolve("html-knowledge-ingestion.json")));

        assertThrows(IngestionInputException.class, () -> HtmlKnowledgeIngestionRunner.run(
                HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                        "--output-dir", output.toString(), "--store-root", occupied.toString()})));

        assertFalse(Files.exists(output.resolve("html-knowledge-ingestion.json")));
        assertFalse(Files.exists(output.resolve("html-kc-v1-manifest.tsv")));
    }

    @Test
    void suppliedSnapshotIsIngestedSeparatelyWithoutItsTextInAnyOutput() throws IOException {
        var snapshot = Files.writeString(directory.resolve("index.md"), MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT);
        var output = directory.resolve("reports");
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--output-dir", output.toString(),
                "--mdn-snapshot", snapshot.toString(),
                "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                "--mdn-sha256", MdnSnapshotSourceTest.sha256(snapshot)});

        var outcome = HtmlKnowledgeIngestionRunner.run(options);
        var written = HtmlKnowledgeIngestionRunner.write(outcome);

        assertTrue(outcome.report().allPassed(), outcome.report().toMarkdown());
        assertEquals(6, written.size());
        assertTrue(outcome.report().checks().stream()
                .anyMatch(check -> check.name().equals("external.mdn.ingestion.readback")));
        for (var file : written) {
            var content = Files.readString(file);
            for (var line : MdnSnapshotSourceTest.snapshotLines()) {
                assertFalse(content.contains(line), file + " contains snapshot text: " + line);
            }
        }
    }

    @Test
    void anAuthoredOnlyRunRemovesExternalOutputsOfAnEarlierRun() throws IOException {
        var snapshot = Files.writeString(directory.resolve("index.md"), MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT);
        var output = directory.resolve("reports");
        HtmlKnowledgeIngestionRunner.write(HtmlKnowledgeIngestionRunner.run(HtmlKnowledgeIngestionRunner.Options.parse(
                new String[] {"--output-dir", output.toString(), "--mdn-snapshot", snapshot.toString(),
                        "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                        "--mdn-sha256", MdnSnapshotSourceTest.sha256(snapshot)})));
        assertTrue(Files.exists(output.resolve("html-kc-ext-mdn-v1-manifest.tsv")));

        HtmlKnowledgeIngestionRunner.write(HtmlKnowledgeIngestionRunner.run(
                HtmlKnowledgeIngestionRunner.Options.parse(new String[] {"--output-dir", output.toString()})));

        assertFalse(Files.exists(output.resolve("html-kc-ext-mdn-v1-manifest.tsv")));
        assertFalse(Files.exists(output.resolve("html-kc-ext-mdn-v1-chunks.tsv")));
        assertTrue(Files.exists(output.resolve("html-kc-v1-manifest.tsv")));
    }

    @Test
    void rejectsOverlappingStoreRootsAndAnOutputDirectoryInsideAStoreRoot() {
        var authored = directory.resolve("roots/authored");
        var mdnArgs = new String[] {"--mdn-snapshot", "index.md", "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                "--mdn-sha256", "0".repeat(64)};
        for (var mdnRoot : List.of(authored, authored.resolve("nested"), directory.resolve("roots"),
                directory.resolve("roots/./authored"))) {
            var args = concat(new String[] {
                    "--store-root", authored.toString(), "--mdn-store-root", mdnRoot.toString()}, mdnArgs);
            assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeIngestionRunner.Options.parse(args),
                    mdnRoot.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", authored.toString(), "--output-dir", authored.resolve("reports").toString()}));
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", authored.toString(), "--output-dir", authored.toString()}));
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", authored.toString(), "--output-dir", directory.resolve("roots").toString()}));
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeIngestionRunner.Options.parse(concat(
                new String[] {"--mdn-store-root", authored.toString(), "--output-dir",
                        directory.resolve("roots").toString()}, mdnArgs)));
        assertFalse(Files.exists(directory.resolve("roots")));
        HtmlKnowledgeIngestionRunner.Options.parse(concat(new String[] {"--store-root", authored.toString(),
                "--mdn-store-root", directory.resolve("roots/mdn").toString()}, mdnArgs));
    }

    @Test
    void anOccupiedExternalRootFailsBeforeTheAuthoredCorpusIsImported() throws IOException {
        var snapshot = Files.writeString(directory.resolve("index.md"), MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT);
        var authored = directory.resolve("authored");
        var occupied = Files.createDirectories(directory.resolve("occupied"));
        Files.writeString(occupied.resolve("notes.txt"), "unrelated");
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", authored.toString(), "--mdn-store-root", occupied.toString(),
                "--mdn-snapshot", snapshot.toString(), "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                "--mdn-sha256", MdnSnapshotSourceTest.sha256(snapshot)});

        var failure = assertThrows(IllegalStateException.class, () -> HtmlKnowledgeIngestionRunner.run(options));

        assertTrue(failure.getMessage().contains("duplicate import refused"), failure.getMessage());
        assertFalse(Files.exists(authored));
    }

    @Test
    void symlinkedAliasesOfAStoreRootAreRejectedAsOverlapping() throws IOException {
        var real = Files.createDirectories(directory.resolve("real"));
        var alias = directory.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not supported here: " + e);
        }
        var mdnArgs = new String[] {"--mdn-snapshot", "index.md", "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                "--mdn-sha256", "0".repeat(64)};

        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeIngestionRunner.Options.parse(concat(
                new String[] {"--store-root", real.resolve("store").toString(),
                        "--mdn-store-root", alias.resolve("store").toString()}, mdnArgs)));
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", real.resolve("store").toString(),
                "--output-dir", alias.resolve("store/reports").toString()}));
        assertFalse(Files.exists(real.resolve("store")));
    }

    @Test
    void anUnwritableOutputLocationFailsBeforeAnyStoreIsWritten() throws IOException {
        var root = directory.resolve("kept");
        var file = Files.writeString(directory.resolve("not-a-directory"), "x");
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", root.toString(), "--output-dir", file.toString()});

        assertThrows(IllegalStateException.class, () -> HtmlKnowledgeIngestionRunner.run(options));
        assertFalse(Files.exists(root));

        var output = Files.createDirectories(directory.resolve("reports/html-knowledge-ingestion.json"));
        var blocked = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", root.toString(), "--output-dir", output.getParent().toString()});
        assertThrows(IllegalStateException.class, () -> HtmlKnowledgeIngestionRunner.run(blocked));
        assertFalse(Files.exists(root));
    }

    @Test
    void aSymlinkedOutputFileIsRejectedBeforeAnyStoreIsWritten() throws IOException {
        var root = directory.resolve("kept");
        var output = Files.createDirectories(directory.resolve("reports"));
        try {
            Files.createSymbolicLink(output.resolve("html-kc-v1-manifest.tsv"), root.resolve("manifest.json"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not supported here: " + e);
        }
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", root.toString(), "--output-dir", output.toString()});

        var failure = assertThrows(IllegalStateException.class, () -> HtmlKnowledgeIngestionRunner.run(options));

        assertTrue(failure.getMessage().contains("symbolic link"), failure.getMessage());
        assertFalse(Files.exists(root));
    }

    @Test
    void aSnapshotThatAliasesAnOutputFileIsRejectedBeforeCleanup() throws IOException {
        var reports = Files.createDirectories(directory.resolve("reports"));
        var kept = directory.resolve("kept");
        var snapshot = Files.writeString(reports.resolve("html-knowledge-ingestion.md"),
                MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT);
        var manifest = Files.writeString(reports.resolve("html-kc-v1-manifest.tsv"),
                MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT);
        var aliases = new ArrayList<Path>(
                List.of(snapshot, directory.resolve("reports/../reports/html-kc-v1-manifest.tsv")));
        try {
            aliases.add(Files.createSymbolicLink(directory.resolve("alias"), reports).resolve(snapshot.getFileName()));
            aliases.add(Files.createSymbolicLink(directory.resolve("snap.md"), snapshot));
        } catch (UnsupportedOperationException | IOException e) {
            // Symbolic links are not supported here: the direct and normalized aliases still run.
        }

        for (var alias : aliases) {
            var sha256 = MdnSnapshotSourceTest.sha256(alias);
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                            "--output-dir", reports.toString(), "--store-root", kept.toString(),
                            "--mdn-snapshot", alias.toString(), "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                            "--mdn-sha256", sha256}), alias.toString());
            assertTrue(failure.getMessage().contains("must not be one of the output files"), failure.getMessage());
        }
        assertEquals(MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT, Files.readString(snapshot));
        assertEquals(MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT, Files.readString(manifest));
        assertFalse(Files.exists(kept));
    }

    @Test
    void aSnapshotInsideTheOutputDirectoryUnderAnotherNameIsIngestedAndKept() throws IOException {
        var reports = Files.createDirectories(directory.resolve("reports"));
        var snapshot = Files.writeString(reports.resolve("index.md"), MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT);

        var outcome = HtmlKnowledgeIngestionRunner.run(HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--output-dir", reports.toString(), "--mdn-snapshot", snapshot.toString(),
                "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                "--mdn-sha256", MdnSnapshotSourceTest.sha256(snapshot)}));
        HtmlKnowledgeIngestionRunner.write(outcome);

        assertTrue(outcome.report().allPassed(), outcome.report().toMarkdown());
        assertTrue(outcome.external().isPresent());
        assertEquals(MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT, Files.readString(snapshot));
    }

    @Test
    void missingRequestedSnapshotFailsBeforeAnyStoreIsWritten() {
        var root = directory.resolve("kept");
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--store-root", root.toString(),
                "--mdn-snapshot", directory.resolve("absent.md").toString(),
                "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                "--mdn-sha256", "0".repeat(64)});

        assertThrows(CorpusValidationException.class, () -> HtmlKnowledgeIngestionRunner.run(options));
        assertFalse(Files.exists(root));
    }

    @Test
    void rejectsIncompleteOrUnknownOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeIngestionRunner.Options.parse(new String[] {"--mdn-snapshot", "index.md"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeIngestionRunner.Options.parse(new String[] {"--quick"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeIngestionRunner.Options.parse(new String[] {"--store-root"}));
    }

    private static String[] concat(String[] first, String[] second) {
        return Stream.concat(Stream.of(first), Stream.of(second)).toArray(String[]::new);
    }
}
