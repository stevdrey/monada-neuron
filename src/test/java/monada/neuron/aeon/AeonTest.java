package monada.neuron.aeon;

import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeonTest {

    @Test
    void definesTheDocumentedCognitivePurposes() {
        assertArrayEquals(
                new AeonPurpose[] {
                        AeonPurpose.PERCEPTION,
                        AeonPurpose.REASONING,
                        AeonPurpose.EVALUATION,
                        AeonPurpose.EVOLUTION,
                        AeonPurpose.ACTION,
                        AeonPurpose.CONSTRAINT,
                        AeonPurpose.SELF_MONITORING
                },
                AeonPurpose.values());
    }

    @Test
    void usesImmutableUuidIdentityAndPurpose() {
        var id = uuid(1);
        var first = new Aeon(id, AeonPurpose.PERCEPTION);
        var equal = new Aeon(id, AeonPurpose.REASONING);
        var different = new Aeon(uuid(2), AeonPurpose.PERCEPTION);

        assertAll(
                () -> assertEquals(id, first.getId()),
                () -> assertEquals(AeonPurpose.PERCEPTION, first.getPurpose()),
                () -> assertEquals(first, equal),
                () -> assertEquals(first.hashCode(), equal.hashCode()),
                () -> assertFalse(first.equals(different)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Aeon(null, AeonPurpose.PERCEPTION)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Aeon(id, null)));
    }

    @Test
    void preservesInsertionOrderAndCanonicalMemberInstance() {
        var aeon = new Aeon(uuid(10), AeonPurpose.REASONING);
        var first = node(uuid(1));
        var second = node(uuid(2));
        var equalButDifferent = node(uuid(1));
        var members = aeon.getMembers();

        assertAll(
                () -> assertTrue(aeon.addMember(first)),
                () -> assertTrue(aeon.addMember(second)),
                () -> assertFalse(aeon.addMember(equalButDifferent)),
                () -> assertEquals(List.of(first, second), List.copyOf(members)),
                () -> assertSame(first, aeon.findMember(first.getId()).orElseThrow()),
                () -> assertTrue(aeon.containsMember(first.getId())),
                () -> assertThrows(UnsupportedOperationException.class, members::clear));
    }

    @Test
    void removalUpdatesTheLiveViewAndReinsertionAppends() {
        var aeon = new Aeon(uuid(10), AeonPurpose.EVALUATION);
        var first = node(uuid(1));
        var second = node(uuid(2));
        aeon.addMember(first);
        aeon.addMember(second);
        var members = aeon.getMembers();

        assertAll(
                () -> assertTrue(aeon.removeMember(first.getId())),
                () -> assertFalse(aeon.removeMember(first.getId())),
                () -> assertEquals(List.of(second), List.copyOf(members)),
                () -> assertTrue(aeon.addMember(first)),
                () -> assertEquals(List.of(second, first), List.copyOf(members)));
    }

    @Test
    void rejectsNullMembershipOperations() {
        var aeon = new Aeon(uuid(1), AeonPurpose.CONSTRAINT);

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> aeon.addMember(null)),
                () -> assertThrows(NullPointerException.class, () -> aeon.removeMember(null)),
                () -> assertThrows(NullPointerException.class, () -> aeon.containsMember(null)),
                () -> assertThrows(NullPointerException.class, () -> aeon.findMember(null)));
    }

    private Node node(UUID id) {
        return new Node.Builder().id(id).type(NodeType.PROCESSOR).build();
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
