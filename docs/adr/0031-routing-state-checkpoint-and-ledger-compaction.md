# ADR 0031: Routing State Checkpoints, Ledger Digest and Compaction

## Status

Accepted (implemented by issue #89; follow-up to ADR 0030 and the #67 replay evaluation)

## Context

ADR 0030 made adaptive routing state caller-owned and bounded, and deferred `RoutingStateCheckpoint`, a ledger digest
format and ledger compaction to #67. #67 stayed evaluation-only, so they remained open:

- A full applied-revision ledger (4,096 entries) blocks learning in its store until the caller rebuilds into a new scope,
  although contract Section 4 says the caller "compacts through a `RoutingStateCheckpoint`".
- Every correction that supersedes an applied reward or penalty rebuilds from scratch, `O(N log N + N·L)` over every
  delivered observation. In the #67 replay the adaptive arm was about an order of magnitude slower than the other arms
  (960 tasks, 51 rebuilds, a ledger of up to 910 entries). That motivates the work; it is not a claim that production
  needs it.
- A rebuild call is capped at 4,096 observations, so a partition with more effective samples cannot be rebuilt from
  scratch in one call.

Contract Section 7 already fixes the checkpoint's shape, its validity rule (usable only while its `processedCutoff` is
strictly below the `supersedesSequence` of the earliest superseded revision) and the required equivalence (starting from
a valid checkpoint gives a state identical to a rebuild from scratch).

## Decision

- **`RoutingStateCheckpoint`** (new record, `routing.adaptation`) with the Section 7 components: `scopeId`,
  `featureSchemaVersion`, evaluation policy id and version, `mappingVersion`, `policyId`, `policyVersion`,
  `processedCutoff`, `preferenceSnapshot` and `ledgerDigest`, plus `retainedMarkers`. The snapshot is a list of
  `CheckpointCohort(key, amplitude, frequency, phase, energy, support)` for **every** cohort, active and dormant: a
  `RoutingPreference` omits dormant cohorts and the Node channels. `RoutingStateStore.checkpoint(policy)` takes one at
  the store's watermark; it is in-memory and caller-owned.
  - A host may persist a checkpoint and build it again, so the constructor rejects more than 4,096 retained markers, a
    marker of another partition or definition, and a digest that counts fewer entries than the markers plus every
    support unit.
- **`routing-ledger-digest/1`** (`LedgerDigest`): each `AppliedRevision` is encoded canonically (tag, length-prefixed
  UTF-8, big-endian fixed-width numbers, presence bytes, enum names, the score's IEEE-754 bits), mirroring `equals` and
  therefore **excluding `cutoff`**. The SHA-256 of each encoding is summed modulo 2^256, with the entry count. The sum is
  order-independent and foldable, so `RoutingStateStore.ledgerDigest()` (compacted part plus live ledger) does not change
  when the ledger is compacted and equals a from-scratch rebuild's.
- **Compaction** `compact(checkpoint)`: all-or-nothing and explicit. The checkpoint must come from this partition and
  definition, must not be behind the current horizon, and must still describe the ledger at or below its cutoff (its
  digest and markers are checked). Every covered entry except **retryable markers** (`NO_TARGET_BINDING`,
  `PROVENANCE_MISMATCH`) is folded into the digest and dropped. Retryable markers are kept because a redelivery can still
  become a first application. The cutoff becomes the **compaction horizon**. Compaction is refused, as a value, when an
  entry it would drop credits a cohort the checkpoint does not hold.
- **Horizon rules in `consume`** apply only to a compacted store:
  - A revision that replaces a revision at or below the horizon is `SUPERSEDES_COMPACTED`, checked first and wherever
    its own sequence lies. This applies when its sample's entry was compacted, or when it is newer than the sample's
    retained marker (contract Section 7: a superseded revision at or before the cutoff invalidates the checkpoint).
    - A compacted ledger cannot tell a correction examined before the checkpoint from one never delivered.
    - It also cannot show a compacted cost-refined reward whose peer is a retained `PROVENANCE_MISMATCH` marker.
    - A re-read of an examined correction therefore asks for a rebuild too: never wrong, only more work. A repeat of a
      retained marker is still answered by the reconciler.
  - Any other delivery of such a sample at or below the horizon is `COVERED`.
  - A delivery at or below the horizon that would apply or record something is `BEFORE_HORIZON`.
  - All three are reported by one new `StateUpdate.BeyondCheckpoint(kind, horizon, affectedFromSequence)` and change
    nothing. The caller rebuilds from a checkpoint strictly below `affectedFromSequence`, or from scratch.
  - `bind` refuses a new key that a compacted entry credited, or that a retained `NO_TARGET_BINDING` marker at or below
    the horizon names, exactly like the existing credited-key rule.
- **`rebuildFrom(checkpoint, observations, exportCutoff, derive)`** stages a store from the checkpoint and replays only
  later observations:
  - The checkpoint's cohorts get its Node channels and support; this store's other cohorts start at the baseline.
  - The ledger starts as the retained markers, and the checkpoint's cutoff is both the watermark and the horizon.
  - Observations at or below the cutoff whose sample the checkpoint does not retain are history it already contains,
    and are skipped, so a whole export may be passed.
  - The receiver need not be the store the checkpoint came from. A persisted checkpoint restores a new, empty store; only
    the export cutoff must reach the checkpoint's cutoff.
  - The checkpoint carries no bucket mapping. `rebuildFrom` and `compact` therefore reject a retained marker recorded
    under another bucket mapping than the definition's, so it can never answer a redelivery the definition would bucket
    differently.
  - An observation that invalidates the checkpoint ends the replay as `RebuildResult.Failed`, so no new result type is
    needed.
  - The from-scratch `rebuild` overloads are unchanged.
- The evaluation's `ReplayDigests.state` uses the core ledger digest. A `CHECKPOINT` rebuild strategy (checkpoint every
  32 deliveries, keep 4, compact to the oldest) adds the `RoutingReplay.adaptive-checkpointed` row and the
  `state.checkpoint-equals-rebuild` check.

