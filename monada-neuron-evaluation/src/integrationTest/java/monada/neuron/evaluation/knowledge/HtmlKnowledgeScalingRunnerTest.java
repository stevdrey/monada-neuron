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
        var phases = Set.of("setup.corpus", "ingest.remember", "open.b1", "open.b2", "firstRecall.b1",
                "firstRecall.b2", "warm.b1", "warm.b2c", "warm.b2", "warm.cycle", "warm.qa.b3", "warm.qa.b1+b3");
        assertEquals(phases, rows.stream().map(row -> row.benchmarkName().substring("scaling.".length()))
                .collect(Collectors.toSet()));
        // Per point: 6 unparameterized rows and 6 rows at each of the 3 values of k.
        assertEquals(2 * (6 + 6 * 3), rows.size());
        for (var row : rows) {
            for (var field : List.of("size", "warmupIterations", "measurementIterations", "operationsPerIteration",
                    "measurementMethod", "validationInWindow", "gcTelemetryScope", "timed", "validation")) {
                assertTrue(row.diagnostics().containsKey(field), row.benchmarkName() + " " + field);
            }
            assertEquals("false", row.diagnostics().get("validationInWindow"));
            assertTrue(row.latency().medianNanos() > 0, row.benchmarkName());
        }
        // Every timed warm row runs the same fixed curve cohort at every size.
        rows.stream().filter(row -> row.benchmarkName().startsWith("scaling.warm."))
                .forEach(row -> assertEquals("27", row.diagnostics().get("operationsPerIteration"),
                        row.benchmarkName() + " " + row.workloadScale()));
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
        assertTrue(metadata.get("reopenMode").startsWith("fresh-handle"));
        assertEquals(TokenPackingSignalCodec.describe(), metadata.get("codec"));
        for (var key : List.of("codec.scope", "query.tuning", "answer.keyClaimCounts", "l3.traceKey",
                "l3.memoryStageLimit")) {
            assertTrue(metadata.containsKey(key), key);
        }
        assertTrue(metadata.get("n25.perf.open.processCold").startsWith("unavailable"));
        assertTrue(metadata.get("footprint.heapRetainedBytes").startsWith("unavailable"));
        assertEquals("q:code-example-02, q:exact-term-03, q:exact-term-04, q:exact-term-05, q:false-premise-01",
                metadata.get("n25.queries.removed"));

        var json = outcome.json();
        assertTrue(json.contains("\"scaling\": {"));
        assertTrue(json.contains("\"heapRetainedBytes\": null"));
        assertTrue(json.contains("\"b2MinusB2cMedianNanosPerQuery\": "));
        assertTrue(json.contains("\"queries\": [\n        { \"queryId\": "));
        assertTrue(json.contains("\"missDiagnostics\": "));
        assertTrue(json.contains("\"tieAtCutoff\": "));
        assertTrue(json.contains("\"answerRows\": [\n        { \"queryId\": "));
        assertTrue(json.contains("\"exactQuote\": "));
        assertTrue(json.contains("\"flags\": { \"anyUnsupportedClaim\": null"));
        assertTrue(json.contains("\"source\": \"unjudged\""));
        assertTrue(json.contains("\"matcherMatching\": null, \"finalMatching\": null, \"reviewerVerdicts\": null,"
                + " \"reviewerOverrides\": null"));
        assertTrue(json.contains("\"byCategory\": { \"exact-term\": { \"nA\": "));
        assertTrue(json.contains("\"traces\": [ { \"k\": 1, \"inputSignals\": "));
        assertTrue(json.contains("\"decision\": \"SUPPORTED\""));
        assertTrue(metadata.get("n100.perf.workload").startsWith("27 curve queries"));
        assertTrue(json.contains("\"verdictSource\": \"" + HtmlKnowledgeScalingReport.UNJUDGED + "\""));
        assertFalse(json.contains("\"textSha256\""), "generated text is publishable, so claims carry their text");
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
                port -> empty, port -> port);
        var failed = degraded.report().checks().stream().filter(check -> !check.passed())
                .map(EvaluationCheck::name).toList();
        assertEquals(List.of("n25.perf.matchesVerified"), failed);
        assertFalse(degraded.report().allPassed());
        // Rows that timed the degraded port are withheld and named as invalidated; untouched rows remain.
        var names = degraded.report().evaluation().results().stream().map(row -> row.benchmarkName()).toList();
        assertFalse(names.contains("scaling.warm.b2"));
        assertFalse(names.contains("scaling.warm.cycle"));
        assertFalse(names.contains("scaling.firstRecall.b2"));
        assertTrue(names.contains("scaling.open.b2"), "the adapter open never goes through the timed port");
        assertTrue(names.contains("scaling.warm.b1"));
        var json = degraded.json();
        assertTrue(json.contains("\"warm.b2.k5\": \"invalidated (a timed sample differs from the verified result"));
        assertTrue(json.contains("\"b2MinusB2cMedianNanosPerQuery\": null"));
        // Unavailable rows render in sorted label order on every JVM.
        var unavailable = degraded.scaling().orElseThrow().toJson();
        var labels = List.of("\"firstRecall.b2\": ", "\"warm.b2.k1\": ", "\"warm.b2.k3\": ",
                "\"warm.b2.k5\": ", "\"warm.cycle.k1\": ");
        for (var i = 1; i < labels.size(); i++) {
            assertTrue(unavailable.indexOf(labels.get(i - 1)) < unavailable.indexOf(labels.get(i)), labels.get(i));
        }
        // An invalidated row stays aligned with its row name and k in the Markdown table.
        var markdown = degraded.markdown();
        assertTrue(markdown.contains("| `warm.b2` | 5 | invalidated |"), markdown);
        assertFalse(markdown.contains("`warm.b2.k5`"));
    }

    @Test
    void anInvalidVerifiedCycleWithholdsItsTimingRow(@TempDir Path invalidDirectory) {
        // The verified cycles see an empty port, so their admitted results differ from B2: trace integrity fails.
        ResonanceMemoryPort empty = request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                request.maxResults(), List.of());
        var invalid = HtmlKnowledgeScalingRunner.run(HtmlKnowledgeScalingRunner.Options.parse(new String[] {
                "--quick", "--sizes", "25", "--output-dir", invalidDirectory.resolve("out").toString()}),
                port -> port, port -> empty);
        var failed = invalid.report().checks().stream().filter(check -> !check.passed())
                .map(EvaluationCheck::name).toList();
        assertEquals(List.of("n25.cycle.traceIntegrity", "n25.perf.matchesVerified"), failed);
        var names = invalid.report().evaluation().results().stream().map(row -> row.benchmarkName()).toList();
        assertFalse(names.contains("scaling.warm.cycle"), "a cycle row timed against an invalid verified run");
        assertTrue(names.contains("scaling.warm.b2"));
        assertTrue(invalid.json().contains("\"warm.cycle.k5\": \"invalidated (no verified result to time against: a"
                + " verified cycle violated trace integrity"));
    }

    @Test
    void unavailableRowLabelsSplitTheirK() {
        assertEquals("warm.b2|5", HtmlKnowledgeScalingReport.rowKey("warm.b2.k5"));
        assertEquals("warm.qa.b1+b3|3", HtmlKnowledgeScalingReport.rowKey("warm.qa.b1+b3.k3"));
        assertEquals("firstRecall.b2|-", HtmlKnowledgeScalingReport.rowKey("firstRecall.b2"));
    }

    @Test
    void invalidOptionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--sizes", "25,50"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--sizes", "100,25"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--sizes", "4294967321"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--seed", "x"}));
        assertThrows(IllegalArgumentException.class,
                () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {"--bogus"}));
        assertThrows(IllegalArgumentException.class, () -> HtmlKnowledgeScalingRunner.Options.parse(new String[] {
                "--output-dir", directory.resolve("x").toString(), "--store-root",
                directory.resolve("x/y").toString()}));
    }
}
