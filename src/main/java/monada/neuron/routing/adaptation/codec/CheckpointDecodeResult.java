package monada.neuron.routing.adaptation.codec;

import monada.neuron.routing.adaptation.RoutingStateCheckpoint;

import java.util.Objects;

/**
 * What {@link CheckpointCodec#decode} made of a byte string: exactly one of {@link Decoded} or {@link Rejected}. A
 * rejection is never an empty or default checkpoint, so damaged bytes cannot pass for a cold start.
 */
public sealed interface CheckpointDecodeResult {

    /**
     * The bytes are the canonical encoding of a valid checkpoint, and their checksum matches. The checksum proves the
     * bytes are unchanged, not who wrote them.
     *
     * @param checkpoint the checkpoint, equal to the encoded one in every component, each marker's {@code cutoff}
     *        included
     */
    record Decoded(RoutingStateCheckpoint checkpoint) implements CheckpointDecodeResult {

        /** Requires a checkpoint. */
        public Decoded {
            Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        }
    }

    /**
     * The bytes are not a canonical v1 checkpoint. Only the reason is kept: messages could echo host tokens.
     *
     * @param reason the first failure in the format's check order
     */
    record Rejected(CheckpointRejectReason reason) implements CheckpointDecodeResult {

        /** Requires a reason. */
        public Rejected {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
