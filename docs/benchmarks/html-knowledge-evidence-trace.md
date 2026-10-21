# HTML Knowledge Evidence Trace (`l3-evidence-v1`)

## Question

When `html-kc-v1` is persisted in a real embedded Monada Resonance Store and reopened in a new process, does a Neuron
cognitive cycle actually **carry** the evidence that the production memory port recalls into the stages after
`MEMORY_RECALL`? And can every evidence pointer the cycle carries be joined back to a verifiably persisted source chunk,
without consulting any gold label?

This page covers Experiment Knowledge 5/8, [#108](https://github.com/stevdrey/monada-neuron/issues/108). It implements
the protocol's [evidence use (L3)](../experiments/html-knowledge-v1.md#evidence-use-l3-delivered-by-108) extension
`l3-evidence-v1`, over the same corpus, query set, codec and adapter configuration as
[judged recall](html-knowledge-recall.md) (B2).

"Memory present" and "memory used" are reported separately. A recalled chunk that the next stage never receives is
present, not used. Even a used chunk is only **carried**: this page does not measure reasoning quality, produces no
answer text, and claims no learning. Stored text is external memory, not trained model parameters.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task. CI runs these tests in the
adapter job as part of `:monada-neuron-evaluation:integrationTest`.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeEvidenceTrace
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeEvidenceTrace -PbenchmarkArgs="--store-root /tmp/html-kq-evidence"
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*EvidenceTrace*'
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.HtmlKnowledgeCycleExperimentTest'
```

Options, exit statuses and output preparation are the same as for
[persistence](html-knowledge-persistence.md#running). With `--store-root`, the directory holds the two Store roots
`full` and `removed`. Outputs, under `build/reports/benchmarks/` of `monada-neuron-evaluation`:

| File | Content |
| :--- | :--- |
| `html-knowledge-evidence-trace.json` | the single machine-readable report: `allChecksPassed`, the checks, the required metadata, and the scored payload under `trace` (`null` when nothing was scored). The payload starts with the `limits`, then aggregates per arm (with `byCategory`), the memory-empty contrast for `A` and `U`, the removed-document targets, and one row per query with its trace in every arm |
| `html-knowledge-evidence-trace.md` | the same, readable |
| `html-kq-v1-evidence-trace.tsv` | every raw trace row of the trace JVM. Its first line states `corpusHash` and `allChecksPassed`: it is not a verified result on its own |

## How a run works

1. The orchestrator writes the labels-free workload (`queryHash`, query ids and texts), exactly as for recall.
2. A **seed** JVM imports `html-kc-v1` into root `full` and `html-kc-v1-without-links` (the same corpus without
   `doc:links`, 28 chunks, validated as its own corpus version) into root `removed`, writes both manifests, and exits.
3. A **trace** JVM reopens both roots. For each root it reads every chunk back through a new `MonadaMemory` handle and
   builds that root's `RecalledReferenceResolver` from what it read back. It then opens the production
   `ResonanceStoreMemoryAdapter` (the B2 configuration: `token-packing v1`, `HashedSignalDecoder`, default options,
   `t = 0.0`) and runs `HtmlKnowledgeCycleExperiment`.
4. The orchestrator scores the trace rows with `EvidenceTraceMetrics`, the only reader of gold labels.

The phase JVMs never load the query set. A test checks that no trace-side class references a label type.

### The cycle

`MEMORY_RECALL -> REASONING -> EVALUATION`, a subset of the canonical stage order, run by the production
`DeterministicCognitiveCycle` with a fresh `PrimaryMonad` (fixed id) per run:

| Stage | Component | Role |
| :--- | :--- | :--- |
| `MEMORY_RECALL` | production `ResonanceMemoryCognitiveStage`, limit 5, over a recording port | emits the input Signals, then the recalled Signals |
| `REASONING` | `RecalledEvidenceReasoningStage` (evaluation-only) | proposes one fixed proposition and attaches one `MemoryReferenceEvidence(reference, SUPPORTS, 1.0)` per admitted result; passes every Signal through |
| `EVALUATION` | production `HypothesisEvaluationCognitiveStage` with `ReferenceHypothesisEvaluationPolicy`, `maxSelected = 1` | scores and selects the hypothesis |

- **No `PERCEPTION` stage.** An Aeon stage rewrites Signals, and the codec rejects Signals it did not produce. The
  memory request is therefore exactly the codec Signals, and `requestMatchesCodec` checks that.
- **No `ADAPTATION` or `ACTION` stage.** They mutate Node state or execute actions, and this experiment must do
  neither.
- **The evidence consumer reads no text and no score.** It is the explicit, documented component the protocol
  requires before a `MemoryReferenceEvidence` counts as use. It is not a production semantic stage. Its constant
  weight means nothing is tuned.

Budget: `CognitiveBudget(16, 512, 256)`, which is roomy for 32 query Signals and 5 results. The trace key `τ` is the
protocol default: value equality of the `Signal` record.

### Evidence resolution and decision

The cycle carries Signals and **opaque references**. The external host resolves them:

- **Resolver.** A reference resolves to `(chunkId, sha256)` only when the root's manifest maps it **and** the trace JVM
  read that chunk back from the reopened root with content bytes hashing to the corpus `sha256`.
- **Failing closed.** An unknown reference, an unverified chunk, or two manifest entries sharing one reference fail
  closed.
- **Cost.** Resolution is one lookup in a fixed map of at most 40 entries, so `O(K)` per cycle.

The host decision reads only stage results and the resolver:

| Decision | When |
| :--- | :--- |
| `SUPPORTED` | the recall completed, and the selected hypothesis has `SUPPORTS` evidence whose every reference was admitted in this cycle and resolves |
| `UNSUPPORTED` | the recall completed, and no hypothesis with supporting evidence was selected |
| `RECALL_FAILED` | the port answered `UNAVAILABLE`, `TIMED_OUT` or `FAILED` |
| `INCOMPLETE` | the cycle ended or failed before `EVALUATION` ran |
| `FAILED_CLOSED` | selected evidence is not an admitted, resolved memory reference |

`SUPPORTED` means "the cycle carried resolvable recalled evidence". It is not an answer, and it is not judged against
any label. The scorer's `memorySupportedRelevant` adds the label check: `SUPPORTED`, and a used chunk is in `Rel(q)`.
That is the most this experiment lets anyone call a memory-supported result.

### Arms and controls

Every arm runs every query, in this fixed order. The real port always runs before the memory-empty port.

| Arm | Port and input | What it shows |
| :--- | :--- | :--- |
| `MEMORY` | production adapter, root `full` | the trace under test |
| `MEMORY_EMPTY` | a port answering `COMPLETE` with no results | memory disabled; the `MemoryEmptyContrast` reference |
| `SWAPPED` | root `full`, slot `i` receives the text of query `(i + n/2) mod n` | evidence follows the text, not the slot |
| `REMOVED` | root `removed` | a removed relevant document cannot be reported as support |
| `TIGHT_BUDGET` | root `full`, `maxSignals = 2 · querySignals + 1` | memory present but not used: one result admitted, no later stage runs |
| `UNAVAILABLE` | a closed adapter over `full` | recall failure keeps the cycle running and is never support |
| `THROWING` | a port that throws | failure propagation: `CognitiveCycleException` at `MEMORY_RECALL` |

Distractor and unanswerable queries are the `distractor` category and the `U` population of `html-kq-v1`. They are
reported per category and per population.

Each row records:
- the cycle inputs and whether the request matched the codec (`requestMatchesCodec`);
- the port's full recorded response (chunk ids and scores), and, for the arms that recall through an open adapter
  (`MEMORY`, `SWAPPED`, `REMOVED`, `TIGHT_BUDGET`), whether it equals a direct B2 recall of the same request on the
  same root (`recalledEqualsB2`, `null` for the other arms). Both flags sit next to `RelevantEvidenceUsed` in every
  query row, so a metric computed from a non-equivalent recall is identifiable;
- the admitted memory-stage result and its status;
- the chunks carried by Signal into later stage outputs, attached as evidence, and selected by `EVALUATION`;
- `traceAmbiguous`;
- the stage list, termination and signal-budget flag;
- the cycle output count and the decision;
- for `MEMORY`, the memory-empty contrast.

## Checks

Trace JVM, per root (`full`, `removed`):
- `persistence.readback`: every chunk read back with matching bytes;
- `persistence.noUnmappedAtoms`: every atom the readback returned maps through the manifest.

The seed JVM reports the `ingestion.*` checks of both roots. The trace JVM also reports:

| Check | Pass condition |
| :--- | :--- |
| `evidence.rows` | 7 arms × 32 queries rows |
| `evidence.recallCompleted` | every arm whose port must complete recorded one `COMPLETE` response per query: the adapter arms `MEMORY`, `SWAPPED`, `REMOVED` and `TIGHT_BUDGET` (the tight budget truncates admission, not the port call) and the memory-empty port |
| `evidence.requestMatchesCodec` | every recorded request, in every arm except `THROWING`, is the codec Signals with limit 5 |
| `evidence.recalledEqualsB2` | every row of `MEMORY`, `SWAPPED`, `REMOVED` and `TIGHT_BUDGET` has `recalledEqualsB2 = true`: its recorded response equals a direct `adapter.recall` of the same request on the same root (B2) |
| `evidence.traceIntegrity` | for every run with a memory result (every arm except `THROWING`): exactly one recorded port call; the admitted results are an ordered prefix of the recorded response; the recalled slice of `outputSignals()` equals their Signals in order; every attached `MemoryReferenceEvidence` names an admitted reference |
| `evidence.referencesResolved` | every reference the cycle carried resolves against its own root's resolver, in every arm: the recorded response (including hits beyond the admitted prefix), the admitted results, the attached and the selected evidence; no row fails closed |
| `evidence.stageList`, `evidence.termination` | `MEMORY_RECALL, REASONING, EVALUATION` and `COMPLETED` in every arm except `TIGHT_BUDGET` and `THROWING` |
| `evidence.deterministicReplay` | in every arm, a second fresh cycle with a fresh monad reproduces each run by value: cycle result, snapshot and recorded calls; a failed run (`THROWING`) is compared by its calls, failed stage, completed stages, snapshot and the identity of its cause, since exceptions have no value equality |
| `contrast.valueEquality` | every compared post-recall component is a record, so the contrast never falls back to identity |
| `control.memoryEmptyUnsupported` | every memory-empty run is `UNSUPPORTED` and attaches nothing |
| `control.removedExcludesDocument` | no `REMOVED` recall returns a `doc:links` chunk |
| `control.swappedMatchesSource` | every `SWAPPED` run recorded the same response and admitted the same result as the `MEMORY` run of its `inputQueryId`, so a difference is attributable to the slot, never to recall drift |
| `control.tightBudgetExhausts` | `CONTEXT_BUDGET_EXHAUSTED` with the signal budget flag; at most one admitted result, a prefix of the response (status `PARTIAL` when more were recalled); no `EVALUATION` |
| `control.unavailableRecall` | `UNAVAILABLE`, `RECALL_FAILED`, nothing attached |
| `control.failurePropagates` | the cycle throws `CognitiveCycleException` at `MEMORY_RECALL` with the injected cause, no completed stage and a `FAILURE` snapshot |

A failing check names every violating arm and query.

The orchestrator adds:
- `process.restart`: seed and trace ran in separate JVMs.
- `control.memoryOffNotSupported` (scorer): no memory-empty run is `memorySupportedRelevant`.
- `control.removedRelevantNotSupported` (scorer): no query whose every relevant chunk belongs to `doc:links`
  (`q:exact-term-01`, `q:multi-evidence-02`, `q:paraphrase-01`, `q:paraphrase-04`) is `memorySupportedRelevant` in
  `REMOVED`. If no such query existed, the check would fail as "not exercised".
- `trace.scored`, only on failure: the trace snapshot parsed but is not structurally one complete trace of the query
  set (a missing, duplicate or foreign row; an input text other than the arm prescribes, which is the slot's own
  query, or for `SWAPPED` the query `(i + n/2) mod n` in query-set order; a contrast outside `MEMORY`; or B2 equality
  on the wrong arms). The scorer rejects it, nothing is scored, and the report is still written with `trace: null` and
  exit status `1`.

Every arm's contract (its recorded port status, whether it recalls through the adapter, runs the full cycle, carries
the contrast or swaps its input) is declared once on `EvidenceTrace.Arm`; the trace-phase checks and the scorer both
read it.

**Unscored rows.** Only completed recalls are scored, as in [judged recall](html-knowledge-recall.md#checks). A row
whose recorded status differs from its arm's contract (`COMPLETE`, `UNAVAILABLE` for `UNAVAILABLE`, none for
`THROWING`), for example an operational `FAILED` recall, is **unscored**: every value of that row is `null`, it is
excluded from every aggregate, decision count, population and contrast count, and each arm reports its `unscored`
count. It is never support. The other rows are still scored, and `evidence.recallCompleted` still fails the run.

## Metrics

As defined by `l3-evidence-v1`, per query and arm. Aggregates are macro means with `nDefined` and `nUndefined`.

- `AdmittedFraction`: admitted / recorded results. Undefined when nothing was recorded.
- `UsedFraction`: used / admitted chunks. A chunk is used when it is carried by Signal (`τ` in a later stage's
  output) or attached as evidence. Undefined when nothing was admitted or the trace is ambiguous.
- `RelevantEvidenceUsed`: 1 when a used chunk is in `Rel(q)`. Over `A` only.
- `MemoryEmptyContrast`: `differs`, `differsInSignals`, `differsInArtifacts`, the first differing post-recall stage,
  both output counts and `terminationDiffers`. Reported per query; the aggregate counts `differs` separately for `A`
  and `U`.

Also reported:
- the decision counts, overall and per category;
- `memorySupportedRelevant` over `A`;
- `supportedOnUnanswerable`: `SUPPORTED` decisions on `U`, where no relevant chunk exists (false support).

No latency row is reported. The recall path's latency is the B2 row of [judged recall](html-knowledge-recall.md),
and adapter overhead is the [adapter JMH](resonance-store-adapter-overhead.md).

## Results (2026-10-20)

Neuron `b6e5d61` (clean tree), Store `1445726`, Azul OpenJDK 27 on Linux, AMD Ryzen 7 7730U, Store roots on
`tmpfs`. One seed JVM and one trace JVM per run. All 29
checks passed. Two full runs gave identical `trace` payloads and identical raw rows.

| Arm | AdmittedFraction | UsedFraction | RelevantEvidenceUsed (A) | memory-supported relevant (A) | SUPPORTED on U | SUPPORTED | UNSUPPORTED | RECALL_FAILED | INCOMPLETE |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `MEMORY` | 1.000 (n=30) | 1.000 (n=30) | 0.682 | 15/22 | 8/10 | 30 | 2 | 0 | 0 |
| `MEMORY_EMPTY` | n/a | n/a | 0.000 | 0/22 | 0/10 | 0 | 32 | 0 | 0 |
| `SWAPPED` | 1.000 (n=30) | 1.000 (n=30) | 0.136 | 3/22 | 9/10 | 30 | 2 | 0 | 0 |
| `REMOVED` | 1.000 (n=30) | 1.000 (n=30) | 0.636 | 14/22 | 8/10 | 30 | 2 | 0 | 0 |
| `TIGHT_BUDGET` | 0.200 (n=30) | 0.000 (n=30) | 0.000 | 0/22 | 0/10 | 0 | 0 | 0 | 32 |
| `UNAVAILABLE` | n/a | n/a | 0.000 | 0/22 | 0/10 | 0 | 0 | 32 | 0 |
| `THROWING` | n/a | n/a | 0.000 | 0/22 | 0/10 | 0 | 0 | 0 | 32 |

`FAILED_CLOSED` was 0 in every arm, and no trace was ambiguous. Memory-empty contrast: `differs` for 22/22
answerable and 8/10 unanswerable queries. The two exceptions are the `blank` queries, which recall nothing.

Observations, not tuned away:

- **The cycle carries exactly what L2 recalled.** With the roomy budget, every recalled result is admitted, passed
  through and attached (`AdmittedFraction = UsedFraction = 1`). `RelevantEvidenceUsed` in `MEMORY` (0.682) equals B2
  `Coverage@5` of judged recall. L3 adds no retrieval quality. It shows the evidence reaches the later stages intact
  and traceable.
- **Memory present is not memory used.** Under `TIGHT_BUDGET` one result is admitted (`AdmittedFraction = 0.2`), but
  the cycle stops before `REASONING`, so nothing is used and every decision is `INCOMPLETE`.
- **Support is not correctness.** With `t = 0.0` the Store returns five chunks for every non-blank query. As a result,
  8 of the 10 unanswerable queries are `SUPPORTED`, and so are 7 answerable queries with no relevant chunk carried.
  Only `memorySupportedRelevant` (15/22) uses the labels, and even that is not an answer.
- **Evidence follows the text.** Swapping texts drops `RelevantEvidenceUsed` from 0.682 to 0.136. Each swapped slot
  recalls exactly what its input text recalls in `MEMORY`.
- **Removal reaches the cycle.** Of the four queries whose relevant chunks were all in `doc:links`, only
  `q:multi-evidence-02` had relevant evidence carried in `MEMORY`. In `REMOVED`, none of the four does (15/22 to 14/22).
- **Failures stay distinguishable.** An unavailable Store leaves the cycle running (`RECALL_FAILED`), and a throwing
  port fails it at `MEMORY_RECALL`. Neither is ever counted as an empty recall or as support.

## Limits

- L3 measures carrying, not influence or reasoning. The evidence consumer has one fixed proposition. The memory-empty
  contrast shows the post-recall artifacts differ when memory is present, not that they are better.
- No answer text is produced and no claim is checked against the evidence; that is L4
  ([#109](https://github.com/stevdrey/monada-neuron/issues/109)).
- The query set is in-sample and has no held-out set. The removed document and the swap offset are fixed in
  advance, not chosen from results.
- Determinism is checked by in-process replay with fresh cycles and by repeated runs. Restart and fresh-root
  determinism of the recall itself are covered by [judged recall](html-knowledge-recall.md#checks).

## Refinements of `l3-evidence-v1`

No definition, formula or rule of the extension changed, so its version is unchanged. This page fixes what the
extension leaves to #108:

- the stage subset and the evaluation-only consumer;
- the trace key (the default);
- the memory-stage limit (5) and the budget;
- the resolver and the host decision;
- the controls beyond the mandatory memory-empty contrast;
- the task name `runHtmlKnowledgeEvidenceTrace`.
