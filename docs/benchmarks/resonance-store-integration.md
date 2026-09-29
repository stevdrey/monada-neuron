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
| `NeuronCycle.FullCycle.RealResonanceStore` | full 5-stage cycle, fresh state per iteration |
| `NeuronCycle.FullCycle.ReplayedMemoryResponse` | same cycle with a no-I/O port replaying the responses recorded from the real store, so downstream stages process identical Signals |

Limits: adapter translation overhead (codec, bounded merge, SHA-256 references) is **not** measured here: a
wall-clock loop is not a reliable microbenchmark, so it is left to a JMH follow-up (ADR 0018). "Cold" means a freshly opened handle in a warmed JVM, not
a cold OS page cache. Cycles use fresh `PrimaryMonad`/topology state per iteration. Adapters and stores opened per iteration
are released in the untimed iteration setup, so they do not stay reachable into later rows. Warm-up and
measurement counts differ per row and are recorded in each row's diagnostics (`warmupIterations`,
`measurementIterations`); the report's run configuration does not advertise baseline defaults.

## Sample results (exploratory, not a gate)

JDK 27 (Zulu), Linux amd64, 4 processors, G1, default heap; store checkout `822b4b3`, manifest `0.4`,
`SimpleFrequencyEncoder` 128 dimensions; full mode, seed 42. Numbers are machine-specific.

| Benchmark | Mean | p95 | Alloc / op |
| :--- | :--- | :--- | :--- |
| `ResonanceStore.SeedFixtureCorpus` (11 docs) | 38.2 ms | 59.7 ms | 803 KB |
| `ResonanceStore.OpenExistingStore` | 1.8 ms | 3.6 ms | 56.7 KB |
| `ResonanceStore.FirstRecall.FreshlyOpenedAdapter` (3 queries, K=5) | 16.1 ms | 23.1 ms | 421 KB |
| `ResonanceStore.Recall.Warm` (3 queries, K=5) | 10.8 ms | 14.7 ms | 415 KB |
| `NeuronStage.MemoryRecall.Warm` | 12.0 ms | 18.7 ms | 416 KB |
| `NeuronCycle.FullCycle.RealResonanceStore` (2 inputs) | 8.2 ms | 9.1 ms | 311 KB |
| `NeuronCycle.FullCycle.ReplayedMemoryResponse` | 1.5 ms | 4.0 ms | 33 KB |

Observations, not conclusions:

- The cycle with the real store costs several times the replayed cycle; the difference is the memory
  port (store queries), since both process identical Signals. Single-run wall-clock deltas on a small
  corpus are noisy (see the replay row's p95).
- Opening the store once is cheap relative to a query, which supports ADR 0018's open-once design.
- Larger corpora, more query Signals per batch, concurrency, and adapter translation overhead (JMH)
  remain exploratory follow-ups.

## Reproducibility

Reports embed: fixture version, document/query counts, adapter threshold, memory-stage limit, Neuron
version and commit, store checkout commit, and the persisted store manifest's version, encoder,
normalizer, dimensions, and vector format. Semantic verdicts and metadata are identical across runs (a
test asserts this); only timestamps and measurements differ.
