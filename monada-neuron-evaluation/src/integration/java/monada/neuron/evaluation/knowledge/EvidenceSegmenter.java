package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The B3 segment rule of {@code html-knowledge-protocol-v1} (Issue #109): each fenced code block is one segment; the
 * remaining prose is split after {@code .}, {@code ?} or {@code !} followed by whitespace or the end of the text, and at
 * blank lines; segments are trimmed and empty segments dropped.
 *
 * <p>A fence opens on a line that starts with three backticks and closes on the next such line, both fence lines
 * included in the segment; an unclosed fence runs to the end of the text. Every offset is a code point index into the
 * input, so a segment's span cites exactly its text. One pass over the code points: {@code O(length)}.
 */
final class EvidenceSegmenter {

    private static final String FENCE = "```";

    /**
     * One segment.
     *
     * @param start first code point (inclusive)
     * @param end last code point (exclusive)
     * @param text the code points {@code [start, end)} of the input
     */
    record Segment(int start, int end, String text) {

        Segment {
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    /** Returns the segments of {@code text} in source order. */
    List<Segment> segments(String text) {
        Objects.requireNonNull(text, "text must not be null");
        var codePoints = text.codePoints().toArray();
        var segments = new ArrayList<Segment>();
        var proseStart = 0;
        var lineStart = 0;
        var fenceStart = -1;
        while (lineStart < codePoints.length) {
            var lineEnd = lineStart;
            while (lineEnd < codePoints.length && codePoints[lineEnd] != '\n') {
                lineEnd++;
            }
            var next = Math.min(lineEnd + 1, codePoints.length);
            var fence = startsWithFence(codePoints, lineStart, lineEnd);
            if (fenceStart >= 0) {
                if (fence) {
                    add(codePoints, fenceStart, lineEnd, segments);
                    fenceStart = -1;
                    proseStart = next;
                }
            } else if (fence) {
                prose(codePoints, proseStart, lineStart, segments);
                fenceStart = lineStart;
            } else if (blank(codePoints, lineStart, lineEnd)) {
                prose(codePoints, proseStart, lineStart, segments);
                proseStart = next;
            }
            lineStart = next;
        }
        if (fenceStart >= 0) {
            add(codePoints, fenceStart, codePoints.length, segments);
        } else {
            prose(codePoints, proseStart, codePoints.length, segments);
        }
        return segments;
    }

    /** Splits the prose {@code [start, end)} into sentences. */
    private void prose(int[] codePoints, int start, int end, List<Segment> segments) {
        var sentenceStart = start;
        for (var index = start; index < end; index++) {
            var codePoint = codePoints[index];
            if ((codePoint == '.' || codePoint == '?' || codePoint == '!')
                    && (index + 1 == end || Character.isWhitespace(codePoints[index + 1]))) {
                add(codePoints, sentenceStart, index + 1, segments);
                sentenceStart = index + 1;
            }
        }
        add(codePoints, sentenceStart, end, segments);
    }

    /** Adds {@code [start, end)} trimmed of whitespace, unless nothing is left. */
    private void add(int[] codePoints, int start, int end, List<Segment> segments) {
        while (start < end && Character.isWhitespace(codePoints[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(codePoints[end - 1])) {
            end--;
        }
        if (start < end) {
            segments.add(new Segment(start, end, new String(codePoints, start, end - start)));
        }
    }

    private boolean startsWithFence(int[] codePoints, int start, int end) {
        if (end - start < FENCE.length()) {
            return false;
        }
        for (var index = 0; index < FENCE.length(); index++) {
            if (codePoints[start + index] != FENCE.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private boolean blank(int[] codePoints, int start, int end) {
        for (var index = start; index < end; index++) {
            if (!Character.isWhitespace(codePoints[index])) {
                return false;
            }
        }
        return true;
    }
}
