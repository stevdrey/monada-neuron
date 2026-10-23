package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.DecisionTrace.Arm;
import monada.neuron.evaluation.knowledge.DecisionTrace.Hit;
import monada.neuron.evaluation.knowledge.DecisionTrace.Outcome;
import monada.neuron.evaluation.knowledge.DecisionTrace.Row;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Expected;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Gold;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Permissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;

/**
 * The scorer of the decision benchmark (Issue #112) and, with the runner, the only reader of {@link HtmlDecisionGold}.
 * It judges every selector row against the gold label of its case and every pair of rows against the gold of its pair.
 *
 * <p>Per row: a {@link Verdict}; the ratios {@code evidenceCoverage} (gold-span chunks among the evidence the arm saw),
 * {@code citationFidelity} (citations inside that evidence), {@code citationGoldOverlap} (citations overlapping a gold
 * span), {@code unsupportedClaimRate} (claims without an eligible exact-quote citation) and
 * {@code exclusionsCited}; and the flags {@code candidateValid}, {@code constraintsSatisfied}, {@code contradiction},
 * {@code noEvidenceCertainty}, {@code unrequestedPermission} and {@code missingPermission}. A ratio with an empty
 * denominator is undefined, never zero. Arm {@link Arm#STORE} gets coverage only; arm {@link Arm#GENERATOR} gets the
 * citation metrics only and no verdict, because free text carries no structured choice.
 *
 * <p>Per pair and arm: whether the observed outcomes change exactly when the gold outcomes do. Cost: linear in the rows
 * times the citations of a row.
 */
final class DecisionMetrics {

    /** The scored judgment of one selector row. */
    enum Verdict {
        /** Decided an acceptable choice, stating no non-acceptable alternative as valid. */
        CORRECT_DECISION,
        /** A conditional answer stating every gold option with its assumption and no ineligible option. */
        CORRECT_CONDITIONAL,
        /** Abstained where the gold abstains. */
        CORRECT_ABSTENTION,
        /** Abstained because the arm's evidence held none of the gold chunks: justified by it, not correct. */
        EVIDENCE_MISS_ABSTENTION,
        /** Abstained although the gold decides or answers conditionally and relevant evidence was present. */
        WRONG_ABSTENTION,
        /** Recommended, or stated as valid, a choice the gold marks ineligible. */
        WRONG_CHOICE,
        /** Decided a choice the gold neither accepts nor rejects, or added such an alternative. */
        UNLISTED_CHOICE,
        /** Decided without conditions where the gold answer is conditional or an abstention. */
        OVERCONFIDENT,
        /** A conditional answer where the gold decides one choice. */
        HEDGED,
        /** A conditional answer missing a gold option or its assumption. */
        INCOMPLETE_CONDITIONAL,
        /** Not judged: the row failed or is free text. */
        NOT_SCORED
    }

    /** Verdicts credited as a correct outcome. */
    static final Set<Verdict> CORRECT = Set.of(Verdict.CORRECT_DECISION, Verdict.CORRECT_CONDITIONAL,
            Verdict.CORRECT_ABSTENTION);

    /** The ratio metrics, in report order. */
    static final List<String> METRICS = List.of("evidenceCoverage", "citationFidelity", "citationGoldOverlap",
            "unsupportedClaimRate", "exclusionsCited");

    /** The flags, in report order. */
    static final List<String> FLAGS = List.of("candidateValid", "constraintsSatisfied", "contradiction",
            "noEvidenceCertainty", "unrequestedPermission", "missingPermission");

