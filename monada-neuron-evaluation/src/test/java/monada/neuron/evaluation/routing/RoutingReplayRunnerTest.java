package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingReplayRunnerTest {

    @TempDir
    Path directory;

    @Test
    void aHarnessFailureIsNeverReportedAsAFailedCheck() throws IOException {
        Path notADirectory = Files.writeString(directory.resolve("file"), "x");

        assertAll(
                // every check passed, but the report cannot be written
                () -> assertEquals(RoutingReplayRunner.HARNESS_FAILURE, RoutingReplayRunner.report(
                        () -> report(true), notADirectory.resolve("sub"), "r", "failed")),
                () -> assertEquals(RoutingReplayRunner.HARNESS_FAILURE, RoutingReplayRunner.report(() -> {
                    throw new IllegalStateException("crash");
                }, directory, "r", "failed")),
                () -> assertEquals(RoutingReplayRunner.CHECK_FAILED,
                        RoutingReplayRunner.report(() -> report(false), directory, "r", "failed")),
                () -> assertEquals(0, RoutingReplayRunner.report(() -> report(true), directory, "r", "failed")),
                () -> assertEquals(RoutingReplayRunner.INVALID_ARGUMENTS,
                        RoutingReplayRunner.execute(new String[] {"--unknown"})),
                () -> assertEquals(RoutingReplayRunner.UNAVAILABLE,
                        RoutingReplayRunner.execute(new String[] {"--store-arm"})));
    }

    private static RoutingReplayReport report(boolean passed) {
        var evaluation = new EvaluationReport(Instant.EPOCH, EnvironmentMetadata.current(),
                new RoutingReplayEvaluation(true).runConfiguration(), List.of(), RoutingReplayReport.TITLE);
        return new RoutingReplayReport(RoutingReplayReport.TITLE, evaluation,
                List.<EvaluationCheck>of(new RoutingReplayEvaluation.Check("stub", passed, "stub")), Map.of());
    }
}
