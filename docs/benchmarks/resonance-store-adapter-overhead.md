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
| Query encoding, 1 Signal | 97.7 +/- 2.2 ns | 296 B |
| Query encoding, 3 Signals (per batch) | 360 +/- 4.7 ns | 968 B |
| Query encoding, 8 Signals (per batch) | 1 155 +/- 45 ns | 2 776 B |
| Decoding, 64 chars | 140 +/- 1.4 ns | 440 B |
| Decoding, 1 KiB | 618 +/- 8.2 ns | 1 405 B |
| Decoding, 16 KiB | 10 003 +/- 94 ns | 16 755 B |
| Opaque reference (SHA-256) | 139 +/- 3.1 ns | 560 B |

### Merge only (`ResonanceStoreAdapterMergeBenchmark`, trivial codecs), ns/op and B/op

| querySignals x maxResults | NONE | MODERATE | HIGH |
| :--- | :--- | :--- | :--- |
| 1 x 1 | 220 / 960 | 220 / 955 | 225 / 960 |
| 3 x 5 | 1 537 / 4 648 | 1 334 / 4 336 | 1 256 / 4 248 |
| 3 x 10 | 3 498 / 9 152 | 3 017 / 8 640 | 2 557 / 8 224 |
| 8 x 10 | 7 720 / 13 157 | 5 370 / 11 797 | 3 696 / 10 541 |
| 8 x 32 | 35 489 / 42 270 | 22 806 / 37 347 | 11 898 / 33 939 |

### Combined adapter-side boundary (`ResonanceStoreAdapterBoundaryBenchmark`), ns/op and B/op

| querySignals x maxResults | NONE | MODERATE | HIGH |
| :--- | :--- | :--- | :--- |
| 1 x 1 | 544 / 1 736 | 551 / 1 720 | 562 / 1 712 |
| 1 x 5 | 1 891 / 6 312 | 1 949 / 6 336 | 2 112 / 6 309 |
| 3 x 1 | 877 / 2 528 | 857 / 2 480 | 917 / 2 480 |
| **3 x 5** (the #31 shape) | **2 758 +/- 56 / 7 896** | 2 518 +/- 42 / 7 584 | 2 549 +/- 32 / 7 512 |
| 3 x 10 | 5 488 / 14 672 | 5 884 / 14 168 | 4 619 / 13 760 |
| 8 x 10 | 10 561 / 20 616 | 8 699 / 19 224 | 6 796 / 17 992 |
| 8 x 32 | 39 492 / 60 320 | 31 502 / 55 568 | 18 778 / 50 971 |

GC counts are 80-220 per 5 s iteration set at these rates (young collections of short-lived garbage); GC
time stays in the low milliseconds per measurement and is not a distinct cost here.

## Reading the results

- **Scaling.** The boundary cost scales roughly linearly with the number of returned results and the
  number of query Signals: about 0.4 us per returned result at `maxResults` 32 with one Signal
  (12.3 us / 32), and about 0.54 us for the fixed 1 x 1 case. Allocation follows the same shape: roughly
  1.2 KB per returned result.
- **SHA-256 is a material share of the merge.** One reference costs about 139 ns and 560 B, and exactly
  one is derived per retained result. At 1 x 32 with no duplicates that is 32 x 139 = about 4.4 us of the
  6.8 us merge (roughly two thirds); at 3 x 5 it is about 0.7 us of 1.5 us (45%). At these sizes
  reference derivation is the largest single contributor to the merge.
- **Dedup is not the cost driver.** More duplicates make the merge cheaper (fewer retained candidates and
  fewer heap operations); `HIGH` at 8 x 32 is about a third of `NONE`. The decoder (which also hashes) adds
  roughly the same per-result cost as a reference at 64-character content (140 ns), and grows with content
  size (about 10 us for 16 KiB).
- **Codec share.** At 3 x 5, the boundary (about 2.7 us) minus the merge (about 1.5 us) leaves about
  1.2 us for encoding three Signals (about 0.36 us) plus decoding the five retained results (about
  0.7 us), consistent with the component rows. Boundary and merge rows are separate JMH runs, so the
  subtraction is approximate.

## Relating this to the real-store numbers (Issue #31)

The integration evaluation's sample run (exploratory, a different session and a 4-processor machine, so
the comparison is **qualitative only**) reports a warm real-store recall of about 10.8 ms for 3 queries at
`K` = 5 and a real-store full cycle of about 8.2 ms. The same shape here costs about 2.5-2.8 us of
Neuron-owned work: roughly four orders of magnitude (about 0.03%) below the store recall. Even the
largest cell (8 Signals x 32 results, 39 us) is far below a single warm store recall measured on the small
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
