package monada.neuron.evaluation.integration;

import monada.neuron.evaluation.metrics.HostProbes;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
        return new HostProbes().gitCommit(checkout);
    }
}
