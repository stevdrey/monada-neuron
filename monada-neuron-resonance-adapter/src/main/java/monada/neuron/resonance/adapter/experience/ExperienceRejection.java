package monada.neuron.resonance.adapter.experience;

import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;

/**
 * A deterministic, inspectable refusal of one sample.
 *
 * @param executionRef exact reference of the execution's first sample event, formatted like an observation reference
 * @param reason typed reason
 * @param detail bounded human-readable detail
 */
public record ExperienceRejection(String executionRef, RejectionReason reason, String detail) {

    /** Validates the fields. */
    public ExperienceRejection {
        OpaqueTokens.requireText(executionRef, "executionRef");
        Objects.requireNonNull(reason, "reason must not be null");
        OpaqueTokens.requireText(detail, "detail");
    }
}
