package monada.neuron.routing.receipt;

import java.util.Objects;

/**
 * What {@link AdviceReceipt#decode} made of a byte string: exactly one of {@link Decoded} or {@link Rejected}.
 *
 * <p>A receipt is a canonical copy of advice for byte comparison only: it is not proof that anything ran or was
 * accepted, not authentication, not a signature and not a routing ledger digest.
 */
public sealed interface DecodeResult {

    /**
     * The bytes are the canonical encoding of a valid decision.
     *
     * <p>A receipt is a canonical copy of advice for byte comparison only: it is not proof that anything ran or was
     * accepted, not authentication, not a signature and not a routing ledger digest.
     *
     * @param receipt the receipt, equal to {@code AdviceReceipt.of(receipt.decision())}
     */
    record Decoded(AdviceReceipt receipt) implements DecodeResult {

        /** Requires a receipt. */
        public Decoded {
            Objects.requireNonNull(receipt, "receipt must not be null");
        }
    }

    /**
     * The bytes are not a canonical v1 receipt. Only the reason is kept: messages could echo host tokens.
     *
     * <p>A receipt is a canonical copy of advice for byte comparison only: it is not proof that anything ran or was
     * accepted, not authentication, not a signature and not a routing ledger digest.
     *
     * @param reason the first failure in the format's check order
     */
    record Rejected(RejectReason reason) implements DecodeResult {

        /** Requires a reason. */
        public Rejected {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
