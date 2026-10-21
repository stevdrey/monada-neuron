package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.AnswerTrace.Outcome;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeQaExperiment.Observation;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeQaExperiment.Root;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the grounded-answer experiment in-process over two freshly imported roots; it sees query ids and texts only. */
class HtmlKnowledgeQaExperimentTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final HtmlKnowledgeCorpus REMOVED = HtmlKnowledgeEvidenceTracePhase.withoutDocument(CORPUS,
            HtmlKnowledgeQaExperiment.REMOVED_DOCUMENT);
    private static final HtmlKnowledgeQuerySet QUERY_SET = HtmlKnowledgeQuerySet.bundled(CORPUS);
    private static final String NOVICE = "q:exact-term-01";

    @TempDir
    static Path directory;

    private static IngestionManifest fullManifest;
    private static Root full;
    private static Root removed;
    private static Observation observation;

    @BeforeAll
    static void runOnce() {
        full = root("full", CORPUS);
        removed = root("removed", REMOVED);
        var texts = QUERY_SET.queries().stream().map(query -> new QueryText(query.queryId(), query.text())).toList();
        observation = new HtmlKnowledgeQaExperiment().run(List.of(full, removed), QUERY_SET.queryHash(), texts);
    }

    @Test
    void everySemanticCheckPasses() {
        assertEquals(List.of("completed", "citationsEligible", "deterministic", "control.removedNeverCited",
                "control.emptyEvidenceAbstains", "generator.labelledTestDouble"),
                observation.checks().stream().map(Check::name).toList());
        observation.checks().forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
        assertEquals(2 * QUERY_SET.queries().size() * 2 * 3 * 3, observation.snapshot().rows().size(),
                "roots x queries x layers x answerK x components");
    }

    @Test
    void theNoviceQueryGetsAVerbatimCitedExtractOfAnActualHitOnly() {
        for (var row : observation.snapshot().rows()) {
            if (!row.queryId().equals(NOVICE) || row.outcome() != Outcome.ANSWERED) {
                continue;
            }
            for (var claim : row.claims()) {
                for (var span : claim.citations()) {
                    assertTrue(row.chunkIds().contains(span.citation().chunkId()), row.toString());
                    var text = CORPUS.chunk(span.citation().chunkId()).orElseThrow().text();
                    assertEquals(claim.text(), text.substring(text.offsetByCodePoints(0, span.citation().start()),
                            text.offsetByCodePoints(0, span.citation().end())));
                }
            }
        }
        assertTrue(observation.snapshot().rows().stream().anyMatch(row -> row.queryId().equals(NOVICE)
                && row.component().equals("B3") && row.outcome() == Outcome.ANSWERED));
    }

    @Test
    void theRootWithoutTheLinksDocumentNeverReturnsOrCitesIt() {
        var prefix = HtmlKnowledgeQaExperiment.chunkPrefix(HtmlKnowledgeQaExperiment.REMOVED_DOCUMENT);
        assertEquals("chk:links#", prefix);
        for (var row : observation.snapshot().rows()) {
            if (row.root().equals("removed")) {
                assertTrue(row.chunkIds().stream().noneMatch(chunk -> chunk.startsWith(prefix)), row.toString());
            }
        }
    }

    @Test
    void everyCompletedRowKeepsItsRetrievalScoresAndTieAtCutoff() {
        for (var row : observation.snapshot().rows()) {
            assertTrue(row.tieAtCutoff().isPresent(), row.toString());
            assertTrue(row.evidence().size() <= row.answerK());
            for (var rank = 1; rank < row.evidence().size(); rank++) {
                assertTrue(row.evidence().get(rank - 1).score() >= row.evidence().get(rank).score(), row.toString());
            }
        }
        var direct = assertInstanceOf(RecalledEvidence.Retrieved.class, full.evidence().direct(full.memory(),
                "What does the fieldset element do?", HtmlKnowledgeRecall.LOOKAHEAD_LIMIT));
        var five = assertInstanceOf(RecalledEvidence.Retrieved.class, full.evidence().direct(full.memory(),
                "What does the fieldset element do?"));
        assertEquals(five.chunks(), direct.chunks().subList(0, five.chunks().size()),
                "the lookahead extends the limit-5 list without changing it");
        assertTrue(HtmlKnowledgeQaExperiment.tieAtCutoff(List.of(0.5, 0.5, 0.25), 1));
        assertFalse(HtmlKnowledgeQaExperiment.tieAtCutoff(List.of(0.5, 0.25), 1));
        assertFalse(HtmlKnowledgeQaExperiment.tieAtCutoff(List.of(0.5), 1), "no rank k + 1");
        assertFalse(HtmlKnowledgeQaExperiment.tieAtCutoff(List.of(0.0, -0.0), 1), "bit-exact comparison");
    }

    @Test
    void aLookaheadThatDoesNotExtendTheBoundListIsAFailedRecall() {
        var experiment = new HtmlKnowledgeQaExperiment();
        var query = new QueryText("q", "text");
        var five = retrieved("a", 0.5, "b", 0.4, "c", 0.4, "d", 0.4, "e", 0.4);
        assertEquals(Optional.empty(), experiment.extensionMismatch("B1", query, five,
                retrieved("a", 0.5, "b", 0.4, "c", 0.4, "d", 0.4, "e", 0.4, "f", 0.3)));
        assertEquals(Optional.empty(), experiment.extensionMismatch("B1", query, five,
                retrieved("a", 0.5, "b", 0.4, "c", 0.4, "d", 0.4, "f", 0.4, "e", 0.4)),
                "an equal-score substitution inside a tie straddling the cutoff is allowed");
        assertTrue(experiment.extensionMismatch("B1", query, five,
                retrieved("a", 0.5, "x", 0.45, "b", 0.4, "c", 0.4, "d", 0.4, "e", 0.4)).isPresent());
        assertTrue(experiment.extensionMismatch("B2", query, retrieved("a", 0.5, "b", 0.4),
                retrieved("a", 0.5, "c", 0.4, "b", 0.4)).isPresent(), "a shorter list cannot gain a result");
    }

    @Test
    void anEligibleParaphraseIsNotAFailedRunAndTheMatrixMustBeComplete() {
        AnswerComponent paraphraser = new AnswerComponent() {
            public String name() {
                return "paraphraser";
            }

            public String description() {
                return "test component";
            }

            public Kind kind() {
                return Kind.TEST_DOUBLE;
            }

            public String tuning() {
                return "none";
            }

            public GroundedAnswer answer(String question, List<EvidenceChunk> evidence) {
                return evidence.isEmpty() ? GroundedAnswer.Abstained.insufficient("none")
                        : new GroundedAnswer.Answered(List.of(new GroundedAnswer.Claim("In other words.",
                        List.of(new Citation(evidence.getFirst().chunkId(), 0, 3)))));
            }
        };
        var texts = List.of(new QueryText(NOVICE, QUERY_SET.queries().stream()
                .filter(query -> query.queryId().equals(NOVICE)).findFirst().orElseThrow().text()));
        var observed = new HtmlKnowledgeQaExperiment(List.of(paraphraser)).run(List.of(full), QUERY_SET.queryHash(),
                texts);
        var eligible = observed.checks().stream().filter(check -> check.name().equals("citationsEligible"))
                .findFirst().orElseThrow();
        assertTrue(eligible.passed(), eligible.detail());
        assertTrue(eligible.detail().contains(" 0 of "), eligible.detail());

        var components = new HtmlKnowledgeQaExperiment().components();
        var roots = List.of("full", "removed");
        var rows = observation.snapshot().rows();
        AnswerMetrics.requireComplete(observation.snapshot(), QUERY_SET, roots, components);
        var missing = new AnswerTrace.Snapshot(QUERY_SET.queryHash(), rows.subList(1, rows.size()));
        assertTrue(assertThrowsMessage(missing, roots, components).contains("missing row"));
        var duplicated = new ArrayList<>(rows);
        duplicated.add(rows.getFirst());
        assertTrue(assertThrowsMessage(new AnswerTrace.Snapshot(QUERY_SET.queryHash(), duplicated), roots, components)
                .contains("duplicate row"));
        var withoutDoubles = rows.stream().filter(row -> row.kind() != AnswerComponent.Kind.TEST_DOUBLE).toList();
        assertTrue(assertThrowsMessage(new AnswerTrace.Snapshot(QUERY_SET.queryHash(), withoutDoubles), roots,
                components).contains("missing row"));
    }

    @Test
    void blankQueriesHaveEmptyEvidenceAndEveryComponentAbstains() {
        for (var row : observation.snapshot().rows()) {
            if (row.queryId().startsWith("q:blank-")) {
                assertEquals(List.of(), row.evidence());
                assertEquals(Outcome.ABSTAINED, row.outcome(), row.toString());
            }
        }
    }

    @Test
    void neuronEvidenceCarriesTheStoreContentOfTheControlForTheSameAtoms() {
        var question = CORPUS.chunk("chk:links#00").orElseThrow().text();
        var neuron = assertInstanceOf(RecalledEvidence.Retrieved.class,
                full.evidence().neuron(full.adapter(), full.memory(), question));
        assertFalse(neuron.chunks().isEmpty());
        for (var chunk : neuron.chunks()) {
            assertEquals(CORPUS.chunk(chunk.chunkId()).orElseThrow().sha256(), CorpusDigests.sha256(chunk.text()));
        }
        var direct = assertInstanceOf(RecalledEvidence.Retrieved.class, full.evidence().direct(full.memory(),
                question));
        assertEquals("chk:links#00", direct.chunks().getFirst().chunkId(), "exact chunk text is recalled first");
    }

    @Test
    void aFailedRecallIsReportedAndNeverReplacedByCorpusText() {
        var closed = ResonanceStoreMemoryAdapter.open(directory.resolve("full"),
                new HtmlKnowledgeRecall(CORPUS).adapterConfig());
        closed.close();
        var question = CORPUS.chunks().getFirst().text();
        assertInstanceOf(RecalledEvidence.Failed.class, full.evidence().neuron(closed, full.memory(), question));
        var swapped = new RecalledEvidence(CORPUS, swapped());
        var failed = assertInstanceOf(RecalledEvidence.Failed.class, swapped.direct(full.memory(), question));
        assertTrue(failed.reason().contains("does not hash to its corpus sha256"), failed.reason());
    }

    @Test
    void aCitationOutsideTheEvidenceOrItsBoundsIsIneligible() {
        var experiment = new HtmlKnowledgeQaExperiment();
        var evidence = List.of(new EvidenceChunk("chk:a#00", 1, 1.0, "Short text."));
        var exact = experiment.verify("Short text.", new Citation("chk:a#00", 0, 11), evidence);
        assertTrue(exact.eligible() && exact.exactQuote());
        var paraphrase = experiment.verify("Brief text.", new Citation("chk:a#00", 0, 11), evidence);
        assertTrue(paraphrase.eligible() && !paraphrase.exactQuote());
        assertFalse(experiment.verify("x", new Citation("chk:a#00", 0, 12), evidence).eligible());
        assertFalse(experiment.verify("x", new Citation("chk:b#00", 0, 1), evidence).eligible());
    }

    @Test
    void theAskModePrintsCitedExtractsOrInsufficientEvidence() {
        var buffer = new ByteArrayOutputStream();
        HtmlKnowledgeAnswerRunner.ask("What is the boiling point of water at sea level?",
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        var printed = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("B1 evidence: "), printed);
        assertTrue(printed.contains("B3c: INSUFFICIENT_EVIDENCE"), printed);
        assertTrue(printed.contains("chunk sha256 "), "B3 answers with a cited extract: " + printed);
    }

    @Test
    void theAnswerSideReferencesNoGoldLabelType() throws IOException {
        for (var type : List.of("HtmlKnowledgeQaExperiment", "HtmlKnowledgeAnswerPhase", "AnswerTrace",
                "AnswerTrace$AnswerRow", "AnswerTrace$Snapshot", "ExtractiveAnswerer", "EvidenceSegmenter",
                "RecalledEvidence", "TestDoubleAnswerGenerator", "GroundedAnswerGenerator", "AnswerComponent",
                "GroundedAnswer")) {
            try (var input = HtmlKnowledgeQaExperiment.class.getResourceAsStream(type + ".class")) {
                var bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (var gold : List.of("HtmlKnowledgeQuerySet", "JudgedQuery", "RecallMetrics", "AnswerMetrics",
                        "AnswerJudgments")) {
                    assertFalse(bytes.contains(gold), type + " references " + gold);
                }
            }
        }
    }

    private static String assertThrowsMessage(AnswerTrace.Snapshot snapshot, List<String> roots,
            List<AnswerComponent> components) {
        return assertThrows(IllegalArgumentException.class,
                () -> AnswerMetrics.requireComplete(snapshot, QUERY_SET, roots, components)).getMessage();
    }

    private static RecalledEvidence.Retrieved retrieved(Object... idsAndScores) {
        var chunks = new ArrayList<EvidenceChunk>();
        for (var i = 0; i < idsAndScores.length; i += 2) {
            chunks.add(new EvidenceChunk("chk:" + idsAndScores[i] + "#00", i / 2 + 1, (Double) idsAndScores[i + 1],
                    "text"));
        }
        return new RecalledEvidence.Retrieved(chunks);
    }

    private static IngestionManifest swapped() {
        var entries = new ArrayList<>(fullManifest.entries());
        var first = entries.get(0);
        var second = entries.get(1);
        entries.set(0, new IngestionManifest.Entry(first.chunkId(), second.atomId(), second.opaqueReference()));
        entries.set(1, new IngestionManifest.Entry(second.chunkId(), first.atomId(), first.opaqueReference()));
        return new IngestionManifest(CORPUS.version(), CORPUS.corpusHash(), entries);
    }

    private static Root root(String name, HtmlKnowledgeCorpus corpus) {
        var path = directory.resolve(name);
        var manifest = new HtmlKnowledgeIngestion(corpus).ingest(path).manifest();
        if (name.equals("full")) {
            fullManifest = manifest;
        }
        return new Root(name, MonadaMemory.open(path, MonadaMemoryOptions.defaults()),
                ResonanceStoreMemoryAdapter.open(path, new HtmlKnowledgeRecall(corpus).adapterConfig()),
                new RecalledEvidence(corpus, manifest));
    }
}
