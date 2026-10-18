package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.CorpusChunk.Conflict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HtmlKnowledgeCorpusTest {

    /**
     * Frozen fingerprint of {@code html-kc-v1}. Any change to text, chunking or labels changes it and must come with
     * a new corpus version, never with an edited constant.
     */
    private static final String HTML_KC_V1_HASH = "1934ef6f127d37788bcb76e6a8e2c8cde7008e0ad9d0062277f65aa4861739a3";

    private static final List<String> DOCUMENT_FILES = List.of(
            "forms.md", "headings-and-structure.md", "images-and-media.md", "legacy-notes.md", "links.md",
            "lists-and-text-semantics.md", "scripts-and-embedding.md");

    @TempDir
    Path directory;

    @Test
    void bundledCorpusSatisfiesTheCorpusContract() {
        var corpus = HtmlKnowledgeCorpus.bundled();

        assertEquals(HtmlKnowledgeCorpus.VERSION, corpus.version());
        assertEquals(7, corpus.documents().size());
        assertEquals(33, corpus.chunks().size());
        var ids = corpus.chunks().stream().map(CorpusChunk::chunkId).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
        var conflicts = corpus.chunks().stream().filter(chunk -> chunk.conflict().isPresent()).toList();
        assertEquals(List.of("chk:images-and-media#01", "chk:legacy-notes#00"),
                conflicts.stream().map(CorpusChunk::chunkId).toList());
        assertEquals(ConflictRole.CURATED, conflicts.getFirst().conflict().orElseThrow().role());
        for (var chunk : corpus.chunks()) {
            var document = corpus.documents().stream()
                    .filter(candidate -> candidate.documentId().equals(chunk.documentId())).findFirst().orElseThrow();
            var text = document.text();
            var start = text.offsetByCodePoints(0, chunk.start());
            var end = text.offsetByCodePoints(0, chunk.end());
            assertAll(chunk.chunkId(),
                    () -> assertEquals(chunk.text(), text.substring(start, end)),
                    () -> assertEquals(CorpusDigests.sha256(chunk.text()), chunk.sha256()),
                    () -> assertTrue(chunk.text().getBytes(StandardCharsets.UTF_8).length <= 1024),
                    () -> assertEquals(HtmlKnowledgeCorpus.AUTHORED_SOURCE, chunk.source()),
                    () -> assertEquals("Apache-2.0", chunk.license()));
        }
    }

    @Test
    void sameInputsYieldIdenticalLabelsOrderAndFingerprints() {
        var first = HtmlKnowledgeCorpus.bundled();
        var second = HtmlKnowledgeCorpus.bundled();

        assertEquals(first.chunks(), second.chunks());
        assertEquals(first.labelsTsv(), second.labelsTsv());
        assertEquals(HTML_KC_V1_HASH, first.corpusHash());
    }

    @Test
    void canonicalIdsAndHashDoNotDependOnTheDefaultLocale() {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));

            assertEquals("chk:x#03", HtmlKnowledgeCorpus.chunkId("x", 3));
            assertEquals(HTML_KC_V1_HASH, HtmlKnowledgeCorpus.bundled().corpusHash());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void boundsCannotExceedTheTwoDigitChunkOrdinals() {
        assertThrows(IllegalArgumentException.class, () -> new CorpusBounds(1, 101, 1024, 101, 1, 1024, false));
        assertEquals(CorpusBounds.MAX_ORDINALS, CorpusBounds.EXTERNAL.maxChunksPerDocument());
    }

    @Test
    void aCopiedCorpusDirectoryLoadsWithTheSameHash() throws IOException {
        copyBundledCorpus();

        assertEquals(HTML_KC_V1_HASH, HtmlKnowledgeCorpus.load(directory).corpusHash());
    }

    @Test
    void resolvesFilesInsideARelativeRootAndRejectsEscapes() throws IOException {
        assertEquals(Path.of("").toAbsolutePath().resolve("documents.tsv"),
                HtmlKnowledgeCorpus.resolveInside(Path.of("."), "documents.tsv"));
        assertEquals(Path.of("").toAbsolutePath().resolve("documents.tsv"),
                HtmlKnowledgeCorpus.resolveInside(Path.of("src/.."), "documents.tsv"));
        assertMessage("escapes", () -> HtmlKnowledgeCorpus.resolveInside(directory, "../outside.md"));

        // Both the corpus and the outside file live in the JUnit temporary directory.
        var corpus = directory.resolve("corpus");
        copyBundledCorpus(corpus);
        var outside = Files.writeString(directory.resolve("outside.md"),
                Files.readString(corpus.resolve("documents/forms.md")));
        Files.delete(corpus.resolve("documents/forms.md"));
        try {
            Files.createSymbolicLink(corpus.resolve("documents/forms.md"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not supported here: " + e);
        }
        assertMessage("through a link", () -> HtmlKnowledgeCorpus.load(corpus));
    }

    @Test
    void textPolicyFollowsProvenanceNotTheCaller() {
        var corpus = HtmlKnowledgeCorpus.bundled();
        var relicensed = corpus.documents().stream()
                .map(document -> document.documentId().equals("doc:forms")
                        ? new CorpusDocument(document.documentId(), document.file(), document.source(), "CC-BY-SA-2.5",
                                document.attribution(), document.references(), document.documentSha256(),
                                document.text())
                        : document)
                .toList();
        var chunks = corpus.chunks().stream()
                .map(chunk -> chunk.documentId().equals("doc:forms")
                        ? new CorpusChunk(chunk.chunkId(), chunk.documentId(), chunk.topic(), chunk.section(),
                                chunk.source(), "CC-BY-SA-2.5", chunk.attribution(), chunk.start(), chunk.end(),
                                chunk.sha256(), chunk.documentSha256(), chunk.conflict(), chunk.text())
                        : chunk)
                .toList();

        assertEquals(HtmlKnowledgeCorpus.TextPolicy.PUBLISHABLE, corpus.textPolicy());
        var redacted = build(CorpusBounds.AUTHORED, relicensed, chunks);
        assertEquals(HtmlKnowledgeCorpus.TextPolicy.REDACTED, redacted.textPolicy());
        assertTrue(redacted.labelsTsv().contains("sha256:" + CorpusDigests.sha256("The form element")));
    }

    @Test
    void labelRenderingNeverContainsChunkText() {
        var corpus = HtmlKnowledgeCorpus.bundled();
        var labels = corpus.labelsTsv();

        assertTrue(corpus.chunks().stream().noneMatch(chunk -> labels.contains(chunk.text())));
    }

    @Test
    void rejectsEmptyMalformedAndMismatchedTables() throws IOException {
        copyBundledCorpus();
        assertRejected("file is empty", "documents.tsv", text -> "");
        assertRejected("corpus declares no documents", "documents.tsv",
                text -> HtmlKnowledgeCorpus.DOCUMENTS_HEADER + "\n");
        assertRejected("expected header", "chunks.tsv", text -> text.replace("chunkId\t", "id\t"));
        assertRejected("tab-separated columns", "chunks.tsv",
                text -> text.replace("\tforms\tThe form element\t", "\tforms\t"));
        assertRejected("does not match doc:<slug>", "documents.tsv",
                text -> text.replace("doc:forms\t", "doc:Forms\t"));
        assertRejected("(no paths)", "documents.tsv", text -> text.replace("\tforms.md\t", "\t../forms.md\t"));
        assertRejected("must be 'chk:forms#01'", "chunks.tsv", text -> text.replace("chk:forms#01", "chk:forms#00"));
        assertRejected("sha256 mismatch", "chunks.tsv", text -> replaceColumn(text, "chk:links#00", 6,
                "0".repeat(64)));
        assertRejected("differs from the canonical span", "chunks.tsv",
                text -> replaceColumn(text, "chk:links#01", 4, "270"));
        assertRejected("differs from the canonical heading", "chunks.tsv",
                text -> replaceColumn(text, "chk:links#01", 3, "Relative URLs"));
        assertRejected("unknown documentId", "chunks.tsv",
                text -> text.replace("chk:links#04\tdoc:links", "chk:links#04\tdoc:missing"));
        assertRejected("conflictRole must be", "chunks.tsv", text -> text.replace("\tCURATED", "\tTRUSTED"));
        assertRejected("exactly one conflictPair", "chunks.tsv",
                text -> text.replace("conflict:img-alt\tCONTRADICTING", "conflict:other\tCONTRADICTING"));
    }

    @Test
    void rejectsChangedMissingInvalidAndOversizeDocuments() throws IOException {
        copyBundledCorpus();
        assertRejected("documentSha256 mismatch", "documents/links.md", text -> text + "\nExtra sentence.");
        assertRejected("is missing", "documents/forms.md", null);

        copyBundledCorpus();
        Files.write(directory.resolve("documents/forms.md"), new byte[] {'#', ' ', (byte) 0xFF});
        assertMessage("invalid UTF-8", () -> HtmlKnowledgeCorpus.load(directory));

        copyBundledCorpus();
        Files.writeString(directory.resolve("documents/forms.md"), "x".repeat(32 * 1024 + 1));
        assertMessage("exceeds the bound of 32768 bytes", () -> HtmlKnowledgeCorpus.load(directory));
    }

    @Test
    void rejectsCorpusInvariantViolations() {
        var corpus = HtmlKnowledgeCorpus.bundled();
        var documents = corpus.documents();
        var chunks = corpus.chunks();

        var original = chunks.get(indexOf(chunks, "chk:links#00"));
        assertMessage("identical normalized text", () -> build(CorpusBounds.AUTHORED, documents,
                replace(chunks, "chk:links#01", chunk -> copy(chunk, chunk.start(), chunk.end(), original.sha256(),
                        chunk.conflict(), original.text()))));
        assertMessage("outside the bound 20..40", () -> build(CorpusBounds.AUTHORED, documents,
                chunks.subList(0, 19)));
        assertMessage("exceeds 4 chunks",
                () -> build(new CorpusBounds(20, 40, 1024, 4, 16, 32 * 1024, true), documents, chunks));
        assertMessage("exactly one conflictPair", () -> build(CorpusBounds.AUTHORED, documents,
                replace(chunks, "chk:forms#00", chunk -> copy(chunk, chunk.start(), chunk.end(), chunk.sha256(),
                        Optional.of(new Conflict("conflict:extra", ConflictRole.CURATED)), chunk.text()))));
        assertMessage("exactly two members", () -> build(CorpusBounds.AUTHORED, documents,
                replace(chunks, "chk:legacy-notes#00", chunk -> copy(chunk, chunk.start(), chunk.end(),
                        chunk.sha256(), Optional.of(new Conflict("conflict:img-alt", ConflictRole.CURATED)),
                        chunk.text()))));
        assertMessage("sourceSpan substring differs", () -> build(CorpusBounds.AUTHORED, documents,
                replace(chunks, "chk:forms#01", chunk -> copy(chunk, chunk.start() + 1, chunk.end() + 1,
                        chunk.sha256(), chunk.conflict(), chunk.text()))));
        assertMessage("must not declare conflict pairs",
                () -> build(new CorpusBounds(20, 40, 1024, 8, 16, 32 * 1024, false), documents, chunks));
    }

    private HtmlKnowledgeCorpus build(CorpusBounds bounds, List<CorpusDocument> documents,
            List<CorpusChunk> chunks) {
        return HtmlKnowledgeCorpus.of("v", bounds, documents, chunks);
    }

    private void assertRejected(String expected, String file, UnaryOperator<String> mutation) throws IOException {
        copyBundledCorpus();
        var path = directory.resolve(file);
        if (mutation == null) {
            Files.delete(path);
        } else {
            Files.writeString(path, mutation.apply(Files.readString(path)));
        }
        assertMessage(expected, () -> HtmlKnowledgeCorpus.load(directory));
    }

    private void assertMessage(String expected, Runnable action) {
        var failure = assertThrows(CorpusValidationException.class, action::run);
        assertTrue(failure.getMessage().contains(expected), "expected '" + expected + "' in: " + failure.getMessage());
    }

    private void copyBundledCorpus() throws IOException {
        copyBundledCorpus(directory);
    }

    private void copyBundledCorpus(Path target) throws IOException {
        Files.createDirectories(target.resolve("documents"));
        var names = new ArrayList<String>(List.of("documents.tsv", "chunks.tsv"));
        DOCUMENT_FILES.forEach(file -> names.add("documents/" + file));
        for (var name : names) {
            try (var input = HtmlKnowledgeCorpus.class.getResourceAsStream("/html-kc-v1/" + name)) {
                Files.write(target.resolve(name), input.readAllBytes());
            }
        }
    }

    private static String replaceColumn(String table, String chunkId, int column, String value) {
        var lines = new ArrayList<String>();
        for (var line : table.split("\n", -1)) {
            if (line.startsWith(chunkId + "\t")) {
                var cells = line.split("\t", -1);
                cells[column] = value;
                line = String.join("\t", cells);
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    private static int indexOf(List<CorpusChunk> chunks, String chunkId) {
        for (var i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).chunkId().equals(chunkId)) {
                return i;
            }
        }
        throw new IllegalArgumentException(chunkId);
    }

    private static List<CorpusChunk> replace(List<CorpusChunk> chunks, String chunkId,
            UnaryOperator<CorpusChunk> change) {
        var copy = new ArrayList<>(chunks);
        var index = indexOf(copy, chunkId);
        copy.set(index, change.apply(copy.get(index)));
        return copy;
    }

    private static CorpusChunk copy(CorpusChunk chunk, int start, int end, String sha256,
            Optional<Conflict> conflict, String text) {
        return new CorpusChunk(chunk.chunkId(), chunk.documentId(), chunk.topic(), chunk.section(), chunk.source(),
                chunk.license(), chunk.attribution(), start, end, sha256, chunk.documentSha256(), conflict, text);
    }
}
