package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static monada.neuron.routing.RoutingFixtures.PARAMETERS;
import static monada.neuron.routing.RoutingFixtures.demo;
import static monada.neuron.routing.RoutingFixtures.demoWith;
import static monada.neuron.routing.RoutingFixtures.empty;
import static monada.neuron.routing.RoutingFixtures.request;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code route-lex/1} verifier in trusted mode: it checks a decision against host-supplied parameters and state
 * definition, not against what the decision records about itself.
 */
class TrustedLexicographicVerifierTest {

    private static final RoutingStateDefinition DEFINITION = RoutingStateDefinition.reference();
    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();
    private final LexicographicRoutingPolicy reference = LexicographicRoutingPolicy.reference();
    private final LexicographicDecisionVerifier trusted =
            LexicographicDecisionVerifier.trusted(reference.parameters(), DEFINITION);

    private void verify(RoutingDecisionVerifier verifier, RoutingDecision decision, RoutingInput input) {
        verifier.verify(decision, input, filter.evaluate(input.request(), input.catalog()));
    }

    private String rejected(RoutingDecision decision, RoutingInput input) {
        return rejected(trusted, decision, input);
    }

    private String rejected(RoutingDecisionVerifier verifier, RoutingDecision decision, RoutingInput input) {
        return assertThrows(IllegalStateException.class, () -> verify(verifier, decision, input)).getMessage();
    }

    private static RoutingInput input(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference) {
        return new RoutingInput(request, catalog, preference);
    }

    private RoutingDecision.Selected selected(RoutingInput input) {
        return assertInstanceOf(RoutingDecision.Selected.class,
                reference.decide(input.request(), input.catalog(), input.preference()));
    }

    private static Provenance withParameters(Provenance p, PolicyParameters parameters) {
        return new Provenance(p.decisionRef(), p.catalogVersion(), p.policyId(), p.policyVersion(), p.cutoff(),
                p.state(), p.validation(), parameters);
    }

    private static Provenance withIdentity(Provenance p, String id, String version) {
        return new Provenance(p.decisionRef(), p.catalogVersion(), id, version, p.cutoff(), p.state(),
                p.validation(), p.parameters());
    }

    private static RoutingDecision.Selected withProvenance(RoutingDecision.Selected s, Provenance provenance) {
        return new RoutingDecision.Selected(provenance, s.route(), s.executionMode(), s.basis(), s.overflowUsed(),
                s.candidates(), s.candidatesTruncated(), s.rulesApplied(), s.rulesSkipped(), s.exclusions(),
                s.cohortBinding());
    }

    private static RoutingDecision.Selected withCohort(RoutingDecision.Selected s, CohortBinding binding) {
        return new RoutingDecision.Selected(s.provenance(), s.route(), s.executionMode(), s.basis(),
                s.overflowUsed(), s.candidates(), s.candidatesTruncated(), s.rulesApplied(), s.rulesSkipped(),
                s.exclusions(), binding);
    }

