package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.ArmResult;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.Outcome;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.Protocol;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.RepeatResult;
import monada.neuron.spectral.SpectralBasis;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The consolidated ablation harness on a small protocol whose seeds are not the registered ones. */
class NcmAblationEvaluationTest {

    static final Protocol SMALL = new Protocol(
            new NextConceptG3Evaluation.Protocol(0x7E57_0001L, 0x7E57_0002L, List.of(0x7E57_1001L), 0x7E57_B007L,
                    100, List.of(20.0, 0.0), 8, 32, 0x7E57_0011L, 0.15, 0.60, 0.05, 0.0, 0.70),
            new NextConceptG5Evaluation.Protocol(0x7E57_0001L, 0x7E57_0002L, List.of(0x7E57_F001L),
                    List.of(0x7E57_1001L), 0x7E57_B005L, 100, List.of(20.0, 0.0), 8, 32, 0.02, 0.60, 0.70, 1.0, 64),
            new LargeWorkloadEvaluation.Protocol(0x7E57_3001L, 0x7E57_3002L, 20.0, 2, 1, 8, 12),
            List.of(2, 5), List.of(0.5), 0x7E57_B130L, 0, 1, 16);

    private static Outcome outcome;

    @BeforeAll
    static void run() {
        outcome = new NcmAblationEvaluation(SMALL, true).run();
    }

    @Test
    void everyCheckPassesAndTheVerdictFollowsItsRule() {
        for (EvaluationCheck check : outcome.checks()) {
            assertTrue(check.passed(), check.name() + ": " + check.detail());
        }
        List<String> names = outcome.checks().stream().map(EvaluationCheck::name).toList();
        assertEquals("workload.tiny", names.getFirst());
        assertEquals(6, names.stream().filter(name -> name.startsWith("g3.")).count());
        assertEquals(8, names.stream().filter(name -> name.startsWith("g5.")).count());
        assertEquals(List.of("d.reference-equivalence", "c.reproduces-g3", "d.paired-inputs",
                "leakage.counterfactual", "determinism", "leakage.test-labels", "host.memory-independence",
                "large.bounds"), names.subList(15, names.size()));
        assertEquals("false", outcome.metadata().get("protocol.registered"));

        boolean featureGo = false;
        for (int a = 0; a < NcmAblationEvaluation.ARMS.size(); a++) {
            int arm = a;
            assertEquals(outcome.repeats().stream().allMatch(repeat -> repeat.arms().get(arm).gatePassed()),
                    outcome.armGo()[a]);
            featureGo |= a > 0 && outcome.armGo()[a];
        }
        String expected = outcome.armGo()[NcmAblationEvaluation.C] ? "GO" : featureGo ? "CONDITIONAL GO" : "NO-GO";
        assertEquals(expected, outcome.verdict());
        assertEquals(outcome.verdict(), outcome.metadata().get("verdict"));
        assertEquals(outcome.g3().go(), outcome.armGo()[NcmAblationEvaluation.C], "C is the G3 harness's C");
    }

    @Test
    void featureArmsAreSelectedFromTheirGridsAndScoredByTheRegisteredRule() {
        assertEquals(List.of("C", "C+coh", "D-haar", "D-haar+coh"), NcmAblationEvaluation.ARMS);
        var selections = outcome.selections();
        assertEquals(SpectralBasis.DFT, selections.get(0).map().basis());
        assertEquals(0.5, selections.get(0).map().coherenceWeight());
        assertEquals(SpectralBasis.HAAR, selections.get(1).map().basis());
        assertEquals(0.0, selections.get(1).map().coherenceWeight());
        assertTrue(SMALL.haarLevels().contains(selections.get(2).map().haarLevels()));
        assertEquals(SMALL.haarLevels().size() * NextConceptG3Evaluation.TEMPERATURES.length, selections.get(1).tried());

        RepeatResult repeat = outcome.repeats().getFirst();
        for (ArmResult arm : repeat.arms()) {
            for (double value : new double[] {arm.coverage(), arm.coveredAccuracy(), arm.forcedAccuracy(),
                    arm.top2(), arm.mrr(), arm.rootCoverage(), arm.forcedEce(), arm.noiseUncovered()}) {
                assertTrue(value >= 0.0 && value <= 1.0, arm.name() + " value out of range: " + value);
            }
            assertTrue(arm.top2() >= arm.forcedAccuracy());
            boolean gate = arm.h3().lower() > SMALL.g3().h3Margin() && arm.coverage() >= SMALL.g3().h3MinCoverage()
                    && arm.h6().lower() > SMALL.g3().h6Margin() && arm.noiseUncovered() > arm.cleanUncovered();
            assertEquals(gate, arm.gatePassed(), arm.name());
            assertEquals(SMALL.g3().snrLevels().size(), arm.coverageByLevel().length);
        }
        assertEquals(0.0, repeat.arms().getFirst().forcedVsC().lower(), "C against itself is exactly zero");
    }

    @Test
    void theLargeWorkloadIsBoundedAndTheResourcesCoverEveryScale() {
        var large = outcome.large();
        assertEquals(256, large.c().hierarchy().size());
        assertEquals(3, large.c().hierarchy().levels());
        assertTrue(large.cScore().maxComparisons() <= 256);
        assertEquals(1.0, large.cScore().leafCoverage(), "no thresholds: every window answers down to a leaf");
        List<String> scenarios = outcome.resources().stream().map(result -> result.benchmarkName()).toList();
        assertEquals(List.of("ncm.tiny.C.window", "ncm.medium.C.window", "ncm.medium.C.predict", "ncm.medium.D.window",
                "ncm.medium.stage.execution", "ncm.large.C.window", "ncm.large.C.predict", "ncm.large.D.window",
                "ncm.large.stage.execution"), scenarios);
        for (var result : outcome.resources()) {
            assertTrue(result.latency().medianNanos() > 0.0, result.benchmarkName());
        }
    }

    @Test
    void theRunnerWritesBothReportsAndMapsFailuresToExitCodes(@TempDir Path dir) throws Exception {
        var runner = new NcmAblationRunner();
        var results = runner.resultsMarkdown(SMALL, outcome);
        assertTrue(results.startsWith("## Ablation Results"));
        assertTrue(results.contains("Verdict: **" + outcome.verdict() + "**"));

        assertEquals(NcmAblationRunner.INVALID_ARGUMENTS, runner.execute(new String[] {"--bogus"}));
        assertEquals(NcmAblationRunner.HARNESS_FAILURE, runner.report(() -> {
            throw new IllegalStateException("boom");
        }, dir));
        var configuration = new NcmAblationEvaluation(SMALL, true).runConfiguration();
        assertEquals(0, runner.report(() -> runner.reportOf(outcome, SMALL, configuration), dir));
        String markdown = Files.readString(dir.resolve("ncm-ablation.md"));
        assertTrue(markdown.contains("## Ablation Results") && markdown.contains("## Semantic Checks"));
        assertTrue(Files.readString(dir.resolve("ncm-ablation.json")).contains("\"verdict\": \""
                + outcome.verdict() + "\""));
    }
}
