package monada.neuron.evolution;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;

import java.util.Objects;

/**
 * Conservative deterministic baseline adaptation policy that updates eligible node states within finite bounds.
 */
public final class DeterministicBaselineAdaptationPolicy implements AdaptationPolicy {

    private static final double TWO_PI = 2.0 * StrictMath.PI;

    private final AdaptationConfig config;

    /** Creates a policy with explicit configuration bounds. */
    public DeterministicBaselineAdaptationPolicy(AdaptationConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Creates a policy with default configuration bounds. */
    public DeterministicBaselineAdaptationPolicy() {
        this(AdaptationConfig.DEFAULT);
    }

    /** Returns the adaptation configuration used by this policy. */
    public AdaptationConfig config() {
        return config;
    }

    @Override
    public AdaptationDecision adapt(Node node, FeedbackInput feedback) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(feedback, "feedback must not be null");
        if (!node.getId().equals(feedback.targetNodeId())) {
            throw new IllegalArgumentException(
                    "feedback targetNodeId must match node id: " + feedback.targetNodeId() + " != " + node.getId());
        }

        double score = feedback.score();
        var previousState = node.getFrequencyState();
        double previousEnergy = node.getEnergy();

        if (score == 0.0) {
            return new AdaptationDecision(
                    node.getId(),
                    false,
                    previousState,
                    previousState,
                    previousEnergy,
                    previousEnergy);
        }

        double candidateAmplitude;
        double candidateFrequency;
        double candidatePhase;
        double candidateEnergy;

        var targetSignal = feedback.targetSignal();
        if (targetSignal != null) {
            var targetState = targetSignal.frequencyState();
            if (score > 0.0) {
                double deltaAmplitude = targetState.amplitude() - previousState.amplitude();
                candidateAmplitude = clamp(
                        previousState.amplitude() + config.learningRate() * score * deltaAmplitude,
                        config.minAmplitude(),
                        config.maxAmplitude());

                double deltaFrequency = targetState.frequency() - previousState.frequency();
                candidateFrequency = clamp(
                        previousState.frequency() + config.learningRate() * score * deltaFrequency,
                        config.minFrequency(),
                        config.maxFrequency());

                double deltaPhase = wrapPhaseDelta(targetState.phase() - previousState.phase());
                candidatePhase = wrapPhase(
                        previousState.phase() + config.learningRate() * score * deltaPhase);

                candidateEnergy = clamp(
                        previousEnergy + config.learningRate() * score * config.energyStep(),
                        config.minEnergy(),
                        config.maxEnergy());
            } else {
                candidateAmplitude = clamp(
                        previousState.amplitude() * (1.0 + config.learningRate() * score),
                        config.minAmplitude(),
                        config.maxAmplitude());

                double deltaFrequency = previousState.frequency() - targetState.frequency();
                candidateFrequency = clamp(
                        previousState.frequency() - config.learningRate() * score * deltaFrequency,
                        config.minFrequency(),
                        config.maxFrequency());

                candidatePhase = previousState.phase();

                candidateEnergy = clamp(
                        previousEnergy + config.learningRate() * score * config.energyStep(),
                        config.minEnergy(),
                        config.maxEnergy());
            }
        } else {
            candidateAmplitude = clamp(
                    previousState.amplitude() * (1.0 + config.learningRate() * score),
                    config.minAmplitude(),
                    config.maxAmplitude());

            candidateFrequency = previousState.frequency();
            candidatePhase = previousState.phase();

            candidateEnergy = clamp(
                    previousEnergy + config.learningRate() * score * config.energyStep(),
                    config.minEnergy(),
                    config.maxEnergy());
        }

        var newState = new FrequencyState(candidateAmplitude, candidateFrequency, candidatePhase);
        double newEnergy = candidateEnergy;

        boolean stateChanged = !newState.equals(previousState);
        boolean energyChanged = Double.compare(newEnergy, previousEnergy) != 0;
        boolean adapted = stateChanged || energyChanged;

        if (stateChanged) {
            node.transition(newState);
        }
        if (energyChanged) {
            node.setEnergy(newEnergy);
        }

        return new AdaptationDecision(
                node.getId(),
                adapted,
                previousState,
                newState,
                previousEnergy,
                newEnergy);
    }

    private double clamp(double value, double min, double max) {
        return Math.clamp(value, min, max);
    }

    private double wrapPhaseDelta(double delta) {
        return StrictMath.IEEEremainder(delta, TWO_PI);
    }

    private double wrapPhase(double phase) {
        double wrapped = StrictMath.IEEEremainder(phase, TWO_PI);
        if (wrapped < 0.0) {
            wrapped += TWO_PI;
        }
        return wrapped;
    }
}
