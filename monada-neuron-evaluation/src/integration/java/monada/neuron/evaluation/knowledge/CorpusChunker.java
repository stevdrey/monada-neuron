package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical, deterministic chunking of one normalized Markdown document (chunking policy {@value #POLICY}).
 *
 * <ol>
 *   <li>Outside fenced code, an ATX heading line ({@code #} to {@code ######}) starts a new section. A section
 *       whose heading has no body (for example a heading directly followed by a subheading) is merged into the
 *       next section; text before the first heading forms a {@value #PREAMBLE} section.</li>
 *   <li>A section is one chunk when its UTF-8 size fits the bound. Otherwise it is split greedily at block
 *       boundaries (blank lines, heading lines, fences), and a paragraph that alone exceeds the bound is split
 *       at sentence ends ({@code .}, {@code !} or {@code ?} followed by whitespace). When headings do not fit
 *       together with the paragraph that follows them, that paragraph is split at sentence ends so the headings
 *       keep its first sentences; when nothing after them fits (a code block or a single sentence), the headings
 *       form their own chunk.</li>
 *   <li>A fenced code block (opened by a line starting with three backticks, closed by a line of exactly three
 *       backticks) is never split; an oversize code block, heading or sentence is rejected, never truncated.</li>
 * </ol>
 *
 * <p>Spans are half-open {@code [start, end)} Unicode code point offsets into the normalized document, trimmed of
 * surrounding whitespace, so the span substring always equals the chunk text. The pass is linear in the document
 * length: code point and byte offsets come from prefix arrays built once.
 */
public final class CorpusChunker {

    /** Versioned name of this chunking policy; changing the rules requires a new name and corpus version. */
    public static final String POLICY = "heading-section-v1 (paragraph, then sentence fallback; fences intact)";

    /** Section label of text that precedes the first heading. */
    public static final String PREAMBLE = "(preamble)";

    private static final Pattern HEADING = Pattern.compile("#{1,6}[ \\t]+(\\S.*)");
    private static final String FENCE = "```";

    private final int maxChunkBytes;

    /** Creates a chunker whose chunks hold at most {@code maxChunkBytes} UTF-8 bytes. */
    public CorpusChunker(int maxChunkBytes) {
        if (maxChunkBytes <= 0) {
            throw new IllegalArgumentException("maxChunkBytes must be positive");
        }
        this.maxChunkBytes = maxChunkBytes;
    }

    /**
     * One chunk of a document.
     *
     * @param start first code point offset (inclusive)
     * @param end last code point offset (exclusive)
     * @param section text of the governing heading, or {@value #PREAMBLE}
     * @param text exact span substring of the normalized document
     */
    public record Span(int start, int end, String section, String text) {

        public Span {
            Objects.requireNonNull(section, "section must not be null");
            Objects.requireNonNull(text, "text must not be null");
            if (start < 0 || start >= end) {
                throw new IllegalArgumentException("span must satisfy 0 <= start < end");
            }
        }
    }

    private enum Kind { HEADING, PARAGRAPH, SENTENCE, CODE }

    /** Block in char offsets: a heading line, a paragraph, one sentence of a paragraph, or a fenced code block. */
    private record Block(Kind kind, int start, int end, String heading) {
    }

    /** Chunks a normalized document; {@code origin} names it in diagnostics. */
    public List<Span> chunk(String document, String origin) {
        Objects.requireNonNull(document, "document must not be null");
        if (document.isBlank()) {
            throw new CorpusValidationException(origin, "document is empty");
        }
        var offsets = new Offsets(document);
        var spans = new ArrayList<Span>();
        for (var section : sections(blocks(document, origin))) {
            pack(document, offsets, section, origin, spans);
        }
        return List.copyOf(spans);
    }

    private List<Block> blocks(String document, String origin) {
        var blocks = new ArrayList<Block>();
        var paragraphStart = -1;
        var paragraphEnd = -1;
        var fenceStart = -1;
        var lineStart = 0;
        while (lineStart <= document.length()) {
            var newline = document.indexOf('\n', lineStart);
            var lineEnd = newline < 0 ? document.length() : newline;
            var line = document.substring(lineStart, lineEnd);
            if (fenceStart >= 0) {
                if (line.equals(FENCE)) {
                    blocks.add(trimmed(document, Kind.CODE, fenceStart, lineEnd, null));
                    fenceStart = -1;
                }
            } else if (line.startsWith(FENCE) || line.isBlank() || HEADING.matcher(line).matches()) {
                if (paragraphStart >= 0) {
                    blocks.add(trimmed(document, Kind.PARAGRAPH, paragraphStart, paragraphEnd, null));
                    paragraphStart = -1;
                }
                if (line.startsWith(FENCE)) {
                    fenceStart = lineStart;
                } else if (!line.isBlank()) {
                    var matcher = HEADING.matcher(line);
                    matcher.matches();
                    blocks.add(trimmed(document, Kind.HEADING, lineStart, lineEnd, matcher.group(1).strip()));
                }
            } else {
                if (paragraphStart < 0) {
                    paragraphStart = lineStart;
                }
                paragraphEnd = lineEnd;
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        if (fenceStart >= 0) {
            throw new CorpusValidationException(origin, "unterminated fenced code block at char " + fenceStart);
        }
        if (paragraphStart >= 0) {
            blocks.add(trimmed(document, Kind.PARAGRAPH, paragraphStart, paragraphEnd, null));
        }
        return blocks;
    }

    private record Section(String label, List<Block> blocks) {
    }

    private List<Section> sections(List<Block> blocks) {
        var sections = new ArrayList<Section>();
        var current = new ArrayList<Block>();
        var label = PREAMBLE;
        var hasBody = false;
        for (var block : blocks) {
            if (block.kind() == Kind.HEADING) {
                if (hasBody) {
                    sections.add(new Section(label, List.copyOf(current)));
                    current.clear();
                    hasBody = false;
                }
                label = block.heading();
            } else {
                hasBody = true;
            }
            current.add(block);
        }
        if (!current.isEmpty()) {
            sections.add(new Section(label, List.copyOf(current)));
        }
        return sections;
    }

    private void pack(String document, Offsets offsets, Section section, String origin, List<Span> spans) {
        var units = new ArrayList<Block>();
        for (var block : section.blocks()) {
            if (offsets.bytes(block.start(), block.end()) <= maxChunkBytes) {
                units.add(block);
            } else if (block.kind() == Kind.PARAGRAPH) {
                units.addAll(sentences(document, offsets, block, section, origin));
            } else {
                throw oversize(origin, section, block.kind() == Kind.CODE ? "code example" : "heading",
                        offsets.bytes(block.start(), block.end()));
            }
        }
        var first = 0;
        var i = 1;
        while (i < units.size()) {
            if (offsets.bytes(units.get(first).start(), units.get(i).end()) <= maxChunkBytes) {
                i++;
                continue;
            }
            var headingsOnly = units.subList(first, i).stream().allMatch(unit -> unit.kind() == Kind.HEADING);
            if (headingsOnly && units.get(i).kind() == Kind.PARAGRAPH) {
                // Keep the heading with as much of its first paragraph as fits: retry with the paragraph's sentences.
                var split = sentences(document, offsets, units.get(i), section, origin);
                if (split.size() > 1) {
                    units.remove(i);
                    units.addAll(i, split);
                    continue;
                }
            }
            // A heading group that fits nothing after it becomes its own chunk instead of being rejected.
            spans.add(span(document, offsets, units.get(first).start(), units.get(i - 1).end(), section.label()));
            first = i;
            i++;
        }
        spans.add(span(document, offsets, units.get(first).start(), units.getLast().end(), section.label()));
    }

    private List<Block> sentences(String document, Offsets offsets, Block paragraph, Section section, String origin) {
        var sentences = new ArrayList<Block>();
        var start = paragraph.start();
        for (var i = paragraph.start(); i < paragraph.end(); i++) {
            var c = document.charAt(i);
            var boundary = (c == '.' || c == '!' || c == '?')
                    && (i + 1 == paragraph.end() || Character.isWhitespace(document.charAt(i + 1)));
            if (boundary) {
                sentences.add(trimmed(document, Kind.SENTENCE, start, i + 1, null));
                start = i + 1;
            }
        }
        if (!document.substring(start, paragraph.end()).isBlank()) {
            sentences.add(trimmed(document, Kind.SENTENCE, start, paragraph.end(), null));
        }
        for (var sentence : sentences) {
            var bytes = offsets.bytes(sentence.start(), sentence.end());
            if (bytes > maxChunkBytes) {
                throw oversize(origin, section, "sentence", bytes);
            }
        }
        return sentences;
    }

    /**
     * Creates a block over {@code [start, end)} without surrounding whitespace, so every size the packer measures is
     * exactly the size of the text {@link #span} emits.
     */
    private Block trimmed(String document, Kind kind, int start, int end, String heading) {
        while (start < end && Character.isWhitespace(document.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(document.charAt(end - 1))) {
            end--;
        }
        return new Block(kind, start, end, heading);
    }

    private Span span(String document, Offsets offsets, int start, int end, String section) {
        while (start < end && Character.isWhitespace(document.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(document.charAt(end - 1))) {
            end--;
        }
        return new Span(offsets.codePoint(start), offsets.codePoint(end), section, document.substring(start, end));
    }

    private CorpusValidationException oversize(String origin, Section section, String what, int bytes) {
        return new CorpusValidationException(origin, "section '" + section.label() + "': " + what + " of " + bytes
                + " UTF-8 bytes exceeds the chunk bound of " + maxChunkBytes + " bytes and cannot be split");
    }

    /** Code point and UTF-8 byte offsets before every char index, built in one pass. */
    private static final class Offsets {

        private final int[] codePoints;
        private final int[] bytes;

        Offsets(String text) {
            codePoints = new int[text.length() + 1];
            bytes = new int[text.length() + 1];
            var i = 0;
            while (i < text.length()) {
                var codePoint = text.codePointAt(i);
                var width = Character.charCount(codePoint);
                var encoded = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
                for (var k = 1; k <= width; k++) {
                    codePoints[i + k] = codePoints[i] + 1;
                    bytes[i + k] = bytes[i] + encoded;
                }
                i += width;
            }
        }

        int codePoint(int charIndex) {
            return codePoints[charIndex];
        }

        int bytes(int start, int end) {
            return bytes[end] - bytes[start];
        }
    }
}
