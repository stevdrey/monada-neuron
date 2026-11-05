package monada.neuron.evaluation.bilingual;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Family ids, occurrence ids and the split assignment of {@code bilingual-sense/1} with the v2 clarifications
 * (R13, R14). Pure functions over ids; no label is read.
 */
final class SensePartitions {

    /** The split of a family. */
    enum Split {
        /** Guideline calibration only; never in any other set. */
        CALIBRATION(0.05),
        /** Fitting and pilot. */
        TRAINING(0.50),
        /** Every selection: windows, grid points, thresholds. */
        VALIDATION(0.70),
        /** Sealed; read by systems once, at step 8. */
        HELD_OUT(1.00);

        private final double cumulativeBoundary;

        Split(double cumulativeBoundary) {
            this.cumulativeBoundary = cumulativeBoundary;
        }

        /** Returns the registered cumulative boundary of the split. */
        double cumulativeBoundary() {
            return cumulativeBoundary;
        }
    }

    /**
     * A family to assign.
     *
     * @param familyId the family id
     * @param primaryStratum direction and lowest-ordinal declared lemma of the family (fixed before any gate)
     */
    record Family(String familyId, String primaryStratum) {
    }

    /** Byte-wise UTF-8 order, which is code-point order (unlike {@link String#compareTo}, UTF-16 order). */
    static final Comparator<String> UTF8_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private final KeyedHash hash;

    SensePartitions(KeyedHash hash) {
        this.hash = Objects.requireNonNull(hash, "hash must not be null");
    }

    /** Returns {@code tatoeba:<sentence id>} (v2 R13). */
    String tatoebaKey(long sentenceId) {
        return "tatoeba:" + sentenceId;
    }

    /** Returns {@code europarl:<row>:<lang>} (v2 R13), {@code row} 0-based over data rows. */
    String europarlKey(long row, String language) {
        if (!language.equals("eng") && !language.equals("spa")) {
            throw new IllegalArgumentException("language must be eng or spa, got " + language);
        }
        return "europarl:" + row + ":" + language;
    }

    /** Returns the family id: lowercase hex {@code SHA-256(UTF-8(k_min))}, {@code k_min} in byte-wise UTF-8 order. */
    String familyId(Collection<String> sentenceKeys) {
        String min = sentenceKeys.stream().min(UTF8_ORDER)
                .orElseThrow(() -> new IllegalArgumentException("a family needs at least one sentence"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(min.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Returns the occurrence id {@code direction|lemma|sentenceKey|start|end} (v2 R14). */
    String occurrenceId(Direction direction, String lemma, String sentenceKey, int start, int end) {
        return direction.label() + "|" + lemma + "|" + sentenceKey + "|" + start + "|" + end;
    }

    /**
     * Returns the direction of a family: the only source language it holds, or, with both, {@code EN>ES} when
     * {@code u(direction, family id) < 0.5}, else {@code ES>EN}.
     */
    Direction direction(String familyId, boolean hasEnglish, boolean hasSpanish) {
        if (hasEnglish != hasSpanish) {
            return hasEnglish ? Direction.EN_ES : Direction.ES_EN;
        }
        if (!hasEnglish) {
            throw new IllegalArgumentException("family " + familyId + " holds no source sentence");
        }
        return hash.u(KeyedHash.DIRECTION, familyId) < 0.5 ? Direction.EN_ES : Direction.ES_EN;
    }

    /**
     * Assigns every family to a split: within each primary stratum, families in increasing {@code h(split, family id)}
     * are indexed {@code i = 0..n-1}; family {@code i} goes to the first split whose cumulative boundary exceeds
     * {@code (i + 0.5) / n}.
     */
    Map<String, Split> assign(List<Family> families) {
        var strata = new LinkedHashMap<String, List<String>>();
        for (Family family : families) {
            strata.computeIfAbsent(family.primaryStratum(), _ -> new ArrayList<>()).add(family.familyId());
        }
        var splits = new LinkedHashMap<String, Split>();
        for (List<String> members : strata.values()) {
            members.sort(hash.order(KeyedHash.SPLIT));
            int n = members.size();
            for (int i = 0; i < n; i++) {
                double quantile = (i + 0.5) / n;
                Split split = Split.HELD_OUT;
                for (Split candidate : Split.values()) {
                    if (candidate.cumulativeBoundary() > quantile) {
                        split = candidate;
                        break;
                    }
                }
                if (splits.put(members.get(i), split) != null) {
                    throw new IllegalArgumentException("family " + members.get(i) + " appears twice");
                }
            }
        }
        return splits;
    }
}
