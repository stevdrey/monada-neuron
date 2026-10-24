package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.AnswerTrace.ClaimRow;
import monada.neuron.evaluation.knowledge.DecisionMetrics.Verdict;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Exclusion;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Option;
import monada.neuron.evaluation.knowledge.DecisionTrace.Arm;
import monada.neuron.evaluation.knowledge.DecisionTrace.Hit;
import monada.neuron.evaluation.knowledge.DecisionTrace.Row;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Claim;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative tests of the decision scorer (Issue #112) on hand-built rows: fabricated evidence, wrong HTML semantics,
 * unrequested permissions, certainty without evidence and a decision that ignores a changed constraint.
 */
class DecisionMetricsTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
    private static final HtmlDecisionCases CASES = HtmlDecisionCases.bundled();
    private static final HtmlDecisionGold GOLD = HtmlDecisionGold.bundled(CASES, CORPUS);
    private static final String LISTS = "chk:lists-and-text-semantics#00";
    private static final String DEFER = "chk:scripts-and-embedding#01";
    private static final String DIV_IFRAME = "chk:headings-and-structure#04";

    private final HtmlKnowledgeQaExperiment citations = new HtmlKnowledgeQaExperiment(List.of());

    @Test
    void aGroundedCorrectDecisionScoresClean() {
        var scored = score("case:list-install-01", decided("ol", List.of(LISTS)), LISTS);
        assertEquals(Verdict.CORRECT_DECISION, scored.verdict());
        assertEquals(1.0, scored.metrics().get("citationFidelity").getAsDouble());
        assertEquals(0.0, scored.metrics().get("unsupportedClaimRate").getAsDouble());
        assertEquals(1.0, scored.metrics().get("citationGoldOverlap").getAsDouble());
        assertTrue(scored.flags().get("constraintsSatisfied"));
        assertFalse(scored.flags().get("contradiction"));
        assertEquals(List.of(), scored.diagnostics());
    }

    @Test
    void fabricatedEvidenceIsIneligibleAndUnsupported() {
        var claim = quote(LISTS);
        var recommendation = new Decided("ol", List.of(), List.of(), List.of(), List.of(), "host", List.of(claim),
                "fake");
        var scored = DecisionMetrics.score(row("case:list-install-01", recommendation, List.of(DEFER)),
                CASES.byId("case:list-install-01"), GOLD.of("case:list-install-01"));
        assertEquals(0.0, scored.metrics().get("citationFidelity").getAsDouble());
        assertEquals(1.0, scored.metrics().get("unsupportedClaimRate").getAsDouble());
        assertTrue(scored.flags().get("noEvidenceCertainty"));
        assertTrue(scored.diagnostics().contains("answered without any eligible citation"), scored.diagnostics()
                .toString());
    }

    @Test
    void aClaimWhoseTextDiffersFromItsSpanIsUnsupported() {
        var real = quote(LISTS);
        var altered = new Claim("Use ol for any list.", real.citations());
        var scored = score("case:list-install-01", new Decided("ol", List.of(), List.of(), List.of(), List.of(),
                "host", List.of(altered), "fake"), LISTS);
        assertEquals(1.0, scored.metrics().get("citationFidelity").getAsDouble());
        assertEquals(1.0, scored.metrics().get("unsupportedClaimRate").getAsDouble());
    }

    @Test
    void deferOnAnInlineScriptIsWrongHtmlSemantics() {
        var scored = score("case:script-defer-external-01", decided("inline+defer", List.of(DEFER)), DEFER);
        assertEquals(Verdict.WRONG_CHOICE, scored.verdict());
        assertTrue(scored.flags().get("contradiction"));
        assertFalse(scored.flags().get("constraintsSatisfied"));
        assertTrue(scored.diagnostics().contains("named ineligible choice inline+defer"));
    }

    @Test
    void excludingTheAcceptableChoiceIsAContradiction() {
        var claim = quote(LISTS);
        var recommendation = new Decided("ol", List.of(), List.of(new Exclusion("ul", "wrong", List.of())), List.of(),
                List.of(), "host", List.of(claim), "fake");
        var scored = score("case:list-ingredients-01", recommendation, LISTS);
        assertEquals(Verdict.WRONG_CHOICE, scored.verdict());
        assertTrue(scored.diagnostics().contains("excluded an acceptable choice"));
    }

    @Test
    void anUnrequestedPermissionIsFlagged() {
        var claim = quote(DIV_IFRAME);
        var recommendation = new Decided("iframe", List.of(), List.of(), List.of(), List.of("allow=camera"), "host",
                List.of(claim), "fake");
        var scored = score("case:embed-widget-01", recommendation, DIV_IFRAME);
        assertTrue(scored.flags().get("unrequestedPermission"));
        assertFalse(scored.flags().get("constraintsSatisfied"));
        var missing = score("case:embed-video-01", new Decided("iframe", List.of(), List.of(), List.of(), List.of(),
                "host", List.of(claim), "fake"), DIV_IFRAME);
        assertTrue(missing.flags().get("missingPermission"));
    }

    @Test
    void onlyTheExactRequestedGrantSatisfiesARequestedPermission() {
        var claim = quote(DIV_IFRAME);
        var exact = score("case:embed-video-01", new Decided("iframe", List.of(), List.of(), List.of(),
                List.of(DecisionRecommendation.REQUESTED_FEATURES_ONLY), "host", List.of(claim), "fake"), DIV_IFRAME);
        assertFalse(exact.flags().get("unrequestedPermission"));
        assertFalse(exact.flags().get("missingPermission"));
        var overbroad = score("case:embed-video-01", new Decided("iframe", List.of(), List.of(), List.of(),
                List.of("allow=everything"), "host", List.of(claim), "fake"), DIV_IFRAME);
        assertTrue(overbroad.flags().get("unrequestedPermission"));
        assertTrue(overbroad.flags().get("missingPermission"));
        assertFalse(overbroad.flags().get("constraintsSatisfied"));
        var extra = score("case:embed-video-01", new Decided("iframe", List.of(), List.of(), List.of(),
                List.of(DecisionRecommendation.REQUESTED_FEATURES_ONLY, "allow=geolocation"), "host", List.of(claim),
                "fake"), DIV_IFRAME);
        assertTrue(extra.flags().get("unrequestedPermission"));
    }

    @Test
    void aCorrectChoiceWithAnUngroundedRationaleIsNotCountedAsGrounded() {
        var offTopic = quote(DEFER);
        var scored = score("case:list-install-01", new Decided("ol", List.of(), List.of(), List.of(), List.of(),
                "host", List.of(offTopic), "fake"), DEFER);
        assertEquals(Verdict.CORRECT_DECISION, scored.verdict(), "the choice alone is still correct");
        assertFalse(scored.flags().get("rationaleGrounded"));
        assertTrue(score("case:list-install-01", decided("ol", List.of(LISTS)), LISTS).flags()
                .get("rationaleGrounded"));
    }

    @Test
    void certaintyWithoutEvidenceOnAnAmbiguousCaseIsOverconfident() {
        var recommendation = new Decided("ul", List.of(), List.of(), List.of(), List.of(), "host", List.of(), "fake");
        var scored = DecisionMetrics.score(row("case:list-ambiguous-01", recommendation, List.of()),
                CASES.byId("case:list-ambiguous-01"), GOLD.of("case:list-ambiguous-01"));
        assertEquals(Verdict.OVERCONFIDENT, scored.verdict());
        assertTrue(scored.flags().get("noEvidenceCertainty"));
        var asked = DecisionMetrics.score(row("case:list-ambiguous-01", new Abstained("unknown", "?", "fake"),
                List.of()), CASES.byId("case:list-ambiguous-01"), GOLD.of("case:list-ambiguous-01"));
        assertEquals(Verdict.CORRECT_ABSTENTION, asked.verdict());
    }

    @Test
    void anAbstentionIsAnEvidenceMissOnlyWhenNoGoldChunkWasSeen() {
        var abstained = new Abstained("insufficient evidence", "-", "fake");
        assertEquals(Verdict.EVIDENCE_MISS_ABSTENTION, DecisionMetrics.score(row("case:list-install-01", abstained,
                List.of(DEFER)), CASES.byId("case:list-install-01"), GOLD.of("case:list-install-01")).verdict());
        assertEquals(Verdict.WRONG_ABSTENTION, DecisionMetrics.score(row("case:list-install-01", abstained,
                List.of(LISTS)), CASES.byId("case:list-install-01"), GOLD.of("case:list-install-01")).verdict());
    }

    @Test
    void aConditionalAnswerMustStateEveryGoldOption() {
        var claim = quote("chk:script-loading-and-permissions#03");
        var partial = new Conditional(List.of(new Option("iframe", "isolation-preferred", "s")), List.of(), List.of(),
                List.of(claim), "fake");
        assertEquals(Verdict.INCOMPLETE_CONDITIONAL, score("case:embed-help-01", partial,
                "chk:script-loading-and-permissions#03").verdict());
        var module = quote("chk:script-loading-and-permissions#01");
        var overbroad = new Conditional(List.of(new Option("module", "module-compatible", "s"),
                new Option("external", "hosting-changes", "s")), List.of(), List.of(), List.of(module), "fake");
        assertEquals(Verdict.OVERBROAD_CONDITIONAL, score("case:script-defer-inline-01", overbroad,
                "chk:script-loading-and-permissions#01").verdict());
        var hedged = new Conditional(List.of(new Option("ol", "x", "s")), List.of(), List.of(), List.of(quote(LISTS)),
                "fake");
        assertEquals(Verdict.HEDGED, score("case:list-install-01", hedged, LISTS).verdict());
    }

    @Test
    void ignoringTheVariedConstraintIsDetectedAsAMissingReversal() {
        var install = row("case:list-install-01", decided("ol", List.of(LISTS)), List.of(LISTS));
        var ingredients = row("case:list-ingredients-01", decided("ol", List.of(LISTS)), List.of(LISTS));
        assertEquals(DecisionTrace.signature(install), DecisionTrace.signature(ingredients));
        assertNotEquals(DecisionMetrics.goldSignature(GOLD.of("case:list-install-01")),
                DecisionMetrics.goldSignature(GOLD.of("case:list-ingredients-01")));
    }

    @Test
    void anIncompleteRowMatrixIsRejected() {
        var only = List.of(row("case:list-install-01", decided("ol", List.of(LISTS)), List.of(LISTS)));
        var message = assertThrows(IllegalArgumentException.class, () -> DecisionMetrics.score(only, CASES, GOLD))
                .getMessage();
        assertTrue(message.startsWith("missing row"), message);
    }

    /** A decision citing the first segment of each of {@code chunks}. */
    private Decided decided(String choice, List<String> chunks) {
        return new Decided(choice, List.of(), List.of(), List.of(), List.of(), "host",
                chunks.stream().map(this::quote).toList(), "fake");
    }

    private DecisionMetrics.Scored score(String caseId, DecisionRecommendation recommendation, String evidence) {
        return DecisionMetrics.score(row(caseId, recommendation, List.of(evidence)), CASES.byId(caseId),
                GOLD.of(caseId));
    }

    private Row row(String caseId, DecisionRecommendation recommendation, List<String> evidenceIds) {
        var evidence = evidenceIds.stream().map(id -> new EvidenceChunk(id, 1, 1.0,
                CORPUS.chunk(id).orElseThrow().text())).toList();
        var claims = switch (recommendation) {
            case Decided decided -> decided.claims();
            case Conditional conditional -> conditional.claims();
            case Abstained _ -> List.<Claim>of();
        };
        var rows = claims.stream().map(claim -> new ClaimRow(claim.text(), claim.citations().stream()
                .map(citation -> citations.verify(claim.text(), citation, evidence)).toList())).toList();
        return new Row(caseId, Arm.NEURON_RULES, "fake", "TEST_DOUBLE", evidence.stream()
                .map(chunk -> new Hit(chunk.chunkId(), chunk.rank(), chunk.score())).toList(), Optional.empty(),
                Row.outcomeOf(recommendation), Optional.of(recommendation), rows, List.of(), "-");
    }

    /** The claim quoting the gold span of {@code chunkId} when it has one, else the chunk's first segment. */
    private Claim quote(String chunkId) {
        var text = CORPUS.chunk(chunkId).orElseThrow().text();
        var segment = new EvidenceSegmenter().segments(text).stream()
                .filter(candidate -> HtmlTokens.distinct(candidate.text()).containsAll(List.of("ol", "ul", "order"))
                        || HtmlTokens.distinct(candidate.text()).containsAll(List.of("iframe", "embeds"))
                        || HtmlTokens.distinct(candidate.text()).containsAll(List.of("defer", "external", "parsed"))
                        || HtmlTokens.distinct(candidate.text()).containsAll(List.of("iframe", "isolated"))
                        || HtmlTokens.distinct(candidate.text()).containsAll(List.of("inline", "classic", "ignored")))
                .findFirst().orElseThrow();
        return new Claim(segment.text(), List.of(new Citation(chunkId, segment.start(), segment.end())));
    }
}
