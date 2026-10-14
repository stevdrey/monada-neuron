package monada.neuron.routing.feedback;

import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RebuildOrderTest {

    private final RebuildOrder order = new RebuildOrder();

    private static RoutingObservation observation(String ref, long revision, long sequence) {
        var s = new FeedbackFixtures.Spec().passing();
        s.experienceRef = ref;
        s.revision = revision;
        s.ledgerSequence = sequence;
        s.supersedes = revision > 0 ? Feature.known(sequence - 1) : Feature.unknown();
        return s.observation();
    }

    private static List<String> refs(List<RoutingObservation> observations) {
        return observations.stream().map(RoutingObservation::experienceRef).toList();
    }

    @Test
    void ordersByLedgerSequenceThenReferenceByCodePoint() {
        var input = List.of(observation("b", 0, 30), observation("a", 0, 30), observation("c", 0, 20),
                observation("😀", 0, 30), observation("～", 0, 30));
        // U+FF5E sorts before U+1F600 by code point, although a UTF-16 order would put the emoji first
        assertEquals(List.of("c", "a", "b", "～", "😀"), refs(order.order(input)));
    }

    @Test
    void keepsOnlyTheLatestRevisionOfEachSampleAtItsOwnPosition() {
        var input = List.of(observation("x", 0, 12), observation("y", 0, 20), observation("x", 2, 40),
                observation("x", 1, 30));
        var ordered = order.order(input);
        assertEquals(List.of("y", "x"), refs(ordered));
        assertEquals(2, ordered.get(1).revision());
    }

    @Test
    void conflictingOlderRevisionsAreRejectedInEveryInputOrder() {
        var current = observation("a", 1, 40);
        var olderOne = observation("a", 0, 12);
        var olderTwo = observation("a", 0, 13);
        var all = List.of(current, olderOne, olderTwo);
        for (var permutation : List.of(
                List.of(all.get(0), all.get(1), all.get(2)), List.of(all.get(0), all.get(2), all.get(1)),
                List.of(all.get(1), all.get(0), all.get(2)), List.of(all.get(1), all.get(2), all.get(0)),
                List.of(all.get(2), all.get(0), all.get(1)), List.of(all.get(2), all.get(1), all.get(0)))) {
            assertThrows(IllegalArgumentException.class, () -> order.order(permutation));
        }
    }

    @Test
    void anyInputPermutationGivesTheSameOrder() {
        var input = new ArrayList<>(List.of(observation("a", 0, 12), observation("b", 1, 40), observation("b", 0, 20),
                observation("c", 0, 12), observation("d", 0, 33)));
        var expected = order.order(input);
        for (int i = 0; i < 10; i++) {
            Collections.rotate(input, 1);
            assertEquals(expected, order.order(input));
        }
        Collections.reverse(input);
        assertEquals(expected, order.order(input));
    }

    @Test
    void anIdenticalRepeatCollapsesButConflictingClaimsAreRejected() {
        var once = observation("a", 0, 12);
        assertEquals(List.of(once), order.order(List.of(once, observation("a", 0, 12))));
        assertThrows(IllegalArgumentException.class,
                () -> order.order(List.of(observation("a", 0, 12), observation("a", 0, 13))));
    }

    @Test
    void isBoundedAt4096AndRejectsNulls() {
        var oversized = new ArrayList<RoutingObservation>();
        var one = observation("a", 0, 12);
        for (int i = 0; i <= RebuildOrder.MAX_OBSERVATIONS; i++) {
            oversized.add(one);
        }
        assertThrows(IllegalArgumentException.class, () -> order.order(oversized));
        var atBound = oversized.subList(0, RebuildOrder.MAX_OBSERVATIONS);
        assertEquals(1, order.order(atBound).size());
        assertThrows(NullPointerException.class, () -> order.order(null));
        var withNull = new ArrayList<RoutingObservation>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> order.order(withNull));
        assertEquals(List.of(), order.order(List.of()));
    }
}
