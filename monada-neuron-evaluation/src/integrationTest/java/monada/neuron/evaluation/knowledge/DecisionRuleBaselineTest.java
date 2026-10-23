package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Tri;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host rule baseline (Issue #112) over corpus chunks given directly as evidence; no Store is involved. */
class DecisionRuleBaselineTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
    private static final HtmlDecisionCases CASES = HtmlDecisionCases.bundled();
    private static final List<EvidenceChunk> ALL = CORPUS.chunks().stream()
            .map(chunk -> new EvidenceChunk(chunk.chunkId(), 1, 1.0, chunk.text())).toList();

    private final DecisionRuleBaseline rules = new DecisionRuleBaseline();

    @Test
    void withEveryChunkAsEvidenceEachCaseGetsItsRuleAndQuotesItsCitations() {
        for (var decisionCase : CASES.cases()) {
            var recommendation = rules.decide(decisionCase, ALL);
            var claims = switch (recommendation) {
                case Decided decided -> decided.claims();
                case Conditional conditional -> conditional.claims();
                case Abstained abstained -> {
                    assertTrue(abstained.reason().contains("unknown"), decisionCase.caseId() + " " + abstained);
                    yield List.<GroundedAnswer.Claim>of();
                }
            };
            for (var claim : claims) {
                var citation = claim.citations().getFirst();
                var text = CORPUS.chunk(citation.chunkId()).orElseThrow().text();
                assertEquals(claim.text(), text.substring(citation.start(), citation.end()), decisionCase.caseId());
            }
        }
    }

    @Test
    void emptyEvidenceAlwaysAbstains() {
        CASES.cases().forEach(decisionCase -> assertInstanceOf(Abstained.class, rules.decide(decisionCase,
                List.of()), decisionCase.caseId()));
    }

    @Test
    void anUnknownConstraintAsksItsQuestionInsteadOfGuessing() {
        var abstained = assertInstanceOf(Abstained.class, rules.decide(CASES.byId("case:list-ambiguous-01"), ALL));
        assertEquals(Constraint.ORDER_MATTERS.question(), abstained.clarifyingQuestion());
        var embed = assertInstanceOf(Abstained.class, rules.decide(CASES.byId("case:embed-ambiguous-01"), ALL));
        assertEquals(Constraint.SEPARATE_DOCUMENT.question(), embed.clarifyingQuestion());
    }

    @Test
    void changingOneConstraintReversesTheDecision() {
        var install = CASES.byId("case:list-install-01");
        assertEquals("ol", assertInstanceOf(Decided.class, rules.decide(install, ALL)).choice());
        assertEquals("ul", assertInstanceOf(Decided.class, rules.decide(with(install, Constraint.ORDER_MATTERS,
                Tri.NO), ALL)).choice());
        var defer = CASES.byId("case:script-defer-external-01");
        assertEquals("external+defer", assertInstanceOf(Decided.class, rules.decide(defer, ALL)).choice());
        var inline = assertInstanceOf(Conditional.class, rules.decide(with(defer, Constraint.EXTERNAL_FILE_ALLOWED,
                Tri.NO), ALL));
        assertEquals("module", inline.options().getFirst().choice());
    }

    @Test
    void neverRecommendsDeferOnInlineCodeNorUnrequestedPermissions() {
        for (var decisionCase : CASES.cases()) {
            if (rules.decide(decisionCase, ALL) instanceof Decided decided) {
                assertTrue(!decided.choice().equals("inline+defer"), decisionCase.caseId());
                if (decisionCase.family() == HtmlDecisionCases.Family.EMBED
                        && decisionCase.value(Constraint.POWERFUL_FEATURES) != Tri.YES) {
                    assertEquals(List.of(), decided.permissions(), decisionCase.caseId());
                }
            }
        }
        var video = assertInstanceOf(Decided.class, rules.decide(CASES.byId("case:embed-video-01"), ALL));
        assertEquals(List.of("allow=requested-features-only"), video.permissions());
    }

    @Test
    void aMissingRequiredCueAbstainsWithInsufficientEvidence() {
        var withoutLists = ALL.stream().filter(chunk -> !chunk.chunkId().startsWith("chk:lists-")).toList();
        var abstained = assertInstanceOf(Abstained.class, rules.decide(CASES.byId("case:list-install-01"),
                withoutLists));
        assertTrue(abstained.reason().startsWith("insufficient evidence"), abstained.reason());
    }

    @Test
    void theRuleTableRendersEveryRule() {
        assertEquals(14, DecisionRuleBaseline.RULES.size());
        DecisionRuleBaseline.RULES.forEach(rule -> assertTrue(rule.render().startsWith(rule.id() + ": if "),
                rule.render()));
    }

    private static DecisionCase with(DecisionCase decisionCase, Constraint constraint, Tri value) {
        var constraints = new EnumMap<>(decisionCase.constraints());
        constraints.put(constraint, value);
        return new DecisionCase(decisionCase.caseId(), decisionCase.family(), decisionCase.pairs(),
                decisionCase.requirement(), constraints, decisionCase.candidates());
    }
}
