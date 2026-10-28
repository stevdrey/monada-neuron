/**
 * Portable byte codec for routing state checkpoints in the opt-in Forge routing extension (issue #100, ADR 0044).
 *
 * <p>{@link monada.neuron.routing.adaptation.codec.CheckpointCodec#encode} turns a
 * {@link monada.neuron.routing.adaptation.RoutingStateCheckpoint} into one canonical byte string of format
 * {@code routing-checkpoint/1}, ending with the SHA-256 of everything before it, so a host can keep learned routing
 * state across restarts in storage it owns. {@link monada.neuron.routing.adaptation.codec.CheckpointCodec#decode}
 * turns those bytes back into the same checkpoint, or returns a coded
 * {@link monada.neuron.routing.adaptation.codec.CheckpointRejectReason}; it never repairs input and never returns an
 * empty checkpoint for bad input, so damaged bytes cannot pass for a cold start.
 *
 * <p>The checksum is integrity, not authenticity: anyone holding the bytes can compute a valid checksum for any
 * checkpoint. A host that must know who wrote a checkpoint adds its own signature or authenticated storage outside
 * Neuron. The codec works on in-memory arrays only; it reads no file, opens no connection and logs nothing.
 * Checkpoint bytes hold host identifiers and learned state, so Neuron never logs, persists or transports them. A
 * decoded checkpoint still restores state only through {@code RoutingStateStore.rebuildFrom}. The byte format is
 * normative in {@code docs/specs/routing-checkpoint-v1.md}.
 */
package monada.neuron.routing.adaptation.codec;
