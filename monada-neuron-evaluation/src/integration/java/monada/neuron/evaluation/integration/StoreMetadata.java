package monada.neuron.evaluation.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Best-effort version and configuration metadata for reports; never influences a verdict. */
final class StoreMetadata {

    private static final Pattern FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|([\\w.\\-]+))");
    private static final Set<String> REPORTED = Set.of(
            "version", "dimensions", "encoder", "encoderVersion", "normalizer", "normalizerVersion",
            "weightingStrategy", "vectorScalarType", "vectorFormatVersion");

    private StoreMetadata() {
    }

    /** Extracts the compatibility-relevant manifest fields, sorted by name. */
    static Map<String, String> manifestFields(String manifestJson) {
        var fields = new TreeMap<String, String>();
        var matcher = FIELD.matcher(manifestJson);
        while (matcher.find()) {
            if (REPORTED.contains(matcher.group(1))) {
                fields.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            }
        }
        return fields;
    }

    /** Returns the short git commit of a checkout directory, or {@code unknown}. */
    static String gitCommit(Path checkout) {
        if (checkout == null || !Files.isDirectory(checkout)) {
            return "unknown";
        }
        try {
            var process = new ProcessBuilder("git", "-C", checkout.toString(), "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes()).trim();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0 || output.isBlank()) {
                return "unknown";
            }
            return output;
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
