# ADR 0045: Checkpoint Recovery Diagnostics Without Implicit State Reset

## Status

Accepted (issue #101; adds `RoutingStateStore.restoreProblems` with `RestoreProblem`, the package
`monada.neuron.routing.adaptation.recovery`, and amends `forge-integration-readiness/1` under ADR 0033 as a minor,
additive bump)

## Context

[ADR 0031](0031-routing-state-checkpoint-and-ledger-compaction.md) gave `RoutingStateStore.rebuildFrom`: a staged
replay from a checkpoint that returns `Rebuilt` or `Failed`, after checks that throw `IllegalArgumentException`.
[ADR 0044](0044-routing-checkpoint-byte-codec.md) lets a host store a checkpoint as bytes, so after a restart the host
holds bytes it may have damaged, taken from another partition, or taken under an older definition, plus a Store export
it may or may not have.

The forces:

1. **A restore either works or throws, with one message for five mismatches.** `requireSamePartition` reports
   "another partition or definition" for a wrong scope, evaluation policy, mapping version or feature schema alike, and
   the rules live in two private methods a host cannot ask.
2. **Some rules depend on the receiving store**, not only on the definition and partition: the watermark comparison,
   a cohort bound after the checkpoint that a retained marker names, and the capacity rule that counts slots held by
   the store's other checkpoints.
3. **A stale checkpoint is found only during replay**, as `Failed` with `SUPERSEDES_COMPACTED`; nothing in the
   checkpoint or the store can tell before the observations are seen.
4. **A missing or cut off export must never read as a cold start.** The issue's stated risk is a host that quietly
   resets learned state because a preflight gave it a number to put in `exportCutoff`.
5. **One rulebook.** A second set of checks in another package would drift from the store's.

## Decision

- **`RoutingStateStore.restoreProblems(checkpoint, exportCutoff)`** returns every reason `rebuildFrom` would throw
  before staging, as a sealed `RestoreProblem` list in check order, at most one per rule. The two private checks became
  three private collectors (partition, export, restorability); `rebuildFrom` throws the first problem's `message()` at
  the same points as before, and `compact` throws on the partition collector and refuses `UNRESTORABLE` on the
  restorability collector. Every existing exception message is unchanged, byte for byte.
- **`monada.neuron.routing.adaptation.recovery`** holds the host facing diagnostic: `CheckpointRecovery.assess` over a
  checkpoint or a `CheckpointDecodeResult`, a target store and a sealed `ExportState` (`Observed(highWatermark)` or
  `Unavailable`), returning `RecoveryAssessment.Compatible` (cutoff, watermark, `ReplayWindow`, caveats) or `Blocked`
  (every `RecoveryBlocker`, ordered by `BlockerKind`). An unavailable export is its own blocker and still lists the
  partition and restorability blockers after it, so a host does not fetch an export for a checkpoint that could never
  restore.
- **A changed routing policy is a notice**, `Caveat.ROUTING_POLICY_CHANGED`, never a blocker: `rebuildFrom` ignores
  the policy, and the preflight must not refuse what the restore accepts.
- **Staleness is reported after replay.** `Compatible` always carries `REPLAY_REQUIRED`, `UNVERIFIED_UNTIL_REPLAY` and
  `EXPORT_COMPLETENESS_UNPROVEN`; `RecoveryPlan.after(RebuildResult.Failed)` turns `SUPERSEDES_COMPACTED` and
  `BEFORE_HORIZON` into "use a checkpoint with cutoff below `affectedFromSequence`, or rebuild from scratch", a
  `RebuildRequired` into the rebuild, and everything else into `INSPECT_FAILED_OBSERVATION` with the `StateUpdate`.
- **No cold start action.** `RecoveryAction` has seven constants and none discards learning; `REBUILD_FROM_SCRATCH`
  means `rebuild` over the full export.
- **Zero side effects and no message text.** The package reads the store only through `restoreProblems`, builds no
  store, and carries typed components the host already owns. The host flow and failure matrix are normative in
  [`routing-checkpoint-recovery-v1.md`](../specs/routing-checkpoint-recovery-v1.md).

## Alternatives

- **A standalone assessment over definition and partition only**, as the issue's wording suggests. Rejected: the
  store dependent rules (force 2) could not run, so the preflight would pass checkpoints `rebuildFrom` rejects, or it
  would copy those rules, which force 5 forbids. Restoring into a new empty store loses nothing: the host passes it.
- **A public rule class shared by the store and the recovery package.** Rejected: the rules need the store's private
  cohort and reservation sets, so the class would need accessors or would live in the store's package anyway.
- **Recovery types inside `adaptation` with package private rules.** Rejected: the package already holds seventeen
  public types, and the issue names `adaptation/recovery` so the host facing diagnostic stays apart from the state.
- **A plain `long exportCutoff` instead of `ExportState`.** Rejected: a number cannot say "I have no export", which is
  exactly the path to force 4.
- **Scanning the host's observations for supersessions before replay.** Rejected: it repeats the reconciler's logic;
  the one place that already detects staleness is the replay.
- **A `DISCARD_LEARNING` action, flagged, so a UI can show it.** Rejected: a listed action becomes the default button.

## Consequences

- A host sees every reason a restore would fail before it tries, with the rules the restore uses, and gets a next
  action that never includes starting cold.
- `restoreProblems` is additive public API on the correctness baseline; the refactor is guarded by a parity test (the
  first problem's message equals the thrown message on every store backed blocker) and the unchanged existing tests.
- Staleness still surfaces only during replay; the preflight can only warn.
- Two more enums and a sealed hierarchy must be kept in step when a store rule is added; the sealed switch in
  `RecoveryBlocker.FromStore.kind()` fails to compile if one is missed.
- `CompactionResult.Refused(UNRESTORABLE)` could later carry the `RestoreProblem`; not done here.

## Follow-up

- Issue #103's restart scenario runs decode, assess, plan and `rebuildFrom` end to end on the replay harness.
- An explainable `UNRESTORABLE` refusal is a small additive change for a later issue.
