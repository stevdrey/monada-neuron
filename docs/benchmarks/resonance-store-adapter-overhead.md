# Resonance Store Adapter Translation and Merge Overhead (JMH)

## Question

How much CPU time and allocation does the **Neuron-owned** part of the production
`ResonanceStoreMemoryAdapter` ([ADR 0018](../adr/0018-resonance-store-embedded-recall-adapter.md)) add around a
Resonance Store recall, independently of the store's own encoding, search, and persistence?

The [integration evaluation](resonance-store-integration.md) measures real-store recall and cycle latency but
deliberately makes no standalone translation-overhead claim: codec work is too small for its wall-clock
collector. This suite uses JMH instead.

## Scope

Measured (Neuron side only):

```text
Signal -> SignalQueryEncoder -> [store recall] -> bounded merge/dedup -> OpaqueReference (SHA-256)
       -> RecalledSignalDecoder -> ResonanceMemoryResult
```

Explicitly **excluded**: `MonadaMemory` search/index/persistence, disk I/O, store encoding, Store ranking,
remote transport, concurrency. The store recall is replaced by a deterministic substitute that returns
prebuilt `ResonanceResult` lists.

No benchmark duplicates production logic. The adapter gained two small public seams with unchanged behavior:

- `ResonanceStoreRecall`: the per-query store operation. Production wraps `MonadaMemory`; the benchmarks
  pass a substitute, so they execute the **real** `ResonanceStoreMemoryAdapter.recall(...)`.
- `OpaqueReference`: the SHA-256 + truncated-hex reference derivation, so it can be timed directly.

Store-library types in the benchmark code are limited to result/option types (`ResonanceResult`,
`KnowledgeAtom`, `MonadaMemoryOptions`) used by the fixtures; `MonadaMemory` is never opened or called.

The benchmarks live in the `integration` source set of `monada-neuron-evaluation` (they need the optional
adapter module, so they exist only when the sibling `monada-resonance-store` checkout is present) under
`monada.neuron.evaluation.integration.jmh`.

## Benchmarks

| Class | Isolates | Parameters |
| :--- | :--- | :--- |
| `ResonanceStoreAdapterQueryEncodingBenchmark` | `CanonicalSignalQueryEncoder.encode`, per **batch** | `signals` = 1, 3, 8 |
| `ResonanceStoreAdapterDecodingBenchmark` | `HashedSignalDecoder.decode` | `contentLength` = 64, 1024, 16384 |
| `ResonanceStoreAdapterOpaqueReferenceBenchmark` | `OpaqueReference.of` (one UUID atom id) | none |
| `ResonanceStoreAdapterMergeBenchmark` | adapter loop + bounded merge/dedup + references + result construction, with trivial constant codecs | `querySignals` x `maxResults` x `duplicates` |
| `ResonanceStoreAdapterBoundaryBenchmark` | everything Neuron-owned: production default codecs + merge + references | `querySignals` x `maxResults` x `duplicates` |

Parameter matrix for merge and boundary: `querySignals` 1, 3, 8; `maxResults` 1, 5, 10, 32; `duplicates`
`NONE` (every candidate a distinct atom), `MODERATE` (even store ranks shared across query Signals), `HIGH`
(every rank shared, so only `maxResults` distinct atoms exist). Each query Signal receives exactly
`maxResults` store results, so the candidate count is `querySignals x maxResults`, the adapter's bound.
Scores tie across Signals on equal ranks to exercise the signal-order and store-rank tie-breakers.
Recalled content is 64 characters in the merge/boundary benchmarks (integration-fixture sized); the decoder
benchmark covers larger payloads.

Validation is outside the measured path. In `@Setup(Level.Trial)` every scenario is checked by
`AdapterBoundaryOracle`, a deliberately naive reference (collect all candidates, keep the best per atom, sort
everything): result count <= `maxResults`, same references and scores in the same order as the reference,
scores finite and non-increasing, references match `rs-` + 32 hex, and repeated recalls return equal
responses. `AdapterBoundaryScenarioTest` runs the full matrix against the production adapter in
`integrationTest` (part of `./gradlew test`). No latency threshold exists anywhere in the build.

