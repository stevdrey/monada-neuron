package monada.neuron.routing;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.evaluation.EvaluationCognitiveStageResult;
import monada.neuron.evaluation.HypothesisEvaluationCognitiveStage;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.Proposition;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static monada.neuron.routing.RoutingFixtures.API_X;
import static monada.neuron.routing.RoutingFixtures.SUB_A;
import static monada.neuron.routing.RoutingFixtures.demo;
import static monada.neuron.routing.RoutingFixtures.empty;
import static monada.neuron.routing.RoutingFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level B composition with a host-configured {@link TrustedRoutingProfile} (Forge Routing Contract v1, section 18):
 * the mandatory validation always runs, the host-selected verifier runs once after it, and the legacy constructor keeps
 * its behavior.
 */
class TrustedRoutingStageTest {

    private static final int DOMAIN = 7;
    private static final CognitiveBudget MINIMUM = new CognitiveBudget(1, 1, 0);
    private static final Optional<HostExecutionContext> HOST =
            Optional.of(HostExecutionContext.of(new HostReference("exec-1")));
    private static final String CUSTOM_ID = "route-lex-audit";

    private final LexicographicRoutingPolicy reference = LexicographicRoutingPolicy.reference();

    private static PrimaryMonad monad() {
        return new PrimaryMonad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private static RoutingInputResolver resolving(RoutingRequest request) {
        return context -> Optional.of(new RoutingInput(request, demo(), empty(request)));
    }

    private static RoutingCognitiveStageResult run(RoutingReasoningStage stage) {
        return stage.execute(monad(), List.of(), new CognitiveContext(MINIMUM, HOST));
    }

    private static void assertRejected(RoutingReasoningStage stage, String fragment) {
        String message = rejected(stage);
        assertTrue(message.contains(fragment), message);
    }

    private static String rejected(RoutingReasoningStage stage) {
        return assertThrows(IllegalStateException.class, () -> run(stage)).getMessage();
    }

    /** A policy with its own identity whose decisions are the reference ones re-stamped, still inside the v1 types. */
    private static final class AuditPolicy implements RoutingPolicy {

        private final LexicographicRoutingPolicy delegate;
        private final AtomicInteger decisions = new AtomicInteger();

        AuditPolicy(LexicographicRoutingPolicy delegate) {
            this.delegate = delegate;
        }

        @Override
        public String policyId() {
            return CUSTOM_ID;
        }

        @Override
        public String policyVersion() {
            return "1";
        }

        @Override
        public RoutingDecision decide(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference) {
            decisions.incrementAndGet();
            return restamp(delegate.decide(request, catalog, preference), CUSTOM_ID);
        }
    }

    /** Counts the calls the host's verifier receives, delegating to the reference verifier under the audit identity. */
    private static final class CountingVerifier implements RoutingDecisionVerifier {

        private final RoutingDecisionVerifier inner;
        private final AtomicInteger calls = new AtomicInteger();

        CountingVerifier(LexicographicRoutingPolicy configuration) {
            this.inner = LexicographicDecisionVerifier.trusted(CUSTOM_ID, "1", configuration.parameters(),
                    configuration.definition());
        }

        @Override
        public String policyId() {
            return inner.policyId();
        }

        @Override
        public String policyVersion() {
            return inner.policyVersion();
        }

        @Override
        public String decisionContractVersion() {
            return inner.decisionContractVersion();
        }

        @Override
        public void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report) {
            calls.incrementAndGet();
            inner.verify(decision, input, report);
        }
    }

    private static RoutingDecision restamp(RoutingDecision decision, String policyId) {
        Provenance p = decision.provenance();
        var provenance = new Provenance(p.decisionRef(), p.catalogVersion(), policyId, "1", p.cutoff(), p.state(),
                p.validation(), p.parameters());
        return switch (decision) {
            case RoutingDecision.Selected s -> new RoutingDecision.Selected(provenance, s.route(), s.executionMode(),
                    s.basis(), s.overflowUsed(), s.candidates(), s.candidatesTruncated(), s.rulesApplied(),
                    s.rulesSkipped(), s.exclusions(), s.cohortBinding());
            case RoutingDecision.Abstain a -> new RoutingDecision.Abstain(provenance, a.reason(), a.eligible(),
                    a.candidates(), a.candidatesTruncated(), a.exclusions(), a.cohortBinding());
            case RoutingDecision.NoEligibleRoute n -> new RoutingDecision.NoEligibleRoute(provenance, n.exclusions());
        };
    }

