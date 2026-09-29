package monada.neuron.evaluation.integration;

import monada.neuron.evaluation.integration.ResonanceStoreIntegrationEvaluation.Check;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the whole evaluation once over a real temporary store and asserts each semantic verdict. */
class ResonanceStoreIntegrationEvaluationTest {

    private static ResonanceStoreIntegrationReport report;

    @BeforeAll
    static void runOnce() {
        report = ResonanceStoreIntegrationRunner.run(42L, true);
    }

    @Test
    void everySemanticCheckPasses() {
        var failures = report.checks().stream()
                .filter(check -> !check.passed())
                .map(check -> check.name() + ": " + check.detail())
                .toList();

        assertEquals(List.of(), failures);
    }

    @Test
    void coversRequiredScenarios() {
        var names = report.checks().stream().map(Check::name).collect(Collectors.toSet());

        for (var required : List.of(
                "recall.solar-energy",
                "recall.no-match",
                "recall.bounded-prefix-stability",
                "cycle.memory-only.deterministic-replay",
                "cycle.memory-only.signal-budget-truncates-recalled-prefix",
                "cycle.full.stage-order-and-budget",
                "cycle.full.deterministic-replay",
                "failure.closed-adapter-unavailable",
                "failure.storage-loss-failed",
                "failure.open-failure-translated",
                "failure.incompatible-manifest-translated",
                "temporary-store-cleanup")) {
            assertTrue(names.contains(required), "missing check " + required);
        }
    }

    @Test
    void separatesSetupFromRecallAndCycleMeasurements() {
        var phases = report.evaluation().results().stream()
                .collect(Collectors.toMap(result -> result.benchmarkName(), result -> result.diagnostics().getOrDefault("phase", "cycle")));

        assertEquals("setup", phases.get("ResonanceStore.SeedFixtureCorpus"));
        assertEquals("setup", phases.get("ResonanceStore.OpenExistingStore"));
        assertEquals("recall", phases.get("ResonanceStore.Recall.Warm"));
        assertEquals("recall", phases.get("ResonanceStore.FirstRecall.FreshlyOpenedAdapter"));
        assertTrue(phases.containsKey("NeuronCycle.FullCycle.RealResonanceStore"));
        assertTrue(phases.containsKey("NeuronCycle.FullCycle.DeterministicMemoryFixture"));
    }

    @Test
    void reportsVersionAndConfigurationMetadata() {
        var metadata = report.metadata();

        assertEquals(ResonanceStoreFixtureCorpus.VERSION, metadata.get("fixtureVersion"));
        assertTrue(metadata.containsKey("neuron.version"));
        assertTrue(metadata.containsKey("store.checkoutCommit"));
        assertTrue(metadata.containsKey("store.manifest.version"));
        assertTrue(metadata.containsKey("store.manifest.encoder"));
        assertTrue(report.toJson().contains("\"allChecksPassed\": true"));
        assertTrue(report.toMarkdown().contains("## Semantic Checks"));
    }

    @Test
    void semanticAndMetadataOutputIsStableAcrossRuns() {
        var again = ResonanceStoreIntegrationRunner.run(42L, true);

        assertEquals(verdicts(report), verdicts(again));
        assertEquals(report.metadata(), again.metadata());
        assertFalse(report.evaluation().results().isEmpty());
    }

    private static Map<String, String> verdicts(ResonanceStoreIntegrationReport report) {
        return report.checks().stream()
                .collect(Collectors.toMap(Check::name, check -> check.passed() + ":" + check.detail()));
    }
}