## Alternatives

- **Chained SHA-256** (`H(previous digest, newly covered entries)`): stronger against a deliberate adversary, but
  dependent on compaction history, so a from-scratch store could not reproduce a compacted store's digest without
  replaying the same compactions. Rejected; the digest is an integrity and equivalence check over caller-owned data, not
  an authentication boundary, and that limit is documented.
- **Reuse `RoutingPreference` as the snapshot**: rejected, it drops dormant cohorts and three of the four Node channels.
- **Drop every covered entry, including retryable markers**: rejected. A later bind and redelivery would then be
  silently ignored where a rebuild from scratch would apply the sample.
- **Report below-horizon deliveries through an existing variant** (`Unchanged` or `RebuildRequired`): rejected. Both
  carry a `Reconciliation` that names a ledger entry, and the entry no longer exists after compaction.
- **Automatic compaction when the ledger fills**: rejected. The contract forbids silent eviction, and the caller decides
  which checkpoint to keep.
- **Keep a per-sample index of compacted references** for exact duplicate and cost-peer checks: rejected. It grows
  without bound, which defeats compaction.

## Consequences

- A full ledger can learn again: checkpoint, compact, and the blocked retry is applied exactly once.
- Corrections of recent samples rebuild from a nearby checkpoint instead of from scratch. Corrections of anything at or
  below the horizon need an earlier checkpoint or a full rebuild (itself capped at 4,096 observations per call), so callers
  keep a few checkpoints.
- A cost-refined derivation whose peer revision was compacted is trusted to have been derived from the same export as the
  observation, which is later than the horizon. The compacted ledger cannot re-check an older view of that peer, so this
  is a documented caller precondition.
- **Source compatibility:** `StateUpdate` gains a permitted subtype, so an exhaustive `switch` over it no longer compiles
  without a new case. The project is `0.1.0-SNAPSHOT` and the evaluation module was the only known consumer (updated
  here). A store that is never compacted never returns it.
- `ReplayDigests.state` values change, because the ledger is now digested by the core format. The fixture and report
  digests do not change.
- Retained footprint (**modeled**): a checkpoint is `O(C + M)`, a few KB at 256 cohorts plus 32 bytes of digest.
  Compaction bounds the live ledger to the entries above the horizon plus retained markers.

## Follow-Up Work

- Checkpoint persistence and serialization remain host concerns; no format is defined here.
- #68: the published consumer loop over real Store exports can exercise checkpoints across export pages.
