package monada.neuron.routing.comparison;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.TrustedRoutingAdvice;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Decides two caller-provided scenarios with their own trusted configuration and reports them side by side (issue
 * #99). Each side runs {@link TrustedRoutingAdvice} once, so the policy decides at most twice; the delta is one merge
 * pass over the two canonical catalog orders, O(R) for at most {@value ScenarioDelta#MAX_ELIGIBILITY_CHANGES} routes,
 * and nothing is sorted or ranked again.
 *
 * <p>The comparator holds no per-call state and no cache. It never changes a scenario, applies a preview, reaches
 * routing state or estimates spend.
 */
public final class ScenarioComparator {

    /** Field-less, so one instance serves every absent route. */
    private static final RouteStanding NOT_IN_CATALOG = new RouteStanding.NotInCatalog();

    private final TrustedRoutingAdvice advice = new TrustedRoutingAdvice();
    private final DeltaDerivation derivation = new DeltaDerivation();

    /**
     * Decides both scenarios and compares them.
     *
     * @param before the scenario the host has now
     * @param after the scenario the host considers
     * @return {@link ScenarioComparison.Incomparable} when the scenarios differ in an input that breaks comparability,
     *         otherwise {@link ScenarioComparison.Compared}
     * @throws IllegalStateException if either side's decision fails trusted verification; no partial preview exists
     */
    public ScenarioComparison compare(RoutingScenario before, RoutingScenario after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        TrustedRoutingAdvice.Verified beforeSide = advice.decide(before.policy(), before.profile(), before.input());
        TrustedRoutingAdvice.Verified afterSide = advice.decide(after.policy(), after.profile(), after.input());
        RoutingDecision beforeDecision = beforeSide.decision();
        RoutingDecision afterDecision = afterSide.decision();
        List<ScenarioDifference> differences = differences(before.input(), after.input(), beforeDecision, afterDecision);
        if (differences.stream().anyMatch(ScenarioDifference::breaksComparability)) {
            return new ScenarioComparison.Incomparable(beforeDecision, afterDecision, differences);
        }
        var delta = derivation.between(beforeDecision, afterDecision,
                eligibilityChanges(before.input(), beforeSide, after.input(), afterSide));
        return new ScenarioComparison.Compared(beforeDecision, afterDecision, differences, delta);
    }

    private List<ScenarioDifference> differences(
            RoutingInput before, RoutingInput after, RoutingDecision beforeDecision, RoutingDecision afterDecision) {
        RoutingRequest a = before.request();
        RoutingRequest b = after.request();
        var found = EnumSet.noneOf(ScenarioDifference.class);
        mark(found, ScenarioDifference.SCOPE, !a.scopeId().equals(b.scopeId()));
        mark(found, ScenarioDifference.TASK, !a.taskId().equals(b.taskId()));
        mark(found, ScenarioDifference.STAGE_KIND, !a.stageKind().equals(b.stageKind()));
        mark(found, ScenarioDifference.FEATURE_SCHEMA,
                !a.features().schemaVersion().equals(b.features().schemaVersion()));
        mark(found, ScenarioDifference.EVALUATION_POLICY, !a.evaluationPolicyId().equals(b.evaluationPolicyId())
                || !a.evaluationPolicyVersion().equals(b.evaluationPolicyVersion()));
        mark(found, ScenarioDifference.CUTOFF, a.cutoff() != b.cutoff());
        mark(found, ScenarioDifference.CATALOG, !before.catalog().equals(after.catalog()));
        mark(found, ScenarioDifference.REQUIREMENTS, !a.requirements().equals(b.requirements()));
        mark(found, ScenarioDifference.OVERFLOW_ADMISSION, a.overflowPermitted() != b.overflowPermitted());
        mark(found, ScenarioDifference.FEATURES, !a.features().equals(b.features()));
        mark(found, ScenarioDifference.REQUEST_CONTEXT, !a.sourceFingerprint().equals(b.sourceFingerprint())
                || !a.contextFingerprint().equals(b.contextFingerprint())
                || !a.constraintsFingerprint().equals(b.constraintsFingerprint()));
        mark(found, ScenarioDifference.REQUEST_IDENTITY, !a.executionId().equals(b.executionId())
                || !a.attemptId().equals(b.attemptId()) || !a.stageId().equals(b.stageId())
                || a.requestOrdinal() != b.requestOrdinal());
        mark(found, ScenarioDifference.PREFERENCE, !before.preference().equals(after.preference()));
        mark(found, ScenarioDifference.POLICY_IDENTITY,
                !beforeDecision.provenance().policyId().equals(afterDecision.provenance().policyId())
                        || !beforeDecision.provenance().policyVersion().equals(afterDecision.provenance().policyVersion()));
        mark(found, ScenarioDifference.POLICY_PARAMETERS,
                !beforeDecision.provenance().parameters().equals(afterDecision.provenance().parameters()));
        return List.copyOf(found);
    }

    private void mark(EnumSet<ScenarioDifference> found, ScenarioDifference difference, boolean differs) {
        if (differs) {
            found.add(difference);
        }
    }

    /**
     * Walks both catalogs in canonical route order. Each side's eligible routes and its decision's exclusions are
     * disjoint, canonically ordered subsets of its own catalog that together cover it (the validator checked the
     * exclusions against the same report), so one cursor per list pairs every route with its standing without a lookup.
     */
    private List<EligibilityChange> eligibilityChanges(RoutingInput before, TrustedRoutingAdvice.Verified beforeSide,
            RoutingInput after, TrustedRoutingAdvice.Verified afterSide) {
        var was = new Standings(before, beforeSide);
        var now = new Standings(after, afterSide);
        var changes = new ArrayList<EligibilityChange>();
        while (was.hasNext() || now.hasNext()) {
            int order = !was.hasNext() ? 1 : !now.hasNext() ? -1 : was.key().compareTo(now.key());
            RouteKey key = order <= 0 ? was.key() : now.key();
            RouteStanding wasStanding = order <= 0 ? was.next() : NOT_IN_CATALOG;
            RouteStanding nowStanding = order >= 0 ? now.next() : NOT_IN_CATALOG;
            if (!wasStanding.equals(nowStanding)) {
                changes.add(new EligibilityChange(key, wasStanding, nowStanding));
            }
        }
        return changes;
    }

    /** A cursor over one side's catalog, yielding each route's standing in canonical route order. */
    private static final class Standings {

        private final List<CatalogEntry> entries;
        private final List<EligibilityReport.EligibleRoute> eligible;
        private final List<EligibilityReport.Exclusion> excluded;
        private int entry;
        private int eligibleAt;
        private int excludedAt;

        Standings(RoutingInput input, TrustedRoutingAdvice.Verified side) {
            this.entries = input.catalog().entries();
            this.eligible = side.report().eligible();
            this.excluded = side.decision().exclusions();
        }

        boolean hasNext() {
            return entry < entries.size();
        }

        RouteKey key() {
            return entries.get(entry).key();
        }

        RouteStanding next() {
            RouteKey key = entries.get(entry++).key();
            if (excludedAt < excluded.size() && excluded.get(excludedAt).key().equals(key)) {
                return new RouteStanding.Excluded(excluded.get(excludedAt++));
            }
            if (eligibleAt < eligible.size() && eligible.get(eligibleAt).key().equals(key)) {
                return new RouteStanding.Eligible(eligible.get(eligibleAt++));
            }
            throw new IllegalStateException("the verified report neither admits nor excludes catalog route " + key);
        }
    }
}
