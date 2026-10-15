package monada.neuron.evaluation.routing;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.EligibilityVerdict;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageDeclaration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One replayed decision and, when its outcome is known, that outcome as of the replay horizon (latest revision).
 *
 * <p>Stage facts ({@link #stageUsage}) and whole-chain facts ({@link #attempts}, {@link #chainCost}) stay separate.
 * Unknown and not-measured usage counters are kept as they are and never read as zero.
 *
 * @param decisionRef the decision identity
 * @param stageKind the routed stage kind
 * @param cutoff the decision cutoff
 * @param stateCutoff the {@code processedCutoff} of the preference snapshot the decision read. The cutoff is inclusive,
 *        so it is at most {@code cutoff}: the adaptive arm's state is the state as of the cutoff (it equals
 *        {@code cutoff}), while the static and derived-not-consumed arms read no learned state (0). No outcome
 *        observed after the cutoff reached the decision
 * @param arm the arm
 * @param kind decision kind
 * @param route the advised route, when selected
 * @param basis the selection basis, when selected
 * @param abstainReason why the policy abstained, when it did
 * @param coverage whether an outcome is known for the advised route
 * @param acceptance derived acceptance, when observed
 * @param verdict learning eligibility, when observed
 * @param provenFailures mandatory dimensions proven to fail, when observed
 * @param attempts whole-chain attempts, 0 when not observed
 * @param stageUsage the routed stage's usage counters, when observed
 * @param usageDeclaration the routed stage's usage declaration, when observed
 * @param chainCost the whole chain's hypothetical cost to acceptance, when observed
 * @param comparison what a direct comparison requires to match
 */
public record ReplaySample(
        DecisionRef decisionRef,
        String stageKind,
        long cutoff,
        long stateCutoff,
        ReplayArm arm,
        DecisionKind kind,
        Optional<RouteKey> route,
        Optional<Basis> basis,
        Optional<AbstainReason> abstainReason,
        Coverage coverage,
        Optional<DerivedAcceptance> acceptance,
        Optional<EligibilityVerdict> verdict,
        List<QualityDimension> provenFailures,
        int attempts,
        List<UsageCounter> stageUsage,
        Optional<UsageDeclaration> usageDeclaration,
        Optional<ReplayCost> chainCost,
        ComparisonKey comparison) {

    /** What the policy returned. */
    public enum DecisionKind {
        SELECTED,
        ABSTAINED,
        NO_ELIGIBLE_ROUTE
    }

    /** Whether an outcome is known for the advised route. */
    public enum Coverage {
        /** The advised route's outcome is known. */
        OBSERVED,
        /** Only the logged route's outcome exists and the replay advised another: no counterfactual is invented. */
        MISSING_COUNTERFACTUAL,
        /** The advised route executed in the fixture, but no outcome was observed or exported for it. */
        UNOBSERVED,
        /** The policy abstained or nothing was eligible, so nothing executed. */
        NOT_EXECUTED
    }

    /** Validates the fields. */
    public ReplaySample {
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        Objects.requireNonNull(arm, "arm must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(basis, "basis must not be null");
        Objects.requireNonNull(abstainReason, "abstainReason must not be null");
        Objects.requireNonNull(coverage, "coverage must not be null");
        Objects.requireNonNull(acceptance, "acceptance must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        provenFailures = List.copyOf(provenFailures);
        stageUsage = List.copyOf(stageUsage);
        Objects.requireNonNull(usageDeclaration, "usageDeclaration must not be null");
        Objects.requireNonNull(chainCost, "chainCost must not be null");
        Objects.requireNonNull(comparison, "comparison must not be null");
        boolean selected = kind == DecisionKind.SELECTED;
        if (selected != route.isPresent() || selected != basis.isPresent()) {
            throw new IllegalArgumentException("a route and a basis are reported exactly for a selection");
        }
        if ((kind == DecisionKind.ABSTAINED) != abstainReason.isPresent()) {
            throw new IllegalArgumentException("an abstention reason is reported exactly for an abstention");
        }
        if (selected == (coverage == Coverage.NOT_EXECUTED)) {
            throw new IllegalArgumentException("only a selection executes, and every selection executes");
        }
        boolean observed = coverage == Coverage.OBSERVED;
        if (observed != acceptance.isPresent() || observed != verdict.isPresent()
                || observed != usageDeclaration.isPresent() || observed != chainCost.isPresent()) {
            throw new IllegalArgumentException("an acceptance, a verdict, a usage declaration and a chain cost are"
                    + " reported exactly for an observed outcome");
        }
        if (observed ? attempts < 1
                : attempts != 0 || !provenFailures.isEmpty() || !stageUsage.isEmpty()) {
            throw new IllegalArgumentException("an observed outcome has at least one attempt; anything else has no"
                    + " attempt, failure or usage");
        }
    }

    /**
     * Whether the routed stage's usage leaves the chain uncovered: a {@code MEASURED} declaration with no counter at
     * all (unrecorded) or with an unknown or not-measured counter. A declared {@code NO_BILLABLE_USAGE} is complete.
     * Not-observed samples report no usage and are never incomplete.
     */
    public boolean stageUsageIncomplete() {
        return usageDeclaration.orElse(null) == UsageDeclaration.MEASURED && (stageUsage.isEmpty()
                || stageUsage.stream().anyMatch(c -> !(c.value() instanceof ResourceValue.Known)));
    }

    /** Whether the advised route reached a validated acceptance. */
    public boolean validatedAccepted() {
        return acceptance.orElse(null) == DerivedAcceptance.VALIDATED_ACCEPTED;
    }

    /** The decision projection two arms are compared by: kind, route, basis and abstention reason. */
    public String decisionProjection() {
        return kind + "|" + route.map(RouteKey::toString).orElse("-") + "|" + basis.map(Enum::name).orElse("-") + "|"
                + abstainReason.map(Enum::name).orElse("-");
    }
}
