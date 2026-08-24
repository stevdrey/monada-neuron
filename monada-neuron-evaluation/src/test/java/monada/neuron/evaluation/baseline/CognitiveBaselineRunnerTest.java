package monada.neuron.evaluation.baseline;

import monada.neuron.evaluation.metrics.EvaluationReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertTrue(hasResonance, "Should contain ScalarResonanceMetric benchmark");
        assertTrue(hasBatchResonance, "Should contain VectorBatchResonance benchmark");
        assertTrue(hasGraph, "Should contain GraphPropagation benchmark");
        assertTrue(hasCompactGraph, "Should contain compact graph propagation benchmark");
        assertTrue(hasCompactCompilation, "Should contain compact graph compilation benchmark");
        assertTrue(hasAeon, "Should contain AeonCoordinator benchmark");
        assertTrue(hasCycle, "Should contain DeterministicCognitiveCycle benchmark");
        assertTrue(hasAdaptation, "Should contain Adaptation comparison benchmark");
    }
}
