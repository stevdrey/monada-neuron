package monada.neuron.evaluation.bilingual;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Comparator;

/**
 * The registered keyed hash of {@code bilingual-sense/1} (kept unchanged by v2, so the split is not re-rolled):
 * {@code h(label, id) = HMAC-SHA-256(key = "bilingual-sense/1", message = label + ":" + id)} read as an unsigned 256-bit
 * integer.
 *
 * <p>Not thread-safe: one {@link Mac} is reused by every call; the harness is single-threaded when scoring.
 */
final class KeyedHash {

    /** The HMAC key, which v2 keeps as {@code "bilingual-sense/1"}. */
    static final String KEY = "bilingual-sense/1";

    /** Label of the split order within a primary stratum. */
    static final String SPLIT = "split";
    /** Label of the order of occurrences within a family. */
    static final String OCCURRENCE = "occurrence";
    /** Label of the training-label and held-out-label permutation controls. */
    static final String PERMUTE = "permute";

    private final Mac mac;

    KeyedHash() {
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** Returns {@code h(label, id)} as a non-negative integer below {@code 2^256}. */
    BigInteger h(String label, String id) {
        byte[] digest = mac.doFinal((label + ":" + id).getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, digest);
    }

    /** Returns a comparator of ids by increasing {@code h(label, id)}, ties (never expected) by the id itself. */
    Comparator<String> order(String label) {
        return Comparator.<String, BigInteger>comparing(id -> h(label, id)).thenComparing(Comparator.naturalOrder());
    }

    /**
     * Returns the Fisher–Yates permutation of {@code 0..n-1} for {@code group}: for {@code i = n-1 .. 1}, swap
     * {@code i} with {@code j = h(permute, group|i) mod (i + 1)}. Entry {@code r} is the source index placed at
     * {@code r}.
     */
    int[] permutation(String group, int n) {
        var order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = h(PERMUTE, group + "|" + i).mod(BigInteger.valueOf(i + 1L)).intValue();
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        return order;
    }
}
