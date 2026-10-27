package monada.neuron.routing.receipt;

import monada.neuron.routing.Basis;
import monada.neuron.routing.Placement;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.receipt.ReceiptFixtures.Base;
import monada.neuron.routing.receipt.ReceiptFixtures.Parts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptSensitivityTest {

    private static final RouteKey SUB_A_2 = new RouteKey("sub-a", 2);

    /**
     * One change per leaf of the layout. A change is applied to every base it is valid for; the decision records tie
     * some leaves together (a compatible state's mapping must match the parameters and the cohort, a basis follows the
     * winner's placement), so those leaves change together as one value.
     */
    private static Map<String, Consumer<Parts>> changes() {
        Map<String, Consumer<Parts>> changes = new LinkedHashMap<>();
        changes.put("decisionRef.scopeId", p -> p.scopeId = "scope-other");
        changes.put("decisionRef.taskId", p -> p.taskId = "task-18");
        changes.put("decisionRef.executionId", p -> p.executionId = "exec-2");
        changes.put("decisionRef.attemptId", p -> p.attemptId = "att-2");
        changes.put("decisionRef.stageId", p -> p.stageId = "s-review");
        changes.put("decisionRef.requestOrdinal", p -> p.requestOrdinal++);
        changes.put("catalogVersion", p -> p.catalogVersion = "cat-demo-8");
        changes.put("policyId", p -> p.policyId = "route-other");
        changes.put("policyVersion", p -> p.policyVersion = "pv-2");
        changes.put("cutoff", p -> p.cutoff++);
        changes.put("state.scopeId", p -> p.stateScopeId = "state-other");
        changes.put("state.featureSchemaVersion", p -> p.stateSchema = "task-features/2");
        changes.put("state.evaluationPolicyId", p -> p.evaluationPolicyId = "forge-other");
        changes.put("state.evaluationPolicyVersion", p -> p.evaluationPolicyVersion = "ev-2");
        changes.put("state.mappingVersion", p -> p.stateMapping = "routing-state/2");
        changes.put("state.processedCutoff", p -> p.processedCutoff++);
        changes.put("state.producedByPolicyId", p -> p.producedById = "producer-2");
        changes.put("state.producedByPolicyVersion", p -> p.producedByVersion = "ppv-2");
        changes.put("validation.reasons", p -> p.mismatches =
                List.of(StateMismatch.SCOPE_MISMATCH, StateMismatch.MAPPING_VERSION_MISMATCH));
        changes.put("parameters.minSupportingObservations", p -> p.minSupport++);
        changes.put("parameters.maxAutoSelectTier present", p -> p.maxTier = OptionalInt.of(2));
        changes.put("parameters.maxAutoSelectTier absent", p -> p.maxTier = OptionalInt.empty());
        changes.put("parameters.maxAutoSelectTier value", p -> p.maxTier = p.maxTier.isPresent()
                ? OptionalInt.of(p.maxTier.getAsInt() + 1) : p.maxTier);
        changes.put("parameters.objective present", p -> p.objective =
                Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE)));
        changes.put("parameters.objective absent", p -> p.objective = Optional.empty());
        changes.put("parameters.objective.dimension", p -> p.objective =
                p.objective.map(o -> new ResourceObjective("latency", o.direction())));
        changes.put("parameters.objective.direction", p -> p.objective =
                p.objective.map(o -> new ResourceObjective(o.dimension(), ResourceObjective.Direction.MAXIMIZE)));
        changes.put("parameters.stateMappingVersion", p -> p.parameterMapping = "routing-state/2");
        changes.put("parameters.stateFeatureSchemaVersion", p -> p.parameterSchema = "task-features/2");
        changes.put("mapping version everywhere", p -> {
            p.stateMapping = "routing-state/2";
            p.parameterMapping = "routing-state/2";
            p.cohortMapping = "routing-state/2";
        });
        changes.put("feature schema version everywhere", p -> {
            p.stateSchema = "task-features/2";
            p.parameterSchema = "task-features/2";
            p.cohortSchema = "task-features/2";
        });
        changes.put("stageKind absent", p -> p.stageKind = Optional.empty());
        changes.put("stageKind value", p -> p.stageKind = Optional.of("review"));
        changes.put("selected route.routeVersion", p -> {
            p.route = SUB_A_2;
            p.candidates = p.candidates.stream().map(c -> c.key().equals(ReceiptFixtures.SUB_A)
                    ? new RankedCandidate(c.rank(), SUB_A_2, c.placement()) : c).toList();
        });
        changes.put("executionMode", p -> p.executionMode = "local");
        changes.put("basis with the winning placement", p -> {
            p.basis = Basis.HOST_PRIORITY;
            p.candidates = p.candidates.stream().map(c -> c.rank() < 2
                    ? new RankedCandidate(c.rank(), c.key(), Placement.FALLBACK_PRIORITY) : c).toList();
        });
        changes.put("overflowUsed", p -> p.overflowUsed = true);
        changes.put("candidate placement", p -> p.candidates = p.candidates.stream().map(c -> c.rank() == 2
                ? new RankedCandidate(2, c.key(), Placement.FALLBACK_PRIORITY) : c).toList());
        changes.put("abstain candidate placement", p -> p.tradeoffCandidates = p.tradeoffCandidates.stream()
                .map(c -> new RankedCandidate(c.rank(), c.key(), Placement.TIER)).toList());
        changes.put("candidatesTruncated", p -> p.truncated = true);
        changes.put("rules", p -> {
            p.applied = List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.LEARNED_PREFERENCE,
                    RoutingRule.ROUTE_ORDER);
            p.skipped = List.of(RoutingRule.RESOURCE_OBJECTIVE);
        });
        changes.put("exclusion routeId", p -> p.exclusions = List.of(exclusion(new RouteKey("ex-went", 3),
                EligibilityReason.MISSING_CAPABILITY, List.of(EligibilityReason.REQUIRED_LIMIT_UNKNOWN))));
        changes.put("exclusion routeVersion", p -> p.exclusions = List.of(exclusion(new RouteKey("ex-gone", 4),
                EligibilityReason.MISSING_CAPABILITY, List.of(EligibilityReason.REQUIRED_LIMIT_UNKNOWN))));
        changes.put("exclusion primaryReason", p -> p.exclusions = List.of(exclusion(new RouteKey("ex-gone", 3),
                EligibilityReason.ROUTE_UNAVAILABLE, List.of(EligibilityReason.REQUIRED_LIMIT_UNKNOWN))));
        changes.put("exclusion additionalReasons", p -> p.exclusions = List.of(exclusion(new RouteKey("ex-gone", 3),
                EligibilityReason.MISSING_CAPABILITY, List.of())));
        changes.put("cohort.cohortBucket", p -> p.cohortBucket = "SMALL");
        changes.put("cohort.bucketMappingVersion", p -> p.bucketMapping = "b/2");
        changes.put("abstain eligible", p -> p.eligible = List.of(ReceiptFixtures.SUB_A, ReceiptFixtures.API_X));
        return changes;
    }

    private static EligibilityReport.Exclusion exclusion(RouteKey key, EligibilityReason primary,
            List<EligibilityReason> additional) {
        return new EligibilityReport.Exclusion(key, primary, additional);
    }

    // covers: AC-3
    @Test
    void everyLeafChangesTheBytesAndTheDigest() {
        List<String> unused = new ArrayList<>();
        for (Map.Entry<String, Consumer<Parts>> change : changes().entrySet()) {
            int applied = 0;
            for (Base base : Base.values()) {
                RoutingDecision original = base.decision();
                Parts parts = base.parts();
                change.getValue().accept(parts);
                RoutingDecision changed;
                try {
                    changed = base.build(parts);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (changed.equals(original)) {
                    continue;
                }
                AdviceReceipt before = AdviceReceipt.of(original);
                AdviceReceipt after = AdviceReceipt.of(changed);
                String label = change.getKey() + " on " + base;
                assertFalse(Arrays.equals(before.bytes(), after.bytes()), label);
                assertNotEquals(before.sha256(), after.sha256(), label);
                applied++;
            }
            if (applied == 0) {
                unused.add(change.getKey());
            }
        }
        assertEquals(List.of(), unused, "changes valid on no base");
    }

    // covers: AC-3
    @Test
    void theVariantAndTheAbstainReasonChangeTheBytesAndTheDigest() {
        List<AdviceReceipt> receipts = Arrays.stream(Base.values()).map(base -> AdviceReceipt.of(base.decision()))
                .toList();

        assertEquals(receipts.size(), receipts.stream().distinct().count());
        assertEquals(receipts.size(), receipts.stream().map(AdviceReceipt::sha256).distinct().count());
        // the variant byte follows the tag
        int variant = ReceiptFixtures.str(AdviceReceipt.FORMAT).length;
        assertEquals(List.of(1, 1, 2, 2, 3), receipts.stream().map(r -> (int) r.bytes()[variant]).toList());
    }

    // covers: AC-3
    @Test
    void everyBaseIsCoveredByAtLeastOneChange() {
        for (Base base : Base.values()) {
            boolean covered = false;
            for (Consumer<Parts> change : changes().values()) {
                Parts parts = base.parts();
                change.accept(parts);
                try {
                    covered |= !base.build(parts).equals(base.decision());
                } catch (IllegalArgumentException e) {
                    // not valid on this base
                }
            }
            assertTrue(covered, base.name());
        }
    }
}
