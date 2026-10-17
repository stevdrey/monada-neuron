package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.CorpusChunk.Conflict;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A validated, versioned HTML knowledge corpus: normalized documents and their canonical labeled chunks.
 *
 * <p>The authored corpus {@value #VERSION} is a bundled resource directory holding {@code documents.tsv},
 * {@code chunks.tsv} and {@code documents/<slug>.md}. Loading normalizes every document, rechunks it with the
 * canonical {@link CorpusChunker} and requires the declared labels (ids, sections, spans, digests) to equal the
 * result, so a label file can never drift from the text it describes. Every corpus invariant of the protocol is
 * checked before a corpus exists; a violation throws {@link CorpusValidationException}.
 *
 * <p>Chunks are exposed in {@code chunkId} code point order, which is also the import order.
 */
public final class HtmlKnowledgeCorpus {

    /** Version of the authored corpus. */
    public static final String VERSION = "html-kc-v1";

    /** Header row of {@code documents.tsv}. */
    public static final String DOCUMENTS_HEADER =
            "documentId\tfile\tsource\tlicense\tattribution\treferences\tdocumentSha256";

    /** Header row of {@code chunks.tsv}. */
    public static final String CHUNKS_HEADER =
            "chunkId\tdocumentId\ttopic\tsection\tstart\tend\tsha256\tconflictPair\tconflictRole";

    /** Marker of an absent optional TSV value. */
    public static final String ABSENT = "-";

    /** Source value of originally authored documents. */
    public static final String AUTHORED_SOURCE = "authored-fixture";

    private static final String RESOURCE_ROOT = "/" + VERSION + "/";
    private static final int MAX_TABLE_BYTES = 64 * 1024;
    private static final Pattern DOCUMENT_ID = Pattern.compile("doc:([a-z0-9]+(?:-[a-z0-9]+)*)");
    private static final Pattern CHUNK_ID = Pattern.compile("chk:([a-z0-9]+(?:-[a-z0-9]+)*)#(\\d{2})");
    private static final Pattern TOPIC = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern CONFLICT_PAIR = Pattern.compile("conflict:[a-z0-9]+(?:-[a-z0-9]+)*");

    /** Whether the corpus text, including its headings, may appear in published outputs. */
    public enum TextPolicy {

        /** Originally authored, Apache-2.0 text: labels may show section headings. */
        PUBLISHABLE,

        /** Third-party text (for example a CC BY-SA snapshot): outputs carry ids, spans and digests only. */
        REDACTED
    }

    private final String version;
    private final CorpusBounds bounds;
    private final TextPolicy textPolicy;
    private final List<CorpusDocument> documents;
    private final List<CorpusChunk> chunks;
    private final Map<String, CorpusChunk> chunksById;
    private final String corpusHash;

    private HtmlKnowledgeCorpus(String version, CorpusBounds bounds, TextPolicy textPolicy,
            List<CorpusDocument> documents, List<CorpusChunk> chunks) {
        this.version = version;
        this.bounds = bounds;
        this.textPolicy = textPolicy;
        this.documents = documents.stream().sorted(Comparator.comparing(CorpusDocument::documentId)).toList();
        this.chunks = chunks.stream().sorted(Comparator.comparing(CorpusChunk::chunkId)).toList();
        var index = new LinkedHashMap<String, CorpusChunk>();
        this.chunks.forEach(chunk -> index.put(chunk.chunkId(), chunk));
        this.chunksById = Map.copyOf(index);
        this.corpusHash = hash(version, this.chunks);
    }

    /** Loads and validates the authored corpus bundled on the integration classpath. */
    public static HtmlKnowledgeCorpus bundled() {
        return load(new ClasspathFiles(), CorpusBounds.AUTHORED);
    }

    /** Loads and validates an authored-format corpus directory with the authored bounds. */
    public static HtmlKnowledgeCorpus load(Path directory) {
        return load(new DirectoryFiles(Objects.requireNonNull(directory, "directory must not be null")),
                CorpusBounds.AUTHORED);
    }

    /**
     * Validates already chunked documents and builds a corpus, as an external source does.
     *
     * @throws CorpusValidationException when any corpus invariant is violated
     */
    static HtmlKnowledgeCorpus of(String version, CorpusBounds bounds, TextPolicy textPolicy,
            List<CorpusDocument> documents, List<CorpusChunk> chunks) {
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(textPolicy, "textPolicy must not be null");
        validate(version, bounds, List.copyOf(documents), List.copyOf(chunks));
        return new HtmlKnowledgeCorpus(version, bounds, textPolicy, documents, chunks);
    }

    /** Returns the corpus version. */
    public String version() {
        return version;
    }

    /** Returns the bounds the corpus was validated against. */
    public CorpusBounds bounds() {
        return bounds;
    }

    /** Returns whether the corpus text may appear in published outputs. */
    public TextPolicy textPolicy() {
        return textPolicy;
    }

    /** Returns the documents in {@code documentId} order. */
    public List<CorpusDocument> documents() {
        return documents;
    }

    /** Returns the chunks in {@code chunkId} order, which is the import order. */
    public List<CorpusChunk> chunks() {
        return chunks;
    }

    /** Returns the chunk with {@code chunkId}. */
    public Optional<CorpusChunk> chunk(String chunkId) {
        return Optional.ofNullable(chunksById.get(chunkId));
    }

    /** Returns the canonical {@code corpusHash} (protocol "Dataset hashes"). */
    public String corpusHash() {
        return corpusHash;
    }

    /**
     * Renders the inspectable chunk labels and provenance as TSV, in {@code chunkId} order. Chunk text is never
     * included. Under {@link TextPolicy#REDACTED} the {@code section} heading is replaced by {@code sha256:<hex>} of
     * its UTF-8 bytes, so the rendering of an external corpus carries no third-party prose but stays comparable.
     */
    public String labelsTsv() {
        var sb = new StringBuilder();
        sb.append("# corpusVersion: ").append(version).append('\n');
        sb.append("# corpusHash: ").append(corpusHash).append('\n');
        if (textPolicy == TextPolicy.REDACTED) {
            sb.append("# Third-party text: section is redacted to sha256:<hex> of the heading; no prose is included.\n");
        }
        sb.append("chunkId\tdocumentId\ttopic\tsection\tsource\tlicense\tattribution\tstart\tend\tsha256"
                + "\tdocumentSha256\tconflictPair\tconflictRole\n");
        for (var chunk : chunks) {
            var section = textPolicy == TextPolicy.REDACTED
                    ? "sha256:" + CorpusDigests.sha256(chunk.section())
                    : chunk.section();
            sb.append(String.join("\t", chunk.chunkId(), chunk.documentId(), chunk.topic(), section,
                    chunk.source(), chunk.license(), chunk.attribution(), Integer.toString(chunk.start()),
                    Integer.toString(chunk.end()), chunk.sha256(), chunk.documentSha256(),
                    chunk.conflict().map(Conflict::pairId).orElse(ABSENT),
                    chunk.conflict().map(conflict -> conflict.role().name()).orElse(ABSENT))).append('\n');
        }
        return sb.toString();
    }

    private static HtmlKnowledgeCorpus load(CorpusFiles files, CorpusBounds bounds) {
        var normalizer = new CorpusNormalizer();
        var chunker = new CorpusChunker(bounds.maxChunkBytes());
        var documentRows = table(files, "documents.tsv", DOCUMENTS_HEADER);
        var chunkRows = table(files, "chunks.tsv", CHUNKS_HEADER);
        if (documentRows.isEmpty()) {
            throw new CorpusValidationException("documents.tsv", "corpus declares no documents");
        }
        if (documentRows.size() > bounds.maxDocuments()) {
            throw new CorpusValidationException("documents.tsv",
                    documentRows.size() + " documents exceed the bound of " + bounds.maxDocuments());
        }

        var documents = new ArrayList<CorpusDocument>();
        for (var row : documentRows) {
            var documentId = row.required(0, "documentId");
            var matcher = DOCUMENT_ID.matcher(documentId);
            if (!matcher.matches()) {
                throw row.reject("documentId '" + documentId + "' does not match doc:<slug>");
            }
            var file = row.required(1, "file");
            if (!file.equals(matcher.group(1) + ".md")) {
                throw row.reject("file must be exactly '" + matcher.group(1) + ".md' (no paths), was '" + file + "'");
            }
            var origin = "documents/" + file;
            var text = normalizer.normalize(files.read(origin, bounds.maxDocumentBytes()), origin);
            var declared = row.required(6, "documentSha256");
            var actual = CorpusDigests.sha256(text);
            if (!declared.equals(actual)) {
                throw row.reject("documentSha256 mismatch for " + origin + ": declared " + declared + ", actual " + actual);
            }
            var references = row.optional(5).map(value -> List.of(value.split(" "))).orElse(List.of());
            documents.add(new CorpusDocument(documentId, file, row.required(2, "source"), row.required(3, "license"),
                    row.required(4, "attribution"), references, actual, text));
        }

        var rowsByDocument = new LinkedHashMap<String, List<Row>>();
        documents.forEach(document -> rowsByDocument.put(document.documentId(), new ArrayList<>()));
        for (var row : chunkRows) {
            var documentId = row.required(1, "documentId");
            var rows = rowsByDocument.get(documentId);
            if (rows == null) {
                throw row.reject("unknown documentId '" + documentId + "'");
            }
            rows.add(row);
        }

        var chunks = new ArrayList<CorpusChunk>();
        for (var document : documents) {
            var spans = chunker.chunk(document.text(), "documents/" + document.file());
            var rows = rowsByDocument.get(document.documentId());
            if (rows.size() != spans.size()) {
                throw new CorpusValidationException("chunks.tsv", "declares " + rows.size() + " chunks for "
                        + document.documentId() + " but canonical chunking yields " + spans.size());
            }
            for (var i = 0; i < spans.size(); i++) {
                chunks.add(declaredChunk(document, spans.get(i), rows.get(i), i));
            }
        }
        return of(VERSION, bounds, TextPolicy.PUBLISHABLE, documents, chunks);
    }

    private static CorpusChunk declaredChunk(CorpusDocument document, CorpusChunker.Span span, Row row, int ordinal) {
        var expectedId = chunkId(document.slug(), ordinal);
        var chunkId = row.required(0, "chunkId");
        if (!chunkId.equals(expectedId)) {
            throw row.reject("chunkId '" + chunkId + "' must be '" + expectedId + "' (document order)");
        }
        var section = row.required(3, "section");
        if (!section.equals(span.section())) {
            throw row.reject("section '" + section + "' differs from the canonical heading '" + span.section() + "'");
        }
        var start = row.integer(4, "start");
        var end = row.integer(5, "end");
        if (start != span.start() || end != span.end()) {
            throw row.reject("span [" + start + ", " + end + ") differs from the canonical span ["
                    + span.start() + ", " + span.end() + ")");
        }
        var sha256 = row.required(6, "sha256");
        var actual = CorpusDigests.sha256(span.text());
        if (!sha256.equals(actual)) {
            throw row.reject("sha256 mismatch: declared " + sha256 + ", actual " + actual);
        }
        var pair = row.optional(7);
        var role = row.optional(8);
        if (pair.isPresent() != role.isPresent()) {
            throw row.reject("conflictPair and conflictRole must both be present or both be '-'");
        }
        Optional<Conflict> conflict = Optional.empty();
        if (pair.isPresent()) {
            try {
                conflict = Optional.of(new Conflict(pair.get(), ConflictRole.valueOf(role.get())));
            } catch (IllegalArgumentException e) {
                throw row.reject("conflictRole must be CURATED or CONTRADICTING, was '" + role.get() + "'");
            }
        }
        return new CorpusChunk(chunkId, document.documentId(), row.required(2, "topic"), section, document.source(),
                document.license(), document.attribution(), start, end, sha256, document.documentSha256(), conflict,
                span.text());
    }

    /** Returns the canonical chunk id of the {@code ordinal}-th chunk of document {@code slug}. */
    static String chunkId(String slug, int ordinal) {
        return String.format(Locale.ROOT, "chk:%s#%02d", slug, ordinal);
    }

    private static void validate(String version, CorpusBounds bounds, List<CorpusDocument> documents,
            List<CorpusChunk> chunks) {
        var origin = "corpus " + version;
        if (documents.isEmpty() || chunks.isEmpty()) {
            throw new CorpusValidationException(origin, "corpus is empty");
        }
        if (documents.size() > bounds.maxDocuments()) {
            throw new CorpusValidationException(origin,
                    documents.size() + " documents exceed the bound of " + bounds.maxDocuments());
        }
        if (chunks.size() < bounds.minChunks() || chunks.size() > bounds.maxChunks()) {
            throw new CorpusValidationException(origin, chunks.size() + " chunks are outside the bound "
                    + bounds.minChunks() + ".." + bounds.maxChunks());
        }
        var normalizer = new CorpusNormalizer();
        var documentsById = new HashMap<String, CorpusDocument>();
        for (var document : documents) {
            if (!DOCUMENT_ID.matcher(document.documentId()).matches()) {
                throw new CorpusValidationException(origin, "invalid documentId '" + document.documentId() + "'");
            }
            if (documentsById.put(document.documentId(), document) != null) {
                throw new CorpusValidationException(origin, "duplicate documentId '" + document.documentId() + "'");
            }
            if (!document.documentSha256().equals(CorpusDigests.sha256(document.text()))) {
                throw new CorpusValidationException(origin, document.documentId() + ": documentSha256 mismatch");
            }
        }

        var chunkIds = new HashSet<String>();
        var textDigests = new HashMap<String, String>();
        var perDocument = new HashMap<String, Integer>();
        var cursors = new HashMap<String, int[]>();
        var conflicts = new LinkedHashMap<String, List<CorpusChunk>>();
        for (var chunk : chunks) {
            var where = origin + ", " + chunk.chunkId();
            var document = documentsById.get(chunk.documentId());
            if (document == null) {
                throw new CorpusValidationException(where, "unknown documentId '" + chunk.documentId() + "'");
            }
            var idMatcher = CHUNK_ID.matcher(chunk.chunkId());
            if (!idMatcher.matches() || !idMatcher.group(1).equals(document.slug())) {
                throw new CorpusValidationException(where, "chunkId must be chk:" + document.slug() + "#<nn>");
            }
            if (!chunkIds.add(chunk.chunkId())) {
                throw new CorpusValidationException(where, "duplicate chunkId");
            }
            var ordinal = perDocument.merge(chunk.documentId(), 1, Integer::sum) - 1;
            if (Integer.parseInt(idMatcher.group(2)) != ordinal) {
                throw new CorpusValidationException(where, "chunk ordinals must be contiguous from 00 in document order");
            }
            if (ordinal + 1 > bounds.maxChunksPerDocument()) {
                throw new CorpusValidationException(where, "document " + chunk.documentId() + " exceeds "
                        + bounds.maxChunksPerDocument() + " chunks");
            }
            if (!TOPIC.matcher(chunk.topic()).matches() || chunk.section().isBlank()) {
                throw new CorpusValidationException(where, "topic must be a lowercase slug and section non-blank");
            }
            if (!chunk.source().equals(document.source()) || !chunk.license().equals(document.license())
                    || !chunk.attribution().equals(document.attribution())
                    || !chunk.documentSha256().equals(document.documentSha256())) {
                throw new CorpusValidationException(where, "provenance differs from its document");
            }
            if (chunk.text().isBlank() || !normalizer.isNormalized(chunk.text())) {
                throw new CorpusValidationException(where, "chunk text is empty or not normalized");
            }
            var bytes = chunk.text().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > bounds.maxChunkBytes()) {
                throw new CorpusValidationException(where, bytes + " UTF-8 bytes exceed the chunk bound of "
                        + bounds.maxChunkBytes());
            }
            if (!chunk.sha256().equals(CorpusDigests.sha256(chunk.text()))) {
                throw new CorpusValidationException(where, "sha256 does not match the chunk text");
            }
            var duplicate = textDigests.putIfAbsent(chunk.sha256(), chunk.chunkId());
            if (duplicate != null) {
                // The Store derives atom ids from content, so identical texts would collapse into one atom.
                throw new CorpusValidationException(where, "identical normalized text as " + duplicate);
            }
            checkSpan(where, document, chunk, cursors.computeIfAbsent(chunk.documentId(), id -> new int[2]));
            chunk.conflict().ifPresent(conflict -> {
                if (!CONFLICT_PAIR.matcher(conflict.pairId()).matches()) {
                    throw new CorpusValidationException(where, "conflictPair must match conflict:<slug>");
                }
                conflicts.computeIfAbsent(conflict.pairId(), id -> new ArrayList<>()).add(chunk);
            });
        }
        validateConflicts(origin, bounds, conflicts);
    }

    /**
     * Checks that the span lies in the document and its substring equals the chunk text. Chunks of one document
     * arrive in ordinal order with non-overlapping, increasing spans, so a cursor {@code [codePoint, charIndex]}
     * advances monotonically and the whole check is linear in the document length.
     */
    private static void checkSpan(String where, CorpusDocument document, CorpusChunk chunk, int[] cursor) {
        var text = document.text();
        var length = text.codePointCount(0, text.length());
        if (chunk.start() < cursor[0] || chunk.start() >= chunk.end() || chunk.end() > length) {
            throw new CorpusValidationException(where, "sourceSpan [" + chunk.start() + ", " + chunk.end()
                    + ") is outside the document, empty, or overlaps the previous chunk");
        }
        var startChar = text.offsetByCodePoints(cursor[1], chunk.start() - cursor[0]);
        var endChar = text.offsetByCodePoints(startChar, chunk.end() - chunk.start());
        if (!text.substring(startChar, endChar).equals(chunk.text())) {
            throw new CorpusValidationException(where, "sourceSpan substring differs from the chunk text");
        }
        cursor[0] = chunk.end();
        cursor[1] = endChar;
    }

    private static void validateConflicts(String origin, CorpusBounds bounds, Map<String, List<CorpusChunk>> conflicts) {
        if (!bounds.requireConflictPair()) {
            if (!conflicts.isEmpty()) {
                throw new CorpusValidationException(origin, "this corpus kind must not declare conflict pairs");
            }
            return;
        }
        if (conflicts.size() != 1) {
            throw new CorpusValidationException(origin,
                    "exactly one conflictPair is required, found " + conflicts.size() + " " + conflicts.keySet());
        }
        var members = conflicts.values().iterator().next();
        var curated = members.stream()
                .filter(chunk -> chunk.conflict().orElseThrow().role() == ConflictRole.CURATED)
                .count();
        if (members.size() != 2 || curated != 1) {
            throw new CorpusValidationException(origin, "the conflictPair needs exactly two members with exactly one"
                    + " CURATED, found " + members.size() + " members and " + curated + " CURATED");
        }
    }

    private static String hash(String version, List<CorpusChunk> sortedChunks) {
        var digest = new CanonicalRecordDigest().field(version).field(sortedChunks.size());
        for (var chunk : sortedChunks) {
            digest.field(chunk.chunkId())
                    .field(chunk.documentId())
                    .field(chunk.topic())
                    .field(chunk.section())
                    .field(chunk.source())
                    .field(chunk.license())
                    .field(chunk.attribution())
                    .field(chunk.start())
                    .field(chunk.end())
                    .field(chunk.sha256())
                    .field(chunk.documentSha256())
                    .field(chunk.conflict().map(Conflict::pairId).orElse(null))
                    .field(chunk.conflict().map(conflict -> conflict.role().name()).orElse(null));
        }
        return digest.hex();
    }

    private static List<Row> table(CorpusFiles files, String name, String header) {
        var normalizer = new CorpusNormalizer();
        var text = normalizer.normalize(files.read(name, MAX_TABLE_BYTES), name);
        var lines = text.split("\n", -1);
        var rows = new ArrayList<Row>();
        var columns = header.split("\t", -1).length;
        var headerSeen = false;
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!headerSeen) {
                if (!line.equals(header)) {
                    throw new CorpusValidationException(name + ":" + (i + 1), "expected header '" + header + "'");
                }
                headerSeen = true;
                continue;
            }
            var cells = line.split("\t", -1);
            if (cells.length != columns) {
                throw new CorpusValidationException(name + ":" + (i + 1),
                        "expected " + columns + " tab-separated columns, found " + cells.length);
            }
            rows.add(new Row(name + ":" + (i + 1), List.of(cells)));
        }
        if (!headerSeen) {
            throw new CorpusValidationException(name, "missing header row");
        }
        return rows;
    }

    /** One data row of a corpus table with its {@code file:line} origin. */
    private record Row(String origin, List<String> cells) {

        String required(int column, String name) {
            var value = cells.get(column).strip();
            if (value.isEmpty() || value.equals(ABSENT)) {
                throw reject(name + " is required");
            }
            return value;
        }

        Optional<String> optional(int column) {
            var value = cells.get(column).strip();
            return value.isEmpty() || value.equals(ABSENT) ? Optional.empty() : Optional.of(value);
        }

        int integer(int column, String name) {
            try {
                return Integer.parseInt(required(column, name));
            } catch (NumberFormatException e) {
                throw reject(name + " must be an integer");
            }
        }

        CorpusValidationException reject(String rule) {
            return new CorpusValidationException(origin, rule);
        }
    }

    /** Bounded reader of corpus files by relative name. */
    private interface CorpusFiles {

        byte[] read(String name, int maxBytes);
    }

    private static byte[] readBounded(InputStream input, String name, int maxBytes) throws IOException {
        var bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new CorpusValidationException(name, "file exceeds the bound of " + maxBytes + " bytes");
        }
        if (bytes.length == 0) {
            throw new CorpusValidationException(name, "file is empty");
        }
        return bytes;
    }

    private static final class ClasspathFiles implements CorpusFiles {

        @Override
        public byte[] read(String name, int maxBytes) {
            try (var input = HtmlKnowledgeCorpus.class.getResourceAsStream(RESOURCE_ROOT + name)) {
                if (input == null) {
                    throw new CorpusValidationException(RESOURCE_ROOT + name, "bundled corpus file is missing");
                }
                return readBounded(input, name, maxBytes);
            } catch (IOException e) {
                throw new CorpusValidationException(name, "cannot read bundled corpus file", e);
            }
        }
    }

    /**
     * Resolves {@code name} inside the corpus directory {@code root}. The root is made absolute first, so a relative
     * root such as {@code .} works. The file must lie inside the root lexically and, when it exists, after resolving
     * symbolic links, so neither {@code ..} nor a link can read outside the corpus directory.
     *
     * @throws CorpusValidationException when the file escapes the root
     */
    static Path resolveInside(Path root, String name) {
        var base = root.toAbsolutePath().normalize();
        var file = base.resolve(name).normalize();
        if (!file.startsWith(base) || file.equals(base)) {
            throw new CorpusValidationException(name, "path escapes the corpus directory");
        }
        if (Files.exists(file)) {
            try {
                if (!file.toRealPath().startsWith(base.toRealPath())) {
                    throw new CorpusValidationException(name, "path escapes the corpus directory through a link");
                }
            } catch (IOException e) {
                throw new CorpusValidationException(file.toString(), "cannot resolve corpus file", e);
            }
        }
        return file;
    }

    private record DirectoryFiles(Path root) implements CorpusFiles {

        @Override
        public byte[] read(String name, int maxBytes) {
            var file = resolveInside(root, name);
            try (var input = Files.newInputStream(file)) {
                return readBounded(input, name, maxBytes);
            } catch (NoSuchFileException e) {
                throw new CorpusValidationException(file.toString(), "corpus file is missing", e);
            } catch (IOException e) {
                throw new CorpusValidationException(file.toString(), "cannot read corpus file", e);
            }
        }
    }

    @Override
    public String toString() {
        return "HtmlKnowledgeCorpus[" + version + ", " + documents.size() + " documents, " + chunks.size()
                + " chunks, corpusHash=" + corpusHash + "]";
    }
}
