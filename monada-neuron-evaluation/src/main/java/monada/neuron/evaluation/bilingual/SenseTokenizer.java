package monada.neuron.evaluation.bilingual;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The registered tokenization, identical for every arm: Unicode NFKC, case folding with {@link Locale#ROOT}, split on
 * whitespace, punctuation as separate tokens.
 *
 * <p>Offsets are tracked through normalization (v2 occurrence id): the stored text is normalized one base code point
 * plus its following combining marks at a time, so a composed or decomposed accent maps to the code points it came from.
 * Every token records the code-point span of the stored text it was built from.
 */
final class SenseTokenizer {

    /** The token that replaces the target span in a masked sequence (near-duplicate rule). */
    static final String TARGET = "⟨T⟩";

    /** Window half-width meaning the whole sentence ({@code w = S}). */
    static final int SENTENCE = Integer.MAX_VALUE;

    /**
     * One token.
     *
     * @param text normalized, case-folded text
     * @param start inclusive code-point offset in the stored sentence
     * @param end exclusive code-point offset in the stored sentence
     * @param punctuation whether the token is a single punctuation code point
     */
    record Token(String text, int start, int end, boolean punctuation) {
    }

    /** Tokenizes a stored sentence. */
    List<Token> tokenize(String sentence) {
        var tokens = new ArrayList<Token>();
        int[] points = sentence.codePoints().toArray();
        var current = new StringBuilder();
        int currentStart = -1;
        int currentEnd = -1;
        int i = 0;
        while (i < points.length) {
            int chunkEnd = i + 1;
            while (chunkEnd < points.length && combining(points[chunkEnd])) {
                chunkEnd++;
            }
            String chunk = Normalizer.normalize(new String(points, i, chunkEnd - i), Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            for (int cp : chunk.codePoints().toArray()) {
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                    currentStart = flush(tokens, current, currentStart, currentEnd);
                } else if (punctuation(cp)) {
                    currentStart = flush(tokens, current, currentStart, currentEnd);
                    tokens.add(new Token(Character.toString(cp), i, chunkEnd, true));
                } else {
                    if (currentStart < 0) {
                        currentStart = i;
                    }
                    current.appendCodePoint(cp);
                    currentEnd = chunkEnd;
                }
            }
            i = chunkEnd;
        }
        flush(tokens, current, currentStart, currentEnd);
        return tokens;
    }

    /** Returns the index of the token whose span is exactly {@code [start, end)}. */
    int tokenAt(List<Token> tokens, int start, int end) {
        for (int t = 0; t < tokens.size(); t++) {
            if (tokens.get(t).start() == start && tokens.get(t).end() == end) {
                return t;
            }
        }
        throw new IllegalArgumentException("no token spans [" + start + ", " + end + ")");
    }

    /**
     * Returns the positions of the context window: at most {@code w} tokens on each side of {@code target}, excluding
     * the target, in sentence order. {@code w = }{@link #SENTENCE} is the whole sentence.
     */
    int[] window(int tokenCount, int target, int w) {
        int from = w == SENTENCE ? 0 : Math.max(0, target - w);
        int to = w == SENTENCE ? tokenCount : (int) Math.min(tokenCount, (long) target + w + 1);
        var positions = new int[to - from - (target >= from && target < to ? 1 : 0)];
        int n = 0;
        for (int p = from; p < to; p++) {
            if (p != target) {
                positions[n++] = p;
            }
        }
        return positions;
    }

    /** Returns the masked sequence of the near-duplicate rule: target replaced by {@link #TARGET}, punctuation removed. */
    List<String> masked(List<Token> tokens, int target) {
        var sequence = new ArrayList<String>(tokens.size());
        for (int t = 0; t < tokens.size(); t++) {
            if (t == target) {
                sequence.add(TARGET);
            } else if (!tokens.get(t).punctuation()) {
                sequence.add(tokens.get(t).text());
            }
        }
        return sequence;
    }

    private int flush(List<Token> tokens, StringBuilder current, int start, int end) {
        if (!current.isEmpty()) {
            tokens.add(new Token(current.toString(), start, end, false));
            current.setLength(0);
        }
        return -1;
    }

    private boolean combining(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private boolean punctuation(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }
}
