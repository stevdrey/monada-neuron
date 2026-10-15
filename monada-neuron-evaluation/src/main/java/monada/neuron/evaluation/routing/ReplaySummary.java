package monada.neuron.evaluation.routing;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageKind;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Observational summary of one arm's replay: per routed stage kind and for the whole run.
 *
 * <p>Stage figures cover decisions, coverage, quality, the routed stage's own token usage and feedback. Chain
 * figures (attempts, rework and the hypothetical cost to an accepted outcome) are whole-execution facts: they are
 * reported once per execution, identified by scope, task and execution, in {@link #chain()} and never attributed to
 * a stage or route. Token sums add known values only and count unknown and not-measured counters separately; costs
 * are summed per currency and never across currencies, and a partial cost is reported as a separate lower bound.
 * Every figure is a count over the fixture, not a rate estimate and not a causal claim.
 *
 * @param arm the arm
 * @param total stage figures over every stage kind
 * @param byStage stage figures per stage kind
 * @param chain whole-chain figures, once per execution
 * @param pairing direct-comparability of observed executions of the same task
 */
public record ReplaySummary(
        ReplayArm arm,
        StageFigures total,
        SortedMap<String, StageFigures> byStage,
        ChainFigures chain,
        Pairing pairing) {

    /**
     * Figures of the decisions and outcomes of one stage kind (or of all of them).
     *
     * @param decisions decisions made
     * @param selected decisions that advised a route
     * @param abstained abstentions per reason
     * @param noEligibleRoute decisions with no eligible route
     * @param basis selections per basis; {@code COLD_START} and {@code HOST_PRIORITY} are the baseline fallback
     * @param coverage decisions per outcome coverage
     * @param validatedAccepted observed outcomes with a validated acceptance
     * @param gateFailures observed outcomes with at least one mandatory dimension proven to fail
     * @param tokens routed-stage usage per kind
     * @param feedback derived feedback per score rule, and no-update derivations per reason
     * @param updates state updates per kind
     * @param credited derivations that credit a cohort
     * @param eligibilityViolations derivations that break the quality-gated credit rules; must be zero
     */
    public record StageFigures(
            int decisions,
            int selected,
            SortedMap<String, Integer> abstained,
            int noEligibleRoute,
            SortedMap<String, Integer> basis,
            SortedMap<String, Integer> coverage,
            int validatedAccepted,
            int gateFailures,
            SortedMap<String, TokenTally> tokens,
            SortedMap<String, Integer> feedback,
            SortedMap<String, Integer> updates,
            int credited,
            int eligibilityViolations) {

        /** Copies the maps. */
        public StageFigures {
            abstained = frozen(abstained);
            basis = frozen(basis);
            coverage = frozen(coverage);
            tokens = frozen(tokens);
            feedback = frozen(feedback);
            updates = frozen(updates);
        }

        /** Selections that fell back to the deterministic baseline ordering. */
        public int baselineFallback() {
            return basis(Basis.COLD_START) + basis(Basis.HOST_PRIORITY);
        }

        /** Selections with a given basis. */
        public int basis(Basis kind) {
            return basis.getOrDefault(kind.name(), 0);
        }

        /** Abstentions with a given reason. */
        public int abstained(AbstainReason reason) {
            return abstained.getOrDefault(reason.name(), 0);
        }

        /** Decisions with a given coverage. */
        public int coverage(ReplaySample.Coverage kind) {
            return coverage.getOrDefault(kind.name(), 0);
        }
    }

    /**
     * Usage of one kind over the observed routed stages; unknown values are counted, never read as zero.
     *
     * @param knownSum exact sum of the known values; a {@code BigInteger}, so large legal counters never wrap
     * @param known counters with a known value
     * @param unknown counters reported as unknown
     * @param notMeasured counters reported as not measured
     * @param absent observed stages that reported no counter of this kind
     */
    public record TokenTally(BigInteger knownSum, int known, int unknown, int notMeasured, int absent) {
    }

    /**
     * Whole-chain figures, counted once per execution from its terminal observed sample (the last issued, which knows
     * the most about the chain).
     *
     * @param executions observed executions
     * @param attempts attempts over them; a {@code long}, which cannot overflow: at most
     *        {@value ReplayFixture#MAX_TASKS} executions of fewer than 2^31 attempts each
     * @param rework attempts beyond the first, bounded the same way
     * @param costStatus executions per cost status
     * @param available sum of complete costs per currency
     * @param partialLowerBound sum of partial costs per currency; a lower bound, never mixed with {@code available}
     * @param costWithoutValidatedAcceptance executions reporting an {@code AVAILABLE} or {@code PARTIAL} cost to an
     *        accepted outcome without a validated acceptance: an inconsistent cost, counted here and never summed
     */
    public record ChainFigures(
            int executions,
            long attempts,
            long rework,
            SortedMap<String, Integer> costStatus,
            SortedMap<String, BigDecimal> available,
            SortedMap<String, BigDecimal> partialLowerBound,
            int costWithoutValidatedAcceptance) {

        /** Copies the maps. */
        public ChainFigures {
            costStatus = frozen(costStatus);
            available = frozen(available);
            partialLowerBound = frozen(partialLowerBound);
        }
    }

    /**
     * Pairs of observed executions of the same task, one pair per unordered pair of different executions, so two
     * observations of one execution (such as two routed stages) are never paired with each other nor counted twice
     * against another execution. A pair is directly comparable when some routed stage of one and some routed stage of
     * the other have matching {@link ComparisonKey}s; otherwise it is incomparable, by the fields of its closest stage
     * pair (fewest differing fields, the first in replay order on a tie).
     *
     * @param comparable pairs with a matching stage pair: directly comparable
     * @param incomparable pairs whose every stage pair differs in some field: observational only
     * @param incomparableBy incomparable pairs per differing field of their closest stage pair
     */
    public record Pairing(int comparable, int incomparable, SortedMap<String, Integer> incomparableBy) {

        /** Copies the map. */
        public Pairing {
            incomparableBy = frozen(incomparableBy);
        }
    }

    /** Copies the maps. */
    public ReplaySummary {
        Objects.requireNonNull(arm, "arm must not be null");
        Objects.requireNonNull(total, "total must not be null");
        byStage = frozen(byStage);
        Objects.requireNonNull(chain, "chain must not be null");
        Objects.requireNonNull(pairing, "pairing must not be null");
    }

    /**
     * Summarizes a recorded run. {@code O(S + E + Σ n²)} for groups of {@code n} observed samples of one task (every
     * stage pair of every execution pair).
     */
    public static ReplaySummary of(ReplayRun run) {
        var total = new StageTally();
        var byStage = new TreeMap<String, StageTally>();
        for (ReplaySample sample : run.samples()) {
            total.add(sample);
            byStage.computeIfAbsent(sample.stageKind(), ignored -> new StageTally()).add(sample);
        }
        for (FeedbackEvent event : run.feedback()) {
            total.add(event);
            byStage.computeIfAbsent(event.stageKind(), ignored -> new StageTally()).add(event);
        }
        var stages = new TreeMap<String, StageFigures>();
        byStage.forEach((stage, tally) -> stages.put(stage, tally.figures()));
        return new ReplaySummary(run.arm(), total.figures(), stages, chain(run.samples()), pairing(run.samples()));
    }

    /**
     * The terminal observed sample of each execution (scope, task and execution): the last one issued, by
     * {@code requestOrdinal}, which knows the most about the chain (a later attempt sees the repair and the acceptance
     * an earlier one could not). Replay order is cutoff first and a later-issued request may be decided against an
     * earlier snapshot, so issuance order, not list position, decides; ordinals are unique per scope, so there is no
     * tie. It alone carries the execution's whole-chain facts, so they are counted once however many of its stages
     * were routed. Executions are listed in the order they first appear, so iteration is deterministic.
     */
    static List<ReplaySample> chainSamplePerExecution(List<ReplaySample> samples) {
        var terminal = new LinkedHashMap<ExecutionKey, ReplaySample>();
        for (ReplaySample sample : samples) {
            if (sample.coverage() == ReplaySample.Coverage.OBSERVED) {
                terminal.merge(executionKey(sample), sample, (kept, next) ->
                        next.decisionRef().requestOrdinal() > kept.decisionRef().requestOrdinal() ? next : kept);
            }
        }
        return List.copyOf(terminal.values());
    }

    private static ExecutionKey executionKey(ReplaySample sample) {
        return new ExecutionKey(sample.decisionRef().scopeId(), sample.decisionRef().taskId(),
                sample.decisionRef().executionId());
    }

    private static ChainFigures chain(List<ReplaySample> samples) {
        int executions = 0;
        long attempts = 0;
        long rework = 0;
        var status = new TreeMap<String, Integer>();
        var available = new TreeMap<String, BigDecimal>();
        var partial = new TreeMap<String, BigDecimal>();
        int withoutAcceptance = 0;
        for (ReplaySample sample : chainSamplePerExecution(samples)) {
            executions++;
            attempts += sample.attempts();
            rework += sample.attempts() - 1;
            ReplayCost cost = sample.chainCost().orElseThrow();
            status.merge(cost.status().name(), 1, Integer::sum);
            var target = switch (cost.status()) {
                case AVAILABLE -> available;
                case PARTIAL -> partial;
                case UNAVAILABLE -> null;
            };
            if (target != null && !sample.validatedAccepted()) {
                // a cost to an accepted outcome exists only once there is one: reported, never summed
                withoutAcceptance++;
            } else if (target != null) {
                for (Money amount : cost.amounts()) {
                    target.merge(amount.currency(), amount.amount(), BigDecimal::add);
                }
            }
        }
        return new ChainFigures(executions, attempts, rework, status, available, partial, withoutAcceptance);
    }

    private static Pairing pairing(List<ReplaySample> samples) {
        // insertion order follows the samples' replay order, so grouping is deterministic; only counts leave it.
        // Each execution keeps all its observed stages, and every unordered pair of executions is counted once.
        var byTask = new LinkedHashMap<TaskKey, LinkedHashMap<String, List<ReplaySample>>>();
        for (ReplaySample sample : samples) {
            if (sample.coverage() == ReplaySample.Coverage.OBSERVED) {
                byTask.computeIfAbsent(new TaskKey(sample.decisionRef().scopeId(), sample.decisionRef().taskId()),
                        ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(sample.decisionRef().executionId(), ignored -> new ArrayList<>()).add(sample);
            }
        }
        int comparable = 0;
        int incomparable = 0;
        var by = new TreeMap<String, Integer>();
        for (LinkedHashMap<String, List<ReplaySample>> executions : byTask.values()) {
            List<List<ReplaySample>> group = List.copyOf(executions.values());
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    List<String> closest = closestStagePair(group.get(i), group.get(j));
                    if (closest.isEmpty()) {
                        comparable++;
                    } else {
                        incomparable++;
                        closest.forEach(field -> by.merge(field, 1, Integer::sum));
                    }
                }
            }
        }
        return new Pairing(comparable, incomparable, by);
    }

    /** The differing fields of the closest stage pair of two executions; empty when some pair matches. */
    private static List<String> closestStagePair(List<ReplaySample> first, List<ReplaySample> second) {
        List<String> closest = null;
        for (ReplaySample a : first) {
            for (ReplaySample b : second) {
                List<String> fields = a.comparison().differingFields(b.comparison());
                if (fields.isEmpty()) {
                    return fields;
                }
                if (closest == null || fields.size() < closest.size()) {
                    closest = fields;
                }
            }
        }
        return closest;
    }

    /**
     * Every figure as a flat, sorted {@code name -> value} map, prefixed with the arm's name: the canonical form the
     * report prints and the report digest covers.
     */
    public SortedMap<String, String> lines() {
        var lines = new TreeMap<String, String>();
        String prefix = arm.benchmarkName() + ".";
        stageLines(lines, prefix + "total.", total);
        byStage.forEach((stage, figures) -> stageLines(lines, prefix + "stage." + stage + ".", figures));
        lines.put(prefix + "chain.executions", Integer.toString(chain.executions()));
        lines.put(prefix + "chain.attempts", Long.toString(chain.attempts()));
        lines.put(prefix + "chain.rework", Long.toString(chain.rework()));
        chain.costStatus().forEach((k, v) -> lines.put(prefix + "chain.cost.status." + k, Integer.toString(v)));
        chain.available().forEach((k, v) -> lines.put(prefix + "chain.cost.available." + k, v.toPlainString()));
        lines.put(prefix + "chain.cost.withoutValidatedAcceptance",
                Integer.toString(chain.costWithoutValidatedAcceptance()));
        chain.partialLowerBound().forEach((k, v) -> lines.put(prefix + "chain.cost.partialLowerBound." + k,
                v.toPlainString()));
        lines.put(prefix + "pairs.comparable", Integer.toString(pairing.comparable()));
        lines.put(prefix + "pairs.incomparable", Integer.toString(pairing.incomparable()));
        pairing.incomparableBy().forEach((k, v) -> lines.put(prefix + "pairs.incomparableBy." + k,
                Integer.toString(v)));
        return Collections.unmodifiableSortedMap(lines);
    }

    private static void stageLines(Map<String, String> lines, String prefix, StageFigures figures) {
        lines.put(prefix + "decisions", Integer.toString(figures.decisions()));
        lines.put(prefix + "selected", Integer.toString(figures.selected()));
        lines.put(prefix + "noEligibleRoute", Integer.toString(figures.noEligibleRoute()));
        lines.put(prefix + "baselineFallback", Integer.toString(figures.baselineFallback()));
        lines.put(prefix + "validatedAccepted", Integer.toString(figures.validatedAccepted()));
        lines.put(prefix + "gateFailures", Integer.toString(figures.gateFailures()));
        lines.put(prefix + "credited", Integer.toString(figures.credited()));
        lines.put(prefix + "eligibilityViolations", Integer.toString(figures.eligibilityViolations()));
        figures.abstained().forEach((k, v) -> lines.put(prefix + "abstained." + k, Integer.toString(v)));
        figures.basis().forEach((k, v) -> lines.put(prefix + "basis." + k, Integer.toString(v)));
        figures.coverage().forEach((k, v) -> lines.put(prefix + "coverage." + k, Integer.toString(v)));
        figures.feedback().forEach((k, v) -> lines.put(prefix + "feedback." + k, Integer.toString(v)));
        figures.updates().forEach((k, v) -> lines.put(prefix + "update." + k, Integer.toString(v)));
        figures.tokens().forEach((k, t) -> lines.put(prefix + "tokens." + k, "knownSum=" + t.knownSum() + ",known="
                + t.known() + ",unknown=" + t.unknown() + ",notMeasured=" + t.notMeasured() + ",absent="
                + t.absent()));
    }

    /** One execution: scope, task and execution, compared component by component (no concatenation). */
    private record ExecutionKey(String scopeId, String taskId, String executionId) {
    }

    /** One task of one scope. */
    private record TaskKey(String scopeId, String taskId) {
    }

    private static <K, V> SortedMap<K, V> frozen(SortedMap<K, V> map) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(map));
    }

    /**
     * Mutable accumulator of one stage kind; never escapes {@link #of}. Its {@code int} counters cannot overflow: they
     * count at most {@value ReplayFixture#MAX_TASKS} decisions and {@value RebuildOrder#MAX_OBSERVATIONS} deliveries.
     */
    private static final class StageTally {

        private int decisions;
        private int selected;
        private final TreeMap<String, Integer> abstained = new TreeMap<>();
        private int noEligibleRoute;
        private final TreeMap<String, Integer> basis = new TreeMap<>();
        private final TreeMap<String, Integer> coverage = new TreeMap<>();
        private int validatedAccepted;
        private int gateFailures;
        private final TreeMap<String, TokenAccumulator> tokens = new TreeMap<>();
        private final TreeMap<String, Integer> feedback = new TreeMap<>();
        private final TreeMap<String, Integer> updates = new TreeMap<>();
        private int credited;
        private int eligibilityViolations;

        void add(ReplaySample sample) {
            decisions++;
            coverage.merge(sample.coverage().name(), 1, Integer::sum);
            switch (sample.kind()) {
                case SELECTED -> {
                    selected++;
                    basis.merge(sample.basis().orElseThrow().name(), 1, Integer::sum);
                }
                case ABSTAINED -> abstained.merge(sample.abstainReason().orElseThrow().name(), 1, Integer::sum);
                case NO_ELIGIBLE_ROUTE -> noEligibleRoute++;
            }
            if (sample.coverage() != ReplaySample.Coverage.OBSERVED) {
                return;
            }
            if (sample.validatedAccepted()) {
                validatedAccepted++;
            }
            if (!sample.provenFailures().isEmpty()) {
                gateFailures++;
            }
            var reported = new HashSet<String>();
            for (UsageCounter counter : sample.stageUsage()) {
                TokenAccumulator tally = tokens.computeIfAbsent(counter.kind().name(),
                        ignored -> new TokenAccumulator());
                reported.add(counter.kind().name());
                switch (counter.value()) {
                    case ResourceValue.Known known -> {
                        tally.knownSum = tally.knownSum.add(BigInteger.valueOf(known.value()));
                        tally.known++;
                    }
                    case ResourceValue.Unknown ignored -> tally.unknown++;
                    case ResourceValue.NotMeasured ignored -> tally.notMeasured++;
                }
            }
            for (UsageKind kind : UsageKind.values()) {
                if (!reported.contains(kind.name())) {
                    tokens.computeIfAbsent(kind.name(), ignored -> new TokenAccumulator()).absent++;
                }
            }
        }

        void add(FeedbackEvent event) {
            String key = event.rule().map(rule -> "rule." + rule.name())
                    .orElseGet(() -> "noUpdate." + event.noUpdate().orElseThrow().name());
            feedback.merge(key, 1, Integer::sum);
            updates.merge(event.update().name(), 1, Integer::sum);
            if (event.credits()) {
                credited++;
            }
            if (event.eligibilityViolation()) {
                eligibilityViolations++;
            }
        }

        StageFigures figures() {
            var tallies = new TreeMap<String, TokenTally>();
            tokens.forEach((kind, t) -> tallies.put(kind,
                    new TokenTally(t.knownSum, t.known, t.unknown, t.notMeasured, t.absent)));
            return new StageFigures(decisions, selected, abstained, noEligibleRoute, basis, coverage,
                    validatedAccepted, gateFailures, tallies, feedback, updates, credited, eligibilityViolations);
        }
    }

    /** Mutable usage tally of one kind; never escapes {@link #of}. */
    private static final class TokenAccumulator {
        private BigInteger knownSum = BigInteger.ZERO;
        private int known;
        private int unknown;
        private int notMeasured;
        private int absent;
    }
}
