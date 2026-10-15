package monada.neuron.evaluation.routing;

import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
 * reported once per execution in {@link #chain()} and never attributed to a stage or route. Token sums add known values
 * only and count unknown and not-measured counters separately; costs are summed per currency and never across
 * currencies, and a partial cost is reported as a separate lower bound. Every figure is a count over the fixture, not
 * a rate estimate and not a causal claim.
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
            return basis.getOrDefault("COLD_START", 0) + basis.getOrDefault("HOST_PRIORITY", 0);
        }

        /** Decisions with a given coverage. */
        public int coverage(ReplaySample.Coverage kind) {
            return coverage.getOrDefault(kind.name(), 0);
        }
    }

    /**
     * Usage of one kind over the observed routed stages; unknown values are counted, never read as zero.
     *
     * @param knownSum sum of the known values
     * @param known counters with a known value
     * @param unknown counters reported as unknown
     * @param notMeasured counters reported as not measured
     * @param absent observed stages that reported no counter of this kind
     */
    public record TokenTally(long knownSum, int known, int unknown, int notMeasured, int absent) {
    }

    /**
     * Whole-chain figures, counted once per execution.
     *
     * @param executions observed executions
     * @param attempts attempts over them
     * @param rework attempts beyond the first
     * @param costStatus executions per cost status
     * @param available sum of complete costs per currency
     * @param partialLowerBound sum of partial costs per currency; a lower bound, never mixed with {@code available}
     */
    public record ChainFigures(
            int executions,
            int attempts,
            int rework,
            SortedMap<String, Integer> costStatus,
            SortedMap<String, BigDecimal> available,
            SortedMap<String, BigDecimal> partialLowerBound) {

        /** Copies the maps. */
        public ChainFigures {
            costStatus = frozen(costStatus);
            available = frozen(available);
            partialLowerBound = frozen(partialLowerBound);
        }

        /** Executions with a given cost status. */
        public int costStatus(ReplayCost.Status status) {
            return costStatus.getOrDefault(status.name(), 0);
        }
    }

    /**
     * Pairs of observed executions of the same task.
     *
     * @param comparable pairs whose {@link ComparisonKey}s match: directly comparable
     * @param incomparable pairs that differ in some field: observational only
     * @param incomparableBy incomparable pairs per differing field
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

    /** Summarizes a recorded run. {@code O(S + E + Σ g²)} for groups of {@code g} observed samples of one task. */
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

    private static ChainFigures chain(List<ReplaySample> samples) {
        Set<String> seen = new HashSet<>();
        int executions = 0;
        int attempts = 0;
        int rework = 0;
        var status = new TreeMap<String, Integer>();
        var available = new TreeMap<String, BigDecimal>();
        var partial = new TreeMap<String, BigDecimal>();
        for (ReplaySample sample : samples) {
            if (sample.coverage() != ReplaySample.Coverage.OBSERVED
                    || !seen.add(sample.decisionRef().scopeId() + "\u0000" + sample.decisionRef().executionId())) {
                continue;
            }
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
            if (target != null) {
                for (Money amount : cost.amounts()) {
                    target.merge(amount.currency(), amount.amount(), BigDecimal::add);
                }
            }
        }
        return new ChainFigures(executions, attempts, rework, status, available, partial);
    }

    private static Pairing pairing(List<ReplaySample> samples) {
        var byTask = new TreeMap<String, List<ReplaySample>>();
        for (ReplaySample sample : samples) {
            if (sample.coverage() == ReplaySample.Coverage.OBSERVED) {
                byTask.computeIfAbsent(sample.decisionRef().scopeId() + "\u0000" + sample.decisionRef().taskId(),
                        ignored -> new ArrayList<>()).add(sample);
            }
        }
        int comparable = 0;
        int incomparable = 0;
        var by = new TreeMap<String, Integer>();
        for (List<ReplaySample> group : byTask.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    List<String> fields = group.get(i).comparison().differingFields(group.get(j).comparison());
                    if (fields.isEmpty()) {
                        comparable++;
                    } else {
                        incomparable++;
                        fields.forEach(field -> by.merge(field, 1, Integer::sum));
                    }
                }
            }
        }
        return new Pairing(comparable, incomparable, by);
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
        lines.put(prefix + "chain.attempts", Integer.toString(chain.attempts()));
        lines.put(prefix + "chain.rework", Integer.toString(chain.rework()));
        chain.costStatus().forEach((k, v) -> lines.put(prefix + "chain.cost.status." + k, Integer.toString(v)));
        chain.available().forEach((k, v) -> lines.put(prefix + "chain.cost.available." + k, v.toPlainString()));
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

    private static <K, V> SortedMap<K, V> frozen(SortedMap<K, V> map) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(map));
    }

    /** Mutable accumulator of one stage kind; never escapes {@link #of}. */
    private static final class StageTally {

        private int decisions;
        private int selected;
        private final TreeMap<String, Integer> abstained = new TreeMap<>();
        private int noEligibleRoute;
        private final TreeMap<String, Integer> basis = new TreeMap<>();
        private final TreeMap<String, Integer> coverage = new TreeMap<>();
        private int validatedAccepted;
        private int gateFailures;
        private final TreeMap<String, long[]> tokens = new TreeMap<>();
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
                long[] tally = tokens.computeIfAbsent(counter.kind().name(), ignored -> new long[5]);
                reported.add(counter.kind().name());
                switch (counter.value()) {
                    case ResourceValue.Known known -> {
                        tally[0] += known.value();
                        tally[1]++;
                    }
                    case ResourceValue.Unknown ignored -> tally[2]++;
                    case ResourceValue.NotMeasured ignored -> tally[3]++;
                }
            }
            for (UsageKind kind : UsageKind.values()) {
                if (!reported.contains(kind.name())) {
                    tokens.computeIfAbsent(kind.name(), ignored -> new long[5])[4]++;
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
                    new TokenTally(t[0], (int) t[1], (int) t[2], (int) t[3], (int) t[4])));
            return new StageFigures(decisions, selected, abstained, noEligibleRoute, basis, coverage,
                    validatedAccepted, gateFailures, tallies, feedback, updates, credited, eligibilityViolations);
        }
    }
}
