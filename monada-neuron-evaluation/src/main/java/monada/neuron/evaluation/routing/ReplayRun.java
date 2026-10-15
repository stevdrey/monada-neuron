package monada.neuron.evaluation.routing;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.outcome.RoutingObservation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one arm's replay produced.
 *
 * @param arm the arm
 * @param samples one sample per task, in replay order (empty when the run did not record samples)
 * @param feedback one event per delivered observation, in delivery order (empty when not recorded)
 * @param delivered every observation delivered to the feedback policy, in delivery order
 * @param decisions every decision's route, kind, basis, abstention, coverage and snapshot watermark, kept whether or
 *        not samples are recorded, so an unrecorded (measured) run can be checked against its reference
 * @param derivations every delivery's derivation by rule and reason, counted whether or not events are recorded
 * @param state the final caller-owned state; absent for the static arm, which keeps none. The run hands it over:
 *        nothing else holds it
 * @param rebuilds how the run's correction-triggered rebuilds were carried out
 */
public record ReplayRun(
        ReplayArm arm,
        List<ReplaySample> samples,
        List<FeedbackEvent> feedback,
        List<Delivered> delivered,
        DecisionTrace decisions,
        Derivations derivations,
        Optional<RoutingStateStore> state,
        RebuildStats rebuilds) {

    /**
     * Rebuild work of one run: what the checkpoint strategy is meant to reduce.
     *
     * @param strategy where rebuilds started
     * @param rebuilds correction-triggered rebuilds
     * @param fromCheckpoint rebuilds that started from a checkpoint
     * @param replayedObservations observations handed to all rebuild calls together
     * @param checkpoints checkpoints taken
     * @param compactions compactions that dropped at least one entry
     * @param compactedEntries ledger entries dropped by compaction
     */
    public record RebuildStats(RebuildStrategy strategy, int rebuilds, int fromCheckpoint, long replayedObservations,
            int checkpoints, int compactions, int compactedEntries) {

        /** No rebuild work: a run that never rebuilt from scratch. */
        public static final RebuildStats NONE = new RebuildStats(RebuildStrategy.SCRATCH, 0, 0, 0L, 0, 0, 0);

        /** Requires the strategy. */
        public RebuildStats {
            Objects.requireNonNull(strategy, "strategy must not be null");
        }
    }

    /** A run that did no rebuild work, such as one assembled by hand. */
    public ReplayRun(ReplayArm arm, List<ReplaySample> samples, List<FeedbackEvent> feedback, List<Delivered> delivered,
            DecisionTrace decisions, Derivations derivations, Optional<RoutingStateStore> state) {
        this(arm, samples, feedback, delivered, decisions, derivations, state, RebuildStats.NONE);
    }

    /**
     * An observation and the decision and request it answers.
     *
     * @param observation the observation
     * @param decision the decision it answers
     * @param request the request of that decision
     * @param replayed whether the decision is a replayed one (synthetic outcome) rather than a logged one (export)
     */
    public record Delivered(RoutingObservation observation, RoutingDecision.Selected decision, RoutingRequest request,
            boolean replayed) {

        /** Validates the fields. */
        public Delivered {
            Objects.requireNonNull(observation, "observation must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(request, "request must not be null");
        }
    }

    /** Copies the lists. */
    public ReplayRun {
        Objects.requireNonNull(arm, "arm must not be null");
        samples = List.copyOf(samples);
        feedback = List.copyOf(feedback);
        delivered = List.copyOf(delivered);
        Objects.requireNonNull(decisions, "decisions must not be null");
        Objects.requireNonNull(derivations, "derivations must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(rebuilds, "rebuilds must not be null");
    }
}
