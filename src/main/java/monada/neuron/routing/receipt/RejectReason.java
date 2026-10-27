package monada.neuron.routing.receipt;

/**
 * Why {@link AdviceReceipt#decode} refused a byte string. Declaration order is not the check order: the format spec
 * fixes that precedence, and the first failure wins.
 *
 * <p>A receipt is a canonical copy of advice for byte comparison only: it is not proof that anything ran or was
 * accepted, not authentication, not a signature and not a routing ledger digest.
 */
public enum RejectReason {
    /** The input is longer than {@link AdviceReceipt#MAX_ENCODED_BYTES}; no byte was read. */
    OVERSIZE,
    /** The tag is well formed but names a major version other than {@code 1}. */
    UNSUPPORTED_VERSION,
    /** The input is not structurally a v1 receipt: a bad tag, a truncated or out of range field, or trailing bytes. */
    MALFORMED,
    /** The decision builds, but its canonical encoding differs from the input. */
    NON_CANONICAL,
    /** Every field is well formed, but the routing records reject the values. */
    INVALID_DECISION
}
