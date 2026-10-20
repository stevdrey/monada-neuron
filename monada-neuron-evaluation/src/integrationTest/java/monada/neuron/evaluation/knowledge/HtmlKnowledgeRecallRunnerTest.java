package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the full two-JVM recall experiment once; every test reads that run. */
class HtmlKnowledgeRecallRunnerTest {

    private static final Set<String> REQUIRED = Set.of(
            "seed.ingestion.manifestComplete",
            "seed.ingestion.contentFidelity",
            "seed.ingestion.readback",
            "seed.recall.completed",
            "seed.fidelity.source",
            "seed.fidelity.reference",
            "seed.neuron.b2EqualsB2c",
            "seed.recall.prefixStability",
            "reopen.persistence.readback",
            "reopen.persistence.noUnmappedAtoms",
            "reopen.recall.completed",
            "reopen.fidelity.source",
            "reopen.fidelity.reference",
            "reopen.neuron.b2EqualsB2c",
            "reopen.recall.prefixStability",
            "replica.ingestion.manifestComplete",
            "replica.ingestion.contentFidelity",
            "replica.ingestion.readback",
            "replica.recall.completed",
            "replica.fidelity.source",
            "replica.fidelity.reference",
            "replica.neuron.b2EqualsB2c",
            "replica.recall.prefixStability",
            "process.restart",
            "determinism.restartEqual",
            "determinism.freshRootEqual");

    @TempDir
    static Path directory;

    private static HtmlKnowledgeRecallRunner.Outcome outcome;
    private static List<Path> written;

    @BeforeAll
    static void runOnce() throws IOException {
        outcome = HtmlKnowledgeRecallRunner.run(HtmlKnowledgePersistenceRunner.Options.parse(
                new String[] {"--output-dir", directory.resolve("out").toString()}));
        written = HtmlKnowledgeRecallRunner.write(outcome);
    }

    @Test
    void everyRequiredCheckIsPresentAndPasses() {
        var names = outcome.report().checks().stream().map(EvaluationCheck::name).collect(Collectors.toSet());
        assertEquals(REQUIRED, names);
        assertTrue(outcome.report().allPassed(), outcome.markdown());
    }

    @Test
    void seedAndReopenRanInSeparateProcesses() {
        var reopen = outcome.reopen().orElseThrow();
        var replica = outcome.replica().orElseThrow();
        assertEquals(3, Set.of(outcome.seed().pid(), reopen.pid(), replica.pid()).size());
        assertEquals(0, replica.exitStatus());
        assertNotEquals(outcome.seed().pid(), reopen.pid());
        assertNotEquals(ProcessHandle.current().pid(), reopen.pid());
        assertEquals(0, outcome.seed().exitStatus());
        assertEquals(0, reopen.exitStatus());
    }

    @Test
    void reportRecordsTheRequiredFields() {
        var metadata = outcome.report().metadata();
        for (var key : List.of("protocolVersion", "corpus.version", "corpus.hash", "query.version", "query.hash",
                "query.tuning", "seed", "k", "threshold", "codec", "reopenMode", "store.checkoutCommit",
                "neuron.commit", "seed.jvm.version", "runMode", "env.cpuModel", "env.totalPhysicalMemoryBytes",
                "env.hostId", "env.storeRoot.fileSystem", "env.storeRoot.storageType")) {
            assertTrue(metadata.containsKey(key), key);
        }
        var querySet = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        assertEquals(querySet.queryHash(), metadata.get("query.hash"));
        assertTrue(metadata.get("codec").startsWith("token-packing v1"), metadata.get("codec"));
    }

    @Test
    void outputsCarryEveryQueryAndUndefinedValuesAsNull() throws IOException {
        var out = directory.resolve("out");
        assertEquals(Set.of("html-knowledge-recall.json", "html-knowledge-recall.md", "html-kq-v1-recall.tsv"),
                written.stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        var results = Files.readString(out.resolve("html-knowledge-recall.json"));
        assertTrue(results.contains("\"allChecksPassed\": true"), "verdict and scores share one object");
        assertTrue(results.contains("\"recall\": {"), "scored payload embedded in the report");
        assertTrue(results.contains("\"byCategory\": {"), "metric aggregates per category");
        assertTrue(Files.readString(out.resolve("html-kq-v1-recall.tsv")).startsWith("# raw seed-phase rows"));
        for (var query : HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled()).queries()) {
            assertTrue(results.contains("\"queryId\": \"" + query.queryId() + "\""), query.queryId());
        }
        assertTrue(results.contains("\"HitPrecision@1\": { \"value\": "), "aggregates carry nDefined/nUndefined");
        assertTrue(markdown(out).contains("### Quality at k = 5 by category"));
        assertTrue(!results.contains("NaN"), "undefined values are null, never NaN");
        var markdown = markdown(out);
        assertTrue(markdown.contains("## Judged Recall (`html-kq-v1`)"), markdown);
        assertTrue(markdown.contains("### Follow-up candidates"), markdown);
    }

