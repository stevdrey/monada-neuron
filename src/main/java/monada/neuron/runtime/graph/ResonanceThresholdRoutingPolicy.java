package monada.neuron.runtime.graph;

import monada.neuron.model.NodeView;
import monada.neuron.resonance.ResonanceMetric;
import monada.neuron.signal.Signal;

import java.util.Objects;

/**
 * Routes signals whose frequency state resonates with the target node at or above a threshold.
 *
 * <p>The policy delegates scoring to an injected {@link ResonanceMetric}; it does not hard-code a
 * particular resonance formula. Signal kind, source-node state, and node energy do not affect the
 * decision.
 */
public final class ResonanceThresholdRoutingPolicy implements SignalRoutingPolicy {

    private final ResonanceMetric metric;
    private final double minimumScore;

    /**
     * Creates a threshold policy.
     *
     * @param metric resonance metric used for each routing decision
     * @param minimumScore inclusive finite threshold in {@code [0, 1]}
     */
    public ResonanceThresholdRoutingPolicy(ResonanceMetric metric, double minimumScore) {
        this.metric = Objects.requireNonNull(metric, "metric must not be null");
        if (!Double.isFinite(minimumScore)) {
            throw new IllegalArgumentException(
                    "minimumScore must be finite, got: " + minimumScore);
        }
        if (minimumScore < 0.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException(
                    "minimumScore must be in [0, 1], got: " + minimumScore);
        }
        this.minimumScore = minimumScore;
    }

    /** Returns the inclusive score threshold used by this policy. */
    public double minimumScore() {
        return minimumScore;
    }

    @Override
    public boolean shouldRoute(NodeView source, NodeView target, Signal signal) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(signal, "signal must not be null");
        return metric.score(signal.frequencyState(), target.getFrequencyState()) >= minimumScore;
    }
}
