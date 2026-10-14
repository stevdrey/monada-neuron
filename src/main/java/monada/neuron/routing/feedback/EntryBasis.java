package monada.neuron.routing.feedback;

import monada.neuron.routing.token.CanonicalLists;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.QualityDimension;

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
     * @param provenFailures mandatory dimensions whose effective state is {@code FAIL}
     */
    record Derived(
            CohortKey cohortKey,
            ScoreRule rule,
            DerivedAcceptance acceptance,
            List<IneligibilityReason> reasons,
            List<QualityDimension> provenFailures) implements EntryBasis {

        /** Validates and canonicalizes; {@link ScoreRule#requireConsistent} owns the rule/assessment checks. */
        public Derived {
            Objects.requireNonNull(cohortKey, "cohortKey must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
            Objects.requireNonNull(acceptance, "acceptance must not be null");
            reasons = CanonicalLists.canonical(reasons, Comparator.naturalOrder(), IneligibilityReason.values().length,
                    "reasons");
            provenFailures = CanonicalLists.canonical(provenFailures, Comparator.naturalOrder(),
                    QualityDimension.values().length, "provenFailures");
            rule.requireConsistent(acceptance, reasons, provenFailures);
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
