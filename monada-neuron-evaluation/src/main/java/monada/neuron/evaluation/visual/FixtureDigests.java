package monada.neuron.evaluation.visual;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** The SHA-256 derived values of the corpus: pixel hashes, seeds (Section 8.2) and splits (Section 8.10). */
final class FixtureDigests {

    /** The key that prefixes an id before its split byte is taken. */
    static final String SPLIT_KEY = "visual-fixtures/1";

    /** SHA-256 of bytes, as lowercase hex. */
    String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    /** The first 8 bytes of SHA-256 of the id, big endian. */
    long seed(String id) {
        return ByteBuffer.wrap(sha256(id.getBytes(StandardCharsets.UTF_8))).getLong();
    }

    /** {@code b}: the first byte of SHA-256 of the split key, a colon and the id, as {@code 0} to {@code 255}. */
    int splitByte(String id) {
        return sha256((SPLIT_KEY + ":" + id).getBytes(StandardCharsets.UTF_8))[0] & 0xff;
    }

    /**
     * The split of every id: within each group, ids sorted by {@code b} then id, the first {@code ceil(0.2 n)} to
     * test, the next {@code ceil(0.2 n)} to validation, the rest to train.
     */
    Map<String, FixtureSplit> splits(List<FixtureDefinition> definitions) {
        var byGroup = new EnumMap<FixtureGroup, List<String>>(FixtureGroup.class);
        for (FixtureDefinition definition : definitions) {
            byGroup.computeIfAbsent(definition.group(), group -> new ArrayList<>()).add(definition.id());
        }
        var splits = new HashMap<String, FixtureSplit>(definitions.size() * 2);
        for (List<String> ids : byGroup.values()) {
            ids.sort(Comparator.<String>comparingInt(this::splitByte).thenComparing(Comparator.naturalOrder()));
            int held = (ids.size() + 4) / 5;
            for (var i = 0; i < ids.size(); i++) {
                splits.put(ids.get(i), i < held ? FixtureSplit.TEST
                        : i < 2 * held ? FixtureSplit.VALIDATION : FixtureSplit.TRAIN);
            }
        }
        return Map.copyOf(splits);
    }

    private byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
