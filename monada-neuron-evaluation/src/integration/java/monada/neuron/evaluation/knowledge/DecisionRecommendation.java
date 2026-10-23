package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Claim;

import java.util.List;
import java.util.Objects;

/**
 * What a decision selector (Issue #112) returns for one case: a decision, a conditional answer or an abstention. Every
 * evidence claim carries its citations into the selector's evidence; host-authored reason text is kept apart from those
 * claims, so a report never presents a rule's wording as something the evidence said.
 */
sealed interface DecisionRecommendation {

    /** Most claims one recommendation may carry. */
    int MAX_CLAIMS = 8;

    /**
     * The one permission grant the benchmark recognizes: an {@code allow} listing exactly the features the requirement
     * asks for. A selector that grants anything else grants an unrequested permission.
     */
    String REQUESTED_FEATURES_ONLY = "allow=requested-features-only";

    /** Returns the rule or component that produced the recommendation. */
    String ruleId();

    /**
     * A rejected alternative.
     *
     * @param choice the rejected choice
     * @param reason the host-authored reason
     * @param citations evidence for the reason, empty when the selector found none
     */
    record Exclusion(String choice, String reason, List<Citation> citations) {

        public Exclusion {
            Objects.requireNonNull(choice, "choice must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            citations = List.copyOf(citations);
        }
    }

    /**
     * One option of a conditional answer.
     *
     * @param choice the choice
     * @param assumption the assumption key it depends on, such as {@code module-compatible}
     * @param statement the host-authored statement of the assumption
     */
    record Option(String choice, String assumption, String statement) {

        public Option {
            Objects.requireNonNull(choice, "choice must not be null");
            Objects.requireNonNull(assumption, "assumption must not be null");
            Objects.requireNonNull(statement, "statement must not be null");
        }
    }

    /**
     * A decision.
     *
     * @param choice the recommended choice
     * @param alsoValid further choices the selector states as equally valid
     * @param excluded the rejected alternatives
     * @param constraintsUsed the constraint values the decision depends on, as {@code name=value}
     * @param permissions the permissions the recommendation grants, empty for none
     * @param reason the host-authored reason for the choice
     * @param claims the evidence claims, each with its citations
     * @param ruleId the producing rule
     */
    record Decided(String choice, List<String> alsoValid, List<Exclusion> excluded, List<String> constraintsUsed,
            List<String> permissions, String reason, List<Claim> claims, String ruleId)
            implements DecisionRecommendation {

        public Decided {
            Objects.requireNonNull(choice, "choice must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            alsoValid = List.copyOf(alsoValid);
            excluded = List.copyOf(excluded);
            constraintsUsed = List.copyOf(constraintsUsed);
            permissions = List.copyOf(permissions);
            claims = bounded(claims);
        }
    }

    /**
     * A conditional answer: each option holds only under its assumption.
     *
     * @param options the options, at least one
     * @param excluded the rejected alternatives
     * @param constraintsUsed the constraint values the answer depends on
     * @param claims the evidence claims
     * @param ruleId the producing rule
     */
    record Conditional(List<Option> options, List<Exclusion> excluded, List<String> constraintsUsed,
            List<Claim> claims, String ruleId) implements DecisionRecommendation {

        public Conditional {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("a conditional answer needs at least one option");
            }
            excluded = List.copyOf(excluded);
            constraintsUsed = List.copyOf(constraintsUsed);
            claims = bounded(claims);
        }
    }

    /**
     * An abstention.
     *
     * @param reason why no choice is made
     * @param clarifyingQuestion the question that would decide the case, or {@code "-"} for none
     * @param ruleId the producing rule
     */
    record Abstained(String reason, String clarifyingQuestion, String ruleId) implements DecisionRecommendation {

        public Abstained {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(clarifyingQuestion, "clarifyingQuestion must not be null");
            Objects.requireNonNull(ruleId, "ruleId must not be null");
        }
    }

    private static List<Claim> bounded(List<Claim> claims) {
        if (claims.size() > MAX_CLAIMS) {
            throw new IllegalArgumentException("at most " + MAX_CLAIMS + " claims, got " + claims.size());
        }
        return List.copyOf(claims);
    }
}
