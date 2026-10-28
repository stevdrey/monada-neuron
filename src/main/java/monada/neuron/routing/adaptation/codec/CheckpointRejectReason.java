package monada.neuron.routing.adaptation.codec;

/**
 * Why {@link CheckpointCodec#decode} refused a byte string. Declaration order is not the check order: the format spec
 * fixes that precedence, and the first failure wins.
 *
 * <p>The checksum proves the bytes are unchanged, not who wrote them: anyone can build a valid checksum for any
 * checkpoint.
 */
public enum CheckpointRejectReason {
    /** The input is longer than {@link CheckpointCodec#MAX_ENCODED_BYTES}; no byte was read. */
    OVERSIZE,
    /**
     * The input is not structurally a v1 checkpoint: a bad or cut off tag, fewer than 32 bytes after the tag, an out of
     * range length, count, presence or basis byte, invalid UTF-8, an unknown enum name, or bytes left before the
     * checksum.
     */
    MALFORMED,
    /** The tag is well formed but names a major version other than {@code 1}. */
    UNSUPPORTED_VERSION,
    /** The last 32 bytes are not the SHA-256 of the bytes before them. */
    CORRUPT,
    /** Every field is well formed, but the checkpoint records reject the values. */
    INVALID_CHECKPOINT,
    /** The checkpoint builds, but its canonical encoding differs from the input. */
    NON_CANONICAL
}
