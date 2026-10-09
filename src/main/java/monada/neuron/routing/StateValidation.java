package monada.neuron.routing;

import java.util.List;
import java.util.Objects;

/** Outcome of checking a {@link RoutingPreference} against a request. */
public sealed interface StateValidation
        permits StateValidation.Compatible, StateValidation.NotEvaluated, StateValidation.Incompatible {

    /** The snapshot passed every admission rule. */
    record Compatible() implements StateValidation {
    }

    /** The state was not consulted (no route was eligible). */
    record NotEvaluated() implements StateValidation {
    }

    /**
     * The snapshot failed at least one admission rule.
     *
     * @param reasons every mismatch, unique and in {@link StateMismatch} declaration order; the first is primary
     */
    record Incompatible(List<StateMismatch> reasons) implements StateValidation {

        /** Validates order and copies. */
        public Incompatible {
            Objects.requireNonNull(reasons, "reasons must not be null");
            if (reasons.isEmpty() || reasons.size() > StateMismatch.values().length) {
                throw new IllegalArgumentException("reasons must hold 1 to " + StateMismatch.values().length
                        + " entries, got: " + reasons.size());
            }
            StateMismatch previous = null;
            for (StateMismatch reason : reasons) {
                Objects.requireNonNull(reason, "reason must not be null");
                if (previous != null && reason.compareTo(previous) <= 0) {
                    throw new IllegalArgumentException("reasons must be unique and in declaration order");
                }
                previous = reason;
            }
            reasons = List.copyOf(reasons);
        }
    }
}
