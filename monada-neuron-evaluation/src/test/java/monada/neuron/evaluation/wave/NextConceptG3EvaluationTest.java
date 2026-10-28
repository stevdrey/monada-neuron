package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Outcome;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Protocol;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.RepeatResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The G3 harness on a small protocol whose seeds are not the registered ones. */
class NextConceptG3EvaluationTest {

    private static final Protocol SMALL = new Protocol(0x7E57_0001L, 0x7E57_0002L, List.of(0x7E57_1001L),
            0x7E57_B007L, 100, List.of(20.0, 0.0), 8, 32, 0x7E57_0011L, 0.15, 0.60, 0.05, 0.0, 0.70);

    private static Outcome outcome;

    @BeforeAll
    static void run() {
        outcome = new NextConceptG3Evaluation(SMALL, true).run();
    }

    @Test
    void everyCheckPassesAndTheVerdictIsData() {
        for (EvaluationCheck check : outcome.checks()) {
            assertTrue(check.passed(), check.name() + ": " + check.detail());
        }
        assertEquals(List.of("fixtures.p15-n9-n12", "splits.disjoint", "leakage.counterfactual", "determinism",
                "leakage.test-labels"), outcome.checks().stream().map(EvaluationCheck::name).toList());
        assertEquals("false", outcome.metadata().get("protocol.registered"));
        assertTrue(outcome.metadata().get("g3.verdict").startsWith(outcome.go() ? "GO" : "NO-GO"));
        assertTrue(outcome.metadata().get("c.features").contains("no resonance features"));
    }

    @Test
    void metricsAreConsistentRatesAndTheGateFollowsItsRule() {
        RepeatResult repeat = outcome.repeats().getFirst();
        for (double value : new double[] {repeat.coverage(), repeat.cAccuracy(), repeat.aAccuracy(),
                repeat.forcedAccuracy(), repeat.flatAccuracy(), repeat.flatCoverage(), outcome.noiseUncovered(),
                repeat.forcedReliability().ece(), repeat.abstentionPrecision(), repeat.abstentionRecall()}) {
            assertTrue(value >= 0.0 && value <= 1.0, "rate out of range: " + value);
        }
        assertTrue(repeat.h3().lower() <= repeat.h3().upper());
        boolean expected = repeat.h3().lower() > SMALL.h3Margin() && repeat.coverage() >= SMALL.h3MinCoverage()
                && repeat.h6().lower() > SMALL.h6Margin() && outcome.noiseUncovered() > repeat.cleanUncovered();
        assertEquals(expected, repeat.gatePassed());
        assertEquals(outcome.repeats().stream().allMatch(RepeatResult::gatePassed), outcome.go());
        // A answers one fixed leaf, so on covered windows its accuracy is the share of that next leaf.
        assertTrue(outcome.selection().mostFrequentNext() >= 2);
        assertEquals(SMALL.selectionCoverage() <= outcome.selection().coverage(), outcome.selection().reached());
        // Determinism and counterfactual reruns guard their label sets too: test, permuted and regenerated.
        assertTrue(outcome.checks().getLast().detail().startsWith(3 * SMALL.snrLevels().size() + " guarded"),
                outcome.checks().getLast().detail());
    }

    @Test
    void aProtocolWithoutTheCleanLevelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Protocol(0x7E57_0001L, 0x7E57_0002L,
                List.of(0x7E57_1001L), 0x7E57_B007L, 100, List.of(10.0, 0.0), 8, 32, 0x7E57_0011L, 0.15, 0.60, 0.05,
                0.0, 0.70));
    }

    @Test
    void runnerWritesTheReportAndMapsFailuresToExitCodes(@TempDir Path directory) throws Exception {
        var runner = new NextConceptG3Runner();
        var report = runner.run(SMALL, true);
        assertEquals(0, runner.report(() -> report, directory));
        String markdown = Files.readString(directory.resolve("next-concept-g3.md"));
        assertTrue(markdown.contains("## G3 Results"));
        assertTrue(markdown.indexOf("## G3 Results") < markdown.indexOf("## Run Configuration"));
        assertTrue(Files.readString(directory.resolve("next-concept-g3.json")).contains("\"g3\": {"));

        assertEquals(NextConceptG3Runner.HARNESS_FAILURE, runner.report(() -> {
            throw new IllegalStateException("boom");
        }, directory));
        assertEquals(NextConceptG3Runner.INVALID_ARGUMENTS, runner.execute(new String[] {"--unknown"}));
    }
}
