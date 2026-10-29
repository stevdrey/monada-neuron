# Routing Checkpoint Recovery v1

**Status**: Accepted (issue #101; host facing recovery flow, decided in
[ADR 0045](../adr/0045-checkpoint-recovery-diagnostics.md))
**Defined over**: `RoutingStateCheckpoint` of [ADR 0031](../adr/0031-routing-state-checkpoint-and-ledger-compaction.md)
and the bytes of [`routing-checkpoint/1`](routing-checkpoint-v1.md)
**Code**: `monada.neuron.routing.adaptation.recovery` (`CheckpointRecovery`, `RecoveryAssessment`, `RecoveryPlan`) and
`RoutingStateStore.restoreProblems`

A host that stored a checkpoint and wants it back after a restart has one restore operation,
`RoutingStateStore.rebuildFrom`, which either replays or throws. This document gives the host a read only step before
it: an assessment that says whether the restore would start, every reason it would not, and what to do next. Nothing
in it restores, resets or discards state; recovery execution stays the host's explicit `rebuildFrom` call.

## 1. The flow

```text
bytes ──decode──> CheckpointDecodeResult
                      │
                      ▼
  CheckpointRecovery.assess(decoded, target store, ExportState[, current policy])
                      │
          ┌───────────┴────────────┐
      Compatible                Blocked(blockers...)
          │                         │
   RecoveryPlan.of ──> REPLAY   RecoveryPlan.of ──> next actions (Section 4)
          │
   target.rebuildFrom(checkpoint, export observations, export watermark, derive)
          │
    ┌─────┴──────┐
 Rebuilt      Failed(observation, update)
                    │
             RecoveryPlan.after ──> next actions (Section 4)
```

1. **Decode** the bytes with `CheckpointCodec.decode`. A `Rejected` result carries only a `CheckpointRejectReason`.
2. **Assess** with the store the host will restore into (after a restart, a new empty `RoutingStateStore` of the host's
   definition and partition) and what the host knows about its Store export: `ExportState.Observed(highWatermark)` or
   `ExportState.Unavailable`. Passing the host's current `RoutingPolicy` adds the policy notice of Section 3.
