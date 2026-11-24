package monada.neuron.evaluation.visual;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one external {@code cwebp} binary with fixed arguments and no shell, to record the WebP baseline sizes of
 * contract Section 12. Any failure stops the caller: a missing binary, a non zero exit, an empty version line or a
 * call that does not finish within the time limit, after which the process is killed.
 */
final class CwebpTool {

    private static final Duration LIMIT = Duration.ofSeconds(60);

    private final Path binary;
    private final Duration limit;

    CwebpTool(Path binary) {
        this(binary, LIMIT);
    }

    /** A tool whose every call must finish within {@code limit}. */
    CwebpTool(Path binary, Duration limit) {
        this.binary = binary;
        this.limit = limit;
    }

    /** The first line {@code cwebp -version} prints. */
    String version() {
        String output = run(List.of(binary.toString(), "-version"));
        String first = output.lines().findFirst().orElse("").strip();
        if (first.isEmpty()) {
            throw new IllegalStateException("command '" + binary + " -version' printed no version line");
        }
        return first;
    }

    /** Encodes with {@code -lossless -exact} and returns the size of the file written. */
    long lossless(Path png, Path webp) {
        return encode(List.of(binary.toString(), "-quiet", "-lossless", "-exact", png.toString(), "-o",
                webp.toString()), webp);
    }

    /** Encodes with {@code -q 75 -exact} and returns the size of the file written. */
    long q75(Path png, Path webp) {
        return encode(List.of(binary.toString(), "-quiet", "-q", "75", "-exact", png.toString(), "-o",
                webp.toString()), webp);
    }

    private long encode(List<String> command, Path webp) {
        run(command);
        try {
            return Files.size(webp);
        } catch (IOException e) {
            throw new IllegalStateException("command '" + String.join(" ", command) + "' wrote no file", e);
        }
    }

    private String run(List<String> command) {
        String printed = String.join(" ", command);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            // Read on another thread: a read on this one would block for as long as a hung process keeps its output
            // open, and the time limit would never be checked.
            var reading = new FutureTask<>(() -> process.getInputStream().readAllBytes());
            Thread.ofVirtual().start(reading);
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("command '" + printed + "' did not finish within " + limit.toMillis()
                        + " ms");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IllegalStateException("command '" + printed + "' exited with code " + exit);
            }
            return new String(reading.get(limit.toMillis(), TimeUnit.MILLISECONDS), StandardCharsets.UTF_8);
        } catch (TimeoutException e) {
            throw new IllegalStateException("command '" + printed + "' kept its output open for more than "
                    + limit.toMillis() + " ms after it exited", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("command '" + printed + "' could not run: " + e.getCause().getMessage(),
                    e.getCause());
        } catch (IOException e) {
            throw new IllegalStateException("command '" + printed + "' could not run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command '" + printed + "' was interrupted", e);
        }
    }
}
