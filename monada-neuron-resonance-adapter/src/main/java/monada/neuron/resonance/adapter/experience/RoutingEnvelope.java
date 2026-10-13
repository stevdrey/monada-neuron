package monada.neuron.resonance.adapter.experience;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.token.OpaqueTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The versioned routing envelope Forge holds for one routed stage and references from the Store through an opaque
 * artifact (contract Section 3.2). It lets the translator recover the decision, the route and the cohort binding without
 * any Store routing field. The descriptor snapshot is inside the digest-protected payload, so no catalog lookup exists.
 *
 * @param contractVersion must be {@link RoutingRequest#CONTRACT_VERSION}
 * @param decisionRef the decision this stage executed
 * @param route the executed route
 * @param descriptor snapshot of the executed route's worker description
 * @param catalogVersion catalog snapshot identity
 * @param routingPolicyId routing policy id
 * @param routingPolicyVersion routing policy version
 * @param evaluationPolicyId evaluation policy id
 * @param evaluationPolicyVersion evaluation policy version
 * @param cohortBinding the cohort binding of the decision, or unknown when the policy computed none
 * @param executionMode opaque execution mode token
 */
public record RoutingEnvelope(
        String contractVersion,
        DecisionRef decisionRef,
        RouteKey route,
        ExecutedRouteDescriptor descriptor,
        String catalogVersion,
        String routingPolicyId,
        String routingPolicyVersion,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        Feature<CohortBinding> cohortBinding,
        String executionMode) {

    /** Artifact kind that references an envelope on a Store stage event. */
    public static final String ARTIFACT_KIND = "neuron-routing-envelope/1";

    /** Validates the envelope. */
    public RoutingEnvelope {
        Objects.requireNonNull(contractVersion, "contractVersion must not be null");
        if (!RoutingRequest.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported contractVersion: " + contractVersion);
        }
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        OpaqueTokens.require(catalogVersion, "catalogVersion");
        OpaqueTokens.require(routingPolicyId, "routingPolicyId");
        OpaqueTokens.require(routingPolicyVersion, "routingPolicyVersion");
        OpaqueTokens.require(evaluationPolicyId, "evaluationPolicyId");
        OpaqueTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
        Objects.requireNonNull(cohortBinding, "cohortBinding must not be null");
        OpaqueTokens.require(executionMode, "executionMode");
    }

    /**
     * Canonical payload: fixed field order, UTF-8, every string as {@code <code-point-count>:<text>} so no delimiter can
     * be forged by a token, an absent value as {@code -}.
     *
     * @return the canonical bytes whose SHA-256 Forge records as the artifact digest
     */
    public byte[] canonicalPayload() {
        var out = new StringBuilder(256);
        text(out, contractVersion);
        text(out, decisionRef.scopeId());
        text(out, decisionRef.taskId());
        text(out, decisionRef.executionId());
        text(out, decisionRef.attemptId());
        text(out, decisionRef.stageId());
        out.append(decisionRef.requestOrdinal()).append('\n');
        text(out, route.routeId());
        out.append(route.routeVersion()).append('\n');
        text(out, descriptor.worker());
        optional(out, descriptor.provider());
        optional(out, descriptor.model());
        optional(out, descriptor.effort());
        text(out, descriptor.billingMode().name());
        text(out, catalogVersion);
        text(out, routingPolicyId);
        text(out, routingPolicyVersion);
        text(out, evaluationPolicyId);
        text(out, evaluationPolicyVersion);
        if (cohortBinding instanceof Feature.Known<CohortBinding> known) {
            CohortBinding binding = known.value();
            text(out, binding.cohortBucket());
            text(out, binding.bucketMappingVersion());
            text(out, binding.mappingVersion());
            text(out, binding.featureSchemaVersion());
        } else {
            out.append("-\n");
        }
        text(out, executionMode);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * SHA-256 of {@link #canonicalPayload()}.
     *
     * @return lower-case hexadecimal digest
     */
    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalPayload()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static void text(StringBuilder out, String value) {
        out.append(value.codePointCount(0, value.length())).append(':').append(value).append('\n');
    }

    private static void optional(StringBuilder out, Feature<String> value) {
        if (value instanceof Feature.Known<String> known) {
            text(out, known.value());
        } else {
            out.append("-\n");
        }
    }
}
