package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.DecisionMetrics.Verdict;
import monada.neuron.evaluation.knowledge.DecisionTrace.Arm;
import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the decision benchmark in-process over a freshly imported temporary Store (Issue #112): real Store evidence,
 * real Neuron cycles, the host rules, the test-double generator, and a constant selector that ignores constraints.
 */
class HtmlDecisionExperimentTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
    private static final HtmlDecisionCases CASES = HtmlDecisionCases.bundled();
    private static final HtmlDecisionGold GOLD = HtmlDecisionGold.bundled(CASES, CORPUS);

    @TempDir
    static Path directory;

    private static HtmlDecisionExperiment.Root root;
    private static HtmlDecisionExperiment.Observation observation;

    @BeforeAll
    static void runOnce() {
        var path = directory.resolve("store");
        var ingestion = new HtmlKnowledgeIngestion(CORPUS);
        var manifest = ingestion.ingest(path).manifest();
        var memory = MonadaMemory.open(path, MonadaMemoryOptions.defaults());
        var unread = ingestion.readbackSweep(memory, manifest).unread();
        root = new HtmlDecisionExperiment.Root(memory, ResonanceStoreMemoryAdapter.open(path,
                new HtmlKnowledgeRecall(CORPUS).adapterConfig()), new RecalledEvidence(CORPUS, manifest),
                new RecalledReferenceResolver(CORPUS, manifest, Set.copyOf(unread)));
        observation = new HtmlDecisionExperiment(new DecisionRuleBaseline(),
                Optional.of(new TestDoubleAnswerGenerator())).run(root, CASES.cases());
    }

    @Test
    void everySemanticCheckPasses() {
        assertEquals(List.of("decision.completed", "decision.selectorCitationsVerified",
                "control.emptyEvidenceAbstains", "decision.deterministic", "generator.labelled",
                "perf.matchesVerified"), observation.checks().stream().map(Check::name).toList());
        observation.checks().forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
        assertEquals(CASES.cases().size() * Arm.values().length, observation.rows().size());
        assertEquals(3, observation.perf().size());
    }

    @Test
    void theNeuronArmCarriesItsCycleTraceAndOnlySelectedEvidence() {
        for (var row : observation.rows()) {
            if (row.arm() != Arm.NEURON_RULES) {
                continue;
            }
            var trace = row.neuron().orElseThrow();
            assertEquals(row.evidence().stream().map(DecisionTrace.Hit::chunkId).toList(),
                    trace.decision() == EvidenceDecision.SUPPORTED ? trace.selected() : List.of(), row.caseId());
            assertTrue(trace.admitted().containsAll(trace.selected()), row.caseId());
        }
    }

    @Test
    void theGeneratorArmIsLabelledAndNeverJudged() {
        var scores = DecisionMetrics.score(observation.rows(), CASES, GOLD);
        scores.scored().stream().filter(scored -> scored.row().arm() == Arm.GENERATOR).forEach(scored -> {
            assertEquals(AnswerComponent.Kind.TEST_DOUBLE.name(), scored.row().kind());
            assertEquals(Verdict.NOT_SCORED, scored.verdict());
            assertTrue(scored.row().outcome() == Outcome.FREE_TEXT || scored.row().outcome() == Outcome.ABSTAINED);
        });
    }

    @Test
    void theScoredRunHasNoContradictionNoPermissionLeakAndAbstainsOnAmbiguity() {
        var scores = DecisionMetrics.score(observation.rows(), CASES, GOLD);
        for (var scored : scores.scored()) {
            assertFalse(scored.flags().getOrDefault("contradiction", false), scored.toString());
            assertFalse(scored.flags().getOrDefault("unrequestedPermission", false), scored.toString());
            assertFalse(scored.flags().getOrDefault("noEvidenceCertainty", false), scored.toString());
            if (scored.gold().expected() == HtmlDecisionGold.Expected.ABSTAIN && scored.row().arm() != Arm.STORE
                    && scored.row().arm() != Arm.GENERATOR) {
                assertEquals(Verdict.CORRECT_ABSTENTION, scored.verdict(), scored.row().caseId());
            }
        }
    }

    @Test
    void aSelectorThatIgnoresConstraintsFailsTheContrastivePairs() {
        var constant = new DecisionSelector() {
            @Override
            public String name() {
                return "constant";
            }

            @Override
            public String description() {
                return "TEST_DOUBLE: always the first candidate";
            }

            @Override
            public Kind kind() {
                return Kind.TEST_DOUBLE;
            }

            @Override
            public DecisionRecommendation decide(DecisionCase decisionCase, List<EvidenceChunk> evidence) {
                return new DecisionRecommendation.Decided(decisionCase.candidates().getFirst(), List.of(), List.of(),
                        List.of(), List.of(), "constant", List.of(), "constant");
            }
        };
        var rows = new HtmlDecisionExperiment(constant, Optional.empty()).matrix(root, CASES.cases());
        var scores = DecisionMetrics.score(rows, CASES, GOLD);
        var neuron = scores.aggregates().stream().filter(aggregate -> aggregate.arm() == Arm.NEURON_RULES)
                .findFirst().orElseThrow();
        assertEquals(0, neuron.pairsConsistent(), "a constant answer never reverses");
        assertEquals(CASES.cases().size(), neuron.flagged().get("noEvidenceCertainty"));
        assertTrue(neuron.verdicts().getOrDefault(Verdict.OVERCONFIDENT, 0) > 0);
    }

    @Test
    void aTimedNeuronPassThatAdmitsDifferentEvidenceIsWithheld() {
        UnaryOperator<ResonanceMemoryPort> dropLast = port -> request -> {
            var response = port.recall(request);
            var results = response.results();
            return new ResonanceMemoryResponse(response.status(), response.resultLimit(), results.isEmpty() ? results
                    : results.subList(0, results.size() - 1));
        };
        var degraded = new HtmlDecisionExperiment(new DecisionRuleBaseline(), Optional.empty(), dropLast)
                .run(root, CASES.cases());
        var perf = degraded.checks().stream().filter(check -> check.name().equals("perf.matchesVerified"))
                .findFirst().orElseThrow();
        assertFalse(perf.passed(), perf.detail());
        assertTrue(degraded.metadata().get("perf.neuron.cycle").startsWith("invalidated"));
        assertTrue(degraded.metadata().containsKey("perf.store.recall.medianNanosPerCase"));
        assertEquals(List.of("decision.store.recall", "decision.selector.decide"), degraded.perf().stream()
                .map(row -> row.benchmarkName()).toList());
    }

    @Test
    void noStepIsTimedWhenTheVerifiedPassFails() {
        var guessing = new DecisionSelector() {
            @Override
            public String name() {
                return "guess";
            }

            @Override
            public String description() {
                return "TEST_DOUBLE: decides the first candidate even without evidence";
            }

            @Override
            public Kind kind() {
                return Kind.TEST_DOUBLE;
            }

            @Override
            public DecisionRecommendation decide(DecisionCase decisionCase, List<EvidenceChunk> evidence) {
                return new DecisionRecommendation.Decided(decisionCase.candidates().getFirst(), List.of(), List.of(),
                        List.of(), List.of(), "guess", List.of(), "guess");
            }
        };
        var failed = new HtmlDecisionExperiment(guessing, Optional.empty()).run(root, CASES.cases());
        assertTrue(failed.checks().stream().anyMatch(check -> check.name().equals("control.emptyEvidenceAbstains")
                && !check.passed()));
        assertEquals(List.of(), failed.perf());
        HtmlDecisionExperiment.STEPS.forEach(step -> assertTrue(failed.metadata().get("perf." + step)
                .startsWith("withheld"), step));
    }

    @Test
    void theSelectorSideReferencesNoGoldLabelType() throws IOException {
        for (var type : List.of("HtmlDecisionExperiment", "DecisionRuleBaseline", "DecisionSelector",
                "DecisionRecommendation", "DecisionTrace", "DecisionTrace$Row", "HtmlDecisionCases",
                "HtmlDecisionCases$DecisionCase", "RecalledEvidence", "HtmlKnowledgeCycleExperiment")) {
            try (var input = HtmlDecisionExperiment.class.getResourceAsStream(type + ".class")) {
                var bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (var gold : List.of("HtmlDecisionGold", "DecisionMetrics", "HtmlKnowledgeQuerySet",
                        "JudgedQuery", "AnswerJudgments")) {
                    assertFalse(bytes.contains(gold), type + " references " + gold);
                }
            }
        }
    }
}
