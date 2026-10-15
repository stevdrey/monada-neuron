package monada.neuron.routing.adaptation;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.EntryBasis;
import monada.neuron.routing.feedback.FeedbackIdentity;
import monada.neuron.routing.feedback.PeerRevision;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Digest {@value #FORMAT} of a set of applied-revision ledger entries: the number of entries and the sum, modulo
 * {@code 2^256}, of the SHA-256 of each entry's canonical encoding.
 *
 * <p>The sum makes the digest independent of entry order and lets it be folded: the digest of a ledger is the digest of
 * its compacted part plus the digest of its live part, so a compacted {@link RoutingStateStore} reports the same digest
 * as an uncompacted one with the same history, and a {@link RoutingStateCheckpoint}'s digest compares directly with a
 * from-scratch rebuild's. It is an integrity and equivalence check over caller-owned data, <b>not</b> an
 * authentication boundary: a modular sum of hashes is weaker against a deliberate adversary than a chained hash.
 *
 * <p>Canonical entry encoding: UTF-8 of the tag {@value #ENTRY_TAG}, then every component that
 * {@link AppliedRevision#equals} compares, in declaration order, <b>excluding {@code cutoff}</b> (the snapshot a claim
 * was read at is not part of it). A string is a 4-byte big-endian length and its UTF-8 bytes; a {@code long} is 8
 * bytes big-endian; an {@code int} 4 bytes; a {@code double} its {@link Double#doubleToLongBits} as a {@code long}; an
 * enum constant its name as a string; a {@link Feature} or {@link java.util.Optional} one presence byte ({@code 0} or
 * {@code 1}) and then the value; a {@link UUID} its most and least significant bits; a list its size as an {@code int}
 * and then its elements; an {@link EntryBasis} a tag byte ({@code 1} derived, {@code 2} marker) and then its components.
 * Nested records ({@link FeedbackIdentity}, {@link DecisionRef}, {@code RouteKey}, {@link CohortKey},
 * {@code CohortBinding}, {@link PeerRevision}) are encoded component by component in declaration order. No delimiter
 * is used, so distinct entries cannot collide on concatenation.
 *
 * @param entries number of digested entries, non-negative
 * @param sum lowercase hexadecimal of the 32-byte sum, big-endian
 */
public record LedgerDigest(long entries, String sum) {

    /** Version of the digest format. */
    public static final String FORMAT = "routing-ledger-digest/1";

    /** Tag that versions the entry encoding. */
    static final String ENTRY_TAG = "forge-routing/1/ledger-entry";

    /** The digest of no entries. */
    public static final LedgerDigest EMPTY = new LedgerDigest(0, "0".repeat(64));

    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(256);
    private static final HexFormat HEX = HexFormat.of();

    /** Validates the count and the sum's shape. */
    public LedgerDigest {
        if (entries < 0) {
            throw new IllegalArgumentException("entries must be non-negative, got: " + entries);
        }
        Objects.requireNonNull(sum, "sum must not be null");
        if (sum.length() != 64 || !sum.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("sum must be 64 lowercase hexadecimal digits, got: " + sum);
        }
    }

    /**
     * The digest of the given entries. A pure function of the entries, so class-level. {@code O(L)} time.
     *
     * @param ledger entries in any order
     */
    public static LedgerDigest of(Collection<AppliedRevision> ledger) {
        Objects.requireNonNull(ledger, "ledger must not be null");
        MessageDigest sha = sha256();
        BigInteger total = BigInteger.ZERO;
        for (AppliedRevision entry : ledger) {
            total = total.add(new BigInteger(1, sha.digest(encode(Objects.requireNonNull(entry,
                    "ledger must not contain null")))));
        }
        return new LedgerDigest(ledger.size(), hex(total.mod(MODULUS)));
    }

    /** The digest of the union of two disjoint entry sets. */
    public LedgerDigest plus(LedgerDigest other) {
        Objects.requireNonNull(other, "other must not be null");
        return new LedgerDigest(Math.addExact(entries, other.entries),
                hex(value().add(other.value()).mod(MODULUS)));
    }

    /**
     * The digest of this set without a subset of it.
     *
     * @throws IllegalArgumentException if the subset has more entries than this set
     */
    public LedgerDigest minus(LedgerDigest subset) {
        Objects.requireNonNull(subset, "subset must not be null");
        if (subset.entries > entries) {
            throw new IllegalArgumentException("cannot remove " + subset.entries + " entries from " + entries);
        }
        return new LedgerDigest(entries - subset.entries, hex(value().subtract(subset.value()).mod(MODULUS)));
    }

    private BigInteger value() {
        return new BigInteger(sum, 16);
    }

    private static String hex(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, fixed, 32 - length, length);
        return HEX.formatHex(fixed);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    /** The canonical encoding of one entry; package-private so tests can pin it. */
    static byte[] encode(AppliedRevision entry) {
        var out = new Encoder();
        out.raw(ENTRY_TAG.getBytes(StandardCharsets.UTF_8));
        FeedbackIdentity identity = entry.identity();
        out.text(identity.experienceRef());
        out.int64(identity.revision());
        out.int64(identity.ledgerSequence());
        DecisionRef decision = identity.decisionRef();
        out.text(decision.scopeId());
        out.text(decision.taskId());
        out.text(decision.executionId());
        out.text(decision.attemptId());
        out.text(decision.stageId());
        out.int64(decision.requestOrdinal());
        out.text(identity.route().routeId());
        out.int64(identity.route().routeVersion());
        out.text(identity.mappingVersion());
        out.text(identity.evaluationPolicyId());
        out.text(identity.evaluationPolicyVersion());
        out.text(entry.routingPolicyId());
        out.text(entry.routingPolicyVersion());
        if (entry.supersedesSequence() instanceof Feature.Known<Long> known) {
            out.int8(1);
            out.int64(known.value());
        } else {
            out.int8(0);
        }
        out.text(entry.catalogVersion());
        out.int8(entry.targetNodeId().isPresent() ? 1 : 0);
        entry.targetNodeId().ifPresent(id -> {
            out.int64(id.getMostSignificantBits());
            out.int64(id.getLeastSignificantBits());
        });
        out.int8(entry.costPeer().isPresent() ? 1 : 0);
        entry.costPeer().ifPresent(peer -> {
            out.text(peer.experienceRef());
            out.int64(peer.revision());
            out.int64(peer.ledgerSequence());
        });
        switch (entry.basis()) {
            case EntryBasis.Derived derived -> {
                out.int8(1);
                CohortKey key = derived.cohortKey();
                out.text(key.scopeId());
                out.text(key.stageKind());
                out.text(key.cohortBucket());
                out.text(key.route().routeId());
                out.int64(key.route().routeVersion());
                out.text(derived.rule().name());
                out.text(derived.acceptance().name());
                out.names(derived.reasons());
                out.names(derived.provenFailures());
            }
            case EntryBasis.NoUpdateMarker marker -> {
                out.int8(2);
                out.text(marker.reason().name());
                out.text(marker.cohortBinding().cohortBucket());
                out.text(marker.cohortBinding().bucketMappingVersion());
                out.text(marker.cohortBinding().mappingVersion());
                out.text(marker.cohortBinding().featureSchemaVersion());
            }
        }
        out.text(entry.disposition().name());
        out.int64(Double.doubleToLongBits(entry.score()));
        return out.bytes();
    }

    /** Big-endian, length-prefixed byte writer. */
    private static final class Encoder {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        private final ByteBuffer scratch = ByteBuffer.allocate(Long.BYTES);

        void raw(byte[] value) {
            bytes.writeBytes(value);
        }

        void int8(int value) {
            bytes.write(value);
        }

        void int32(int value) {
            bytes.write(scratch.clear().putInt(value).array(), 0, Integer.BYTES);
        }

        void int64(long value) {
            bytes.write(scratch.clear().putLong(value).array(), 0, Long.BYTES);
        }

        void text(String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            int32(utf8.length);
            bytes.writeBytes(utf8);
        }

        void names(List<? extends Enum<?>> values) {
            int32(values.size());
            values.forEach(value -> text(value.name()));
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
