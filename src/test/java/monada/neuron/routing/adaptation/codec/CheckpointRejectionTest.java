package monada.neuron.routing.adaptation.codec;

import monada.neuron.routing.adaptation.LedgerDigest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.body;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.concat;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.empty;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.i32;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.i64;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.populated;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.rejected;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.seal;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.splice;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.str;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.u8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointRejectionTest {

    private static final CheckpointRejectReason MALFORMED = CheckpointRejectReason.MALFORMED;
    /** {@code str("routing-checkpoint/1")}: a 4 byte length and 20 bytes. */
    private static final int TAG_BYTES = 24;
    private static final byte[] TAG = str("routing-checkpoint/1");

    private static final byte[] POPULATED = CheckpointCodec.encode(populated());
    private static final byte[] EMPTY = CheckpointCodec.encode(empty());
    /** No cohorts and no markers, with a cutoff and digest sum that make the count fields easy to find. */
    private static final byte[] SPARSE = CheckpointCodec.encode(CheckpointFixtures.checkpoint(7, List.of(),
            new LedgerDigest(0, CheckpointFixtures.DIGEST_SUM), List.of()));
    private static final byte[] SUM = HexFormat.of().parseHex(CheckpointFixtures.DIGEST_SUM);

    @Test
    void inputAboveTheCapIsOversizeWithNoByteRead() {
        // a valid tag at the front would not help: the size check comes first
        byte[] oversize = Arrays.copyOf(TAG, CheckpointCodec.MAX_ENCODED_BYTES + 1);
        assertEquals(CheckpointRejectReason.OVERSIZE, rejected(oversize));
    }

    @Test
    void everyTruncationIsMalformedNearTheTagAndCorruptAfterIt() {
        for (int length = 0; length < POPULATED.length; length++) {
            var expected = length < TAG_BYTES + 32 ? MALFORMED : CheckpointRejectReason.CORRUPT;
            assertEquals(expected, rejected(Arrays.copyOf(POPULATED, length)), "length " + length);
        }
    }

    @Test
    void everySingleByteFlipIsRejected() {
        for (int offset = 0; offset < POPULATED.length; offset++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] flipped = POPULATED.clone();
                flipped[offset] ^= (byte) (1 << bit);
                var reason = rejected(flipped);
                if (offset >= TAG_BYTES) {
                    assertEquals(CheckpointRejectReason.CORRUPT, reason, "offset " + offset + " bit " + bit);
                } else {
                    assertTrue(reason == MALFORMED || reason == CheckpointRejectReason.UNSUPPORTED_VERSION,
                            "offset " + offset + " bit " + bit + ": " + reason);
                }
            }
        }
    }

    @Test
    void appendedBytesAreCorrupt() {
        assertEquals(CheckpointRejectReason.CORRUPT, rejected(concat(POPULATED, u8(0))));
    }

    @Test
    void anotherMajorIsUnsupportedWhateverFollows() {
        assertEquals(CheckpointRejectReason.UNSUPPORTED_VERSION, rejected(str("routing-checkpoint/2")));
        assertEquals(CheckpointRejectReason.UNSUPPORTED_VERSION,
                rejected(concat(str("routing-checkpoint/0"), body(EMPTY))));
        // compared as a digit string, so a major past any integer type is still just unsupported
        assertEquals(CheckpointRejectReason.UNSUPPORTED_VERSION,
                rejected(seal(concat(str("routing-checkpoint/99999999999999999999999"),
                        Arrays.copyOfRange(body(EMPTY), TAG_BYTES, body(EMPTY).length)))));
    }

    @Test
    void aBadTagIsMalformed() {
        for (String tag : new String[] {"routing-checkpoint/01", "routing-checkpoint/", "routing-checkpoint/1a",
                "routing-checkpoint/-1", "routing-advice-receipt/1", "ROUTING-CHECKPOINT/1", "routing-checkpoint/١"}) {
            assertEquals(MALFORMED, rejected(concat(str(tag), Arrays.copyOfRange(EMPTY, TAG_BYTES, EMPTY.length))),
                    tag);
        }
        assertEquals(MALFORMED, rejected(concat(i32(0), Arrays.copyOfRange(EMPTY, TAG_BYTES, EMPTY.length))));
        assertEquals(MALFORMED, rejected(concat(str("x".repeat(65)), new byte[64])));
        assertEquals(MALFORMED, rejected(concat(i32(-1), new byte[64])));
        assertEquals(MALFORMED, rejected(concat(str(u8(0xC0, 0x80)), new byte[64])));
    }

    @Test
    void fewerThan32BytesAfterTheTagAreMalformedNotCorrupt() {
        assertEquals(MALFORMED, rejected(concat(TAG, new byte[31])));
        assertEquals(CheckpointRejectReason.CORRUPT, rejected(concat(TAG, new byte[32])));
    }

    @Test
    void aTagWithOnlyAChecksumIsMalformedBecauseTheFieldsEndEarly() {
        assertEquals(MALFORMED, rejected(seal(TAG)));
    }

    @Test
    void outOfRangeStringLengthsAreMalformedWithoutAllocation() {
        byte[] scope = str(CheckpointFixtures.SCOPE);
        byte[] rest = Arrays.copyOfRange(scope, 4, scope.length);
        // a length far beyond the input never allocates a buffer
        assertEquals(MALFORMED, rejected(splice(EMPTY, scope, concat(i32(2_000_000_000), rest))));
        assertEquals(MALFORMED, rejected(splice(EMPTY, scope, concat(i32(-5), rest))));
        assertEquals(MALFORMED, rejected(splice(EMPTY, scope, concat(i32(0), rest))));
        // longer than the bytes left before the checksum, though the checksum bytes would cover it
        assertEquals(MALFORMED, rejected(splice(EMPTY, scope, concat(i32(EMPTY.length - 32), rest))));
        // above the 512 byte token bound, with the bytes present
        assertEquals(MALFORMED, rejected(splice(EMPTY, scope, str("s".repeat(513)))));
        // the 2,048 byte text bound applies to an experienceRef, the token bound to everything else
        assertEquals(MALFORMED, rejected(splice(POPULATED, str("exp-a"), str("e".repeat(2_049)))));
    }

    @Test
    void invalidUtf8IsMalformed() {
        byte[] scope = str(CheckpointFixtures.SCOPE);
        // an overlong encoding of '/', an encoded surrogate, a lone continuation byte, a cut off sequence
        for (byte[] bad : new byte[][] {u8(0xC0, 0xAF), u8(0xED, 0xA0, 0x80), u8(0x80), u8(0xF0, 0x9F, 0x98)}) {
            assertEquals(MALFORMED, rejected(splice(EMPTY, scope, str(bad))));
        }
    }

    @Test
    void countsOutOfRangeAreMalformed() {
        // the cohort count follows processedCutoff 7
        byte[] cohorts = concat(i64(7), i32(0));
        assertEquals(MALFORMED, rejected(splice(SPARSE, cohorts, concat(i64(7), i32(257)))));
        assertEquals(MALFORMED, rejected(splice(SPARSE, cohorts, concat(i64(7), i32(-1)))));
        // within the bound, but the cohorts it declares are not there
        assertEquals(MALFORMED, rejected(splice(SPARSE, cohorts, concat(i64(7), i32(256)))));
        // the marker count follows the digest sum
        byte[] markers = concat(SUM, i32(0));
        assertEquals(MALFORMED, rejected(splice(SPARSE, markers, concat(SUM, i32(4_097)))));
        assertEquals(MALFORMED, rejected(splice(SPARSE, markers, concat(SUM, i32(Integer.MIN_VALUE)))));
        assertEquals(MALFORMED, rejected(splice(SPARSE, markers, concat(SUM, i32(4_096)))));
    }

    @Test
    void presenceAndBasisBytesOutOfRangeAreMalformed() {
        // the targetNodeId presence byte follows catalogVersion and the marker cutoff 5
        byte[] target = concat(str("catalog-7"), i64(5), u8(0));
        assertEquals(MALFORMED, rejected(splice(POPULATED, target, concat(str("catalog-7"), i64(5), u8(2)))));
        // the supersedesSequence presence byte of exp-a follows its routing policy version
        byte[] supersedes = concat(str("3"), u8(0), str("catalog-7"));
        assertEquals(MALFORMED, rejected(splice(POPULATED, supersedes, concat(str("3"), u8(7), str("catalog-7")))));
        // basis 1 (a derived entry) and 3 (unknown) before the reason
        byte[] basis = concat(u8(2), str("NO_TARGET_BINDING"));
        assertEquals(MALFORMED, rejected(splice(POPULATED, basis, concat(u8(1), str("NO_TARGET_BINDING")))));
        assertEquals(MALFORMED, rejected(splice(POPULATED, basis, concat(u8(3), str("NO_TARGET_BINDING")))));
    }

    @Test
    void anEnumNameMustMatchAConstantExactly() {
        for (String name : new String[] {"no_target_binding", "NO_TARGET_BINDING ", "NO_TARGET"}) {
            assertEquals(MALFORMED, rejected(splice(POPULATED, str("NO_TARGET_BINDING"), str(name))), name);
        }
        byte[] disposition = concat(str("NEUTRAL"), i64(Double.doubleToRawLongBits(0.0)));
        assertEquals(MALFORMED, rejected(splice(POPULATED, disposition,
                concat(str("NEUTRALS"), i64(Double.doubleToRawLongBits(0.0))))));
    }

    @Test
    void bytesLeftBeforeTheChecksumAreMalformed() {
        assertEquals(MALFORMED, rejected(seal(concat(body(EMPTY), u8(0)))));
        assertEquals(MALFORMED, rejected(seal(concat(body(POPULATED), u8(0)))));
    }

    @Test
    void fieldsThatEndEarlyAreMalformed() {
        byte[] body = body(POPULATED);
        for (int length = TAG_BYTES; length < body.length; length++) {
            assertEquals(MALFORMED, rejected(seal(Arrays.copyOf(body, length))), "length " + length);
        }
    }

    @Test
    void theFirstFailureInTableOrderWins() {
        // oversize before a bad tag
        assertEquals(CheckpointRejectReason.OVERSIZE, rejected(new byte[CheckpointCodec.MAX_ENCODED_BYTES + 1]));
        // the tag before the checksum: a future major with any trailer reads as unsupported, not corrupt
        assertEquals(CheckpointRejectReason.UNSUPPORTED_VERSION,
                rejected(concat(str("routing-checkpoint/2"), Arrays.copyOfRange(EMPTY, TAG_BYTES, EMPTY.length))));
        // the checksum before structure: a structural fault behind a stale checksum is corrupt
        byte[] structural = splice(EMPTY, str(CheckpointFixtures.SCOPE), i32(-5));
        byte[] staleChecksum = concat(body(structural), Arrays.copyOfRange(EMPTY, EMPTY.length - 32, EMPTY.length));
        assertEquals(CheckpointRejectReason.CORRUPT, rejected(staleChecksum));
        // structure before records: a refused token early and a structural fault late is malformed
        byte[] badToken = splice(EMPTY, str(CheckpointFixtures.SCOPE), str("scope\u0007demo"));
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(badToken));
        assertEquals(MALFORMED, rejected(seal(concat(body(badToken), u8(0)))));
    }
}