    @Test
    void theReferenceProfileHandsOffExactlyTheLegacyHypothesis() {
        var request = request(List.of("java"), false);
        var legacy = run(new RoutingReasoningStage(reference, resolving(request), DOMAIN));
        var trusted = run(new RoutingReasoningStage(reference, TrustedRoutingProfile.lexicographic(reference),
                resolving(request), DOMAIN));

        assertEquals(legacy.decision(), trusted.decision());
        assertEquals(legacy.hypotheses(), trusted.hypotheses());
        assertEquals(new Proposition(DOMAIN, 1), trusted.hypotheses().get(0).proposition());
    }

    @Test
    void everyDecisionVariantPassesTheTrustedPath() {
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 3, OptionalInt.of(1),
                Optional.empty());

        var abstention = run(new RoutingReasoningStage(capped, TrustedRoutingProfile.lexicographic(capped),
                resolving(request(List.of("long-context"), true)), DOMAIN));
        var none = run(new RoutingReasoningStage(reference, TrustedRoutingProfile.lexicographic(reference),
                resolving(request(List.of("nonexistent"), false)), DOMAIN));

        assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED,
                assertInstanceOf(RoutingDecision.Abstain.class, abstention.decision()).reason());
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, none.decision());
        assertTrue(abstention.hypotheses().isEmpty() && none.hypotheses().isEmpty());
    }

    @Test
    void aPolicyOtherThanTheProfileHostsIsRejectedWhenTheStageIsBuilt() {
        var profile = TrustedRoutingProfile.lexicographic(reference);
        var audit = new AuditPolicy(reference);

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new RoutingReasoningStage(audit, profile, resolving(request(List.of("java"), false)), DOMAIN));

        assertTrue(failure.getMessage().contains(CUSTOM_ID), failure.getMessage());
        assertThrows(NullPointerException.class,
                () -> new RoutingReasoningStage(reference, (TrustedRoutingProfile) null, context -> Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class, () -> new RoutingReasoningStage(reference, profile,
                context -> Optional.empty(), -1));
    }

    @Test
    void aPolicyConfiguredDifferentlyThanTheHostTrustsIsRejectedAtExecution() {
        var request = request(List.of("java"), false);
        var different = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 1, OptionalInt.empty(),
                Optional.empty());
        // Same policy identity, so only the independent configuration can tell them apart.
        var stage = new RoutingReasoningStage(different, TrustedRoutingProfile.lexicographic(reference),
                resolving(request), DOMAIN);
        var legacy = new RoutingReasoningStage(different, resolving(request), DOMAIN);

        assertTrue(rejected(stage).contains("differ from the trusted configuration"));
        assertInstanceOf(RoutingDecision.Selected.class, run(legacy).decision());
    }

    @Test
    void aFabricatedCohortBucketIsRejectedByTheStageOnlyOnTheTrustedPath() {
        var request = request(List.of("java"), false);
        RoutingPolicy forging = new RoutingPolicy() {
            @Override
            public String policyId() {
                return reference.policyId();
            }

            @Override
            public String policyVersion() {
                return reference.policyVersion();
            }

            @Override
            public RoutingDecision decide(RoutingRequest r, RouteCatalog c, RoutingPreference p) {
                var s = assertInstanceOf(RoutingDecision.Selected.class, reference.decide(r, c, p));
                var cohort = s.cohortBinding();
                return new RoutingDecision.Selected(s.provenance(), s.route(), s.executionMode(), s.basis(),
                        s.overflowUsed(), s.candidates(), s.candidatesTruncated(), s.rulesApplied(),
                        s.rulesSkipped(), s.exclusions(), new CohortBinding("L", cohort.bucketMappingVersion(),
                                cohort.mappingVersion(), cohort.featureSchemaVersion()));
            }
        };
        var trusted = new RoutingReasoningStage(forging, TrustedRoutingProfile.lexicographic(reference),
                resolving(request), DOMAIN);
        var legacy = new RoutingReasoningStage(forging, resolving(request), DOMAIN);

        assertTrue(rejected(trusted).contains("trusted mapping derives"));
        assertInstanceOf(RoutingDecision.Selected.class, run(legacy).decision());
    }

    @Test
    void aCustomPolicyWithAHostVerifierReachesEvaluationWithoutChangingTheStage() {
        var request = request(List.of("java"), false);
        var audit = new AuditPolicy(reference);
        var verifier = new CountingVerifier(reference);
        var profile = new TrustedRoutingProfile(CUSTOM_ID, "1", RoutingDecision.CONTRACT_VERSION, verifier);
        var stage = new RoutingReasoningStage(audit, profile, context -> Optional.of(
                new RoutingInput(request, demo(), RoutingPreference.empty(request, RoutingStateDefinition.reference(),
                        CUSTOM_ID, "1"))), DOMAIN);
        var cycle = new DeterministicCognitiveCycle(List.of(stage,
                new HypothesisEvaluationCognitiveStage(new ReferenceHypothesisEvaluationPolicy(), 1)));

        var result = cycle.execute(monad(), List.of(), new CognitiveBudget(2, 2, 0), HOST);

        var routed = assertInstanceOf(RoutingCognitiveStageResult.class, result.stageResults().get(0));
        assertEquals(CUSTOM_ID, routed.decision().provenance().policyId());
        var evaluation = assertInstanceOf(EvaluationCognitiveStageResult.class, result.stageResults().get(1));
        assertEquals(new Proposition(DOMAIN, 1), evaluation.evaluated().get(0).proposition());
        assertEquals(1, audit.decisions.get());
        assertEquals(1, verifier.calls.get());
    }

    @Test
    void thePolicyCannotChangeItsIdentityAfterTheProfileWasChecked() {
        var request = request(List.of("java"), false);
        var renamed = new boolean[1];
        RoutingPolicy shifting = new RoutingPolicy() {
            @Override
            public String policyId() {
                return renamed[0] ? "other-family" : reference.policyId();
            }

            @Override
            public String policyVersion() {
                return reference.policyVersion();
            }

            @Override
            public RoutingDecision decide(RoutingRequest r, RouteCatalog c, RoutingPreference p) {
                return restamp(reference.decide(r, c, p), "other-family");
            }
        };
        var stage = new RoutingReasoningStage(shifting, TrustedRoutingProfile.lexicographic(reference),
                resolving(request), DOMAIN);
        renamed[0] = true;

        // The decision is stamped with the identity the policy now reports; only the profile's identity counts.
        assertRejected(stage, "other-family/1 instead of route-lex/1");
    }

    @Test
    void aSeparatelySuppliedConfigurationRejectsAPolicyConfiguredDifferently() {
        var request = request(List.of("java"), false);
        var hostParameters = reference.parameters();
        var wrong = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 1, OptionalInt.empty(),
                Optional.empty());
        var profile = TrustedRoutingProfile.lexicographic(hostParameters, RoutingStateDefinition.reference());

        // The profile does not read the policy object, so a wrongly configured instance cannot vouch for itself.
        var stage = new RoutingReasoningStage(wrong, profile, resolving(request), DOMAIN);

        assertRejected(stage, "differ from the trusted configuration");
    }

    @Test
    void anIncompatibleFamilyOrContractIsRejectedBeforeAnyDecisionExists() {
        var verifier = new CountingVerifier(reference);

        var contract = assertThrows(IllegalArgumentException.class,
                () -> new TrustedRoutingProfile(CUSTOM_ID, "1", "routing-decision/2", verifier));
        var family = assertThrows(IllegalArgumentException.class,
                () -> new TrustedRoutingProfile("other-family", "1", RoutingDecision.CONTRACT_VERSION, verifier));

        assertTrue(contract.getMessage().contains("unsupported routing decision contract"), contract.getMessage());
        assertTrue(family.getMessage().contains(CUSTOM_ID + "/1"), family.getMessage());
    }

    /** A host verifier that accepts everything, to prove it cannot widen what the mandatory validation allows. */
    private static final class AcceptAll implements RoutingDecisionVerifier {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String policyId() {
            return "stub";
        }

        @Override
        public String policyVersion() {
            return "1";
        }

        @Override
        public String decisionContractVersion() {
            return RoutingDecision.CONTRACT_VERSION;
        }

        @Override
        public void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report) {
            calls.incrementAndGet();
        }
    }

    private RoutingReasoningStage permissive(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference,
            String catalogVersion, RouteKey route, String mode, boolean overflow, AcceptAll accepting) {
        return permissive(DecisionRef.of(request), request, catalog, preference, catalogVersion, route, mode, overflow, accepting);
    }

    /** A stage whose stub policy decides about {@code decided} while the host resolved {@code resolved}. */
    private RoutingReasoningStage permissive(DecisionRef decided, RoutingRequest resolved, RouteCatalog catalog,
            RoutingPreference preference, String catalogVersion, RouteKey route, String mode, boolean overflow,
            AcceptAll accepting) {
        var request = resolved;
        var provenance = new Provenance(decided, catalogVersion, "stub", "1", request.cutoff(),
                preference.binding(), new StateValidation.Compatible(), RoutingFixtures.PARAMETERS);
        var ranked = List.of(new RankedCandidate(0, route, Placement.ONLY_ELIGIBLE));
        var decision = new RoutingDecision.Selected(provenance, route, mode, Basis.COLD_START, overflow, ranked,
                false, RoutingFixtures.APPLIED, RoutingFixtures.SKIPPED,
                new RouteEligibilityFilter().evaluate(request, catalog).excluded().stream()
                        .filter(exclusion -> !exclusion.key().equals(route)).toList(),
                RoutingFixtures.COLD_COHORT);
        RoutingPolicy stub = new RoutingPolicy() {
            @Override
            public String policyId() {
                return "stub";
            }

            @Override
            public String policyVersion() {
                return "1";
            }

            @Override
            public RoutingDecision decide(RoutingRequest r, RouteCatalog c, RoutingPreference p) {
                return decision;
            }
        };
        var profile = new TrustedRoutingProfile("stub", "1", RoutingDecision.CONTRACT_VERSION, accepting);
        return new RoutingReasoningStage(stub, profile,
                context -> Optional.of(new RoutingInput(resolved, catalog, preference)), DOMAIN);
    }

    @Test
    void aPermissiveVerifierCannotHandOffAnIneligibleRouteWrongModeOrForeignProvenance() {
        var java = request(List.of("java"), false);
        var preference = empty(java);

        // api-x is an overflow route and overflow is not permitted, so the filter excludes it.
        var accepting = new AcceptAll();
        assertRejected(permissive(java, demo(), preference, "cat-demo-7", API_X, "sandboxed", true, accepting), "not eligible");
        // sub-a is eligible, but not in a mode the filter permits.
        assertRejected(permissive(java, demo(), preference, "cat-demo-7", SUB_A, "unsafe", false, accepting), "disagrees with the eligibility filter");
        // A decision about another catalog snapshot, whose Proposition.code would index the wrong routes.
        assertRejected(permissive(java, demo(), preference, "cat-foreign", SUB_A, "sandboxed", false, accepting), "catalog cat-foreign");
        // A decision about another request than the one the host resolved.
        var otherRequest = new DecisionRef("scope-demo", "other-task", "exec-1", "att-1", "s-impl", 101L);
        assertRejected(permissive(otherRequest, java, demo(), preference, "cat-demo-7", SUB_A, "sandboxed",
                false, accepting), "another request");

        // The mandatory validation runs first, so the permissive verifier was never consulted for these decisions.
        assertEquals(0, accepting.calls.get());
    }

    @Test
    void aValidDecisionReachesTheHostVerifierExactlyOnce() {
        var java = request(List.of("java"), false);
        var accepting = new AcceptAll();

        var result = run(permissive(java, demo(), empty(java), "cat-demo-7", SUB_A, "sandboxed", false, accepting));

        assertInstanceOf(RoutingDecision.Selected.class, result.decision());
        assertEquals(1, accepting.calls.get());
    }

    /** Both paths measured separately; the guard is not a latency benchmark. */
    private static final long TRUSTED_STAGE_LIMIT_BYTES = 44 * 1024;

    @Test
    void trustedPathAllocationIsBoundedForMaximumSizeInputs(TestReporter reporter) {
        var request = request(List.of("java"), true);
        var catalog = RoutingFixtures.maximumCatalog();
        var preference = RoutingFixtures.maximumPreference();
        var objective = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 3, OptionalInt.empty(),
                Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE)));
        var plain = LexicographicRoutingPolicy.reference();
        var context = new CognitiveContext(MINIMUM, HOST);
        var monad = monad();
        RoutingInputResolver resolver = c -> Optional.of(new RoutingInput(request, catalog, preference));

        var legacy = new RoutingReasoningStage(objective, resolver, DOMAIN);
        var trusted = new RoutingReasoningStage(objective, TrustedRoutingProfile.lexicographic(objective), resolver,
                DOMAIN);
        long legacyBytes = AllocationProbe.measure(20_000, () -> legacy.execute(monad, List.of(), context));
        long trustedBytes = AllocationProbe.measure(20_000, () -> trusted.execute(monad, List.of(), context));

        reporter.publishEntry("selected.legacyAllocatedBytes", Long.toString(legacyBytes));
        reporter.publishEntry("selected.trustedAllocatedBytes", Long.toString(trustedBytes));
        assertTrue(trustedBytes < TRUSTED_STAGE_LIMIT_BYTES, "allocated " + trustedBytes);

        // A state-incompatible abstention (stale mapping version, no ranking) and a no-route decision exercise the other
        // variants on the same sizes; a tier-cap abstention ranks like Selected and is not measured separately.
        var stale = new RoutingPreference("scope-demo", "task-features/1", "forge-gates", "1", "routing-state/0",
                "route-lex", "1", 40, List.of());
        RoutingInputResolver staleResolver = c -> Optional.of(new RoutingInput(request, catalog, stale));
        var staleTrusted = new RoutingReasoningStage(plain, TrustedRoutingProfile.lexicographic(plain),
                staleResolver, DOMAIN);
        var staleLegacy = new RoutingReasoningStage(plain, staleResolver, DOMAIN);
        long abstainLegacy = AllocationProbe.measure(20_000, () -> staleLegacy.execute(monad, List.of(), context));
        long abstainTrusted = AllocationProbe.measure(20_000, () -> staleTrusted.execute(monad, List.of(), context));
        reporter.publishEntry("abstain.legacyAllocatedBytes", Long.toString(abstainLegacy));
        reporter.publishEntry("abstain.trustedAllocatedBytes", Long.toString(abstainTrusted));
        assertTrue(abstainTrusted < TRUSTED_STAGE_LIMIT_BYTES, "allocated " + abstainTrusted);

        var impossible = request(List.of("nonexistent"), false);
        RoutingInputResolver noneResolver = c -> Optional.of(new RoutingInput(impossible, catalog, preference));
        var noneTrusted = new RoutingReasoningStage(objective, TrustedRoutingProfile.lexicographic(objective),
                noneResolver, DOMAIN);
        var noneLegacy = new RoutingReasoningStage(objective, noneResolver, DOMAIN);
        long noneLegacyBytes = AllocationProbe.measure(20_000, () -> noneLegacy.execute(monad, List.of(), context));
        long noneTrustedBytes = AllocationProbe.measure(20_000, () -> noneTrusted.execute(monad, List.of(), context));
        reporter.publishEntry("none.legacyAllocatedBytes", Long.toString(noneLegacyBytes));
        reporter.publishEntry("none.trustedAllocatedBytes", Long.toString(noneTrustedBytes));
        assertTrue(noneTrustedBytes < TRUSTED_STAGE_LIMIT_BYTES, "allocated " + noneTrustedBytes);
    }
}
