package monada.neuron.evaluation.baseline;

import monada.neuron.evaluation.metrics.EvaluationReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveBaselineRunnerTest {

    @Test
    void runsQuickBaselineSuiteSuccessfully() {
        var runner = new CognitiveBaselineRunner(42L, true);
        EvaluationReport report = runner.runBaselineSuite();

        assertNotNull(report);
        assertNotNull(report.environment());
        assertFalse(report.results().isEmpty());

        boolean hasResonance = report.results().stream().anyMatch(r -> r.benchmarkName().contains("ScalarResonanceMetric"));
        boolean hasBatchResonance = report.results().stream().anyMatch(r -> r.benchmarkName().contains("VectorBatchResonance"));
        boolean hasGraph = report.results().stream().anyMatch(r -> r.benchmarkName().contains("GraphPropagation"));
        boolean hasCompactGraph = report.results().stream()
                .anyMatch(r -> r.benchmarkName().contains("CompactGraphPropagation"));
        boolean hasCompactCompilation = report.results().stream()
                .anyMatch(r -> r.benchmarkName().equals("CompactGraphCompilation"));
        boolean hasAeon = report.results().stream().anyMatch(r -> r.benchmarkName().contains("AeonCoordinator"));
        boolean hasCycle = report.results().stream().anyMatch(r -> r.benchmarkName().contains("DeterministicCognitiveCycle"));
        boolean hasAdaptation = report.results().stream().anyMatch(r -> r.benchmarkName().contains("CognitiveCycle.Adaptation"));
        boolean hasNodeStateLayout = report.results().stream()
                .anyMatch(r -> r.benchmarkName().contains("NodeStateLayout"));

        assertTrue(hasResonance, "Should contain ScalarResonanceMetric benchmark");
        assertTrue(hasBatchResonance, "Should contain VectorBatchResonance benchmark");
        assertTrue(hasGraph, "Should contain GraphPropagation benchmark");
        assertTrue(hasCompactGraph, "Should contain compact graph propagation benchmark");
        assertTrue(hasCompactCompilation, "Should contain compact graph compilation benchmark");
        assertTrue(hasAeon, "Should contain AeonCoordinator benchmark");
        assertTrue(hasCycle, "Should contain DeterministicCognitiveCycle benchmark");
        assertTrue(hasAdaptation, "Should contain Adaptation comparison benchmark");
        assertTrue(hasNodeStateLayout, "Should contain the FFM state-layout experiment");
    }

    @Test
    void fullCycleRowsReportAllFiveStagesCompleted() {
        var report = new CognitiveBaselineRunner(42L, true).runBaselineSuite();

        var cycleRows = report.results().stream()
                .filter(r -> r.benchmarkName().equals("DeterministicCognitiveCycle.FullCycle")
                        || r.benchmarkName().startsWith("CognitiveCycle.Adaptation."))
                .toList();

        assertEquals(3, cycleRows.size());
        for (var row : cycleRows) {
            var diagnostics = row.diagnostics();
            assertEquals("COMPLETED", diagnostics.get("termination"), row.benchmarkName());
            assertEquals("5", diagnostics.get("stagesExecuted"), row.benchmarkName());
            assertEquals("false", diagnostics.get("stepBudgetExhausted"), row.benchmarkName());
            assertEquals("false", diagnostics.get("signalBudgetExhausted"), row.benchmarkName());
            assertEquals("false", diagnostics.get("traceBudgetExhausted"), row.benchmarkName());
            assertEquals("0", diagnostics.get("omittedTraceEntries"), row.benchmarkName());
            assertEquals("0", diagnostics.get("propagationCalibrationRung"), row.benchmarkName());
            assertEquals("issue-48-corrected", diagnostics.get("baselineRevision"), row.benchmarkName());
        }
    }

    @Test
    void customSeedWhoseTopologyNeedsMoreHopsStillExecutesAllFiveStages() {
        var report = new CognitiveBaselineRunner(10_365L, true).runBaselineSuite();

        var row = report.results().stream()
                .filter(r -> r.benchmarkName().equals("DeterministicCognitiveCycle.FullCycle"))
                .findFirst()
                .orElseThrow();
        assertEquals("COMPLETED", row.diagnostics().get("termination"));
        assertEquals("5", row.diagnostics().get("stagesExecuted"));
        assertNotEquals("0", row.diagnostics().get("propagationCalibrationRung"));
    }
}
