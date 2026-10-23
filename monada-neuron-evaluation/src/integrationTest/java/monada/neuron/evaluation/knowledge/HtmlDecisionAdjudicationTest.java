package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlDecisionAdjudication.Item;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Expected;
import monada.neuron.evaluation.knowledge.HtmlDecisionRunner.FrozenHashes;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The independent adjudication of the decision gold (Issue #152): strict parsing, independence and revision. */
class HtmlDecisionAdjudicationTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
    private static final HtmlDecisionCases CASES = HtmlDecisionCases.bundled();
    private static final HtmlDecisionGold GOLD = HtmlDecisionGold.bundled(CASES, CORPUS);
    private static final String REVIEWER = "other-vendor-model";
    private static final String NAV = "case:list-nav-01";
    private static final String NAV_CHUNK = "chk:lists-and-text-semantics#00";

    @Test
    void anAllAgreeAdjudicationKeepsTheAuthoredLabels() {
        var adjudication = parse(allAgree());
        assertEquals(CASES.cases().size() * Item.values().length, adjudication.verdicts().size());
        assertEquals(List.of(), adjudication.missing());
        assertEquals(Set.of(), adjudication.dependentReviewers());
        assertEquals(Set.of(REVIEWER), adjudication.reviewers());
        assertEquals(0, adjudication.changes());
        assertEquals(GOLD.cells().size(), adjudication.revisedTable().size());
        for (var i = 0; i < GOLD.cells().size(); i++) {
            assertEquals(GOLD.cells().get(i).subList(0, 8), adjudication.revisedTable().get(i).subList(0, 8));
            assertEquals(REVIEWER, adjudication.revisedTable().get(i).get(8));
            assertEquals(GOLD.cells().get(i).get(9), adjudication.revisedTable().get(i).get(9));
        }
    }

    @Test
    void aChangeIsAppliedRevalidatedAndRecordedInTheChangelog() {
        var text = replace(allAgree(), NAV, Item.RATIONALE, "change\tThe links have no order that matters.\t"
                + "The original wording is fine but too short.");
        var adjudication = parse(text);
        assertEquals(1, adjudication.changes());
        var revision = adjudication.revisionText(CASES);
        assertTrue(revision.contains("# changelog: case:list-nav-01 rationale (" + REVIEWER + "): The original"));
        var revised = HtmlDecisionGold.parse(revision, "revision", HtmlDecisionGold.REVISION_VERSION, CASES, CORPUS);
        assertEquals(HtmlDecisionGold.REVISION_VERSION, revised.version());
        assertEquals("The links have no order that matters.", revised.of(NAV).rationale());
        assertEquals(REVIEWER, revised.of(NAV).draftedBy());
        assertEquals(GOLD.of("case:list-install-01").draftedBy(), revised.of("case:list-install-01").draftedBy());
        assertEquals(adjudication.revisedTable(), revised.cells());
    }

    @Test
    void aChangedSpanIsAnExactQuoteResolvedToOffsets() {
        var chunk = CORPUS.chunk(NAV_CHUNK).orElseThrow().text();
        var current = GOLD.of(NAV).evidence().getFirst();
        var whole = chunk.substring(chunk.offsetByCodePoints(0, current.start()),
                chunk.offsetByCodePoints(0, current.end()));
        assertRejected(() -> parse(replace(allAgree(), NAV, Item.EVIDENCE_SPANS, "change\t" + NAV_CHUNK + " \""
                + whole + "\"\tsame span")), "must differ");
        var shorter = whole.substring(0, whole.length() / 2).strip();
        var adjudication = parse(replace(allAgree(), NAV, Item.EVIDENCE_SPANS, "change\t" + NAV_CHUNK + " \""
                + shorter + "\"\tonly the first half is needed"));
        var span = HtmlDecisionGold.parse(adjudication.revisionText(CASES), "revision",
                HtmlDecisionGold.REVISION_VERSION, CASES, CORPUS).of(NAV).evidence();
        assertEquals(1, span.size());
        assertEquals(current.start(), span.getFirst().start());
        assertEquals(CorpusDigests.sha256(shorter), span.getFirst().sha256());
        assertRejected(() -> parse(replace(allAgree(), NAV, Item.EVIDENCE_SPANS, "change\t" + NAV_CHUNK
                + " \"no such words here\"\tmissing")), "not in");
        assertRejected(() -> parse(replace(allAgree(), NAV, Item.EVIDENCE_SPANS, "change\t" + NAV_CHUNK
                + " 35 100\tno quote")), "exact quote");
        assertRejected(() -> parse(replace(allAgree(), NAV, Item.EVIDENCE_SPANS, "change\t" + NAV_CHUNK
                + " \"e\"\tambiguous")), "more than once");
    }

    @Test
    void aRevisionThatBreaksAGoldInvariantIsRejected() {
        assertRejected(() -> parse(replace(allAgree(), NAV, Item.EXPECTED, "change\tABSTAIN\tunclear")),
                "ABSTAIN has no acceptable");
        assertRejected(() -> parse(replace(allAgree(), NAV, Item.ACCEPTABLE, "change\tol\tswap")), "disjoint");
        var abstain = replace(replace(allAgree(), NAV, Item.EXPECTED, "change\tABSTAIN\tunclear"), NAV,
                Item.ACCEPTABLE, "change\t-\tnone when abstaining");
        assertEquals(Expected.ABSTAIN, HtmlDecisionGold.parse(parse(abstain).revisionText(CASES), "revision",
                HtmlDecisionGold.REVISION_VERSION, CASES, CORPUS).of(NAV).expected());
    }

    @Test
    void malformedRowsAreRejected() {
        var text = allAgree();
        assertRejected(() -> parse(replace(text, NAV, Item.RATIONALE, "maybe\t-\tunsure")), "agree or change");
        assertRejected(() -> parse(replace(text, NAV, Item.RATIONALE, "agree\tnew text\tagree")), "proposes nothing");
        assertRejected(() -> parse(replace(text, NAV, Item.RATIONALE, "change\t-\tno value")), "needs a proposed");
        assertRejected(() -> parse(replace(text, NAV, Item.PERMISSIONS, "change\tnone\tsame")), "must differ");
        assertRejected(() -> parse(text.replace(NAV + "\trationale\t", NAV + "\treasoning\t")), "is not one of");
        assertRejected(() -> parse(text.replace(NAV + "\t", "case:list-orphan-01\t")), "unknown case");
        var line = text.lines().filter(value -> value.startsWith(NAV + "\trationale\t")).findFirst().orElseThrow();
        assertRejected(() -> parse(text + line + "\n"), "repeated verdict");
        assertRejected(() -> parse(text.replace(NAV + "\trationale\tagree\t-\tfits\t" + REVIEWER,
                NAV + "\trationale\tagree\t-\t-\t" + REVIEWER)), "reason is required");
    }

    @Test
    void missingRowsAndDependentReviewersAreReportedNotHidden() {
        var text = allAgree();
        var withoutNav = String.join("\n", text.lines().filter(line -> !line.startsWith(NAV + "\t")).toList());
        var missing = parse(withoutNav).missing();
        assertEquals(Item.values().length, missing.size());
        assertTrue(missing.contains(NAV + " evidenceSpans"));
        var dependent = parse(text.replace("\t" + REVIEWER + "\n", "\t" + HtmlDecisionAdjudication.RULE_AUTHOR
                + "\n"));
        assertEquals(Set.of(HtmlDecisionAdjudication.RULE_AUTHOR), dependent.dependentReviewers());
    }

    @Test
    void theRunnerChecksTheFrozenHashCompletenessIndependenceAndRevision() {
        var adjudication = parse(allAgree());
        var runner = new HtmlDecisionRunner(Options.parse(new String[0]), Optional.empty(),
                new FrozenHashes(HtmlDecisionRunner.FROZEN_GOLD_HASH, "", adjudication.hash()));
        var checks = new ArrayList<Check>();
        runner.adjudicationChecks(Optional.of(adjudication), Optional.empty(), checks);
        assertEquals(List.of("adjudication.hashFrozen", "adjudication.complete", "adjudication.independent",
                "gold.matchesAdjudication"), checks.stream().map(Check::name).toList());
        checks.forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));

        checks.clear();
        runner.adjudicationChecks(Optional.of(adjudication), Optional.of(GOLD), checks);
        assertFalse(checks.getLast().passed(), "a revision without a change");

        var changed = parse(replace(allAgree(), NAV, Item.RATIONALE, "change\tUnordered links.\tshorter"));
        var revision = HtmlDecisionGold.parse(changed.revisionText(CASES), "revision",
                HtmlDecisionGold.REVISION_VERSION, CASES, CORPUS);
        checks.clear();
        runner.adjudicationChecks(Optional.of(changed), Optional.of(revision), checks);
        assertTrue(checks.getLast().passed(), checks.getLast().detail());
        assertFalse(checks.getFirst().passed(), "the changed adjudication is not the frozen one");
        checks.clear();
        runner.adjudicationChecks(Optional.of(changed), Optional.empty(), checks);
        assertFalse(checks.getLast().passed(), "a change without a revision");
        checks.clear();
        runner.adjudicationChecks(Optional.empty(), Optional.of(revision), checks);
        assertEquals(List.of("gold.matchesAdjudication"), checks.stream().map(Check::name).toList());
        assertFalse(checks.getFirst().passed());
    }

    @Test
    void theTemplateAndWorksheetCoverEveryCaseAndItemButNoRule() {
        var template = HtmlDecisionAdjudication.template(CASES);
        assertEquals(CASES.cases().size() * Item.values().length, template.lines()
                .filter(line -> line.startsWith("case:")).count());
        assertTrue(template.contains(HtmlDecisionAdjudication.HEADER + "\n"));
        var worksheet = HtmlDecisionAdjudicationWorksheet.worksheet(CASES, GOLD, CORPUS);
        CASES.cases().forEach(value -> assertTrue(worksheet.contains("### `" + value.caseId() + "`"),
                value.caseId()));
        CORPUS.chunks().forEach(chunk -> assertTrue(worksheet.contains("### `" + chunk.chunkId() + "`"),
                chunk.chunkId()));
        assertTrue(worksheet.contains(HtmlDecisionAdjudication.RULE_AUTHOR));
        DecisionRuleBaseline.RULES.forEach(rule -> assertFalse(worksheet.contains(rule.render()), rule.id()));
        assertFalse(worksheet.contains(DecisionRuleBaseline.VERSION));
    }

    private static HtmlDecisionAdjudication parse(String text) {
        return HtmlDecisionAdjudication.parse(text, "test", CASES, GOLD, CORPUS);
    }

    private static String allAgree() {
        var text = new StringBuilder("# test adjudication\n").append(HtmlDecisionAdjudication.HEADER).append('\n');
        for (var decisionCase : CASES.cases()) {
            for (var item : Item.values()) {
                text.append(String.join("\t", decisionCase.caseId(), item.label(), "agree", "-", "fits", REVIEWER))
                        .append('\n');
            }
        }
        return text.toString();
    }

    /** Replaces the verdict, proposed and reason cells of one row of {@code text}. */
    private static String replace(String text, String caseId, Item item, String verdictProposedReason) {
        var prefix = caseId + "\t" + item.label() + "\t";
        var old = prefix + "agree\t-\tfits\t" + REVIEWER;
        assertTrue(text.contains(old), old);
        return text.replace(old, prefix + verdictProposedReason + "\t" + REVIEWER);
    }

    private static void assertRejected(Executable load, String fragment) {
        var message = assertThrows(CorpusValidationException.class, load).getMessage();
        assertTrue(message.contains(fragment), message);
    }
}
