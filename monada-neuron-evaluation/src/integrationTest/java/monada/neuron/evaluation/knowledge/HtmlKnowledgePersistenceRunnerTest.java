package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the full two-JVM experiment once; every test reads that run. */
class HtmlKnowledgePersistenceRunnerTest {

    private static final Set<String> REQUIRED = Set.of(
            "seed.ingestion.readback",
            "seed.persistence.atomCount",
            "seed.neuron.signalAttribution",
            "reopen.persistence.atomCount",
            "reopen.persistence.encodingProfile",
            "reopen.persistence.readback",
            "reopen.persistence.noUnmappedAtoms",
            "reopen.neuron.recallComplete",
            "reopen.neuron.referenceAttribution",
            "reopen.neuron.signalAttribution",
            "reopen.neuron.directVsAdapterEqual",
            "reopen.persistence.restartProfileEqual",
            "reopen.persistence.restartProbeSetEqual",
            "reopen.persistence.restartRecallEqual",
            "reopen.neuron.restartEqual",
            "reopen.persistence.freshHandleConsistency",
            "process.restart",
            "neuron.defaultCodecInsufficient",
            "audit.missingPath",
            "audit.pathIsFile",
            "audit.changedEncodingProfile",
            "audit.removedVectorSegment",
            "audit.duplicateRemember",
            "audit.closedAdapter",
            "audit.alteredAtom",
            "audit.removedAtom");

    @TempDir
    static Path directory;

    private static HtmlKnowledgePersistenceRunner.Outcome outcome;
    private static List<Path> written;

    @BeforeAll
    static void runOnce() throws IOException {
        outcome = HtmlKnowledgePersistenceRunner.run(
                HtmlKnowledgePersistenceRunner.Options.parse(new String[] {"--output-dir", directory.toString()}));
        written = HtmlKnowledgePersistenceRunner.write(outcome);
    }

    @Test
    void everySemanticCheckPasses() {
        var failed = outcome.report().checks().stream().filter(check -> !check.passed())
                .map(check -> check.name() + ": " + check.detail()).toList();
        assertEquals(List.of(), failed);
    }

    @Test
    void coversEveryRequiredScenario() {
        var names = outcome.report().checks().stream().map(check -> check.name()).collect(Collectors.toSet());
        var missing = REQUIRED.stream().filter(name -> !names.contains(name)).sorted().toList();
        assertEquals(List.of(), missing);
    }

    @Test
    void seedAndReopenRanInSeparateJvmsThatExitedCleanly() {
        var reopen = outcome.reopen().orElseThrow();
        var self = ProcessHandle.current().pid();
        assertNotEquals(outcome.seed().pid(), reopen.pid());
        assertNotEquals(self, outcome.seed().pid());
        assertNotEquals(self, reopen.pid());
        assertEquals(0, outcome.seed().exitStatus());
        assertEquals(0, reopen.exitStatus());
        var metadata = outcome.report().metadata();
        assertEquals(Long.toString(outcome.seed().pid()), metadata.get("seed.pid"));
        assertEquals(Long.toString(reopen.pid()), metadata.get("reopen.pid"));
    }

    @Test
    void reportsReopenModeCodecProfileAndVersions() {
        Map<String, String> metadata = outcome.report().metadata();
        assertTrue(metadata.get("reopenMode").startsWith("restart"));
        assertTrue(metadata.get("codec").startsWith(ChunkTextProbeCodec.NAME + " v" + ChunkTextProbeCodec.VERSION));
        assertEquals("5", metadata.get("probes.topK"));
        assertEquals(PersistenceProbes.VERSION, metadata.get("probes.version"));
        assertEquals(new HtmlKnowledgePersistence(HtmlKnowledgeCorpus.bundled()).probeHash(),
                metadata.get("probes.hash"));
        for (var key : List.of("store.manifest.version", "store.manifest.encoder", "store.manifest.normalizer",
                "store.manifest.dimensions", "store.manifest.vectorScalarType", "store.implementationVersion",
                "store.checkoutCommit", "neuron.commit", "neuron.version", "phase.seed.pid", "phase.reopen.pid",
                "phase.forwardedJvmArguments", "seed.jvm.version", "seed.jvm.arguments", "seed.jvm.maxMemoryBytes",
                "reopen.jvm.version", "reopen.jvm.arguments", "reopen.jvm.maxMemoryBytes")) {
            assertTrue(metadata.containsKey(key), key);
        }
        assertEquals("temporary (deleted)", metadata.get("store.root"));
    }

