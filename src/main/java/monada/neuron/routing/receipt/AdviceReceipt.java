package monada.neuron.routing.receipt;

import monada.neuron.routing.RoutingDecision;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The canonical {@value #FORMAT} encoding of one {@link RoutingDecision} and its SHA-256 digest, so a host can keep
 * what Neuron advised and later compare it byte for byte with a replay or with what it ran.
 *
 * <p>A receipt is a canonical copy of advice for byte comparison only: it is not proof that anything ran or was
 * accepted, not authentication, not a signature and not a routing ledger digest.
 *
 * <p>The encoding covers every component of the decision, in declaration order, as length prefixed big endian fields
 * after the tag {@value #FORMAT}; the format spec {@code docs/specs/routing-advice-receipt-v1.md} is normative. One
 * decision has exactly one encoding, so two receipts are equal exactly when their decisions are equal, and
 * {@link #decode} accepts nothing but that encoding. The digest covers the whole encoding, tag included, which keeps
 * it apart from any other SHA-256 over Neuron data. Unlike the {@code routing-ledger-digest/1} of ADR 0031, which sums
 * the hashes of a set of applied feedback revisions, a receipt hashes one piece of advice and says nothing about
 * feedback, credit or what the host did with the advice. SHA-256 detects change; it does not prove who wrote the
 * bytes.
 *
 * <p>The decision's opaque tokens may carry sensitive host data, so {@link #toString} shows only format, size and
 * digest. Instances are immutable and safe to share; equality compares the encoding.
 */
public final class AdviceReceipt {

    /** Tag and version of the byte format, written first in every encoding. */
    public static final String FORMAT = "routing-advice-receipt/1";

    /** Largest encoding, in bytes, that {@link #of} produces and {@link #decode} reads. */
    public static final int MAX_ENCODED_BYTES = 65_536;

    private static final HexFormat HEX = HexFormat.of();

    private final RoutingDecision decision;
    private final byte[] bytes;
    private final String sha256;
    private final int hash;

    private AdviceReceipt(RoutingDecision decision, byte[] bytes) {
        this.decision = decision;
        this.bytes = bytes;
        this.sha256 = HEX.formatHex(newDigest().digest(bytes));
        this.hash = Arrays.hashCode(bytes);
    }

    /**
     * The receipt of a decision of any variant. A pure function of the decision, so class-level. {@code O(size)} time.
     *
     * @throws IllegalStateException if the encoding exceeds {@link #MAX_ENCODED_BYTES}, which today's decision bounds
     *         rule out
     */
    public static AdviceReceipt of(RoutingDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        byte[] encoded = ReceiptCodec.encode(decision);
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalStateException("encoding exceeds MAX_ENCODED_BYTES");
        }
        return new AdviceReceipt(decision, encoded);
    }

    /**
     * Reads a receipt back strictly and never modifies the array. Every fault the format or the routing records can
     * raise comes back as a {@link DecodeResult.Rejected}: oversize input, another major version, a structural fault,
     * values a record constructor rejects ({@code IllegalArgumentException} or {@code NullPointerException}), or a
     * non canonical encoding. Any other exception would be a codec bug and is left to surface rather than masked as a
     * reason. {@code O(size)} time.
     */
    public static DecodeResult decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        return ReceiptCodec.decode(bytes);
    }

    /** A receipt over bytes the decoder already proved canonical for this decision; takes ownership of the array. */
    static AdviceReceipt decoded(RoutingDecision decision, byte[] canonical) {
        return new AdviceReceipt(decision, canonical);
    }

    /** The decision: the same instance passed to {@link #of}, or an equal one rebuilt by {@link #decode}. */
    public RoutingDecision decision() {
        return decision;
    }

    /** A new copy of the encoding on every call. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Length of the encoding, 1 to {@link #MAX_ENCODED_BYTES}. */
    public int size() {
        return bytes.length;
    }

    /** SHA-256 of {@link #bytes()}, as 64 lowercase hexadecimal digits. */
    public String sha256() {
        return sha256;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AdviceReceipt receipt && Arrays.equals(bytes, receipt.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "AdviceReceipt[format=" + FORMAT + ", size=" + bytes.length + ", sha256=" + sha256 + "]";
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
