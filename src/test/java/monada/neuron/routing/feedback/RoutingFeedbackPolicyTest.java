package monada.neuron.routing.feedback;

import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.CohortMapping;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.outcome.AttemptStatus;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.EvidenceRef;
import monada.neuron.routing.outcome.EvidenceRevision;
import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.ProvenanceField;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityObservation;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.outcome.StageAttribution;
import monada.neuron.testsupport.AllocationProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static monada.neuron.routing.feedback.FeedbackFixtures.CONFIG;
import static monada.neuron.routing.feedback.FeedbackFixtures.DEFINITION;
import static monada.neuron.routing.feedback.FeedbackFixtures.GATES;
import static monada.neuron.routing.feedback.FeedbackFixtures.NODE_A;
import static monada.neuron.routing.feedback.FeedbackFixtures.NODE_B;
import static monada.neuron.routing.feedback.FeedbackFixtures.NO_COST;
import static monada.neuron.routing.feedback.FeedbackFixtures.bindings;
import static monada.neuron.routing.feedback.FeedbackFixtures.decide;
import static monada.neuron.routing.feedback.FeedbackFixtures.obs;
import static monada.neuron.routing.feedback.FeedbackFixtures.policy;
import static monada.neuron.routing.feedback.FeedbackFixtures.request;
import static monada.neuron.routing.feedback.FeedbackFixtures.tokens;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingFeedbackPolicyTest {

    private static final double EPS = 1e-12;

    private final FeedbackFixtures.Spec spec = new FeedbackFixtures.Spec();

    private FeedbackDerivation derive(FeedbackFixtures.Spec s) {
        return policy().derive(s.observation(), s.decision, s.request, bindings(s.decision, s.request, NODE_A));
    }

    private RoutingFeedback feedback(FeedbackDerivation derivation) {
        return assertInstanceOf(FeedbackDerivation.Derived.class, derivation).feedback();
    }

    private FeedbackDerivation.NoUpdate noUpdate(FeedbackDerivation derivation) {
        return assertInstanceOf(FeedbackDerivation.NoUpdate.class, derivation);
    }

    // ---- executed route credit ----

    @Test
    void validatedStageLocalAcceptanceReinforcesTheBoundNodeOfTheExecutedRoute() {
        var f = feedback(derive(spec.passing()));
        assertEquals(FeedbackDisposition.REINFORCE, f.disposition());
        assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, f.rule());
        assertEquals(1, f.entries().size());
        assertEquals(NODE_A, f.entries().getFirst().targetNodeId());
        assertEquals(0.8, f.entries().getFirst().score(), EPS);
        assertEquals(FeedbackFixtures.keyOf(spec.decision, spec.request), f.cohortKey());
        assertTrue(f.reasons().isEmpty());
        assertEquals(2, f.effectiveEvidence().size());
    }

    @Test
    void provenMandatoryFailurePenalizesWithTheConfiguredBoundedScore() {
        var f = feedback(derive(spec.failing()));
        assertEquals(FeedbackDisposition.PENALIZE, f.disposition());
        assertEquals(ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE, f.rule());
        assertEquals(-0.4, f.entries().getFirst().score(), EPS);
        assertEquals(List.of(QualityDimension.TESTS), f.provenFailures());
    }

    @Test
    void acceptedButFailedAfterCorrectionIsPenalizedLikeAProvenFailure() {
        spec.passing().with(obs("ev-tests-late", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 8));
        var f = feedback(derive(spec));
        assertEquals(FeedbackDisposition.PENALIZE, f.disposition());
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, f.derivedAcceptance());
    }

    @Test
    void aRouteThatWasNotExecutedGetsNoCreditAndNothingIsDerived() {
        spec.passing().executed = new RouteKey("sub-b", 1);
        var none = noUpdate(derive(spec));
        assertEquals(NoUpdateReason.PROVENANCE_MISMATCH, none.reason());
        assertTrue(none.mismatches().contains(ProvenanceField.EXECUTED_ROUTE));
    }

    @Test
    void foreignProvenanceDerivesNothing() {
        spec.passing().sourceFingerprint = "src-other";
        assertEquals(NoUpdateReason.PROVENANCE_MISMATCH, noUpdate(derive(spec)).reason());
    }

    @Test
    void foreignScopeBindingsAndMissingBindingsMutateNothing() {
        spec.passing();
        var key = FeedbackFixtures.keyOf(spec.decision, spec.request);
        var foreignScope = new TargetBindings("scope-other", "forge-gates", "1", DEFINITION.mappingVersion(), FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(
                        new CohortKey("scope-other", key.stageKind(), key.cohortBucket(), key.route()), NODE_A)));
        assertEquals(NoUpdateReason.BINDING_SCOPE_MISMATCH,
                noUpdate(policy().derive(spec.observation(), spec.decision, spec.request, foreignScope)).reason());

        var otherPolicy = new TargetBindings("scope-demo", "forge-gates", "2", DEFINITION.mappingVersion(), FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(key, NODE_A)));
        assertEquals(NoUpdateReason.BINDING_PARTITION_MISMATCH,
                noUpdate(policy().derive(spec.observation(), spec.decision, spec.request, otherPolicy)).reason());
        var otherMapping = new TargetBindings("scope-demo", "forge-gates", "1", "routing-state/other", FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(key, NODE_A)));
        assertEquals(NoUpdateReason.BINDING_PARTITION_MISMATCH,
                noUpdate(policy().derive(spec.observation(), spec.decision, spec.request, otherMapping)).reason());

        var otherRoute = new TargetBindings("scope-demo", "forge-gates", "1", DEFINITION.mappingVersion(), FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(
                        new CohortKey("scope-demo", key.stageKind(), key.cohortBucket(), new RouteKey("sub-b", 1)),
                        NODE_B)));
        assertEquals(NoUpdateReason.NO_TARGET_BINDING,
                noUpdate(policy().derive(spec.observation(), spec.decision, spec.request, otherRoute)).reason());
        var empty = new TargetBindings("scope-demo", "forge-gates", "1", DEFINITION.mappingVersion(), FeedbackFixtures.SCHEMA, List.of());
        assertEquals(NoUpdateReason.NO_TARGET_BINDING,
                noUpdate(policy().derive(spec.observation(), spec.decision, spec.request, empty)).reason());
    }

    @Test
    void aDecisionOrBindingsOfAnotherFeatureSchemaDeriveNothing() {
        spec.passing();
        var otherSchema = new CohortMapping() {
            @Override
            public String bucketMappingVersion() {
                return DEFINITION.cohortMapping().bucketMappingVersion();
            }

            @Override
            public String featureSchemaVersion() {
                return "task-features/other";
            }

            @Override
            public String bucketOf(TaskFeatures features) {
                return DEFINITION.cohortMapping().bucketOf(features);
            }
        };
        var definition = new RoutingStateDefinition(DEFINITION.mappingVersion(), otherSchema);
        var policy = new RoutingFeedbackPolicy(definition, CONFIG, GATES);
        var bindings = new TargetBindings("scope-demo", "forge-gates", "1", DEFINITION.mappingVersion(),
                "task-features/other", List.of());
        assertEquals(NoUpdateReason.FEATURE_SCHEMA_MISMATCH,
                noUpdate(policy.derive(spec.observation(), spec.decision, spec.request, bindings)).reason());
        assertTrue(NoUpdateReason.FEATURE_SCHEMA_MISMATCH.foreignPartition());

        // bindings built for another schema are a foreign partition too
        var key = FeedbackFixtures.keyOf(spec.decision, spec.request);
        var foreignBindings = new TargetBindings("scope-demo", "forge-gates", "1", DEFINITION.mappingVersion(),
                "task-features/other", List.of(new TargetBinding(key, NODE_A)));
        assertEquals(NoUpdateReason.BINDING_PARTITION_MISMATCH,
                noUpdate(policy().derive(spec.observation(), spec.decision, spec.request, foreignBindings)).reason());
    }

    @Test
    void anOutcomeOfAnotherPartitionPairedWithACurrentDecisionIsClassifiedAsForeign() {
        spec.passing();
        var observation = spec.observation();
        var outcome = observation.outcome();
        var binding = outcome.cohortBinding();
        for (var foreign : List.of(
                new CohortBinding(binding.cohortBucket(), binding.bucketMappingVersion(), "routing-state/other",
                        binding.featureSchemaVersion()),
                new CohortBinding(binding.cohortBucket(), binding.bucketMappingVersion(), binding.mappingVersion(),
                        "task-features/other"))) {
            var mispaired = new RoutingObservation(new RoutingOutcome(outcome.contractVersion(),
                    outcome.decisionRef(), outcome.executedRoute(), foreign, outcome.catalogVersion(),
                    outcome.routingPolicyId(), outcome.routingPolicyVersion(), outcome.sourceFingerprint(),
                    outcome.contextFingerprint(), outcome.constraintsFingerprint(), outcome.evaluationPolicyId(),
                    outcome.evaluationPolicyVersion(), outcome.cutoff(), outcome.invocation(),
                    outcome.outcomeStatus(), outcome.outcomeOrigin(), outcome.acceptedAttemptId(),
                    outcome.acceptedAttemptStatus(), outcome.outcomeSequence(), outcome.observations(),
                    outcome.judgedRevisions(), outcome.resources(), outcome.attribution()),
                    observation.experienceRef(), observation.revision(), observation.ledgerSequence(),
                    observation.supersedesSequence());
            var none = noUpdate(policy().derive(mispaired, spec.decision, spec.request,
                    bindings(spec.decision, spec.request, NODE_A)));
            assertTrue(none.reason().foreignPartition(), none.reason().toString());
        }
    }

    @Test
    void aRequestOfAnotherFeatureSchemaIsRejectedBeforeTheCohortMappingRuns() {
        spec.passing();
        var strict = new CohortMapping() {
            @Override
            public String bucketMappingVersion() {
                return DEFINITION.cohortMapping().bucketMappingVersion();
            }

            @Override
            public String featureSchemaVersion() {
                return DEFINITION.cohortMapping().featureSchemaVersion();
            }

            @Override
            public String bucketOf(TaskFeatures features) {
                if (!featureSchemaVersion().equals(features.schemaVersion())) {
                    throw new IllegalArgumentException("built for " + featureSchemaVersion());
                }
                return DEFINITION.cohortMapping().bucketOf(features);
            }
        };
        var policy = new RoutingFeedbackPolicy(new RoutingStateDefinition(DEFINITION.mappingVersion(), strict),
                CONFIG, GATES);
        var other = new RoutingRequest(spec.request.contractVersion(), spec.request.scopeId(), spec.request.taskId(),
                spec.request.executionId(), spec.request.attemptId(), spec.request.stageId(),
                spec.request.stageKind(), spec.request.requestOrdinal(), spec.request.sourceFingerprint(),
                spec.request.contextFingerprint(), spec.request.constraintsFingerprint(),
                spec.request.evaluationPolicyId(), spec.request.evaluationPolicyVersion(),
                TaskFeatures.builder().schemaVersion("task-features/other").stageKind("implement").build(),
                spec.request.requirements(), false, spec.request.cutoff());
        var none = noUpdate(policy.derive(spec.observation(), spec.decision, other,
                bindings(spec.decision, other, NODE_A)));
        assertEquals(NoUpdateReason.PROVENANCE_MISMATCH, none.reason());
        assertEquals(List.of(ProvenanceField.FEATURE_SCHEMA_VERSION), none.mismatches());
    }

    @Test
    void aForeignBucketMappingVersionOnTheDecisionOrOutcomeIsAForeignPartition() {
        spec.passing();
        var observation = spec.observation();
        var outcome = observation.outcome();
        var binding = outcome.cohortBinding();
        var foreign = new CohortBinding(binding.cohortBucket(), "bucket-mapping/other", binding.mappingVersion(),
                binding.featureSchemaVersion());
        var mispaired = new RoutingObservation(new RoutingOutcome(outcome.contractVersion(), outcome.decisionRef(),
                outcome.executedRoute(), foreign, outcome.catalogVersion(), outcome.routingPolicyId(),
                outcome.routingPolicyVersion(), outcome.sourceFingerprint(), outcome.contextFingerprint(),
                outcome.constraintsFingerprint(), outcome.evaluationPolicyId(), outcome.evaluationPolicyVersion(),
                outcome.cutoff(), outcome.invocation(), outcome.outcomeStatus(), outcome.outcomeOrigin(),
                outcome.acceptedAttemptId(), outcome.acceptedAttemptStatus(), outcome.outcomeSequence(),
                outcome.observations(), outcome.judgedRevisions(), outcome.resources(), outcome.attribution()),
                observation.experienceRef(), observation.revision(), observation.ledgerSequence(),
                observation.supersedesSequence());
        var none = noUpdate(policy().derive(mispaired, spec.decision, spec.request,
                bindings(spec.decision, spec.request, NODE_A)));
        assertEquals(NoUpdateReason.MAPPING_MISMATCH, none.reason());
    }

    @Test
    void aPeerOfAnotherBucketMappingIsNotAComparison() {
        var self = new FeedbackFixtures.Spec().passing();
        self.resources = tokens(400, ResourceValue.Provenance.REPORTED);
        var peer = peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true);
        var outcome = peer.observation().outcome();
        var binding = outcome.cohortBinding();
        var foreign = new CohortBinding(binding.cohortBucket(), "bucket-mapping/other", binding.mappingVersion(),
                binding.featureSchemaVersion());
        var foreignPeer = new RoutingObservation(new RoutingOutcome(outcome.contractVersion(), outcome.decisionRef(),
                outcome.executedRoute(), foreign, outcome.catalogVersion(), outcome.routingPolicyId(),
                outcome.routingPolicyVersion(), outcome.sourceFingerprint(), outcome.contextFingerprint(),
                outcome.constraintsFingerprint(), outcome.evaluationPolicyId(), outcome.evaluationPolicyVersion(),
                outcome.cutoff(), outcome.invocation(), outcome.outcomeStatus(), outcome.outcomeOrigin(),
                outcome.acceptedAttemptId(), outcome.acceptedAttemptStatus(), outcome.outcomeSequence(),
                outcome.observations(), outcome.judgedRevisions(), outcome.resources(), outcome.attribution()),
                "exp-2", 0, 12L, Feature.unknown());
        var reference = new CostReference(foreignPeer, peer.decision, peer.request);
        var f = feedback(policy().derive(self.observation(), self.decision, self.request,
                bindings(self.decision, self.request, NODE_A), Optional.of(reference)));
        assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, f.rule());
    }

    @Test
    void aForeignBindingPartitionIsRejectedBeforeEveryNeutralOrNoUpdateExit() {
        var foreignScope = new TargetBindings("scope-other", "forge-gates", "1", DEFINITION.mappingVersion(),
                FeedbackFixtures.SCHEMA, List.of());
        var foreignPolicy = new TargetBindings("scope-demo", "forge-gates", "9", DEFINITION.mappingVersion(),
                FeedbackFixtures.SCHEMA, List.of());
        var pending = new FeedbackFixtures.Spec().passing();
        pending.status = OutcomeStatus.PENDING;
        var environmental = new FeedbackFixtures.Spec().failing();
        environmental.invocation = InvocationStatus.TIMED_OUT;
        var chainOnly = new FeedbackFixtures.Spec().passing();
        chainOnly.attribution = StageAttribution.none();
        for (var s : List.of(new FeedbackFixtures.Spec(), pending, environmental, chainOnly)) {
            assertEquals(NoUpdateReason.BINDING_SCOPE_MISMATCH, noUpdate(
                    policy().derive(s.observation(), s.decision, s.request, foreignScope)).reason());
            assertEquals(NoUpdateReason.BINDING_PARTITION_MISMATCH, noUpdate(
                    policy().derive(s.observation(), s.decision, s.request, foreignPolicy)).reason());
        }
    }

    @Test
    void aCostRefinedRewardRecordsThePeerRevisionItDependedOn() {
        var self = new FeedbackFixtures.Spec().passing();
        self.resources = tokens(400, ResourceValue.Provenance.REPORTED);
        var peer = peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true);
        var f = withCost(policy(), self, peer);
        assertEquals(Optional.of(new PeerRevision("exp-2", 0, 12L)), f.costPeer());
        assertEquals(Optional.empty(), feedback(derive(new FeedbackFixtures.Spec().passing())).costPeer());
    }

    @Test
    void aPeerFromAfterTheFeedbackCutoffCannotInfluenceIt() {
        var self = new FeedbackFixtures.Spec().passing();
        self.resources = tokens(400, ResourceValue.Provenance.REPORTED);
        var later = peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true);
        later.cutoff = self.cutoff + 1;
        assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, withCost(policy(), self, later).rule());
        // a peer evaluated as of the same cutoff still counts
        var same = peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true);
        assertEquals(ScoreRule.REINFORCE_COST_REFINED, withCost(policy(), self, same).rule());
    }

    @Test
    void aDecisionFromAnotherStateDefinitionDerivesNothing() {
        spec.passing();
        var other = new RoutingStateDefinition("routing-state/2", DEFINITION.cohortMapping());
        var policy = new RoutingFeedbackPolicy(other, CONFIG, GATES);
        var bindings = new TargetBindings("scope-demo", "forge-gates", "1", "routing-state/2", FeedbackFixtures.SCHEMA, List.of());
        assertEquals(NoUpdateReason.MAPPING_MISMATCH,
                noUpdate(policy.derive(spec.observation(), spec.decision, spec.request, bindings)).reason());
    }

    @Test
    void aRequestThatDidNotProduceTheDecisionIsRejectedBeforeAnyLookup() {
        spec.passing();
        var other = new RoutingRequest(spec.request.contractVersion(),
                spec.request.scopeId(), spec.request.taskId(), spec.request.executionId(), spec.request.attemptId(),
                spec.request.stageId(), "review", spec.request.requestOrdinal(), spec.request.sourceFingerprint(),
                spec.request.contextFingerprint(), spec.request.constraintsFingerprint(),
                spec.request.evaluationPolicyId(), spec.request.evaluationPolicyVersion(),
                TaskFeatures.builder().stageKind("review").changeSize(100_000L).build(),
                spec.request.requirements(), false, spec.request.cutoff());
        var none = noUpdate(policy().derive(spec.observation(), spec.decision, other,
                bindings(spec.decision, other, NODE_A)));
        assertEquals(NoUpdateReason.PROVENANCE_MISMATCH, none.reason());
        assertEquals(List.of(ProvenanceField.COHORT_BINDING), none.mismatches());
    }

    // ---- neutral and no-update cases ----

    @Test
    void environmentalFailuresStayNeutralEvenWithFailingEvidence() {
        for (var status : List.of(InvocationStatus.UNAVAILABLE, InvocationStatus.TIMED_OUT)) {
            var s = new FeedbackFixtures.Spec().failing();
            s.invocation = status;
            var f = feedback(derive(s));
            assertEquals(FeedbackDisposition.NEUTRAL, f.disposition());
            assertEquals(ScoreRule.NEUTRAL_ENVIRONMENTAL, f.rule());
            assertTrue(f.entries().isEmpty());
        }
    }

    @Test
    void cancelledAndPendingWorkIsNeutral() {
        var cancelled = new FeedbackFixtures.Spec().passing();
        cancelled.status = OutcomeStatus.CANCELLED;
        assertEquals(ScoreRule.NEUTRAL_CANCELLED, feedback(derive(cancelled)).rule());

        // an ACCEPTED claim for a cancelled invocation would be contradictory, so the outcome here is a rejection
        var invocationCancelled = new FeedbackFixtures.Spec().failing();
        invocationCancelled.invocation = InvocationStatus.CANCELLED;
        assertEquals(ScoreRule.NEUTRAL_CANCELLED, feedback(derive(invocationCancelled)).rule());

        var pending = new FeedbackFixtures.Spec().passing();
        pending.status = OutcomeStatus.PENDING;
        assertEquals(ScoreRule.NEUTRAL_PENDING, feedback(derive(pending)).rule());
    }

    @Test
    void importedClaimsAreNeverRewardedOrPenalized() {
        var accepted = new FeedbackFixtures.Spec().passing();
        accepted.origin = OutcomeOrigin.IMPORTED_CLAIM;
        assertEquals(ScoreRule.NEUTRAL_IMPORTED_CLAIM, feedback(derive(accepted)).rule());

        var failed = new FeedbackFixtures.Spec().failing();
        failed.origin = OutcomeOrigin.IMPORTED_CLAIM;
        var f = feedback(derive(failed));
        assertEquals(ScoreRule.NEUTRAL_IMPORTED_CLAIM, f.rule());
        assertTrue(f.entries().isEmpty());
    }

    @Test
    void missingUnknownAndForeignPolicyEvidenceIsNeutral() {
        var missing = new FeedbackFixtures.Spec();
        var f = feedback(derive(missing));
        assertEquals(ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE, f.rule());
        assertTrue(f.reasons().contains(IneligibilityReason.MISSING_EVIDENCE));

        var unknown = new FeedbackFixtures.Spec().passing()
                .with(obs("ev-tests-late", 0, QualityDimension.TESTS, QualityState.UNKNOWN, "att-1", 8));
        var u = feedback(derive(unknown));
        assertEquals(ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE, u.rule());
        assertTrue(u.reasons().contains(IneligibilityReason.UNKNOWN_EVIDENCE));

        var foreign = new FeedbackFixtures.Spec().passing();
        foreign.observations.replaceAll(o -> o.dimension() == QualityDimension.TESTS
                ? new QualityObservation(o.eventRef(), o.revision(), o.dimension(),
                        o.state(), o.justification(), o.evaluatorId(), o.evaluatorVersion(), o.policyId(), "9",
                        o.source(), o.attemptId(), o.ledgerSequence(), o.evidenceRefs())
                : o);
        assertEquals(ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE, feedback(derive(foreign)).rule());
    }

    @Test
    void rejectionWithoutAProvenFailureIsNeutralNotAPenalty() {
        var s = new FeedbackFixtures.Spec();
        s.status = OutcomeStatus.REJECTED;
        var f = feedback(derive(s));
        assertEquals(ScoreRule.NEUTRAL_NO_ATTRIBUTABLE_FAILURE, f.rule());
        assertTrue(f.entries().isEmpty());
    }

    @Test
    void anAcceptedUnfinishedRepairAttemptIsNotValidatedAndStaysNeutral() {
        var s = new FeedbackFixtures.Spec();
        s.acceptedAttempt = "att-2";
        s.with(obs("ev-tests", 0, QualityDimension.TESTS, QualityState.PASS, "att-2", 5))
                .with(obs("ev-sec", 0, QualityDimension.SECURITY, QualityState.PASS, "att-2", 6));
        var f = feedback(derive(s));
        assertEquals(ScoreRule.NEUTRAL_UNVALIDATED_ACCEPTANCE, f.rule());
        assertEquals(DerivedAcceptance.ACCEPTED_UNVALIDATED, f.derivedAcceptance());
    }

    @Test
    void chainLevelAcceptanceWithoutStageLocalEvidenceUpdatesNothing() {
        for (var attribution : List.of(
                new StageAttribution(StageAttribution.Basis.CHAIN_ONLY, 2, List.of()), StageAttribution.none())) {
            var s = new FeedbackFixtures.Spec().passing();
            s.attribution = attribution;
            assertEquals(NoUpdateReason.NO_STAGE_ATTRIBUTION, noUpdate(derive(s)).reason());
            var failed = new FeedbackFixtures.Spec().failing();
            failed.attribution = attribution;
            assertEquals(NoUpdateReason.NO_STAGE_ATTRIBUTION, noUpdate(derive(failed)).reason());
        }
    }

    @Test
    void aMixedRouteRepairCreditsOnlyTheRoutedStageWhenEvidenceIsStageLocal() {
        var s = new FeedbackFixtures.Spec();
        s.invocation = InvocationStatus.FAILED;
        s.acceptedAttempt = "att-2";
        s.acceptedAttemptStatus = Feature.known(AttemptStatus.COMPLETED);
        s.with(obs("ev-tests", 0, QualityDimension.TESTS, QualityState.PASS, "att-2", 5))
                .with(obs("ev-sec", 0, QualityDimension.SECURITY, QualityState.PASS, "att-2", 6));
        s.attribution = new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, 2,
                List.of(new EvidenceRef("log", "ref-stage", Feature.unknown())));
        var key = FeedbackFixtures.keyOf(s.decision, s.request);
        var both = new TargetBindings("scope-demo", "forge-gates", "1", DEFINITION.mappingVersion(), FeedbackFixtures.SCHEMA,
                List.of(new TargetBinding(key, NODE_A), new TargetBinding(
                        new CohortKey("scope-demo", key.stageKind(), key.cohortBucket(), new RouteKey("sub-b", 1)),
                        NODE_B)));
        var f = feedback(policy().derive(s.observation(), s.decision, s.request, both));
        assertEquals(List.of(NODE_A), f.entries().stream().map(e -> e.targetNodeId()).toList());
    }

    @Test
    void replacedEvidenceRevisionsAndContradictoryEvidenceAreRejected() {
        var stale = new FeedbackFixtures.Spec().passing()
                .with(obs("ev-tests", 1, QualityDimension.TESTS, QualityState.PASS, "att-1", 7));
        stale.judged.add(new EvidenceRevision("ev-tests", 0));
        stale.ledgerSequence = 12L;
        assertEquals(NoUpdateReason.STALE_ARTIFACT_REVISION, noUpdate(derive(stale)).reason());

        var contradictory = new FeedbackFixtures.Spec().passing()
                .with(obs("ev-tests-b", 0, QualityDimension.TESTS, QualityState.FAIL, "att-1", 5));
        assertEquals(NoUpdateReason.CONTRADICTORY_EVIDENCE, noUpdate(derive(contradictory)).reason());
    }

    // ---- cost ----

    private FeedbackFixtures.Spec peer(String task, ResourceSummary resources, boolean passing) {
        var request = request("scope-demo", task, "exec-2", "att-1", "s-impl-2", 102L);
        var peer = new FeedbackFixtures.Spec(request, decide(request, "sub-b"));
        peer.experienceRef = "exp-2";
        peer.resources = resources;
        return passing ? peer.passing() : peer.failing();
    }

    private RoutingFeedback withCost(
            RoutingFeedbackPolicy policy, FeedbackFixtures.Spec self, FeedbackFixtures.Spec peer) {
        var reference = new CostReference(peer.observation(), peer.decision, peer.request);
        return feedback(policy.derive(self.observation(), self.decision, self.request,
                bindings(self.decision, self.request, NODE_A), Optional.of(reference)));
    }

    @Test
    void aStrictlyCheaperValidatedExecutionOfADirectlyComparablePairEarnsTheBoundedBonus() {
        var self = new FeedbackFixtures.Spec().passing();
        self.resources = tokens(400, ResourceValue.Provenance.REPORTED);
        var f = withCost(policy(), self, peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true));
        assertEquals(ScoreRule.REINFORCE_COST_REFINED, f.rule());
        assertEquals(1.0, f.entries().getFirst().score(), EPS);
    }

    @Test
    void costNeverRaisesAScoreWithoutACompleteStrictlyBetterComparablePair() {
        var reported = tokens(400, ResourceValue.Provenance.REPORTED);
        var cases = List.of(
                // equal and worse measurements
                new Object[] {reported, peer("task-17", tokens(400, ResourceValue.Provenance.REPORTED), true)},
                new Object[] {tokens(1000, ResourceValue.Provenance.REPORTED),
                        peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true)},
                // different provenance, incomplete or unmeasured usage on either side
                new Object[] {reported, peer("task-17", tokens(900, ResourceValue.Provenance.ESTIMATED), true)},
                new Object[] {ResourceSummary.unrecorded(),
                        peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true)},
                new Object[] {reported, peer("task-17", ResourceSummary.unrecorded(), true)},
                // another task is not a direct pair
                new Object[] {reported, peer("task-99", tokens(900, ResourceValue.Provenance.REPORTED), true)},
                // a peer that was not validated says nothing
                new Object[] {reported, peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), false)});
        for (var c : cases) {
            var self = new FeedbackFixtures.Spec().passing();
            self.resources = (ResourceSummary) c[0];
            var f = withCost(policy(), self, (FeedbackFixtures.Spec) c[1]);
            assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, f.rule());
            assertEquals(0.8, f.entries().getFirst().score(), EPS);
        }
    }

    @Test
    void aPeerWhoseRequestDidNotProduceItsDecisionIsNotAComparison() {
        var self = new FeedbackFixtures.Spec().passing();
        self.resources = tokens(400, ResourceValue.Provenance.REPORTED);
        var peer = peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true);
        var forged = new RoutingRequest(peer.request.contractVersion(),
                peer.request.scopeId(), peer.request.taskId(), peer.request.executionId(), peer.request.attemptId(),
                peer.request.stageId(), peer.request.stageKind(), peer.request.requestOrdinal(),
                peer.request.sourceFingerprint(), peer.request.contextFingerprint(),
                peer.request.constraintsFingerprint(), peer.request.evaluationPolicyId(),
                peer.request.evaluationPolicyVersion(),
                TaskFeatures.builder().stageKind("implement").changeSize(100_000L)
                        .build(),
                peer.request.requirements(), false, peer.request.cutoff());
        var reference = new CostReference(peer.observation(), peer.decision, forged);
        var f = feedback(policy().derive(self.observation(), self.decision, self.request,
                bindings(self.decision, self.request, NODE_A), Optional.of(reference)));
        assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, f.rule());
    }

    @Test
    void twoAccountsOfOneExactDecisionAreNotACostComparison() {
        var self = new FeedbackFixtures.Spec().passing();
        self.resources = tokens(400, ResourceValue.Provenance.REPORTED);
        // same DecisionRef (same request), but a catalog whose only route is sub-b
        var rival = new FeedbackFixtures.Spec(self.request, decide(self.request, "sub-b")).passing();
        rival.experienceRef = "exp-2";
        rival.resources = tokens(900, ResourceValue.Provenance.REPORTED);
        assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, withCost(policy(), self, rival).rule());
    }

    @Test
    void theSameRouteIsNotAComparisonAndNoObjectiveMeansNoRefinement() {
        var self = new FeedbackFixtures.Spec().passing();
        self.resources = tokens(400, ResourceValue.Provenance.REPORTED);
        var request = request("scope-demo", "task-17", "exec-2", "att-1", "s-impl-2", 102L);
        var sameRoute = new FeedbackFixtures.Spec(request, decide(request, "sub-a"));
        sameRoute.experienceRef = "exp-2";
        sameRoute.resources = tokens(900, ResourceValue.Provenance.REPORTED);
        assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, withCost(policy(), self, sameRoute.passing()).rule());

        var noCost = new RoutingFeedbackPolicy(DEFINITION, NO_COST, GATES);
        var better = peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true);
        assertEquals(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, withCost(noCost, self, better).rule());
    }

    @Test
    void cheapFailingWorkCannotGainPositiveFeedbackAndCostDoesNotChangeAPenalty() {
        var self = new FeedbackFixtures.Spec().failing();
        self.resources = tokens(1, ResourceValue.Provenance.REPORTED);
        var f = withCost(policy(), self, peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true));
        assertEquals(FeedbackDisposition.PENALIZE, f.disposition());
        assertEquals(-0.4, f.entries().getFirst().score(), EPS);

        var unknownAndCheap = new FeedbackFixtures.Spec();
        unknownAndCheap.resources = tokens(1, ResourceValue.Provenance.REPORTED);
        var neutral = withCost(policy(), unknownAndCheap,
                peer("task-17", tokens(900, ResourceValue.Provenance.REPORTED), true));
        assertEquals(FeedbackDisposition.NEUTRAL, neutral.disposition());
    }

    // ---- determinism, identity, immutability ----

    @Test
    void equalInputsGiveEqualFeedbackWithExactProvenance() {
        var s = new FeedbackFixtures.Spec().passing();
        s.revision = 2;
        s.ledgerSequence = 30L;
        s.supersedes = Feature.known(12L);
        var first = feedback(derive(s));
        var second = feedback(derive(s));
        assertEquals(first, second);
        var id = first.identity();
        assertEquals("exp-1", id.experienceRef());
        assertEquals(2, id.revision());
        assertEquals(30L, id.ledgerSequence());
        assertEquals(DEFINITION.mappingVersion(), id.mappingVersion());
        assertEquals(new RouteKey("sub-a", 1), id.route());
        assertEquals(Feature.known(12L), first.supersedesSequence());
        assertEquals(s.decision.provenance().catalogVersion(), first.catalogVersion());
        assertEquals(s.decision.provenance().policyId(), first.routingPolicyId());
    }

    @Test
    void permutedEvidenceOrderGivesEqualFeedback() {
        var forward = new FeedbackFixtures.Spec().passing();
        var backward = new FeedbackFixtures.Spec().passing();
        java.util.Collections.reverse(backward.observations);
        assertEquals(feedback(derive(forward)), feedback(derive(backward)));
    }

    private static Node node(UUID id) {
        return new Node.Builder().id(id).frequencyState(new FrequencyState(4.0, 50.0, 0.5)).energy(10.0)
                .type(NodeType.PROCESSOR).build();
    }

    @Test
    void derivationHasNoSideEffectsAndApplyingTheAdapterMovesOnlyTheBoundNode() {
        var bound = node(NODE_A);
        var other = node(NODE_B);
        var boundBefore = bound.getFrequencyState();
        var otherBefore = other.getFrequencyState();
        var s = new FeedbackFixtures.Spec().passing();
        var observation = s.observation();

        var f = feedback(policy().derive(observation, s.decision, s.request, bindings(s.decision, s.request, NODE_A)));
        assertEquals(boundBefore, bound.getFrequencyState());
        assertEquals(10.0, bound.getEnergy());
        assertEquals(otherBefore, other.getFrequencyState());
        assertEquals(observation, s.observation());

        var adaptation = new DeterministicBaselineAdaptationPolicy(
                new AdaptationConfig(0.5, 0.5, 100.0, 0.0, 1000.0, 0.0, 10000.0, 2.0));
        for (var input : f.toFeedbackInputs()) {
            adaptation.adapt(bound, input);
        }
        // amplitude 4.0 * (1 + 0.5 * 0.8) = 5.6; energy 10 + 0.5 * 0.8 * 2.0 = 10.8
        assertEquals(5.6, bound.getFrequencyState().amplitude(), EPS);
        assertEquals(10.8, bound.getEnergy(), EPS);
        assertEquals(otherBefore, other.getFrequencyState());
        assertEquals(10.0, other.getEnergy());
    }

    @Test
    void aPenaltyShrinksTheAmplitudeByTheConfiguredFactor() {
        var bound = node(NODE_A);
        var f = feedback(derive(new FeedbackFixtures.Spec().failing()));
        var adaptation = new DeterministicBaselineAdaptationPolicy(
                new AdaptationConfig(0.5, 0.5, 100.0, 0.0, 1000.0, 0.0, 10000.0, 2.0));
        f.toFeedbackInputs().forEach(input -> adaptation.adapt(bound, input));
        // amplitude 4.0 * (1 + 0.5 * -0.4) = 3.2; energy 10 + 0.5 * -0.4 * 2.0 = 9.6
        assertEquals(3.2, bound.getFrequencyState().amplitude(), EPS);
        assertEquals(9.6, bound.getEnergy(), EPS);
    }

    @Test
    void neutralFeedbackAdaptsNothing() {
        var f = feedback(derive(new FeedbackFixtures.Spec()));
        assertTrue(f.toFeedbackInputs().isEmpty());
    }

    @Test
    void nullArgumentsAreRejected() {
        var s = new FeedbackFixtures.Spec().passing();
        var bindings = bindings(s.decision, s.request, NODE_A);
        assertThrows(NullPointerException.class, () -> policy().derive(null, s.decision, s.request, bindings));
        assertThrows(NullPointerException.class,
                () -> policy().derive(s.observation(), s.decision, s.request, bindings, null));
        assertThrows(NullPointerException.class, () -> new RoutingFeedbackPolicy(null, CONFIG, GATES));
    }

    @Test
    void measuredAllocationOfOneDerivationIsPublishedAndBounded(TestReporter reporter) {
        var s = new FeedbackFixtures.Spec().passing();
        var observation = s.observation();
        var bindings = bindings(s.decision, s.request, NODE_A);
        var policy = policy();
        long bytes = AllocationProbe.measure(20_000, () -> policy.derive(observation, s.decision, s.request, bindings));
        reporter.publishEntry("derive bytes allocated (2 mandatory dimensions)", Long.toString(bytes));
        assertTrue(bytes < 16 * 1024, "one derivation allocated " + bytes + " bytes");
    }
}
