package monada.neuron.evaluation.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Canonical text normalization of the corpus contract: strict UTF-8, {@code \n} line endings, Unicode NFC and
 * trimmed leading and trailing whitespace. Applied before hashing and before {@code remember}.
 *
 * <p>Fenced code blocks receive no treatment beyond this document-wide normalization, so their content is kept
 * byte-for-byte after it. Invalid UTF-8, a byte-order mark and control characters other than tab and line feed
 * are rejected rather than repaired, so a corrupt file is diagnosed instead of silently hashed.
 */
public final class CorpusNormalizer {

    /** Decodes strictly and normalizes; {@code origin} names the input in diagnostics. */
    public String normalize(byte[] bytes, String origin) {
        return normalize(decode(bytes, origin), origin);
    }

    /** Normalizes already decoded text; {@code origin} names the input in diagnostics. */
    public String normalize(String text, String origin) {
        var unified = text.replace("\r\n", "\n").replace('\r', '\n');
        var normalized = Normalizer.normalize(unified, Normalizer.Form.NFC).strip();
        for (var i = 0; i < normalized.length(); i++) {
            var c = normalized.charAt(i);
            if (c == '\uFEFF') {
                throw new CorpusValidationException(origin, "byte-order mark or U+FEFF is not allowed");
            }
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                throw new CorpusValidationException(origin,
                        String.format(Locale.ROOT, "control character U+%04X at char %d is not allowed", (int) c, i));
            }
        }
        return normalized;
    }

    /** Returns whether {@code text} is already in normalized form. */
    public boolean isNormalized(String text) {
        try {
            return normalize(text, "text").equals(text);
        } catch (CorpusValidationException e) {
            return false;
        }
    }

    private String decode(byte[] bytes, String origin) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new CorpusValidationException(origin, "invalid UTF-8 (" + e.getMessage() + ")", e);
        }
    }
}
