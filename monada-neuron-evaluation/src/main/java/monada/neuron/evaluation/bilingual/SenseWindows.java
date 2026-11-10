package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseTokenizer.Token;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context windows of items, shared by every arm so each sees the same tokens: the tokens of the source sentence,
 * excluding the target span, at most {@code w} on each side. A per-run cache keeps one tokenization per distinct item.
 */
final class SenseWindows {

    /**
     * A window.
     *
     * @param tokens window token texts, in sentence order
     * @param positions sentence token index of each window token
     */
    record Window(String[] tokens, int[] positions) {

        /** The empty window, used by check {@code nc.context-mask}. */
        static final Window EMPTY = new Window(new String[0], new int[0]);
    }

    private record Tokenized(List<Token> tokens, int target) {
    }

    private final SenseTokenizer tokenizer;
    private final Map<SenseItem, Tokenized> cache = new HashMap<>();

    SenseWindows(SenseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /** Drops every cached tokenization, so the next window of each item tokenizes it again (cost measurement). */
    void clear() {
        cache.clear();
    }

    /** Returns the window of half-width {@code w} of an item. */
    Window window(SenseItem item, int w) {
        Tokenized tokenized = cache.computeIfAbsent(item, this::tokenize);
        int[] positions = tokenizer.window(tokenized.tokens().size(), tokenized.target(), w);
        var texts = new String[positions.length];
        for (int i = 0; i < positions.length; i++) {
            texts[i] = tokenized.tokens().get(positions[i]).text();
        }
        return new Window(texts, positions);
    }

    private Tokenized tokenize(SenseItem item) {
        List<Token> tokens = tokenizer.tokenize(item.sentence());
        return new Tokenized(tokens, tokenizer.tokenAt(tokens, item.start(), item.end()));
    }
}
