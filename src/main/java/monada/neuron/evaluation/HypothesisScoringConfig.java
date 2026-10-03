package monada.neuron.evaluation;

/**
 * Configuration of the reference scoring policy.
 *
 * <p>With a component, {@code resonanceWeight * resonance(h)} is added to the support mass, so
 * resonance never outweighs one fully weighted supporting evidence entry. A zero weight never
 * invokes the component.
 *
 * @param resonance optional resonance component; {@code null} means none
 * @param resonanceWeight finite weight in {@code [0, 1]}; must be zero when no component is set
 */
public record HypothesisScoringConfig(HypothesisResonanceComponent resonance, double resonanceWeight) {

    /** Evidence-only scoring with no resonance contribution. */
    public static final HypothesisScoringConfig NONE = new HypothesisScoringConfig(null, 0.0);

    /** Validates the weight range and the component/weight consistency. */
    public HypothesisScoringConfig {
        if (!(resonanceWeight >= 0.0 && resonanceWeight <= 1.0)) {
            throw new IllegalArgumentException(
                    "resonanceWeight must be finite and in [0, 1], got: " + resonanceWeight);
        }
        if (resonance == null && resonanceWeight != 0.0) {
            throw new IllegalArgumentException("resonanceWeight must be zero without a resonance component");
        }
    }

    /** Returns whether the component will be consulted. */
    public boolean usesResonance() {
        return resonance != null && resonanceWeight > 0.0;
    }
}
