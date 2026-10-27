/**
 * Canonical, versioned routing advice receipts for the opt-in Forge routing extension (issue #97, ADR 0039).
 *
 * <p>{@link monada.neuron.routing.receipt.AdviceReceipt#of} turns any {@code RoutingDecision} into one canonical byte
 * string of format {@code routing-advice-receipt/1} and its SHA-256 digest, so a host can keep what Neuron advised and
 * later compare it, byte for byte, with a replay or with what it ran.
 * {@link monada.neuron.routing.receipt.AdviceReceipt#decode} turns those bytes back into the same decision, or returns
 * a coded {@link monada.neuron.routing.receipt.RejectReason}; it never repairs input.
 *
 * <p>A receipt is a byte comparator only. It is not proof that anything ran or was accepted, not authentication, not
 * a signature, and not the order independent {@code routing-ledger-digest/1} of ADR 0031, which digests a set of
 * applied feedback revisions rather than one piece of advice. SHA-256 here detects change; anyone can build a valid
 * receipt for any decision. Receipts hold opaque host tokens, so Neuron never logs, persists or transports them: the
 * host decides whether and where to keep them. The byte format is normative in
 * {@code docs/specs/routing-advice-receipt-v1.md}.
 */
package monada.neuron.routing.receipt;
