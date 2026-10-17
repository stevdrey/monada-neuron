package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusNormalizerTest {

    private final CorpusNormalizer normalizer = new CorpusNormalizer();

    @Test
    void unifiesLineEndingsComposesToNfcAndTrims() {
        var raw = "  cafe\u0301\r\nline\rend \n\n";

        var normalized = normalizer.normalize(raw.getBytes(StandardCharsets.UTF_8), "raw");

        assertEquals("caf\u00E9\nline\nend", normalized);
        assertTrue(normalizer.isNormalized(normalized));
        assertFalse(normalizer.isNormalized(raw));
    }

    @Test
    void keepsFencedCodeIndentationAndBlankLines() {
        var text = "## Example\n\n```\n<ol>\n    <li>One</li>\n\n</ol>\n```";

        assertEquals(text, normalizer.normalize(text.getBytes(StandardCharsets.UTF_8), "fence"));
    }

    @Test
    void rejectsInvalidUtf8() {
        var invalid = new byte[] {'o', 'k', (byte) 0xC3, (byte) 0x28};

        var failure = assertThrows(CorpusValidationException.class, () -> normalizer.normalize(invalid, "bad.md"));
        assertTrue(failure.getMessage().contains("bad.md: invalid UTF-8"), failure.getMessage());
    }

    @Test
    void rejectsByteOrderMarkAndControlCharacters() {
        var bom = "\uFEFFtext".getBytes(StandardCharsets.UTF_8);
        var control = "text\u0000more".getBytes(StandardCharsets.UTF_8);

        assertTrue(assertThrows(CorpusValidationException.class, () -> normalizer.normalize(bom, "bom"))
                .getMessage().contains("byte-order mark"));
        assertTrue(assertThrows(CorpusValidationException.class, () -> normalizer.normalize(control, "nul"))
                .getMessage().contains("U+0000"));
    }
}
