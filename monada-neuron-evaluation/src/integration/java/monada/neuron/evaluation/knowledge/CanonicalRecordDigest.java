package monada.neuron.evaluation.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Streaming SHA-256 over the protocol's canonical byte serialization of dataset hashes ({@code corpusHash},
 * later {@code queryHash}): every field is its UTF-8 bytes preceded by the byte length as a 4-byte big-endian
 * unsigned integer, an absent value is the length {@code 0xFFFFFFFF} with no bytes, and integers are encoded as
 * their decimal string. Formatting, file layout or map order therefore cannot change a hash.
 */
final class CanonicalRecordDigest {

    private static final int ABSENT = 0xFFFFFFFF;

    private final MessageDigest digest = CorpusDigests.newDigest();
    private final ByteBuffer length = ByteBuffer.allocate(Integer.BYTES);

    /** Appends a present string field. */
    CanonicalRecordDigest field(String value) {
        if (value == null) {
            return absent();
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        appendLength(bytes.length);
        digest.update(bytes);
        return this;
    }

    /** Appends an integer field as its decimal string. */
    CanonicalRecordDigest field(long value) {
        return field(Long.toString(value));
    }

    /** Appends an absent field. */
    CanonicalRecordDigest absent() {
        appendLength(ABSENT);
        return this;
    }

    /** Completes the digest and returns it as lowercase hex; the instance must not be reused. */
    String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }

    private void appendLength(int value) {
        length.clear();
        length.putInt(value);
        digest.update(length.array());
    }
}
