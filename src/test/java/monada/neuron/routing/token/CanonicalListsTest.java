package monada.neuron.routing.token;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalListsTest {

    @Test
    void sortsByTheGivenOrderAndReturnsAnImmutableCopy() {
        var copy = CanonicalLists.canonical(List.of(3, 1, 2), Comparator.naturalOrder(), 3, "numbers");
        assertEquals(List.of(1, 2, 3), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add(4));
    }

    @Test
    void rejectsMoreThanTheBound() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalLists.canonical(List.of(1, 2, 3), Comparator.naturalOrder(), 2, "numbers"));
        assertEquals(List.of(), CanonicalLists.canonical(List.of(), Comparator.<Integer>naturalOrder(), 0, "numbers"));
    }

    @Test
    void rejectsDuplicatesByTheOrderNotByEquals() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalLists.canonical(List.of(1, 1), Comparator.naturalOrder(), 2, "numbers"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalLists.canonical(List.of("a", "A"),
                String.CASE_INSENSITIVE_ORDER, 2, "words"));
    }

    @Test
    void rejectsNullsAndANullSource() {
        assertThrows(NullPointerException.class,
                () -> CanonicalLists.canonical(null, Comparator.<Integer>naturalOrder(), 1, "numbers"));
        var withNull = new ArrayList<Integer>();
        withNull.add(null);
        assertThrows(NullPointerException.class,
                () -> CanonicalLists.canonical(withNull, Comparator.<Integer>naturalOrder(), 1, "numbers"));
    }
}
