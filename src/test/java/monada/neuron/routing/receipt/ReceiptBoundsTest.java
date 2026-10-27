package monada.neuron.routing.receipt;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.Placement;
import monada.neuron.routing.PolicyParameters;
import monada.neuron.routing.Provenance;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.StateBinding;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.token.OpaqueTokens;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptBoundsTest {

    /** Token {@code i}: 128 code points of four byte UTF-8, distinct per {@code i}. */
    private static String token(int i) {
        return "𝔸".repeat(127) + Character.toString(0x1F600 + i);
    }

    private static RouteKey route(int i) {
        return new RouteKey(token(i), Long.MAX_VALUE);
    }

    /** Every optional field present and every token at its largest. */
    private static Provenance provenance(StateValidation validation) {
        String mapping = token(60);
        String schema = token(61);
        return new Provenance(new DecisionRef(token(40), token(41), token(42), token(43), token(44), Long.MAX_VALUE),
                token(45), token(46), token(47), Long.MAX_VALUE,
                new StateBinding(token(48), schema, token(49), token(50), mapping, Long.MAX_VALUE, token(51), token(52)),
                validation, new PolicyParameters(Integer.MAX_VALUE, OptionalInt.of(Integer.MAX_VALUE),
                        Optional.of(new ResourceObjective(token(53), ResourceObjective.Direction.MAXIMIZE)), mapping,
                        schema),
                Optional.of(token(54)));
    }

    private static CohortBinding cohort() {
        return new CohortBinding(token(55), token(56), token(60), token(61));
    }

    private static List<RankedCandidate> candidates() {
        List<RankedCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < RoutingDecision.MAX_CANDIDATES; i++) {
            candidates.add(new RankedCandidate(i, route(i), Placement.RESOURCE_OBJECTIVE));
        }
        return candidates;
    }

    /** Exclusions of routes {@code from} until the catalog is full, each with every additional reason. */
    private static List<EligibilityReport.Exclusion> exclusions(int from, int count) {
        List<EligibilityReason> additional = List.of(EligibilityReason.values()).subList(1, 9);
        List<EligibilityReport.Exclusion> exclusions = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            exclusions.add(new EligibilityReport.Exclusion(route(i), EligibilityReason.STAGE_INCOMPATIBLE, additional));
        }
        return exclusions;
    }

    private static List<RouteKey> routes(int count) {
        return IntStream.range(0, count).mapToObj(ReceiptBoundsTest::route).toList();
    }

    static RoutingDecision worstSelected() {
        // an exclusion (744 bytes) outweighs a ranked candidate (about 550), so one candidate and 31 exclusions is the
        // largest selection; a full truncated ranking leaves room for only 23 exclusions
        return new RoutingDecision.Selected(provenance(new StateValidation.Compatible()), route(0), token(57),
                Basis.COLD_START, true, List.of(new RankedCandidate(0, route(0), Placement.ONLY_ELIGIBLE)), false,
                List.of(RoutingRule.values()), List.of(), exclusions(1, RouteCatalog.MAX_ROUTES - 1), cohort());
    }

    /** The full truncated ranking, which the size test also bounds. */
    static RoutingDecision truncatedSelected() {
        return new RoutingDecision.Selected(provenance(new StateValidation.Compatible()), route(0), token(57),
                Basis.RESOURCE_OBJECTIVE, true, candidates(), true, List.of(RoutingRule.values()), List.of(),
                exclusions(RoutingDecision.MAX_CANDIDATES, RouteCatalog.MAX_ROUTES - RoutingDecision.MAX_CANDIDATES - 1),
                cohort());
    }

    static RoutingDecision worstTradeoff() {
        // an excluded route costs more bytes than an eligible one, so eight eligible routes is the largest case
        return new RoutingDecision.Abstain(provenance(new StateValidation.Compatible()),
                AbstainReason.POLICY_TRADEOFF_UNRESOLVED, routes(RoutingDecision.MAX_CANDIDATES), candidates(), false,
                exclusions(RoutingDecision.MAX_CANDIDATES, RouteCatalog.MAX_ROUTES - RoutingDecision.MAX_CANDIDATES),
                Optional.of(cohort()));
    }

    static RoutingDecision worstIncompatible() {
        return new RoutingDecision.Abstain(provenance(new StateValidation.Incompatible(List.of(StateMismatch.values()))),
                AbstainReason.STATE_INCOMPATIBLE, routes(1), List.of(), false,
                exclusions(1, RouteCatalog.MAX_ROUTES - 1));
    }

    static RoutingDecision worstNoEligibleRoute() {
        return new RoutingDecision.NoEligibleRoute(provenance(new StateValidation.NotEvaluated()),
                exclusions(0, RouteCatalog.MAX_ROUTES));
    }

    // covers: AC-7
    @Test
    void theCapIs64KiB() {
        assertEquals(65_536, AdviceReceipt.MAX_ENCODED_BYTES);
    }

    // covers: AC-7
    @Test
    void theWorstCaseOfEveryVariantFitsTheCapAndIsPinned() {
        assertEquals(512, token(0).getBytes(StandardCharsets.UTF_8).length);
        // measured sizes of the largest selection, the full truncated ranking, both abstentions and no eligible route;
        // the format spec records the same numbers
        int[] expected = {36_714, 34_625, 38_437, 33_638, 33_693};
        List<RoutingDecision> worst = List.of(worstSelected(), truncatedSelected(), worstTradeoff(),
                worstIncompatible(), worstNoEligibleRoute());

        int[] measured = worst.stream().mapToInt(decision -> AdviceReceipt.of(decision).size()).toArray();

        assertEquals(Arrays.toString(expected), Arrays.toString(measured));
        for (RoutingDecision decision : worst) {
            AdviceReceipt receipt = AdviceReceipt.of(decision);
            assertTrue(receipt.size() <= AdviceReceipt.MAX_ENCODED_BYTES);
            assertEquals(receipt, assertInstanceOf(DecodeResult.Decoded.class,
                    AdviceReceipt.decode(receipt.bytes())).receipt());
        }
    }

    // covers: AC-8
    @Test
    void theReferenceWriterAgreesWithTheCodecOnEveryPresentBranch() {
        // the golden vectors leave every optional field absent; the worst cases have them all present, a truncated
        // ranking and a full mismatch list, so this is where a second writer must agree on the present branches
        for (RoutingDecision decision : List.of(worstSelected(), truncatedSelected(), worstTradeoff(),
                worstIncompatible(), worstNoEligibleRoute())) {
            assertArrayEquals(ReferenceReceiptWriter.write(decision), AdviceReceipt.of(decision).bytes());
        }
    }

    // covers: AC-7
    @Test
    void theEnumVocabularyOfTheLayoutIsPinned() {
        assertEquals(List.of("SCOPE_MISMATCH", "FEATURE_SCHEMA_MISMATCH", "EVALUATION_POLICY_MISMATCH",
                "MAPPING_VERSION_MISMATCH", "PROCESSED_CUTOFF_NEWER"), names(StateMismatch.values()));
        assertEquals(List.of("MINIMIZE", "MAXIMIZE"), names(ResourceObjective.Direction.values()));
        assertEquals(List.of("COLD_START", "HOST_PRIORITY", "LEARNED_PREFERENCE", "RESOURCE_OBJECTIVE"),
                names(Basis.values()));
        assertEquals(List.of("ONLY_ELIGIBLE", "TIER", "FALLBACK_PRIORITY", "LEARNED_PREFERENCE",
                "RESOURCE_OBJECTIVE", "ROUTE_ORDER"), names(Placement.values()));
        assertEquals(List.of("TIER", "FALLBACK_PRIORITY", "LEARNED_PREFERENCE", "RESOURCE_OBJECTIVE",
                "ROUTE_ORDER"), names(RoutingRule.values()));
        assertEquals(List.of("POLICY_TRADEOFF_UNRESOLVED", "STATE_INCOMPATIBLE"), names(AbstainReason.values()));
        assertEquals(List.of("STAGE_INCOMPATIBLE", "ROUTE_UNAVAILABLE", "AVAILABILITY_UNKNOWN",
                "MODE_NOT_PERMITTED", "LOCALITY_NOT_PERMITTED", "OVERFLOW_NOT_PERMITTED", "MISSING_CAPABILITY",
                "REQUIRED_LIMIT_EXCEEDED", "REQUIRED_LIMIT_UNKNOWN"), names(EligibilityReason.values()));
    }

    // covers: AC-7
    @Test
    void theStructuralBoundsMatchTheDecisionBounds() {
        assertEquals(8, RoutingDecision.MAX_CANDIDATES);
        assertEquals(32, RouteCatalog.MAX_ROUTES);
        assertEquals(8, EligibilityReport.Exclusion.MAX_ADDITIONAL);
        assertEquals(128, OpaqueTokens.MAX_CODE_POINTS);
    }

    private static List<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
    }
}
