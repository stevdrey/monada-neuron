package monada.neuron.reasoning;

/**
 * Opaque, modality-neutral symbolic statement a hypothesis asserts.
 *
 * <p>Equality is structural: two hypotheses with equal propositions are equivalent claims. The
 * meaning of {@code domain} and {@code code} belongs to the producing reasoning capability.
 *
 * @param domain non-negative namespace of the producing capability
 * @param code capability-defined identifier of the statement within its domain
 */
public record Proposition(int domain, long code) {

    /** Validates the namespace. */
    public Proposition {
        if (domain < 0) {
            throw new IllegalArgumentException("domain must be non-negative, got: " + domain);
        }
    }
}
