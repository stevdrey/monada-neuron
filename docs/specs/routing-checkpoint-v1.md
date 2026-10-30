# Routing Checkpoint v1

**Format identifier**: `routing-checkpoint/1`
**Status**: Accepted (issue #100; normative byte format, decided in
[ADR 0048](../adr/0048-routing-checkpoint-byte-codec.md))
**Defined over**: `RoutingStateCheckpoint` of [ADR 0031](../adr/0031-routing-state-checkpoint-and-ledger-compaction.md)
(Forge Routing Contract v1, section 7)
**Code**: `monada.neuron.routing.adaptation.codec` (`CheckpointCodec`, `CheckpointDecodeResult`, `CheckpointRejectReason`)

A routing checkpoint is the canonical byte string of one `RoutingStateCheckpoint`, ending with the SHA-256 of every byte
before it. A host keeps it in storage it owns so learned routing state survives a restart, and reads it back with
`CheckpointCodec.decode` before `RoutingStateStore.rebuildFrom`. This document fixes the bytes so another implementation
can produce and check them without Java.

The checksum is integrity, not authenticity: it proves the bytes are unchanged, not who wrote them.

## 1. Primitives

The primitives are those of [`routing-advice-receipt/1`](routing-advice-receipt-v1.md) Section 1, plus `f64`, `text`,
`uuid` and a raw byte run. Every multibyte number is big endian.

| Name | Encoding |
| --- | --- |
| `u8` | one unsigned byte |
| `i32` | four bytes, two's complement |
| `i64` | eight bytes, two's complement |
| `f64` | the `i64` of `Double.doubleToRawLongBits`. Every value in the format is finite, so raw and canonical bits agree, and `-0.0` stays `-0.0` |
| `str` | an `i32` byte length, then that many bytes of UTF-8. Length 1 to 512 (a token is at most 128 code points of at most 4 bytes); 1 to 64 for the tag |
| `text` | a `str` with length 1 to 2,048 (at most 512 code points of at most 4 bytes) |
| `enum` | a `str` holding the constant's exact, case sensitive name |
| `opt<T>` | a `u8` presence (`0` absent, `1` present), then `T` when present |
| `list<T>` | an `i32` count, then the elements in order |
| `key` | a `str` route id, then an `i64` route version |
| `uuid` | an `i64` of the most significant bits, then an `i64` of the least significant bits |

UTF-8 is decoded strictly: overlong forms, encoded surrogates, stray continuation bytes and cut off sequences are
refused. No Unicode normalization happens. There are no separators; every field is length prefixed or fixed width.

Exactly two fields are `text`: a marker's `experienceRef` and its cost peer's `experienceRef`. Every other string is a
`str`. A value inside its byte bound that still breaks a token rule (too many code points, a control character, leading
or trailing padding) passes the structural read and is refused by the record constructor (`INVALID_CHECKPOINT`).

## 2. Layout

| # | Field | Encoding |
| --- | --- | --- |
| 1 | tag | `str` `routing-checkpoint/1` |
| 2 | partition and definition | `str` scopeId, featureSchemaVersion, evaluationPolicyId, evaluationPolicyVersion, mappingVersion, policyId, policyVersion |
| 3 | processedCutoff | `i64` |
| 4 | preferenceSnapshot | `list` (count 0 to 256) of cohort |
| 5 | ledgerDigest | `i64` entries, then the 32 raw bytes of the sum, big endian |
| 6 | retainedMarkers | `list` (count 0 to 4,096) of marker |
| 7 | checksum | 32 bytes: SHA-256 of every byte of fields 1 to 6 |
| 8 | end | no further bytes |

A **cohort** is `str` scopeId, stageKind, cohortBucket; `key` route; `f64` amplitude, frequency, phase, energy; `i32`
supportingObservations.

A **marker** is every component of `AppliedRevision` in declaration order, which is the `routing-ledger-digest/1` entry
order with `cutoff` added in its place:

| Part | Encoding |
| --- | --- |
| identity | `text` experienceRef; `i64` revision, ledgerSequence; `str` scopeId, taskId, executionId, attemptId, stageId; `i64` requestOrdinal; `key` route; `str` mappingVersion, evaluationPolicyId, evaluationPolicyVersion |
| routing policy | `str` routingPolicyId, routingPolicyVersion |
| supersedesSequence | `opt<i64>` (`Feature.Known` is present) |
| catalogVersion | `str` |
| cutoff | `i64` |
| targetNodeId | `opt<uuid>` |
| costPeer | `opt` of `text` experienceRef, `i64` revision, `i64` ledgerSequence |
| basis | `u8` `2` (no update marker), then `enum` reason (`NoUpdateReason`), then `str` cohortBucket, bucketMappingVersion, mappingVersion, featureSchemaVersion |
| disposition | `enum` (`FeedbackDisposition`) |
| score | `f64` |

