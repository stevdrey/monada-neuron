package monada.neuron.evaluation.bilingual;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensePartitionsTest {

    private final KeyedHash hash = new KeyedHash();
    private final SensePartitions partitions = new SensePartitions();

    @Test
    void keyedHashMatchesAnIndependentHmacSha256() {
        // Vectors from Python's hmac module with key "bilingual-sense/1".
        assertEquals(new BigInteger("158e41ff1b0abc74d7854515f094dc2fbe232336ae7c235ae12ad67c6076767e", 16),
                hash.h("split", "abc"));
        assertEquals(new BigInteger("b30f5c8a5ae0e0487f42bbb873b360c700f230599a027ebdf474c9a07f7ce2a5", 16),
                hash.h("direction", "fam1"));
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
    void occurrenceIdFollowsV2() {
        assertEquals("EN>ES|bank|tatoeba:42|3|7",
                partitions.occurrenceId(Direction.EN_ES, "bank", "tatoeba:42", 3, 7));
    }
}
