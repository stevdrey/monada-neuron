# Routing Advice Receipt v1

**Format identifier**: `routing-advice-receipt/1`
**Status**: Accepted (issue #97; normative byte format, decided in
[ADR 0039](../adr/0039-canonical-routing-advice-receipts.md))
**Defined over**: `routing-decision/1` (`RoutingDecision.CONTRACT_VERSION`) of the
[Forge Routing Contract v1](forge-routing-contract-v1.md)
**Code**: `monada.neuron.routing.receipt` (`AdviceReceipt`, `DecodeResult`, `RejectReason`)

An advice receipt is the canonical byte string of one `RoutingDecision` plus the SHA-256 of those bytes. A host keeps it
to compare, byte for byte or by digest, what Neuron advised with a replay or with what the host later ran. This document
fixes the bytes so another implementation can produce and check them without Java.

A receipt is a canonical copy of advice for byte comparison only: it is not proof that anything ran or was accepted, not
authentication, not a signature and not a routing ledger digest.

## 1. Primitives

Every multibyte number is big endian.

| Name | Encoding |
| --- | --- |
| `u8` | one unsigned byte |
| `bool` | a `u8` of `0` (false) or `1` (true) |
| `i32` | four bytes, two's complement |
| `i64` | eight bytes, two's complement |
| `str` | an `i32` byte length, then that many bytes of UTF-8. Length 1 to 512 for tokens (a token is at most 128 code points of at most 4 bytes); 1 to 64 for the tag |
| `enum` | a `str` holding the constant's exact, case sensitive name, under the token length rule |
| `opt<T>` | a `u8` presence (`0` absent, `1` present), then `T` when present |
| `list<T>` | an `i32` count, then the elements in order |
| `key` | a `str` route id, then an `i64` route version |

Strings are written as the exact UTF-8 of the token. No Unicode normalization happens: a precomposed and a decomposed form
are different tokens with different bytes. `OpaqueTokens` already rejects unpaired surrogates at construction, so every
token has a lossless UTF-8 form. There are no separators; every field is length prefixed or fixed width, so adjacent
fields cannot collide.

## 2. Layout

| # | Field | Encoding |
| --- | --- | --- |
| 1 | tag | `str` `routing-advice-receipt/1` |
| 2 | variant | `u8`: `1` `Selected`, `2` `Abstain`, `3` `NoEligibleRoute` |
| 3 | decision ref | `str` scopeId, taskId, executionId, attemptId, stageId; `i64` requestOrdinal |
| 4 | provenance | `str` catalogVersion, policyId, policyVersion; `i64` cutoff |
| 5 | state binding | `str` scopeId, featureSchemaVersion, evaluationPolicyId, evaluationPolicyVersion, mappingVersion; `i64` processedCutoff; `str` producedByPolicyId, producedByPolicyVersion |
| 6 | validation | `u8`: `1` `Compatible`, `2` `NotEvaluated`, `3` `Incompatible`; only after `3` comes `list<enum StateMismatch>` (count 0 to 5) |
| 7 | parameters | `i32` minSupportingObservations; `opt<i32>` maxAutoSelectTier; `opt` objective (`str` dimension, `enum` direction); `str` stateMappingVersion, stateFeatureSchemaVersion |
| 8 | stage kind | `opt<str>` |
| 9a | `Selected` body | `key` route; `str` executionMode; `enum` basis; `bool` overflowUsed; candidates; `bool` candidatesTruncated; `list<enum RoutingRule>` rulesApplied, rulesSkipped (count 0 to 5 each); exclusions; cohort binding |
| 9b | `Abstain` body | `enum` reason; `list<key>` eligible (count 0 to 32); candidates; `bool` candidatesTruncated; exclusions; `opt` cohort binding |
| 9c | `NoEligibleRoute` body | exclusions |
| | candidates | `list` (count 0 to 8); each `i32` rank, `key`, `enum` placement |
| | exclusions | `list` (count 0 to 32); each `key`, `enum` primaryReason, `list<enum EligibilityReason>` additionalReasons (count 0 to 8) |
| | cohort binding | `str` cohortBucket, bucketMappingVersion, mappingVersion, featureSchemaVersion |
| 10 | end | no further bytes |

