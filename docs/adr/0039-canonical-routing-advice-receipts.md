# ADR 0039: Canonical Routing Advice Receipts

## Status

Accepted (issue #97; adds `monada.neuron.routing.receipt` and the `routing-advice-receipt/1` format, and amends
`forge-integration-readiness/1` under ADR 0033 as a minor, additive bump)

## Context

A future Forge review and delivery loop needs to correlate what Neuron advised with what the host later ran, including
delayed evidence and replay, without persisting a mutable Java object graph. The
[readiness contract](../specs/forge-integration-readiness-v1.md) reserved this as "the retained decision used as a
receipt" and left the size cap and version rule to issue #97.

The forces:

1. **Faithfulness and zero ambiguity carry the weight.** Two parties far apart in time must be able to agree they hold
   the same advice. Byte equality must mean decision equality, in both directions.
2. **A receipt must never read as more than advice.** The readiness contract's never permission list (Section 2.2)
   already says a decision grants nothing. A receipt is a copy of that decision, so it inherits the rule.
3. **Neuron already has one digest.** `routing-ledger-digest/1` (ADR 0031) is an order independent sum of SHA-256 over a
   set of applied feedback revisions, used to compare a checkpoint with a rebuild. It digests feedback state, not advice.
4. **Hard limits from the issue.** No JSON or other dependency, no I/O, no secrets or task body, no token generation, no
   change to old decision types, bounded immutable data, linear encode and hash, portable Java 27 APIs only.

## Decision

- **`AdviceReceipt.of(RoutingDecision)`** encodes every component of any decision variant once, in declaration order, as
  length prefixed big endian fields after the tag `routing-advice-receipt/1`, and computes SHA-256 over the whole
  encoding. The tag is the domain separation. `AdviceReceipt` is a final class (it holds a `byte[]`) whose equality
  compares bytes; `toString` shows only format, size and digest.
- **`AdviceReceipt.decode(byte[])`** reads strictly and returns a sealed `DecodeResult`: `Decoded(receipt)` or
  `Rejected(reason)` with a closed `RejectReason` (`OVERSIZE`, `UNSUPPORTED_VERSION`, `MALFORMED`, `NON_CANONICAL`,
  `INVALID_DECISION`). Decoding parses a private copy in two phases: phase 1 reads raw values through one bounds checked
  reader and builds no record; phase 2 builds records through their public constructors, then encodes again and requires
  the input byte for byte. Only `IllegalArgumentException` and `NullPointerException` from phase 2 constructors are
  caught; their messages are dropped because they can echo host tokens.
- **Primitives follow `LedgerDigest`**: a string is a 4 byte length and strict UTF-8 (decoded with a reporting
  `CharsetDecoder`, never replaced), an enum constant is its exact name, an optional is a presence byte, a list is a count.
  Unknown enum names are `MALFORMED`: v1 has a closed vocabulary.
- **The cap is 65,536 bytes** (`MAX_ENCODED_BYTES`), checked before any byte is read. The measured worst case under
  today's decision bounds is 38,437 bytes (an `Abstain(POLICY_TRADEOFF_UNRESOLVED)` with eight eligible routes and 24
  exclusions).
- **Major only versioning.** The tag's major is compared as a digit string; anything other than `1` is
  `UNSUPPORTED_VERSION`. Any change to a decision record, an enum constant used in the layout, or a bound needs
  `routing-advice-receipt/2`. `routing-advice-receipt/1` is defined over `routing-decision/1` only.
- **Integrity comparator only.** SHA-256 detects change and confirms byte equality; anyone can build a valid receipt for
  any decision. A receipt is not proof that anything ran or was accepted, not authentication, not a signature and not a
  routing ledger digest. Neuron never logs, persists or transports receipts; the host decides whether and where to keep
  them.

The byte layout, bounds, decode precedence and golden vectors are normative in
[routing-advice-receipt-v1.md](../specs/routing-advice-receipt-v1.md).

## Alternatives

- **An advice subset** (identity, provenance, variant, route, mode, basis or reason; no candidates, rules or exclusions).
  Smaller, but two decisions with different rankings or exclusions would share a receipt, and the decoder could not give
  the decision back.
- **A subset plus a digest of the full decision.** Detects divergence, but means two encodings to specify and version,
  and the host still cannot recover the explanation.
- **Canonical text** (one field per line, escaped values). Readable, but escaping and number formatting become the main
  place for collisions and silent normalization, and a text format invites logging of sensitive host tokens.
- **Reusing `LedgerDigest`.** Wrong semantics: a modular sum over a set is order independent and foldable, which is right
  for a ledger and wrong for one piece of advice, and it would blur the line between advice and feedback state.
- **Minor versions with skippable fields.** A v1 reader would accept bytes it only partly understands while the digest
  still covers them; a new major is cheap and the tag announces it.

## Consequences

- Forge can store a small, stable record of each piece of advice and compare it with a replay or with what ran, by bytes
  or by digest, without Java serialization or a JSON library.
- One decision has exactly one valid byte string and one digest; `decode` repairs nothing.
- The byte layout is now a contract. Changing a `RoutingDecision` record, an enum constant used in it, or a bound such as
  `RouteCatalog.MAX_ROUTES` requires a new major, and stored v1 bytes stay readable only by a v1 decoder. Tests pin every
  enum vocabulary and four golden vectors so such a change fails a named test.
- Decoding costs about four times an encode: it reads every field into deferred records, builds them, and encodes
  again. An informal wall clock probe over the five worst case decisions (33 to 38 KB, warmed, no JMH yet) measured
  roughly 25 µs per encode, 110 µs per decode and 44 µs for `of` with its digest on the development machine. The
  path is cold (stored receipts are read occasionally), linear and bounded by the cap; a JMH benchmark belongs with
  the checkpoint codec (#100) if the shared primitives move.
- `RoutingDecision` and every type outside `monada.neuron.routing.receipt` are unchanged. No dependency and no preview API
  is added.

## Follow-Up

- Dispositions (#98) should reference a receipt by its `sha256()` plus its `DecisionRef`, never by adding fields to the
  receipt.
- The checkpoint codec (#100) should reuse these primitive rules (string, list, optional, tag and version rule); moving a
  shared writer and reader into `routing.token` can happen then.
- A smaller summary record, if Forge needs one, would be a separate tag, not a change to `routing-advice-receipt/1`.
