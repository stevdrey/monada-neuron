package monada.neuron.routing.adaptation;

import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.model.NodeType;
import monada.neuron.routing.ChangeSizeCohortMapping;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.feedback.RoutingFeedbackConfig;
import monada.neuron.routing.feedback.RoutingFeedbackPolicy;
import monada.neuron.routing.outcome.EvidencePolicy;

import java.util.Objects;

/**
 * The composite state-transition definition of an adaptive routing state: the cohort mapping, the initial Node
 * state, the adaptation bounds and the feedback scores, all identified by the one {@code mappingVersion} of
 * {@link #state()} (contract section 7).
 *
 * <p>Any change to any part changes what is learned, so the host must give it a new {@code mappingVersion}; a snapshot
 * built under another version is incompatible and never silently reused. It is additive: the existing
 * {@link RoutingStateDefinition} keeps its signature and remains what a routing policy admits snapshots against.
 *
 * <p>The score-only adaptation path multiplies the amplitude, so the definition requires
 * {@code minAmplitude > 0} and {@code minAmplitude < baselineAmplitude <= maxAmplitude}: a cohort amplitude stays
 * strictly positive and a penalized route can still recover. The initial energy and frequency must lie inside the
 * adaptation bounds.
 *
 * @param state cohort mapping and composite {@code mappingVersion}
 * @param initial state every cohort Node starts from
 * @param adaptation bounds and rate of {@code DeterministicBaselineAdaptationPolicy}
 * @param feedback scores feedback derivation assigns
 */
public record AdaptiveStateDefinition(
        RoutingStateDefinition state,
        InitialNodeState initial,
        AdaptationConfig adaptation,
        RoutingFeedbackConfig feedback) {

    /** Validates the parts against each other. */
    public AdaptiveStateDefinition {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(initial, "initial must not be null");
        Objects.requireNonNull(adaptation, "adaptation must not be null");
        Objects.requireNonNull(feedback, "feedback must not be null");
        if (adaptation.minAmplitude() <= 0.0) {
            throw new IllegalArgumentException(
                    "routing adaptation requires minAmplitude > 0, got: " + adaptation.minAmplitude());
        }
        double baseline = initial.baselineAmplitude();
        if (baseline <= adaptation.minAmplitude() || baseline > adaptation.maxAmplitude()) {
            throw new IllegalArgumentException("baselineAmplitude must lie in (minAmplitude, maxAmplitude] = ("
                    + adaptation.minAmplitude() + ", " + adaptation.maxAmplitude() + "], got: " + baseline);
        }
        if (initial.energy() < adaptation.minEnergy() || initial.energy() > adaptation.maxEnergy()) {
            throw new IllegalArgumentException("initial energy must lie in [minEnergy, maxEnergy] = ["
                    + adaptation.minEnergy() + ", " + adaptation.maxEnergy() + "], got: " + initial.energy());
        }
        if (initial.frequency() < adaptation.minFrequency() || initial.frequency() > adaptation.maxFrequency()) {
            throw new IllegalArgumentException("initial frequency must lie in [minFrequency, maxFrequency] = ["
                    + adaptation.minFrequency() + ", " + adaptation.maxFrequency() + "], got: "
                    + initial.frequency());
        }
    }

    /**
     * Reference definition {@code adaptive-routing-state/1} over {@link ChangeSizeCohortMapping#DEFAULT}.
     *
     * <p>Baseline amplitude {@code 1.0} inside {@code [0.125, 8.0]} (three octaves either way), learning rate
     * {@code 0.1}, energy {@code 10.0} inside {@code [0, 1000]} with step {@code 1.0}, and
     * {@link RoutingFeedbackConfig#DEFAULT} scores: a reward multiplies the amplitude by {@code 1.1}, a penalty by
     * {@code 0.95}, so at least 22 consecutive rewards reach the ceiling and at least 41 consecutive penalties the
     * floor. These are conservative illustrative values, not tuned or calibrated ones; no effectiveness claim is made.
     */
    public static AdaptiveStateDefinition reference() {
        return new AdaptiveStateDefinition(
                new RoutingStateDefinition("adaptive-routing-state/1", ChangeSizeCohortMapping.DEFAULT),
                new InitialNodeState(1.0, 0.0, 0.0, 10.0, NodeType.MEMORY),
                new AdaptationConfig(0.1, 0.125, 8.0, 0.0, 1000.0, 0.0, 10_000.0, 1.0),
                RoutingFeedbackConfig.DEFAULT);
    }

    /** The composite version every part of this definition is identified by. */
    public String mappingVersion() {
        return state.mappingVersion();
    }

    /** The feature schema the cohort mapping was built for. */
    public String featureSchemaVersion() {
        return state.cohortMapping().featureSchemaVersion();
    }

    /** A feedback policy that derives under exactly this definition's mapping and scores. */
    public RoutingFeedbackPolicy feedbackPolicy(EvidencePolicy evidencePolicy) {
        return new RoutingFeedbackPolicy(state, feedback, evidencePolicy);
    }
}
