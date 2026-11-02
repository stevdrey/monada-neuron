package consumer.forge;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.FeatureReadinessAssessment;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.review.DecisionReview;

import java.util.List;
import java.util.Objects;

/**
 * One answered request, as the host keeps it: the request it built, the decision Neuron returned, the feature
 * assessment it built beside the request, the review projected from the decision, the receipt of the decision, and the
 * draft epoch captured when the request was built. The fingerprints and the catalog version the guard compares are read
 * from the request and the decision's provenance, never copied. Advice is unverified Level A preview output and is
 * never authorization.
 *
 * @param request the request as built from the draft
 * @param decision what {@code decide} returned
 * @param assessment which feature fields the host knew when it built the request
 * @param review the decision projected for the person who accepts or declines it
 * @param receipt the canonical copy of the decision
 * @param capturedEpoch the workspace epoch at request time
 */
public record Advice(
        RoutingRequest request,
        RoutingDecision decision,
        FeatureReadinessAssessment.Assessed assessment,
        DecisionReview review,
        AdviceReceipt receipt,
        int capturedEpoch) {

    public Advice {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(receipt, "receipt");
        if (!DecisionRef.of(request).equals(decision.provenance().decisionRef())) {
            throw new IllegalArgumentException("the decision does not answer the request");
        }
    }

    public DecisionRef ref() {
        return decision.provenance().decisionRef();
    }

    /** The catalog snapshot version the decision was made over, as its provenance records it. */
    public String catalogVersion() {
        return decision.provenance().catalogVersion();
    }

    /** The feature dimensions this host's preview needs and the draft did not supply. */
    public List<FeatureDimension> missingFeatures() {
        return assessment.missingForHostPreview();
    }
}
