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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the full two-JVM grounded-answer experiment once with the bundled judgments; every test reads that run. */
class HtmlKnowledgeAnswerRunnerTest {

    private static final Set<String> REQUIRED = required();

    @TempDir
    static Path directory;

    private static HtmlKnowledgeAnswerRunner.Outcome outcome;
    private static List<Path> written;

    @BeforeAll
    static void runOnce() throws IOException {
        outcome = HtmlKnowledgeAnswerRunner.run(HtmlKnowledgePersistenceRunner.Options.parse(
                new String[] {"--output-dir", directory.resolve("out").toString()}));
        written = HtmlKnowledgeAnswerRunner.write(outcome);
    }

    @Test
    void everyRequiredCheckIsPresentAndPasses() {
        var names = outcome.report().checks().stream().map(EvaluationCheck::name).collect(Collectors.toSet());
        assertEquals(REQUIRED, names);
        assertTrue(outcome.report().allPassed(), outcome.markdown());
    }

    @Test
    void seedAndAnswerRanInSeparateProcesses() {
        var answer = outcome.answerPhase().orElseThrow();
        assertNotEquals(outcome.seed().pid(), answer.pid());
        assertNotEquals(ProcessHandle.current().pid(), answer.pid());
        assertEquals(0, outcome.seed().exitStatus());
        assertEquals(0, answer.exitStatus());
    }

    @Test
    void reportRecordsTheRequiredL4Fields() {
        var metadata = outcome.report().metadata();
        assertEquals("l4-answer-v1", metadata.get("extensions"));
        assertEquals(HtmlKnowledgeAnswerRunner.FROZEN_JUDGMENTS_HASH, metadata.get("judgments.hash"));
        assertTrue(metadata.get("evidenceLayers").startsWith("B1, B2"));
        assertEquals("1, 3, 5", metadata.get("answerK"));
        assertTrue(metadata.get("query.tuning").contains("in-sample (tuned)"));
        assertTrue(metadata.get("answer.component.B3c").contains("in-sample (tuned)"));
        assertTrue(metadata.get("answer.component.generator-test-double").startsWith("TEST_DOUBLE"));
        assertTrue(metadata.containsKey("answer.perf.B3.medianNanos"));
        var json = outcome.json();
        assertTrue(json.contains("\"answers\": {"), "scored payload present");
        assertTrue(json.contains("\"matcherMatching\""));
        assertTrue(json.contains("\"chunkSha256\""));
        assertTrue(json.contains("\"tieAtCutoff\": "));
        assertTrue(json.contains("\"evidence\": [ { \"rank\": 1, \"chunkId\": "));
        assertTrue(json.contains("\"relevant\": "));
        assertFalse(json.contains("NaN"));
    }

    @Test
    void outputsAreWrittenAndNothingIsLeftToJudge() throws IOException {
        var names = written.stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        assertEquals(Set.of("html-knowledge-answers.json", "html-knowledge-answers.md", "html-kq-v1-answers.tsv",
                HtmlKnowledgeAnswerRunner.TEMPLATE), names);
        var template = Files.readString(directory.resolve("out").resolve(HtmlKnowledgeAnswerRunner.TEMPLATE));
        assertEquals(2, template.lines().count(), "only the comment and the header: " + template);
    }

    private static Set<String> required() {
        var names = new LinkedHashSet<String>();
        for (var root : List.of("full", "removed")) {
            for (var check : List.of("ingestion.manifestComplete", "ingestion.contentFidelity", "ingestion.readback")) {
                names.add("seed." + root + "." + check);
            }
            names.add("answer." + root + ".persistence.readback");
            names.add("answer." + root + ".persistence.noUnmappedAtoms");
        }
        for (var check : List.of("completed", "citationsEligible", "deterministic", "control.removedNeverCited",
                "control.emptyEvidenceAbstains", "generator.labelledTestDouble")) {
            names.add("answer." + check);
        }
        names.addAll(List.of("process.restart", "answer.scored", "judgment.complete", "judgment.noStaleEntries",
                "judgment.hashFrozen"));
        return Set.copyOf(names);
    }
}
