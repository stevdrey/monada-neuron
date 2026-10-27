package monada.neuron.evaluation.wave;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Report fields of contract section 14.4 that {@code EnvironmentMetadata} does not cover: the CPU model and the
 * Neuron commit. Every probe is best effort and reports {@code unknown} instead of failing the run.
 */
final class WaveRunEnvironment {

    private static final int MAX_CPUINFO_BYTES = 1 << 20;

    /** Returns the first {@code model name} of {@code /proc/cpuinfo}, or {@code unknown}. */
    String cpuModel() {
        var cpuInfo = Path.of("/proc/cpuinfo");
        try (var input = Files.newInputStream(cpuInfo)) {
            var text = new String(input.readNBytes(MAX_CPUINFO_BYTES), StandardCharsets.UTF_8);
            return text.lines()
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .findFirst()
                    .orElse("unknown");
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    /** Returns the short commit of the checkout holding {@code directory}, with {@code +dirty} when uncommitted. */
    String commit(Path directory) {
        var commit = git(directory, "rev-parse", "--short", "HEAD");
        var status = git(directory, "status", "--porcelain");
        if (commit == null || commit.isBlank() || status == null) {
            return "unknown";
        }
        return status.isBlank() ? commit : commit + "+dirty";
    }

    /** Runs git with a bounded wait and returns trimmed output, or {@code null} on any failure. */
    private String git(Path directory, String... arguments) {
        Path capture = null;
        try {
            capture = Files.createTempFile("monada-neuron-git-", ".txt");
            var command = new ArrayList<String>(List.of("git", "-C", directory.toString()));
            command.addAll(List.of(arguments));
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(capture.toFile())
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
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
                    // Best effort: a leftover temporary file does not change the report.
                }
            }
        }
    }
}
