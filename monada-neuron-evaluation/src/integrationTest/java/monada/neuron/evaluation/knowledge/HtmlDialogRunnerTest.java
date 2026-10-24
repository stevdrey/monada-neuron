package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.DecisionTrace.Row;
import monada.neuron.evaluation.knowledge.DialogTrace.Change;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the multi-turn suite CLI end to end twice over fresh temporary Stores (Issue #113). */
class HtmlDialogRunnerTest {

    private static final List<String> REQUIRED = List.of("seed.ingestion.manifestComplete",
            "seed.ingestion.contentFidelity", "seed.ingestion.readback", "reopen.persistence.readback",
            "dialog.completed", "dialog.selectorCitationsVerified", "context.noLeakage",
            "control.noContextElliptical", "control.evidenceRemoved", "dialog.deterministic", "perf.matchesVerified",
            "dialog.scored", "gold.hashFrozen", "gold.reusedHashFrozen");

    @TempDir
    static Path directory;

    private static HtmlDialogRunner.Outcome first;
    private static HtmlDialogRunner.Outcome second;
    private static List<Path> written;

    @BeforeAll
    static void runTwice() throws IOException {
        first = HtmlDialogRunner.run(Options.parse(new String[] {"--output-dir",
                directory.resolve("one").toString()}));
        written = HtmlDialogRunner.write(first);
        second = HtmlDialogRunner.run(Options.parse(new String[] {"--output-dir",
                directory.resolve("two").toString()}));
    }

    @Test
    void everyCheckPassesInBothRuns() {
        for (var outcome : List.of(first, second)) {
            assertEquals(REQUIRED, outcome.report().checks().stream().map(EvaluationCheck::name).toList());
            outcome.report().checks().forEach(check -> assertTrue(check.passed(), check.name() + ": "
                    + check.detail()));
            assertEquals(HtmlDialogRunner.FROZEN_GOLD_HASH, outcome.report().metadata().get("gold.hash"));
            assertEquals(HtmlDecisionGold.REVISION_VERSION, outcome.report().metadata().get("gold.reused"));
        }
    }

    @Test
    void twoRunsHaveTheSameSemanticDigestAndTrace() {
        assertEquals(first.semanticDigest(), second.semanticDigest());
        assertEquals(first.files().get(HtmlDialogRunner.TRACE), second.files().get(HtmlDialogRunner.TRACE));
        assertEquals(first.dialogs().orElseThrow().json(), second.dialogs().orElseThrow().json());
    }

    @Test
    void theReportStatesWhoHoldsStateAndPrintsTheContextRules() {
        var markdown = first.markdown();
        for (var section : List.of("### Who holds conversational state", "### Host context rules (evaluation-only)",
                "### Single-turn retrieval (arm A, scored apart)", "### Multi-turn results per arm", "### Ablations",
                "### Change sensitivity (B+R)", "### Turns", "### Errors", "### Possible follow-ups", "### Limits",
                "NOT INDEPENDENTLY ADJUDICATED")) {
            assertTrue(markdown.contains(section), section);
        }
        DialogContextAssembler.RULES.forEach(rule -> assertTrue(markdown.contains(rule.substring(0, 12)), rule));
        var json = first.json();
        assertTrue(json.contains("\"semanticDigest\": \"" + first.semanticDigest() + "\""));
        assertTrue(json.contains("\"contextRules\": [\"C1 version: "));
        assertFalse(first.dialogs().orElseThrow().json().contains("generatedAt"));
    }

    @Test
    void theOutputsAreWritten() {
        assertEquals(List.of("html-dialog.json", "html-dialog.md", "html-dialog-v1-trace.tsv"),
                written.stream().map(path -> path.getFileName().toString()).toList());
        written.forEach(path -> assertTrue(Files.exists(path), path.toString()));
        assertTrue(first.files().get(HtmlDialogRunner.TRACE).contains("\nend\t120\n"));
    }

    @Test
    void aChangeIsClassifiedByOutcomeChoicesAndCaveats() {
        var iframe = decided("iframe", List.of());
        var allowed = decided("iframe", List.of(DecisionRecommendation.REQUESTED_FEATURES_ONLY));
        var div = decided("div", List.of());
        assertEquals(Change.FIRST, DialogTrace.change(Optional.empty(), iframe, false));
        assertEquals(Change.FIRST, DialogTrace.change(Optional.of(div), iframe, true));
        assertEquals(Change.UNCHANGED, DialogTrace.change(Optional.of(iframe), iframe, false));
        assertEquals(Change.QUALIFIED, DialogTrace.change(Optional.of(iframe), allowed, false));
        assertEquals(Change.REVISED, DialogTrace.change(Optional.of(div), iframe, false));
        var abstained = row(new DecisionRecommendation.Abstained("ask", "?", "L1"));
        assertEquals(Change.REVISED, DialogTrace.change(Optional.of(abstained), div, false));
        var failed = new Row("turn", DecisionTrace.Arm.NEURON_RULES, "R", "HOST_RULE_BASELINE", List.of(),
                Optional.empty(), Outcome.RECALL_FAILED, Optional.empty(), List.of(), List.of(), "failed");
        assertEquals(Change.NOT_APPLICABLE, DialogTrace.change(Optional.of(div), failed, false));
    }

    private static Row decided(String choice, List<String> permissions) {
        return row(new DecisionRecommendation.Decided(choice, List.of(), List.of(), List.of(), permissions, "r",
                List.of(), "E"));
    }

    private static Row row(DecisionRecommendation recommendation) {
        return new Row("turn", DecisionTrace.Arm.NEURON_RULES, "R", "HOST_RULE_BASELINE", List.of(), Optional.empty(),
                Row.outcomeOf(recommendation), Optional.of(recommendation), List.of(), List.of(), "-");
    }
}
