/**
 * Read only recovery diagnostics for routing state checkpoints in the opt-in Forge routing extension (issue #101).
 *
 * <p>A host that decoded a {@link monada.neuron.routing.adaptation.RoutingStateCheckpoint} asks
 * {@link monada.neuron.routing.adaptation.recovery.CheckpointRecovery#assess} whether
 * {@code RoutingStateStore.rebuildFrom} would accept it on the store it means to restore into, over the export it holds,
 * before it replays. The answer is {@code Compatible} (with the caveats a preflight cannot see past) or
 * {@code Blocked} with every reason, in the order the store checks them, through the store's own
 * {@link monada.neuron.routing.adaptation.RoutingStateStore#restoreProblems}: one rulebook, never a second one.
 * {@link monada.neuron.routing.adaptation.recovery.RecoveryPlan} turns an assessment, or a replay that stopped, into
 * the next actions. None of them discards learning: a host starting cold does that by its own choice.
 *
 * <p>Nothing here changes a store, builds one, reads a file or logs. Blockers and steps carry typed components the host
 * already owns, no message text. A compatible assessment describes the store at the call; a host that consumes or
 * compacts afterwards assesses again. The host flow and failure matrix are in
 * {@code docs/specs/routing-checkpoint-recovery-v1.md}.
 *
 * <pre>{@code
 * var decoded = CheckpointCodec.decode(bytes);
 * var target = new RoutingStateStore(definition, partition);
 * var assessment = CheckpointRecovery.assess(decoded, target, new ExportState.Observed(export.highWatermark()));
 * var plan = RecoveryPlan.of(assessment);
 * if (assessment instanceof RecoveryAssessment.Compatible ok
 *         && decoded instanceof CheckpointDecodeResult.Decoded d) {
 *     var result = target.rebuildFrom(d.checkpoint(), export.observations(), ok.exportHighWatermark(), derive);
 *     if (result instanceof RebuildResult.Failed failed) {
 *         plan = RecoveryPlan.after(failed);
 *     }
 * }
 * }</pre>
 */
package monada.neuron.routing.adaptation.recovery;
