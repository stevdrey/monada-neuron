package monada.neuron.routing.adaptation.codec;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.adaptation.CheckpointCohort;
import monada.neuron.routing.adaptation.LedgerDigest;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.EntryBasis;
import monada.neuron.routing.feedback.FeedbackIdentity;
import monada.neuron.routing.feedback.NoUpdateReason;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checkpoints built directly from the records, and byte helpers that edit an encoding and seal it again. */
final class CheckpointFixtures {

    static final String SCOPE = "scope-demo";
    static final String SCHEMA = "features/1";
    static final String EVAL_ID = "forge-gates";
    static final String EVAL_VERSION = "1";
    static final String MAPPING = "adaptive-test/1";
    static final String DIGEST_SUM = "0123456789abcdef".repeat(4);
    /** A token with a 4 byte UTF-8 code point (U+1F600). */
    static final String EMOJI_BUCKET = "bucket-😀";
    static final String EMOJI_REF = "exp-😀";

    static final CohortKey KEY_A = new CohortKey(SCOPE, "implement", "UNKNOWN", new RouteKey("sub-a", 1));
    static final CohortKey KEY_B = new CohortKey(SCOPE, "implement", EMOJI_BUCKET, new RouteKey("sub-b", 2));

    private CheckpointFixtures() {
    }

    static RoutingStateCheckpoint empty() {
        return checkpoint(0, List.of(), LedgerDigest.EMPTY, List.of());
    }

    /**
     * Two cohorts and two markers carrying the edge values: {@code -0.0} as a channel and as a score, a 4 byte code
     * point, an absent and a present {@code supersedesSequence}, both retryable reasons, a support count of 0 and a
     * marker whose audit {@code cutoff} differs from its sequence.
     */
    static RoutingStateCheckpoint populated() {
        return checkpoint(20, cohorts(), new LedgerDigest(10, DIGEST_SUM), markers());
    }

    static List<CheckpointCohort> cohorts() {
        return List.of(
                new CheckpointCohort(KEY_A, 0.75, -0.0, 1.5, 2.0, 3),
                new CheckpointCohort(KEY_B, -0.25, 0.5, 0.0, 1.0, 0));
    }

    static List<AppliedRevision> markers() {
        return List.of(
                marker("exp-a", 0, 5, Feature.unknown(), NoUpdateReason.NO_TARGET_BINDING, 5, 0.0),
                marker(EMOJI_REF, 1, 9, Feature.known(4L), NoUpdateReason.PROVENANCE_MISMATCH, 12, -0.0));
    }

    static RoutingStateCheckpoint checkpoint(long cutoff, List<CheckpointCohort> cohorts, LedgerDigest digest,
            List<AppliedRevision> markers) {
        return new RoutingStateCheckpoint(SCOPE, SCHEMA, EVAL_ID, EVAL_VERSION, MAPPING, "policy-x", "3", cutoff,
                cohorts, digest, markers);
    }

    static AppliedRevision marker(String ref, long revision, long sequence, Feature<Long> supersedes,
            NoUpdateReason reason, long cutoff, double score) {
        return new AppliedRevision(
                new FeedbackIdentity(ref, revision, sequence,
                        new DecisionRef(SCOPE, "task-1", "exec-1", "attempt-1", "stage-1", 3),
                        new RouteKey("sub-a", 1), MAPPING, EVAL_ID, EVAL_VERSION),
                "policy-x", "3", supersedes, "catalog-7", cutoff, Optional.empty(), Optional.empty(),
                new EntryBasis.NoUpdateMarker(reason, new CohortBinding("UNKNOWN", "buckets/1", MAPPING, SCHEMA)),
                FeedbackDisposition.NEUTRAL, score);
    }

    static CheckpointRejectReason rejected(byte[] bytes) {
        return switch (CheckpointCodec.decode(bytes)) {
            case CheckpointDecodeResult.Rejected rejected -> rejected.reason();
            case CheckpointDecodeResult.Decoded decoded -> throw new AssertionError("decoded " + decoded);
        };
    }

    /** {@code value} as a {@code str}: an {@code i32} UTF-8 length, then the bytes. */
    static byte[] str(String value) {
        return str(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] str(byte[] utf8) {
        return concat(i32(utf8.length), utf8);
    }

    static byte[] i32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    static byte[] i64(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    static byte[] u8(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** The encoding without its 32 checksum bytes. */
    static byte[] body(byte[] encoded) {
        return Arrays.copyOf(encoded, encoded.length - 32);
    }

    /** {@code body} followed by its SHA-256, so an edit reaches the structural checks instead of failing as corrupt. */
    static byte[] seal(byte[] body) {
        return concat(body, sha256(body));
    }

    /** Replaces the single occurrence of {@code pattern} in the body of {@code encoded} and seals the result. */
    static byte[] splice(byte[] encoded, byte[] pattern, byte[] replacement) {
        byte[] body = body(encoded);
        int at = indexOf(body, pattern, 0);
        assertTrue(at >= 0, "pattern not found");
        assertEquals(-1, indexOf(body, pattern, at + 1), "pattern is not unique");
        return seal(concat(Arrays.copyOfRange(body, 0, at), replacement,
                Arrays.copyOfRange(body, at + pattern.length, body.length)));
    }

    static int indexOf(byte[] bytes, byte[] pattern, int from) {
        for (int i = from; i <= bytes.length - pattern.length; i++) {
            if (Arrays.equals(bytes, i, i + pattern.length, pattern, 0, pattern.length)) {
                return i;
            }
        }
        return -1;
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
