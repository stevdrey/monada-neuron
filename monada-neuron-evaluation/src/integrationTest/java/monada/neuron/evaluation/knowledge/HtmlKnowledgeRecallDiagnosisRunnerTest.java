package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.RecallDiagnosisLadder.Rung;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.resonance.adapter.ResonanceStoreRecall;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the diagnosis once over base-25 and base-33; most tests read that run. */
class HtmlKnowledgeRecallDiagnosisRunnerTest {

    @TempDir
    static Path directory;

    private static HtmlKnowledgeRecallDiagnosisRunner.Outcome outcome;
    private static List<Path> written;

    @BeforeAll
    static void runOnce() throws IOException {
        outcome = run("first", UnaryOperator.identity());
        written = HtmlKnowledgeRecallDiagnosisRunner.write(outcome);
    }

    private static HtmlKnowledgeRecallDiagnosisRunner.Outcome run(String name,
            UnaryOperator<ResonanceStoreRecall> seam) {
        return HtmlKnowledgeRecallDiagnosisRunner.run(HtmlKnowledgeRecallDiagnosisRunner.Options.parse(new String[] {
                "--points", "base-25,base-33", "--output-dir", directory.resolve(name).toString()}), seam);
    }

    @Test
    void everyPointPassesItsSemanticChecks() {
        var names = outcome.report().checks().stream().map(EvaluationCheck::name).collect(Collectors.toSet());
        assertTrue(names.contains("ladder.corpora"));
        for (var point : List.of("base-25", "base-33")) {
            for (var check : List.of("ingestion.readback", "recall.completed", "fidelity.source",
                    "fidelity.reference", "neuron.b2EqualsB2c", "recall.prefixStability",
                    "diagnosis.captureCompleted", "diagnosis.maxScoreReplayEqualsB2",
                    "diagnosis.attributionConsistent")) {
                assertTrue(names.contains(point + "." + check), point + "." + check);
            }
        }
        assertTrue(outcome.report().allPassed(), outcome.markdown());
    }

    @Test
    void theBaselineReproducesTheScalingProfile() {
        // base-25 is #111's N = 25 point; its B1 and B2 Recall@5 over the curve cohort are those of the scaling report.
        var base25 = outcome.diagnosis().orElseThrow().toJson();
        assertTrue(base25.contains("\"system\": \"B1\", \"pool\": \"full-text\", \"k\": 5, \"Recall@5\": 0.735"),
                "B1");
        assertTrue(base25.contains("\"system\": \"B2\", \"pool\": \"k\", \"k\": 5, \"Recall@5\": 0.539"), "B2");
    }

    @Test
    void theMachineReadableReportHoldsHitsFatesAndLabels() throws IOException {
        var json = Files.readString(written.getFirst());
        assertTrue(json.contains("\"allChecksPassed\": true"));
        for (var member : List.of("\"diagnosis\":", "\"captures\":", "\"fates\":", "\"displacers\":", "\"tokens\":",
                "\"candidateCeilings\":", "\"comparability\":", "\"regressions\":", "\"cost\":", "\"groups\":",
                "\"alternatives\":", "\"OUTRANKED_IN_MERGE\"")) {
            assertTrue(json.contains(member), member);
        }
        assertTrue(json.contains(HtmlKnowledgeRecallDiagnosisReport.RESEARCH_ONLY));
        assertFalse(json.matches("(?s).*\"atomId\".*"), "raw atom ids never reach the report");
        var markdown = Files.readString(written.get(1));
        assertTrue(markdown.contains("### Where relevant chunks are lost (k = 5)"));
        assertTrue(markdown.contains("base growth (no distractors)"));
        assertTrue(markdown.contains("research-only, in-sample exploratory"));
    }

    @Test
    void aSecondRunReproducesTheDiagnosisExactly() {
        var again = run("second", UnaryOperator.identity());

        assertEquals(outcome.diagnosis().orElseThrow().toJson(), again.diagnosis().orElseThrow().toJson());
    }

    @Test
    void aFailingStoreCallIsAFailedCheckNeverAZeroScore() {
        UnaryOperator<ResonanceStoreRecall> seam = store -> (query, limit, threshold) -> {
            if (query.equals("element")) {
                throw new IllegalStateException("injected");
            }
            return store.recall(query, limit, threshold);
        };
        var failed = run("failing", seam);

        assertFalse(failed.report().allPassed());
        var failing = failed.report().checks().stream().filter(check -> !check.passed()).map(EvaluationCheck::name)
                .collect(Collectors.toSet());
        assertTrue(failing.contains("base-33.diagnosis.captureCompleted"), failing.toString());
        assertTrue(failing.contains("base-33.diagnosis.maxScoreReplayEqualsB2"), "not compared is not equal");
        var json = failed.diagnosis().orElseThrow().toJson();
        assertTrue(json.contains("\"failure\": \"token"), "the failure is recorded with the query");
        assertFalse(json.contains("\"failedQueries\": []"), "failed queries are listed, not scored as zeros");
    }

    @Test
    void optionsAreParsedStrictly() {
        var defaults = HtmlKnowledgeRecallDiagnosisRunner.Options.parse(new String[0]);
        assertEquals(List.of(Rung.values()), defaults.rungs());
        assertEquals(ScalingDistractorGenerator.DEFAULT_SEED, defaults.seed());
        assertEquals(List.of(Rung.N100), HtmlKnowledgeRecallDiagnosisRunner.Options.parse(new String[] {"--points",
                "n100"}).rungs());
        for (var invalid : List.of(new String[] {"--points", "n500,n100"}, new String[] {"--points", "n42"},
                new String[] {"--seed", "x"}, new String[] {"--points"}, new String[] {"--quick"},
                new String[] {"--output-dir", directory.resolve("a").toString(), "--store-root",
                        directory.resolve("a/b").toString()})) {
            assertThrows(IllegalArgumentException.class,
                    () -> HtmlKnowledgeRecallDiagnosisRunner.Options.parse(invalid), String.join(" ", invalid));
        }
    }
}
