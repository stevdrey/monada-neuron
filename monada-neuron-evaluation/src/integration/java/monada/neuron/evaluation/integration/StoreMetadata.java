package monada.neuron.evaluation.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Best-effort version and configuration metadata for reports; never influences a verdict. */
public final class StoreMetadata {

    private static final Pattern FIELD =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([\\w.\\-]+))");
    private static final Set<String> REPORTED = Set.of(
            "version", "dimensions", "encoder", "encoderVersion", "normalizer", "normalizerVersion",
            "weightingStrategy", "vectorScalarType", "vectorFormatVersion");

    private StoreMetadata() {
    }

    /** Extracts the compatibility-relevant manifest fields, sorted by name. */
    public static Map<String, String> manifestFields(String manifestJson) {
        var fields = new TreeMap<String, String>();
        var matcher = FIELD.matcher(manifestJson);
        while (matcher.find()) {
            if (REPORTED.contains(matcher.group(1))) {
                fields.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            }
        }
        return fields;
    }

    /**
     * Returns the string or scalar value of top-level manifest field {@code name}, read without opening the Store, or
     * empty when the manifest has no such field.
     */
    public static Optional<String> manifestField(String manifestJson, String name) {
        var matcher = FIELD.matcher(manifestJson);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return Optional.of(matcher.group(2) != null ? unescape(matcher.group(2)) : matcher.group(3));
            }
        }
        return Optional.empty();
    }

    /** Decodes the JSON string escapes of {@code body}: {@code \" \\ \/ \b \f \n \r \t} and {@code \\uXXXX}. */
    static String unescape(String body) {
        var text = new StringBuilder(body.length());
        for (var i = 0; i < body.length(); i++) {
            var c = body.charAt(i);
            if (c != '\\' || i + 1 == body.length()) {
                text.append(c);
                continue;
            }
            var escaped = body.charAt(++i);
            switch (escaped) {
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> {
                    if (i + 4 >= body.length()) {
                        throw new IllegalArgumentException("truncated \\u escape in manifest string " + body);
                    }
                    text.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> text.append(escaped);
            }
        }
        return text.toString();
    }

    /** Returns the short git commit, suffixed {@code +dirty} for uncommitted changes, or {@code unknown}. */
    public static String gitCommit(Path checkout) {
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
    private static String git(Path checkout, String... arguments) {
        Path capture = null;
        try {
            capture = Files.createTempFile("monada-neuron-git-", ".txt");
            var command = new ArrayList<String>(List.of("git", "-C", checkout.toString()));
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
                    // Temporary metadata capture only.
                }
            }
        }
    }
}