Every record component is encoded, including ones a constructor could derive (for example a candidate's `rank`). Enums by
field: `StateMismatch`, `ResourceObjective.Direction`, `Basis`, `Placement`, `RoutingRule`, `AbstainReason`,
`EligibilityReason`; their constant names as of v1 are pinned by `ReceiptBoundsTest`.

**Canonical order.** Lists keep the order the decision records store. `exclusions` and `Abstain.eligible` are stored in
`RouteKey` order: route id by Unicode code point (which equals UTF-8 byte order), then version. That is not
`String.compareTo`, which orders by UTF-16 unit.

## 3. Bounds and size cap

The count bounds in the layout are structural and enforced while reading. The record constructors apply tighter rules
(for example `Abstain.eligible` needs 1 to 32 routes, `Incompatible` 1 to 5 mismatches); those failures are
`INVALID_DECISION`.

| Bound | Value | Source |
| --- | --- | --- |
| bytes per token | 512 | `OpaqueTokens.MAX_CODE_POINTS` times 4 |
| ranked candidates | 8 | `RoutingDecision.MAX_CANDIDATES` |
| routes (eligible plus exclusions) | 32 | `RouteCatalog.MAX_ROUTES` |
| additional reasons per exclusion | 8 | `EligibilityReport.Exclusion.MAX_ADDITIONAL` |
| rules per list; mismatches | 5; 5 | `RoutingRule.values().length`; `StateMismatch.values().length` |
| encoded receipt | at most 65,536 bytes | `AdviceReceipt.MAX_ENCODED_BYTES` |

Measured worst cases, with every token at 128 four byte code points and every optional field present
(`ReceiptBoundsTest`):

| Decision | Bytes |
| --- | --- |
| `Selected`, one candidate, 31 exclusions with 8 additional reasons each | 36,714 |
| `Selected`, eight candidates truncated, 23 exclusions | 34,625 |
| `Abstain(POLICY_TRADEOFF_UNRESOLVED)`, 8 eligible and ranked, 24 exclusions | 38,437 |
| `Abstain(STATE_INCOMPATIBLE)`, every mismatch, 1 eligible, 31 exclusions | 33,638 |
| `NoEligibleRoute`, 32 exclusions | 33,693 |

An exclusion (744 bytes at worst) outweighs a ranked candidate (about 550), so the largest selection ranks one route.
`AdviceReceipt.of` throws `IllegalStateException` instead of returning a receipt above the cap; today's bounds make that
impossible.

## 4. Version rule

The tag is `routing-advice-receipt/` followed by a major: one or more ASCII digits `0` to `9`, with no leading zero unless
the major is exactly `0`. The major is compared as a digit string, never parsed into a number. A well formed tag whose
major is not `1` is `UNSUPPORTED_VERSION`, whatever follows it. There are no minor versions: any change to a decision
record, an enum constant used in the layout, or a bound needs `routing-advice-receipt/2`, and a successor decision
contract also needs a new major.

## 5. Digest

`sha256()` is the SHA-256 of the whole encoding, tag included, as 64 lowercase hexadecimal digits. The tag is the domain
separation that keeps receipt digests apart from any other SHA-256 over Neuron data. It detects change and confirms byte
equality; it says nothing about who wrote the bytes, and anyone can build a valid receipt for any decision.

## 6. Decoding

`AdviceReceipt.decode` throws only for a null array. It copies the input, never modifies it, and returns
`Rejected(reason)` for any fault, checking in this order (first failure wins):

| Phase | Reason | When |
| --- | --- | --- |
| 0 | `OVERSIZE` | the array is longer than 65,536 bytes; no byte is read |
| 1 | `MALFORMED` | the tag is not well formed: empty or cut off input, a length outside 1 to 64, invalid UTF-8, or text that is not the prefix and a major as in Section 4 |
| 1 | `UNSUPPORTED_VERSION` | a well formed tag whose major is not `1` |
| 1 | `MALFORMED` | a structural fault at the first faulty field: input ends early; a string length that is negative, out of range or past the end; invalid UTF-8 (including overlong forms and encoded surrogates); a variant, validation, presence or boolean byte outside its values; a count that is negative or above its bound; an enum name that is not an exact constant name |
| 1 | `MALFORMED` | bytes remain after the last field |
| 2 | `INVALID_DECISION` | the values are well formed but a record constructor rejects them |
| 2 | `NON_CANONICAL` | the records build, but encoding the decision again does not give the input byte for byte (in v1 only for `exclusions` or `Abstain.eligible` out of canonical order) |

Phase 1 reads raw values and builds no record. Phase 2 builds the records through their public constructors and catches
only `IllegalArgumentException` and `NullPointerException`; the message is dropped because it can echo host tokens.
`Rejected` carries only the reason. A `Decoded` receipt equals `AdviceReceipt.of` of its decision.

## 7. Golden vectors

Built from the shared test fixtures (`ReceiptFixtures`) and checked against an independent reference writer
(`ReferenceReceiptWriter`) and committed hexadecimal in `ReceiptGoldenVectorTest`:

| Vector | Bytes | SHA-256 |
| --- | --- | --- |
| `Selected` with task id `tâche-17` | 678 | `9d52439fb53691c7ce3c828cb710f28696deb0b4b747327401e525642213ab23` |
| `Abstain(POLICY_TRADEOFF_UNRESOLVED)` | 575 | `e30c79ef194f40ad19666cc77df37919bfc4589b913ff517fed4140992eb0b18` |
| `Abstain(STATE_INCOMPATIBLE)` | 471 | `a9adddfc29e1c00a1c109b2624e8ccae775d9131b82b7a855116ea2c42645d23` |
| `NoEligibleRoute` | 373 | `d60de1ae6301c7c95562a4cae8309b1050070accafd5f265c8ab3d8dd1c95c28` |

Full bytes of the smallest vector, `NoEligibleRoute` (scope `scope-demo`, task `task-17`, one exclusion of `ex-gone`
version 3 for `MISSING_CAPABILITY` plus `REQUIRED_LIMIT_UNKNOWN`), in hexadecimal:

```text
00000018726f7574696e672d6164766963652d726563656970742f31030000000a73636f70652d64656d6f0000000774
61736b2d313700000006657865632d31000000056174742d3100000006732d696d706c00000000000000650000000a63
61742d64656d6f2d3700000009726f7574652d6c65780000000470762d3100000000000000320000000b73746174652d
73636f70650000000f7461736b2d66656174757265732f310000000b666f7267652d67617465730000000465762d3100
00000f726f7574696e672d73746174652f3100000000000000280000000870726f6475636572000000057070762d3102
0000000300000000000f726f7574696e672d73746174652f310000000f7461736b2d66656174757265732f3101000000
09696d706c656d656e74000000010000000765782d676f6e650000000000000003000000124d495353494e475f434150
4142494c495459000000010000001652455155495245445f4c494d49545f554e4b4e4f574e
```

## 8. Not the routing ledger digest

`routing-ledger-digest/1` ([ADR 0031](../adr/0031-routing-state-checkpoint-and-ledger-compaction.md), `LedgerDigest`) is
the count and the sum modulo `2^256` of the SHA-256 of each applied feedback revision. It is order independent and folds
across compaction, so a checkpoint can be compared with a rebuild. An advice receipt is the opposite shape: one ordered
encoding of one decision, hashed once. It records advice, never feedback, credit, execution or acceptance, and neither
value can stand in for the other.

## 9. Handling

Receipts hold opaque host tokens, which may carry sensitive host data. `AdviceReceipt.toString` shows only format, size and
digest. Neuron never logs, persists or transports a receipt; the host chooses whether and where to keep one, and an
explicit transport is the host's opt in.
