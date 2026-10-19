# HTML Knowledge Judged Recall (`html-kq-v1`)

## Question

When `html-kc-v1` is stored in a real embedded Monada Resonance Store, does the Store (L1) return the chunks that
independently curated gold labels call relevant, and nothing when the corpus holds no answer? Does Neuron's production
memory port (L2) recall the same evidence through a documented, non-oracle Signal codec? And how do both compare with
a trivial lexical reference over the same chunks?

This page covers Experiment Knowledge 4/8, [#107](https://github.com/stevdrey/monada-neuron/issues/107). It implements
the protocol's [query set](../experiments/html-knowledge-v1.md#query-set-and-gold-labels-html-kq-v1-delivered-by-107),
[retrieval metrics](../experiments/html-knowledge-v1.md#retrieval-metrics-l1-and-l2), [L2 codec
rule](../experiments/html-knowledge-v1.md#neuron-recall-l2-codec-rule) and baselines B0 to B2c, rerun under the
[restart condition](../experiments/html-knowledge-v1.md#run-conditions).

Recall is not reasoning, and stored text is external memory, not trained model parameters. A relevant chunk in the
top-k proves retrieval only. Evidence use (#108) and answers (#109) are not scored here.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task. CI runs these tests in the
adapter job as part of `:monada-neuron-evaluation:integrationTest`.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeRecall
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeRecall -PbenchmarkArgs="--store-root /tmp/html-kq-recall"
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Recall*'
```

Options, exit statuses and output preparation are the same as for
[persistence](html-knowledge-persistence.md#running). Outputs, under `build/reports/benchmarks/` of
`monada-neuron-evaluation`:

| File | Content |
| :--- | :--- |
| `html-knowledge-recall.json` | the single machine-readable report. It holds `allChecksPassed`, the semantic checks, the required metadata (run identity, versions, hashes, codec, phase JVMs) and the orchestrator environment. The scored payload sits in the same object under `recall`, or is `null` when nothing was scored. That payload has: aggregates per layer and `k` with `nDefined`/`nUndefined`; a `byCategory` object with every metric aggregate, `NoAnswerFalsePositiveRate` and the outcome counts per category; the abstention metrics; one row per query (expected chunk ids, returned ids with rank and score, `tieAtCutoff`, outcome, per-query metrics, miss diagnostics); the B1/B2c codec effect, where a query is `comparable` only when B1 and B2c both completed, and otherwise has `equalChunkOrder: null` and its `failedLayers`; and the follow-up candidates |
| `html-knowledge-recall.md` | the same, readable: checks and metadata, then the scored summary (aggregates, quality and outcomes by category, codec effect, per-query table, follow-ups) |
| `html-kq-v1-recall.tsv` | raw rows of the seed phase (query, layer, limit, rank, chunk id, hexadecimal score), including the limit-6 lookahead and any `<failed>` markers. Its first line states `corpusHash` and `allChecksPassed`: it is not a verified result on its own |

## Query set `html-kq-v1`

`monada-neuron-evaluation/src/integration/resources/html-kq-v1/queries.tsv`: 32 queries, curated from the corpus text
and frozen before any system output was inspected (`queryHash
e91b04045664c48695f34e16bc74e63879ddbe8e11ca4613914538cff9e9060f`).

**Errata, before merge.** The first frozen edition (`97b29b68…`) left `q:false-premise-03` without the keyClaim that
refutes its premise. The protocol requires that claim for every `false-premise` query, and the loader now enforces it.
The approved claim "No HTML attribute makes a page load twice as fast in every browser." was added under the same
version, because nothing had been merged. The correction fixes a contract violation found in review; it was not
prompted by any system output. keyClaims are scored at L4 only, so no L1/L2 result changed.

Lists are `|`-separated, `-` is empty, a span is `chunkId start end sha256` in code points of the normalized
chunk text, and `text` is never trimmed (escapes `\\`, `\t`, `\n`).

| Category | Queries | Relevant set |
| :--- | ---: | :--- |
| `exact-term` | 5 | one chunk |
| `paraphrase` | 4 | one chunk; `avoidedTerms` recorded and checked against the text |
| `multi-evidence` | 3 | two required chunks (one also has a supporting chunk) |
| `code-example` | 3 | the fact is only in a fenced example |
| `distractor` | 3 | one chunk; another chunk shares the query vocabulary |
| `no-answer` | 4 | empty |
| `out-of-domain` | 3 | empty |
| `false-premise` | 3 | the refuting chunk for two; empty for one |
| `conflicting-source` | 2 | both chunks of `conflict:img-alt` |
| `blank` | 2 | empty (`""` and three spaces) |

10 of 32 queries (31 %) have an empty relevant set. `HtmlKnowledgeQuerySet` checks every load-time invariant of the
protocol against the corpus, including span digests, the conflict pair, the paraphrase term check and the codec's
encodability. Any violation fails the run with exit `2`. `keyClaims` are curated now and scored only at L4 (#109).

## Systems

| Id | System | Limits |
| :--- | :--- | :--- |
| B0 | `LexicalOverlapBaseline`: distinct query tokens contained in each chunk; score 0 dropped; score descending, then `chunkId` | 1, 3, 5, 6 |
| B1 | `MonadaMemory.resonate(text).topK(limit).threshold(0.0)` with default options | 1, 3, 5, 6 |
| B2 | `ResonanceStoreMemoryAdapter.recall` with `TokenPackingSignalCodec` as query encoder, the production `HashedSignalDecoder`, default options and `t = 0.0` | 1, 3, 5, 6 |
| B2c | the codec's query texts sent directly to the Store with the same limit and `t`, merged by the adapter's rule | 1, 3, 5, 6 |

Tokens are maximal `Character.isLetterOrDigit` runs lowercased with `Locale.ROOT` (`HtmlTokens`), shared by B0, the
paraphrase check and the codec. `t = 0.0` is the Store's `MonadaQuery` default; nothing was tuned on this set, and no
threshold sweep was run.

Every system is **scored only on its limit-5 list**: `Ret_k` is a prefix of that list, as the protocol requires. The
limit-6 **lookahead** exists only to set `tieAtCutoff` at k = 5, that is, whether ranks 5 and 6 share a score. Without
it, a tie that straddles the k = 5 cutoff could never be seen. Limits 1 and 3 serve the prefix-stability check,
which the protocol applies to every system.

### L2 codec `token-packing v1`

The protocol forbids lookup codecs and codecs that read query ids or labels for L2 quality. A `Signal` carries only a
kind and three doubles, so `TokenPackingSignalCodec` is stateless and invertible. Each distinct token, in order of
first occurrence, becomes one `OBSERVATION(1.0, f, p)` Signal. Characters 1 to 10 of the token are packed into `f`
and characters 11 to 20 into `p`, each as `Σ (digit + 1) · 37^i` over `[0-9a-z]`, which is exact in a `double`.
`encode(signal)` unpacks the token again.

- A text with no token becomes one Signal that encodes the empty query, for which the Store returns nothing. Blank
  queries therefore still go through the real port.
- Limits:
  - tokens must be in `[0-9a-z]`;
  - a token may be at most 20 characters long;
  - a query may have at most 32 distinct tokens;
  - a Signal the codec cannot have produced is rejected, and the adapter propagates that exception as an
    operational failure.
- The decoder is the production `HashedSignalDecoder`, which does not know the corpus. Results map to chunks only
  through the opaque reference and the import manifest.

Because the adapter issues one Store query per Signal, B2 sends one query **per token**, whereas B1 sends the whole
text. That difference is the codec effect, and B2c isolates it.

## Checks

The phase JVMs never load the query set. The orchestrator writes a labels-free workload to the work directory: the
`queryHash`, a header and `queryId<TAB>text` rows. The phases read only that file (`HtmlKnowledgeRecall.QueryText`),
so no gold label is ever present in a phase JVM. The orchestrator's scorer (`RecallMetrics`) is the only reader of the
labels. A test checks that the recall-side classes reference no label type and that the workload leaks no gold chunk
id or claim. Each phase reports:

| Check | Pass condition |
| :--- | :--- |
| `recall.completed` | every B1/B2c Store call returned with finite scores and every B2 recall answered `COMPLETE`, at every limit. A non-finite Store score fails the layer, as the production adapter does. |
| `fidelity.source` | every B1 and B2c atom maps through the manifest, and its returned content hashes to the corpus `sha256`, as returned. |
| `fidelity.reference` | every B2 reference maps through the manifest. |
| `neuron.b2EqualsB2c` | all `4 × queries` comparisons were made (limits 1, 3, 5 and 6), and in each B2 equals the codec-direct control: same atoms, same order, bit-exact scores. A difference would be an adapter defect. A comparison skipped because a call failed is a failure, never a silent pass. |
| `recall.prefixStability` | all `12 × queries` comparisons were made: for every system, the limit-1 and limit-3 lists are prefixes of the limit-5 list, and the limit-5 list is the prefix of the lookahead. The only allowed exception is an equal-score substitution inside a tie that straddles the cutoff: the longer list must hold a result beyond the cutoff with the cutoff's score. A skipped comparison is a failure. |

Any failure of a query in a layer, at any limit, leaves exactly one `<failed>` marker for that query and layer. This
covers an exception, a non-`COMPLETE` status, an unmapped atom or reference, and a content mismatch. The scorer then
gives the query no outcome in that layer: it is excluded from every aggregate and counted as unscored, never as an
abstention.

The files the orchestrator and the phase JVMs exchange are checked for completeness:
- the phase result, the recall snapshot and the query workload end with a line that states their entry count;
- the manifest must map every corpus chunk in order.

An empty or truncated file is therefore rejected, and a phase that left one counts as not completed. It is never read
as a result with fewer checks, rows or queries.

The orchestrator adds three checks:
- `process.restart`: seed and reopen ran in separate JVMs, as for [persistence](html-knowledge-persistence.md).
- `determinism.restartEqual`: every row of B0, B1, B2 and B2c at every limit is identical in the reopen JVM, which
  reads the seed's root.
- `determinism.freshRootEqual`: a third JVM, `replica`, imports the corpus into its own fresh root and runs the same
  workload; every row must equal the seed's. Rows hold chunk ids mapped through each root's own manifest, so
  Store-owned atom ids are never compared across roots. The replica's own checks are reported as `replica.*`.

Restart equality alone could not catch nondeterminism that is fixed at import time and then persisted. Fresh-root
equality does.

The seed and replica phases also carry the three `ingestion.*` checks of #105, which judge the import under test.

**Semantic boundary.** Only what the protocol requires is a check:
- the measured recalls of B0, B1, B2 and B2c at limits 1, 3, 5 and 6, with their fidelity and B2 = B2c;
- prefix stability;
- the ingestion of the root under test;
- one readback in the restarted process;
- restart and fresh-root determinism.

Every other call exists only for latency and is a diagnostic, never a check, because latency must not affect the
verdict or the exit status. These calls are:
- the cold first-recall samples;
- the unmeasured warm-up pass;
- reopen readback repetitions 2 to 5;
- the 4 scratch imports.

A latency-only call that fails, or returns unacceptable output, is excluded from its row. The reason is recorded in
`<row>.excludedSamples` (`perf.warmup.failures` for the warm-up pass); the value is `0` when nothing was excluded.
No failed or incomplete call is ever published as a latency sample, and a row's `measurementIterations` counts only
the successful samples. Scores are compared bit-exactly everywhere (`sameScore`, so `+0.0` and `-0.0` differ), in
B2 = B2c, in prefix stability and in tie detection. This covers the measured warm rows too: a measured call that throws or
is not `COMPLETE` is excluded. The report writes every number through one helper that turns a non-finite value into
`null`, so the JSON stays valid even for a failed run. Before it runs any query, the reopen phase repeats
the protocol's full-corpus readback on the existing root:
- `reopen.persistence.readback`: every manifest chunk, queried with its own text, returns its atom with content
  hashing to the corpus `sha256`;
- `reopen.persistence.noUnmappedAtoms`.

A chunk lost or corrupted on disk therefore fails the run even when no judged query would return it.

The replica root and the scratch roots of the timed imports are fresh temporary directories created **beside the root
under test**, in its parent directory, so every latency row is measured on the same storage. They are deleted with the
run; with `--store-root`, nothing but the kept root remains in its parent.

The replica root's storage is recorded as `env.replicaRoot.{fileSystem,storageType}`, next to the seed root's.
`env.replicaRoot.sameFileStoreAsStoreRoot` and `<phase>.env.timingScratch.sameFileStoreAsStoreRoot` confirm that the
roots share a file store. If the scratch directory were ever on another file store, its imports would be skipped and
only the import under test sampled.

The metadata records the protocol's required fields. Besides versions, hashes, the codec, the commits and the Store
manifest, these are:
- run identity: `runMode`, and best-effort environment fields `env.cpuModel`, `env.totalPhysicalMemoryBytes`, `env.hostId` (salted, truncated SHA-256 of the host name; the name itself is never written) and the Store root's `env.storeRoot.{fileSystem,storageType}`. A value that cannot be read is `unknown (<reason>)` and never fails a run.

## Results (2026-10-19, final harness)

Every figure in this section comes from one harness revision, measured on a clean tree.

Environment:
- Neuron `a7d612b`, the final harness, with no uncommitted changes (`neuron.commit` has no `+dirty`);
- Store checkout `c3e98d1`;
- Azul OpenJDK 27 on Linux, AMD Ryzen 7 7730U;
- seed root, replica root and timing scratch roots all on `tmpfs` (memory-backed), on the same file store
  (`env.replicaRoot.sameFileStoreAsStoreRoot = true`, `<phase>.env.timingScratch.sameFileStoreAsStoreRoot = true`);
- one seed, one reopen and one replica JVM, each 1.1 to 1.2 s from process start to exit (`process.<p>.wallNanos`).
  The run configuration lists the phase JVMs actually launched ("phase JVMs launched: seed, reopen, replica"). A run
  that skips a phase names it as not launched.

Two full runs gave byte-identical `recall` payloads.
- All 26 checks passed. They include `determinism.freshRootEqual`: the replica reproduced all 1800 seed rows.
- No latency sample was excluded (`excludedSamples = 0`), and nothing was left behind (`cleanup.leftover = none`).
- The persistence and ingestion runners also pass on the same commit.
- Scores are unchanged from the first edition, across every harness revision and Store checkout since; scoring still
  reads the limit-5 lists.

| Layer | Recall@1 | Recall@5 | MRR@5 | Coverage@5 | FullEvidence@5 | NoAnswerFPR@5 | AbstentionRecall |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B0 | 0.470 | 0.818 | 0.682 | 0.818 | 1.000 | 0.800 | 0.200 |
| B1 | 0.500 | 0.795 | 0.638 | 0.818 | 1.000 | 0.800 | 0.200 |
| B2 | 0.152 | 0.598 | 0.351 | 0.682 | 0.333 | 0.800 | 0.200 |

Means are over the 22 answerable queries. FullEvidence is over the 3 `multi-evidence` queries, and the abstention
values are over the 10 queries with an empty relevant set.

| Category (k = 5) | B0 Recall / MRR | B1 Recall / MRR | B2 Recall / MRR |
| :--- | :--- | :--- | :--- |
| `exact-term` | 0.800 / 0.600 | 0.800 / 0.667 | 0.600 / 0.120 |
| `paraphrase` | 0.250 / 0.250 | 0.250 / 0.250 | 0.000 / 0.000 |
| `multi-evidence` | 1.000 / 0.667 | 1.000 / 0.833 | 0.722 / 0.611 |
| `code-example` | 1.000 / 0.667 | 1.000 / 0.778 | 1.000 / 0.667 |
| `distractor` | 1.000 / 1.000 | 1.000 / 0.778 | 0.667 / 0.400 |
| `false-premise` (refuted) | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 0.750 |
| `conflicting-source` | 1.000 / 1.000 | 0.750 / 0.267 | 0.500 / 0.292 |

`NoAnswerFalsePositiveRate@5` is `1.000` for `no-answer`, `out-of-domain` and the unrefuted `false-premise` query in
every layer, and `0.000` for `blank`.

Observations, not tuned away:

- **No-answer behavior.** With `t = 0.0` every layer returns five chunks for every non-blank query. Every `no-answer`,
  `out-of-domain` and unrefuted `false-premise` query is therefore `FALSE_EVIDENCE` in every layer, and only the two
  `blank` queries abstain. Detecting "no answer" needs a threshold or a separate decision. Any threshold chosen by
  looking at this set would be `in-sample (tuned)`.
- **B1 compared with B0.** The Store's lexical/frequency encoder is close to the trivial overlap reference: slightly
  better at k = 1 (0.500 against 0.470) and slightly worse at k = 5 (0.795 against 0.818).
  - It misses `q:exact-term-02` ("fieldset"), which B0 ranks first.
  - It finds `q:exact-term-01` (the `a` element) at rank 3, where B0 misses it.
- **Paraphrases.** Three of the four paraphrases are missed by both B0 and B1. As expected, a lexical encoder does
  not bridge the avoided terms.
- **Codec effect.** Token-by-token recall (B2) is much weaker than whole-text recall:
  - B1 and B2c differ in top-5 order for 30 of 32 comparable queries (both layers completed for every query);
  - B2 misses every paraphrase;
  - B2 retrieves only one side of both conflicting-source queries.

  This measures the codec, not the adapter: B2 = B2c held for every query at every limit.
- **Ties.** The lookahead flags a tie straddling the k = 5 cutoff for 23 queries in B0, whose integer overlap scores
  tie widely, and for none in B1, B2 or B2c.
- **Latency.** Every latency row reports median and p95 over its samples, and records its sampling:
  `warmupIterations`, `measurementIterations`, `operationsPerIteration`, `measurementMethod`, `validationInWindow`
  and `gcTelemetryScope`. No GC or allocation telemetry is collected, and no row is ever part of a verdict.

  The rows:
  - `perf.b1.recallLimit5` and `perf.b2.recallLimit5` take one sample per query (32), after one unmeasured pass of
    every query (`perf.warmupQueries`), on the same handles. Only the Store call (B1) or the adapter recall (B2) is
    timed.
  - `perf.open` and the first-recall rows open 5 fresh handle pairs used only for timing. Each pair's first B1 and B2
    call is timed before any other call on it, for the first query that has a token, so blank queries cannot warm
    the handles. The JVM has already run the import or reopen readback on other handles. The observation then opens
    a pair of its own, untimed.
  - Every latency-only step catches its own exceptions: a timed open, a cold or warm-up call, a readback repetition
    or a scratch import. A failure excludes that sample and is recorded in `excludedSamples`. Only the
    protocol-required steps can fail the run: the observation's handles, the measured recalls, readback 1 and the
    import under test.
  - `perf.ingest` and `perf.ingestReadback` take the import under test plus 4 further imports into scratch roots
    beside it, deleted afterwards.
  - `perf.reopenReadback` repeats the full-corpus readback 5 times. Repetition 1 is the required readback, and its
    checks are reported. A failure in repetitions 2 to 5 only excludes that sample.

  | Row (seed; reopen for the readback) | Samples | Timed operation | Median | p95 |
  | :--- | ---: | :--- | ---: | ---: |
  | `perf.b1.recallLimit5` | 32 | warm Store call, limit 5 | 0.22 ms | 0.33 ms |
  | `perf.b2.recallLimit5` | 32 | warm adapter recall, limit 5 | 1.55 ms | 3.04 ms |
  | `perf.b1.firstRecall` | 5 | first Store call on fresh handles | 2.26 ms | 3.51 ms |
  | `perf.b2.firstRecall` | 5 | first adapter recall on fresh handles | 4.02 ms | 13.06 ms |
  | `perf.open` | 5 | `MonadaMemory.open` plus the adapter's own open | 3.51 ms | 21.84 ms |
  | `perf.ingest` | 5 | Store open plus 33 `remember` calls | 21.53 ms | 77.15 ms |
  | `perf.ingestReadback` | 5 | 33 `resonate` calls with hash checks | 15.74 ms | 41.77 ms |
  | `perf.reopenReadback` (reopen) | 5 | Store open plus 33 `resonate` calls with hash checks | 23.48 ms | 97.44 ms |

  The warm rows of the reopen and replica JVMs are in the same range: warm B1 has medians of 0.23 and 0.22 ms, and
  warm B2 1.70 and 1.64 ms. These rows are single-machine and exploratory. They vary with machine load between
  runs; one earlier run on a busy machine measured `perf.ingest` at about 45 ms. They are comparable across runs only
  when their environment and sampling fields match. With 5 samples, a p95 is the slowest sample, so it mostly
  shows the first, colder repetition. B2 is slower than B1 because it makes one Store query per token. The
  whole-JVM time is process bookkeeping (`process.<p>.wallNanos`), not a latency row.

Follow-up candidates (listed in the report and never relabeled):
- B1 misses `q:exact-term-02` and `q:paraphrase-01/03/04`, and returns `q:conflicting-source-01` only `PARTIAL`.
- B2 misses `q:exact-term-01/02`, `q:distractor-03` and all four paraphrases. It returns `q:multi-evidence-01/03` and
  both conflicting-source queries only `PARTIAL`.
- Every non-blank empty-gold query (8 of the 10) is `FALSE_EVIDENCE` in B1 and B2. The two blank queries are correct
  abstentions.

## Limits

- The query set is **in-sample**: it has no held-out counterpart, so these numbers describe this synthetic set only.
- The codec is one documented, text-only design. A semantic codec could close part of the B1/B2 gap; that is future
  work and needs a new codec version.
- Results within one root are compared by atom. Across roots, they are compared by mapped chunk id, order and score.
- Store and Forge code are unchanged, and the core library is unchanged.
