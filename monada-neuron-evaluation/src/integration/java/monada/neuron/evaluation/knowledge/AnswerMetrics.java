package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerJudgments.Key;
import monada.neuron.evaluation.knowledge.AnswerJudgments.Missing;
import monada.neuron.evaluation.knowledge.AnswerTrace.AnswerRow;
import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.AnswerTrace.Outcome;
import monada.neuron.evaluation.knowledge.AnswerTrace.Snapshot;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.JudgedQuery.Answerability;
import monada.neuron.evaluation.knowledge.JudgedQuery.EvidenceSpan;
import monada.neuron.evaluation.knowledge.RecallMetrics.Aggregate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The L4 scorer of Issue #109 ({@code l4-answer-v1}): the only reader of gold labels and of the human judgments. It
 * scores every answer row of a judged component with the protocol's primary status (the first row of the status table
 * whose condition holds), its flags, the key-claim matching (matcher-only and final) and the citation and claim
 * metrics, and aggregates them per root, layer, {@code answerK} and component.
 *
 * <p>#109 refinements, documented in {@code docs/benchmarks/html-knowledge-answers.md}:
 * <ul>
 *   <li>a claim with an eligible citation that quotes it exactly is {@code supported} mechanically (source
 *       {@code exact-quote}); any other claim with an eligible citation needs a human {@code supported} verdict;</li>
 *   <li>the refutation {@code keyClaim} of a {@code FALSE_PREMISE} query is its first {@code keyClaim};</li>
 *   <li>{@code conflictDisclosed} is {@code false} by rule when the eligible citations do not cover both chunks of the
 *       pair, and a human verdict otherwise;</li>
 *   <li>{@link AnswerComponent.Kind#TEST_DOUBLE} rows are never judged and get no status: only citation fidelity.</li>
 * </ul>
 * A judged row with a missing verdict gets no status and is counted as unjudged; {@code judgment.complete} fails.
 */
final class AnswerMetrics {

    /** The primary answer status, in the protocol's order. */
    enum Status {
        CORRECT_ABSTENTION,
        EVIDENCE_MISS_ABSTENTION,
        WRONG_ABSTENTION,
        FALSE_PREMISE_ACCEPTED,
        CONTRADICTED,
        UNSUPPORTED,
        FALSE_PREMISE_CORRECTED,
        FALSE_PREMISE_UNADDRESSED,
        ANSWERED_UNANSWERABLE,
        CONFLICT_EVIDENCE_MISSING,
        CONFLICT_HIDDEN,
        CONFLICT_SURFACED,
        SUPPORTED_CORRECT,
        SUPPORTED_PARTIAL,
        SUPPORTED_OFF_TARGET
    }

    /** The per-query metrics, in report order. */
    static final List<String> METRICS = List.of("CitationFidelity", "CitationPrecision", "CitationRecall",
            "ClaimSupport", "KeyClaimCoverage", "KeyClaimCoverage(matcher)");

    private static final int MAX_MATCHING_SIDE = 16;

    private AnswerMetrics() {
    }

    /**
     * One judged value with where it came from.
     *
     * @param value the value
     * @param source {@code exact-quote}, {@code rule: ...}, or {@code reviewer <id> (drafted by ...): <reason>}
     */
    record Judged(boolean value, String source) {
    }

    /**
     * One claim with its verdicts.
     *
     * @param index 1-based claim index
     * @param text the claim
     * @param citations its citations with the phase's checks
     * @param supported the support verdict
     * @param contradicted the contradiction verdict
     */
    record ClaimVerdict(int index, String text, List<CitedSpan> citations, Judged supported, Judged contradicted) {
    }

    /**
     * One candidate key-claim edge.
     *
     * @param keyClaim 1-based {@code keyClaim} index
     * @param claim 1-based claim index
     * @param matcher whether every B0 token of the {@code keyClaim} occurs in the claim
     * @param reviewer the reviewer's verdict, which replaces the matcher's
     */
    record Edge(int keyClaim, int claim, boolean matcher, Judged reviewer) {

        boolean overridden() {
            return reviewer.value() != matcher;
        }
    }

    /**
     * A scored row.
     *
     * @param row the raw answer row
     * @param query the judged query
     * @param retrievalComplete whether {@code required(q) ⊆ E(q)}
     * @param status the primary status; empty for a test double, a failed recall or an unjudged answer
     * @param flags the protocol flags, always reported for judged answers
     * @param claims every claim with its verdicts
     * @param edges every candidate edge with the matcher's and the reviewer's verdict
     * @param matcherMatching the matcher-only matching as {@code (keyClaim, claim)} pairs
     * @param finalMatching the final matching
     * @param metrics the per-query metrics; absent values are undefined
     * @param mergedCitations eligible citations merged into a group with another
     * @param missing items still to be judged
     */
    record ScoredAnswer(AnswerRow row, JudgedQuery query, boolean retrievalComplete, Optional<Status> status,
            Map<String, Boolean> flags, List<ClaimVerdict> claims, List<Edge> edges, List<List<Integer>> matcherMatching,
            List<List<Integer>> finalMatching, Map<String, OptionalDouble> metrics, int mergedCitations,
            List<String> missing) {

        /** Whether this row is judged for answer quality: a completed row of an extractive baseline. */
        boolean judged() {
            return row.kind() == AnswerComponent.Kind.EXTRACTIVE_BASELINE && row.outcome() != Outcome.RECALL_FAILED;
        }
    }

    /**
     * The aggregate of one root, layer, {@code answerK} and component.
     *
     * @param root the root
     * @param layer the evidence layer
     * @param answerK the evidence bound
     * @param component the component
     * @param kind its kind
     * @param scored rows with a status
     * @param unscored rows whose recall failed
     * @param unjudged judged rows with a missing verdict
     * @param statuses count per status
     * @param metrics macro mean of each per-query metric over the rows it is defined for
     * @param abstentionPrecision abstentions with {@code Rel(q) = ∅} over abstentions
     * @param abstentionRecall abstentions with {@code Rel(q) = ∅} over scored queries with {@code Rel(q) = ∅}
     * @param retrievalComplete scored answerable queries whose {@code E(q)} holds every required chunk
     * @param answerable scored queries with {@code Rel(q) ≠ ∅}
     * @param overrides reviewer verdicts on edges that differ from the matcher
     */
    record GroupAggregate(String root, String layer, int answerK, String component, AnswerComponent.Kind kind,
            int scored, int unscored, int unjudged, Map<Status, Integer> statuses, Map<String, Aggregate> metrics,
            OptionalDouble abstentionPrecision, OptionalDouble abstentionRecall, int retrievalComplete,
            int answerable, int overrides) {
    }

    /**
     * Everything the scorer produced.
     *
     * @param answers every scored row, in snapshot order
     * @param aggregates one per root, layer, {@code answerK} and component
     * @param checks the judgment checks
     * @param missing every item still to be judged, without duplicates
     * @param judgmentsHash the hash of the judgments used
     * @param staleJudgments judgments that no answer of this run uses
     */
    record Scores(List<ScoredAnswer> answers, List<GroupAggregate> aggregates, List<Check> checks,
            List<Missing> missing, String judgmentsHash, List<String> staleJudgments) {
    }

    /** Scores {@code snapshot} against the gold labels of {@code querySet} and {@code judgments}. */
    static Scores score(HtmlKnowledgeQuerySet querySet, Snapshot snapshot, AnswerJudgments judgments) {
        Objects.requireNonNull(querySet, "querySet must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(judgments, "judgments must not be null");
        if (!snapshot.queryHash().equals(querySet.queryHash())) {
            throw new IllegalArgumentException("snapshot was recorded for queryHash " + snapshot.queryHash()
                    + ", not " + querySet.queryHash());
        }
        var byId = querySet.queries().stream().collect(Collectors.toMap(JudgedQuery::queryId, Function.identity()));
        var used = new HashSet<Key>();
        var missing = new LinkedHashMap<Key, Missing>();
        var answers = new ArrayList<ScoredAnswer>();
        for (var row : snapshot.rows()) {
            var query = byId.get(row.queryId());
            if (query == null) {
                throw new IllegalArgumentException("snapshot row for unknown query " + row.queryId());
            }
            answers.add(scoreRow(query, row, judgments, used, missing));
        }
        var stale = judgments.keys().stream().filter(key -> !used.contains(key))
                .map(key -> key.queryId() + " " + key.item()).sorted().toList();
        var checks = new ArrayList<Check>();
        checks.add(new Check("judgment.complete", missing.isEmpty(), missing.isEmpty()
                ? "every claim, flag and candidate key-claim edge of every judged answer has a recorded verdict ("
                        + judgments.origin() + ")"
                : missing.size() + " items are not judged yet; see the judgment template"));
        checks.add(new Check("judgment.noStaleEntries", stale.isEmpty(), stale.isEmpty()
                ? "every recorded judgment belongs to an answer of this run"
                : stale.size() + " judgments match no answer of this run: "
                        + String.join("; ", stale.subList(0, Math.min(5, stale.size())))));
        return new Scores(answers, aggregate(answers), checks, List.copyOf(missing.values()), judgments.hash(), stale);
    }

    private static ScoredAnswer scoreRow(JudgedQuery query, AnswerRow row, AnswerJudgments judgments, Set<Key> used,
            Map<Key, Missing> missing) {
        var evidence = Set.copyOf(row.evidence());
        var retrievalComplete = evidence.containsAll(query.required());
        var citations = row.citations();
        var metrics = new LinkedHashMap<String, OptionalDouble>();
        var eligible = citations.stream().filter(CitedSpan::eligible).toList();
        metrics.put("CitationFidelity", RecallMetrics.ratio(eligible.size(), citations.size()));
        var groups = groups(eligible);
        var matched = citationMatching(groups, query.evidenceSpans());
        metrics.put("CitationPrecision", RecallMetrics.ratio(matched, citations.size()));
        metrics.put("CitationRecall", RecallMetrics.ratio(matched, query.evidenceSpans().size()));
        var merged = eligible.size() - groups.size();
        if (row.kind() != AnswerComponent.Kind.EXTRACTIVE_BASELINE || row.outcome() == Outcome.RECALL_FAILED) {
            metrics.put("ClaimSupport", OptionalDouble.empty());
            metrics.put("KeyClaimCoverage", OptionalDouble.empty());
            metrics.put("KeyClaimCoverage(matcher)", OptionalDouble.empty());
            return new ScoredAnswer(row, query, retrievalComplete, Optional.empty(), Map.of(), List.of(), List.of(),
                    List.of(), List.of(), metrics, merged, List.of());
        }
        if (row.outcome() == Outcome.ABSTAINED) {
            metrics.put("ClaimSupport", OptionalDouble.empty());
            metrics.put("KeyClaimCoverage", OptionalDouble.empty());
            metrics.put("KeyClaimCoverage(matcher)", OptionalDouble.empty());
            Status status;
            if (query.relevant().isEmpty()) {
                status = Status.CORRECT_ABSTENTION;
            } else if (!retrievalComplete) {
                status = Status.EVIDENCE_MISS_ABSTENTION;
            } else {
                status = Status.WRONG_ABSTENTION;
            }
            return new ScoredAnswer(row, query, retrievalComplete, Optional.of(status), flags(query, false, false,
                    false, false, false), List.of(), List.of(), List.of(), List.of(), metrics, merged, List.of());
        }

        var answer = AnswerJudgments.canonical(row.claims());
        var lookup = new Lookup(query, answer, judgments, used, missing, row);
        var claims = new ArrayList<ClaimVerdict>();
        for (var index = 1; index <= row.claims().size(); index++) {
            var claim = row.claims().get(index - 1);
            var hasEligible = claim.citations().stream().anyMatch(CitedSpan::eligible);
            Judged supported;
            if (!hasEligible) {
                supported = new Judged(false, "rule: no eligible citation");
            } else if (claim.citations().stream().anyMatch(CitedSpan::exactQuote)) {
                supported = new Judged(true, "exact-quote: the claim is the verbatim text of an eligible citation");
            } else {
                supported = lookup.get("claim" + index + ".supported", "", claim.text());
            }
            var contradicted = lookup.get("claim" + index + ".contradicted", "", claim.text());
            claims.add(new ClaimVerdict(index, claim.text(), claim.citations(), supported, contradicted));
        }
        var edges = new ArrayList<Edge>();
        for (var claim : claims) {
            if (!claim.supported().value()) {
                continue;
            }
            var claimTokens = HtmlTokens.distinct(claim.text());
            for (var keyIndex = 1; keyIndex <= query.keyClaims().size(); keyIndex++) {
                var keyClaim = query.keyClaims().get(keyIndex - 1);
                var matcher = claimTokens.containsAll(HtmlTokens.distinct(keyClaim));
                var reviewer = lookup.get("claim" + claim.index() + ".edge" + keyIndex, matcher ? "accept" : "reject",
                        "keyClaim " + keyIndex + ": " + keyClaim + "\nclaim: " + claim.text());
                edges.add(new Edge(keyIndex, claim.index(), matcher, reviewer));
            }
        }
        var answerability = query.answerability();
        var premiseAccepted = answerability == Answerability.FALSE_PREMISE
                && lookup.get("premiseAccepted", "", "query: " + query.text()).value();
        var conflictDisclosed = false;
        if (answerability == Answerability.CONFLICTING) {
            var cited = row.citations().stream().filter(CitedSpan::eligible).map(span -> span.citation().chunkId())
                    .collect(Collectors.toSet());
            conflictDisclosed = cited.containsAll(query.required())
                    && lookup.get("conflictDisclosed", "", "query: " + query.text()).value();
        }
        var keyClaims = query.keyClaims().size();
        var matcherMatching = matching(keyClaims, claims.size(), edges, true);
        var finalMatching = matching(keyClaims, claims.size(), edges, false);
        var premiseCorrected = answerability == Answerability.FALSE_PREMISE
                && finalMatching.stream().anyMatch(edge -> edge.getFirst() == 1);
        var anyUnsupported = claims.stream().anyMatch(claim -> !claim.supported().value());
        var anyContradicted = claims.stream().anyMatch(claim -> claim.contradicted().value());
        metrics.put("ClaimSupport", RecallMetrics.ratio((int) claims.stream()
                .filter(claim -> claim.supported().value()).count(), claims.size()));
        metrics.put("KeyClaimCoverage", RecallMetrics.ratio(finalMatching.size(), keyClaims));
        metrics.put("KeyClaimCoverage(matcher)", RecallMetrics.ratio(matcherMatching.size(), keyClaims));
        var flags = flags(query, anyUnsupported, anyContradicted, premiseAccepted, premiseCorrected,
                conflictDisclosed);
        Optional<Status> status = lookup.missingItems.isEmpty()
                ? Optional.of(status(query, retrievalComplete, flags, finalMatching.size()))
                : Optional.empty();
        return new ScoredAnswer(row, query, retrievalComplete, status, flags, claims, edges, matcherMatching,
                finalMatching, metrics, merged, lookup.missingItems);
    }

    /** The primary status of an answered (not abstained) row: the first row of the table whose condition holds. */
    static Status status(JudgedQuery query, boolean retrievalComplete, Map<String, Boolean> flags, int matched) {
        var answerability = query.answerability();
        if (flags.getOrDefault("premiseAccepted", false)) {
            return Status.FALSE_PREMISE_ACCEPTED;
        }
        if (flags.get("anyContradictedClaim")) {
            return Status.CONTRADICTED;
        }
        if (flags.get("anyUnsupportedClaim")) {
            return Status.UNSUPPORTED;
        }
        if (answerability == Answerability.FALSE_PREMISE) {
            return flags.get("premiseCorrected") ? Status.FALSE_PREMISE_CORRECTED : Status.FALSE_PREMISE_UNADDRESSED;
        }
        if (answerability == Answerability.NO_ANSWER_IN_CORPUS || answerability == Answerability.OUT_OF_DOMAIN
                || answerability == Answerability.BLANK) {
            return Status.ANSWERED_UNANSWERABLE;
        }
        if (answerability == Answerability.CONFLICTING) {
            if (!retrievalComplete) {
                return Status.CONFLICT_EVIDENCE_MISSING;
            }
            return flags.get("conflictDisclosed") ? Status.CONFLICT_SURFACED : Status.CONFLICT_HIDDEN;
        }
        var keyClaims = query.keyClaims().size();
        if (keyClaims > 0 && matched == keyClaims) {
            return Status.SUPPORTED_CORRECT;
        }
        return matched > 0 ? Status.SUPPORTED_PARTIAL : Status.SUPPORTED_OFF_TARGET;
    }

    private static Map<String, Boolean> flags(JudgedQuery query, boolean anyUnsupported, boolean anyContradicted,
            boolean premiseAccepted, boolean premiseCorrected, boolean conflictDisclosed) {
        var flags = new LinkedHashMap<String, Boolean>();
        flags.put("anyUnsupportedClaim", anyUnsupported);
        flags.put("anyContradictedClaim", anyContradicted);
        if (query.answerability() == Answerability.FALSE_PREMISE) {
            flags.put("premiseAccepted", premiseAccepted);
            flags.put("premiseCorrected", premiseCorrected);
        }
        if (query.answerability() == Answerability.CONFLICTING) {
            flags.put("conflictDisclosed", conflictDisclosed);
        }
        return flags;
    }

    /** Looks up verdicts of one answer and records what is used and what is missing. */
    private static final class Lookup {

        private final JudgedQuery query;
        private final String answer;
        private final AnswerJudgments judgments;
        private final Set<Key> used;
        private final Map<Key, Missing> missing;
        private final AnswerRow row;
        private final List<String> missingItems = new ArrayList<>();

        Lookup(JudgedQuery query, String answer, AnswerJudgments judgments, Set<Key> used, Map<Key, Missing> missing,
                AnswerRow row) {
            this.query = query;
            this.answer = answer;
            this.judgments = judgments;
            this.used = used;
            this.missing = missing;
            this.row = row;
        }

        /** Returns the recorded verdict of {@code item}, or a provisional {@code false} that marks it missing. */
        Judged get(String item, String proposal, String context) {
            var key = new Key(query.queryId(), answer, item);
            var verdict = judgments.verdict(key);
            if (verdict.isPresent()) {
                used.add(key);
                var value = verdict.get();
                return new Judged(value.asBoolean(), "reviewer " + value.reviewer() + " (drafted by "
                        + value.draftedBy() + "): " + value.reason());
            }
            missingItems.add(item);
            missing.putIfAbsent(key, new Missing(key, proposal, "query " + query.queryId() + " (" + query.answerability()
                    + "): " + query.text() + "\nfirst seen: " + row.component() + " " + row.root() + "/" + row.layer()
                    + "/k" + row.answerK() + "\nanswer: " + answer + "\n" + context));
            return new Judged(false, "not judged");
        }
    }

    /**
     * The maximum one-to-one matching between {@code keyClaims} and claims over the accepted edges (the matcher's when
     * {@code matcherOnly}, the reviewer's otherwise) that, among matchings of that size, has the lexicographically
     * smallest edge list sorted by ({@code keyClaim}, claim). Returns {@code [keyClaim, claim]} pairs, 1-based.
     */
    static List<List<Integer>> matching(int keyClaims, int claims, List<Edge> edges, boolean matcherOnly) {
        if (keyClaims > MAX_MATCHING_SIDE || claims > MAX_MATCHING_SIDE) {
            throw new IllegalArgumentException("matching side exceeds " + MAX_MATCHING_SIDE);
        }
        var accepted = new boolean[keyClaims + 1][claims + 1];
        for (var edge : edges) {
            accepted[edge.keyClaim()][edge.claim()] = matcherOnly ? edge.matcher() : edge.reviewer().value();
        }
        var size = maximum(accepted, 1, new boolean[claims + 1]);
        var result = new ArrayList<List<Integer>>();
        var usedClaims = new boolean[claims + 1];
        var fromKey = 1;
        for (var remaining = size; remaining > 0; remaining--) {
            var chosen = false;
            for (var key = fromKey; key <= keyClaims && !chosen; key++) {
                for (var claim = 1; claim <= claims && !chosen; claim++) {
                    if (accepted[key][claim] && !usedClaims[claim]) {
                        usedClaims[claim] = true;
                        if (maximum(accepted, key + 1, usedClaims) >= remaining - 1) {
                            result.add(List.of(key, claim));
                            fromKey = key + 1;
                            chosen = true;
                        } else {
                            usedClaims[claim] = false;
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Size of a maximum matching of {@code keyClaims >= fromKey} into the claims not in {@code used}. */
    private static int maximum(boolean[][] accepted, int fromKey, boolean[] used) {
        var claims = used.length - 1;
        var owner = new int[claims + 1];
        var size = 0;
        for (var key = fromKey; key < accepted.length; key++) {
            if (augment(accepted, key, used, owner, new boolean[claims + 1])) {
                size++;
            }
        }
        return size;
    }

    private static boolean augment(boolean[][] accepted, int key, boolean[] used, int[] owner, boolean[] visited) {
        for (var claim = 1; claim < owner.length; claim++) {
            if (accepted[key][claim] && !used[claim] && !visited[claim]) {
                visited[claim] = true;
                if (owner[claim] == 0 || augment(accepted, owner[claim], used, owner, visited)) {
                    owner[claim] = key;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Merges eligible citations into citation groups: per chunk, the connected components of the "overlaps in at
     * least one code point" relation.
     */
    static List<List<CitedSpan>> groups(List<CitedSpan> eligible) {
        var groups = new ArrayList<List<CitedSpan>>();
        var assigned = new boolean[eligible.size()];
        for (var seed = 0; seed < eligible.size(); seed++) {
            if (assigned[seed]) {
                continue;
            }
            var group = new ArrayList<CitedSpan>();
            var pending = new ArrayList<Integer>(List.of(seed));
            assigned[seed] = true;
            while (!pending.isEmpty()) {
                var current = eligible.get(pending.removeLast());
                group.add(current);
                for (var other = 0; other < eligible.size(); other++) {
                    var candidate = eligible.get(other).citation();
                    if (!assigned[other] && current.citation().overlaps(candidate.chunkId(), candidate.start(),
                            candidate.end())) {
                        assigned[other] = true;
                        pending.add(other);
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    /** {@code M(q)}: the size of a maximum one-to-one matching between citation groups and gold spans. */
    static int citationMatching(List<List<CitedSpan>> groups, List<EvidenceSpan> gold) {
        var accepted = new boolean[gold.size() + 1][groups.size() + 1];
        for (var span = 0; span < gold.size(); span++) {
            var target = gold.get(span);
            for (var group = 0; group < groups.size(); group++) {
                accepted[span + 1][group + 1] = groups.get(group).stream().anyMatch(cited -> cited.citation()
                        .overlaps(target.chunkId(), target.start(), target.end()));
            }
        }
        return maximum(accepted, 1, new boolean[groups.size() + 1]);
    }

    private static List<GroupAggregate> aggregate(List<ScoredAnswer> answers) {
        var groups = new LinkedHashMap<List<Object>, List<ScoredAnswer>>();
        for (var answer : answers) {
            var row = answer.row();
            groups.computeIfAbsent(List.of(row.root(), row.layer(), row.answerK(), row.component()),
                    key -> new ArrayList<>()).add(answer);
        }
        var aggregates = new ArrayList<GroupAggregate>();
        for (var group : groups.values()) {
            var first = group.getFirst().row();
            var completed = group.stream().filter(answer -> answer.row().outcome() != Outcome.RECALL_FAILED).toList();
            var scored = completed.stream().filter(answer -> answer.status().isPresent()).toList();
            var statuses = new EnumMap<Status, Integer>(Status.class);
            scored.forEach(answer -> statuses.merge(answer.status().get(), 1, Integer::sum));
            var population = first.kind() == AnswerComponent.Kind.TEST_DOUBLE ? completed : scored;
            var metrics = new LinkedHashMap<String, Aggregate>();
            for (var metric : METRICS) {
                metrics.put(metric, RecallMetrics.mean(population.stream().map(answer -> answer.metrics().get(metric))
                        .toList()));
            }
            var abstentions = scored.stream().filter(answer -> answer.row().outcome() == Outcome.ABSTAINED).toList();
            var correct = (int) abstentions.stream().filter(answer -> answer.query().relevant().isEmpty()).count();
            var empty = (int) scored.stream().filter(answer -> answer.query().relevant().isEmpty()).count();
            var answerable = scored.stream().filter(answer -> !answer.query().relevant().isEmpty()).toList();
            aggregates.add(new GroupAggregate(first.root(), first.layer(), first.answerK(), first.component(),
                    first.kind(), scored.size(), group.size() - completed.size(),
                    (int) completed.stream().filter(answer -> answer.judged() && answer.status().isEmpty()).count(),
                    statuses, metrics, RecallMetrics.ratio(correct, abstentions.size()),
                    RecallMetrics.ratio(correct, empty),
                    (int) answerable.stream().filter(ScoredAnswer::retrievalComplete).count(), answerable.size(),
                    (int) scored.stream().flatMap(answer -> answer.edges().stream()).filter(Edge::overridden).count()));
        }
        return aggregates;
    }

    /** Returns the distinct chunk ids cited by {@code answer}, in first-citation order. */
    static Set<String> citedChunks(ScoredAnswer answer) {
        return answer.row().citations().stream().map(span -> span.citation().chunkId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
