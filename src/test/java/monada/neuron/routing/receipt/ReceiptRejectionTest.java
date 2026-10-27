package monada.neuron.routing.receipt;

import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.receipt.ReceiptFixtures.Base;
import monada.neuron.routing.receipt.ReceiptFixtures.Parts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static monada.neuron.routing.receipt.ReceiptFixtures.after;
import static monada.neuron.routing.receipt.ReceiptFixtures.i32;
import static monada.neuron.routing.receipt.ReceiptFixtures.i64;
import static monada.neuron.routing.receipt.ReceiptFixtures.indexOf;
import static monada.neuron.routing.receipt.ReceiptFixtures.replaceText;
import static monada.neuron.routing.receipt.ReceiptFixtures.splice;
import static monada.neuron.routing.receipt.ReceiptFixtures.str;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptRejectionTest {

    private static final int TAG_END = str(AdviceReceipt.FORMAT).length;

    private static byte[] encoded(Base base) {
        return AdviceReceipt.of(base.decision()).bytes();
    }

    private static void assertRejected(RejectReason reason, byte[] bytes) {
        assertEquals(new DecodeResult.Rejected(reason), AdviceReceipt.decode(bytes));
    }

    private static byte[] overwrite(byte[] bytes, int at, byte[] value) {
        return splice(bytes, at, at + value.length, value);
    }

    /** A valid {@code NoEligibleRoute} body after the given tag. */
    private static byte[] tagged(byte[] tag) {
        byte[] body = Arrays.copyOfRange(encoded(Base.NO_ELIGIBLE_ROUTE), TAG_END, encoded(Base.NO_ELIGIBLE_ROUTE).length);
        return ByteBuffer.allocate(tag.length + body.length).put(tag).put(body).array();
    }

    private static byte[] swapTexts(byte[] bytes, String first, String second) {
        int i = indexOf(bytes, first);
        int j = indexOf(bytes, second);
        byte[] copy = overwrite(bytes, i, str(second));
        return overwrite(copy, j, str(first));
    }

    // covers: AC-6
    @Test
    void aNullArrayIsRejected() {
        var thrown = assertThrows(NullPointerException.class, () -> AdviceReceipt.decode(null));
        assertEquals("bytes must not be null", thrown.getMessage());
    }

    // covers: AC-6
    @Test
    void anOversizeArrayIsRejectedBeforeAnyByteIsRead() {
        byte[] valid = encoded(Base.NO_ELIGIBLE_ROUTE);
        byte[] oversize = Arrays.copyOf(valid, AdviceReceipt.MAX_ENCODED_BYTES + 1);

        assertRejected(RejectReason.OVERSIZE, oversize);
        assertRejected(RejectReason.OVERSIZE, new byte[AdviceReceipt.MAX_ENCODED_BYTES + 1]);
        assertRejected(RejectReason.MALFORMED, new byte[AdviceReceipt.MAX_ENCODED_BYTES]);
    }

    // covers: AC-6
    @ParameterizedTest
    @ValueSource(strings = {"routing-advice-receipt/01", "routing-advice-receipt/1.1", "routing-advice-receipt/",
            "routing-advice-receipt/１", "routing-advice-receipt/+1", "routing-advice-receipt/1 ",
            "routing-ledger-digest/1", "Routing-advice-receipt/1"})
    void aTagThatIsNotAFormatAndADigitMajorIsMalformed(String tag) {
        assertRejected(RejectReason.MALFORMED, tagged(str(tag)));
    }

    // covers: AC-6
    @ParameterizedTest
    @ValueSource(strings = {"routing-advice-receipt/0", "routing-advice-receipt/2", "routing-advice-receipt/10",
            "routing-advice-receipt/99999999999"})
    void aWellFormedTagOfAnotherMajorIsUnsupported(String tag) {
        assertRejected(RejectReason.UNSUPPORTED_VERSION, tagged(str(tag)));
        // the version is decided from the tag alone, whatever follows
        assertRejected(RejectReason.UNSUPPORTED_VERSION, str(tag));
        assertRejected(RejectReason.UNSUPPORTED_VERSION, ByteBuffer.allocate(str(tag).length + 3).put(str(tag))
                .put(new byte[] {9, 9, 9}).array());
    }

    // covers: AC-6
    @Test
    void aTagLengthOutsideItsRangeOrInvalidUtf8IsMalformed() {
        byte[] badUtf8 = str(AdviceReceipt.FORMAT);
        badUtf8[10] = (byte) 0xFF;
        byte[] tooLong = ByteBuffer.allocate(4 + 65).putInt(65).put("a".repeat(65).getBytes(StandardCharsets.UTF_8))
                .array();

        for (byte[] bytes : List.of(new byte[0], new byte[] {0, 0, 0}, i32(0), i32(-1), i32(Integer.MIN_VALUE),
                tooLong, tagged(badUtf8), i32(24))) {
            assertRejected(RejectReason.MALFORMED, bytes);
        }
        assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(tagged(str(AdviceReceipt.FORMAT))));
    }

    // covers: AC-6
    @ParameterizedTest
    @EnumSource(Base.class)
    void truncationAtEveryByteIsMalformed(Base base) {
        byte[] bytes = encoded(base);
        for (int length = 0; length < bytes.length; length++) {
            assertRejected(RejectReason.MALFORMED, Arrays.copyOf(bytes, length));
        }
    }

    // covers: AC-6
    @Test
    void stringLengthsOutsideTheirRangeOrPastTheEndAreMalformed() {
        byte[] bytes = encoded(Base.NO_ELIGIBLE_ROUTE);
        int length = indexOf(bytes, "task-17");

        for (int bad : new int[] {-1, 0, 513, Integer.MAX_VALUE, Integer.MIN_VALUE, bytes.length - length}) {
            assertRejected(RejectReason.MALFORMED, overwrite(bytes, length, i32(bad)));
        }
    }

    // covers: AC-6
    @Test
    void tagBytesOutsideTheirAllowedValuesAreMalformed() {
        byte[] none = encoded(Base.NO_ELIGIBLE_ROUTE);
        byte[] selected = encoded(Base.SELECTED);
        int validation = after(none, "ppv-1");
        int stageKindPresence = indexOf(none, "implement") - 1;
        int overflowUsed = after(selected, "COLD_START");

        for (int variant : new int[] {0, 4, 255}) {
            assertRejected(RejectReason.MALFORMED, overwrite(none, TAG_END, new byte[] {(byte) variant}));
        }
        for (int kind : new int[] {0, 4}) {
            assertRejected(RejectReason.MALFORMED, overwrite(none, validation, new byte[] {(byte) kind}));
        }
        assertRejected(RejectReason.MALFORMED, overwrite(none, stageKindPresence, new byte[] {2}));
        assertRejected(RejectReason.MALFORMED, overwrite(selected, overflowUsed, new byte[] {2}));
    }

    // covers: AC-6
    @Test
    void listCountsThatAreNegativeOrAboveTheirBoundAreMalformed() {
        byte[] none = encoded(Base.NO_ELIGIBLE_ROUTE);
        byte[] selected = encoded(Base.SELECTED);
        int exclusions = after(none, "implement");
        int candidates = after(selected, "COLD_START") + 1;

        for (int bad : new int[] {-1, 33, Integer.MAX_VALUE}) {
            assertRejected(RejectReason.MALFORMED, overwrite(none, exclusions, i32(bad)));
        }
        assertRejected(RejectReason.MALFORMED, overwrite(selected, candidates, i32(9)));
        assertRejected(RejectReason.MALFORMED, overwrite(selected, candidates, i32(-1)));
    }

    // covers: AC-6
    @Test
    void anEnumNameThatIsNotAnExactConstantIsMalformed() {
        byte[] selected = encoded(Base.SELECTED);

        for (String bad : List.of("cold_start", "COLD_STARX", "Cold_Start")) {
            assertRejected(RejectReason.MALFORMED, replaceText(selected, "COLD_START", bad));
        }
    }

    // covers: AC-6
    @Test
    void trailingBytesAreMalformedEvenAfterInvalidValues() {
        byte[] none = encoded(Base.NO_ELIGIBLE_ROUTE);
        byte[] negativeOrdinal = overwrite(none, after(none, "s-impl"), i64(-1));

        assertRejected(RejectReason.MALFORMED, Arrays.copyOf(none, none.length + 1));
        assertRejected(RejectReason.INVALID_DECISION, negativeOrdinal);
        assertRejected(RejectReason.MALFORMED, Arrays.copyOf(negativeOrdinal, negativeOrdinal.length + 1));
    }

    // covers: AC-6
    @Test
    void wellFormedValuesTheRecordsRejectAreAnInvalidDecision() {
        byte[] none = encoded(Base.NO_ELIGIBLE_ROUTE);
        byte[] selected = encoded(Base.SELECTED);
        byte[] incompatible = encoded(Base.ABSTAIN_INCOMPATIBLE);
        int eligible = after(incompatible, "STATE_INCOMPATIBLE");
        // two keys of str "sub-a"/"sub-b" (9 bytes) and a version (8 bytes) each
        byte[] noEligible = splice(incompatible, eligible, eligible + 4 + 2 * 17, i32(0));
        int skipped = indexOf(selected, "LEARNED_PREFERENCE");
        byte[] skippedSwapped = splice(selected, skipped, skipped + 2 * str("LEARNED_PREFERENCE").length,
                ByteBuffer.allocate(2 * str("LEARNED_PREFERENCE").length).put(str("RESOURCE_OBJECTIVE"))
                        .put(str("LEARNED_PREFERENCE")).array());
        int firstRank = after(selected, "COLD_START") + 1 + 4;

        for (byte[] bytes : List.of(
                overwrite(none, after(none, "s-impl"), i64(-1)),
                replaceText(none, "task-17", "task\u000117"),
                noEligible,
                replaceText(selected, "sub-a", "sub-c"),
                skippedSwapped,
                overwrite(selected, firstRank, i32(1)))) {
            assertRejected(RejectReason.INVALID_DECISION, bytes);
        }
    }

    // covers: AC-6
    @Test
    void listsTheRecordsSortSilentlyAreNonCanonicalOutOfOrder() {
        Parts parts = new Parts();
        parts.exclusions = List.of(
                new EligibilityReport.Exclusion(new RouteKey("ex-aa", 1), EligibilityReason.ROUTE_UNAVAILABLE, List.of()),
                new EligibilityReport.Exclusion(new RouteKey("ex-bb", 1), EligibilityReason.ROUTE_UNAVAILABLE, List.of()));
        byte[] twoExclusions = AdviceReceipt.of(parts.noEligibleRoute()).bytes();
        byte[] incompatible = encoded(Base.ABSTAIN_INCOMPATIBLE);

        assertRejected(RejectReason.NON_CANONICAL, swapTexts(twoExclusions, "ex-aa", "ex-bb"));
        assertRejected(RejectReason.NON_CANONICAL, swapTexts(incompatible, "sub-a", "sub-b"));
    }

    // covers: AC-6
    @Test
    void decodingNeverModifiesItsInput() {
        for (Base base : Base.values()) {
            byte[] bytes = encoded(base);
            byte[] copy = bytes.clone();
            var decoded = assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(bytes));
            assertArrayEquals(copy, bytes);
            // the receipt owns its own copy
            Arrays.fill(bytes, (byte) 0);
            assertArrayEquals(copy, decoded.receipt().bytes());
        }
        byte[] corrupt = Arrays.copyOf(encoded(Base.SELECTED), 40);
        byte[] copy = corrupt.clone();
        AdviceReceipt.decode(corrupt);
        assertArrayEquals(copy, corrupt);
    }

    // covers: AC-6
    @Test
    void theRemainingListCountsAboveTheirBoundAreMalformed() {
        byte[] incompatible = encoded(Base.ABSTAIN_INCOMPATIBLE);
        byte[] selected = encoded(Base.SELECTED);
        int mismatches = after(incompatible, "ppv-1") + 1;
        int eligible = after(incompatible, "STATE_INCOMPATIBLE");
        int rulesSkipped = indexOf(selected, "LEARNED_PREFERENCE") - Integer.BYTES;
        int additional = after(selected, "MISSING_CAPABILITY");

        assertRejected(RejectReason.MALFORMED, overwrite(incompatible, mismatches, i32(6)));
        assertRejected(RejectReason.MALFORMED, overwrite(incompatible, eligible, i32(33)));
        assertRejected(RejectReason.MALFORMED, overwrite(selected, rulesSkipped, i32(6)));
        assertRejected(RejectReason.MALFORMED, overwrite(selected, additional, i32(9)));
        assertRejected(RejectReason.MALFORMED, overwrite(selected, additional, i32(-1)));
    }

    // covers: AC-6
    @Test
    void everyPresenceAndBooleanByteAcceptsOnlyZeroOrOne() {
        byte[] none = encoded(Base.NO_ELIGIBLE_ROUTE);
        byte[] tradeoff = encoded(Base.ABSTAIN_TRADEOFF);
        // NotEvaluated is one validation byte, then minSupportingObservations, then the two presence bytes
        int maxTierPresence = after(none, "ppv-1") + 1 + Integer.BYTES;
        int objectivePresence = maxTierPresence + 1;
        int cohortPresence = indexOf(tradeoff, "UNKNOWN") - 1;
        int truncated = indexOf(tradeoff, "ex-gone") - Integer.BYTES - 1;

        for (int bad : new int[] {2, 255}) {
            assertRejected(RejectReason.MALFORMED, overwrite(none, maxTierPresence, new byte[] {(byte) bad}));
            assertRejected(RejectReason.MALFORMED, overwrite(none, objectivePresence, new byte[] {(byte) bad}));
            assertRejected(RejectReason.MALFORMED, overwrite(tradeoff, cohortPresence, new byte[] {(byte) bad}));
            assertRejected(RejectReason.MALFORMED, overwrite(tradeoff, truncated, new byte[] {(byte) bad}));
        }
        // absent with the cohort still following is trailing bytes; absent with it removed is well formed, so the
        // records decide, and a tradeoff abstention without its cohort is invalid
        assertRejected(RejectReason.MALFORMED, overwrite(tradeoff, cohortPresence, new byte[] {0}));
        byte[] withoutCohort = overwrite(Arrays.copyOf(tradeoff, cohortPresence + 1), cohortPresence, new byte[] {0});
        assertRejected(RejectReason.INVALID_DECISION, withoutCohort);
    }

    // covers: AC-6
    @Test
    void aDuplicateExclusionIsAnInvalidDecisionNotSilentlyMerged() {
        Parts parts = new Parts();
        parts.exclusions = List.of(
                new EligibilityReport.Exclusion(new RouteKey("ex-aa", 1), EligibilityReason.ROUTE_UNAVAILABLE, List.of()),
                new EligibilityReport.Exclusion(new RouteKey("ex-bb", 1), EligibilityReason.ROUTE_UNAVAILABLE, List.of()));
        byte[] bytes = AdviceReceipt.of(parts.noEligibleRoute()).bytes();

        assertRejected(RejectReason.INVALID_DECISION, replaceText(bytes, "ex-bb", "ex-aa"));
    }

    // covers: AC-6
    @Test
    void randomCorruptionNeverThrowsNeverTouchesTheInputAndNeverRepairs() {
        var random = new Random(97);
        int decoded = 0;
        for (int i = 0; i < 5_000; i++) {
            byte[] bytes = encoded(Base.values()[i % Base.values().length]);
            for (int k = 0, changes = 1 + random.nextInt(3); k < changes; k++) {
                bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
            }
            byte[] copy = bytes.clone();

            DecodeResult result = AdviceReceipt.decode(bytes);

            assertArrayEquals(copy, bytes);
            if (result instanceof DecodeResult.Decoded accepted) {
                // a corruption that still decodes must be the one canonical encoding of the decision it decodes to
                assertArrayEquals(bytes, AdviceReceipt.of(accepted.receipt().decision()).bytes());
                decoded++;
            }
        }
        assertTrue(decoded > 0, "the seed should hit at least one still valid token change");
    }
}
