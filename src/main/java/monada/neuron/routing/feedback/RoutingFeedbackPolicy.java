package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.outcome.EligibilityVerdict;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.LearningAssessment;
import monada.neuron.routing.outcome.OutcomeEvaluator;
import monada.neuron.routing.outcome.ProvenanceField;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageDeclaration;
import monada.neuron.routing.ResourceObjective;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Derives {@link RoutingFeedback} from one learning-eligible observation (contract section 6).
 *
 * <p>The verdict rule stays with {@link OutcomeEvaluator}; this class only maps an assessment to a disposition and a
 * bounded score, and decides which caller-bound Node, if any, may receive it:
 * <ol>
 * <li>an outcome that does not answer the exact decision and request (another route than the advised one, scope,
 *     stage, catalog, policy or fingerprints), a replaced evidence revision or contradictory evidence derive nothing;</li>
 * <li>environmental failure, cancelled or pending work, an imported claim, missing, unknown or foreign-policy evidence
 *     and a rejection without a proven mandatory failure derive {@code NEUTRAL} feedback with no entries;</li>
 * <li>a validated or failed result without stage-local attribution (a chain-only or mixed-route repair) derives
 *     nothing, so no participant receives chain-level credit;</li>
 * <li>validated acceptance reinforces and a proven mandatory failure penalizes, and only the Node bound to the
 *     executed route's cohort in the decision's scope and state partition is a target; a missing or foreign binding
 *     derives nothing.</li>
 * </ol>
 *
 * <p>Cost is read only after the quality gates granted a reward, and only when a directly comparable, validated
 * execution of another route has a complete, equally sourced, strictly worse measurement of the declared counter.
 * Unknown, not-measured and absent usage never count as cheap or as zero, and cost never offsets a failed gate.
 *
 * <p>Pure, stateless and deterministic: no clock, randomness, Node access or hidden history. Time is that of
 * {@link OutcomeEvaluator#assess} plus {@code O(log C)} for the binding lookup, {@code C <= 256}; a cost reference
 * adds one more {@code assess}.
 */
public final class RoutingFeedbackPolicy {

    private final RoutingStateDefinition definition;
    private final RoutingFeedbackConfig config;
    private final EvidencePolicy evidencePolicy;
    private final OutcomeEvaluator evaluator = new OutcomeEvaluator();

    /**
     * Creates a policy.
     *
     * @param definition state definition whose {@code mappingVersion} the decisions and bindings must carry
     * @param config scoring rules
     * @param evidencePolicy host evidence policy in force
     */
    public RoutingFeedbackPolicy(
            RoutingStateDefinition definition, RoutingFeedbackConfig config, EvidencePolicy evidencePolicy) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.evidencePolicy = Objects.requireNonNull(evidencePolicy, "evidencePolicy must not be null");
    }

    /** Derives feedback without the optional cost refinement. */
    public FeedbackDerivation derive(
            RoutingObservation observation,
            RoutingDecision.Selected decision,
            RoutingRequest request,
            TargetBindings bindings) {
        return derive(observation, decision, request, bindings, Optional.empty());
    }

    /**
     * Derives feedback for one observation.
     *
     * @param observation the effective sample revision
     * @param decision the {@code Selected} decision the outcome answers
     * @param request the request that produced the decision
     * @param bindings caller-supplied Node bindings of the state partition
     * @param cost optional comparison execution; read only for a reward and only when the config declares an objective
     * @return feedback or an explicit "no update"; never throws for well-formed arguments
     */
    public FeedbackDerivation derive(
            RoutingObservation observation,
            RoutingDecision.Selected decision,
            RoutingRequest request,
            TargetBindings bindings,
            Optional<CostReference> cost) {
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        Objects.requireNonNull(cost, "cost must not be null");
        // both the decision and the outcome must belong to this state partition, and so must the bindings, before any
        // assessment-based exit: a foreign sample would otherwise be derived (or marked) and recorded in this ledger
        CohortBinding recorded = observation.outcome().cohortBinding();
        Optional<NoUpdateReason> foreign = partitionMismatch(decision.cohortBinding(), recorded);
        if (foreign.isPresent()) {
            return noUpdate(foreign.get(), null);
        }
        RoutingOutcome outcome = observation.outcome();
        if (!bindings.scopeId().equals(outcome.decisionRef().scopeId())) {
            return noUpdate(NoUpdateReason.BINDING_SCOPE_MISMATCH, null);
        }
        if (!bindings.evaluationPolicyId().equals(outcome.evaluationPolicyId())
                || !bindings.evaluationPolicyVersion().equals(outcome.evaluationPolicyVersion())
                || !bindings.mappingVersion().equals(definition.mappingVersion())
                || !bindings.featureSchemaVersion().equals(definition.cohortMapping().featureSchemaVersion())) {
            return noUpdate(NoUpdateReason.BINDING_PARTITION_MISMATCH, null);
        }
        if (!definition.cohortMapping().featureSchemaVersion().equals(request.features().schemaVersion())) {
            // checked before the cohort mapping runs: a mapping built for its own schema may reject foreign features
            return new FeedbackDerivation.NoUpdate(NoUpdateReason.PROVENANCE_MISMATCH,
                    List.of(IneligibilityReason.PROVENANCE_MISMATCH), List.of(ProvenanceField.FEATURE_SCHEMA_VERSION));
        }
        if (!producedBy(request, decision)) {
            // the request is not the one whose features produced the decision, so its stage kind cannot be trusted
            return new FeedbackDerivation.NoUpdate(NoUpdateReason.PROVENANCE_MISMATCH,
                    List.of(IneligibilityReason.PROVENANCE_MISMATCH), List.of(ProvenanceField.COHORT_BINDING));
        }
        LearningAssessment assessment = evaluator.assess(observation, decision, request, evidencePolicy);
        List<IneligibilityReason> reasons = assessment.reasons();
        if (reasons.contains(IneligibilityReason.PROVENANCE_MISMATCH)) {
            return noUpdate(NoUpdateReason.PROVENANCE_MISMATCH, assessment);
        }
        if (reasons.contains(IneligibilityReason.STALE_ARTIFACT_REVISION)) {
            return noUpdate(NoUpdateReason.STALE_ARTIFACT_REVISION, assessment);
        }
        if (reasons.contains(IneligibilityReason.CONTRADICTORY_EVIDENCE)) {
            return noUpdate(NoUpdateReason.CONTRADICTORY_EVIDENCE, assessment);
        }
        if (reasons.contains(IneligibilityReason.ENVIRONMENTAL_FAILURE)) {
            return neutral(observation, decision, request, assessment, ScoreRule.NEUTRAL_ENVIRONMENTAL);
        }
        if (reasons.contains(IneligibilityReason.CANCELLED)) {
            return neutral(observation, decision, request, assessment, ScoreRule.NEUTRAL_CANCELLED);
        }
        if (reasons.contains(IneligibilityReason.PENDING)) {
            return neutral(observation, decision, request, assessment, ScoreRule.NEUTRAL_PENDING);
        }
        if (reasons.contains(IneligibilityReason.IMPORTED_CLAIM)) {
            return neutral(observation, decision, request, assessment, ScoreRule.NEUTRAL_IMPORTED_CLAIM);
        }
        if (reasons.contains(IneligibilityReason.NO_STAGE_ATTRIBUTION)) {
            return noUpdate(NoUpdateReason.NO_STAGE_ATTRIBUTION, assessment);
        }
        return switch (assessment.verdict()) {
            case POSITIVE_CANDIDATE, NEGATIVE_CANDIDATE ->
                    credit(observation, decision, request, bindings, cost, assessment);
            case NOT_ELIGIBLE -> neutral(observation, decision, request, assessment, notEligibleRule(reasons));
        };
    }

    /**
     * Classifies a decision and outcome binding pair against the active definition: mapping version, feature schema and
     * bucket-mapping version must all be the ones this policy's state was built under.
     */
    private Optional<NoUpdateReason> partitionMismatch(CohortBinding decisionBinding, CohortBinding outcomeBinding) {
        if (!definition.mappingVersion().equals(decisionBinding.mappingVersion())
                || !definition.mappingVersion().equals(outcomeBinding.mappingVersion())
                || !definition.cohortMapping().bucketMappingVersion().equals(decisionBinding.bucketMappingVersion())
                || !definition.cohortMapping().bucketMappingVersion().equals(outcomeBinding.bucketMappingVersion())) {
            return Optional.of(NoUpdateReason.MAPPING_MISMATCH);
        }
        String schema = definition.cohortMapping().featureSchemaVersion();
        if (!schema.equals(decisionBinding.featureSchemaVersion()) || !schema.equals(outcomeBinding.featureSchemaVersion())) {
            return Optional.of(NoUpdateReason.FEATURE_SCHEMA_MISMATCH);
        }
        return Optional.empty();
    }

    /** True when the request's features reproduce the cohort bucket the decision recorded. */
    private boolean producedBy(RoutingRequest request, RoutingDecision.Selected decision) {
        return definition.cohortMapping().featureSchemaVersion().equals(request.features().schemaVersion())
                && definition.cohortMapping().bucketOf(request.features()).equals(decision.cohortBinding().cohortBucket());
    }

    private FeedbackDerivation credit(
            RoutingObservation observation,
            RoutingDecision.Selected decision,
            RoutingRequest request,
            TargetBindings bindings,
            Optional<CostReference> cost,
            LearningAssessment assessment) {
        RoutingOutcome outcome = observation.outcome();
        CohortKey key = cohortKey(decision, request, outcome);
        UUID target = bindings.lookup(key);
        if (target == null) {
            return noUpdate(NoUpdateReason.NO_TARGET_BINDING, null);
        }
        ScoreRule rule;
        double score;
        Optional<PeerRevision> peer = Optional.empty();
        if (assessment.verdict() == EligibilityVerdict.POSITIVE_CANDIDATE) {
            boolean refined = cost.isPresent() && config.costObjective().isPresent()
                    && strictlyBetter(observation, request, cost.get(), config.costObjective().get());
            rule = refined ? ScoreRule.REINFORCE_COST_REFINED : ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL;
            score = refined ? config.refinedReinforceScore() : config.reinforceScore();
            peer = refined ? Optional.of(new PeerRevision(cost.get().peer().experienceRef(),
                    cost.get().peer().revision(), cost.get().peer().ledgerSequence())) : Optional.empty();
        } else {
            rule = ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE;
            score = config.penalizeScore();
        }
        return new FeedbackDerivation.Derived(
                feedback(observation, decision, request, assessment, rule, List.of(FeedbackEntry.of(target, score)),
                        peer));
    }

    private FeedbackDerivation neutral(
            RoutingObservation observation,
            RoutingDecision.Selected decision,
            RoutingRequest request,
            LearningAssessment assessment,
            ScoreRule rule) {
        return new FeedbackDerivation.Derived(
                feedback(observation, decision, request, assessment, rule, List.of(), Optional.empty()));
    }

    private RoutingFeedback feedback(
            RoutingObservation observation,
            RoutingDecision.Selected decision,
            RoutingRequest request,
            LearningAssessment assessment,
            ScoreRule rule,
            List<FeedbackEntry> entries,
            Optional<PeerRevision> costPeer) {
        RoutingOutcome outcome = observation.outcome();
        FeedbackDisposition disposition = rule.disposition();
        return new RoutingFeedback(
                RoutingRequest.CONTRACT_VERSION,
                new FeedbackIdentity(observation.experienceRef(), observation.revision(),
                        observation.ledgerSequence(), outcome.decisionRef(), outcome.executedRoute(),
                        definition.mappingVersion(), outcome.evaluationPolicyId(), outcome.evaluationPolicyVersion()),
                observation.supersedesSequence(),
                cohortKey(decision, request, outcome),
                outcome.routingPolicyId(),
                outcome.routingPolicyVersion(),
                outcome.catalogVersion(),
                outcome.cutoff(),
                assessment.derivedAcceptance(),
                rule,
                disposition,
                entries,
                assessment.reasons(),
                assessment.effectiveEvidence(),
                assessment.provenFailures(),
                costPeer);
    }

    private CohortKey cohortKey(
            RoutingDecision.Selected decision, RoutingRequest request, RoutingOutcome outcome) {
        return new CohortKey(outcome.decisionRef().scopeId(), request.stageKind(),
                decision.cohortBinding().cohortBucket(), outcome.executedRoute());
    }

    private ScoreRule notEligibleRule(List<IneligibilityReason> reasons) {
        if (reasons.contains(IneligibilityReason.NO_ATTRIBUTABLE_FAILURE)) {
            return ScoreRule.NEUTRAL_NO_ATTRIBUTABLE_FAILURE;
        }
        if (reasons.contains(IneligibilityReason.STALE_EVALUATION_POLICY)
                || reasons.contains(IneligibilityReason.MISSING_EVIDENCE)
                || reasons.contains(IneligibilityReason.UNKNOWN_EVIDENCE)) {
            return ScoreRule.NEUTRAL_INCOMPLETE_EVIDENCE;
        }
        return ScoreRule.NEUTRAL_UNVALIDATED_ACCEPTANCE;
    }

    private FeedbackDerivation noUpdate(NoUpdateReason reason, LearningAssessment assessment) {
        return assessment == null
                ? new FeedbackDerivation.NoUpdate(reason, List.of(), List.of())
                : new FeedbackDerivation.NoUpdate(reason, assessment.reasons(),
                        reason == NoUpdateReason.PROVENANCE_MISMATCH ? assessment.mismatches() : List.of());
    }

    /**
     * True only for a pairwise statement the contract allows: the peer is another route's execution of the same
     * task and stage kind on the same source, context, constraints and evaluation policy, it was itself validated
     * and attributable, and both measure the declared counter completely with the same provenance.
     */
    private boolean strictlyBetter(
            RoutingObservation observation, RoutingRequest request, CostReference reference, CostObjective objective) {
        RoutingOutcome self = observation.outcome();
        RoutingOutcome peer = reference.peer().outcome();
        RoutingRequest peerRequest = reference.peerRequest();
        if (peer.cutoff() > self.cutoff()) {
            // a later peer would let future acceptance and usage influence feedback as of an earlier cutoff
            return false;
        }
        if (!self.decisionRef().scopeId().equals(peer.decisionRef().scopeId())
                || !self.decisionRef().taskId().equals(peer.decisionRef().taskId())
                || !request.stageKind().equals(peerRequest.stageKind())
                || !self.sourceFingerprint().equals(peer.sourceFingerprint())
                || !self.contextFingerprint().equals(peer.contextFingerprint())
                || !self.constraintsFingerprint().equals(peer.constraintsFingerprint())
                || !self.evaluationPolicyId().equals(peer.evaluationPolicyId())
                || !self.evaluationPolicyVersion().equals(peer.evaluationPolicyVersion())
                || self.executedRoute().equals(peer.executedRoute())
                || self.decisionRef().equals(peer.decisionRef())) {
            return false;
        }
        if (partitionMismatch(reference.peerDecision().cohortBinding(), peer.cohortBinding()).isPresent()
                || !producedBy(peerRequest, reference.peerDecision())) {
            return false;
        }
        LearningAssessment peerAssessment =
                evaluator.assess(reference.peer(), reference.peerDecision(), peerRequest, evidencePolicy);
        if (peerAssessment.verdict() != EligibilityVerdict.POSITIVE_CANDIDATE) {
            return false;
        }
        ResourceValue.Known own = known(self.resources(), objective);
        ResourceValue.Known other = known(peer.resources(), objective);
        if (own == null || other == null || own.provenance() != other.provenance()) {
            return false;
        }
        return objective.direction() == ResourceObjective.Direction.MINIMIZE
                ? own.value() < other.value()
                : own.value() > other.value();
    }

    private ResourceValue.Known known(ResourceSummary resources, CostObjective objective) {
        if (resources.declaration() != UsageDeclaration.MEASURED) {
            return null;
        }
        for (UsageCounter counter : resources.counters()) {
            if (counter.kind() == objective.kind()) {
                return counter.value() instanceof ResourceValue.Known known ? known : null;
            }
        }
        return null;
    }
}
