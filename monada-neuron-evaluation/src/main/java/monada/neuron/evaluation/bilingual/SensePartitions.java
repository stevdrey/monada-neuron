package monada.neuron.evaluation.bilingual;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * Family and occurrence ids of {@code bilingual-sense/1} with the v2 clarifications (R13, R14), and the split of a
 * family. Assigning families to splits is offline tooling (step 3). Pure functions over ids; no label is read.
 */
final class SensePartitions {

    /** The split of a family. */
    enum Split {
        /** Guideline calibration only; never in any other set. */
        CALIBRATION,
        /** Fitting and pilot. */
        TRAINING,
        /** Every selection: windows, grid points, thresholds. */
        VALIDATION,
        /** Sealed; read by systems once, at step 8. */
        HELD_OUT
    }

    /** Byte-wise UTF-8 order, which is code-point order (unlike {@link String#compareTo}, UTF-16 order). */
    static final Comparator<String> UTF8_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    /** Returns the family id: lowercase hex {@code SHA-256(UTF-8(k_min))}, {@code k_min} in byte-wise UTF-8 order. */
    String familyId(Collection<String> sentenceKeys) {
        String min = sentenceKeys.stream().min(UTF8_ORDER)
                .orElseThrow(() -> new IllegalArgumentException("a family needs at least one sentence"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(min.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Returns the occurrence id {@code direction|lemma|sentenceKey|start|end} (v2 R14). */
    String occurrenceId(Direction direction, String lemma, String sentenceKey, int start, int end) {
        return direction.label() + "|" + lemma + "|" + sentenceKey + "|" + start + "|" + end;
    }
}
