package monada.neuron.routing.receipt;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.receipt.ReceiptFixtures.Parts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReceiptAmbiguityTest {

    private static AdviceReceipt withTokens(String taskId, String executionId) {
        Parts parts = new Parts();
        parts.taskId = taskId;
        parts.executionId = executionId;
        return AdviceReceipt.of(parts.noEligibleRoute());
    }

    private static AdviceReceipt withTask(String taskId) {
        return withTokens(taskId, "exec-1");
    }

    private static RoutingDecision roundTrip(AdviceReceipt receipt) {
        return assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(receipt.bytes()))
                .receipt().decision();
    }

    // covers: AC-4
    @Test
    void adjacentTokensThatConcatenateAlikeDoNotCollide() {
        AdviceReceipt left = withTokens("ab", "c");
        AdviceReceipt right = withTokens("a", "bc");

        assertFalse(Arrays.equals(left.bytes(), right.bytes()));
        assertNotEquals(left.sha256(), right.sha256());
    }

    // covers: AC-4
    @Test
    void precomposedAndDecomposedFormsStayDistinctAndUnnormalized() {
        String precomposed = "café";
        String decomposed = "café";
        assertEquals(precomposed, Normalizer.normalize(decomposed, Normalizer.Form.NFC));

        AdviceReceipt nfc = withTask(precomposed);
        AdviceReceipt nfd = withTask(decomposed);

        assertNotEquals(nfc, nfd);
        assertNotEquals(nfc.sha256(), nfd.sha256());
        for (var pair : new Object[][] {{nfc, precomposed}, {nfd, decomposed}}) {
            AdviceReceipt receipt = (AdviceReceipt) pair[0];
            String token = (String) pair[1];
            ReceiptFixtures.indexOf(receipt.bytes(), token);
            assertEquals(token, ((RoutingDecision.NoEligibleRoute) roundTrip(receipt)).provenance().decisionRef()
                    .taskId());
        }
    }

    // covers: AC-4
    @Test
    void anEmojiSequenceAndTheLongestFourByteTokenRoundTripAsExactUtf8() {
        String emoji = "dev-👩‍💻";
        String longest = "𝔸".repeat(128);

        for (String token : new String[] {emoji, longest}) {
            AdviceReceipt receipt = withTask(token);
            int at = ReceiptFixtures.indexOf(receipt.bytes(), token);
            byte[] utf8 = token.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(utf8, Arrays.copyOfRange(receipt.bytes(), at + 4, at + 4 + utf8.length));
            assertEquals(token, roundTrip(receipt).provenance().decisionRef().taskId());
        }
        assertEquals(512, longest.getBytes(StandardCharsets.UTF_8).length);
    }

    // covers: AC-4
    @Test
    void aDecisionCannotHoldAnUnpairedSurrogate() {
        Parts parts = new Parts();
        parts.taskId = "task-\uD800";
        assertThrows(IllegalArgumentException.class, parts::noEligibleRoute);
    }

    // covers: AC-4
    @Test
    void invalidUtf8IsMalformedNeverReplaced() {
        byte[] three = withTask("abc").bytes();
        byte[] two = withTask("ab").bytes();
        byte[][] corrupt = {
                ReceiptFixtures.replaceText(three, "abc", new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}),
                ReceiptFixtures.replaceText(two, "ab", new byte[] {(byte) 0xC0, (byte) 0x80}),
                ReceiptFixtures.replaceText(three, "abc", new byte[] {'a', (byte) 0xE2, (byte) 0x82}),
                ReceiptFixtures.replaceText(two, "ab", new byte[] {'a', (byte) 0xFF}),
        };

        for (byte[] bytes : corrupt) {
            assertEquals(new DecodeResult.Rejected(RejectReason.MALFORMED), AdviceReceipt.decode(bytes));
        }
    }
}
