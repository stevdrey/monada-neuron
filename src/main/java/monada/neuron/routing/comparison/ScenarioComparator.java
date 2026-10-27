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

    private final TrustedRoutingAdvice advice = new TrustedRoutingAdvice();

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
        RoutingDecision beforeDecision = advice.decide(before.policy(), before.profile(), before.input());
        RoutingDecision afterDecision = advice.decide(after.policy(), after.profile(), after.input());
        List<ScenarioDifference> differences = differences(before.input(), after.input(), beforeDecision, afterDecision);
        if (differences.stream().anyMatch(ScenarioDifference::breaksComparability)) {
            return new ScenarioComparison.Incomparable(beforeDecision, afterDecision, differences);
        }
        var delta = ScenarioDelta.between(beforeDecision, afterDecision,
                eligibilityChanges(before.input(), beforeDecision, after.input(), afterDecision));
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
        mark(found, ScenarioDifference.OVERFLOW_PERMISSION, a.overflowPermitted() != b.overflowPermitted());
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
     * Walks both catalogs in canonical route order. Each decision's exclusions are a canonically ordered subset of its
     * own catalog, so one cursor per exclusion list pairs every route with its standing without a lookup.
     */
    private List<EligibilityChange> eligibilityChanges(
            RoutingInput before, RoutingDecision beforeDecision, RoutingInput after, RoutingDecision afterDecision) {
        List<CatalogEntry> beforeEntries = before.catalog().entries();
        List<CatalogEntry> afterEntries = after.catalog().entries();
        List<EligibilityReport.Exclusion> beforeExcluded = beforeDecision.exclusions();
        List<EligibilityReport.Exclusion> afterExcluded = afterDecision.exclusions();
        var changes = new ArrayList<EligibilityChange>();
        int beforeEntry = 0;
        int afterEntry = 0;
        int beforeExclusion = 0;
        int afterExclusion = 0;
        while (beforeEntry < beforeEntries.size() || afterEntry < afterEntries.size()) {
            RouteKey beforeKey = beforeEntry < beforeEntries.size() ? beforeEntries.get(beforeEntry).key() : null;
            RouteKey afterKey = afterEntry < afterEntries.size() ? afterEntries.get(afterEntry).key() : null;
            int order = beforeKey == null ? 1 : afterKey == null ? -1 : beforeKey.compareTo(afterKey);
            RouteKey key = order <= 0 ? beforeKey : afterKey;
            RouteStanding was = new RouteStanding.NotInCatalog();
            RouteStanding now = new RouteStanding.NotInCatalog();
            if (order <= 0) {
                boolean excluded = beforeExclusion < beforeExcluded.size()
                        && beforeExcluded.get(beforeExclusion).key().equals(key);
                was = excluded ? new RouteStanding.Excluded(beforeExcluded.get(beforeExclusion++))
                        : new RouteStanding.Eligible();
                beforeEntry++;
            }
            if (order >= 0) {
                boolean excluded = afterExclusion < afterExcluded.size()
                        && afterExcluded.get(afterExclusion).key().equals(key);
                now = excluded ? new RouteStanding.Excluded(afterExcluded.get(afterExclusion++))
                        : new RouteStanding.Eligible();
                afterEntry++;
            }
            if (!was.equals(now)) {
                changes.add(new EligibilityChange(key, was, now));
            }
        }
        return changes;
    }
}
