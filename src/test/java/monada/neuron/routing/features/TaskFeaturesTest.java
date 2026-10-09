package monada.neuron.routing.features;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFeaturesTest {

    private static TaskFeatures.Builder base() {
        return TaskFeatures.builder().stageKind("implement").category("bugfix");
    }

    @Test
    void permutedTagInputYieldsEqualSnapshots() {
        TaskFeatures a = base().languages(List.of("rust", "java", "go")).domains(List.of("data", "backend")).build();
        TaskFeatures b = base().languages(List.of("go", "rust", "java")).domains(List.of("backend", "data")).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(Feature.known(List.of("go", "java", "rust")), a.languages());
    }

    @Test
    void tagsAreOrderedByCodePointNotUtf16Unit() {
        String supplementary = new String(Character.toChars(0x1F600));
        String highBmp = "Ａ";
        TaskFeatures features = base().languages(List.of(supplementary, highBmp)).build();

        // U+FF21 < U+1F600 by code point; UTF-16 order would put the surrogate pair first.
        assertEquals(Feature.known(List.of(highBmp, supplementary)), features.languages());
    }

    @Test
    void mutatingTheSourceCollectionCannotChangeTheSnapshot() {
        List<String> source = new ArrayList<>(List.of("java", "go"));
        TaskFeatures features = base().languages(source).build();
        TaskFeatures copy = base().languages(List.of("java", "go")).build();

        source.clear();
        source.add("evil");

        assertEquals(copy, features);
        Feature.Known<List<String>> known = (Feature.Known<List<String>>) features.languages();
        assertThrows(UnsupportedOperationException.class, () -> known.value().add("x"));
    }

    @Test
    void unknownIsDistinctFromEmptyAndFromMeasuredZero() {
        TaskFeatures unknown = TaskFeatures.builder().build();
        TaskFeatures empty = TaskFeatures.builder().languages(List.of()).changeSize(0).build();

        assertNotEquals(unknown.languages(), empty.languages());
        assertNotEquals(unknown.changeSize(), empty.changeSize());
        assertTrue(unknown.stageKind() instanceof Feature.Unknown<String>);
    }

    @Test
    void acceptsUnicodeTokensByCodePointCount() {
        String wide = "😀".repeat(128);
        TaskFeatures features = base().category(wide).languages(List.of("日本語", "é")).build();

        assertEquals(Feature.known(wide), features.category());
        assertThrows(IllegalArgumentException.class, () -> base().category("😀".repeat(129)).build());
    }

    @Test
    void rejectsInvalidTokens() {
        assertThrows(IllegalArgumentException.class, () -> base().category("").build());
        assertThrows(IllegalArgumentException.class, () -> base().category(" lead").build());
        assertThrows(IllegalArgumentException.class, () -> base().category("trail ").build());
        assertThrows(IllegalArgumentException.class, () -> base().category("a\u0000b").build());
        assertThrows(IllegalArgumentException.class, () -> base().category("tab\there").build());
        assertThrows(IllegalArgumentException.class, () -> base().languages(List.of("ok", "bad\n")).build());
        assertThrows(NullPointerException.class, () -> base().category(null).build());
    }

    @Test
    void rejectsUnicodeSpaceSeparatorsAtTheBoundariesOnly() {
        for (String space : List.of("\u00A0", "\u202F", "\u2007", "\u3000", "\u2003")) {
            assertThrows(IllegalArgumentException.class, () -> base().category(space + "x").build());
            assertThrows(IllegalArgumentException.class, () -> base().category("x" + space).build());
            assertThrows(IllegalArgumentException.class, () -> base().languages(List.of(space + "x")).build());
            assertEquals(Feature.known("a" + space + "b"), base().category("a" + space + "b").build().category());
        }
    }

    @Test
    void enforcesTagCapacityAndRejectsDuplicates() {
        List<String> eight = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        List<String> nine = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i");

        assertEquals(8, ((Feature.Known<List<String>>) base().languages(eight).build().languages()).value().size());
        assertThrows(IllegalArgumentException.class, () -> base().languages(nine).build());
        assertThrows(IllegalArgumentException.class, () -> base().domains(nine).build());
        assertThrows(IllegalArgumentException.class, () -> base().languages(List.of("java", "java")).build());
    }

    @Test
    void rejectsNegativeQuantities() {
        assertThrows(IllegalArgumentException.class, () -> base().changeSize(-1).build());
        assertThrows(IllegalArgumentException.class, () -> base().contextSize(-1).build());
        assertEquals(Feature.known(Long.MAX_VALUE), base().changeSize(Long.MAX_VALUE).build().changeSize());
    }

    @Test
    void schemaVersionIsAValidatedToken() {
        assertEquals("other/9", base().schemaVersion("other/9").build().schemaVersion());
        assertThrows(IllegalArgumentException.class, () -> base().schemaVersion("").build());
    }

    @Test
    void carriesNoIdentityPriceOrOutcomeComponent() {
        for (var component : TaskFeatures.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            for (String forbidden : List.of("id", "route", "price", "cost", "outcome", "feedback", "project")) {
                assertTrue(!name.equals(forbidden) && !name.endsWith(forbidden),
                        "TaskFeatures must not carry '" + name + "'");
            }
        }
    }
}