    /**
     * One scored row.
     *
     * @param row the trace row
     * @param gold the gold label of its case
     * @param verdict the verdict
     * @param metrics the ratio metrics, empty when undefined
     * @param flags the flags of a judged row
     * @param diagnostics why the row is not correct, empty when it is
     */
    record Scored(Row row, Gold gold, Verdict verdict, Map<String, OptionalDouble> metrics, Map<String, Boolean> flags,
            List<String> diagnostics) {

        Scored {
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(gold, "gold must not be null");
            Objects.requireNonNull(verdict, "verdict must not be null");
            metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
            flags = Collections.unmodifiableMap(new LinkedHashMap<>(flags));
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * One pair judged for one arm.
     *
     * @param pair the pair
     * @param arm the arm
     * @param expectedChange whether the gold outcomes of the two cases differ
     * @param observedChange whether the observed outcomes differ
     * @param scored whether both rows were judged
     */
    record PairScore(HtmlDecisionCases.Pair pair, Arm arm, boolean expectedChange, boolean observedChange,
            boolean scored) {

        boolean consistent() {
            return scored && expectedChange == observedChange;
        }
    }

    /**
     * Totals of one arm.
     *
     * @param arm the arm
     * @param rows its rows
     * @param verdicts the count per verdict
     * @param correct rows with a correct verdict
     * @param judged rows with a verdict other than {@link Verdict#NOT_SCORED}
     * @param metrics the mean of each ratio over the rows where it is defined
     * @param flagged the count of rows per raised flag
     * @param pairsConsistent consistent pairs
     * @param pairsScored judged pairs
     */
    record ArmAggregate(Arm arm, int rows, Map<Verdict, Integer> verdicts, int correct, int judged,
            Map<String, OptionalDouble> metrics, Map<String, Integer> flagged, int pairsConsistent, int pairsScored) {
    }

    /**
     * The scores of a run.
     *
     * @param scored every row, in trace order
     * @param pairs every pair per selector arm
     * @param aggregates the totals per arm present
     */
    record Scores(List<Scored> scored, List<PairScore> pairs, List<ArmAggregate> aggregates) {

        Scores {
            scored = List.copyOf(scored);
            pairs = List.copyOf(pairs);
            aggregates = List.copyOf(aggregates);
        }
    }

    private DecisionMetrics() {
    }

    /**
     * Requires exactly the expected row matrix: every case once per arm, and arm {@link Arm#GENERATOR} for every case
     * or for none.
     *
     * @throws IllegalArgumentException naming the first missing or extra row
     */
    static void requireComplete(List<Row> rows, HtmlDecisionCases cases) {
        var seen = new HashSet<String>();
        var generatorRows = rows.stream().filter(row -> row.arm() == Arm.GENERATOR).count();
        for (var row : rows) {
            if (!cases.contains(row.caseId())) {
                throw new IllegalArgumentException("row for unknown case " + row.caseId());
            }
            if (!seen.add(row.caseId() + " " + row.arm())) {
                throw new IllegalArgumentException("repeated row " + row.caseId() + " " + row.arm().label());
            }
        }
        for (var decisionCase : cases.cases()) {
            for (var arm : Arm.values()) {
                if (arm == Arm.GENERATOR && generatorRows == 0) {
                    continue;
                }
                if (!seen.contains(decisionCase.caseId() + " " + arm)) {
                    throw new IllegalArgumentException("missing row " + decisionCase.caseId() + " " + arm.label());
                }
            }
        }
    }

    /** Scores {@code rows} against {@code gold}. */
    static Scores score(List<Row> rows, HtmlDecisionCases cases, HtmlDecisionGold gold) {
        requireComplete(rows, cases);
        var scored = new ArrayList<Scored>(rows.size());
        for (var row : rows) {
            scored.add(score(row, cases.byId(row.caseId()), gold.of(row.caseId())));
        }
        var byKey = new LinkedHashMap<String, Scored>();
        scored.forEach(value -> byKey.put(value.row().caseId() + " " + value.row().arm(), value));
        var pairs = new ArrayList<PairScore>();
        for (var arm : List.of(Arm.STORE_RULES, Arm.NEURON_RULES, Arm.NO_EVIDENCE_RULES)) {
            for (var pair : cases.pairs()) {
                var first = byKey.get(pair.first() + " " + arm);
                var second = byKey.get(pair.second() + " " + arm);
                var judged = first.verdict() != Verdict.NOT_SCORED && second.verdict() != Verdict.NOT_SCORED;
                pairs.add(new PairScore(pair, arm, !goldSignature(first.gold()).equals(goldSignature(second.gold())),
                        !signature(first.row()).equals(signature(second.row())), judged));
            }
        }
        var aggregates = new ArrayList<ArmAggregate>();
        for (var arm : Arm.values()) {
            var armRows = scored.stream().filter(value -> value.row().arm() == arm).toList();
            if (armRows.isEmpty()) {
                continue;
            }
            var verdicts = new EnumMap<Verdict, Integer>(Verdict.class);
            armRows.forEach(value -> verdicts.merge(value.verdict(), 1, Integer::sum));
            var metrics = new LinkedHashMap<String, OptionalDouble>();
            for (var metric : METRICS) {
                metrics.put(metric, mean(armRows.stream().map(value -> value.metrics().get(metric)).toList()));
            }
            var flagged = new LinkedHashMap<String, Integer>();
            for (var flag : FLAGS) {
                flagged.put(flag, (int) armRows.stream().filter(value -> value.flags().getOrDefault(flag, false))
                        .count());
            }
            var armPairs = pairs.stream().filter(pair -> pair.arm() == arm).toList();
            aggregates.add(new ArmAggregate(arm, armRows.size(), verdicts,
                    (int) armRows.stream().filter(value -> CORRECT.contains(value.verdict())).count(),
                    (int) armRows.stream().filter(value -> value.verdict() != Verdict.NOT_SCORED).count(), metrics,
                    flagged, (int) armPairs.stream().filter(PairScore::consistent).count(),
                    (int) armPairs.stream().filter(PairScore::scored).count()));
        }
        return new Scores(scored, pairs, aggregates);
    }

    /** Scores one row against the gold label of its case. */
    static Scored score(Row row, HtmlDecisionCases.DecisionCase decisionCase, Gold gold) {
        var metrics = new LinkedHashMap<String, OptionalDouble>();
        var goldChunks = gold.evidenceChunks();
        var seen = row.evidence().stream().map(Hit::chunkId).toList();
        metrics.put("evidenceCoverage", ratio(goldChunks.stream().filter(seen::contains).count(), goldChunks.size()));
        var citations = row.citations();
        metrics.put("citationFidelity", ratio(citations.stream().filter(CitedSpan::eligible).count(),
                citations.size()));
        metrics.put("citationGoldOverlap", ratio(citations.stream().filter(span -> overlapsGold(span, gold)).count(),
                citations.size()));
        metrics.put("unsupportedClaimRate", ratio(row.claims().stream().filter(claim -> claim.citations().stream()
                .noneMatch(CitedSpan::exactQuote)).count(), row.claims().size()));
        var diagnostics = new ArrayList<String>();
        goldChunks.stream().filter(chunk -> !seen.contains(chunk))
                .forEach(chunk -> diagnostics.add("evidence lacks gold chunk " + chunk));
        if (row.recommendation().isEmpty()) {
            metrics.put("exclusionsCited", OptionalDouble.empty());
            if (row.outcome() == Outcome.RECALL_FAILED) {
                diagnostics.add("recall failed: " + row.detail());
            }
            return new Scored(row, gold, Verdict.NOT_SCORED, metrics, Map.of(), diagnostics);
        }
        var recommendation = row.recommendation().get();
        var exclusions = switch (recommendation) {
            case Decided decided -> decided.excluded();
            case Conditional conditional -> conditional.excluded();
            case Abstained _ -> List.<DecisionRecommendation.Exclusion>of();
        };
        metrics.put("exclusionsCited", ratio(exclusions.stream().filter(exclusion -> !exclusion.citations().isEmpty())
                .count(), exclusions.size()));

        var named = named(recommendation);
        var flags = new LinkedHashMap<String, Boolean>();
        var invalid = named.stream().filter(choice -> !decisionCase.candidates().contains(choice)).toList();
        flags.put("candidateValid", invalid.isEmpty());
        invalid.forEach(choice -> diagnostics.add("named non-candidate choice " + choice));
        var ineligible = named.stream().filter(gold.ineligible()::contains).toList();
        ineligible.forEach(choice -> diagnostics.add("named ineligible choice " + choice));
        var permissions = recommendation instanceof Decided decided ? decided.permissions() : List.<String>of();
        var unrequested = gold.permissions() == Permissions.NONE && !permissions.isEmpty();
        var missing = gold.permissions() == Permissions.REQUESTED && recommendation instanceof Decided
                && permissions.isEmpty();
        flags.put("unrequestedPermission", unrequested);
        flags.put("missingPermission", missing);
        if (unrequested) {
            diagnostics.add("granted unrequested permissions " + permissions);
        }
        if (missing) {
            diagnostics.add("granted none of the requested permissions");
        }
        flags.put("constraintsSatisfied", ineligible.isEmpty() && !unrequested && !missing);
        var rejectsAcceptable = gold.expected() == Expected.DECIDE && exclusions.stream()
                .anyMatch(exclusion -> gold.acceptable().contains(exclusion.choice()));
        if (rejectsAcceptable) {
            diagnostics.add("excluded an acceptable choice");
        }
        flags.put("contradiction", !ineligible.isEmpty() || rejectsAcceptable);
        var eligibleCitations = row.citations().stream().filter(CitedSpan::eligible).count();
        var certain = !(recommendation instanceof Abstained);
        flags.put("noEvidenceCertainty", certain && eligibleCitations == 0);
        if (certain && eligibleCitations == 0) {
            diagnostics.add("answered without any eligible citation");
        }

        var verdict = verdict(recommendation, gold, goldChunks.stream().noneMatch(seen::contains), diagnostics);
        return new Scored(row, gold, verdict, metrics, flags, diagnostics);
    }

    private static Verdict verdict(DecisionRecommendation recommendation, Gold gold, boolean evidenceMissed,
            List<String> diagnostics) {
        return switch (recommendation) {
            case Abstained _ -> {
                if (gold.expected() == Expected.ABSTAIN) {
                    yield Verdict.CORRECT_ABSTENTION;
                }
                diagnostics.add("abstained; the gold is " + gold.expected());
                yield evidenceMissed ? Verdict.EVIDENCE_MISS_ABSTENTION : Verdict.WRONG_ABSTENTION;
            }
            case Decided decided -> {
                var stated = new ArrayList<String>();
                stated.add(decided.choice());
                stated.addAll(decided.alsoValid());
                if (stated.stream().anyMatch(gold.ineligible()::contains)) {
                    yield Verdict.WRONG_CHOICE;
                }
                if (gold.expected() != Expected.DECIDE) {
                    diagnostics.add("decided " + decided.choice() + "; the gold is " + gold.expected());
                    yield Verdict.OVERCONFIDENT;
                }
                if (!gold.acceptable().containsAll(stated)) {
                    diagnostics.add("stated " + stated + ", acceptable are " + new TreeSet<>(gold.acceptable()));
                    yield Verdict.UNLISTED_CHOICE;
                }
                yield Verdict.CORRECT_DECISION;
            }
            case Conditional conditional -> {
                if (conditional.options().stream().anyMatch(option -> gold.ineligible().contains(option.choice()))) {
                    yield Verdict.WRONG_CHOICE;
                }
                if (gold.expected() == Expected.DECIDE) {
                    diagnostics.add("answered conditionally; the gold decides " + new TreeSet<>(gold.acceptable()));
                    yield Verdict.HEDGED;
                }
                if (gold.expected() == Expected.ABSTAIN) {
                    diagnostics.add("answered conditionally; the gold abstains");
                    yield Verdict.OVERCONFIDENT;
                }
                var stated = conditional.options().stream().map(option -> option.choice() + "@" + option.assumption())
                        .toList();
                var missingOptions = gold.options().stream().map(HtmlDecisionGold.Option::render)
                        .filter(option -> !stated.contains(option)).toList();
                if (!missingOptions.isEmpty()) {
                    diagnostics.add("missing conditional options " + missingOptions);
                    yield Verdict.INCOMPLETE_CONDITIONAL;
                }
                yield Verdict.CORRECT_CONDITIONAL;
            }
        };
    }

    /** Every choice a recommendation states as valid. */
    private static List<String> named(DecisionRecommendation recommendation) {
        return switch (recommendation) {
            case Decided decided -> {
                var all = new ArrayList<String>();
                all.add(decided.choice());
                all.addAll(decided.alsoValid());
                yield all;
            }
            case Conditional conditional -> conditional.options().stream()
                    .map(DecisionRecommendation.Option::choice).toList();
            case Abstained _ -> List.of();
        };
    }

    private static boolean overlapsGold(CitedSpan span, Gold gold) {
        return span.eligible() && gold.evidence().stream().anyMatch(goldSpan -> span.citation()
                .overlaps(goldSpan.chunkId(), goldSpan.start(), goldSpan.end()));
    }

    /** The observable outcome of a row, compared within a pair. */
    static String signature(Row row) {
        return row.outcome() + " " + row.recommendation().map(value -> switch (value) {
            case Decided decided -> new TreeSet<>(named(decided)) + " " + decided.permissions();
            case Conditional conditional -> conditional.options().stream()
                    .map(option -> option.choice() + "@" + option.assumption()).sorted().toList().toString();
            case Abstained _ -> "";
        }).orElse("");
    }

    /** The gold outcome of a case, compared within a pair. */
    static String goldSignature(Gold gold) {
        return gold.expected() + " " + new TreeSet<>(gold.acceptable()) + " " + gold.options().stream()
                .map(HtmlDecisionGold.Option::render).sorted().toList() + " " + gold.permissions();
    }

    private static OptionalDouble ratio(long numerator, long denominator) {
        return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) numerator / denominator);
    }

    private static OptionalDouble mean(List<OptionalDouble> values) {
        return values.stream().filter(OptionalDouble::isPresent).mapToDouble(OptionalDouble::getAsDouble).average();
    }
}
