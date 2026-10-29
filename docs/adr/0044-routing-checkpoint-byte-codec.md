# ADR 0044: Portable Byte Codec for Routing State Checkpoints

## Status

Accepted (issue #100; adds `monada.neuron.routing.adaptation.codec` and the `routing-checkpoint/1` format, and amends
`forge-integration-readiness/1` under ADR 0033 as a minor, additive bump)

## Context

Issue #89 ([ADR 0031](0031-routing-state-checkpoint-and-ledger-compaction.md)) gave Neuron an immutable
`RoutingStateCheckpoint`: every cohort's four Node channels and support count, active and dormant, the
`routing-ledger-digest/1` of every ledger entry it covers, and the retryable no update markers a restored store must
still recognize. It exists only as a Java value. Forge will need learned routing state to survive desktop restarts, and
the [readiness contract](../specs/forge-integration-readiness-v1.md) (Sections 2.1 and 7.3) gives persistence to the
host: Neuron ships values and codecs, never storage.

The forces:

1. **A checkpoint is restored from, not compared.** A flipped bit in an amplitude, a support count or a sequence still
   decodes as a valid checkpoint, and that value becomes the starting state of every later decision. Structural
   validation alone cannot make that damage fail closed.
2. **Lossless and deterministic pull apart on one field.** A retained marker's `cutoff` is audit context that
   `AppliedRevision.equals` and the ledger digest ignore.
3. **The checkpoint carries no active or dormant flag.** Dormancy depends on the catalog a later snapshot is published
   for.
4. **Size and hostile input.** At its bounds a checkpoint holds 256 cohorts and 4,096 markers of up to about 10 KB each.
   A decoder that trusts a declared length or count can be made to allocate far more than its input.
5. **Hard limits from the issue.** No storage, encryption, authentication, automatic checkpoint policy, Java object
   serialization or new dependency; in memory arrays and stable Java 27 APIs only; ADR 0031's compaction rule unchanged.

## Decision

- **`CheckpointCodec.encode(RoutingStateCheckpoint)`** writes every component of the checkpoint and of each marker, in
  declaration order, with the primitive rules of `routing-advice-receipt/1` after the tag `routing-checkpoint/1`, and
  ends the bytes with the SHA-256 of everything before them. The layout, bounds and decode order are normative in
  [`routing-checkpoint-v1.md`](../specs/routing-checkpoint-v1.md).
- **`CheckpointCodec.decode(byte[])`** returns a sealed `CheckpointDecodeResult`: `Decoded(checkpoint)` or
  `Rejected(CheckpointRejectReason)`. It checks size (`OVERSIZE`), then the tag (`MALFORMED`, `UNSUPPORTED_VERSION`),
  then the checksum (`CORRUPT`), then structure (`MALFORMED`), then the records' own validation (`INVALID_CHECKPOINT`),
  then canonical form (`NON_CANONICAL`); the first failure wins. It never returns an empty or default checkpoint for bad
  input, so a damaged file cannot pass for a cold start.
- **The checksum is integrity, not authenticity.** Anyone holding the bytes can compute a valid checksum for any
  checkpoint. A host that must know who wrote one adds its own signature or authenticated storage outside Neuron.
- **Every component is written**, each marker's `cutoff` included, so a round trip loses nothing. Byte equality is
  therefore component equality, which is stricter than `equals` for a marker whose audit cutoff alone differs; the byte
  doc says so.
- **No status byte per cohort.** Active and dormant cohorts are written alike; the dormant golden vector is taken from a
  real store whose cohort's route left the catalog.
- **The cap is the measured worst case**, `MAX_ENCODED_BYTES = 43,149,964`, so every checkpoint a store can produce
  encodes. Protection against hostile sizes comes from the reader: every length and count is checked against its
  bound and the bytes left before the checksum before anything is allocated.
- **A static codec in its own package.** Encode and decode are pure functions of their input with nothing to configure,
  like `LedgerDigest.of`, and the checksum already lives inside the bytes, so a wrapper type like `AdviceReceipt` would
  only repeat it. The public names carry a `Checkpoint` prefix so a host importing both codecs has no clash with
  `receipt.DecodeResult` and `receipt.RejectReason`. The `Writer` and `Reader` are package private copies of the
  receipt codec's primitives; the receipt codec is untouched.
- The evaluation's `CHECKPOINT` strategy keeps only the decoded copy of each checkpoint, and the
  `state.checkpoint-codec-roundtrip` check confirms the run is unchanged.

## Alternatives

- **Structure only, like the receipt.** The same layout without a checksum. Rejected: a receipt is compared with a
  digest the host keeps, but a checkpoint is restored from, so a flipped value byte would silently become learned
  state.
- **Compact marker layout with a shared public primitive class.** Skip the five marker components the constructor
  forces and move the writer and reader into a public class in `routing.token`. Rejected: about 120 KB smaller at the
  bound, but it adds a second marker layout beside the digest's, grows the public API and edits the accepted receipt
  codec.
- **A round 16 MiB cap.** Rejected: a valid checkpoint with thousands of maximum length markers could not be saved,
  which turns a size budget into lost learned state.
- **Java object serialization.** Rejected by the issue: it ties the bytes to class internals and brings known
  deserialization hazards.

## Consequences

- A host can keep learned routing state across restarts in storage it owns, with no storage code in Neuron. A damaged
  copy is refused as `CORRUPT` instead of restoring wrong amplitudes or counts.
- The byte layout is now a contract. Any change to a record in the layout, an enum constant name used in it, or a bound
  such as `RoutingStateStore.MAX_LEDGER` needs `routing-checkpoint/2`. Tests pin three golden vectors against an
  independent reference writer, and the cap against the measured worst case, so such a change fails a named test.
- Worst case bytes are about 43 MB, nearly all of it 4,096 maximum length markers; decoding at the cap holds up to about
  six times the input on the heap at its peak, counting the caller's array (itemized in the byte doc). Real checkpoints are far smaller (the populated vector is 967 bytes). No JMH benchmark
  was added; `CheckpointBoundsTest`, which builds the worst case checkpoint, encodes it and decodes it, ran in about half
  a second on the development machine, an informal wall clock figure and not a measurement.
- About 150 lines of primitive writing and reading are duplicated from the receipt codec.
- `RoutingStateCheckpoint`, the compaction rule, `rebuildFrom` and the ledger digest are unchanged. The evaluation's
  `RebuildStats` gains a `codecRoundTrips` count. No dependency and no preview API is added.

## Follow-Up

- Move the receipt and checkpoint writers and readers to one shared package private home once a third format needs
  them.
- Issue #101 (checkpoint recovery diagnostics, [ADR 0045](0045-checkpoint-recovery-diagnostics.md)) consumes
  `CheckpointDecodeResult` and turns a rejection into an `UNDECODABLE` blocker carrying the reason, before
  `rebuildFrom`.