3. **Plan** with `RecoveryPlan.of(assessment)`. A plan is advice; the host decides.
4. **Replay** only on `Compatible`, with `rebuildFrom`. The `ReplayWindow` says which part of the export the replay
   consumes (every observation above the cutoff, plus every revision of the retained markers' samples); passing the
   whole export is also correct, because `rebuildFrom` drops covered history itself.
5. **Plan again** on `RebuildResult.Failed` with `RecoveryPlan.after(failed)`.

The assessment calls `RoutingStateStore.restoreProblems(checkpoint, exportCutoff)`, the same rules `rebuildFrom`
applies before it stages anything, in the same order, so the preflight and the restore cannot disagree. `rebuildFrom`
throws the first problem's `message()`; the assessment lists all of them, at most one per rule.

## 2. Failure matrix

The blockers, in report order (`BlockerKind` declaration order, which is check order). A `Blocked` assessment holds
every blocker found; `RecoveryPlan.of` plans from the first.

| Blocker | Carries | Means | Next action |
| --- | --- | --- | --- |
| `UNDECODABLE` | `CheckpointRejectReason` | the bytes are not a canonical checkpoint; nothing else is inspected | `OBTAIN_READABLE_CHECKPOINT`, then `REBUILD_FROM_SCRATCH` |
| `EXPORT_UNAVAILABLE` | nothing | the host has no export to replay; the partition and restorability rules still run, so their blockers follow this one | `OBTAIN_EXPORT` alone, or first and then the plan of the first store blocker |
| `FOREIGN_SCOPE` | `Mismatch(SCOPE)` | the checkpoint's scope is not the store's | `FIX_HOST_CONFIGURATION` or `OBTAIN_READABLE_CHECKPOINT`, then `REBUILD_FROM_SCRATCH` |
| `FOREIGN_EVALUATION_POLICY` | `Mismatch(EVALUATION_POLICY)` | the evaluation policy id or version differs | as above |
| `MAPPING_VERSION_MISMATCH` | `Mismatch(MAPPING_VERSION)` | the composite `mappingVersion` differs: any part of the definition changed | as above |
| `FEATURE_SCHEMA_MISMATCH` | `Mismatch(FEATURE_SCHEMA)` | the cohort mapping's feature schema differs | as above |
| `BUCKET_MAPPING_MISMATCH` | marker ref, found and expected versions | a retained marker was recorded under another bucket mapping | as above |
| `CHANNEL_OUT_OF_BOUNDS` | `CohortKey` | a cohort's Node channel is outside the definition's adaptation bounds | as above |
| `EXPORT_BEHIND_CHECKPOINT` | export and checkpoint cutoffs | the export does not reach the checkpoint's cutoff | `OBTAIN_EXPORT` (a newer one), or `USE_OLDER_CHECKPOINT` with cutoff below `export + 1` (omitted when the export cutoff the host passed is negative: no checkpoint lies that low) |
| `EXPORT_BEHIND_STORE` | export and store cutoffs | the target already examined past the export | `OBTAIN_EXPORT` |
| `COHORT_BOUND_AFTER_CHECKPOINT` | `CohortKey`, marker ref, marker sequence, checkpoint cutoff | a cohort bound on the target is recorded as unbound by a retained marker | `USE_OLDER_CHECKPOINT` with cutoff below the marker's sequence, then `REBUILD_FROM_SCRATCH` |
| `COHORT_CAPACITY` | union size, reserved only count | the checkpoint's cohorts, the store's and its reservations exceed 256 | `FIX_HOST_CONFIGURATION`, then `REBUILD_FROM_SCRATCH` |

The mismatch rows share one next action because the preflight cannot tell a wrong definition or partition on the host
from a checkpoint of another one; the host checks both. When the exclusive bound of `USE_OLDER_CHECKPOINT` would be
`0`, the step is omitted (sequence `0` is a valid position, so no checkpoint lies below it) and only the rebuild remains.

After a replay stopped (`RebuildResult.Failed`):

| `update` | Next action |
| --- | --- |
| `BeyondCheckpoint(SUPERSEDES_COMPACTED, horizon, from)` | `USE_OLDER_CHECKPOINT` with cutoff below `from` (the superseded position), then `REBUILD_FROM_SCRATCH` |
| `BeyondCheckpoint(BEFORE_HORIZON, horizon, from)` | the same, `from` being the observation's own sequence |
| `RebuildRequired` | `REBUILD_FROM_SCRATCH` |
| anything else (`Blocked`, `ForeignPartition`, a stale revision, a covered delivery that still stopped) | `INSPECT_FAILED_OBSERVATION`, carrying the `StateUpdate`; Neuron does not guess at a cause it cannot see |

`REBUILD_FROM_SCRATCH` always means `RoutingStateStore.rebuild` over the full export. It never means a new empty store
without the export, and no `RecoveryAction` discards learning or resets state. A host that starts cold does so by its
own decision, outside this advice.

## 3. What `Compatible` promises, and what it cannot

`Compatible` carries the checkpoint's cutoff, the export's high watermark, the `ReplayWindow` and these caveats:

| Caveat | Meaning |
| --- | --- |
| `REPLAY_REQUIRED` | nothing is restored until the host calls `rebuildFrom`; the assessment is a value, not a state |
| `UNVERIFIED_UNTIL_REPLAY` | a later revision superseding something at or below the cutoff is seen only during replay, as `Failed` with `SUPERSEDES_COMPACTED`; a stale checkpoint cannot be detected before it |
| `EXPORT_COMPLETENESS_UNPROVEN` | the preflight sees a watermark, not the observations: an export cut off below a watermark that still reaches past the cutoff passes |
| `ROUTING_POLICY_CHANGED` | only with the policy overload: the current policy's id or version differs from the one the checkpoint was taken for; the learned state is provenance of that policy, `rebuildFrom` ignores it, so this is a notice, never a blocker |

Three more limits the host must know:

- The preflight proves nothing about authenticity. The codec's checksum says the bytes are unchanged, not who wrote
  them; a host that must trust the source signs or stores the bytes under its own control.
- `Compatible` describes the target store as it was at the call. The store is mutable and not thread safe; a host that
  consumes observations, binds cohorts or compacts between `assess` and `rebuildFrom` assesses again.
- The observation list itself is not checked: `rebuildFrom` still throws for an oversized or inconsistent list or a
  null element, between the export rules and the restorability rules.

## 4. Next actions

A plan lists steps in order. `OBTAIN_EXPORT`, when present, comes first and every later step needs it, because each
path ends in a replay or a rebuild over an export. The steps after it are alternatives in order of preference: take the
first you can, and move to the next when it does not lead to a restore; `REBUILD_FROM_SCRATCH` is the last resort. An
`UNDECODABLE` plan does not mention the export (nothing past the bytes was inspected); its rebuild still needs one.

| `RecoveryAction` | What the host does |
| --- | --- |
| `REPLAY_FROM_CHECKPOINT` | call `rebuildFrom` over the replay window |
| `USE_OLDER_CHECKPOINT` | pick a checkpoint with `processedCutoff < cutoffBelow`, then assess again |
| `REBUILD_FROM_SCRATCH` | call `rebuild` over the full export |
| `OBTAIN_READABLE_CHECKPOINT` | find bytes that decode, or a checkpoint of this partition and definition |
| `OBTAIN_EXPORT` | obtain an export, or one whose watermark reaches far enough |
| `FIX_HOST_CONFIGURATION` | compare the store's definition and partition with what the checkpoint was taken for |
| `INSPECT_FAILED_OBSERVATION` | look at the carried `StateUpdate` and the observation `Failed` names |

## 5. Side effects and tokens

`restoreProblems`, `assess`, `RecoveryPlan.of` and `RecoveryPlan.after` are pure: the target store's snapshot, ledger,
digest, watermarks, checkpoint, horizon, cohorts, bindings and reservations are the same before and after. No type in
the package reads a file, opens a connection, logs or starts a thread, and none holds a store.

Blockers and steps carry typed components the host already owns (a `CohortKey`, a sample reference, a bucket mapping
version); the package builds no message text. `RestoreProblem.message()` exists for the store's exception and may echo
host tokens; a host that must not log them reads the typed components instead.

## 6. Example

```java
var decoded = CheckpointCodec.decode(bytes);
var target = new RoutingStateStore(definition, partition);
var export = exportOrNull == null ? new ExportState.Unavailable() : new ExportState.Observed(exportOrNull.highWatermark());
var assessment = CheckpointRecovery.assess(decoded, target, export, currentPolicy);
var plan = RecoveryPlan.of(assessment);
if (assessment instanceof RecoveryAssessment.Compatible ok && decoded instanceof CheckpointDecodeResult.Decoded d) {
    var result = target.rebuildFrom(d.checkpoint(), exportOrNull.observations(), ok.exportHighWatermark(), derive);
    if (result instanceof RebuildResult.Failed failed) {
        plan = RecoveryPlan.after(failed);
    }
}
// show plan.steps() to the person; never start cold on Neuron's behalf
```
