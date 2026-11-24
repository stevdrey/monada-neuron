package monada.neuron.evaluation.visual;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs one external {@code cwebp} binary with fixed arguments and no shell, to record the WebP baseline sizes of
 * contract Section 12. Any failure stops the caller: a missing binary, a non zero exit or an empty version line.
 */
final class CwebpTool {

    private final Path binary;

    CwebpTool(Path binary) {
        this.binary = binary;
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
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("command '" + printed + "' exited with code " + exit);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("command '" + printed + "' could not run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command '" + printed + "' was interrupted", e);
        }
    }
}