    @Test
    void everyReferenceDecisionVariantIsAcceptedAgainstTheHostsConfiguration() {
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));
        verify(trusted, reference.decide(java, demo(), cold.preference()), cold);

        // Truncated explanation: 32 eligible routes, 8 ranked, with a configured objective and 256 cohorts.
        var objective = new LexicographicRoutingPolicy(DEFINITION, 3, OptionalInt.empty(),
                Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE)));
        var maxRequest = request(List.of("java"), true);
        var max = input(maxRequest, RoutingFixtures.maximumCatalog(), RoutingFixtures.maximumPreference());
        var truncated = assertInstanceOf(RoutingDecision.Selected.class,
                objective.decide(maxRequest, max.catalog(), max.preference()));
        assertTrue(truncated.candidatesTruncated());
        verify(LexicographicDecisionVerifier.trusted(objective.parameters(), DEFINITION), truncated, max);

        // Unknown resource estimates are never cheapest: the objective is skipped for the tied group.
        var unknown = input(java, demoWith(List.of(RoutingFixtures.cost(RoutingFixtures.SUB_A,
                new ResourceValue.Unknown()))), empty(java));
        verify(LexicographicDecisionVerifier.trusted(objective.parameters(), DEFINITION),
                objective.decide(java, unknown.catalog(), unknown.preference()), unknown);

        // Tier cap: only api-x (tier 2) is eligible, above the cap of 1.
        var capped = new LexicographicRoutingPolicy(DEFINITION, 3, OptionalInt.of(1), Optional.empty());
        var overflow = request(List.of("long-context"), true);
        var overflowInput = input(overflow, demo(), empty(overflow));
        var cap = capped.decide(overflow, demo(), overflowInput.preference());
        assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED,
                assertInstanceOf(RoutingDecision.Abstain.class, cap).reason());
        verify(LexicographicDecisionVerifier.trusted(capped.parameters(), DEFINITION), cap, overflowInput);

        // State-incompatible abstention: an older mapping version, with no cohort computed.
        var stale = new RoutingPreference(java.scopeId(), java.features().schemaVersion(), java.evaluationPolicyId(),
                java.evaluationPolicyVersion(), "routing-state/0", "route-lex", "1", 0L, List.of());
        var staleInput = input(java, demo(), stale);
        var abstain = assertInstanceOf(RoutingDecision.Abstain.class,
                reference.decide(java, demo(), stale));
        assertEquals(AbstainReason.STATE_INCOMPATIBLE, abstain.reason());
        verify(trusted, abstain, staleInput);

        // No eligible route: the state is not consulted and no cohort is derived.
        var impossible = request(List.of("nonexistent"), false);
        var none = input(impossible, demo(), empty(impossible));
        verify(trusted, assertInstanceOf(RoutingDecision.NoEligibleRoute.class,
                reference.decide(impossible, demo(), none.preference())), none);
    }

    @Test
    void fabricatedPolicyParametersAreRejectedEvenWhenTheyAreInternallyConsistent() {
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));
        var honest = selected(cold);
        var fabricated = new PolicyParameters(1, OptionalInt.empty(), Optional.empty(), "routing-state/1",
                "task-features/1");
        var forged = withProvenance(honest, withParameters(honest.provenance(), fabricated));

        assertTrue(rejected(forged, cold).contains("differ from the trusted configuration"));
        // The self-reported mode checks the decision against what it records about itself, so it accepts the forgery.
        assertDoesNotThrow(() -> verify(new SelfReportedLexicographicVerifier(), forged, cold));
    }

    @Test
    void aTierCapOrObjectiveTheHostDidNotConfigureIsRejected() {
        var java = request(List.of("long-context"), true);
        var cold = input(java, demo(), empty(java));
        var capped = new LexicographicRoutingPolicy(DEFINITION, 3, OptionalInt.of(1), Optional.empty());
        var abstention = capped.decide(java, demo(), cold.preference());

        // The host never configured a cap, so a policy that abstains on one is not what the host composed.
        assertTrue(rejected(abstention, cold).contains("differ from the trusted configuration"));
        // A host cap of 1 rejects a policy that records none and selects the tier-2 route.
        var hostCap = LexicographicDecisionVerifier.trusted(capped.parameters(), DEFINITION);
        assertTrue(rejected(hostCap, selected(cold), cold).contains("differ from the trusted configuration"));
    }

    @Test
    void aFabricatedMappingVersionInTheRecordedParametersIsRejected() {
        var java = request(List.of("java"), false);
        var stale = new RoutingPreference(java.scopeId(), java.features().schemaVersion(), java.evaluationPolicyId(),
                java.evaluationPolicyVersion(), "routing-state/0", "route-lex", "1", 0L, List.of());
        var staleInput = input(java, demo(), stale);
        var honest = assertInstanceOf(RoutingDecision.Abstain.class, reference.decide(java, demo(), stale));
        var other = new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "routing-state/0",
                "task-features/1");
        var forged = new RoutingDecision.Abstain(withParameters(honest.provenance(), other), honest.reason(),
                honest.eligible(), honest.candidates(), honest.candidatesTruncated(), honest.exclusions());

        assertTrue(rejected(forged, staleInput).contains("differ from the trusted configuration"));
    }

    @Test
    void aFabricatedCohortBucketOrBucketMappingVersionIsRejectedBeforeTheRanking() {
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));
        var honest = selected(cold);
        var binding = honest.cohortBinding();

        var bucket = withCohort(honest, new CohortBinding("S", binding.bucketMappingVersion(),
                binding.mappingVersion(), binding.featureSchemaVersion()));
        var mapping = withCohort(honest, new CohortBinding(binding.cohortBucket(), "change-size/9",
                binding.mappingVersion(), binding.featureSchemaVersion()));

        assertTrue(rejected(bucket, cold).contains("trusted mapping derives"));
        assertTrue(rejected(mapping, cold).contains("trusted mapping derives"));
        // Self-reported verification cannot tell: it ranks with whatever bucket the decision names.
        assertDoesNotThrow(() -> verify(new SelfReportedLexicographicVerifier(), bucket, cold));
    }

    @Test
    void theCohortBucketIsDerivedFromTheRequestFeaturesWithTheTrustedMapping() {
        var features = TaskFeatures.builder().stageKind("implement").changeSize(10).build();
        var small = request(features, List.of("java"), false, 50L);
        var smallInput = input(small, demo(), empty(small));
        var honest = selected(smallInput);
        assertEquals("S", honest.cohortBinding().cohortBucket());
        verify(trusted, honest, smallInput);

        // Claiming the cold-start cohort for a small change is a fabricated bucket.
        var forged = withCohort(honest, RoutingFixtures.COLD_COHORT);
        assertTrue(rejected(forged, smallInput).contains("trusted mapping derives"));
        // A host whose mapping buckets that change as "M" rejects the reference policy's honest "S".
        var otherMapping = new RoutingStateDefinition(DEFINITION.mappingVersion(),
                new ChangeSizeCohortMapping("change-size/1", 5, 500));
        var strict = LexicographicDecisionVerifier.trusted(
                new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), otherMapping.mappingVersion(),
                        otherMapping.cohortMapping().featureSchemaVersion()), otherMapping);
        assertTrue(rejected(strict, honest, smallInput).contains("trusted mapping derives"));
    }

    @Test
    void aFabricatedCohortOnATierCapAbstentionIsRejected() {
        var capped = new LexicographicRoutingPolicy(DEFINITION, 3, OptionalInt.of(1), Optional.empty());
        var hostVerifier = LexicographicDecisionVerifier.trusted(capped.parameters(), DEFINITION);
        var overflow = request(List.of("long-context"), true);
        var in = input(overflow, demo(), empty(overflow));
        var honest = assertInstanceOf(RoutingDecision.Abstain.class, capped.decide(overflow, demo(), in.preference()));
        var binding = honest.cohortBinding().orElseThrow();

        for (var forged : List.of(
                new CohortBinding("L", binding.bucketMappingVersion(), binding.mappingVersion(),
                        binding.featureSchemaVersion()),
                new CohortBinding(binding.cohortBucket(), "change-size/9", binding.mappingVersion(),
                        binding.featureSchemaVersion()))) {
            var abstention = new RoutingDecision.Abstain(honest.provenance(), honest.reason(), honest.eligible(),
                    honest.candidates(), honest.candidatesTruncated(), honest.exclusions(), Optional.of(forged));
            assertTrue(rejected(hostVerifier, abstention, in).contains("trusted mapping derives"), forged.toString());
        }
        verify(hostVerifier, honest, in);
    }

    @Test
    void aTrustedMappingThatProducesAnInvalidBucketIsRejectedAsAnIllegalState() {
        var invalid = new CohortMapping() {
            @Override
            public String bucketMappingVersion() {
                return "b/1";
            }

            @Override
            public String featureSchemaVersion() {
                return "task-features/1";
            }

            @Override
            public String bucketOf(TaskFeatures features) {
                return "x".repeat(200);
            }
        };
        var definition = new RoutingStateDefinition("routing-state/1", invalid);
        var hostVerifier = LexicographicDecisionVerifier.trusted(
                new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "routing-state/1", "task-features/1"),
                definition);
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));

        assertTrue(rejected(hostVerifier, selected(cold), cold).contains("invalid cohort binding"));
    }

    @Test
    void aVerifierCanBeBoundToAnotherPolicyIdentity() {
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));
        var audit = LexicographicDecisionVerifier.trusted("route-lex-audit", "1", reference.parameters(), DEFINITION);

        // The reference decision is stamped route-lex/1, which this verifier does not check.
        assertTrue(rejected(audit, selected(cold), cold).contains("route-lex-audit/1"));
        assertThrows(IllegalArgumentException.class,
                () -> LexicographicDecisionVerifier.trusted(" ", "1", reference.parameters(), DEFINITION));
    }

    @Test
    void anotherPolicyIdentityIsRejected() {
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));
        var honest = selected(cold);

        var otherId = withProvenance(honest, withIdentity(honest.provenance(), "other-policy", "1"));
        var otherVersion = withProvenance(honest, withIdentity(honest.provenance(), "route-lex", "2"));

        assertTrue(rejected(otherId, cold).contains("other-policy/1"));
        assertTrue(rejected(otherVersion, cold).contains("route-lex/2"));
    }

    @Test
    void stateMismatchesAreDerivedFromTheTrustedDefinitionInBothDirections() {
        var java = request(List.of("java"), false);
        // The preference was built under mapping 0, so the host's definition implies MAPPING_VERSION_MISMATCH only.
        var stale = new RoutingPreference(java.scopeId(), java.features().schemaVersion(), java.evaluationPolicyId(),
                java.evaluationPolicyVersion(), "routing-state/0", "route-lex", "1", 0L, List.of());
        var staleInput = input(java, demo(), stale);
        var eligible = List.of(RoutingFixtures.SUB_A, RoutingFixtures.SUB_B);
        var exclusions = filter.evaluate(java, demo()).excluded();

        RoutingDecision omitted = incompatible(stale, java, List.of(StateMismatch.SCOPE_MISMATCH), eligible,
                exclusions);
        RoutingDecision spurious = incompatible(stale, java,
                List.of(StateMismatch.MAPPING_VERSION_MISMATCH, StateMismatch.PROCESSED_CUTOFF_NEWER), eligible,
                exclusions);
        RoutingDecision exact = incompatible(stale, java, List.of(StateMismatch.MAPPING_VERSION_MISMATCH), eligible,
                exclusions);

        assertTrue(rejected(omitted, staleInput).contains("implies"));
        assertTrue(rejected(spurious, staleInput).contains("implies"));
        verify(trusted, exact, staleInput);
    }

    @Test
    void aFeatureSchemaMismatchTheTrustedDefinitionImpliesCannotBeOmitted() {
        var java = request(List.of("java"), false);
        // The host's cohort mapping was built for another schema, so every admission must report FEATURE_SCHEMA_MISMATCH.
        var otherSchemaMapping = new CohortMapping() {
            @Override
            public String bucketMappingVersion() {
                return "b/0";
            }

            @Override
            public String featureSchemaVersion() {
                return "task-features/0";
            }

            @Override
            public String bucketOf(TaskFeatures features) {
                return UNKNOWN_BUCKET;
            }
        };
        var definition = new RoutingStateDefinition("routing-state/1", otherSchemaMapping);
        var host = new LexicographicRoutingPolicy(definition, 3, OptionalInt.empty(), Optional.empty());
        var hostVerifier = LexicographicDecisionVerifier.trusted(host.parameters(), definition);
        var preference = empty(java);
        var in = input(java, demo(), preference);

        verify(hostVerifier, host.decide(java, demo(), preference), in);
        // A policy that claims a compatible state under this definition is fabricating admission.
        var claimsCompatible = new RoutingPreference(java.scopeId(), "task-features/0", java.evaluationPolicyId(),
                java.evaluationPolicyVersion(), "routing-state/1", "route-lex", "1", 0L, List.of());
        var forgedParameters = host.parameters();
        var provenance = new Provenance(DecisionRef.of(java), demo().catalogVersion(), "route-lex", "1",
                java.cutoff(), claimsCompatible.binding(), new StateValidation.Compatible(), forgedParameters);
        var forged = new RoutingDecision.Selected(provenance, RoutingFixtures.SUB_A, "sandboxed",
                Basis.COLD_START, false, List.of(
                        new RankedCandidate(0, RoutingFixtures.SUB_A, Placement.ROUTE_ORDER),
                        new RankedCandidate(1, RoutingFixtures.SUB_B, Placement.ROUTE_ORDER)),
                false, RoutingFixtures.APPLIED, RoutingFixtures.SKIPPED, filter.evaluate(java, demo()).excluded(),
                new CohortBinding("UNKNOWN", "b/0", "routing-state/1", "task-features/0"));

        assertTrue(rejected(hostVerifier, forged, input(java, demo(), claimsCompatible)).contains("implies"));
    }

    @Test
    void theRankingIsStillRecomputedFromTrustedValues() {
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));
        var honest = selected(cold);
        var swapped = List.of(honest.candidates().get(1), honest.candidates().get(0));
        var reordered = new RoutingDecision.Selected(honest.provenance(), honest.candidates().get(1).key(),
                "sandboxed", honest.basis(), false,
                List.of(new RankedCandidate(0, swapped.get(0).key(), swapped.get(0).placement()),
                        new RankedCandidate(1, swapped.get(1).key(), swapped.get(1).placement())),
                false, honest.rulesApplied(), honest.rulesSkipped(), honest.exclusions(), honest.cohortBinding());

        assertTrue(rejected(reordered, cold).contains("recomputed ranking"));
    }

    @Test
    void aReportForAnotherCatalogIsRejected() {
        var java = request(List.of("java"), false);
        var cold = input(java, demo(), empty(java));
        var foreign = new EligibilityReport("other-catalog", List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> trusted.verify(selected(cold), cold, foreign));
    }

    private static RoutingDecision incompatible(RoutingPreference preference, RoutingRequest request,
            List<StateMismatch> reasons, List<RouteKey> eligible, List<EligibilityReport.Exclusion> exclusions) {
        return new RoutingDecision.Abstain(
                RoutingFixtures.provenance(request, preference, new StateValidation.Incompatible(reasons), PARAMETERS),
                AbstainReason.STATE_INCOMPATIBLE, eligible, List.of(), false, exclusions);
    }
}
