package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.wave.ConceptFixtureGenerator.ConceptSplit;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Outcome;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The H2 harness on a small protocol whose seeds are not the registered ones. */
class SpectralResonanceH2EvaluationTest {

    private static final Protocol SMALL = new Protocol(0x7E57_0001L, 0x7E57_0002L, List.of(0x7E57_1001L),
            0x7E57_B007L, 100, List.of(20.0, 0.0), 4, 32, 0.10);

    @Test
    void everyCheckPassesAndTheVerdictIsData() {
        Outcome outcome = new SpectralResonanceH2Evaluation(SMALL, true).run();

        for (EvaluationCheck check : outcome.checks()) {
            assertTrue(check.passed(), check.name() + ": " + check.detail());
        }
        assertEquals(List.of("fixtures.p10-p12-n6", "splits.disjoint", "leakage.counterfactual", "determinism",
                "leakage.test-labels"), outcome.checks().stream().map(EvaluationCheck::name).toList());
        assertEquals("false", outcome.metadata().get("protocol.registered"));
        assertTrue(outcome.metadata().get("g2.verdict").startsWith(outcome.go() ? "GO" : "NO-GO"));
        assertEquals(H2Arm.values().length, outcome.repeats().getFirst().arms().size());
    }

    @Test
    void repeatedSplitSeedsFailTheDisjointnessCheck() {
        var overlapping = new Protocol(0x7E57_1001L, 0x7E57_0002L, List.of(0x7E57_1001L), 0x7E57_B007L, 20,
                List.of(10.0), 2, 16, 0.10);
        Outcome outcome = new SpectralResonanceH2Evaluation(overlapping, true).run();

        assertFalse(outcome.checks().stream().filter(c -> c.name().equals("splits.disjoint")).findFirst()
                .orElseThrow().passed());
    }

    @Test
    void disjointnessCatchesSharedCleanWindowsUnderDifferentNoise() {
        var generator = new ConceptFixtureGenerator();
        // Same tone seed, different SNR: identical clean windows, different noisy samples.
        ConceptSplit first = generator.generate(SplitRole.TRAINING, 0x7E57_0001L, 20.0, 2, 4);
        ConceptSplit second = generator.generate(SplitRole.TEST, 0x7E57_0001L, 0.0, 2, 4);
        ConceptSplit other = generator.generate(SplitRole.TEST, 0x7E57_0002L, 20.0, 2, 4);
        var evaluation = new SpectralResonanceH2Evaluation(SMALL, true);

        assertFalse(Arrays.equals(first.windows().getFirst().wave().samples(),
                second.windows().getFirst().wave().samples()));
        assertFalse(evaluation.disjointnessCheck(List.of(1L, 2L), List.of(first, second)).passed());
        assertTrue(evaluation.disjointnessCheck(List.of(1L, 2L), List.of(first, other)).passed());
    }

    @Test
    void runnerWritesTheReportAndMapsFailuresToExitCodes(@TempDir Path directory) throws Exception {
        var runner = new SpectralResonanceH2Runner();
        assertEquals(0, runner.report(() -> runner.run(SMALL, true), directory));
        String markdown = Files.readString(directory.resolve("spectral-resonance-h2.md"));
        assertTrue(markdown.contains("## H2 Results"));
        assertTrue(markdown.indexOf("## H2 Results") < markdown.indexOf("## Run Configuration"));
        assertTrue(Files.readString(directory.resolve("spectral-resonance-h2.json")).contains("\"h2\": {"));

        assertEquals(SpectralResonanceH2Runner.HARNESS_FAILURE, runner.report(() -> {
            throw new IllegalStateException("boom");
        }, directory));
        assertEquals(SpectralResonanceH2Runner.HARNESS_FAILURE, runner.report(() -> {
            throw new AssertionError("an Error is a harness failure, not a failed check");
        }, directory));
        assertEquals(SpectralResonanceH2Runner.INVALID_ARGUMENTS,
                runner.execute(new String[] {"--unknown"}));
    }
}
