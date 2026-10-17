package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlKnowledgeIngestionRunnerTest {

    @TempDir
    Path directory;

    @Test
    void defaultRunPassesReportsExternalAsUnavailableAndWritesOutputs() throws IOException {
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {});

        var outcome = HtmlKnowledgeIngestionRunner.run(options);
        var written = HtmlKnowledgeIngestionRunner.write(outcome, directory);

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
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {"--store-root", root.toString()});

        HtmlKnowledgeIngestionRunner.run(options);

        assertTrue(Files.exists(root.resolve("manifest.json")));
        assertThrows(IllegalStateException.class, () -> HtmlKnowledgeIngestionRunner.run(options));
    }

    @Test
    void suppliedSnapshotIsIngestedSeparatelyWithoutItsTextInAnyOutput() throws IOException {
        var snapshot = Files.writeString(directory.resolve("index.md"), MdnSnapshotSourceTest.SYNTHETIC_SNAPSHOT);
        var output = directory.resolve("reports");
        var options = HtmlKnowledgeIngestionRunner.Options.parse(new String[] {
                "--mdn-snapshot", snapshot.toString(),
                "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                "--mdn-sha256", MdnSnapshotSourceTest.sha256(snapshot)});

        var outcome = HtmlKnowledgeIngestionRunner.run(options);
        var written = HtmlKnowledgeIngestionRunner.write(outcome, output);

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
                new String[] {"--mdn-snapshot", snapshot.toString(), "--mdn-revision", MdnSnapshotSourceTest.REVISION,
                        "--mdn-sha256", MdnSnapshotSourceTest.sha256(snapshot)})), output);
        assertTrue(Files.exists(output.resolve("html-kc-ext-mdn-v1-manifest.tsv")));

        HtmlKnowledgeIngestionRunner.write(
                HtmlKnowledgeIngestionRunner.run(HtmlKnowledgeIngestionRunner.Options.parse(new String[] {})), output);

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
        assertFalse(Files.exists(directory.resolve("roots")));
        HtmlKnowledgeIngestionRunner.Options.parse(concat(new String[] {"--store-root", authored.toString(),
                "--mdn-store-root", directory.resolve("roots/mdn").toString()}, mdnArgs));
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
