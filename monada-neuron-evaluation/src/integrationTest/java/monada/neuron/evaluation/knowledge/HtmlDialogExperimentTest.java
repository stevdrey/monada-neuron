package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.DecisionMetrics.Verdict;
import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Held;
import monada.neuron.evaluation.knowledge.DialogContextAssembler.Hold;
import monada.neuron.evaluation.knowledge.DialogMetrics.Support;
import monada.neuron.evaluation.knowledge.DialogTrace.Arm;
import monada.neuron.evaluation.knowledge.DialogTrace.Change;
import monada.neuron.evaluation.knowledge.DialogTrace.TurnRow;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the multi-turn suite in-process over a freshly imported temporary Store (Issue #113): real Store evidence, real
 * Neuron cycles per turn, the host context assembler and the unchanged host rules, then the scorer.
 */
class HtmlDialogExperimentTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
    private static final HtmlDecisionCases CASES = HtmlDecisionCases.bundled();
    private static final HtmlDialogCases DIALOGS = HtmlDialogCases.bundled();
    private static final HtmlDialogGold GOLD = HtmlDialogGold.bundled(DIALOGS, CASES,
            HtmlDecisionGold.bundledRevision(CASES, CORPUS).orElseThrow(), CORPUS,
            HtmlDialogRunner.reference(DIALOGS));

    @TempDir
    static Path directory;

    private static HtmlDecisionExperiment.Root root;
    private static HtmlDialogExperiment.Observation observation;
    private static DialogMetrics.Scores scores;

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
        observation = new HtmlDialogExperiment(new DecisionRuleBaseline(), CORPUS).run(root, DIALOGS);
        scores = DialogMetrics.score(observation.rows(), DIALOGS, GOLD);
    }

    @Test
    void everySemanticCheckPasses() {
        assertEquals(List.of("dialog.completed", "dialog.selectorCitationsVerified", "context.noLeakage",
                "control.noContextElliptical", "control.evidenceRemoved", "dialog.deterministic",
                "perf.matchesVerified"), observation.checks().stream().map(Check::name).toList());
        observation.checks().forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
        assertEquals(DIALOGS.turns().size() * Arm.values().length, observation.rows().size());
        assertEquals(2, observation.perf().size());
    }

    @Test
    void everyTurnRecordsItsQuestionContextEvidenceAndAnswer() {
        for (var row : observation.rows()) {
            assertEquals(DIALOGS.turn(row.turnId()).utterance(), row.question());
            assertEquals(row.turnId(), row.row().caseId());
            if (row.arm() == Arm.STORE) {
                assertEquals(Outcome.RETRIEVED, row.row().outcome(), row.turnId());
                continue;
            }
            assertTrue(row.row().recommendation().isPresent(), row.turnId() + " " + row.arm());
            if (row.arm() == Arm.NEURON_RULES || row.arm() == Arm.EVIDENCE_REMOVED) {
                assertTrue(row.row().neuron().isPresent(), row.turnId());
            }
            if (row.arm() == Arm.NO_CONTEXT) {
                assertEquals("-", row.context());
                assertEquals(List.of(), row.assembled().used());
            }
        }
    }

    @Test
    void anEllipticalFollowUpIsNeverUnderstoodWithoutContext() {
        for (var scored : scores.scored()) {
            if (scored.row().arm() != Arm.NO_CONTEXT || DIALOGS.turn(scored.row().turnId()).family().isPresent()) {
                continue;
            }
            assertEquals(Outcome.ABSTAINED, scored.row().row().outcome(), scored.row().turnId());
            if (scored.label().gold().expected() != HtmlDecisionGold.Expected.ABSTAIN) {
                assertFalse(scored.taskCorrect(), scored.row().turnId() + " is not answered without context");
            }
        }
    }

    @Test
    void withTheEvidenceRemovedNothingClaimsSupport() {
        for (var scored : scores.scored()) {
            if (scored.row().arm() == Arm.EVIDENCE_REMOVED) {
                assertTrue(scored.support() == Support.ABSTAINED || scored.support() == Support.UNSUPPORTED,
                        scored.row().turnId() + " " + scored.support());
                assertFalse(scored.decision().flags().getOrDefault("noEvidenceCertainty", false));
            }
        }
    }

    @Test
    void theHostHoldsContradictionsBudgetAndUnseenTurnsInsteadOfGuessing() {
        var expected = List.of("dialog:list-order#3 CONTRADICTION", "dialog:lost-context#3 MISSING_ANTECEDENT",
                "dialog:lost-context#4 BUDGET_EXHAUSTED", "dialog:embed-partner#4 UNSUPPORTED",
                "dialog:orphan-ellipsis#1 NO_FAMILY");
        var held = observation.rows().stream().filter(row -> row.arm() == Arm.NEURON_RULES)
                .filter(row -> row.assembled() instanceof Held).map(row -> row.turnId() + " " + row.assembly())
                .toList();
        assertTrue(held.containsAll(expected), held.toString());
        for (var row : observation.rows()) {
            if (row.arm() != Arm.STORE && row.assembled() instanceof Held) {
                assertEquals(Outcome.ABSTAINED, row.row().outcome(), row.turnId());
                assertTrue(row.row().recommendation().orElseThrow().ruleId().startsWith("context:"), row.turnId());
            }
        }
    }

    @Test
    void onlyAnAbstentionThatAsksCountsAsAClarification() {
        var b = scores.aggregates().stream().filter(aggregate -> aggregate.arm() == Arm.NEURON_RULES).findFirst()
                .orElseThrow();
        var asked = scores.scored().stream().filter(scored -> scored.row().arm() == Arm.NEURON_RULES)
                .filter(scored -> scored.row().row().recommendation().orElse(null)
                        instanceof DecisionRecommendation.Abstained abstained
                        && !abstained.clarifyingQuestion().equals("-")).count();
        assertEquals(asked, (long) b.counts().get("clarificationRequests"));
        assertTrue(b.counts().get("clarificationRequests") < b.counts().get("abstained"), b.counts().toString());
        for (var turnId : List.of("dialog:embed-partner#4", "dialog:lost-context#4")) {
            var row = scores.scored().stream().filter(scored -> scored.row().arm() == Arm.NEURON_RULES
                    && scored.row().turnId().equals(turnId)).findFirst().orElseThrow();
            assertEquals("-", ((DecisionRecommendation.Abstained) row.row().row().recommendation().orElseThrow())
                    .clarifyingQuestion(), turnId + " abstains without asking");
        }
        assertFalse(b.metrics().containsKey("clarificationRecall"));
    }

    @Test
    void aChangeThatShouldReviseIsScoredAndOneThatNeedNotIsCreditedByCorrectness() {
        var b = scores.aggregates().stream().filter(aggregate -> aggregate.arm() == Arm.NEURON_RULES).findFirst()
                .orElseThrow();
        assertTrue(b.counts().get("revisionTurns") >= 5, b.counts().toString());
        assertTrue(b.counts().get("noChangeRequiredTurns") >= 4, b.counts().toString());
        var conditional = scores.scored().stream().filter(scored -> scored.row().arm() == Arm.NEURON_RULES
                && scored.row().turnId().equals("dialog:script-dom#3")).findFirst().orElseThrow();
        assertEquals(Verdict.CORRECT_CONDITIONAL, conditional.decision().verdict());
        assertEquals(Change.REVISED, conditional.row().change());
        assertEquals(Optional.of(true), conditional.revisionCorrect());
        var kept = scores.scored().stream().filter(scored -> scored.row().arm() == Arm.NEURON_RULES
                && scored.row().turnId().equals("dialog:script-dom#4")).findFirst().orElseThrow();
        assertEquals(Change.UNCHANGED, kept.row().change(), "a conditional answer stays conditional");
        assertEquals(Optional.empty(), kept.revisionCorrect(), "no change is required, so none is scored");
        assertTrue(kept.correct());
    }

    @Test
    void aTurnOfAnotherDialogInjectedIntoTheContextIsRejectedNotUsed() {
        var foreign = DIALOGS.turn("dialog:topic-switch#1");
        var tampered = new HtmlDialogExperiment(new DecisionRuleBaseline(), CORPUS, context -> context.current()
                .turn() == 1 ? context : new DialogContext(context.version(), context.dialogId(), context.current(),
                        prepend(foreign, context))).rows(root, DIALOGS);
        for (var row : tampered) {
            if (row.turn() == 1 || row.arm() == Arm.NO_CONTEXT) {
                continue;
            }
            var held = (Held) row.assembled();
            assertEquals(Hold.REJECTED, held.hold(), row.turnId());
            if (!row.dialogId().equals(foreign.dialogId())) {
                assertTrue(held.detail().contains(foreign.turnId()), held.detail());
            }
        }
    }

    @Test
    void aSelectorThatIgnoresConstraintsDoesNotReviseWhenItShould() {
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
        var rows = new HtmlDialogExperiment(constant, CORPUS).rows(root, DIALOGS);
        var constantScores = DialogMetrics.score(rows, DIALOGS, GOLD);
        var b = constantScores.aggregates().stream().filter(aggregate -> aggregate.arm() == Arm.NEURON_RULES)
                .findFirst().orElseThrow();
        assertTrue(b.counts().get("revisionCorrect") < b.counts().get("revisionTurns"), b.counts().toString());
        assertTrue(b.counts().get("unjustifiedCertainty") > 0, b.counts().toString());
    }

    @Test
    void theScorerRequiresEveryTurnAndArmOnce() {
        var rows = new ArrayList<TurnRow>(observation.rows());
        rows.removeLast();
        assertThrows(IllegalArgumentException.class, () -> DialogMetrics.score(rows, DIALOGS, GOLD));
        rows.add(observation.rows().getFirst());
        assertThrows(IllegalArgumentException.class, () -> DialogMetrics.score(rows, DIALOGS, GOLD));
    }

    private static List<DialogContext.PriorTurn> prepend(HtmlDialogCases.Turn foreign, DialogContext context) {
        var priors = new ArrayList<DialogContext.PriorTurn>();
        priors.add(new DialogContext.PriorTurn(foreign, Optional.empty()));
        priors.addAll(context.priors());
        var limit = DialogContextAssembler.MAX_TURNS - 1;
        return priors.size() > limit ? priors.subList(0, limit) : priors;
    }
}
