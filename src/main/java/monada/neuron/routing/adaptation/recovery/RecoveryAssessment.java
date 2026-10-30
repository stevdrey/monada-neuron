package monada.neuron.routing.adaptation.recovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What {@link CheckpointRecovery#assess} found: exactly one of {@link Compatible} or {@link Blocked}. Neither holds a
 * store or a checkpoint; a blocked assessment is never an empty state, so a failed preflight cannot pass for a cold
 * start. It describes the target store as it was at the call: the store is mutable, so a host that consumes
 * observations or compacts afterwards assesses again.
 */
public sealed interface RecoveryAssessment {

    /**
     * {@code rebuildFrom} with this checkpoint and export would pass every check before replay. Nothing is restored
     * yet, and the {@link #caveats()} say what the preflight cannot see.
     *
     * @param checkpointCutoff the checkpoint's cutoff
     * @param exportHighWatermark the export's high watermark, at or above the cutoff
     * @param window the part of the export the replay consumes
     * @param caveats the fixed caveats, plus {@link Caveat#ROUTING_POLICY_CHANGED} when it applies, in declaration order
     */
    record Compatible(long checkpointCutoff, long exportHighWatermark, ReplayWindow window, List<Caveat> caveats)
            implements RecoveryAssessment {

        /** Requires consistent positions and copies the caveats. */
        public Compatible {
            if (checkpointCutoff < 0) {
                throw new IllegalArgumentException("checkpointCutoff must be non-negative, got: " + checkpointCutoff);
            }
            if (exportHighWatermark < checkpointCutoff) {
                throw new IllegalArgumentException("a compatible export reaches the checkpoint's cutoff "
                        + checkpointCutoff + ", got: " + exportHighWatermark);
            }
            Objects.requireNonNull(window, "window must not be null");
            caveats = List.copyOf(Objects.requireNonNull(caveats, "caveats must not be null"));
        }
    }

    /**
     * {@code rebuildFrom} would refuse, for every listed reason. The list holds at most one blocker per rule, ordered by
     * {@link BlockerKind}; with an observed export the first is what {@code rebuildFrom} throws for, and
     * {@link RecoveryPlan#of} plans from it.
     *
     * @param blockers at least one blocker
     */
    record Blocked(List<RecoveryBlocker> blockers) implements RecoveryAssessment {

        /** Requires at least one blocker and orders them by kind, stably. */
        public Blocked {
            Objects.requireNonNull(blockers, "blockers must not be null");
            if (blockers.isEmpty()) {
                throw new IllegalArgumentException("a blocked assessment names at least one blocker");
            }
            var ordered = new ArrayList<RecoveryBlocker>(blockers.size());
            for (RecoveryBlocker blocker : blockers) {
                ordered.add(Objects.requireNonNull(blocker, "blockers must not contain null"));
            }
            ordered.sort(Comparator.comparing(RecoveryBlocker::kind));
            blockers = List.copyOf(ordered);
        }
    }
}
