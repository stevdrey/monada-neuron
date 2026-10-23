package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.Options;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the decision benchmark CLI end to end twice over fresh temporary Stores (Issue #112). */
class HtmlDecisionRunnerTest {

    private static final List<String> REQUIRED = List.of("seed.ingestion.manifestComplete",
            "seed.ingestion.contentFidelity", "seed.ingestion.readback", "reopen.persistence.readback",
            "decision.completed", "decision.selectorCitationsVerified", "control.emptyEvidenceAbstains",
            "decision.deterministic", "perf.matchesVerified", "decision.scored", "gold.hashFrozen");

    @TempDir
    static Path directory;

    private static HtmlDecisionRunner.Outcome first;
    private static HtmlDecisionRunner.Outcome second;
    private static List<Path> written;

    @BeforeAll
    static void runTwice() throws IOException {
        first = HtmlDecisionRunner.run(Options.parse(new String[] {"--output-dir",
                directory.resolve("one").toString()}), Optional.empty());
        written = HtmlDecisionRunner.write(first);
        second = HtmlDecisionRunner.run(Options.parse(new String[] {"--output-dir",
                directory.resolve("two").toString()}), Optional.empty());
    }

    @Test
    void everyCheckPassesInBothRuns() {
        for (var outcome : List.of(first, second)) {
            assertEquals(REQUIRED, outcome.report().checks().stream().map(EvaluationCheck::name).toList());
            outcome.report().checks().forEach(check -> assertTrue(check.passed(), check.name() + ": "
                    + check.detail()));
        }
    }

    @Test
    void twoRunsHaveTheSameSemanticDigest() {
        assertEquals(first.semanticDigest(), second.semanticDigest());
        assertEquals(first.decisions().orElseThrow().json(), second.decisions().orElseThrow().json());
    }

    @Test
    void theReportSeparatesTheFourLayersAndPrintsTheRules() {
        var markdown = first.markdown();
        for (var section : List.of("### Knowledge retrieval (arm A, Store only)", "### Neuron evidence use (arm B+R)",
                "### Host-owned decision rules (evaluation-only)", "### Optional generator (arm C)",
                "Not configured: arm C did not run")) {
            assertTrue(markdown.contains(section), section);
        }
        DecisionRuleBaseline.RULES.forEach(rule -> assertTrue(markdown.contains(rule.id() + ": if "), rule.id()));
        var json = first.json();
        assertTrue(json.contains("\"semanticDigest\": \"" + first.semanticDigest() + "\""));
        assertTrue(json.contains("\"hostRules\": \"HOST_RULE_BASELINE R: "));
        assertTrue(json.contains("\"generator\": null"));
        assertFalse(first.decisions().orElseThrow().json().contains("generatedAt"));
    }

    @Test
    void theOutputsAreWritten() {
        assertEquals(List.of("html-decision.json", "html-decision.md", "html-decision-v1-trace.tsv"),
                written.stream().map(path -> path.getFileName().toString()).toList());
        written.forEach(path -> assertTrue(Files.exists(path), path.toString()));
        assertTrue(first.files().get(HtmlDecisionRunner.TRACE).contains("\nend\t68\n"));
    }

    @Test
    void theGeneratorOptionAcceptsOnlyTheTestDouble() {
        var arguments = HtmlDecisionRunner.Arguments.parse(new String[] {"--generator", "test-double"});
        assertTrue(arguments.generator());
        var message = assertThrows(IllegalArgumentException.class,
                () -> HtmlDecisionRunner.Arguments.parse(new String[] {"--generator", "openai"})).getMessage();
        assertTrue(message.contains("test-double"), message);
    }
}
