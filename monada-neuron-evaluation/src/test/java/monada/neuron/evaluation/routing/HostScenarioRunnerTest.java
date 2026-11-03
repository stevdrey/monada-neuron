package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.routing.RoutingReplayEvaluation.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostScenarioRunnerTest {

    @TempDir
    Path output;

    private String[] args() {
        return new String[] {"--output-dir", output.toString()};
    }

    @Test
    void anUnknownArgumentIsInvalid() {
        assertEquals(RoutingReplayRunner.INVALID_ARGUMENTS, HostScenarioRunner.execute(new String[] {"--quick"}));
        assertEquals(RoutingReplayRunner.INVALID_ARGUMENTS, HostScenarioRunner.execute(new String[] {"--output-dir"}));
    }

    @Test
    void aPassingEvaluationExitsZeroAndWritesBothReports() throws Exception {
        var passing = new HostScenarioEvaluation.Outcome(List.of(new Check("host.ok", true, "fine")),
                Map.of("host.fixtureVersion", HostScenarioFixtures.VERSION));

        assertEquals(0, HostScenarioRunner.execute(args(), () -> passing));
        String markdown = Files.readString(output.resolve("forge-host-scenarios.md"));
        assertTrue(markdown.contains(HostScenarioRunner.TITLE), markdown);
        assertTrue(Files.exists(output.resolve("forge-host-scenarios.json")));
    }

    @Test
    void aFailedCheckExitsOne() {
        var failing = new HostScenarioEvaluation.Outcome(List.of(new Check("host.broken", false, "broken")), Map.of());

        assertEquals(RoutingReplayRunner.CHECK_FAILED, HostScenarioRunner.execute(args(), () -> failing));
    }

    @Test
    void aThrowingEvaluationIsAHarnessFailure() {
        assertEquals(RoutingReplayRunner.HARNESS_FAILURE, HostScenarioRunner.execute(args(), () -> {
            throw new IllegalStateException("boom");
        }));
    }
}
