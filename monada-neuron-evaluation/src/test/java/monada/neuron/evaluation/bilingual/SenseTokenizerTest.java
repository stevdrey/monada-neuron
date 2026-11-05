package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseTokenizer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SenseTokenizerTest {

    private final SenseTokenizer tokenizer = new SenseTokenizer();

    @Test
    void appliesNfkcCaseFoldingAndSeparatesPunctuation() {
        List<Token> tokens = tokenizer.tokenize("The ＢＡＮＫ's ﬁle, closed!");
        assertEquals(List.of("the", "bank", "'", "s", "file", ",", "closed", "!"),
                tokens.stream().map(Token::text).toList());
        assertEquals(new Token("bank", 4, 8, false), tokens.get(1));
        assertEquals(new Token("'", 8, 9, true), tokens.get(2));
    }

    @Test
    void offsetsTrackStoredCodePointsThroughComposition() {
        // "dátil" stored decomposed: d a U+0301 t i l (6 code points) normalizes to 5.
        String sentence = "un dátil dulce";
        List<Token> tokens = tokenizer.tokenize(sentence);
        assertEquals(new Token("dátil", 3, 9, false), tokens.get(1));
        assertEquals(1, tokenizer.tokenAt(tokens, 3, 9));
        assertThrows(IllegalArgumentException.class, () -> tokenizer.tokenAt(tokens, 3, 8));
    }

    @Test
    void windowExcludesTheTargetAndRespectsBounds() {
        assertArrayEquals(new int[] {1, 2, 4, 5}, tokenizer.window(9, 3, 2));
        assertArrayEquals(new int[] {0, 2, 3}, tokenizer.window(4, 1, 5));
        assertArrayEquals(new int[] {0, 1, 2, 4, 5, 6, 7, 8}, tokenizer.window(9, 3, SenseTokenizer.SENTENCE));
        assertArrayEquals(new int[0], tokenizer.window(1, 0, 3));
    }

    @Test
    void maskedSequenceReplacesTheTargetAndDropsPunctuation() {
        List<Token> tokens = tokenizer.tokenize("Bank!");
        assertEquals(List.of(SenseTokenizer.TARGET), tokenizer.masked(tokens, 0));
        List<Token> other = tokenizer.tokenize("The bank, again.");
        assertEquals(List.of("the", SenseTokenizer.TARGET, "again"), tokenizer.masked(other, 1));
    }

    @Test
    void matchesWholeDeclaredFormsOnly() {
        List<Token> tokens = tokenizer.tokenize("Banks bank embankment bank's");
        assertEquals(List.of(0, 1, 3), tokenizer.matches(tokens, Set.of("bank", "banks")));
    }
}
