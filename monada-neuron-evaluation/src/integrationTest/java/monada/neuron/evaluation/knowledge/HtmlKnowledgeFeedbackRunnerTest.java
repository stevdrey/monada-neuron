package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the full three-JVM relevance-feedback experiment once; every test reads that run. */
class HtmlKnowledgeFeedbackRunnerTest {

    @TempDir
    static Path directory;

    private static HtmlKnowledgeFeedbackRunner.Outcome outcome;
    private static List<Path> written;

    @BeforeAll
    static void runOnce() throws IOException {
        outcome = HtmlKnowledgeFeedbackRunner.run(HtmlKnowledgePersistenceRunner.Options.parse(
                new String[] {"--output-dir", directory.resolve("out").toString()}));
        written = HtmlKnowledgeFeedbackRunner.write(outcome);
    }

    @Test
    void everyRequiredCheckIsPresentAndPasses() {
        var names = outcome.report().checks().stream().map(EvaluationCheck::name).collect(Collectors.toSet());
        assertEquals(required(), names);
        assertTrue(outcome.report().allPassed(), outcome.markdown());
    }

    @Test
    void phasesRanInSeparateProcesses() {
        assertEquals(List.of("seed", "feedback", "reopen"), outcome.phases().stream()
                .map(HtmlKnowledgePersistenceRunner.PhaseRun::phase).toList());
        var pids = new HashSet<Long>();
        outcome.phases().forEach(run -> pids.add(run.pid()));
        pids.add(ProcessHandle.current().pid());
        assertEquals(4, pids.size());
        outcome.phases().forEach(run -> assertEquals(0, run.exitStatus(), run.stderr()));
    }

    @Test
    void heldOutQueriesShowNoEffectAndInSampleQueriesChange() {
        var markdown = outcome.markdown();
        for (var arm : FeedbackArm.treatments()) {
            assertTrue(markdown.contains("- " + arm.label() + ", direct Store (B1): no effect"), markdown);
            assertTrue(markdown.contains("- " + arm.label() + ", Neuron adapter (B2): no effect"), markdown);
        }
        assertTrue(markdown.contains("### Direct Store (B1)"));
        assertTrue(markdown.contains("### Neuron adapter (B2)"));
        assertTrue(markdown.contains("| treatment-exact | calibration-with-events | in-sample (tuned) |"));
        var json = outcome.json();
        assertTrue(json.contains("\"extension\": \"feedback-v1\""));
        assertTrue(json.contains("\"feedbackHash\": \"" + outcome.report().metadata().get("feedback.hash") + "\""));
        assertTrue(json.contains("\"splitHash\": "));
        assertTrue(json.contains("\"variantHash\": "));
        assertTrue(json.contains("not Forge worker-route adaptation"));
    }

    @Test
    void outputsAreWrittenAndHoldNoStoreAtomId() throws IOException {
        var names = written.stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        assertEquals(Set.of("html-knowledge-feedback.json", "html-knowledge-feedback.md",
                "html-kq-v1-feedback-rows.tsv", "html-kq-v1-feedback-events.tsv", "html-kq-v1-feedback-split.tsv"),
                names);
        // Atom ids are derived from content, so a fresh import yields the ids every arm root used.
        var manifest = new HtmlKnowledgeIngestion(HtmlKnowledgeCorpus.bundled()).ingest(directory.resolve("ids"))
                .manifest();
        for (var path : written) {
            var content = Files.readString(path);
            for (var entry : manifest.entries()) {
                assertFalse(content.contains(entry.atomId()), path + " contains the atom id of " + entry.chunkId());
            }
        }
        var events = Files.readString(directory.resolve("out").resolve("html-kq-v1-feedback-events.tsv"));
        assertTrue(events.lines().anyMatch(line -> line.startsWith("treatment-normalized\t")
                && line.split("\t")[2].startsWith("normalized:")));
    }

    @Test
    void aNonEmptyKeptRootIsRejected() throws IOException {
        var kept = directory.resolve("kept");
        Files.createDirectories(kept.resolve("something"));
        assertThrows(IngestionInputException.class, () -> HtmlKnowledgeFeedbackRunner.run(
                HtmlKnowledgePersistenceRunner.Options.parse(new String[] {"--output-dir",
                        directory.resolve("rejected").toString(), "--store-root", kept.toString()})));
    }

    private static Set<String> required() {
        var names = new ArrayList<String>();
        var observation = List.of("recall.completed", "fidelity.source", "fidelity.reference", "neuron.b2EqualsB2c");
        for (var arm : FeedbackArm.values()) {
            var seed = "seed." + arm.label() + ".";
            List.of("ingestion.manifestComplete", "ingestion.contentFidelity", "ingestion.readback",
                    "feedback.logEmpty", "recall.prefixStability").forEach(name -> names.add(seed + name));
            observation.forEach(name -> names.add(seed + name));
            observation.forEach(name -> names.add("feedback." + arm.label() + "." + name));
            var reopen = "reopen." + arm.label() + ".";
            List.of("persistence.readback", "persistence.noUnmappedAtoms").forEach(name -> names.add(reopen + name));
            observation.forEach(name -> names.add(reopen + name));
            var disabled = "reopen." + arm.label() + "-disabled.";
            observation.forEach(name -> names.add(disabled + name));
            names.add(disabled + "recall.prefixStability");
            names.add("feedback.logUnchangedAcrossRestart." + arm.label());
            names.add("determinism.restartEqual." + arm.label());
        }
        names.add("seed.profile.strategyNeutral");
        names.add("feedback.control.recall.prefixStability");
        names.add("feedback.control.feedback.none");
        names.add("reopen.control.recall.prefixStability");
        for (var arm : FeedbackArm.treatments()) {
            names.add("feedback." + arm.label() + ".feedback.applied");
            names.add("feedback.persisted." + arm.label());
            names.add("feedback.persistenceAudit." + arm.label());
            names.add("feedback.disabledEqualsControl." + arm.label());
            names.add("feedback.heldOutUnchanged." + arm.label());
            names.add("feedback.calibrationWithoutEventsUnchanged." + arm.label());
        }
        names.addAll(List.of("arms.preFeedbackEqual", "split.valid", "variants.keyPreserving", "feedback.planDerived",
                "process.restart", "determinism.controlStable"));
        return Set.copyOf(names);
    }
}
