package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlTokensTest {

    @Test
    void tokensAreLowercasedLetterOrDigitRuns() {
        assertEquals(List.of("what", "is", "the", "html", "a", "element", "for"),
                HtmlTokens.tokens("What is the HTML <a> element for?"));
        assertEquals(List.of("target", "blank", "rel", "noopener"),
                HtmlTokens.tokens("target=\"_blank\" rel=\"noopener\""));
        assertEquals(List.of("h1", "élément", "2024"), HtmlTokens.tokens("<H1> Élément 2024"));
        assertEquals(List.of(), HtmlTokens.tokens("   \t\n"));
        assertEquals(List.of(), HtmlTokens.tokens(""));
    }

    @Test
    void distinctKeepsFirstOccurrenceOrder() {
        assertEquals(List.of("b", "a"), List.copyOf(HtmlTokens.distinct("b a B A b")));
    }
}