## JMH configuration and commands

```text
mode: average time (ns/op)   threads: 1   forks: 3   warm-up: 3 x 1s   measurement: 5 x 1s
profiler: gc (alloc.rate.norm, gc.count, gc.time)   results returned from the benchmark method
```

```bash
./gradlew :monada-neuron-evaluation:jmhResonanceStoreAdapter                      # full run, defaults above (~40 min)
./gradlew :monada-neuron-evaluation:jmhResonanceStoreAdapter -PjmhArgs="-bm avgt -f 1 -wi 1 -i 2 -r 300ms -prof gc -p querySignals=3 -p maxResults=5 ResonanceStoreAdapterBoundary"   # smoke
```

The generic `:jmh` task only sees the `main` source set, which cannot depend on the optional adapter, hence
the dedicated task. `-PjmhArgs` replaces the defaults and is split on spaces.

The substitute store is stateful (a cursor cycling over the per-Signal result lists) and is meant for these
single-threaded benchmarks only; the adapter calls the store exactly once per query Signal, in order.

## Environment

JDK 27+35 (Zulu, OpenJDK 64-Bit Server VM), `--add-modules=jdk.incubator.vector`, G1 with default heap;
Linux 7.2 (Fedora), AMD Ryzen 7 7730U (16 hardware threads, laptop part, frequency scaling active), 14 GiB
RAM, other desktop applications running; Neuron `ce85c04` plus this change. Numbers are exploratory,
machine-specific, and not a gate; the `+/-` values are JMH 99.9% confidence half-widths over 3 forks x 5
iterations and some rows (for example 3 signals x 10 results, `MODERATE`) are visibly noisier.

## Results

Time per operation (ns) +/- error, allocation in bytes per operation (`gc.alloc.rate.norm`).

### Component latencies

| Benchmark | Time | Alloc / op |
| :--- | :--- | :--- |
| Query encoding, 1 Signal | 98.6 +/- 2.1 ns | 296 B |
| Query encoding, 3 Signals (per batch) | 372 +/- 9.8 ns | 968 B |
| Query encoding, 8 Signals (per batch) | 1 152 +/- 62 ns | 2 776 B |
| Decoding, 64 chars | 152 +/- 2.1 ns | 440 B |
| Decoding, 1 KiB | 654 +/- 28 ns | 1 395 B |
| Decoding, 16 KiB | 10 176 +/- 65 ns | 16 760 B |
| Opaque reference (SHA-256) | 141 +/- 4.7 ns | 544 B |

### Merge only (`ResonanceStoreAdapterMergeBenchmark`, trivial codecs), ns/op / B/op

| querySignals x maxResults | NONE | MODERATE | HIGH |
| :--- | :--- | :--- | :--- |
| 1 x 1 | 221 / 960 | 233 / 960 | 229 / 949 |
| 3 x 5 | 1 685 / 4 664 | 1 344 / 4 347 | 1 323 / 4 253 |
| 3 x 10 | 3 739 / 9 045 | 3 063 / 8 632 | 2 634 / 8 232 |
| 8 x 10 | 9 039 / 13 165 | 5 583 / 11 699 | 3 859 / 10 648 |
| 8 x 32 | 36 587 / 42 270 | 24 349 / 38 200 | 12 201 / 33 768 |

### Combined adapter-side boundary (`ResonanceStoreAdapterBoundaryBenchmark`), ns/op / B/op

