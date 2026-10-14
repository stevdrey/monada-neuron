package monada.neuron.routing.feedback;

import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.ProvenanceField;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Result of {@link RoutingFeedbackPolicy#derive}: feedback (possibly neutral) or an explicit "no update". */
public sealed interface FeedbackDerivation permits FeedbackDerivation.Derived, FeedbackDerivation.NoUpdate {

    /**
     * Feedback to hand to the caller's state, which applies a non-neutral one to the bound Node.
     *
     * @param feedback the derived artifact
     */
    record Derived(RoutingFeedback feedback) implements FeedbackDerivation {

        /** Requires the artifact. */
        public Derived {
            Objects.requireNonNull(feedback, "feedback must not be null");
        }
    }

    /**
     * No feedback exists for the sample and nothing may be applied.
     *
     * @param reason why
     * @param reasons assessment reasons, declaration order; empty when the reason is a binding or mapping one
     * @param mismatches provenance fields that disagree, declaration order; non-empty only for
     *         {@code PROVENANCE_MISMATCH}
     */
    record NoUpdate(NoUpdateReason reason, List<IneligibilityReason> reasons, List<ProvenanceField> mismatches)
            implements FeedbackDerivation {

        /** Validates and canonicalizes. */
        public NoUpdate {
            Objects.requireNonNull(reason, "reason must not be null");
            reasons = Canonical.sortedUnique(reasons, Comparator.naturalOrder(), IneligibilityReason.values().length,
                    "reasons");
            mismatches = Canonical.sortedUnique(mismatches, Comparator.naturalOrder(), ProvenanceField.values().length,
                    "mismatches");
            if (reason != NoUpdateReason.PROVENANCE_MISMATCH && !mismatches.isEmpty()) {
                throw new IllegalArgumentException("only PROVENANCE_MISMATCH lists mismatches");
            }
            if (reasons.contains(IneligibilityReason.PROVENANCE_MISMATCH) != !mismatches.isEmpty()) {
                throw new IllegalArgumentException(
                        "mismatches must be listed exactly when the PROVENANCE_MISMATCH reason is");
            }
            if (reason == NoUpdateReason.PROVENANCE_MISMATCH && mismatches.isEmpty()) {
                throw new IllegalArgumentException("PROVENANCE_MISMATCH needs the differing fields");
            }
        }
    }
}
