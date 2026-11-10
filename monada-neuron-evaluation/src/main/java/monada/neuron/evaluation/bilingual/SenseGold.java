package monada.neuron.evaluation.bilingual;

/**
 * Adjudicated label of one item: a non-empty set of clusters ({@code ANS}), {@code UNANSWERABLE} ({@code UNA}) or
 * {@code OUT_OF_INVENTORY} ({@code OOI}). Under {@code bilingual-sense/2} every gold label is {@code agent-gold}: a
 * model judgement, never human annotation (v2 R4).
 */
sealed interface SenseGold {

    /** {@code UNA}: the source sentence does not determine a cluster. */
    SenseGold UNANSWERABLE = new Abstain(false);

    /** {@code OOI}: the occurrence is not a sense of the inventory (for example a homograph of another lemma). */
    SenseGold OUT_OF_INVENTORY = new Abstain(true);

    /** Returns whether the correct output is an answer rather than {@code ABSTAIN}. */
    boolean answerable();

    /** Returns the gold of a non-empty cluster set given as a bit mask over cluster ordinals. */
    static SenseGold answerable(int mask) {
        return new Answerable(mask);
    }

    /**
     * {@code ANS} gold: the bit mask of acceptable cluster ordinals.
     *
     * @param mask non-zero bit mask, bit {@code k} set when cluster {@code k} is acceptable
     */
    record Answerable(int mask) implements SenseGold {

        /** Rejects an empty set. */
        public Answerable {
            if (mask == 0) {
                throw new IllegalArgumentException("an ANS gold set must not be empty");
            }
        }

        @Override
        public boolean answerable() {
            return true;
        }

        /** Returns whether cluster {@code k} is in the gold set. */
        boolean contains(int k) {
            return k >= 0 && k < Integer.SIZE && (mask & (1 << k)) != 0;
        }

        /** Returns {@code |G|}. */
        int size() {
            return Integer.bitCount(mask);
        }
    }

    /**
     * {@code UNA} or {@code OOI} gold: the correct output is {@code ABSTAIN}.
     *
     * @param outOfInventory {@code true} for {@code OOI}, {@code false} for {@code UNA}
     */
    record Abstain(boolean outOfInventory) implements SenseGold {

        @Override
        public boolean answerable() {
            return false;
        }
    }
}
