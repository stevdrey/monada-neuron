package monada.neuron.resonance.adapter.experience;

import com.monada.core.execution.ExperienceRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical text of a Store {@link ExperienceRef} without its revision, so every revision of one event shares one
 * reference. Each part is written as {@code <code-point-count>:<text>}, so no token can forge a delimiter; an absent
 * attempt is {@code -}. The text names the exact Store event and is kept as is whenever it fits the neutral
 * 512-code-point limit; otherwise (five identifiers can reach 640 code points) it is replaced by
 * {@code xr1h|<SHA-256 hex of the exact text>}, which is deterministic and still identifies that exact event.
 */
final class ExperienceRefs {

    private static final int MAX_CODE_POINTS = 512;

    private ExperienceRefs() {
    }

    static String format(ExperienceRef ref) {
        var out = new StringBuilder(96);
        out.append("xr1");
        part(out, ref.scope().value());
        part(out, ref.task().value());
        part(out, ref.execution().value());
        ref.attempt().ifPresentOrElse(a -> part(out, a.value()), () -> out.append("|-"));
        part(out, ref.eventId().value());
        String exact = out.toString();
        if (exact.codePointCount(0, exact.length()) <= MAX_CODE_POINTS) {
            return exact;
        }
        return "xr1h|" + digest(exact);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static void part(StringBuilder out, String value) {
        out.append('|').append(value.codePointCount(0, value.length())).append(':').append(value);
    }
}
