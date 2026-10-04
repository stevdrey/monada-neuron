package monada.neuron.evaluation.metrics;

import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared report format. The golden files were captured from the report renderers that existed
 * before they were unified, so the refactoring is checked to change no byte of the output.
 */
class CheckedEvaluationReportTest {

    private record Check(String name, boolean passed, String detail) implements EvaluationCheck {
    }

    private static final String TITLE = "Monada Neuron Cross-Cycle Feedback Loop Report";

    private static CheckedEvaluationReport report(String heading, List<Check> checks) {
        var environment = new EnvironmentMetadata("27", "Vendor \"Q\"", "VM", "Linux", "amd64", 4,
                1L << 30, 1L << 29, List.of("-Xmx1g", "--add-modules=x"), List.of("G1 Young", "G1 Old"));
        var run = new RunConfiguration(42L, true, 1, 3, "ops | per \"iteration\"");
        var evaluation = new EvaluationReport(
                Instant.parse("2026-01-01T00:00:00Z"), environment, run, List.of(), "Title \"x\"");
        var metadata = new TreeMap<String, String>();
        metadata.put("b.key", "value | with pipe\nand newline");
        metadata.put("a.key", "quote \" and \\ backslash\ttab");
        return new CheckedEvaluationReport(TITLE, heading, evaluation, checks, metadata);
    }

    private static final List<Check> CHECKS = List.of(
            new Check("c.one", true, "detail | pipe"),
            new Check("c.two", false, "line1\nline2 \"q\""));

    private static String golden(String name) throws IOException {
        try (var stream = CheckedEvaluationReportTest.class.getResourceAsStream("/golden/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void rendersTheGoldenJsonAndMarkdownExactly() throws IOException {
        var report = report("Feedback Loop Metadata", CHECKS);

        assertAll(
                () -> assertEquals(golden("checked-evaluation-report.json"), report.toJson()),
                () -> assertEquals(golden("checked-evaluation-report.md"), report.toMarkdown()));
    }

    @Test
    void theMetadataHeadingIsTheOnlyDifferenceBetweenTheTwoEvaluationFlavours() throws IOException {
        var integration = report("Integration Metadata", CHECKS);

        assertAll(
                () -> assertEquals(
                        golden("checked-evaluation-report.md").replace("## Feedback Loop Metadata", "## Integration Metadata"),
                        integration.toMarkdown()),
                () -> assertEquals(golden("checked-evaluation-report.json"), integration.toJson()));
    }

    @Test
    void passesOnlyWhenEveryCheckPassesAndSortsMetadataByKey() {
        var passing = report("Metadata", List.of(new Check("ok", true, "fine")));

        assertAll(
                () -> assertTrue(passing.allPassed()),
                () -> assertFalse(report("Metadata", CHECKS).allPassed()),
                () -> assertEquals(List.of("a.key", "b.key"), List.copyOf(passing.metadata().keySet())),
                () -> assertThrows(UnsupportedOperationException.class, () -> passing.metadata().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> passing.checks().clear()));
    }

    @Test
    void rejectsNullComponents() {
        var base = report("Metadata", CHECKS);

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new CheckedEvaluationReport(
                        null, "h", base.evaluation(), CHECKS, Map.of())),
                () -> assertThrows(NullPointerException.class, () -> new CheckedEvaluationReport(
                        TITLE, null, base.evaluation(), CHECKS, Map.of())),
                () -> assertThrows(NullPointerException.class, () -> new CheckedEvaluationReport(
                        TITLE, "h", null, CHECKS, Map.of())),
                () -> assertThrows(NullPointerException.class, () -> new CheckedEvaluationReport(
                        TITLE, "h", base.evaluation(), null, Map.of())),
                () -> assertThrows(NullPointerException.class, () -> new CheckedEvaluationReport(
                        TITLE, "h", base.evaluation(), CHECKS, null)));
    }
}
