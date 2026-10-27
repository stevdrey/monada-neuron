package monada.neuron.evaluation.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort host probes shared by every report that records the CPU model or a checkout's commit, beyond what
 * {@link EnvironmentMetadata} captures. A value that cannot be read is reported as {@code unknown}; a probe never
 * fails a run or influences a verdict.
 */
public final class HostProbes {

    private static final int MAX_CPUINFO_BYTES = 256 * 1024;
    private static final long GIT_TIMEOUT_SECONDS = 5;

    /** Creates the probes. */
    public HostProbes() {
    }

    /**
     * Returns the first non-empty {@code model name} of a Linux CPU information file such as {@code /proc/cpuinfo},
     * or {@code unknown (<reason>)}.
     */
    public String cpuModel(Path cpuInfo) {
        try (var input = Files.newInputStream(cpuInfo)) {
            var text = new String(input.readNBytes(MAX_CPUINFO_BYTES), StandardCharsets.UTF_8);
            return text.lines()
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .filter(model -> !model.isEmpty())
                    .findFirst()
                    .orElse("unknown (no model name in " + cpuInfo + ")");
        } catch (IOException | RuntimeException e) {
            return "unknown (" + cpuInfo + " unreadable)";
        }
    }

    /**
     * Returns the short git commit of {@code checkout}, suffixed {@code +dirty} for uncommitted changes, or
     * {@code unknown} when it is {@code null}, not a directory, or git fails.
     */
    public String gitCommit(Path checkout) {
        if (checkout == null || !Files.isDirectory(checkout)) {
            return "unknown";
        }
        var commit = git(checkout, "rev-parse", "--short", "HEAD");
        var status = git(checkout, "status", "--porcelain");
        if (commit == null || commit.isBlank() || status == null) {
            return "unknown";
        }
        return status.isBlank() ? commit : commit + "+dirty";
    }

    /** Runs git with a bounded wait and returns trimmed output, or {@code null} on any failure. */
    private String git(Path checkout, String... arguments) {
        Path capture = null;
        try {
            capture = Files.createTempFile("monada-neuron-git-", ".txt");
            var command = new ArrayList<String>(List.of("git", "-C", checkout.toString()));
            command.addAll(List.of(arguments));
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(capture.toFile())
                    .start();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? Files.readString(capture).trim() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (capture != null) {
                try {
                    Files.deleteIfExists(capture);
                } catch (IOException ignored) {
                    // Temporary capture only: a leftover file does not change the report.
                }
            }
        }
    }
}
