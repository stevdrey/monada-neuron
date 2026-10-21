package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerMetrics.Edge;
import monada.neuron.evaluation.knowledge.AnswerMetrics.Judged;
import monada.neuron.evaluation.knowledge.AnswerMetrics.ScoredAnswer;
import monada.neuron.evaluation.knowledge.AnswerMetrics.Status;
import monada.neuron.evaluation.knowledge.AnswerTrace.AnswerRow;
import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.AnswerTrace.ClaimRow;
import monada.neuron.evaluation.knowledge.AnswerTrace.Outcome;
import monada.neuron.evaluation.knowledge.AnswerTrace.Snapshot;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.knowledge.JudgedQuery.EvidenceSpan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The L4 scorer over hand-built rows of the bundled query set, with judgments written in each test. */
class AnswerMetricsTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final HtmlKnowledgeQuerySet QUERY_SET = HtmlKnowledgeQuerySet.bundled(CORPUS);

    // q:exact-term-01: required chk:links#00, keyClaim "The a element creates a hyperlink."
    private static final String EXACT = "q:exact-term-01";
    private static final String CLAIM = "The a element creates a hyperlink.";

    @Test
    void abstentionsAreClassifiedByTheRelevantSetAndTheEvidence() {
        assertEquals(Status.CORRECT_ABSTENTION, score(abstained("q:out-of-domain-01", List.of("chk:forms#00")),
                "").status().orElseThrow());
        assertEquals(Status.EVIDENCE_MISS_ABSTENTION, score(abstained(EXACT, List.of("chk:forms#00")), "")
                .status().orElseThrow());
        var wrong = score(abstained(EXACT, List.of("chk:links#00")), "");
        assertEquals(Status.WRONG_ABSTENTION, wrong.status().orElseThrow());
        assertEquals(OptionalDouble.empty(), wrong.metrics().get("ClaimSupport"));
        assertEquals(OptionalDouble.empty(), wrong.metrics().get("CitationFidelity"));
        assertEquals(OptionalDouble.of(0.0), wrong.metrics().get("CitationRecall"));
    }

    @Test
    void anExactQuoteMatchingEveryKeyClaimIsSupportedCorrect() {
        var row = answered(EXACT, List.of("chk:links#00"), claim(CLAIM, span("chk:links#00", 0, 34, true, true)));
        var scored = score(row, judgments(EXACT, row, "claim1.contradicted", "false", "claim1.edge1", "accept"));
        assertEquals(Status.SUPPORTED_CORRECT, scored.status().orElseThrow());
        assertTrue(scored.claims().getFirst().supported().source().startsWith("exact-quote"));
        assertEquals(List.of(List.of(1, 1)), scored.finalMatching());
        assertEquals(OptionalDouble.of(1.0), scored.metrics().get("KeyClaimCoverage"));
        assertEquals(OptionalDouble.of(1.0), scored.metrics().get("ClaimSupport"));
        assertEquals(OptionalDouble.of(1.0), scored.metrics().get("CitationFidelity"));
        assertTrue(scored.edges().getFirst().matcher());
    }

    @Test
    void aReviewerRejectionReplacesTheMatcherAndIsCountedAsAnOverride() {
        var row = answered(EXACT, List.of("chk:links#00"), claim(CLAIM, span("chk:links#00", 0, 34, true, true)));
        var scored = score(row, judgments(EXACT, row, "claim1.contradicted", "false", "claim1.edge1", "reject"));
        assertEquals(Status.SUPPORTED_OFF_TARGET, scored.status().orElseThrow());
        assertEquals(List.of(List.of(1, 1)), scored.matcherMatching());
        assertEquals(List.of(), scored.finalMatching());
        assertEquals(OptionalDouble.of(1.0), scored.metrics().get("KeyClaimCoverage(matcher)"));
        assertEquals(OptionalDouble.of(0.0), scored.metrics().get("KeyClaimCoverage"));
        assertTrue(scored.edges().getFirst().overridden());
        var all = AnswerMetrics.score(QUERY_SET, new Snapshot(QUERY_SET.queryHash(), List.of(row)),
                judgments(EXACT, row, "claim1.contradicted", "false", "claim1.edge1", "reject"));
        assertEquals(1, all.aggregates().getFirst().overrides());
    }

    @Test
    void contradictionPrecedesUnsupportedAndAClaimWithoutAnEligibleCitationIsUnsupported() {
        var contradicted = answered(EXACT, List.of("chk:links#00"),
                claim(CLAIM, span("chk:links#00", 0, 34, true, true)));
        assertEquals(Status.CONTRADICTED, score(contradicted, judgments(EXACT, contradicted, "claim1.contradicted",
                "true", "claim1.edge1", "accept")).status().orElseThrow());

        var ineligible = answered(EXACT, List.of("chk:links#00"),
                claim(CLAIM, span("chk:forms#00", 0, 34, false, false)));
        var scored = score(ineligible, judgments(EXACT, ineligible, "claim1.contradicted", "false"));
        assertEquals(Status.UNSUPPORTED, scored.status().orElseThrow());
        assertEquals("rule: no eligible citation", scored.claims().getFirst().supported().source());
        assertEquals(List.of(), scored.edges(), "an unsupported claim has no candidate edge");
        assertEquals(OptionalDouble.of(0.0), scored.metrics().get("CitationFidelity"));
        assertEquals(OptionalDouble.of(0.0), scored.metrics().get("ClaimSupport"));
    }

    @Test
    void aNonExactEligibleClaimNeedsAHumanSupportVerdict() {
        var row = answered(EXACT, List.of("chk:links#00"), claim("Links are made with a.",
                span("chk:links#00", 0, 34, true, false)));
        var unjudged = score(row, judgments(EXACT, row, "claim1.contradicted", "false"));
        assertEquals(Optional.empty(), unjudged.status());
        assertTrue(unjudged.missing().contains("claim1.supported"), unjudged.missing().toString());
        var judged = score(row, judgments(EXACT, row, "claim1.supported", "true", "claim1.contradicted", "false",
                "claim1.edge1", "reject"));
        assertEquals(Status.SUPPORTED_OFF_TARGET, judged.status().orElseThrow());
    }

    @Test
    void falsePremiseAnswersAreAcceptedCorrectedOrUnaddressed() {
        // q:false-premise-01: required chk:lists-and-text-semantics#03; keyClaim 1 is the refutation.
        var id = "q:false-premise-01";
        var row = answered(id, List.of("chk:lists-and-text-semantics#03"),
                claim("The b element does not add importance.", span("chk:lists-and-text-semantics#03", 0, 10,
                        true, true)));
        assertEquals(Status.FALSE_PREMISE_ACCEPTED, score(row, judgments(id, row, "claim1.contradicted", "false",
                "claim1.edge1", "accept", "claim1.edge2", "reject", "premiseAccepted", "true")).status()
                .orElseThrow());
        var corrected = score(row, judgments(id, row, "claim1.contradicted", "false", "claim1.edge1", "accept",
                "claim1.edge2", "reject", "premiseAccepted", "false"));
        assertEquals(Status.FALSE_PREMISE_CORRECTED, corrected.status().orElseThrow());
        assertTrue(corrected.flags().get("premiseCorrected"));
        assertEquals(Status.FALSE_PREMISE_UNADDRESSED, score(row, judgments(id, row, "claim1.contradicted", "false",
                "claim1.edge1", "reject", "claim1.edge2", "accept", "premiseAccepted", "false")).status()
                .orElseThrow());
    }

    @Test
    void anAnsweredUnanswerableQueryIsNeverCredited() {
        var id = "q:no-answer-01";
        var row = answered(id, List.of("chk:forms#00"), claim("A form collects input.",
                span("chk:forms#00", 0, 22, true, true)));
        var scored = score(row, judgments(id, row, "claim1.contradicted", "false"));
        assertEquals(Status.ANSWERED_UNANSWERABLE, scored.status().orElseThrow());
        assertEquals(OptionalDouble.empty(), scored.metrics().get("KeyClaimCoverage"));
        assertEquals(OptionalDouble.empty(), scored.metrics().get("CitationRecall"), "no gold spans");
    }

    @Test
    void aConflictAnswerCitingOneChunkIsHiddenByRuleOrMissingEvidence() {
        var id = "q:conflicting-source-01";
        var one = claim("A caption replaces alt.", span("chk:legacy-notes#00", 0, 10, true, true));
        var complete = answered(id, List.of("chk:legacy-notes#00", "chk:images-and-media#01"), one);
        var hidden = score(complete, judgments(id, complete, "claim1.contradicted", "false", "claim1.edge1",
                "reject"));
        assertEquals(Status.CONFLICT_HIDDEN, hidden.status().orElseThrow());
        assertFalse(hidden.flags().get("conflictDisclosed"));
        assertFalse(hidden.missing().contains("conflictDisclosed"), "one cited chunk cannot disclose the conflict");
        var partial = answered(id, List.of("chk:legacy-notes#00"), one);
        assertEquals(Status.CONFLICT_EVIDENCE_MISSING, score(partial, judgments(id, partial, "claim1.contradicted",
                "false", "claim1.edge1", "reject")).status().orElseThrow());
    }

    @Test
    void missingAndStaleJudgmentsFailTheirChecks() {
        var row = answered(EXACT, List.of("chk:links#00"), claim(CLAIM, span("chk:links#00", 0, 34, true, true)));
        var stale = AnswerJudgments.parse(AnswerJudgments.HEADER + "\n" + EXACT + "\tother answer\t"
                + "claim1.contradicted\tfalse\treason\tstevdrey\tclaude\n", "test");
        var scores = AnswerMetrics.score(QUERY_SET, new Snapshot(QUERY_SET.queryHash(), List.of(row)), stale);
        var checks = scores.checks().stream().collect(Collectors.toMap(Check::name, Check::passed));
        assertFalse(checks.get("judgment.complete"));
        assertFalse(checks.get("judgment.noStaleEntries"));
        assertEquals(2, scores.missing().size(), "contradicted and edge1");
        assertEquals("accept", scores.missing().stream().filter(missing -> missing.key().item().equals("claim1.edge1"))
                .findFirst().orElseThrow().proposal());
        assertEquals(1, scores.aggregates().getFirst().unjudged());
        assertTrue(AnswerJudgments.template(scores.missing()).contains("claim1.edge1"));
    }

    @Test
    void aTestDoubleRowIsNeverJudgedOrGivenAStatus() {
        var row = new AnswerRow("full", "B1", 5, EXACT, "generator-test-double", AnswerComponent.Kind.TEST_DOUBLE,
                hits(List.of("chk:links#00")), Optional.of(false), Outcome.ANSWERED, "", List.of(claim(CLAIM,
                span("chk:links#00", 0, 34, true, true))));
        var scores = AnswerMetrics.score(QUERY_SET, new Snapshot(QUERY_SET.queryHash(), List.of(row)),
                AnswerJudgments.none());
        var scored = scores.answers().getFirst();
        assertEquals(Optional.empty(), scored.status());
        assertFalse(scored.judged());
        assertTrue(scores.missing().isEmpty());
        assertEquals(OptionalDouble.of(1.0), scored.metrics().get("CitationFidelity"));
        assertEquals(OptionalDouble.empty(), scored.metrics().get("CitationPrecision"),
                "a citation that overlaps the gold span still earns no gold-relative score");
        assertEquals(OptionalDouble.empty(), scored.metrics().get("CitationRecall"));
        var aggregate = scores.aggregates().getFirst().metrics();
        assertEquals(OptionalDouble.empty(), aggregate.get("CitationPrecision").value());
        assertEquals(OptionalDouble.empty(), aggregate.get("CitationRecall").value());
        assertEquals(OptionalDouble.of(1.0), aggregate.get("CitationFidelity").value());
    }

    @Test
    void theMatchingIsMaximumThenLexicographicallySmallest() {
        var all = List.of(edge(1, 1, true), edge(1, 2, true), edge(2, 1, true), edge(2, 2, true));
        assertEquals(List.of(List.of(1, 1), List.of(2, 2)), AnswerMetrics.matching(2, 2, all, false));
        var forced = List.of(edge(1, 1, true), edge(1, 2, true), edge(2, 1, true));
        assertEquals(List.of(List.of(1, 2), List.of(2, 1)), AnswerMetrics.matching(2, 2, forced, false),
                "maximum size first, even though (1, 1) is smaller");
        var oneClaim = List.of(edge(1, 1, true), edge(2, 1, true));
        assertEquals(List.of(List.of(1, 1)), AnswerMetrics.matching(2, 1, oneClaim, false),
                "one claim never matches two keyClaims");
        assertEquals(List.of(), AnswerMetrics.matching(2, 2, List.of(edge(1, 1, false)), false));
    }

    @Test
    void overlappingCitationsFormOneGroupAndEarnAtMostOneSpan() {
        var nested = List.of(span("chk:a#00", 0, 20, true, false), span("chk:a#00", 5, 10, true, false),
                span("chk:a#00", 18, 30, true, false), span("chk:a#00", 40, 50, true, false),
                span("chk:b#00", 0, 20, true, false));
        var groups = AnswerMetrics.groups(nested);
        assertEquals(3, groups.size());
        var gold = List.of(new EvidenceSpan("chk:a#00", 0, 5, "x"), new EvidenceSpan("chk:a#00", 25, 28, "x"),
                new EvidenceSpan("chk:a#00", 45, 46, "x"));
        assertEquals(2, AnswerMetrics.citationMatching(groups, gold), "the first group overlaps two spans, once");
        var duplicate = List.of(span("chk:a#00", 0, 5, true, false), span("chk:a#00", 0, 5, true, false));
        assertEquals(1, AnswerMetrics.citationMatching(AnswerMetrics.groups(duplicate), gold));
    }

    @Test
    void theSnapshotRoundTripsMultiClaimRowsAndRejectsTruncation() {
        var rows = List.of(
                answered(EXACT, List.of("chk:links#00"), claim("tab\there\nnewline \\ back",
                        span("chk:links#00", 0, 4, true, false), span("chk:forms#00", 1, 2, false, false)),
                        claim("second", span("chk:links#00", 5, 11, true, true))),
                abstained(EXACT, List.of()),
                new AnswerRow("full", "B2", 1, EXACT, "B3", AnswerComponent.Kind.EXTRACTIVE_BASELINE, List.of(),
                        Optional.empty(), Outcome.RECALL_FAILED, "B2 threw x", List.of()),
                new AnswerRow("full", "B2", 3, EXACT, "B3", AnswerComponent.Kind.EXTRACTIVE_BASELINE,
                        List.of(new AnswerTrace.Hit("chk:links#00", 0x1.8p-1), new AnswerTrace.Hit("chk:forms#00", -0.0)),
                        Optional.of(true), Outcome.ABSTAINED, "INSUFFICIENT_EVIDENCE: x", List.of()));
        var snapshot = new Snapshot(QUERY_SET.queryHash(), rows);
        var tsv = snapshot.toTsv();
        assertEquals(snapshot, Snapshot.parseTsv(tsv));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.parseTsv(tsv.substring(0, tsv.length() / 2)));
        assertThrows(IllegalArgumentException.class, () -> new AnswerRow("full", "B1", 5, EXACT, "B3",
                AnswerComponent.Kind.EXTRACTIVE_BASELINE, List.of(), Optional.of(false), Outcome.ANSWERED, "",
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnswerRow("full", "B1", 5, EXACT, "B3",
                AnswerComponent.Kind.EXTRACTIVE_BASELINE, List.of(), Optional.empty(), Outcome.ABSTAINED, "x",
                List.of()), "a completed recall always records tieAtCutoff");
    }

    @Test
    void judgmentsAreParsedStrictly() {
        var header = AnswerJudgments.HEADER + "\n";
        assertThrows(CorpusValidationException.class, () -> AnswerJudgments.parse(header
                + "q\ta\tclaim1.edge1\ttrue\treason\tstevdrey\tclaude\n", "test"), "edges take accept/reject");
        assertThrows(CorpusValidationException.class, () -> AnswerJudgments.parse(header
                + "q\ta\tclaim1.color\ttrue\treason\tstevdrey\tclaude\n", "test"), "unknown item");
        assertThrows(CorpusValidationException.class, () -> AnswerJudgments.parse(header
                + "q\ta\tpremiseAccepted\ttrue\t\tstevdrey\tclaude\n", "test"), "reason required");
        var row = "q\ta\tpremiseAccepted\ttrue\treason\tstevdrey\tclaude\n";
        assertThrows(CorpusValidationException.class, () -> AnswerJudgments.parse(header + row + row, "test"));
        assertThrows(CorpusValidationException.class, () -> AnswerJudgments.parse(row, "test"), "header required");
        var parsed = AnswerJudgments.parse("# comment\n" + header + row, "test");
        assertEquals(1, parsed.keys().size());
        assertEquals(64, parsed.hash().length());
        AnswerJudgments.bundled();
    }

    private static ScoredAnswer score(AnswerRow row, String judgments) {
        var parsed = judgments.isEmpty() ? AnswerJudgments.none() : AnswerJudgments.parse(judgments, "test");
        return AnswerMetrics.score(QUERY_SET, new Snapshot(QUERY_SET.queryHash(), List.of(row)), parsed).answers()
                .getFirst();
    }

    private static ScoredAnswer score(AnswerRow row, AnswerJudgments judgments) {
        return AnswerMetrics.score(QUERY_SET, new Snapshot(QUERY_SET.queryHash(), List.of(row)), judgments).answers()
                .getFirst();
    }

    private static AnswerJudgments judgments(String queryId, AnswerRow row, String... itemsAndVerdicts) {
        var tsv = new StringBuilder(AnswerJudgments.HEADER).append('\n');
        var answer = QueryText.escape(AnswerJudgments.canonical(row.claims()));
        for (var i = 0; i < itemsAndVerdicts.length; i += 2) {
            tsv.append(String.join("\t", queryId, answer, itemsAndVerdicts[i], itemsAndVerdicts[i + 1], "test reason",
                    "stevdrey", "claude")).append('\n');
        }
        return AnswerJudgments.parse(tsv.toString(), "test");
    }

    private static AnswerRow answered(String queryId, List<String> evidence, ClaimRow... claims) {
        return new AnswerRow("full", "B1", 5, queryId, "B3", AnswerComponent.Kind.EXTRACTIVE_BASELINE, hits(evidence),
                Optional.of(false), Outcome.ANSWERED, "", List.of(claims));
    }

    private static AnswerRow abstained(String queryId, List<String> evidence) {
        return new AnswerRow("full", "B1", 5, queryId, "B3", AnswerComponent.Kind.EXTRACTIVE_BASELINE, hits(evidence),
                Optional.of(false), Outcome.ABSTAINED, "INSUFFICIENT_EVIDENCE: test", List.of());
    }

    private static List<AnswerTrace.Hit> hits(List<String> chunkIds) {
        return chunkIds.stream().map(chunkId -> new AnswerTrace.Hit(chunkId, 1.0)).toList();
    }

    private static ClaimRow claim(String text, CitedSpan... spans) {
        return new ClaimRow(text, new ArrayList<>(List.of(spans)));
    }

    private static CitedSpan span(String chunkId, int start, int end, boolean eligible, boolean exact) {
        return new CitedSpan(new Citation(chunkId, start, end), eligible, exact);
    }

    private static Edge edge(int keyClaim, int claim, boolean accepted) {
        return new Edge(keyClaim, claim, accepted, new Judged(accepted, "test"));
    }
}
