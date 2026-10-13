# ADR 0018: Embedded Resonance Store Recall Adapter

## Status

Accepted

## Context

ADR 0010 defined `ResonanceMemoryPort` and deferred any production adapter until a concrete
transport and compatibility need were known. Monada Resonance Store now exposes an embedded
developer API, `MonadaMemory` (`resonate(query).topK(k).threshold(t).execute()`), that queries by
text and returns text `KnowledgeAtom`s with scores. Neuron Signals carry no text, and the store is
an unpublished sibling project built on Java 26.

## Decision

A separate Gradle module, `monada-neuron-resonance-adapter`, is the only code that depends on both
Neuron memory contracts and Resonance Store API types. The dependency direction is
`adapter -> core` and `adapter -> store`; the root/core project never depends on the adapter or the
store, and its runtime classpath contains no `com.monada` artifacts.

`ResonanceStoreMemoryAdapter` opens one `MonadaMemory` at construction (or is bound to a caller-owned
instance with `using(...)`) and never reopens it per recall. `close()` marks the adapter
unavailable because the store API has no close operation.

Recall issues one bounded `topK(maxResults)` store query per query Signal, in request order. Candidates
are deduplicated by recalled atom (best candidate kept) and selected with a bounded heap ordered by
score descending, then query Signal order, then store rank. Ranking inside each query remains the
store's; Neuron only merges across Signals of one batch. Result references are a truncated SHA-256
of the store atom id, so neither store ids nor physical offsets become cognitive identity.

Failures are translated at the edge: closed adapter -> `UNAVAILABLE`; store `UncheckedIOException`,
non-finite scores, and codec rejection of store data -> `FAILED`; store open failures (including
encoding-profile mismatch) -> `ResonanceStoreAdapterException` at construction. `PARTIAL` and
`TIMED_OUT` are never produced by this synchronous, all-or-nothing adapter. Other runtime exceptions
propagate as operational failures per ADR 0010.

Signal-to-text and text-to-Signal translation is injected through `SignalQueryEncoder` and
`RecalledSignalDecoder`. The defaults (`CanonicalSignalQueryEncoder`, `HashedSignalDecoder`) are
deterministic interim placeholders with no semantic meaning; real deployments supply a domain codec.

The store is consumed as a Gradle composite build (`includeBuild("../monada-resonance-store")`),
included, together with the adapter module itself, only when the sibling checkout exists, so core builds and `./gradlew test` do not require it. Compatibility
expectation: the store's `monada-api` exposing `MonadaMemory.open(Path, MonadaMemoryOptions)` and
`resonate(...).topK(...).threshold(...).execute()` returning finite-scored results, at the sibling
checkout commit used to build; on-disk manifest versions (0.1-0.4) and encoding-profile
compatibility are enforced by the store when opening.

## Alternatives Considered

### Extend Signal or the request with text payloads

Rejected: it would change ADR 0005/0010 contracts and leak a text-store assumption into cognition.

### One concatenated query for the whole batch

Rejected: it loses per-Signal recall semantics; the bounded merge costs at most N times `maxResults`
candidates.

### Publish the store or vendor a jar

Deferred: no artifact repository exists yet; a composite build keeps sources authoritative.

## Consequences

- Core tests and artifacts run without the store; only the adapter module needs it.
- Contract tests run the same port semantics against a deterministic fixture and the production
  adapter over a temporary seeded store.
- Recall quality depends on the injected codec; the defaults are for reproducibility only.
- The store builds with the same Java 27 toolchain as Neuron, so adapter builds need no second JDK.
- CI exercises the adapter (Issue #84): the `adapter` job in `.github/workflows/ci.yml` checks out the store next to
  this repository and runs `./gradlew :monada-neuron-resonance-adapter:test`; it fails when the store checkout is
  missing or that task does not exist. It tracks store `main` rather than a pinned commit, because the store is still
  evolving in lockstep with Neuron and a pin would need constant bumps. Each run records the tested Neuron commit (the
  merge commit for pull requests) and store commit in the job summary; reproduce a run locally by checking out both at
  those commits in the sibling layout. A manual `workflow_dispatch` with `resonance_store_ref=<sha>` re-tests a branch
  head only, not a pull request merge commit, and is unavailable for fork pull requests. Trade-off: a store change can turn a Neuron pull request red
  without any Neuron change, and re-running the same pull request may test a different store commit. The Neuron-only
  `verify` job keeps proving that core builds and tests do not need the store.

- Execution experience is read through a second, separate seam (Issue #64): `StoreExperienceTranslator` turns the Store's
  bounded `execution-sample/1` exports into Neuron-owned `RoutingObservation`s. It is read-only against a caller-owned
  `ExecutionMemory`, keeps Store types inside the adapter module, never calls `feedback()` and never learns; recalled
  similarity is not quality or reward. See contract Section 20 of `docs/specs/forge-routing-contract-v1.md`.
- The adapter delegates each store query to the public `ResonanceStoreRecall` seam (production wraps
  `MonadaMemory`) and derives references through `OpaqueReference`. Behavior is unchanged; the seam lets
  the Neuron-owned translation and merge work run, and be benchmarked, without a store or duplicated logic.

## Follow-Up

- Domain-meaningful Signal codec.
- Remote transport and timeouts/virtual threads only if deployment requires them.
- Publish the store to a versioned repository and replace the composite build with a version range; at that point,
  or once the store stabilizes, replace the `main` tracking of the CI `adapter` job with a pinned revision or range.
- ~~Measure adapter translation overhead separately from store recall.~~ Done in Issue #49 with JMH; see
  [adapter overhead benchmark](../benchmarks/resonance-store-adapter-overhead.md).
- ~~Translate Store execution exports into routing observations.~~ Done in Issue #64; see the contract Section 20.
- ~~Exercise the adapter module in CI.~~ Done in Issue #84 against store `main`, with the tested commit recorded.
