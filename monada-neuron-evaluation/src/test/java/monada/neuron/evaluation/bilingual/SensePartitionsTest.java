package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SensePartitions.Family;
import monada.neuron.evaluation.bilingual.SensePartitions.Split;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensePartitionsTest {

    private final KeyedHash hash = new KeyedHash();
    private final SensePartitions partitions = new SensePartitions(hash);

    @Test
    void keyedHashMatchesAnIndependentHmacSha256() {
        // Vectors from Python's hmac module with key "bilingual-sense/1".
        assertEquals(new BigInteger("158e41ff1b0abc74d7854515f094dc2fbe232336ae7c235ae12ad67c6076767e", 16),
                hash.h("split", "abc"));
        assertEquals(new BigInteger("b30f5c8a5ae0e0487f42bbb873b360c700f230599a027ebdf474c9a07f7ce2a5", 16),
                hash.h("direction", "fam1"));
        double u = hash.u("direction", "fam1");
        assertTrue(u >= 0.5 && u < 1.0, "0xb3... reads above one half");
    }

    @Test
    void twoLanguageFamilyDirectionFollowsTheKeyedHash() {
        assertEquals(Direction.ES_EN, partitions.direction("fam1", true, true));
        assertEquals(Direction.EN_ES, partitions.direction("fam1", true, false));
        assertEquals(Direction.ES_EN, partitions.direction("fam1", false, true));
        assertThrows(IllegalArgumentException.class, () -> partitions.direction("fam1", false, false));
    }

    @Test
    void permutationIsADeterministicPermutation() {
        int[] first = hash.permutation("EN>ES|bank", 50);
        assertTrue(Arrays.equals(first, hash.permutation("EN>ES|bank", 50)));
        assertEquals(50, Arrays.stream(first).distinct().count());
        assertTrue(Arrays.stream(first).allMatch(i -> i >= 0 && i < 50));
    }

    @Test
    void familyIdHashesTheSmallestKeyInUtf8ByteOrder() {
        assertEquals("8fbd583ea2feb29d1d516133ba63eea6d90568832ca4b6800e4049ae61b74ee9",
                partitions.familyId(List.of("tatoeba:9", "tatoeba:12", "tatoeba:3x")));
        // U+FF61 sorts before U+1F600 in UTF-8 bytes, although its UTF-16 code unit is larger than the surrogate.
        String supplementary = "k" + new String(Character.toChars(0x1F600));
        String bmp = "k｡";
        assertTrue(supplementary.compareTo(bmp) < 0, "UTF-16 order differs");
        assertTrue(SensePartitions.UTF8_ORDER.compare(bmp, supplementary) < 0);
        assertEquals(partitions.familyId(List.of(bmp)), partitions.familyId(List.of(supplementary, bmp)));
    }

    @Test
    void sentenceAndOccurrenceKeysFollowV2() {
        assertEquals("tatoeba:42", partitions.tatoebaKey(42));
        assertEquals("europarl:0:spa", partitions.europarlKey(0, "spa"));
        assertThrows(IllegalArgumentException.class, () -> partitions.europarlKey(0, "deu"));
        assertEquals("EN>ES|bank|tatoeba:42|3|7",
                partitions.occurrenceId(Direction.EN_ES, "bank", "tatoeba:42", 3, 7));
    }

    @Test
    void splitAssignmentUsesTheRegisteredCumulativeBoundaries() {
        var families = new ArrayList<Family>();
        for (int i = 0; i < 20; i++) {
            families.add(new Family("f" + i, "EN>ES|bank"));
        }
        for (int i = 0; i < 7; i++) {
            families.add(new Family("g" + i, "ES>EN|banco"));
        }
        Map<String, Split> splits = partitions.assign(families);
        // n = 20: (i + 0.5) / 20 < 0.05 only for i = 0; < 0.50 for i < 10; < 0.70 for i < 14.
        assertEquals(Map.of(Split.CALIBRATION, 1, Split.TRAINING, 9, Split.VALIDATION, 4, Split.HELD_OUT, 6),
                counts(splits, "f"));
        // n = 7: quantiles 0.071, 0.214, 0.357, 0.5, 0.643, 0.786, 0.929.
        assertEquals(Map.of(Split.TRAINING, 3, Split.VALIDATION, 2, Split.HELD_OUT, 2), counts(splits, "g"));
        var sorted = new ArrayList<>(families.subList(0, 20).stream().map(Family::familyId).toList());
        sorted.sort(hash.order(KeyedHash.SPLIT));
        assertEquals(Split.CALIBRATION, splits.get(sorted.getFirst()));
        assertEquals(Split.HELD_OUT, splits.get(sorted.getLast()));
    }

    @Test
    void aFamilyIsAssignedOnce() {
        assertThrows(IllegalArgumentException.class, () -> partitions.assign(
                List.of(new Family("f", "EN>ES|bank"), new Family("f", "EN>ES|bank"))));
    }

    private Map<Split, Integer> counts(Map<String, Split> splits, String prefix) {
        var counts = new EnumMap<Split, Integer>(Split.class);
        var seen = new HashSet<String>();
        splits.forEach((family, split) -> {
            if (family.startsWith(prefix) && seen.add(family)) {
                counts.merge(split, 1, Integer::sum);
            }
        });
        return counts;
    }
}
