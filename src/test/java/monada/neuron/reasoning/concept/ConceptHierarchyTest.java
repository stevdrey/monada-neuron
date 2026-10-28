package monada.neuron.reasoning.concept;

import monada.neuron.reasoning.Proposition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static monada.neuron.reasoning.concept.ConceptFixtures.HIERARCHY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract section 8.1 rules and fixture N12. */
class ConceptHierarchyTest {

    @Test
    void fixtureHierarchyHasTheExpectedCompactLayout() {
        assertAll(
                () -> assertEquals(6, HIERARCHY.size()),
                () -> assertEquals(2, HIERARCHY.levels()),
                () -> assertEquals(2, HIERARCHY.rootCount()),
                () -> assertEquals(0, HIERARCHY.root(0)),
                () -> assertEquals(1, HIERARCHY.root(1)),
                () -> assertEquals(2, HIERARCHY.childCount(0)),
                () -> assertEquals(2, HIERARCHY.child(0, 0)),
                () -> assertEquals(3, HIERARCHY.child(0, 1)),
                () -> assertEquals(5, HIERARCHY.child(1, 1)),
                () -> assertEquals(4, HIERARCHY.leafCount()),
                () -> assertEquals(2, HIERARCHY.leaf(0)),
                () -> assertEquals(-1, HIERARCHY.leafPosition(1)),
                () -> assertEquals(3, HIERARCHY.leafPosition(5)),
                () -> assertTrue(HIERARCHY.isLeaf(4)),
                () -> assertFalse(HIERARCHY.isLeaf(0)),
                () -> assertTrue(HIERARCHY.inSubtree(3, 0)),
                () -> assertTrue(HIERARCHY.inSubtree(0, 0)),
                () -> assertFalse(HIERARCHY.inSubtree(4, 0)),
                () -> assertEquals(OptionalInt.of(5), HIERARCHY.ordinalOf(22)),
                () -> assertEquals(OptionalInt.empty(), HIERARCHY.ordinalOf(99)),
                () -> assertEquals(new Proposition(ConceptFixtures.DOMAIN, 12), HIERARCHY.proposition(3)));
    }

    @Test
    void n12RejectsCyclesChildrenBeforeParentsAndExcessDepth() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(1, List.of(
                        new ConceptNode(0, 1, ConceptNode.ROOT, 0),
                        new ConceptNode(1, 2, 1, 1))), "self-reference is a cycle"),
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(1, List.of(
                        new ConceptNode(0, 1, ConceptNode.ROOT, 0),
                        new ConceptNode(1, 2, 2, 1),
                        new ConceptNode(2, 3, 1, 1))), "forward references form a cycle"),
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(1, List.of(
                        new ConceptNode(0, 11, 1, 1),
                        new ConceptNode(1, 1, ConceptNode.ROOT, 0))), "child before parent"),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptNode(3, 4, 2,
                        ConceptHierarchy.MAX_DEPTH), "depth over maxDepth"),
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(1, List.of(
                        new ConceptNode(0, 1, ConceptNode.ROOT, 0),
                        new ConceptNode(1, 2, 0, 2))), "depth must follow the parent"),
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(1, List.of(
                        new ConceptNode(0, 1, ConceptNode.ROOT, 0),
                        new ConceptNode(1, 1, 0, 1))), "duplicate code"),
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(1, List.of(
                        new ConceptNode(1, 1, ConceptNode.ROOT, 0))), "ordinal must equal position"),
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(1, List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> ConceptHierarchy.of(-1, List.of(
                        new ConceptNode(0, 1, ConceptNode.ROOT, 0)))),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptNode(0, 1, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptNode(1, 1, ConceptNode.ROOT, 1)));
    }

    @Test
    void boundsAdmitTheLargestHierarchyAndRejectOneMore() {
        List<ConceptNode> nodes = new ArrayList<>();
        for (int i = 0; i < ConceptHierarchy.MAX_CONCEPTS; i++) {
            nodes.add(i < 16 ? new ConceptNode(i, i, ConceptNode.ROOT, 0)
                    : i < 64 ? new ConceptNode(i, i, (i - 16) % 16, 1)
                    : new ConceptNode(i, i, 16 + (i - 64) % 48, 2));
        }
        ConceptHierarchy largest = ConceptHierarchy.of(0, nodes);
        assertAll(
                () -> assertEquals(ConceptHierarchy.MAX_CONCEPTS, largest.size()),
                () -> assertEquals(ConceptHierarchy.MAX_DEPTH, largest.levels()),
                () -> assertTrue(largest.inSubtree(255, (255 - 64) % 48 + 16)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptNode(ConceptHierarchy.MAX_CONCEPTS, 1, ConceptNode.ROOT, 0)));
    }
}
