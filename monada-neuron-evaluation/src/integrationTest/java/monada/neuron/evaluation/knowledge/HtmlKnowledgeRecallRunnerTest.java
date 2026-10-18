package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.metrics.EvaluationCheck;
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
            "reopen.recall.completed",
            "reopen.fidelity.source",
            "reopen.fidelity.reference",
            "reopen.neuron.b2EqualsB2c",
            "reopen.recall.prefixStability",
            "process.restart",
            "determinism.restartEqual");

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
                "neuron.commit", "seed.jvm.version")) {
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
    }

    private static String markdown(Path out) throws IOException {
        return Files.readString(out.resolve("html-knowledge-recall.md"));
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
