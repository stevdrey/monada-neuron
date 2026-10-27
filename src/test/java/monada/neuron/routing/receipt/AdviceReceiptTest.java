package monada.neuron.routing.receipt;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.receipt.ReceiptFixtures.Base;
import monada.neuron.routing.receipt.ReceiptFixtures.Parts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdviceReceiptTest {

    // covers: AC-1
    @ParameterizedTest
    @EnumSource(Base.class)
    void aReceiptHoldsTheSameDecisionAndAFreshCopyOfItsBytes(Base base) throws Exception {
        RoutingDecision decision = base.decision();
        AdviceReceipt receipt = AdviceReceipt.of(decision);

        assertSame(decision, receipt.decision());
        byte[] first = receipt.bytes();
        Arrays.fill(first, (byte) 0x7f);
        assertArrayEquals(AdviceReceipt.of(decision).bytes(), receipt.bytes());
        assertEquals(receipt.bytes().length, receipt.size());
        assertTrue(receipt.size() <= AdviceReceipt.MAX_ENCODED_BYTES);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(receipt.bytes()));
        assertEquals(expected, receipt.sha256());
        assertTrue(receipt.sha256().matches("[0-9a-f]{64}"), receipt.sha256());
    }

    // covers: AC-1
    @Test
    void theEncodingStartsWithTheLengthPrefixedFormatTag() {
        byte[] tag = ReceiptFixtures.str("routing-advice-receipt/1");
        byte[] bytes = AdviceReceipt.of(Base.NO_ELIGIBLE_ROUTE.decision()).bytes();

        assertEquals("routing-advice-receipt/1", AdviceReceipt.FORMAT);
        assertArrayEquals(tag, Arrays.copyOf(bytes, tag.length));
        assertEquals(24, "routing-advice-receipt/1".getBytes(StandardCharsets.UTF_8).length);
    }

    // covers: AC-1
    @Test
    void aNullDecisionIsRejected() {
        var thrown = assertThrows(NullPointerException.class, () -> AdviceReceipt.of(null));
        assertEquals("decision must not be null", thrown.getMessage());
    }

    // covers: AC-2
    @ParameterizedTest
    @EnumSource(Base.class)
    void repeatedCallsGiveIdenticalBytesAndDigest(Base base) {
        RoutingDecision decision = base.decision();
        AdviceReceipt first = AdviceReceipt.of(decision);

        for (int i = 0; i < 5; i++) {
            AdviceReceipt again = AdviceReceipt.of(decision);
            assertArrayEquals(first.bytes(), again.bytes());
            assertEquals(first.sha256(), again.sha256());
        }
    }

    // covers: AC-2
    @Test
    void inputOrderOfExclusionsAndEligibleRoutesDoesNotChangeTheReceipt() {
        var other = new EligibilityReport.Exclusion(new RouteKey("ex-other", 1),
                EligibilityReason.ROUTE_UNAVAILABLE, List.of());
        var ordered = new Parts();
        ordered.exclusions = List.of(ReceiptFixtures.GONE, other);
        ordered.eligible = List.of(ReceiptFixtures.SUB_A, ReceiptFixtures.SUB_B, ReceiptFixtures.API_X);
        var reversed = new Parts();
        reversed.exclusions = List.of(other, ReceiptFixtures.GONE);
        reversed.eligible = List.of(ReceiptFixtures.API_X, ReceiptFixtures.SUB_B, ReceiptFixtures.SUB_A);

        for (Base base : List.of(Base.SELECTED, Base.ABSTAIN_INCOMPATIBLE, Base.NO_ELIGIBLE_ROUTE)) {
            AdviceReceipt a = AdviceReceipt.of(base.build(ordered));
            AdviceReceipt b = AdviceReceipt.of(base.build(reversed));
            assertArrayEquals(a.bytes(), b.bytes(), base.name());
            assertEquals(a.sha256(), b.sha256(), base.name());
        }
    }

    // covers: AC-2
    @Test
    void receiptsAreEqualExactlyWhenTheirDecisionsAreEqual() {
        List<RoutingDecision> decisions = new ArrayList<>();
        for (Base base : Base.values()) {
            decisions.add(base.decision());
            decisions.add(base.decision());
            Parts changed = base.parts();
            changed.taskId = "task-18";
            decisions.add(base.build(changed));
        }

        for (RoutingDecision a : decisions) {
            for (RoutingDecision b : decisions) {
                AdviceReceipt left = AdviceReceipt.of(a);
                AdviceReceipt right = AdviceReceipt.of(b);
                assertEquals(a.equals(b), left.equals(right), a + " vs " + b);
                if (left.equals(right)) {
                    assertEquals(left.hashCode(), right.hashCode());
                }
            }
        }
        assertNotEquals(AdviceReceipt.of(decisions.getFirst()), decisions.getFirst());
    }

    private static RouteCatalog catalog(boolean reversed) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (String id : List.of("route-a", "route-b", "route-c")) {
            entries.add(new CatalogEntry(RouteDescriptor.builder(id, 1).stages(List.of("implement"))
                    .executionModes(List.of("sandboxed")).locality("hosted").tier(id.equals("route-c") ? 2 : 1)
                    .capabilities(List.of(id.equals("route-c") ? "python" : "java")).build(), Availability.AVAILABLE, 1));
        }
        if (reversed) {
            Collections.reverse(entries);
        }
        return RouteCatalog.of("cat-1", entries);
    }

    private static RoutingDecision decide(LexicographicRoutingPolicy policy, String capability, boolean reversed) {
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope-w1", "task-3", "exec-1", "att-1",
                "stage-2", "implement", 7L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(capability), List.of(), List.of("hosted"), List.of("sandboxed"), 0L),
                false, 0L);
        return policy.decide(request, catalog(reversed),
                RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion()));
    }

    // covers: AC-2, AC-5
    @Test
    void receiptsOfRealPolicyDecisionsRoundTripAndIgnoreCatalogInputOrder() {
        var reference = LexicographicRoutingPolicy.reference();
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.of(1), Optional.empty());
        List<RoutingDecision> decisions = List.of(decide(reference, "java", false), decide(capped, "python", false),
                decide(reference, "rust", false));
        assertInstanceOf(RoutingDecision.Selected.class, decisions.get(0));
        assertInstanceOf(RoutingDecision.Abstain.class, decisions.get(1));
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, decisions.get(2));

        for (RoutingDecision decision : decisions) {
            AdviceReceipt receipt = AdviceReceipt.of(decision);
            var decoded = assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(receipt.bytes()));
            assertEquals(decision, decoded.receipt().decision());
        }
        assertEquals(AdviceReceipt.of(decide(reference, "java", false)), AdviceReceipt.of(decide(reference, "java", true)));
        assertEquals(AdviceReceipt.of(decide(reference, "rust", false)), AdviceReceipt.of(decide(reference, "rust", true)));
    }

    // covers: AC-5
    @ParameterizedTest
    @EnumSource(Base.class)
    void decodingAnEncodingGivesAnEqualReceiptAndDecision(Base base) {
        RoutingDecision decision = base.decision();
        AdviceReceipt receipt = AdviceReceipt.of(decision);

        var decoded = assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(receipt.bytes()));

        assertEquals(receipt, decoded.receipt());
        assertEquals(decision, decoded.receipt().decision());
        assertEquals(receipt.sha256(), decoded.receipt().sha256());
    }
}
