package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.JudgedQuery.Answerability;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlKnowledgeQuerySetTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();

    /**
     * Frozen fingerprint of {@code html-kq-v1}, fixed when its labels were approved and before any system output was
     * inspected, and revised once before merge by a recorded errata (the missing refutation claim of
     * {@code q:false-premise-03}; previous hash {@code 97b29b68…}). Any later label change must come with a new
     * query-set version, never with an edited constant.
     */
    private static final String HTML_KQ_V1_HASH = "e91b04045664c48695f34e16bc74e63879ddbe8e11ca4613914538cff9e9060f";

    @TempDir
    Path directory;

    @Test
    void bundledQuerySetSatisfiesTheQueryContract() {
        var querySet = HtmlKnowledgeQuerySet.bundled(CORPUS);

        assertEquals(HtmlKnowledgeQuerySet.VERSION, querySet.version());
        assertEquals(32, querySet.queries().size());
        var ids = querySet.queries().stream().map(JudgedQuery::queryId).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
        for (var category : Category.values()) {
            assertTrue(querySet.byCategory().get(category).size() >= 2, category.label());
        }
        var empty = querySet.queries().stream().filter(query -> query.relevant().isEmpty()).count();
        assertEquals(10, empty);
        var conflicting = querySet.byCategory().get(Category.CONFLICTING_SOURCE);
        conflicting.forEach(query -> assertEquals(Set.of("chk:images-and-media#01", "chk:legacy-notes#00"),
                query.required()));
        var blank = querySet.byCategory().get(Category.BLANK);
        assertEquals(List.of("", "   "), blank.stream().map(JudgedQuery::text).toList());
        assertEquals(Answerability.BLANK, blank.getFirst().answerability());
        assertEquals(HTML_KQ_V1_HASH, querySet.queryHash());
    }

    @Test
    void directoryCopyLoadsWithTheSameHash() throws IOException {
        write(UnaryOperator.identity());

        assertEquals(HtmlKnowledgeQuerySet.bundled(CORPUS).queryHash(),
                HtmlKnowledgeQuerySet.load(directory, CORPUS).queryHash());
    }

    @Test
    void hashCoversLabelsButNotFormatting() throws IOException {
        write(text -> text.replace("# html-kq-v1 judged", "# a different comment, html-kq-v1 judged"));
        var reformatted = HtmlKnowledgeQuerySet.load(directory, CORPUS).queryHash();
        write(text -> text.replace("A fieldset groups related controls.", "A fieldset groups the related controls."));
        var relabeled = HtmlKnowledgeQuerySet.load(directory, CORPUS).queryHash();

        var bundled = HtmlKnowledgeQuerySet.bundled(CORPUS).queryHash();
        assertEquals(bundled, reformatted);
        assertTrue(!bundled.equals(relabeled));
    }

    @Test
    void everyLoadTimeInvariantIsEnforced() {
        rejects(text -> text.replace("q:exact-term-02\t", "q:exact-term-01\t"), "duplicate queryId");
        rejects(text -> text.replace("exact-term\tANSWERABLE\tWhat does the fieldset", "exact-term\tFALSE_PREMISE\t"
                + "What does the fieldset"), "does not match category");
        rejects(text -> text.replace("q:exact-term-02\texact-term", "q:exact-term-02\tparaphrase"),
                "queryId must be q:paraphrase-<nn>");
        rejects(text -> text.replace("mail application?", "email application?"), "avoided term 'email'");
        rejects(text -> text.replace("chk:forms#05 22 70 e4", "chk:forms#05 22 71 e4"), "digest does not match");
        rejects(text -> text.replace("chk:forms#05 22 70", "chk:forms#05 22 7000"), "out of bounds");
        rejects(text -> row(text, "q:no-answer-01", 4, "chk:forms#00"), "must have no relevant chunk");
        rejects(text -> row(text, "q:exact-term-01", 4, "chk:nothing#00"), "unknown chunk");
        rejects(text -> row(text, "q:exact-term-01", 5, "chk:links#00"), "overlap");
        rejects(text -> row(text, "q:exact-term-01", 6, "-"), "every required chunk needs an evidence span");
        rejects(text -> row(text, "q:exact-term-01", 7, "-"), "at least one keyClaim");
        rejects(text -> row(text, "q:false-premise-03", 7, "-"), "refutes its premise");
        rejects(text -> row(text, "q:exact-term-01", 8, "hyperlink"), "avoidedTerms are required exactly");
        rejects(text -> row(text, "q:conflicting-source-01", 4, "chk:images-and-media#01"), "conflict pair");
        rejects(text -> row(text, "q:exact-term-01", 3, " "), "blank exactly for blank queries");
        rejects(text -> row(text, "q:exact-term-01", 3, "What is the HTML a element for?\\x"), "unknown text escape");
        rejects(text -> row(text, "q:exact-term-01", 3, "What is the HTML a élément for?"), "L2 codec");
        rejects(text -> row(text, "q:exact-term-01", 7, "A claim|A claim"), "repeats an entry");
        rejects(text -> dropRows(text, "q:blank-"), "category blank has fewer than 2");
        rejects(text -> dropRows(text, "q:out-of-domain-"), "category out-of-domain");
        rejects(text -> text.replace("queryId\tcategory", "id\tcategory"), "expected header");
    }

    private void rejects(UnaryOperator<String> edit, String message) {
        var rejection = assertThrows(CorpusValidationException.class, () -> {
            write(edit);
            HtmlKnowledgeQuerySet.load(directory, CORPUS);
        }, message);
        assertTrue(rejection.getMessage().contains(message), rejection.getMessage() + " should mention " + message);
    }

    /** Replaces column {@code column} of the row of {@code queryId}. */
    private static String row(String text, String queryId, int column, String value) {
        return text.lines().map(line -> {
            if (!line.startsWith(queryId + "\t")) {
                return line;
            }
            var cells = line.split("\t", -1);
            cells[column] = value;
            return String.join("\t", cells);
        }).collect(Collectors.joining("\n", "", "\n"));
    }

    private static String dropRows(String text, String prefix) {
        return Arrays.stream(text.split("\n")).filter(line -> !line.startsWith(prefix))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private void write(UnaryOperator<String> edit) throws IOException {
        try (var input = HtmlKnowledgeQuerySet.class.getResourceAsStream("/html-kq-v1/queries.tsv")) {
            var text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("queries.tsv"), edit.apply(text), StandardCharsets.UTF_8);
        }
    }
}