    @Test
    void timingsAreWarmAndLabeledAsSuch() {
        var sampling = outcome.report().metadata().get("seed.perf.sampling");
        assertTrue(sampling.startsWith("warm: one unmeasured pass"), sampling);
        assertEquals("32", outcome.report().metadata().get("seed.perf.warmupQueries"));
        var metadata = outcome.report().metadata();
        var rows = new ArrayList<String>();
        for (var phase : List.of("seed", "reopen", "replica")) {
            for (var row : List.of("perf.open", "perf.b1.firstRecall", "perf.b2.firstRecall")) {
                rows.add(phase + "." + row);
            }
            assertTrue(Long.parseLong(metadata.get("process." + phase + ".wallNanos")) > 0, phase);
            assertFalse(metadata.containsKey("phase." + phase + ".nanos"), "process time is not a latency row");
        }
        for (var phase : List.of("seed", "replica")) {
            rows.add(phase + ".perf.ingest");
            rows.add(phase + ".perf.ingestReadback");
        }
        rows.add("reopen.perf.reopenReadback");
        for (var row : rows) {
            for (var field : List.of("medianNanos", "p95Nanos", "warmupIterations", "measurementIterations",
                    "operationsPerIteration", "measurementMethod", "validationInWindow", "gcTelemetryScope")) {
                assertTrue(metadata.containsKey(row + "." + field), row + "." + field);
            }
            assertEquals(Integer.toString(LatencyRow.REPETITIONS), metadata.get(row + ".measurementIterations"), row);
            assertTrue(Long.parseLong(metadata.get(row + ".p95Nanos")) >= Long.parseLong(metadata.get(row
                    + ".medianNanos")), row);
        }
        assertEquals("true", metadata.get("env.replicaRoot.sameFileStoreAsStoreRoot"));
        assertEquals("none", metadata.get("cleanup.leftover"));
        for (var phase : List.of("seed", "reopen", "replica")) {
            assertEquals("0", metadata.get(phase + ".perf.warmup.failures"), phase);
            assertEquals("0", metadata.get(phase + ".perf.b1.firstRecall.excludedSamples"), phase);
            assertEquals("0", metadata.get(phase + ".perf.b2.recallLimit5.excludedSamples"), phase);
        }
        assertEquals("0", metadata.get("reopen.perf.reopenReadback.excludedSamples"));
        assertTrue(outcome.report().checks().stream().noneMatch(check -> check.name().contains("auxiliary")
                || check.name().contains("firstRecall") || check.name().contains("timing")),
                "latency-only calls never produce a check");
        assertEquals("true", metadata.get("seed.env.timingScratch.sameFileStoreAsStoreRoot"));
        assertEquals("0", metadata.get("seed.perf.ingest.excludedSamples"));
        assertEquals("0", metadata.get("replica.perf.ingestReadback.excludedSamples"));
        assertEquals("true", metadata.get("replica.env.timingScratch.sameFileStoreAsStoreRoot"));
        assertTrue(outcome.report().evaluation().runConfiguration().toString().contains("median and p95"),
                "the run configuration describes the sampled rows");
        var configuration = outcome.report().evaluation().runConfiguration().operationsPerIterationPolicy();
        assertTrue(configuration.startsWith("phase JVMs launched: seed, reopen, replica;"), configuration);
        assertFalse(configuration.contains("not launched"), configuration);
        assertEquals("phase JVMs launched: seed; not launched: reopen, replica (an earlier phase did not complete or its"
                + " root could not be created)", HtmlKnowledgePersistenceRunner.launchedPhases(
                        List.of("seed", "reopen", "replica"), List.of(outcome.seed())));
        assertTrue(metadata.containsKey("env.replicaRoot.fileSystem"));
        assertTrue(metadata.containsKey("env.replicaRoot.storageType"));
        for (var phase : List.of("seed", "reopen", "replica")) {
            for (var row : List.of("perf.b1.recallLimit5", "perf.b2.recallLimit5")) {
                var prefix = phase + "." + row + ".";
                assertTrue(metadata.containsKey(prefix + "medianNanos") && metadata.containsKey(prefix + "p95Nanos"));
                assertEquals("1", metadata.get(prefix + "warmupIterations"), prefix);
                assertEquals("32", metadata.get(prefix + "measurementIterations"), prefix);
                assertEquals("1", metadata.get(prefix + "operationsPerIteration"), prefix);
                assertEquals("false", metadata.get(prefix + "validationInWindow"), prefix);
                assertTrue(metadata.get(prefix + "measurementMethod").startsWith("System.nanoTime"), prefix);
                assertEquals("none collected", metadata.get(prefix + "gcTelemetryScope"), prefix);
            }
        }
    }

    private static String markdown(Path out) throws IOException {
        return Files.readString(out.resolve("html-knowledge-recall.md"));
    }

    @Test
    void aKeptRootGetsItsReplicaAndScratchRootsBesideItAndNothingIsLeftBehind() throws IOException {
        var parent = Files.createDirectories(directory.resolve("kept-parent"));
        var kept = parent.resolve("store");
        var keptOutcome = HtmlKnowledgeRecallRunner.run(HtmlKnowledgePersistenceRunner.Options.parse(new String[] {
                "--output-dir", directory.resolve("kept-out").toString(), "--store-root", kept.toString()}));

        assertTrue(keptOutcome.report().allPassed(), keptOutcome.markdown());
        assertEquals("true", keptOutcome.report().metadata().get("env.replicaRoot.sameFileStoreAsStoreRoot"));
        try (var children = Files.list(parent)) {
            assertEquals(List.of(kept), children.toList(), "only the kept root remains beside it");
        }
    }

    @Test
    void aNonEmptyStoreRootIsRejected() throws IOException {
        var kept = Files.createDirectories(directory.resolve("kept"));
        Files.writeString(kept.resolve("occupied"), "x");
        assertThrows(IngestionInputException.class, () -> HtmlKnowledgeRecallRunner.run(
                HtmlKnowledgePersistenceRunner.Options.parse(new String[] {"--output-dir",
                        directory.resolve("other").toString(), "--store-root", kept.toString()})));
    }
}
