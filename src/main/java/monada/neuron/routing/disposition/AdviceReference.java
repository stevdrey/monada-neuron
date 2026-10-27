package monada.neuron.routing.disposition;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.receipt.AdviceReceipt;

import java.util.Objects;
import java.util.Optional;

/**
 * The identity of one piece of advice as a disposition keeps it: the decision's {@link DecisionRef}, the SHA-256 digest
 * of its receipt, the kind of advice and, for a selection only, the advised route. It holds neither the receipt bytes
 * nor the decision; the digest already covers every decision component, and the ref is a cheap first comparison.
 *
 * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization and
 * not quality evidence, and it awards no credit.
 *
 * <p>{@link #toString} prints the kind, the request ordinal and the digest only, never a token.
 *
 * @param decisionRef identity of the decision, copied from its provenance
 * @param receiptSha256 {@link AdviceReceipt#sha256()} of the receipt: exactly 64 lowercase hexadecimal characters
 * @param kind the decision variant
 * @param advisedRoute the selected route, present exactly when {@code kind} is {@link AdviceKind#SELECTED}
 */
public record AdviceReference(
        DecisionRef decisionRef, String receiptSha256, AdviceKind kind, Optional<RouteKey> advisedRoute) {

    /** Length of a SHA-256 digest in hexadecimal characters. */
    private static final int DIGEST_LENGTH = 64;

    /** Validates the digest form and that the advised route is present exactly for a selection. */
    public AdviceReference {
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        Objects.requireNonNull(receiptSha256, "receiptSha256 must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(advisedRoute, "advisedRoute must not be null");
        requireDigest(receiptSha256);
        if (advisedRoute.isPresent() != (kind == AdviceKind.SELECTED)) {
            throw new IllegalArgumentException("advisedRoute is present exactly for SELECTED advice, got " + kind
                    + (advisedRoute.isPresent() ? " with" : " without") + " a route");
        }
    }

    /** The reference of a receipt of any variant. A pure copy of identity, so class-level. */
    public static AdviceReference of(AdviceReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        RoutingDecision decision = receipt.decision();
        DecisionRef ref = decision.provenance().decisionRef();
        return switch (decision) {
            case RoutingDecision.Selected selected ->
                    new AdviceReference(ref, receipt.sha256(), AdviceKind.SELECTED, Optional.of(selected.route()));
            case RoutingDecision.Abstain ignored ->
                    new AdviceReference(ref, receipt.sha256(), AdviceKind.ABSTAINED, Optional.empty());
            case RoutingDecision.NoEligibleRoute ignored ->
                    new AdviceReference(ref, receipt.sha256(), AdviceKind.NO_ELIGIBLE_ROUTE, Optional.empty());
        };
    }

    @Override
    public String toString() {
        return "AdviceReference[" + fields() + "]";
    }

    /** The printable fields a disposition shows: kind, ordinal and digest, never a token. */
    String fields() {
        return "kind=" + kind + ", requestOrdinal=" + decisionRef.requestOrdinal() + ", receiptSha256=" + receiptSha256;
    }

    private static void requireDigest(String digest) {
        if (digest.length() != DIGEST_LENGTH) {
            throw new IllegalArgumentException("receiptSha256 must be " + DIGEST_LENGTH
                    + " lowercase hexadecimal characters, got length " + digest.length());
        }
        for (int i = 0; i < DIGEST_LENGTH; i++) {
            char c = digest.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException("receiptSha256 must be " + DIGEST_LENGTH
                        + " lowercase hexadecimal characters, got a non hexadecimal character at " + i);
            }
        }
    }
}
