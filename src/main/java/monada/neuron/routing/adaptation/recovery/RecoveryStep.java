package monada.neuron.routing.adaptation.recovery;

import monada.neuron.routing.adaptation.StateUpdate;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One step of a {@link RecoveryPlan}.
 *
 * @param action what to do
 * @param cutoffBelow for {@link RecoveryAction#USE_OLDER_CHECKPOINT} only: the exclusive upper bound of the older
 *        checkpoint's cutoff, always above 0
 * @param update for {@link RecoveryAction#INSPECT_FAILED_OBSERVATION} only: what consuming the observation reported
 */
public record RecoveryStep(RecoveryAction action, OptionalLong cutoffBelow, Optional<StateUpdate> update) {

    /** Requires each component exactly for the action that carries it. */
    public RecoveryStep {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(cutoffBelow, "cutoffBelow must not be null");
        Objects.requireNonNull(update, "update must not be null");
        if (cutoffBelow.isPresent() != (action == RecoveryAction.USE_OLDER_CHECKPOINT)) {
            throw new IllegalArgumentException("cutoffBelow is carried by USE_OLDER_CHECKPOINT only, got: " + action);
        }
        if (cutoffBelow.isPresent() && cutoffBelow.getAsLong() <= 0) {
            throw new IllegalArgumentException("no checkpoint lies strictly below " + cutoffBelow.getAsLong());
        }
        if (update.isPresent() != (action == RecoveryAction.INSPECT_FAILED_OBSERVATION)) {
            throw new IllegalArgumentException("update is carried by INSPECT_FAILED_OBSERVATION only, got: " + action);
        }
    }

    /** A step that carries nothing. */
    public static RecoveryStep of(RecoveryAction action) {
        return new RecoveryStep(action, OptionalLong.empty(), Optional.empty());
    }

    /** {@code USE_OLDER_CHECKPOINT} with its bound. */
    public static RecoveryStep olderCheckpoint(long cutoffBelow) {
        return new RecoveryStep(RecoveryAction.USE_OLDER_CHECKPOINT, OptionalLong.of(cutoffBelow), Optional.empty());
    }

    /** {@code INSPECT_FAILED_OBSERVATION} with what the replay reported. */
    public static RecoveryStep inspect(StateUpdate update) {
        return new RecoveryStep(RecoveryAction.INSPECT_FAILED_OBSERVATION, OptionalLong.empty(),
                Optional.of(Objects.requireNonNull(update, "update must not be null")));
    }
}
