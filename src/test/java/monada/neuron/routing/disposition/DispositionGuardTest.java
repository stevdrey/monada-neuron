package monada.neuron.routing.disposition;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.disposition.AdviceDisposition.AlternativeProposed;
import monada.neuron.routing.disposition.AdviceDisposition.Declined;
import monada.neuron.routing.disposition.AdviceDisposition.Deferred;
import monada.neuron.routing.disposition.AdviceDisposition.Followed;
import monada.neuron.routing.disposition.DispositionFixtures.Base;
import monada.neuron.routing.disposition.DispositionFixtures.Parts;
import monada.neuron.routing.receipt.AdviceReceipt;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import static monada.neuron.routing.disposition.DispositionFixtures.SUB_A;
import static monada.neuron.routing.disposition.DispositionFixtures.SUB_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispositionGuardTest {

    private static final DispositionResult MISMATCH =
            new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH);

    private static AdviceReference reference(Base base) {
        return AdviceReference.of(base.receipt());
    }

    private static List<String> componentNames(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    // covers: AC-2
    @Test
    void aFollowedCannotBeBuiltFromAnAbstentionOrNoEligibleRoute() {
        for (Base base : List.of(Base.ABSTAIN_TRADEOFF, Base.ABSTAIN_INCOMPATIBLE, Base.NO_ELIGIBLE_ROUTE)) {
            AdviceReference reference = reference(base);

            var thrown = assertThrows(IllegalArgumentException.class, () -> new Followed(reference), base.name());
            assertTrue(thrown.getMessage().contains("SELECTED"), thrown.getMessage());
        }
        assertInstanceOf(Followed.class, new Followed(reference(Base.SELECTED)));
    }

    // covers: AC-4
    @Test
    void anAlternativeCannotBeBuiltOnTheAdvisedRoute() {
        AdviceReference selected = reference(Base.SELECTED);

        for (AlternativeStanding standing : AlternativeStanding.values()) {
            assertThrows(IllegalArgumentException.class, () -> new AlternativeProposed(selected, SUB_A, standing));
        }
        assertEquals(SUB_B, new AlternativeProposed(selected, SUB_B, AlternativeStanding.HOST_CLAIM).route());
        assertEquals(SUB_A, new AlternativeProposed(reference(Base.ABSTAIN_TRADEOFF), SUB_A,
                AlternativeStanding.ELIGIBLE_IN_DECISION).route());
    }

    // covers: AC-5
    @Test
    void aReferenceRequiresASixtyFourCharacterLowercaseHexDigest() {
        DecisionRef ref = Base.SELECTED.ref();
        String digest = Base.SELECTED.receipt().sha256();

        assertEquals(64, digest.length());
        new AdviceReference(ref, digest, AdviceKind.SELECTED, Optional.of(SUB_A));
        for (String bad : List.of("", digest.substring(1), digest + "0", digest.toUpperCase(Locale.ROOT),
                digest.substring(0, 63) + "g", digest.substring(0, 63) + " ", "0".repeat(63) + "G")) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> new AdviceReference(ref, bad, AdviceKind.SELECTED, Optional.of(SUB_A)), bad);
            assertTrue(thrown.getMessage().contains("receiptSha256"), thrown.getMessage());
        }
    }

    // covers: AC-5
    @Test
    void aReferenceHoldsARouteExactlyWhenItIsASelection() {
        DecisionRef ref = Base.SELECTED.ref();
        String digest = Base.SELECTED.receipt().sha256();

        assertThrows(IllegalArgumentException.class,
                () -> new AdviceReference(ref, digest, AdviceKind.SELECTED, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdviceReference(ref, digest, AdviceKind.ABSTAINED, Optional.of(SUB_A)));
        assertThrows(IllegalArgumentException.class,
                () -> new AdviceReference(ref, digest, AdviceKind.NO_ELIGIBLE_ROUTE, Optional.of(SUB_A)));
        new AdviceReference(ref, digest, AdviceKind.ABSTAINED, Optional.empty());
        new AdviceReference(ref, digest, AdviceKind.NO_ELIGIBLE_ROUTE, Optional.empty());
    }

    // covers: AC-5
    @Test
    void everyFactoryRejectsAnExpectedReferenceThatDiffersInAnyField() {
        List<Consumer<Parts>> changes = List.of(
                parts -> parts.scopeId = "scope-other",
                parts -> parts.taskId = "task-18",
                parts -> parts.executionId = "exec-2",
                parts -> parts.attemptId = "att-2",
                parts -> parts.stageId = "s-other",
                parts -> parts.requestOrdinal = 102);
        assertEquals(DecisionRef.class.getRecordComponents().length, changes.size());

        for (Base base : Base.values()) {
            AdviceReceipt receipt = base.receipt();
            for (Consumer<Parts> change : changes) {
                Parts parts = base.parts();
                change.accept(parts);
                DecisionRef other = base.build(parts).provenance().decisionRef();
                assertFalse(other.equals(base.ref()));

                assertEquals(MISMATCH, AdviceDisposition.followed(receipt, other));
                assertEquals(MISMATCH, AdviceDisposition.alternative(receipt, other, SUB_B));
                assertEquals(MISMATCH, AdviceDisposition.deferred(receipt, other));
                assertEquals(MISMATCH, AdviceDisposition.declined(receipt, other));
            }
        }
    }

    // covers: AC-5
    @Test
    void theReferenceCheckRunsBeforeTheVariantAndRouteRules() {
        Parts other = Base.SELECTED.parts();
        other.requestOrdinal = 102;
        DecisionRef otherRef = Base.SELECTED.build(other).provenance().decisionRef();

        assertEquals(MISMATCH, AdviceDisposition.followed(Base.ABSTAIN_TRADEOFF.receipt(), otherRef));
        assertEquals(MISMATCH, AdviceDisposition.followed(Base.NO_ELIGIBLE_ROUTE.receipt(), otherRef));
        assertEquals(MISMATCH, AdviceDisposition.alternative(Base.SELECTED.receipt(), otherRef, SUB_A));
        assertEquals(new DispositionResult.Rejected(DispositionRejection.ADVICE_NOT_SELECTED),
                AdviceDisposition.followed(Base.ABSTAIN_TRADEOFF.receipt(), Base.ABSTAIN_TRADEOFF.ref()));
        assertEquals(new DispositionResult.Rejected(DispositionRejection.ALTERNATIVE_EQUALS_ADVICE),
                AdviceDisposition.alternative(Base.SELECTED.receipt(), Base.SELECTED.ref(), SUB_A));
    }

    // covers: AC-5
    @Test
    void aHandBuiltReferenceWithAFalseKindDoesNotReferToTheReceipt() {
        AdviceReceipt abstained = Base.ABSTAIN_TRADEOFF.receipt();
        AdviceReference truth = AdviceReference.of(abstained);
        var lie = new AdviceReference(truth.decisionRef(), truth.receiptSha256(), AdviceKind.SELECTED,
                Optional.of(SUB_A));

        assertFalse(new Followed(lie).refersTo(abstained));
        assertFalse(new Deferred(lie).refersTo(abstained));
        assertFalse(new Declined(lie).refersTo(abstained));
        assertTrue(new Deferred(truth).refersTo(abstained));
    }

    // covers: AC-5
    @Test
    void aHandBuiltAlternativeWithAFalseStandingDoesNotReferToTheReceipt() {
        AdviceReceipt receipt = Base.SELECTED.receipt();
        AdviceReference truth = AdviceReference.of(receipt);

        assertFalse(new AlternativeProposed(truth, SUB_B, AlternativeStanding.HOST_CLAIM).refersTo(receipt));
        assertFalse(new AlternativeProposed(truth, SUB_B, AlternativeStanding.EXCLUDED_IN_DECISION).refersTo(receipt));
        assertTrue(new AlternativeProposed(truth, SUB_B, AlternativeStanding.ELIGIBLE_IN_DECISION).refersTo(receipt));
    }

    // covers: AC-1
    @Test
    void aReferenceNamesTheNullComponentItRejects() {
        DecisionRef ref = Base.SELECTED.ref();
        String digest = Base.SELECTED.receipt().sha256();

        assertEquals("decisionRef must not be null", assertThrows(NullPointerException.class,
                () -> new AdviceReference(null, digest, AdviceKind.SELECTED, Optional.of(SUB_A))).getMessage());
        assertEquals("receiptSha256 must not be null", assertThrows(NullPointerException.class,
                () -> new AdviceReference(ref, null, AdviceKind.SELECTED, Optional.of(SUB_A))).getMessage());
        assertEquals("kind must not be null", assertThrows(NullPointerException.class,
                () -> new AdviceReference(ref, digest, null, Optional.of(SUB_A))).getMessage());
        assertEquals("advisedRoute must not be null", assertThrows(NullPointerException.class,
                () -> new AdviceReference(ref, digest, AdviceKind.SELECTED, null)).getMessage());
    }

    // covers: AC-1
    @Test
    void equalComponentsGiveEqualRecordsAndHashCodes() {
        AdviceReceipt receipt = Base.SELECTED.receipt();
        AdviceReference reference = AdviceReference.of(receipt);
        var byHand = new AdviceReference(receipt.decision().provenance().decisionRef(), receipt.sha256(),
                AdviceKind.SELECTED, Optional.of(SUB_A));

        assertEquals(reference, byHand);
        assertEquals(reference.hashCode(), byHand.hashCode());
        assertEquals(new Followed(byHand), new Followed(reference));
        assertEquals(new Followed(byHand).hashCode(), new Followed(reference).hashCode());
        var factoryResult = AdviceDisposition.alternative(receipt, Base.SELECTED.ref(), SUB_B);
        var handResult = new DispositionResult.Bound(
                new AlternativeProposed(byHand, SUB_B, AlternativeStanding.ELIGIBLE_IN_DECISION));
        assertEquals(handResult, factoryResult);
        assertEquals(handResult.hashCode(), factoryResult.hashCode());
        assertEquals(new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH),
                new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH));
    }

    // covers: AC-6
    @Test
    void noDispositionTypeCarriesMutableOrExtraState() {
        for (Class<?> record : List.of(AdviceReference.class, Followed.class, AlternativeProposed.class, Deferred.class,
                Declined.class, DispositionResult.Bound.class, DispositionResult.Rejected.class)) {
            int instanceFields = 0;
            for (Field field : record.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertTrue(Modifier.isFinal(field.getModifiers()), record.getSimpleName() + "." + field.getName());
                    instanceFields++;
                }
            }
            assertEquals(record.getRecordComponents().length, instanceFields, record.getSimpleName());
        }
    }

    // covers: AC-1, AC-6
    @Test
    void theRecordsHaveExactlyTheComponentsOfTheContract() {
        assertEquals(List.of("decisionRef", "receiptSha256", "kind", "advisedRoute"),
                componentNames(AdviceReference.class));
        assertEquals(List.of("advice"), componentNames(Followed.class));
        assertEquals(List.of("advice", "route", "standing"), componentNames(AlternativeProposed.class));
        assertEquals(List.of("advice"), componentNames(Deferred.class));
        assertEquals(List.of("advice"), componentNames(Declined.class));
        assertEquals(List.of("disposition"), componentNames(DispositionResult.Bound.class));
        assertEquals(List.of("reason"), componentNames(DispositionResult.Rejected.class));
        assertEquals(List.of(Followed.class, AlternativeProposed.class, Deferred.class, Declined.class),
                List.of(AdviceDisposition.class.getPermittedSubclasses()));
        assertEquals(List.of(DispositionResult.Bound.class, DispositionResult.Rejected.class),
                List.of(DispositionResult.class.getPermittedSubclasses()));
        assertEquals(List.of("SELECTED", "ABSTAINED", "NO_ELIGIBLE_ROUTE"),
                Arrays.stream(AdviceKind.values()).map(Enum::name).toList());
        assertEquals(List.of("ELIGIBLE_IN_DECISION", "EXCLUDED_IN_DECISION", "HOST_CLAIM"),
                Arrays.stream(AlternativeStanding.values()).map(Enum::name).toList());
        assertEquals(List.of("REFERENCE_MISMATCH", "ADVICE_NOT_SELECTED", "ALTERNATIVE_EQUALS_ADVICE"),
                Arrays.stream(DispositionRejection.values()).map(Enum::name).toList());
    }
}
