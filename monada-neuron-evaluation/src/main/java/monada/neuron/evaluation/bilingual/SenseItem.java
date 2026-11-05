package monada.neuron.evaluation.bilingual;

import java.util.Objects;
import java.util.Set;

/**
 * The registered system input of one item, and nothing else: the direction, the lemma, the frozen inventory of that
 * {@code (direction, lemma)}, the source sentence and the span. No translation, alignment, pass-B label, document
 * neighbour, source, family or split field exists on this type, so no arm can read one (check
 * {@code leakage.input-surface}).
 *
 * @param direction language direction
 * @param lemma the lemma of the declared form at the span
 * @param inventory the frozen inventory, or {@code null} for a lemma with no inventory (every arm then abstains)
 * @param sentence the source sentence as stored in the source file
 * @param start inclusive span start, in code points of {@code sentence}
 * @param end exclusive span end, in code points of {@code sentence}
 */
record SenseItem(Direction direction, String lemma, SenseInventory inventory, String sentence, int start, int end) {

    /** The only record components the input surface may carry. */
    static final Set<String> ALLOWED_COMPONENTS = Set.of("direction", "lemma", "inventory", "sentence", "start", "end");

    /** Validates the span and the inventory's direction and lemma. */
    SenseItem {
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(lemma, "lemma must not be null");
        Objects.requireNonNull(sentence, "sentence must not be null");
        int length = sentence.codePointCount(0, sentence.length());
        if (start < 0 || end <= start || end > length) {
            throw new IllegalArgumentException("span [" + start + ", " + end + ") is outside the sentence");
        }
        if (inventory != null && (inventory.direction() != direction || !inventory.lemma().equals(lemma))) {
            throw new IllegalArgumentException("inventory of " + inventory.lemma() + " does not match " + lemma);
        }
    }

    /** Returns the {@code (direction, lemma)} key, {@code <direction label>|<lemma>}, that per-lemma models use. */
    String lemmaKey() {
        return direction.label() + "|" + lemma;
    }

    /** Returns whether the lemma has a frozen inventory. */
    boolean hasInventory() {
        return inventory != null;
    }
}
