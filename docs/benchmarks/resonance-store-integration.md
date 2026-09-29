# Neuron + Resonance Store Integration Evaluation

## Question

Does a complete Primary Monad cognitive cycle recall correctly from a **real** embedded Monada Resonance
Store through the production adapter ([ADR 0018](../adr/0018-resonance-store-embedded-recall-adapter.md)),
while preserving Neuron stage ordering, budget, and deterministic-replay semantics — and what does that
integration cost compared with the Neuron-only fixture memory used by the
[baseline](baseline-methodology.md)?

This is the integration oracle for the Neuron/Resonance Store boundary. It does **not** replace the
Resonance Store's own protected evaluation datasets and does not change store retrieval or ranking.

## Running

```bash
./gradlew :monada-neuron-evaluation:runResonanceStoreIntegration                          # full
./gradlew :monada-neuron-evaluation:runResonanceStoreIntegration -PbenchmarkArgs="--quick" # smoke
./gradlew :monada-neuron-evaluation:integrationTest                                        # correctness only
./gradlew test                                                                             # includes integrationTest when the store is present
```

Options: `--quick`, `--seed <n>`, `--output-dir <dir>` (default `build/reports/benchmarks`). Outputs
`resonance-store-integration.json` and `.md`. The process exits non-zero **only** when a semantic check
fails; latency never affects the exit status or any test.

The task requires the sibling checkout `../monada-resonance-store` (and its Java toolchain), exactly as the
adapter module does. Without it the integration source set is not created and `./gradlew test` is
unaffected. No network, credentials, or external services are used.

## Architecture

```text
monada-neuron-evaluation
  ├── main         store-free baseline runner, JMH, workload generator
  ├── integration  fixture corpus, temporary store, evaluation, report, runner   (optional)
  └── integrationTest                                                            (optional)
        │ depends on
        ▼
monada-neuron-resonance-adapter ──► monada-resonance-store (composite build)
```

The evaluation may depend on the adapter; production cognition never depends on evaluation code. Store
types (`MonadaMemory`) appear only in `TemporaryResonanceStore` and the adapter.

## Fixture (`rs-integration-v1`)

`ResonanceStoreFixtureCorpus` holds 11 short documents in four lexically separated topics (solar, ocean,
garden, chess) and 6 queries: multi-hit, bounded (`K` below the number of relevant documents), single-hit,
zero-match, and blank. Any change to documents or expectations bumps `VERSION`.

`FixtureSignalCodec` is the evaluation's domain codec (the default ADR 0018 codecs are non-semantic
placeholders). Queries are keyed by Signal **frequency** so a probe survives stage rewrites of kind,
amplitude, and phase; recalled documents decode to unique Signals whose labels are recoverable for
assertions only.

Assertions are semantic: top-K membership, order only where the lexical gap is large, non-increasing
finite scores, at most `K` results, unique references. Ties (for example two documents with equal store
score) are asserted as sets, never by physical position.

## Semantic checks

| Area | Verified |
| :--- | :--- |
| Recall | expected top-K per query; bounded prefix stability (limit 1 head = limit 3 head); multi-signal batch merge; deterministic repeat; zero-match and blank → `COMPLETE` + empty |
| Memory-only cycle | inputs precede recalled Signals in adapter order; deterministic replay; a tight signal budget admits exactly the head of the unbounded response and ends `CONTEXT_BUDGET_EXHAUSTED` |
| Full cycle | stage order PERCEPTION → MEMORY_RECALL → REASONING → ADAPTATION → ACTION, `COMPLETED`, budgets not exhausted, decodable recalled Signals, deterministic replay across independent setups, kinds/termination equal to the fixture-memory reference |
| Failure | closed adapter → `UNAVAILABLE` (inputs forwarded); deleted vector segments → `FAILED`, no results, no exception in the cycle; non-directory path and unsupported manifest → `ResonanceStoreAdapterException` at open |
| Lifecycle | temporary store directory deleted on close |