| querySignals x maxResults | NONE | MODERATE | HIGH |
| :--- | :--- | :--- | :--- |
| 1 x 1 | 503 / 1 723 | 534 / 1 744 | 574 / 1 760 |
| 1 x 5 | 1 878 / 6 352 | 2 030 / 6 371 | 2 129 / 6 360 |
| 3 x 1 | 869 / 2 528 | 856 / 2 480 | 886 / 2 480 |
| **3 x 5** (the #31 shape) | **2 750 +/- 38 / 7 912** | 2 640 +/- 32 / 7 595 | 2 567 +/- 31 / 7 512 |
| 3 x 10 | 5 404 / 14 680 | 5 160 / 14 168 | 4 675 / 13 771 |
| 8 x 10 | 11 442 / 20 616 | 8 784 / 19 248 | 6 996 / 17 987 |
| 8 x 32 | 41 017 / 60 320 | 31 684 / 55 568 | 20 502 / 50 971 |

GC counts are 80-220 per 5 s iteration set at these rates (young collections of short-lived garbage); GC
time stays in the low milliseconds per measurement and is not a distinct cost here.

## Reading the results

- **Scaling.** The boundary cost scales roughly linearly with the number of returned results and the
  number of query Signals: about 0.37 us per returned result at `maxResults` 32 with one Signal
  (11.9 us / 32), and about 0.5 us for the fixed 1 x 1 case. Allocation follows the same shape: roughly
  1.2 KB per returned result.
- **SHA-256 is a material share of the merge.** One reference costs about 141 ns and 544 B, and exactly
  one is derived per retained result. At 1 x 32 with no duplicates that is 32 x 141 = about 4.5 us of the
  7.2 us merge (roughly 63%); at 3 x 5 it is about 0.7 us of 1.7 us (about 42%). At these sizes
  reference derivation is the largest single contributor to the merge.
- **Dedup is not the cost driver.** More duplicates make the merge cheaper (fewer retained candidates and
  fewer heap operations); `HIGH` at 8 x 32 is about a third of `NONE`. The decoder (which also hashes) adds
  roughly the same per-result cost as a reference at 64-character content (152 ns), and grows with content
  size (about 10 us for 16 KiB).
- **Codec share.** At 3 x 5, the boundary (about 2.75 us) minus the merge (about 1.7 us) leaves about
  1.1 us for encoding three Signals (about 0.37 us) plus decoding the five retained results (about
  0.76 us), consistent with the component rows. Boundary and merge rows are separate JMH runs, so the
  subtraction is approximate.
- **Fixture notes.** Consecutive store ranks of one Signal share a score and Signals with the same index
  modulo 3 tie on equal ranks, so both tie-breakers decide the order; the substitute store keeps the last
  query it received, so the encoder output is observable work and cannot be optimized away.

## Relating this to the real-store numbers (Issue #31)

The integration evaluation's sample run (exploratory, a different session and a 4-processor machine, so
the comparison is **qualitative only**) reports a warm real-store recall of about 10.8 ms for 3 queries at
`K` = 5 and a real-store full cycle of about 8.2 ms. The same shape here costs about 2.6-2.75 us of
Neuron-owned work: roughly four orders of magnitude (about 0.03%) below the store recall. Even the
largest cell (8 Signals x 32 results, 41 us) is far below a single warm store recall measured on the small
fixture corpus.

Conclusion supported by this evidence: for the integration-evaluation shape, adapter-side translation and
merge is negligible next to the Resonance Store recall, and reducing store work (not the adapter) is where
latency improvements must come from. The picture can change if a domain codec is much heavier than the
placeholder codecs, if recalled payloads are large (decoding is dominated by hashing and scales with
content length), or if store recall becomes much faster. This does not establish a latency SLO, and the
absolute numbers are not portable across machines.

To refresh the comparison, run `runResonanceStoreIntegration` and this suite on the same machine in the
same session and compare `ResonanceStore.Recall.Warm` with the matching boundary cell.

## Limitations

- The substitute store returns prebuilt, cache-hot lists, so memory-locality effects of real store results
  are not represented.
- Only the default placeholder codecs are measured; a domain codec changes the encode/decode share.
- Single-threaded, one JVM per fork; concurrent recalls are out of scope.
- Sample results come from one laptop-class machine while other applications were running.
