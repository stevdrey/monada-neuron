package monada.neuron.evaluation;

import monada.neuron.reasoning.Hypothesis;

/**
 * Optional support signal for a candidate, supplied by an adapter.
 *
 * <p>Implementations must be deterministic and side-effect free and must return a finite value in
 * {@code [0, 1]}; anything else makes evaluation fail rather than being clamped. The value can only
 * add support: a low value is absence of support, not contradiction. The core defines no memory or
 * vendor type; deriving the value from the Resonance Store belongs behind an adapter.
 */
@FunctionalInterface
public interface HypothesisResonanceComponent {

    /** Returns the resonance support of one candidate in {@code [0, 1]}. */
    double resonance(Hypothesis hypothesis);
}
