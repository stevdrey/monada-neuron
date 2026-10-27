package monada.neuron.routing.disposition;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.disposition.AdviceDisposition.AlternativeProposed;
import monada.neuron.routing.disposition.DispositionFixtures.Base;
import monada.neuron.routing.disposition.DispositionFixtures.Parts;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.receipt.DecodeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static monada.neuron.routing.disposition.AlternativeStanding.ELIGIBLE_IN_DECISION;
import static monada.neuron.routing.disposition.AlternativeStanding.EXCLUDED_IN_DECISION;
import static monada.neuron.routing.disposition.AlternativeStanding.HOST_CLAIM;
import static monada.neuron.routing.disposition.DispositionFixtures.API_X;
import static monada.neuron.routing.disposition.DispositionFixtures.GONE;
import static monada.neuron.routing.disposition.DispositionFixtures.SUB_A;
import static monada.neuron.routing.disposition.DispositionFixtures.SUB_A_V2;
import static monada.neuron.routing.disposition.DispositionFixtures.SUB_B;
import static monada.neuron.routing.disposition.DispositionFixtures.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlternativeProposedTest {

    private static final RouteKey SUB_B_V2 = new RouteKey("sub-b", 2);
    private static final RouteKey TRUNCATED_TAIL = new RouteKey("r-9", 1);

    private static AlternativeProposed alternative(Base base, RouteKey route) {
        var bound = assertInstanceOf(DispositionResult.Bound.class,
                AdviceDisposition.alternative(base.receipt(), base.ref(), route));
        return assertInstanceOf(AlternativeProposed.class, bound.disposition());
    }

    /** Every variant with every placement, plus the claims AC-3 names. */
    static Stream<Arguments> standings() {
        return Stream.of(
                Arguments.of(Base.SELECTED, SUB_B, ELIGIBLE_IN_DECISION),
                Arguments.of(Base.SELECTED, API_X, ELIGIBLE_IN_DECISION),
                Arguments.of(Base.SELECTED, GONE.key(), EXCLUDED_IN_DECISION),
                Arguments.of(Base.SELECTED, UNKNOWN, HOST_CLAIM),
                Arguments.of(Base.SELECTED, SUB_B_V2, HOST_CLAIM),
                Arguments.of(Base.SELECTED_FULL, new RouteKey("r-7", 1), ELIGIBLE_IN_DECISION),
                Arguments.of(Base.SELECTED_TRUNCATED, new RouteKey("r-7", 1), ELIGIBLE_IN_DECISION),
                Arguments.of(Base.SELECTED_TRUNCATED, TRUNCATED_TAIL, HOST_CLAIM),
                Arguments.of(Base.SELECTED_TRUNCATED, GONE.key(), EXCLUDED_IN_DECISION),
                Arguments.of(Base.SELECTED_SAME_ID_OTHER_VERSION, SUB_A_V2, ELIGIBLE_IN_DECISION),
                Arguments.of(Base.ABSTAIN_TRADEOFF, SUB_A, ELIGIBLE_IN_DECISION),
                Arguments.of(Base.ABSTAIN_TRADEOFF, SUB_B, ELIGIBLE_IN_DECISION),
                Arguments.of(Base.ABSTAIN_TRADEOFF, GONE.key(), EXCLUDED_IN_DECISION),
                Arguments.of(Base.ABSTAIN_TRADEOFF, API_X, HOST_CLAIM),
                Arguments.of(Base.ABSTAIN_TRADEOFF, SUB_B_V2, HOST_CLAIM),
                Arguments.of(Base.ABSTAIN_INCOMPATIBLE, SUB_A, ELIGIBLE_IN_DECISION),
                Arguments.of(Base.ABSTAIN_INCOMPATIBLE, SUB_B, ELIGIBLE_IN_DECISION),
                Arguments.of(Base.ABSTAIN_INCOMPATIBLE, GONE.key(), EXCLUDED_IN_DECISION),
                Arguments.of(Base.ABSTAIN_INCOMPATIBLE, API_X, HOST_CLAIM),
                Arguments.of(Base.NO_ELIGIBLE_ROUTE, GONE.key(), EXCLUDED_IN_DECISION),
                Arguments.of(Base.NO_ELIGIBLE_ROUTE, SUB_A, HOST_CLAIM),
                Arguments.of(Base.NO_ELIGIBLE_ROUTE, UNKNOWN, HOST_CLAIM),
                Arguments.of(Base.NO_ELIGIBLE_ROUTE_EMPTY, SUB_A, HOST_CLAIM),
                Arguments.of(Base.NO_ELIGIBLE_ROUTE_EMPTY, GONE.key(), HOST_CLAIM),
                Arguments.of(Base.NO_ELIGIBLE_ROUTE_EMPTY, UNKNOWN, HOST_CLAIM));
    }

    // covers: AC-3
    @ParameterizedTest
    @MethodSource("standings")
    void standingIsDerivedFromTheDecisionAlone(Base base, RouteKey route, AlternativeStanding expected) {
        AlternativeProposed proposed = alternative(base, route);

        assertEquals(expected, proposed.standing(), base + " " + route);
        assertEquals(route, proposed.route());
        assertEquals(AdviceReference.of(base.receipt()), proposed.advice());
        assertTrue(proposed.refersTo(base.receipt()));
    }

    // covers: AC-3
    @ParameterizedTest
    @EnumSource(Base.class)
    void theVariantOnlyShapesTheReferenceNeverTheRoute(Base base) {
        AlternativeProposed proposed = alternative(base, UNKNOWN);

        assertEquals(HOST_CLAIM, proposed.standing());
        if (base.isSelected()) {
            assertEquals(Optional.of(SUB_A), proposed.advice().advisedRoute());
            assertNotEquals(proposed.advice().advisedRoute().orElseThrow(), proposed.route());
        } else {
            assertEquals(Optional.empty(), proposed.advice().advisedRoute());
        }
    }

    // covers: AC-4
    @Test
    void theAdvisedRouteItselfIsNotAnAlternative() {
        DispositionResult result = AdviceDisposition.alternative(Base.SELECTED.receipt(), Base.SELECTED.ref(), SUB_A);

        assertEquals(new DispositionResult.Rejected(DispositionRejection.ALTERNATIVE_EQUALS_ADVICE), result);
    }

    // covers: AC-4
    @Test
    void anotherVersionOfTheAdvisedRouteIsAnAlternative() {
        AlternativeProposed claimed = alternative(Base.SELECTED, SUB_A_V2);
        AlternativeProposed listed = alternative(Base.SELECTED_SAME_ID_OTHER_VERSION, SUB_A_V2);

        assertEquals(HOST_CLAIM, claimed.standing());
        assertEquals(ELIGIBLE_IN_DECISION, listed.standing());
        for (AlternativeProposed proposed : List.of(claimed, listed)) {
            assertEquals(Optional.of(SUB_A), proposed.advice().advisedRoute());
            assertEquals(SUB_A_V2, proposed.route());
            assertNotEquals(proposed.advice().advisedRoute().orElseThrow(), proposed.route());
        }
    }

    // covers: AC-5
    @Test
    void anAlternativeChecksTheExpectedReferenceBeforeTheRoute() {
        Parts other = Base.SELECTED.parts();
        other.scopeId = "scope-other";
        DecisionRef otherRef = Base.SELECTED.build(other).provenance().decisionRef();

        DispositionResult result = AdviceDisposition.alternative(Base.SELECTED.receipt(), otherRef, SUB_A);

        assertEquals(new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH), result);
    }

    // covers: AC-1, AC-5
    @Test
    void anAlternativeOfADecodedReceiptEqualsTheOriginalAndRefersToBoth() {
        AdviceReceipt receipt = Base.ABSTAIN_TRADEOFF.receipt();
        AdviceReceipt copy = assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(receipt.bytes()))
                .receipt();

        AlternativeProposed original = alternative(Base.ABSTAIN_TRADEOFF, SUB_B);
        var again = assertInstanceOf(DispositionResult.Bound.class,
                AdviceDisposition.alternative(copy, Base.ABSTAIN_TRADEOFF.ref(), SUB_B)).disposition();

        assertEquals(original, again);
        assertEquals(original.hashCode(), again.hashCode());
        assertTrue(original.refersTo(copy));
        assertFalse(original.refersTo(Base.ABSTAIN_INCOMPATIBLE.receipt()));
    }

    // covers: AC-1
    @Test
    void nullArgumentsAreRejectedByName() {
        AdviceReceipt receipt = Base.SELECTED.receipt();
        DecisionRef ref = Base.SELECTED.ref();

        assertEquals("receipt must not be null", assertThrows(NullPointerException.class,
                () -> AdviceDisposition.alternative(null, ref, SUB_B)).getMessage());
        assertEquals("expectedRef must not be null", assertThrows(NullPointerException.class,
                () -> AdviceDisposition.alternative(receipt, null, SUB_B)).getMessage());
        assertEquals("route must not be null", assertThrows(NullPointerException.class,
                () -> AdviceDisposition.alternative(receipt, ref, null)).getMessage());
        AdviceReference reference = AdviceReference.of(receipt);
        assertEquals("advice must not be null", assertThrows(NullPointerException.class,
                () -> new AlternativeProposed(null, SUB_B, HOST_CLAIM)).getMessage());
        assertEquals("route must not be null", assertThrows(NullPointerException.class,
                () -> new AlternativeProposed(reference, null, HOST_CLAIM)).getMessage());
        assertEquals("standing must not be null", assertThrows(NullPointerException.class,
                () -> new AlternativeProposed(reference, SUB_B, null)).getMessage());
    }
}
