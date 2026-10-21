package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * The versioned distractor generator of the scaling family (Issue #111, extension {@code scaling-v1}): expands a fixed
 * set of authored templates into chunks {@code chk:synthetic-<nnnn>#00}, one single-chunk document each, under the
 * same normalization, hashing and size bounds as authored chunks.
 *
 * <p>Every template is about a web topic that no {@code html-kq-v1} query asks about (CSS units, colors, selectors,
 * flexbox and grid, HTTP caching and status codes, SVG paths, character encoding), and none states a fact about the
 * HTML elements, attributes or behaviors the queries cover or about a non-answerable query's topic. The templates share
 * generic vocabulary with the queries (element, attribute, browser, page), so they compete in a lexical ranking: they
 * are distractors by construction, never relevant. Changing a template, the selection rule or the heading bumps
 * {@link #VERSION}.
 *
 * <p>Distractor {@code i} (1-based) draws a topic and three of that topic's sentences from one
 * {@link SplittableRandom} stream seeded with {@code seed}, in order, so {@code generate(m, seed)} is a prefix of
 * {@code generate(n, seed)} for {@code m <= n}: a smaller point's distractors are a subset of a larger point's. The
 * heading carries the ordinal, so every text is unique. Cost: {@code O(count)} time and output size.
 */
final class ScalingDistractorGenerator {

    /** Generator version; part of every generated chunk's source. */
    static final String VERSION = "html-kd-v1";

    /** Default seed of the scaling family. */
    static final long DEFAULT_SEED = 111L;

    /** Upper bound on generated distractors, the largest size point. */
    static final int MAX_COUNT = 500;

    /** License of the authored templates and of every generated chunk. */
    static final String LICENSE = HtmlKnowledgeCorpus.AUTHORED_LICENSE;

    /** Attribution of every generated chunk. */
    static final String ATTRIBUTION = "Monada Neuron contributors (generated from authored templates)";

    private static final int SENTENCES_PER_CHUNK = 3;

    /** One template topic: its slug, its heading and its authored sentences. */
    private record Topic(String slug, String title, List<String> sentences) {
    }

    private static final List<Topic> TOPICS = List.of(
            new Topic("css-units", "CSS length units", List.of(
                    "The rem unit sizes a property relative to the root element's font size.",
                    "The em unit is relative to the font size of the element itself.",
                    "The vw and vh units scale with the size of the browser window.",
                    "Percentages in CSS resolve against a property-specific reference box.",
                    "The ch unit approximates the width of the zero character in the current font.",
                    "Absolute units such as px are anchored to a reference pixel, not a device pixel.")),
            new Topic("css-color", "CSS color notations", List.of(
                    "A hex color uses three or six hexadecimal digits for red, green and blue.",
                    "The rgb() function accepts an optional alpha channel for transparency.",
                    "The hsl() notation describes a color by hue, saturation and lightness.",
                    "The currentColor keyword reuses the computed color property of the element.",
                    "Named colors such as rebeccapurple map to fixed sRGB values.",
                    "The transparent keyword is fully transparent black.")),
            new Topic("css-selectors", "CSS selector specificity", List.of(
                    "An id selector is more specific than a class or attribute selector.",
                    "The universal selector adds no specificity to a rule.",
                    "When two rules have equal specificity, the later one in the style sheet wins.",
                    "An important declaration overrides normal declarations of the same origin.",
                    "The :is() pseudo-class takes the specificity of its most specific argument.",
                    "The :where() pseudo-class always contributes zero specificity.")),
            new Topic("css-layout", "Flexbox and grid alignment", List.of(
                    "The justify-content property distributes flex items along the main axis.",
                    "The align-items property aligns flex items on the cross axis.",
                    "A grid container defines tracks with grid-template-columns.",
                    "The fr unit shares the free space of a grid container between tracks.",
                    "The gap property sets the gutters between grid rows and columns.",
                    "The order property changes the visual order of flex items, not the source order.")),
            new Topic("http-caching", "HTTP caching headers", List.of(
                    "The Cache-Control header with max-age sets how long a response stays fresh.",
                    "An ETag header lets a client revalidate a stored response with If-None-Match.",
                    "A 304 Not Modified response tells the client to reuse its stored copy.",
                    "The no-store directive forbids any cache from keeping the response.",
                    "The Vary header names request headers that select a cached variant.",
                    "The immutable directive says a fresh response will not change while it is fresh.")),
            new Topic("http-status", "HTTP status codes", List.of(
                    "A 404 status code means the server found no resource for the request target.",
                    "A 301 status code marks a permanent redirect to another location.",
                    "A 503 status code signals that the server is temporarily unavailable.",
                    "A 401 status code asks the client to authenticate before retrying.",
                    "A 429 status code tells the client it sent too many requests.",
                    "A 204 status code means the request succeeded with no response body.")),
            new Topic("svg-paths", "SVG path commands", List.of(
                    "The M command of an SVG path moves the current point without drawing.",
                    "The L command draws a straight line to the given coordinates.",
                    "The C command draws a cubic Bezier curve with two control points.",
                    "The Z command closes the current subpath back to its start.",
                    "Lowercase path commands take coordinates relative to the current point.",
                    "The viewBox attribute of an svg element maps user coordinates to its drawing area.")),
            new Topic("text-encoding", "Character encoding", List.of(
                    "UTF-8 encodes every Unicode code point in one to four bytes.",
                    "A byte order mark at the start of a file can signal its encoding.",
                    "Percent-encoding writes a reserved URL character as a percent sign and two hex digits.",
                    "Unicode normalization form NFC composes characters where possible.",
                    "A surrogate pair in UTF-16 represents a code point outside the basic plane.",
                    "ASCII text is also valid UTF-8 byte for byte.")));

    private final CorpusNormalizer normalizer = new CorpusNormalizer();
    private final CorpusChunker chunker = new CorpusChunker(CorpusBounds.SCALING.maxChunkBytes());

    /**
     * One generated single-chunk document.
     *
     * @param document the generated document
     * @param chunk its only chunk, spanning the whole document
     */
    record Distractor(CorpusDocument document, CorpusChunk chunk) {
    }

    /** Returns the source label of every chunk generated with {@code seed}. */
    static String source(long seed) {
        return "generated:" + VERSION + ":" + seed;
    }

    /** Returns the document id of distractor {@code ordinal} (1-based). */
    static String documentId(int ordinal) {
        return String.format(Locale.ROOT, "doc:synthetic-%04d", ordinal);
    }

    /**
     * Generates {@code count} distractors with {@code seed}, in ordinal order.
     *
     * @throws IllegalArgumentException when {@code count} is negative or above {@value #MAX_COUNT}
     */
    List<Distractor> generate(int count, long seed) {
        if (count < 0 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be 0.." + MAX_COUNT + ", was " + count);
        }
        var random = new SplittableRandom(seed);
        var source = source(seed);
        var distractors = new ArrayList<Distractor>(count);
        for (var ordinal = 1; ordinal <= count; ordinal++) {
            var topic = TOPICS.get(random.nextInt(TOPICS.size()));
            var pool = new ArrayList<>(topic.sentences());
            var body = new StringBuilder();
            for (var i = 0; i < SENTENCES_PER_CHUNK; i++) {
                body.append(i == 0 ? "" : " ").append(pool.remove(random.nextInt(pool.size())));
            }
            var documentId = documentId(ordinal);
            var raw = String.format(Locale.ROOT, "# %s (synthetic note %04d)\n\n%s\n", topic.title(), ordinal, body);
            var text = normalizer.normalize(raw, documentId);
            var spans = chunker.chunk(text, documentId);
            if (spans.size() != 1) {
                throw new IllegalStateException(documentId + " chunks into " + spans.size() + " spans, not 1");
            }
            var span = spans.getFirst();
            var documentSha256 = CorpusDigests.sha256(text);
            var slug = documentId.substring("doc:".length());
            var document = new CorpusDocument(documentId, "generated/" + slug + ".md", source, LICENSE, ATTRIBUTION,
                    List.of(), documentSha256, text);
            var chunk = new CorpusChunk(HtmlKnowledgeCorpus.chunkId(slug, 0), documentId, topic.slug(),
                    span.section(), source, LICENSE, ATTRIBUTION, span.start(), span.end(),
                    CorpusDigests.sha256(span.text()), documentSha256, Optional.empty(), span.text());
            distractors.add(new Distractor(document, chunk));
        }
        return distractors;
    }

    /** Returns every template sentence, for the label-integrity review and its test. */
    static List<String> templateSentences() {
        return TOPICS.stream().flatMap(topic -> topic.sentences().stream()).toList();
    }
}