    @Test
    void writesReportManifestAndBothSnapshots() throws IOException {
        assertEquals(5, written.size());
        var seed = Files.readString(directory.resolve("html-kc-v1-seed-recall.tsv"));
        var reopen = Files.readString(directory.resolve("html-kc-v1-reopen-recall.tsv"));
        assertEquals(seed, reopen);
        assertTrue(seed.contains(PersistenceProbes.SNAPSHOT_HEADER));
        assertTrue(Files.readString(directory.resolve("html-kc-v1-persistence-manifest.tsv"))
                .contains(IngestionManifest.HEADER));
        assertTrue(Files.readString(directory.resolve("html-knowledge-persistence.json"))
                .contains("\"reopenMode\""));
    }

    @Test
    void aSecondRunReachesTheSameVerdictsAndDetails(@TempDir Path other) {
        var again = HtmlKnowledgePersistenceRunner.run(
                HtmlKnowledgePersistenceRunner.Options.parse(new String[] {"--output-dir", other.toString()}));

        // Every detail must be run-independent except process.restart, which names the process ids.
        assertEquals(comparable(outcome.report().checks()), comparable(again.report().checks()));
        // Snapshots of independent roots agree because they map atoms through each root's own manifest.
        assertEquals(outcome.files().get("html-kc-v1-reopen-recall.tsv"), again.files().get("html-kc-v1-reopen-recall.tsv"));
    }

    private static List<String> comparable(List<? extends EvaluationCheck> checks) {
        return checks.stream().map(check -> check.name() + "=" + check.passed()
                + (check.name().equals("process.restart") ? "" : ": " + check.detail())).toList();
    }

    @Test
    void refusesAKeptStoreRootThatAlreadyHoldsFiles(@TempDir Path other) throws IOException {
        var root = Files.createDirectories(other.resolve("kept"));
        Files.writeString(root.resolve("notes.txt"), "unrelated");
        var options = HtmlKnowledgePersistenceRunner.Options.parse(new String[] {
                "--store-root", root.toString(), "--output-dir", other.resolve("reports").toString()});

        assertThrows(IngestionInputException.class, () -> HtmlKnowledgePersistenceRunner.run(options));
    }

    @Test
    void reopensOnlyAfterTheSeedLeftAResultManifestAndSnapshot(@TempDir Path other) throws IOException {
        var runner = new HtmlKnowledgePersistenceRunner(
                HtmlKnowledgePersistenceRunner.Options.parse(new String[] {"--output-dir", other.toString()}));
        var work = new HtmlKnowledgePersistencePhase.WorkDirectory(Files.createDirectories(other.resolve("work")));
        var seed = HtmlKnowledgePersistencePhase.SEED;

        // A crashed seed JVM exits 1 like a semantic failure but leaves no artifacts.
        assertTrue(runner.seedArtifacts(work).isEmpty());
        Files.writeString(work.result(seed), "");
        Files.writeString(work.manifest(), "not a manifest");
        Files.writeString(work.snapshot(seed), outcome.files().get("html-kc-v1-seed-recall.tsv"));
        assertTrue(runner.seedArtifacts(work).isEmpty());
        Files.writeString(work.manifest(), outcome.files().get("html-kc-v1-persistence-manifest.tsv"));
        Files.writeString(work.snapshot(seed), "truncated");
        assertTrue(runner.seedArtifacts(work).isEmpty());
        Files.writeString(work.snapshot(seed), outcome.files().get("html-kc-v1-seed-recall.tsv"));
        assertTrue(runner.seedArtifacts(work).isPresent());
    }

    @Test
    void rejectsAnOutputDirectoryNestedInTheStoreRoot(@TempDir Path other) {
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgePersistenceRunner.Options.parse(new String[] {
                "--store-root", other.toString(), "--output-dir", other.resolve("reports").toString()}));
    }
}
