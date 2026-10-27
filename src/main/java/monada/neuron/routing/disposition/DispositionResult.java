package monada.neuron.routing.disposition;

import java.util.Objects;

/**
 * What a disposition factory made of a receipt and an expected reference: exactly one of {@link Bound} or
 * {@link Rejected}. A host mistake is a code, never an exception; only a null argument throws.
 *
 * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization and
 * not quality evidence, and it awards no credit.
 */
public sealed interface DispositionResult {

    /**
     * The receipt matched the expected reference and the variant and route rules held.
     *
     * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization
     * and not quality evidence, and it awards no credit.
     *
     * @param disposition the bound disposition; when a factory produced it, it {@code refersTo} the receipt it was built
     *         from (a hand built {@code Bound} can wrap any disposition)
     */
    record Bound(AdviceDisposition disposition) implements DispositionResult {

        /** Requires a disposition. */
        public Bound {
            Objects.requireNonNull(disposition, "disposition must not be null");
        }
    }

    /**
     * No disposition was bound. Only the code is kept: a message could echo host tokens. The simple name is shared
     * with {@code DecodeResult.Rejected}; a host importing both qualifies one.
     *
     * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization
     * and not quality evidence, and it awards no credit.
     *
     * @param reason the first failure in check order
     */
    record Rejected(DispositionRejection reason) implements DispositionResult {

        /** Requires a reason. */
        public Rejected {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
