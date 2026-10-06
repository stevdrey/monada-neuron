package monada.neuron.resonance.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Derives the opaque, stable reference exposed for a recalled atom: {@code rs-} followed by the
 * first 32 hex characters of the SHA-256 of the atom id, so store identifiers never leave the adapter.
 */
public final class OpaqueReference {

    private static final String REFERENCE_PREFIX = "rs-";
    private static final int REFERENCE_HEX_LENGTH = 32;

    /** Returns the deterministic reference for the atom id. */
    public String of(String atomId) {
        Objects.requireNonNull(atomId, "atomId must not be null");
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(atomId.getBytes(StandardCharsets.UTF_8));
            return REFERENCE_PREFIX + HexFormat.of().formatHex(digest).substring(0, REFERENCE_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
