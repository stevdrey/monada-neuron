package monada.neuron.routing.outcome;

/** Acceptance derived exactly as the Store's execution-memory contract defines it (section 8). */
public enum DerivedAcceptance {
    VALIDATED_ACCEPTED, ACCEPTED_UNVALIDATED, NOT_ACCEPTED, PENDING
}
