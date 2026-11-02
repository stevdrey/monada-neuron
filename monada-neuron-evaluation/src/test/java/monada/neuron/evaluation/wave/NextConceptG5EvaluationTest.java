package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.Arm;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.Outcome;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.Protocol;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.RepeatResult;
import monada.neuron.reasoning.concept.ConceptFeedbackRejection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The G5 harness on a small protocol whose seeds are not the registered ones. */
class NextConceptG5EvaluationTest {

    private static final Protocol SMALL = new Protocol(0x7E57_0001L, 0x7E57_0002L, List.of(0x7E57_F001L),
            List.of(0x7E57_1001L), 0x7E57_B005L, 100, List.of(20.0, 0.0), 8, 32, 0.02, 0.60, 0.70, 1.0, 64);

    private static Outcome outcome;

    @BeforeAll
    static void run() {
        outcome = new NextConceptG5Evaluation(SMALL, true).run();
    }

    @Test
    void everyCheckPassesAndTheVerdictIsData() {
        for (EvaluationCheck check : outcome.checks()) {
            assertTrue(check.passed(), check.name() + ": " + check.detail());
        }
        assertEquals(List.of("splits.disjoint", "leakage.feedback-provenance", "feedback.unverified-abstains",
                "feedback.duplicate-rejected", "feedback.replay-deterministic", "leakage.counterfactual",
                "determinism", "leakage.test-labels"), outcome.checks().stream().map(EvaluationCheck::name).toList());
        assertEquals("false", outcome.metadata().get("protocol.registered"));
        assertEquals(outcome.holds() ? "holds" : "negative", outcome.metadata().get("h5.verdict"));
        assertEquals("ncm-wave-cognition/2", outcome.metadata().get("contract"));
    }

    @Test
    void feedbackIsBoundedAndTheGateFollowsItsRule() {
        RepeatResult repeat = outcome.repeats().getFirst();
        for (int arm = 0; arm < repeat.rate().length; arm++) {
            for (double value : new double[] {repeat.rate()[arm], repeat.coverage()[arm],
                    repeat.coveredAccuracy()[arm]}) {
                assertTrue(value >= 0.0 && value <= 1.0, "rate out of range: " + value);
            }
        }
        assertEquals(Arm.NAMES.size(), repeat.rate().length);
        boolean expected = repeat.h5().lower() > SMALL.h5Margin() && repeat.coverage()[Arm.E] >= SMALL.h5MinCoverage();
        assertEquals(expected, repeat.gatePassed());
        assertEquals(outcome.repeats().stream().allMatch(RepeatResult::gatePassed), outcome.holds());

        var e = repeat.e();
        assertTrue(e.applied() > 0 && e.applied() <= e.outcomes() && e.outcomes() <= e.steps());
        assertEquals(e.applied(), e.last().revision(), "one revision per applied single-outcome update");
        assertEquals(outcome.selection().model().retainedBytes(), e.last().retainedBytes(), "no state growth");
        assertEquals(e.last().retainedBytes(), e.maxCopiedBytes());
        int rejected = 0;
        for (int count : e.rejections()) {
            rejected += count;
        }
        assertEquals(e.outcomes() - e.applied(), rejected);
        assertEquals(0, e.rejections()[ConceptFeedbackRejection.MODEL_MISMATCH.ordinal()]);
        assertEquals(repeat.unverified().outcomes(),
                repeat.unverified().rejections()[ConceptFeedbackRejection.UNVERIFIED.ordinal()]);
        assertSame(outcome.selection().model(), repeat.unverified().last());
        assertNotEquals(e.last().fingerprint(), repeat.shuffled().last().fingerprint());
        assertEquals(NextConceptG5Evaluation.CHECKPOINTS.length, e.checkpoints().size());
        // Determinism and counterfactual reruns guard their label sets too: test, permuted and regenerated.
        assertTrue(outcome.checks().getLast().detail().startsWith(3 * SMALL.snrLevels().size() + " guarded"),
                outcome.checks().getLast().detail());
    }

    @Test
    void aProtocolNeedsOneFeedbackSeedPerRepeat() {
        assertThrows(IllegalArgumentException.class, () -> new Protocol(0x7E57_0001L, 0x7E57_0002L, List.of(),
                List.of(0x7E57_1001L), 0x7E57_B005L, 100, List.of(20.0, 0.0), 8, 32, 0.02, 0.60, 0.70, 1.0, 64));
    }

    @Test
    void runnerWritesTheReportAndMapsFailuresToExitCodes(@TempDir Path directory) throws Exception {
        var runner = new NextConceptG5Runner();
        var report = runner.run(SMALL, true);
        assertEquals(0, runner.report(() -> report, directory));
        String markdown = Files.readString(directory.resolve("next-concept-g5.md"));
        assertTrue(markdown.contains("## G5 Results"));
        assertTrue(markdown.indexOf("## G5 Results") < markdown.indexOf("## Run Configuration"));
        assertTrue(markdown.contains("H5 verdict: **" + (outcome.holds() ? "HOLDS" : "NEGATIVE") + "**"), markdown);
        assertTrue(Files.readString(directory.resolve("next-concept-g5.json")).contains("\"g5\": {"));

        assertEquals(NextConceptG5Runner.HARNESS_FAILURE, runner.report(() -> {
            throw new IllegalStateException("boom");
        }, directory));
        assertEquals(NextConceptG5Runner.INVALID_ARGUMENTS, runner.execute(new String[] {"--unknown"}));
    }
}
