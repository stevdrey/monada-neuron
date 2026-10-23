package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Family;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Expected;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The html-decision-v1 fixture and the html-kd-v1 supplement (Issue #112): pinned hashes, untouched html-kc-v1, and
 * every corruption rejected at load. A changed hash needs a new fixture version, never an edited constant.
 */
class HtmlDecisionFixtureTest {

    private static final String HTML_KC_V1_HASH = "1934ef6f127d37788bcb76e6a8e2c8cde7008e0ad9d0062277f65aa4861739a3";
    private static final String COMBINED_HASH = "5df0fbc303eab91950e98fa8c4e654ae7e03f6c2a661ff7a3f91013f2566dfb3";
    private static final String CASE_HASH = "ed860dc6b45eeda02874050010af4497e2441b2ec7b58331532079d76e25703c";

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
    private static final HtmlDecisionCases CASES = HtmlDecisionCases.bundled();
    private static final String CASES_TEXT = HtmlKnowledgeCorpus.bundledTable(HtmlDecisionCases.VERSION, "cases.tsv");
    private static final String GOLD_TEXT = HtmlKnowledgeCorpus.bundledTable(HtmlDecisionCases.VERSION, "gold.tsv");

    @Test
    void theSupplementLeavesHtmlKcV1Untouched() {
        assertEquals(HTML_KC_V1_HASH, HtmlKnowledgeCorpus.bundled().corpusHash());
        assertEquals(33, HtmlKnowledgeCorpus.bundled().chunks().size());
        var supplement = HtmlKnowledgeCorpus.bundledDecisionSupplement();
        assertEquals(HtmlKnowledgeCorpus.DECISION_SUPPLEMENT_VERSION, supplement.version());
        assertEquals(4, supplement.chunks().size());
        assertEquals(HtmlKnowledgeCorpus.TextPolicy.PUBLISHABLE, supplement.textPolicy());
        assertEquals(HtmlKnowledgeCorpus.DECISION_CORPUS_VERSION, CORPUS.version());
        assertEquals(37, CORPUS.chunks().size());
        assertEquals(COMBINED_HASH, CORPUS.corpusHash());
    }

    @Test
    void combiningACorpusWithItselfIsRejected() {
        var corpus = HtmlKnowledgeCorpus.bundledDecisionSupplement();
        assertThrows(CorpusValidationException.class, () -> HtmlKnowledgeCorpus.combine("twice",
                CorpusBounds.AUTHORED, List.of(HtmlKnowledgeCorpus.bundled(), corpus, corpus)));
    }

    @Test
    void theCaseSetIsPinnedAndHasContrastivePairsOfOneVariedConstraint() {
        assertEquals(CASE_HASH, CASES.caseHash());
        assertEquals(17, CASES.cases().size());
        assertEquals(12, CASES.pairs().size());
        for (var pair : CASES.pairs()) {
            var first = CASES.byId(pair.first());
            var second = CASES.byId(pair.second());
            var varied = first.family().constraints().stream()
                    .filter(constraint -> first.value(constraint) != second.value(constraint)).toList();
            assertEquals(List.of(pair.varied()), varied, pair.pairId());
        }
        assertTrue(CASES.cases().stream().allMatch(value -> HtmlTokens.distinct(value.query()).size()
                <= TokenPackingSignalCodec.MAX_QUERY_TOKENS));
    }

    @Test
    void everyFamilyHasAContrastAndAnAbstentionOrConditionalGold() {
        var gold = HtmlDecisionGold.bundled(CASES, CORPUS);
        for (var family : HtmlDecisionCases.Family.values()) {
            var expected = CASES.cases().stream().filter(value -> value.family() == family)
                    .map(value -> gold.of(value.caseId()).expected()).distinct().toList();
            assertTrue(expected.contains(Expected.DECIDE), family + " " + expected);
            assertTrue(expected.contains(Expected.ABSTAIN) || expected.contains(Expected.CONDITIONAL),
                    family + " " + expected);
        }
        var differing = CASES.pairs().stream().filter(pair -> !DecisionMetrics.goldSignature(gold.of(pair.first()))
                .equals(DecisionMetrics.goldSignature(gold.of(pair.second())))).count();
        assertTrue(differing >= 4, "at least four pairs need different or conditional gold outcomes");
    }

    @Test
    void everyRestrictiveCspCaseStatesThatItsOwnScriptFilesAreAllowed() {
        var csp = CASES.cases().stream()
                .filter(value -> value.family() == HtmlDecisionCases.Family.SCRIPT
                        && value.value(Constraint.RESTRICTIVE_CSP) == HtmlDecisionCases.Tri.YES).toList();
        assertEquals(List.of("case:script-csp-01", "case:script-csp-inline-01"),
                csp.stream().map(HtmlDecisionCases.DecisionCase::caseId).toList());
        // A script-src without 'unsafe-inline' may also block external sources ('none'); the external choice follows
        // only because the case states that the site's own files are allowed.
        csp.forEach(value -> assertTrue(value.requirement().contains("script-src is 'self'"), value.caseId()));
        assertTrue(Constraint.RESTRICTIVE_CSP.question().contains("('self')"));
    }

    @Test
    void theActiveGoldIsTheFrozenAdjudicatedRevisionAndItsCoverageIsPinned() {
        var authored = HtmlDecisionGold.bundled(CASES, CORPUS);
        var adjudication = HtmlDecisionAdjudication.bundled(CASES, authored, CORPUS).orElseThrow();
        var revision = HtmlDecisionGold.bundledRevision(CASES, CORPUS).orElseThrow();
        assertEquals(HtmlDecisionRunner.FROZEN_ADJUDICATION_HASH, adjudication.hash());
        assertEquals(HtmlDecisionRunner.FROZEN_GOLD_REVISION_HASH, revision.hash());
        assertEquals(adjudication.revisedTable(), revision.cells());
        // Under v2 the embed family lost its ABSTAIN and CONDITIONAL cases (#152); restoring them needs new cases.
        var outcomes = new EnumMap<Family, Set<Expected>>(Family.class);
        CASES.cases().forEach(value -> outcomes.computeIfAbsent(value.family(),
                ignored -> EnumSet.noneOf(Expected.class)).add(revision.of(value.caseId()).expected()));
        assertEquals(Map.of(Family.EMBED, Set.of(Expected.DECIDE), Family.LIST, Set.of(Expected.DECIDE,
                Expected.ABSTAIN), Family.SCRIPT, Set.of(Expected.DECIDE, Expected.CONDITIONAL)), outcomes);
        var changing = CASES.pairs().stream().filter(pair -> !DecisionMetrics.goldSignature(revision.of(pair.first()))
                .equals(DecisionMetrics.goldSignature(revision.of(pair.second())))).count();
        assertEquals(9, changing, "pairs whose v2 gold changes");
    }

    @Test
    void theGoldHashIsFrozen() {
        assertEquals(HtmlDecisionRunner.FROZEN_GOLD_HASH, HtmlDecisionGold.bundled(CASES, CORPUS).hash());
    }

    @Test
    void aPairThatDiffersInNoOrTwoConstraintsIsRejected() {
        var none = CASES_TEXT.replace("separateDocument=unknown|integrateWithPage=yes",
                "separateDocument=no|integrateWithPage=yes");
        assertRejected(() -> HtmlDecisionCases.parse("test", none, "test"), "exactly one constraint");
        var two = CASES_TEXT.replace("separateDocument=unknown|integrateWithPage=yes",
                "separateDocument=unknown|integrateWithPage=no");
        assertRejected(() -> HtmlDecisionCases.parse("test", two, "test"), "exactly one constraint");
    }

    @Test
    void aMissingConstraintOrUnknownCandidateIsRejected() {
        assertRejected(() -> HtmlDecisionCases.parse("test", CASES_TEXT.replace("\torderMatters=yes\t",
                "\t-\t"), "test"), "constraints must be exactly");
        assertRejected(() -> HtmlDecisionCases.parse("test", CASES_TEXT.replace("\tol|ul", "\tol|table"), "test"),
                "not a list choice");
        assertRejected(() -> HtmlDecisionCases.parse("test", CASES_TEXT.replace("orderMatters=yes",
                "orderMatters=maybe"), "test"), "yes, no or unknown");
        assertEquals(Constraint.ORDER_MATTERS, CASES.byId("case:list-install-01").family().constraints().getFirst());
    }

    @Test
    void aCorruptedGoldSpanIsRejected() {
        var corrupted = GOLD_TEXT.replace("chk:lists-and-text-semantics#00 35 ",
                "chk:lists-and-text-semantics#00 36 ");
        assertTrue(!corrupted.equals(GOLD_TEXT));
        assertRejected(() -> HtmlDecisionGold.parse(corrupted, "test", CASES, CORPUS), "span digest");
        var fabricated = GOLD_TEXT.replace("chk:lists-and-text-semantics#00 35 ",
                "chk:lists-and-text-semantics#99 35 ");
        assertRejected(() -> HtmlDecisionGold.parse(fabricated, "test", CASES, CORPUS), "not a chunk");
    }

    @Test
    void aMissingOrOrphanOrInconsistentGoldLabelIsRejected() {
        var lines = GOLD_TEXT.lines().toList();
        var withoutNav = String.join("\n", lines.stream().filter(line -> !line.startsWith("case:list-nav-01\t"))
                .toList());
        assertRejected(() -> HtmlDecisionGold.parse(withoutNav, "test", CASES, CORPUS), "without a gold label");
        var orphan = GOLD_TEXT.replace("case:list-nav-01\t", "case:list-orphan-01\t");
        assertRejected(() -> HtmlDecisionGold.parse(orphan, "test", CASES, CORPUS), "unknown case");
        var overlapping = GOLD_TEXT.replace("case:list-nav-01\tDECIDE\tul\tol", "case:list-nav-01\tDECIDE\tul\tul");
        assertRejected(() -> HtmlDecisionGold.parse(overlapping, "test", CASES, CORPUS), "disjoint");
        var nonCandidate = GOLD_TEXT.replace("case:list-nav-01\tDECIDE\tul\tol", "case:list-nav-01\tDECIDE\tdiv\tol");
        assertRejected(() -> HtmlDecisionGold.parse(nonCandidate, "test", CASES, CORPUS), "candidate");
    }

    @Test
    void conditionalOptionsMayRepeatAChoiceButNotAnOptionAndPermissionsHaveOneSpelling() {
        var twoAssumptions = GOLD_TEXT.replace("div@integration-preferred|iframe@isolation-preferred",
                "div@integration-preferred|iframe@isolation-preferred|iframe@third-party-origin");
        assertEquals(3, HtmlDecisionGold.parse(twoAssumptions, "test", CASES, CORPUS).of("case:embed-help-01")
                .options().size());
        var repeated = GOLD_TEXT.replace("div@integration-preferred|iframe@isolation-preferred",
                "div@integration-preferred|iframe@isolation-preferred|div@integration-preferred");
        assertRejected(() -> HtmlDecisionGold.parse(repeated, "test", CASES, CORPUS), "repeated list item");
        var capitalized = GOLD_TEXT.replace("case:list-nav-01\tDECIDE\tul\tol\t-\tnone",
                "case:list-nav-01\tDECIDE\tul\tol\t-\tNone");
        assertRejected(() -> HtmlDecisionGold.parse(capitalized, "test", CASES, CORPUS), "exactly none or requested");
    }

    private static void assertRejected(Executable load, String fragment) {
        var message = assertThrows(CorpusValidationException.class, load).getMessage();
        assertTrue(message.contains(fragment), message);
    }
}
