package monada.neuron.routing.feedback;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.IneligibilityReason;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a ledger entry examined, so that two different claims about one sample revision never compare equal: either a
 * derived feedback (with the rule and the recorded assessment that selected it) or a no-update marker (with the reason
 * and the outcome's cohort binding, because a marker has no feedback to carry them).
 */
public sealed interface EntryBasis permits EntryBasis.Derived, EntryBasis.NoUpdateMarker {

    /**
     * A derived feedback.
     *
     * @param cohortKey cohort the feedback named
     * @param rule rule that produced the disposition and the score
     * @param acceptance acceptance derived as the Store defines it
     * @param reasons assessment reasons, declaration order
     */
    record Derived(CohortKey cohortKey, ScoreRule rule, DerivedAcceptance acceptance, List<IneligibilityReason> reasons)
            implements EntryBasis {

        /** Validates and canonicalizes. */
        public Derived {
            Objects.requireNonNull(cohortKey, "cohortKey must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
            Objects.requireNonNull(acceptance, "acceptance must not be null");
            reasons = Canonical.sortedUnique(reasons, Comparator.naturalOrder(), IneligibilityReason.values().length,
                    "reasons");
            // the same rule/assessment consistency RoutingFeedback enforces, so a ledger entry cannot claim a rule
            // that the recorded assessment could never have selected
            if (reasons.contains(IneligibilityReason.PROVENANCE_MISMATCH)
                    || reasons.contains(IneligibilityReason.STALE_ARTIFACT_REVISION)
                    || reasons.contains(IneligibilityReason.CONTRADICTORY_EVIDENCE)) {
                throw new IllegalArgumentException("the policy derives nothing for reasons " + reasons);
            }
            switch (rule.disposition()) {
                case REINFORCE -> {
                    if (acceptance != DerivedAcceptance.VALIDATED_ACCEPTED || !reasons.isEmpty()) {
                        throw new IllegalArgumentException("a reward needs validated acceptance and no reason");
                    }
                }
                case PENALIZE -> {
                    if ((acceptance != DerivedAcceptance.NOT_ACCEPTED
                            && acceptance != DerivedAcceptance.ACCEPTED_UNVALIDATED)
                            || reasons.stream().anyMatch(IneligibilityReason::blocking)
                            || reasons.contains(IneligibilityReason.IMPORTED_CLAIM)
                            || reasons.contains(IneligibilityReason.NO_STAGE_ATTRIBUTION)
                            || reasons.contains(IneligibilityReason.NO_ATTRIBUTABLE_FAILURE)) {
                        throw new IllegalArgumentException("a penalty cannot follow " + acceptance + " " + reasons);
                    }
                }
                case NEUTRAL -> {
                    if (!rule.covers(acceptance, reasons, List.of())) {
                        throw new IllegalArgumentException(
                                "rule " + rule + " does not match the recorded assessment: " + acceptance + " " + reasons);
                    }
                    for (ScoreRule earlier : ScoreRule.values()) {
                        if (earlier.ordinal() < rule.ordinal() && earlier.covers(acceptance, reasons, List.of())) {
                            throw new IllegalArgumentException(
                                    "rule " + earlier + " takes precedence over " + rule + " for " + reasons);
                        }
                    }
                }
            }
        }
    }

    /**
     * An observation that derived nothing.
     *
     * @param reason why nothing was derived; never a reason that is not recorded
     * @param cohortBinding cohort binding the outcome recorded
     */
    record NoUpdateMarker(NoUpdateReason reason, CohortBinding cohortBinding) implements EntryBasis {

        /** Validates the parts. */
        public NoUpdateMarker {
            Objects.requireNonNull(reason, "reason must not be null");
            if (!reason.recordable()) {
                throw new IllegalArgumentException(reason + " is not recorded in the ledger");
            }
            Objects.requireNonNull(cohortBinding, "cohortBinding must not be null");
        }
    }
}