The full-cycle scenario uses threshold-routed propagation
(`ResonanceThresholdRoutingPolicy`, 0.5). The generator's default `routeAll(50, 4)` bound truncates the
perception stage on the standard topologies (`STAGE_LIMIT_REACHED`), which ends the cycle before memory
recall; the existing `DeterministicCognitiveCycle.FullCycle` baseline therefore currently exercises
perception only. `DeterministicWorkloadGenerator.generateFullCycleSetup` gained an overload taking an
explicit `PropagationConfig`; the original signature keeps its behavior.

## Measurement method

Setup is measured separately from recall and cycle windows, using the shared
`EvaluationMetricsCollector` (per-iteration untimed setup hook, thread-allocated bytes, GC deltas, RSS on
Linux).

| Row | Measures |
| :--- | :--- |
| `ResonanceStore.SeedFixtureCorpus` | temp dir, store open, remembering all documents (setup) |
| `ResonanceStore.OpenExistingStore` | adapter open on a persisted store (setup) |
| `ResonanceStore.FirstRecall.FreshlyOpenedAdapter` | first recall after an untimed adapter open, no warm-up |
| `ResonanceStore.Recall.Warm` | repeated recall on one open adapter |
| `NeuronStage.MemoryRecall.Warm` | `ResonanceMemoryCognitiveStage` over the real adapter |
| `Adapter.SignalTranslation.Only` | fixture encode/decode only |
| `NeuronCycle.FullCycle.RealResonanceStore` | full 5-stage cycle, fresh state per iteration |
| `NeuronCycle.FullCycle.DeterministicMemoryFixture` | same cycle with the Neuron-only fixture memory |

Limits: adapter translation is isolated only for the codec; the adapter's bounded merge and SHA-256
reference hashing are not measured separately. "Cold" means a freshly opened handle in a warmed JVM, not
a cold OS page cache. Cycles use fresh `PrimaryMonad`/topology state per iteration.

## Sample results (exploratory, not a gate)

JDK 27 (Zulu), Linux amd64, 4 processors, G1, default heap; store checkout `822b4b3`, manifest `0.4`,
`SimpleFrequencyEncoder` 128 dimensions; full mode, seed 42. Numbers are machine-specific.

| Benchmark | Mean | p95 | Alloc / op |
| :--- | :--- | :--- | :--- |
| `ResonanceStore.SeedFixtureCorpus` (11 docs) | 16.9 ms | 23.8 ms | 803 KB |
| `ResonanceStore.OpenExistingStore` | 2.5 ms | 6.3 ms | 56.7 KB |
| `ResonanceStore.FirstRecall.FreshlyOpenedAdapter` (3 queries, K=5) | 10.9 ms | 13.8 ms | 420 KB |
| `ResonanceStore.Recall.Warm` (3 queries, K=5) | 11.1 ms | 13.3 ms | 415 KB |
| `NeuronStage.MemoryRecall.Warm` | 10.3 ms | 13.8 ms | 415 KB |
| `Adapter.SignalTranslation.Only` | 20 µs | 38 µs | 119 B |
| `NeuronCycle.FullCycle.RealResonanceStore` (2 inputs) | 9.8 ms | 13.4 ms | 311 KB |
| `NeuronCycle.FullCycle.DeterministicMemoryFixture` | 0.67 ms | 0.91 ms | 28 KB |

Observations, not conclusions:

- Recall is dominated by store queries (about 3.5 ms per query Signal here); adapter codec translation is
  well under 1% of the stage.
- Opening the store once is cheap relative to a query, which supports ADR 0018's open-once design.
- With real memory the cycle is about an order of magnitude slower than with the fixture, almost entirely
  in the memory stage. Larger corpora, more query Signals per batch, and concurrency remain exploratory
  follow-ups.

## Reproducibility

Reports embed: fixture version, document/query counts, adapter threshold, memory-stage limit, Neuron
version and commit, store checkout commit, and the persisted store manifest's version, encoder,
normalizer, dimensions, and vector format. Semantic verdicts and metadata are identical across runs (a
test asserts this); only timestamps and measurements differ.
