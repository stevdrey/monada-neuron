package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the quick profile once over N = 25 and 100; every test reads that run. */
class HtmlKnowledgeScalingRunnerTest {

    @TempDir
    static Path directory;

    private static HtmlKnowledgeScalingRunner.Outcome outcome;
    private static List<Path> written;

    @BeforeAll
    static void runOnce() throws IOException {
        outcome = HtmlKnowledgeScalingRunner.run(HtmlKnowledgeScalingRunner.Options.parse(new String[] {
                "--quick", "--sizes", "25,100", "--output-dir", directory.resolve("out").toString()}));
        written = HtmlKnowledgeScalingRunner.write(outcome);
    }

    @Test
    void everyPointPassesItsSemanticChecks() {
        var names = outcome.report().checks().stream().map(EvaluationCheck::name).collect(Collectors.toSet());
        for (var size : List.of(25, 100)) {
            for (var check : List.of("ingestion.readback", "store.freshHandleReadback", "neuron.b2EqualsB2c",
                    "recall.prefixStability", "cycle.traceIntegrity", "answer.citationsEligible",
                    "perf.matchesVerified")) {
                assertTrue(names.contains("n" + size + "." + check), "n" + size + "." + check);
            }
        }
        assertTrue(outcome.report().allPassed(), outcome.markdown());
    }

    @Test
    void everyPhaseIsARowWithItsSamplingAndCounts() {
        var rows = outcome.report().evaluation().results();
        var phases = Set.of("setup.corpus", "ingest.remember", "open.firstRecall.b1", "open.firstRecall.b2",
                "warm.b1", "warm.b2c", "warm.b2", "warm.cycle", "warm.qa.b3", "warm.qa.b1+b3");
        assertEquals(phases, rows.stream().map(row -> row.benchmarkName().substring("scaling.".length()))
                .collect(Collectors.toSet()));
        // Per point: 4 unparameterized rows and 6 rows at each of the 3 values of k.
        assertEquals(2 * (4 + 6 * 3), rows.size());
        for (var row : rows) {
            for (var field : List.of("size", "warmupIterations", "measurementIterations", "operationsPerIteration",
                    "measurementMethod", "validationInWindow", "gcTelemetryScope", "timed", "validation")) {
                assertTrue(row.diagnostics().containsKey(field), row.benchmarkName() + " " + field);
            }
            assertEquals("false", row.diagnostics().get("validationInWindow"));
            assertTrue(row.latency().medianNanos() > 0, row.benchmarkName());
        }
        var ingest = rows.stream().filter(row -> row.benchmarkName().equals("scaling.ingest.remember")
                && row.diagnostics().get("size").equals("100")).findFirst().orElseThrow();
        assertEquals("100", ingest.diagnostics().get("operationsPerIteration"));
    }

    @Test
    void reportCarriesProvenanceAndMarksMissingValuesUnavailable() throws IOException {
        var metadata = outcome.report().metadata();
        for (var key : List.of("protocolVersion", "extensions", "corpus.base.hash", "query.hash", "generator.version",
                "generator.seed", "n25.corpus.hash", "n100.corpus.hash", "neuron.commit", "store.checkoutCommit",
                "env.cpuModel", "env.hostId", "env.storeRoot.fileSystem", "n100.store.manifest.encoder")) {
            assertTrue(metadata.containsKey(key), key);
        }
        assertEquals("scaling-v1", metadata.get("extensions"));
        assertTrue(metadata.get("n25.perf.open.processCold").startsWith("unavailable"));
        assertTrue(metadata.get("footprint.heapRetainedBytes").startsWith("unavailable"));
        assertEquals("q:code-example-02, q:exact-term-03, q:exact-term-04, q:exact-term-05, q:false-premise-01",
                metadata.get("n25.queries.removed"));

        var json = outcome.json();
        assertTrue(json.contains("\"scaling\": {"));
        assertTrue(json.contains("\"heapRetainedBytes\": null"));
        assertTrue(json.contains("\"b2MinusB2cMedianNanosPerQuery\": "));
        assertFalse(json.contains("NaN"));
        assertTrue(outcome.markdown().contains("## Follow-up triage template"));
        assertEquals(Set.of("html-knowledge-scaling.json", "html-knowledge-scaling.md"),
                written.stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        assertEquals(json, Files.readString(written.getFirst()));
    }

    @Test
    void timingADegradedPathFailsTheRun(@TempDir Path degradedDirectory) {
        ResonanceMemoryPort empty = request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                request.maxResults(), List.of());
        var degraded = HtmlKnowledgeScalingRunner.run(HtmlKnowledgeScalingRunner.Options.parse(new String[] {
                "--quick", "--sizes", "25", "--output-dir", degradedDirectory.resolve("out").toString()}),
                port -> empty);
        var failed = degraded.report().checks().stream().filter(check -> !check.passed())
                .map(EvaluationCheck::name).toList();
        assertEquals(List.of("n25.perf.matchesVerified"), failed);
        assertFalse(degraded.report().allPassed());
    }

    @Test
    void invalidOptionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--sizes", "25,50"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--sizes", "100,25"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--seed", "x"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--bogus"}));
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {
                "--output-dir", directory.resolve("x").toString(), "--store-root",
                directory.resolve("x/y").toString()}));
    }
}