A valid checkpoint retains only retryable no update markers (`NO_TARGET_BINDING`, `PROVENANCE_MISMATCH`), so in valid
bytes `targetNodeId` and `costPeer` are absent, the disposition is `NEUTRAL` and the score is `0.0` or `-0.0`. A reader
still accepts presence `1` and reads the value; the record constructor then refuses it. A basis byte other than `2` is
`MALFORMED`: `routing-checkpoint/1` has no derived entries.

The digest sum is the 64 lowercase hexadecimal digits of `LedgerDigest.sum()` as 32 bytes, and is read back as
lowercase hexadecimal. The codec never recomputes the digest: the compacted entries it covers are not in the checkpoint.

There is no status byte per cohort. Active and dormant cohorts are written alike, because dormancy depends on the catalog
a later snapshot is published for, not on the checkpoint.

Cohorts are written in canonical `CohortKey` order and markers in `experienceRef` code point order, which is the order
`RoutingStateCheckpoint` stores. Checkpoints equal in every component give identical bytes, whatever order the lists were
passed to the constructor in. Because the bytes hold every component, two checkpoints that are `equals` but differ only
in a marker's audit `cutoff` (which `AppliedRevision.equals` ignores) give different bytes.

## 3. Bounds and size cap

`CheckpointCodec.MAX_ENCODED_BYTES` is 43,149,964: the encoding of the largest valid checkpoint, 256 cohorts and 4,096
markers with every token and text at its byte bound and each marker's reason `PROVENANCE_MISMATCH`.

| Part | Bytes | Breakdown |
| --- | --- | --- |
| fixed fields | 3,724 | tag 24; 7 `str` at 516 = 3,612; processedCutoff 8; cohort count 4; digest 40; marker count 4; checksum 32 |
| one cohort | 2,108 | 3 `str` 1,548; `key` 524; 4 `f64` 32; support 4 |
| one marker | 10,402 | experienceRef 2,052; revision and sequence 16; decision ref 2,588; route 524; 3 `str` 1,548; routing policy 1,032; supersedes 9; catalogVersion 516; cutoff 8; targetNodeId 1; costPeer 1; basis 1; reason 23; binding 2,064; disposition 11; score 8 |
| total | 43,149,964 | 3,724 + 256 × 2,108 + 4,096 × 10,402 |

`CheckpointBoundsTest` builds that checkpoint and pins the constant to its measured size. Real checkpoints are far
smaller, but a host must allow for the cap. Decoding at the cap holds up to about six times the input on the heap at
its peak: the caller's array, the private copy, the decoded records (about one input each), and the second encoding
that checks canonical form, whose growing buffer (up to 64 MiB) and its two copies come to about three and a half
inputs together. This is a count of the arrays alive at once, not a heap measurement.

The reader checks every declared length against its bound and against the bytes left before the checksum, and every
count against its bound, before it allocates anything for them. Nothing is sized from an unchecked number. Encode and
decode are O(N) in the encoded size.

## 4. Version rule

The tag is `routing-checkpoint/` followed by a major, under the rule of `routing-advice-receipt/1` Section 4: one or more
ASCII digits with no leading zero unless the major is exactly `0`, compared as a digit string, never parsed. A well formed
tag whose major is not `1` is `UNSUPPORTED_VERSION`, whatever follows it, so a later major may change the trailer. There
are no minor versions: any change to a record in the layout, an enum constant name used in it, or a bound needs
`routing-checkpoint/2`.

## 5. Checksum: integrity, not authenticity

The checksum is the SHA-256 of fields 1 to 6, tag included; the tag is the domain separator that keeps it apart from
any other SHA-256 over Neuron data. It detects accidental damage: a cut off file, a flipped bit, a partial write. It does
not authenticate: anyone holding the bytes can build a valid checksum for any checkpoint, including one with forged
learned state. A host that must know who wrote a checkpoint adds its own signature, MAC or authenticated storage outside
Neuron. Neuron holds no key and no secret.

