package monada.neuron.evaluation.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Opt-in external corpus from a user-supplied, version-pinned local snapshot of MDN's tutorial
 * <em>HTML: Creating the content</em>, as the Markdown source file of the {@code mdn/content} repository.
 *
 * <p>Nothing is downloaded: the caller names the file, the {@code mdn/content} commit it was taken from, and the
 * SHA-256 of its raw bytes. A missing file, a different digest or an invalid revision fails explicitly. The YAML
 * front matter is removed, then the same normalization, canonical chunking and bounds as the authored corpus apply
 * (with the single-document {@link CorpusBounds#EXTERNAL} limits) and the text policy
 * {@link HtmlKnowledgeCorpus.TextPolicy#REDACTED}. No HTML, macro or link is parsed or rewritten.
 *
 * <p>MDN prose is licensed CC BY-SA 2.5 by Mozilla Contributors. Every chunk carries that license, the attribution,
 * the source URL and the pinned revision; the modification (normalized and chunked) is stated in the attribution.
 * The snapshot and any text derived from it must never be committed or used in default CI without an explicit
 * licensing review, so reports and manifests derived from it contain ids, spans and digests only.
 */
public final class MdnSnapshotSource {

    /** Version of the external corpus built from the snapshot. */
    public static final String VERSION = "html-kc-ext-mdn-v1";

    /** Canonical page URL of the tutorial. */
    public static final String PAGE_URL = "https://developer.mozilla.org/en-US/docs/Learn_web_development/"
            + "Getting_started/Your_first_website/Creating_the_content";

    /** Path of the expected file inside an {@code mdn/content} checkout. */
    public static final String CONTENT_PATH =
            "files/en-us/learn_web_development/getting_started/your_first_website/creating_the_content/index.md";

    /** Declared license of MDN prose. */
    public static final String LICENSE = "CC-BY-SA-2.5";

    /** Attribution and modification notice carried by every chunk. */
    public static final String ATTRIBUTION = "Mozilla Contributors (MDN Web Docs), licensed CC BY-SA 2.5;"
            + " modified: front matter removed, normalized and chunked by Monada Neuron html-kc ingestion";

    /** Document id of the snapshot. */
    public static final String DOCUMENT_ID = "doc:mdn-creating-the-content";

    /** Topic label of every snapshot chunk. */
    public static final String TOPIC = "mdn-tutorial";

    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{7,40}");

    private final CorpusNormalizer normalizer = new CorpusNormalizer();

    /**
     * Loads and validates the snapshot.
     *
     * @param snapshot local copy of {@value #CONTENT_PATH}
     * @param revision {@code mdn/content} commit the file was taken from (7 to 40 lowercase hex characters)
     * @param expectedSha256 lowercase-hex SHA-256 of the raw file bytes, as printed by {@code sha256sum}
     * @throws CorpusValidationException when the snapshot is missing, differs from the pin or violates the bounds
     */
    public HtmlKnowledgeCorpus load(Path snapshot, String revision, String expectedSha256) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        var origin = snapshot.toString();
        if (revision == null || !REVISION.matcher(revision).matches()) {
            throw new CorpusValidationException(origin, "--mdn-revision must be the mdn/content commit (7-40 lowercase hex)");
        }
        if (!CorpusDigests.isSha256(expectedSha256)) {
            throw new CorpusValidationException(origin, "--mdn-sha256 must be 64 lowercase hex characters");
        }
        if (!Files.isRegularFile(snapshot)) {
            throw new CorpusValidationException(origin,
                    "requested external MDN snapshot is missing; supply a local copy of mdn/content " + CONTENT_PATH);
        }
        var bounds = CorpusBounds.EXTERNAL;
        var raw = read(snapshot, bounds.maxDocumentBytes());
        var actual = CorpusDigests.sha256(raw);
        if (!actual.equals(expectedSha256)) {
            throw new CorpusValidationException(origin,
                    "snapshot sha256 " + actual + " differs from the pinned " + expectedSha256);
        }

        var text = withoutFrontMatter(normalizer.normalize(raw, origin), origin);
        var source = PAGE_URL + " (mdn/content@" + revision + ":" + CONTENT_PATH + ")";
        var document = new CorpusDocument(DOCUMENT_ID, snapshot.getFileName().toString(), source, LICENSE,
                ATTRIBUTION, List.of(PAGE_URL), CorpusDigests.sha256(text), text);
        var spans = new CorpusChunker(bounds.maxChunkBytes()).chunk(text, origin);
        var chunks = new ArrayList<CorpusChunk>(spans.size());
        for (var i = 0; i < spans.size(); i++) {
            var span = spans.get(i);
            chunks.add(new CorpusChunk(HtmlKnowledgeCorpus.chunkId(document.slug(), i), DOCUMENT_ID, TOPIC,
                    span.section(), source, LICENSE, ATTRIBUTION, span.start(), span.end(),
                    CorpusDigests.sha256(span.text()), document.documentSha256(), Optional.empty(), span.text()));
        }
        return HtmlKnowledgeCorpus.of(VERSION, bounds, HtmlKnowledgeCorpus.TextPolicy.REDACTED, List.of(document),
                chunks);
    }

    /** Removes a leading YAML front matter block delimited by {@code ---} lines, then re-normalizes. */
    private String withoutFrontMatter(String text, String origin) {
        if (!text.startsWith("---\n")) {
            return text;
        }
        var close = text.indexOf("\n---\n", 3);
        var closeAtEnd = text.endsWith("\n---") ? text.length() - 4 : -1;
        var end = close >= 0 ? close + 5 : closeAtEnd >= 0 ? text.length() : -1;
        if (end < 0) {
            throw new CorpusValidationException(origin, "front matter is not terminated by a '---' line");
        }
        var body = text.substring(end);
        if (body.isBlank()) {
            throw new CorpusValidationException(origin, "snapshot has no content after its front matter");
        }
        return normalizer.normalize(body, origin);
    }

    private byte[] read(Path snapshot, int maxBytes) {
        try (var input = Files.newInputStream(snapshot)) {
            var bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new CorpusValidationException(snapshot.toString(), "snapshot exceeds " + maxBytes + " bytes");
            }
            if (bytes.length == 0) {
                throw new CorpusValidationException(snapshot.toString(), "snapshot is empty");
            }
            return bytes;
        } catch (IOException e) {
            throw new CorpusValidationException(snapshot.toString(), "cannot read snapshot", e);
        }
    }
}
