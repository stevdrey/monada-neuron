package monada.neuron.routing.feedback;

import java.util.Objects;
import java.util.Optional;

/**
 * Scoring rules of {@link RoutingFeedbackPolicy}. A change here changes what is learned, so the host must give the
 * state definition a new {@code mappingVersion} (contract section 2); #66 folds this into the composite definition.
 *
 * <p>Ordering invariants (ADR 0021 carried over): a proven failure is a weak signal against the choice, never harsher
 * than the reward is strong, and cost can only add to a reward that quality gates already granted.
 *
 * @param reinforceScore score of a validated, stage-local acceptance, in {@code (0.0, 1.0]}
 * @param penalizeScore score of a proven mandatory failure, in {@code [-reinforceScore, 0.0)}
 * @param costBonus extra reward when a directly comparable validated execution was strictly more expensive, in
 *         {@code [0.0, 1.0 - reinforceScore]}; zero disables the refinement
 * @param costObjective compared usage counter; present exactly when {@code costBonus > 0}
 */
public record RoutingFeedbackConfig(
        double reinforceScore, double penalizeScore, double costBonus, Optional<CostObjective> costObjective) {

    /** Conservative defaults without cost refinement. */
    public static final RoutingFeedbackConfig DEFAULT =
            new RoutingFeedbackConfig(1.0, -0.5, 0.0, Optional.empty());

    /** Validates finiteness, signs and the ordering invariants. */
    public RoutingFeedbackConfig {
        if (!Double.isFinite(reinforceScore) || reinforceScore <= 0.0 || reinforceScore > 1.0) {
            throw new IllegalArgumentException(
                    "reinforceScore must be finite and within (0.0, 1.0], got: " + reinforceScore);
        }
        if (!Double.isFinite(penalizeScore) || penalizeScore >= 0.0 || penalizeScore < -reinforceScore) {
            throw new IllegalArgumentException("penalizeScore must be finite and within [-reinforceScore, 0.0), got: "
                    + penalizeScore + " with reinforceScore " + reinforceScore);
        }
        if (!Double.isFinite(costBonus) || costBonus < 0.0 || reinforceScore + costBonus > 1.0) {
            throw new IllegalArgumentException(
                    "costBonus must be finite and within [0.0, 1.0 - reinforceScore], got: " + costBonus);
        }
        Objects.requireNonNull(costObjective, "costObjective must not be null");
        if ((costBonus > 0.0) != costObjective.isPresent()) {
            throw new IllegalArgumentException("a costObjective is required exactly when costBonus is positive");
        }
    }

    /** Score of a reward refined by cost; never above {@code 1.0}. */
    public double refinedReinforceScore() {
        return Math.min(1.0, reinforceScore + costBonus);
    }
}
