package monada.neuron.evaluation.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Lowercase-hex SHA-256 digests used for corpus, document and chunk fingerprints. */
final class CorpusDigests {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private CorpusDigests() {
    }

    /** Returns the SHA-256 of the UTF-8 bytes of {@code text}. */
    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the SHA-256 of {@code bytes}. */
    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    /** Returns whether {@code value} is a lowercase-hex SHA-256 digest. */
    static boolean isSha256(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
