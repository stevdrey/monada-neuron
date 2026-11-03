package monada.neuron.evaluation.routing;

import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.adaptation.recovery.RecoveryAssessment;
import monada.neuron.routing.adaptation.recovery.RecoveryPlan;

import java.util.Objects;
import java.util.Optional;

/**
 * One restart case of the host scenario suite: a persisted checkpoint assessed against a fresh target store, what the
 * host did next and whether the result matched the from scratch reference.
 *
 * @param caseId the case name
 * @param assessment what {@code CheckpointRecovery.assess} reported
 * @param rebuild what {@code rebuildFrom} returned; empty for a blocked case, which never rebuilds
 * @param plan the recovery plan: {@code RecoveryPlan.of} of the assessment, or {@code RecoveryPlan.after} of a failed
 *        rebuild
 * @param checkpointSha256 SHA-256 of the checkpoint bytes the case assessed; empty when they were never encoded
 * @param checkpointBytes size of those bytes
 * @param outcome how the case ended
 * @param parity whether the recovered state equals its from scratch reference; false for a blocked case
 */
record RecoveryProbe(
        String caseId,
        RecoveryAssessment assessment,
        Optional<RebuildResult> rebuild,
        RecoveryPlan plan,
        Optional<String> checkpointSha256,
        int checkpointBytes,
        RecoveryOutcome outcome,
        boolean parity) {

    /** How a restart case ended. */
    enum RecoveryOutcome {
        /** The checkpoint was compatible and {@code rebuildFrom} rebuilt the state. */
        COMPATIBLE_REBUILT,
        /** {@code rebuildFrom} stopped, so the host rebuilt from scratch instead. */
        REBUILD_FAILED_SCRATCH_FALLBACK,
        /** The assessment blocked the checkpoint; nothing was rebuilt. */
        BLOCKED
    }

    /** Validates. */
    RecoveryProbe {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(rebuild, "rebuild must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(checkpointSha256, "checkpointSha256 must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if ((outcome == RecoveryOutcome.BLOCKED) != rebuild.isEmpty()) {
            throw new IllegalArgumentException("exactly a blocked case never rebuilds");
        }
    }
}
