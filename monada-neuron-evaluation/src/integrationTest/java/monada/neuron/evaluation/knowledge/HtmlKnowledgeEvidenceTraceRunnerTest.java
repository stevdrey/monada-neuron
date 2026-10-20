package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the full two-JVM evidence-trace experiment once; every test reads that run. */
class HtmlKnowledgeEvidenceTraceRunnerTest {

    private static final Set<String> REQUIRED = required();

    @TempDir
    static Path directory;

    private static HtmlKnowledgeEvidenceTraceRunner.Outcome outcome;
    private static List<Path> written;

    @BeforeAll
    static void runOnce() throws IOException {
        outcome = HtmlKnowledgeEvidenceTraceRunner.run(HtmlKnowledgePersistenceRunner.Options.parse(
                new String[] {"--output-dir", directory.resolve("out").toString()}));
        written = HtmlKnowledgeEvidenceTraceRunner.write(outcome);
    }

    @Test
    void everyRequiredCheckIsPresentAndPasses() {
        var names = outcome.report().checks().stream().map(EvaluationCheck::name).collect(Collectors.toSet());
        assertEquals(REQUIRED, names);
        assertTrue(outcome.report().allPassed(), outcome.markdown());
    }

    @Test
    void seedAndTraceRanInSeparateProcesses() {
        var trace = outcome.tracePhase().orElseThrow();
        assertNotEquals(outcome.seed().pid(), trace.pid());
        assertNotEquals(ProcessHandle.current().pid(), trace.pid());
        assertEquals(0, outcome.seed().exitStatus());
        assertEquals(0, trace.exitStatus());
    }

    @Test
    void reportRecordsTheRequiredFields() {
        var metadata = outcome.report().metadata();
        for (var key : List.of("protocolVersion", "extensions", "corpus.version", "corpus.hash",
                "corpus.removed.hash", "query.version", "query.hash", "query.tuning", "seed", "k", "threshold",
                "codec", "reopenMode", "store.checkoutCommit", "neuron.commit", "trace.jvm.version",
                "trace.trace.key", "trace.cycle.memoryLimit", "trace.cycle.budget", "trace.cycle.stages",
                "trace.control.removedDocument", "runMode", "env.cpuModel", "env.hostId",
                "env.storeRoot.fileSystem")) {
            assertTrue(metadata.containsKey(key), key);
        }
        assertEquals(HtmlKnowledgeEvidenceTraceRunner.EXTENSION, metadata.get("extensions"));
    }

    @Test
    void outputsCarryTheLimitsEveryQueryAndEveryArm() throws IOException {
        var out = directory.resolve("out");
        assertEquals(Set.of("html-knowledge-evidence-trace.json", "html-knowledge-evidence-trace.md",
                "html-kq-v1-evidence-trace.tsv"),
                written.stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        var json = Files.readString(out.resolve("html-knowledge-evidence-trace.json"));
        assertTrue(json.contains("\"allChecksPassed\": true"));
        assertTrue(json.contains("\"trace\": { \"limits\": ["), "the limits travel with the scored payload");
        assertTrue(json.contains("not a language-generation result"));
        assertTrue(json.contains("nothing here trains model weights"));
        for (var arm : EvidenceTrace.Arm.values()) {
            assertTrue(json.contains("\"" + arm.name() + "\": { \"AdmittedFraction\""), arm.name());
        }
        for (var query : HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled()).queries()) {
            assertTrue(json.contains("\"queryId\": \"" + query.queryId() + "\""), query.queryId());
        }
        assertFalse(json.contains("NaN"), "undefined values are null, never NaN");
        var markdown = Files.readString(out.resolve("html-knowledge-evidence-trace.md"));
        assertTrue(markdown.contains("## Evidence Trace (L3, l3-evidence-v1)"), markdown);
        assertTrue(markdown.contains("### Limits"), markdown);
        assertTrue(Files.readString(out.resolve("html-kq-v1-evidence-trace.tsv")).startsWith("# raw trace-phase rows"));
    }

    @Test
    void aNonEmptyStoreRootIsRefused() throws IOException {
        var kept = directory.resolve("kept");
        Files.createDirectories(kept);
        Files.writeString(kept.resolve("occupied"), "x");
        assertThrows(IngestionInputException.class, () -> HtmlKnowledgeEvidenceTraceRunner.run(
                HtmlKnowledgePersistenceRunner.Options.parse(new String[] {"--output-dir",
                        directory.resolve("refused").toString(), "--store-root", kept.toString()})));
    }

    private static Set<String> required() {
        var names = new LinkedHashSet<String>();
        for (var root : List.of("full", "removed")) {
            for (var check : List.of("ingestion.manifestComplete", "ingestion.contentFidelity", "ingestion.readback")) {
                names.add("seed." + root + "." + check);
            }
            names.add("trace." + root + ".persistence.readback");
            names.add("trace." + root + ".persistence.noUnmappedAtoms");
        }
        for (var check : List.of("evidence.rows", "evidence.recallCompleted", "evidence.requestMatchesCodec",
                "evidence.recalledEqualsB2", "evidence.traceIntegrity", "evidence.referencesResolved",
                "evidence.stageList", "evidence.termination", "evidence.deterministicReplay",
                "contrast.valueEquality", "control.memoryEmptyUnsupported", "control.removedExcludesDocument",
                "control.tightBudgetExhausts", "control.unavailableRecall", "control.failurePropagates")) {
            names.add("trace." + check);
        }
        names.add("process.restart");
        names.add("control.memoryOffNotSupported");
        names.add("control.removedRelevantNotSupported");
        return Set.copyOf(names);
    }
}
