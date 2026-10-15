package monada.neuron.evaluation.routing;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.AttemptStatus;
import monada.neuron.routing.outcome.EvidenceRef;
import monada.neuron.routing.outcome.EvidenceSource;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityObservation;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.outcome.StageAttribution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link ScriptedOutcome} into the {@code RoutingObservation} a host would export for one {@code Selected}
 * decision, through the public routing API only.
 *
 * <p>The observation answers exactly the given decision (route, cohort binding, catalog, policy and fingerprints), its
 * evidence sits one sequence before the outcome, and the export cutoff is the observation's own position, so nothing
 * observed later is included. A correction is revision 1 of the same sample and supersedes revision 0.
 */
public final class ObservationFactory {

    /** Judge identity written on every synthetic quality observation. */
    static final String JUDGE = "replay-judge";

    /** Prefix of every synthetic sample reference. */
    static final String REF_PREFIX = "exp-sha256:";

    /**
     * The sample reference of a decision: SHA-256 over a length-prefixed UTF-8 encoding of every {@link DecisionRef}
     * field. Opaque tokens may contain any separator, so plain concatenation could map two decisions to one reference;
     * the length prefixes make the encoding injective and the digest keeps the reference within the 512 code points a
     * reference allows, whatever the fields' lengths.
     */
    public String experienceRef(RoutingRequest request) {
        DecisionRef ref = DecisionRef.of(request);
        var encoded = new StringBuilder();
        for (String field : List.of(ref.scopeId(), ref.taskId(), ref.executionId(), ref.attemptId(), ref.stageId(),
                Long.toString(ref.requestOrdinal()))) {
            encoded.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field).append('|');
        }
        return REF_PREFIX + sha256Hex(encoded.toString());
    }

    /**
     * The attempt a repaired outcome was accepted on: {@code repair-} and the first 32 hex digits of the SHA-256 of the
     * routed attempt id. Deterministic and 39 code points long whatever the routed id's length, so a legal
     * 128-code-point attempt id never pushes it past the token bound.
     *
     * @throws IllegalStateException if it would equal the routed attempt id
     */
    String repairAttemptId(String routedAttemptId) {
        String repair = "repair-" + sha256Hex(routedAttemptId).substring(0, 32);
        if (repair.equals(routedAttemptId)) {
            throw new IllegalStateException("the repair attempt id would equal the routed attempt id " + repair);
        }
        return repair;
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    /**
     * The observation of one revision.
     *
     * @param request the request of the decision
     * @param decision the decision that advised the executed route
     * @param outcome the scripted outcome of that route
     * @param revision 0 for the original, 1 for its correction (which must exist)
     * @return the observation
     */
    public RoutingObservation observe(RoutingRequest request, RoutingDecision.Selected decision,
            ScriptedOutcome outcome, int revision) {
        if (revision != 0 && (revision != 1 || outcome.correction().isEmpty())) {
            throw new IllegalArgumentException("no revision " + revision + " for " + request.executionId());
        }
        boolean corrected = revision == 1;
        long position = corrected ? outcome.correction().get().observedAt() : outcome.observedAt();
        OutcomeStatus status = corrected ? outcome.correction().get().status() : outcome.status();
        Map<QualityDimension, QualityState> quality = corrected ? outcome.correction().get().quality()
                : outcome.quality();
        ChainOutcome chain = corrected ? outcome.correction().get().chain() : outcome.chain();
        boolean repaired = outcome.acceptedByRepair() && status == OutcomeStatus.ACCEPTED;
        String evidenceAttempt = repaired ? repairAttemptId(request.attemptId()) : request.attemptId();
        String ref = experienceRef(request);

        var observations = new ArrayList<QualityObservation>(quality.size());
        for (QualityDimension dimension : QualityDimension.values()) {
            QualityState state = quality.get(dimension);
            if (state == null) {
                continue;
            }
            Feature<String> justification = state == QualityState.NOT_APPLICABLE
                    ? Feature.known("not applicable to this fixture stage") : Feature.unknown();
            observations.add(new QualityObservation("ev-" + dimension.name().toLowerCase(Locale.ROOT) + "-r" + revision,
                    0, dimension, state, justification, JUDGE, "1", request.evaluationPolicyId(),
                    request.evaluationPolicyVersion(), EvidenceSource.TOOL, evidenceAttempt, position - 1, List.of()));
        }
        StageAttribution attribution = switch (outcome.attribution()) {
            case STAGE_LOCAL -> new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, 1,
                    List.of(new EvidenceRef("stage-log", ref, Feature.unknown())));
            case CHAIN_ONLY -> new StageAttribution(StageAttribution.Basis.CHAIN_ONLY, Math.max(2, chain.attempts()),
                    List.of());
            case NONE -> StageAttribution.none();
        };
        var routed = new RoutingOutcome(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(request), decision.route(),
                decision.cohortBinding(), decision.provenance().catalogVersion(), decision.provenance().policyId(),
                decision.provenance().policyVersion(), request.sourceFingerprint(), request.contextFingerprint(),
                request.constraintsFingerprint(), request.evaluationPolicyId(), request.evaluationPolicyVersion(),
                position, outcome.invocation(), status, outcome.origin(),
                status == OutcomeStatus.ACCEPTED ? Feature.known(evidenceAttempt) : Feature.unknown(),
                repaired ? Feature.known(AttemptStatus.COMPLETED) : Feature.unknown(), position, observations,
                List.of(), outcome.stage(), attribution);
        return new RoutingObservation(routed, ref, revision, position,
                corrected ? Feature.known(outcome.observedAt()) : Feature.unknown());
    }

    /** The whole-chain facts of a revision. */
    public ChainOutcome chainOf(ScriptedOutcome outcome, int revision) {
        return revision == 1 ? outcome.correction().orElseThrow().chain() : outcome.chain();
    }
}