`routing-ledger-digest/1` (`LedgerDigest`, ADR 0031) is a different value: the count and the order independent sum of
the SHA-256 of each applied ledger entry. The codec carries it unchanged as field 5; the checksum covers it like any
other field.

## 6. Decoding

`CheckpointCodec.decode` throws only for a null array. It copies the input, never modifies it, and returns
`Rejected(reason)` for any fault, checking in this order (first failure wins):

| Phase | Reason | When |
| --- | --- | --- |
| 0 | `OVERSIZE` | the array is longer than `MAX_ENCODED_BYTES`; no byte is read |
| 1 | `MALFORMED` | the tag is not well formed: empty or cut off input, a length outside 1 to 64, invalid UTF-8, or text that is not the prefix and a major as in Section 4 |
| 1 | `UNSUPPORTED_VERSION` | a well formed tag whose major is not `1` |
| 2 | `MALFORMED` | fewer than 32 bytes follow the tag |
| 2 | `CORRUPT` | the last 32 bytes are not the SHA-256 of the bytes before them |
| 3 | `MALFORMED` | a structural fault at the first faulty field of fields 2 to 6, reading only up to the checksum: the fields end early; a string length negative, outside its bound or longer than the bytes left; a count negative or above its bound; invalid UTF-8; a presence byte other than `0` or `1`; a basis byte other than `2`; an enum name that is not an exact constant name; bytes left between field 6 and the checksum |
| 4 | `INVALID_CHECKPOINT` | the values are well formed but a record constructor rejects them |
| 4 | `NON_CANONICAL` | the checkpoint builds, but encoding it again does not give the input byte for byte (cohorts or markers out of canonical order) |

So input cut off inside the tag or within 32 bytes after it is `MALFORMED`, and input cut off anywhere later is
`CORRUPT`. A structural fault is reachable only behind a recomputed checksum.

Phase 3 reads raw values and builds no record. Phase 4 builds the records through their public constructors and catches
only `IllegalArgumentException` and `NullPointerException`; the message is dropped because it can echo host tokens.
`Rejected` carries only the reason. A rejection is never an empty or default checkpoint, so damaged bytes cannot pass for
a cold start. A `Decoded` checkpoint equals the encoded one in every component, each marker's `cutoff` included.

## 7. Golden vectors

Checked against an independent reference writer (`ReferenceCheckpointWriter`) and committed as hexadecimal in
`CheckpointGoldenVectorTest` (empty, populated) and `CheckpointCodecRestoreTest` (dormant):

| Vector | Bytes | Checksum (last 32 bytes) |
| --- | --- | --- |
| empty: no cohorts, no markers, `LedgerDigest.EMPTY` | 196 | `d5ad1384a8b0efcbd21fc11a2f61f6e7494c95d1e377ce6bf5b5991faca90ec7` |
| populated: two cohorts and two markers with `-0.0` as a channel and a score, a 4 byte code point (U+1F600), an absent and a present `supersedesSequence`, both retryable reasons, a support count of 0 and a marker cutoff above its sequence | 967 | `38c7228e39054082b7f173b46461b0a110ab6e32e592630f8a62bc33363464e5` |
| dormant: taken from a real `RoutingStateStore` whose `sub-b` cohort is dormant against a catalog without `sub-b` | 384 | `f30b9138c7bff2f38af18483110ea94860ff48048819835cc8f32f0901641c11` |

Full bytes of the empty vector (scope `scope-demo`, feature schema `features/1`, evaluation policy `forge-gates` version
`1`, mapping `adaptive-test/1`, policy `policy-x` version `3`, cutoff 0), in hexadecimal:

```text
00000014726f7574696e672d636865636b706f696e742f310000000a73636f70652d64656d6f0000000a666561747572
65732f310000000b666f7267652d676174657300000001310000000f61646170746976652d746573742f310000000870
6f6c6963792d780000000133000000000000000000000000000000000000000000000000000000000000000000000000
0000000000000000000000000000000000000000d5ad1384a8b0efcbd21fc11a2f61f6e7494c95d1e377ce6bf5b5991f
aca90ec7
```

## 8. Handling

Checkpoint bytes hold opaque host tokens and learned routing state, which may be sensitive. Neuron never logs, persists
or transports them: the codec works on in-memory arrays only and opens no file, socket or logger, and uses no Java object
serialization. The host chooses whether and where to keep the bytes. A decoded checkpoint still restores state only
through `RoutingStateStore.rebuildFrom`, which checks its partition, definition and staleness as for any checkpoint.
