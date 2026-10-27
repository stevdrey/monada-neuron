package monada.neuron.routing.disposition;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.disposition.AdviceDisposition.Declined;
import monada.neuron.routing.disposition.AdviceDisposition.Deferred;
import monada.neuron.routing.disposition.AdviceDisposition.Followed;
import monada.neuron.routing.disposition.DispositionFixtures.Base;
import monada.neuron.routing.disposition.DispositionFixtures.Parts;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.receipt.DecodeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdviceDispositionTest {

    private static final List<BiFunction<AdviceReceipt, DecisionRef, DispositionResult>> SIMPLE_FACTORIES = List.of(
            AdviceDisposition::followed, AdviceDisposition::deferred, AdviceDisposition::declined);

    private static AdviceReceipt decodedCopy(AdviceReceipt receipt) {
        return assertInstanceOf(DecodeResult.Decoded.class, AdviceReceipt.decode(receipt.bytes())).receipt();
    }

    private static AdviceDisposition bound(DispositionResult result) {
        return assertInstanceOf(DispositionResult.Bound.class, result).disposition();
    }

    // covers: AC-1, AC-2, AC-5
    @Test
    void aSelectedReceiptCanBeFollowedAndTheDispositionRefersToIt() {
        AdviceReceipt receipt = Base.SELECTED.receipt();

        var followed = assertInstanceOf(Followed.class, bound(AdviceDisposition.followed(receipt, Base.SELECTED.ref())));

        assertEquals(AdviceReference.of(receipt), followed.advice());
        assertEquals(AdviceKind.SELECTED, followed.advice().kind());
        assertEquals(Optional.of(DispositionFixtures.SUB_A), followed.advice().advisedRoute());
        assertEquals(receipt.sha256(), followed.advice().receiptSha256());
        assertEquals(Base.SELECTED.ref(), followed.advice().decisionRef());
        assertTrue(followed.refersTo(receipt));
        assertTrue(followed.refersTo(decodedCopy(receipt)));
        assertEquals(followed, bound(AdviceDisposition.followed(decodedCopy(receipt), Base.SELECTED.ref())));
    }

    // covers: AC-1
    @Test
    void theContractVersionIsTheFirstDispositionFormat() {
        assertEquals("routing-advice-disposition/1", AdviceDisposition.CONTRACT_VERSION);
    }

    // covers: AC-2
    @ParameterizedTest
    @EnumSource(Base.class)
    void followedBindsExactlyWhenTheAdviceIsASelection(Base base) {
        DispositionResult result = AdviceDisposition.followed(base.receipt(), base.ref());

        if (base.isSelected()) {
            assertInstanceOf(Followed.class, bound(result));
        } else {
            assertEquals(new DispositionResult.Rejected(DispositionRejection.ADVICE_NOT_SELECTED), result);
        }
    }

    // covers: AC-6
    @ParameterizedTest
    @EnumSource(Base.class)
    void deferredAndDeclinedBindForEveryVariant(Base base) {
        AdviceReceipt receipt = base.receipt();

        var deferred = assertInstanceOf(Deferred.class, bound(AdviceDisposition.deferred(receipt, base.ref())));
        var declined = assertInstanceOf(Declined.class, bound(AdviceDisposition.declined(receipt, base.ref())));

        assertEquals(AdviceReference.of(receipt), deferred.advice());
        assertEquals(AdviceReference.of(receipt), declined.advice());
        assertTrue(deferred.refersTo(receipt));
        assertTrue(declined.refersTo(receipt));
        assertFalse(deferred.equals(declined));
    }

    // covers: AC-1, AC-5
    @ParameterizedTest
    @EnumSource(Base.class)
    void aDispositionOfADecodedReceiptEqualsTheOriginal(Base base) {
        AdviceReceipt receipt = base.receipt();
        AdviceReceipt copy = decodedCopy(receipt);

        for (var factory : SIMPLE_FACTORIES) {
            DispositionResult original = factory.apply(receipt, base.ref());
            DispositionResult again = factory.apply(copy, base.ref());
            assertEquals(original, again);
            if (original instanceof DispositionResult.Bound(AdviceDisposition disposition)) {
                assertEquals(disposition.hashCode(), bound(again).hashCode());
                assertTrue(disposition.refersTo(copy));
            }
        }
    }

    // covers: AC-5
    @Test
    void aDispositionDoesNotReferToAReRequestOrAnotherCatalogVersion() {
        AdviceReceipt receipt = Base.SELECTED.receipt();
        Followed followed = (Followed) bound(AdviceDisposition.followed(receipt, Base.SELECTED.ref()));
        Parts reRequested = Base.SELECTED.parts();
        reRequested.attemptId = "att-2";
        Parts otherCatalog = Base.SELECTED.parts();
        otherCatalog.catalogVersion = "cat-demo-8";

        assertFalse(followed.refersTo(AdviceReceipt.of(Base.SELECTED.build(reRequested))));
        assertFalse(followed.refersTo(AdviceReceipt.of(Base.SELECTED.build(otherCatalog))));
        assertEquals(Base.SELECTED.ref(), Base.SELECTED.build(otherCatalog).provenance().decisionRef());
    }

    // covers: AC-5
    @Test
    void aReceiptIsRequiredToAnswerRefersTo() {
        AdviceDisposition declined = bound(AdviceDisposition.declined(Base.SELECTED.receipt(), Base.SELECTED.ref()));

        var thrown = assertThrows(NullPointerException.class, () -> declined.refersTo(null));
        assertEquals("receipt must not be null", thrown.getMessage());
    }

    // covers: AC-5
    @Test
    void everyFactoryChecksTheExpectedReferenceFirst() {
        AdviceReceipt abstained = Base.ABSTAIN_TRADEOFF.receipt();
        Parts other = Base.ABSTAIN_TRADEOFF.parts();
        other.requestOrdinal = 102;
        DecisionRef otherRef = Base.ABSTAIN_TRADEOFF.build(other).provenance().decisionRef();

        for (var factory : SIMPLE_FACTORIES) {
            assertEquals(new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH),
                    factory.apply(abstained, otherRef));
        }
    }

    // covers: AC-1
    @Test
    void nullArgumentsAreRejectedByName() {
        AdviceReceipt receipt = Base.SELECTED.receipt();

        for (var factory : SIMPLE_FACTORIES) {
            assertEquals("receipt must not be null",
                    assertThrows(NullPointerException.class, () -> factory.apply(null, Base.SELECTED.ref())).getMessage());
            assertEquals("expectedRef must not be null",
                    assertThrows(NullPointerException.class, () -> factory.apply(receipt, null)).getMessage());
        }
        assertEquals("receipt must not be null",
                assertThrows(NullPointerException.class, () -> AdviceReference.of(null)).getMessage());
        assertEquals("advice must not be null",
                assertThrows(NullPointerException.class, () -> new Followed(null)).getMessage());
        assertEquals("advice must not be null",
                assertThrows(NullPointerException.class, () -> new Deferred(null)).getMessage());
        assertEquals("advice must not be null",
                assertThrows(NullPointerException.class, () -> new Declined(null)).getMessage());
        assertEquals("disposition must not be null",
                assertThrows(NullPointerException.class, () -> new DispositionResult.Bound(null)).getMessage());
        assertEquals("reason must not be null",
                assertThrows(NullPointerException.class, () -> new DispositionResult.Rejected(null)).getMessage());
    }

    // covers: AC-5
    @ParameterizedTest
    @EnumSource(Base.class)
    void aReferenceCopiesIdentityDigestKindAndTheAdvisedRouteOnly(Base base) {
        AdviceReceipt receipt = base.receipt();
        RoutingDecision decision = receipt.decision();

        AdviceReference reference = AdviceReference.of(receipt);

        assertEquals(decision.provenance().decisionRef(), reference.decisionRef());
        assertEquals(receipt.sha256(), reference.receiptSha256());
        switch (decision) {
            case RoutingDecision.Selected selected -> {
                assertEquals(AdviceKind.SELECTED, reference.kind());
                assertEquals(Optional.of(selected.route()), reference.advisedRoute());
            }
            case RoutingDecision.Abstain ignored -> {
                assertEquals(AdviceKind.ABSTAINED, reference.kind());
                assertEquals(Optional.empty(), reference.advisedRoute());
            }
            case RoutingDecision.NoEligibleRoute ignored -> {
                assertEquals(AdviceKind.NO_ELIGIBLE_ROUTE, reference.kind());
                assertEquals(Optional.empty(), reference.advisedRoute());
            }
        }
        assertEquals(reference, AdviceReference.of(decodedCopy(receipt)));
    }
}
