package monada.neuron.routing.comparison;

import monada.neuron.routing.RoutingDecision;

import java.util.List;
import java.util.Objects;

/**
 * A read-only, side-by-side preview of two scenarios: exactly one of {@link Compared} or {@link Incomparable}. Both hold
 * the two original decisions unchanged, so abstention reasons, truncation and exclusion reasons read exactly as Neuron
 * produced them. Only {@link Compared} has a {@link ScenarioDelta}, so a pair that answers different questions can never
 * be shown as a change of advice.
 *
 * <p>A comparison is hypothetical advice about advice. It reserves, authorizes and executes nothing, feeds nothing back
 * to routing state and estimates no spend; both decisions still need the host's recheck before any execution.
 */
public sealed interface ScenarioComparison permits ScenarioComparison.Compared, ScenarioComparison.Incomparable {

    /** The before scenario's decision, unchanged. */
    RoutingDecision before();

    /** The after scenario's decision, unchanged. */
    RoutingDecision after();

    /** Every input that differs, unique and in {@link ScenarioDifference} order; empty for identical scenarios. */
    List<ScenarioDifference> differences();

    /** The labels every comparison carries, in {@link PreviewNotice} order. */
    default List<PreviewNotice> notices() {
        return List.of(PreviewNotice.values());
    }

    /**
     * Two scenarios that differ only in inputs a host may vary to preview their effect.
     *
     * @param before the before decision
     * @param after the after decision
     * @param differences inputs that differ; none breaks comparability
     * @param delta what changed between the decisions; its route, decision, ranking and cohort fields must be the
     *         ones the two decisions imply
     */
    record Compared(RoutingDecision before, RoutingDecision after, List<ScenarioDifference> differences,
            ScenarioDelta delta) implements ScenarioComparison {

        /** Stateless; shared so every comparison checks its delta the same way. */
        private static final DeltaDerivation DERIVATION = new DeltaDerivation();

        /** Validates and copies. */
        public Compared {
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(after, "after must not be null");
            differences = canonical(differences);
            Objects.requireNonNull(delta, "delta must not be null");
            if (differences.stream().anyMatch(ScenarioDifference::breaksComparability)) {
                throw new IllegalArgumentException("compared scenarios cannot differ in " + differences);
            }
            if (!delta.equals(DERIVATION.between(before, after, delta.eligibilityChanges()))) {
                throw new IllegalArgumentException("the delta contradicts the decisions it describes: " + delta);
            }
        }
    }

    /**
     * Two scenarios that answer different questions, so no delta between them exists.
     *
     * @param before the before decision
     * @param after the after decision
     * @param differences inputs that differ; at least one breaks comparability
     */
    record Incomparable(RoutingDecision before, RoutingDecision after, List<ScenarioDifference> differences)
            implements ScenarioComparison {

        /** Validates and copies. */
        public Incomparable {
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(after, "after must not be null");
            differences = canonical(differences);
            if (differences.stream().noneMatch(ScenarioDifference::breaksComparability)) {
                throw new IllegalArgumentException("incomparable scenarios differ in an input that breaks "
                        + "comparability, got " + differences);
            }
        }

        /** The differences that break comparability, in {@link ScenarioDifference} order. */
        public List<ScenarioDifference> breaks() {
            return differences.stream().filter(ScenarioDifference::breaksComparability).toList();
        }
    }

    private static List<ScenarioDifference> canonical(List<ScenarioDifference> differences) {
        Objects.requireNonNull(differences, "differences must not be null");
        ScenarioDifference previous = null;
        for (ScenarioDifference difference : differences) {
            Objects.requireNonNull(difference, "difference must not be null");
            if (previous != null && difference.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("differences must be unique and in declaration order");
            }
            previous = difference;
        }
        return List.copyOf(differences);
    }
}
