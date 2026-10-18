package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SequencedSet;

/**
 * The protocol's token rule, shared by the B0 baseline, the paraphrase label check and the L2 codec: maximal runs of
 * code points for which {@link Character#isLetterOrDigit(int)} is true, lowercased with {@link Locale#ROOT}.
 * Tokenizing is linear in the text length.
 */
final class HtmlTokens {

    private HtmlTokens() {
    }

    /** Returns every token of {@code text} in source order, repeats included. */
    static List<String> tokens(String text) {
        Objects.requireNonNull(text, "text must not be null");
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint);
            } else if (!current.isEmpty()) {
                tokens.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
        });
        if (!current.isEmpty()) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    /** Returns the distinct tokens of {@code text} in order of first occurrence. */
    static SequencedSet<String> distinct(String text) {
        return new LinkedHashSet<>(tokens(text));
    }
}
