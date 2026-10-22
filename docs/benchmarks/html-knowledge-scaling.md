# HTML Knowledge Scaling Profile (`scaling-v1`)

## Question

When the HTML knowledge pipeline grows from 25 to 500 stored chunks, **where does the cost go**, and does each
layer's **semantic quality and abstention stay correctly counted** at every size? The pipeline runs from corpus
generation through Store ingestion, a freshly opened handle, warm recall, the Neuron port and cycle, to cited answers.

This page covers Experiment Knowledge 8/8, [#111](https://github.com/stevdrey/monada-neuron/issues/111). It implements
the protocol's [scaling family](../experiments/html-knowledge-v1.md#scaling-family-delivered-by-111) extension
`scaling-v1` over the corpus, query set, codec, threshold and adapter configuration of
[judged recall](html-knowledge-recall.md), the [evidence trace](html-knowledge-evidence-trace.md) and
[grounded answers](html-knowledge-answers.md).

It is a profile, not a gate:

- Latency and allocation never decide the verdict, and no latency target is introduced.
- A regression or a trend prompts a separately scoped follow-up issue, which a person writes by hand from the
  [triage template](#follow-up-triage-template). Nothing is filed automatically.
- It does not duplicate the adapter microbenchmarks of [#49](resonance-store-adapter-overhead.md) or the
  [#31 integration evaluation](resonance-store-integration.md). Both are unchanged and can be rerun for comparison.
- Stored text is external memory. Nothing here trains model weights, and retrieval at scale is not evidence of
  understanding.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeScaling
```

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeScaling -PbenchmarkArgs="--quick"
```

```bash
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Scaling*'
```

Options:

| Option | Meaning |
| :--- | :--- |
| `--quick` | 1 warm-up and 3 measured iterations per warm row, 2 samples per setup, ingestion, reopen and first-recall row (full: 3 + 10 and 5, plus one ingestion warm-up). Every size point still runs |
| `--sizes 25,100,500` | an ascending subset of the protocol's points; the curve population is still fixed by all three points |
| `--seed <n>` | generator seed, default `111` |
| `--output-dir <dir>` | default `build/reports/benchmarks` |
| `--store-root <dir>` | keep the point roots (`scale-<N>/root`) there instead of a deleted temporary directory; it must be absent or empty |

Exit status: `0` all checks passed, `1` a semantic check failed, `2` invalid usage or rejected input. A full run
takes about a minute on the machine below; `--quick` about 20 s.

Outputs, under `build/reports/benchmarks/` of `monada-neuron-evaluation`:

| File | Content |
| :--- | :--- |
| `html-knowledge-scaling.json` | the single machine-readable report. It holds `allChecksPassed`, the checks and the provenance metadata. The scored payload `scaling` holds per point the corpus identity, removed queries, footprint, quality per layer, `k` and category, B3 counts, one `queries` row per eligible query (labels, returned chunks with ranks and scores, outcome and `tieAtCutoff` per `k`, miss diagnostics, and the L3 trace of its verified cycle at each `k`: admitted, attached and selected chunks, decision and termination), one `answerRows` entry per query, layer and `answerK` (evidence, status, claims with checked citations, the five flags and every judgment artifact, `null` where no human verdict exists), normalized rows, `unavailable` and `invalidated` rows and wrapper overhead. `evaluation.results` holds every measured row with its full latency distribution, allocation, GC and RSS telemetry and sampling diagnostics |
| `html-knowledge-scaling.md` | the same, readable, ending with the triage template |

## How a run works

### The scaling family

Each size point is its own frozen corpus `html-kc-v1-scale-<N>` with its own `corpusHash`. It holds the first
`min(N, B)` chunks of `html-kc-v1` in `chunkId` order, with `B = 33`, plus `N − min(N, B)` generated distractors:

| N | base + generated | queries eligible | removed at this point |
| ---: | :--- | ---: | :--- |
| 25 | 25 + 0 | 27 | `q:code-example-02`, `q:exact-term-03`, `q:exact-term-04`, `q:exact-term-05`, `q:false-premise-01` |
| 100 | 33 + 67 | 32 | none |
| 500 | 33 + 467 | 32 | none |

At N = 25, the conflicting-source pair is present. The `lists` chunks after `#01` and all `scripts-and-embedding`
chunks are absent, so the five queries that need them are removed before any check or score. The **curve population**
is the 27 queries eligible at every protocol point. It is derived from all three points even when `--sizes` measures
a subset, so a point scores the same curve queries in every run. Every curve below uses it, so only the corpus size
changes along the curve.
The per-point population is reported separately and is not comparable across points.

**Generator `html-kd-v1`:**

- **Templates.** Eight authored templates on web topics that no `html-kq-v1` query asks about: CSS units, colors,
  selectors and layout; HTTP caching and status codes; SVG paths; character encoding. They share generic vocabulary
  (element, attribute, browser, page), so they compete in a lexical ranking. They state no fact about the HTML the
  queries cover, and they avoid every non-answerable query's topic.
- **Chunks.** Each distractor is its own single-chunk document `doc:synthetic-<nnnn>`, with source
  `generated:html-kd-v1:<seed>` and license `Apache-2.0`. It goes through the same normalization, hashing, chunking and
  bounds as authored text.
- **Sampling.** Three sentences of one topic are drawn from one `SplittableRandom` stream, so a smaller point's
  distractors are a prefix of a larger point's.
- **Bounds.** `CorpusBounds.SCALING` allows 25 to 500 chunks, at most 512 documents, and the authored per-chunk and
  per-document limits.
- **Review.** `ScalingDistractorGeneratorTest` checks that no template contains a key claim or names a protected
  topic. Changing a template bumps the generator version.

### Phases and rows

Each point gets a fresh root on the same storage. The root under test is imported once. Every phase below is a
separate row, timed with `EvaluationMetricsCollector`: `System.nanoTime` and thread-allocated bytes around the
workload, and `LatencyDistribution` percentiles with linear interpolation. These p95 values are not comparable to the
nearest-rank `LatencyRow` values of #106 to #110.

| Row | Timed window | Operation |
| :--- | :--- | :--- |
| `setup.corpus` | generate distractors, build, validate and hash the point corpus | one corpus |
| `ingest.remember` | `MonadaMemory.open` on an empty scratch root plus one `remember` per chunk; the fresh root is untimed setup, and every sample's root is read back in full after the window | one chunk |
| `open.b1` | reopening the root as a fresh `MonadaMemory`, in an already warm JVM (the protocol's "cold"); the handle's recall is checked after the window | one sample |
| `open.b2` | reopening the root as a fresh `ResonanceStoreMemoryAdapter`; its recall is checked after the window | one sample |
| `firstRecall.b1` | the first `resonate` on a handle opened in untimed per-iteration setup | one sample |
| `firstRecall.b2` | the first `recall` on an adapter opened in untimed per-iteration setup | one sample |
| `warm.b1` | `resonate(text).topK(k).threshold(0.0)` on one warm handle | one query |
| `warm.b2c` | the Store-side work of one B2 recall: one `resonate` per codec Signal plus the adapter's documented merge | one query |
| `warm.b2` | `ResonanceStoreMemoryAdapter.recall` on one warm adapter; requests built before the window | one query |
| `warm.cycle` | one fresh `MEMORY_RECALL → REASONING → EVALUATION` cycle over the warm adapter, memory and reasoning limit `k` | one query |
| `warm.qa.b3` | one B3 answer over verified B1 evidence `E(q)` at `answerK = k`; recall excluded | one query |
| `warm.qa.b1+b3` | verified B1 evidence recall (Store call, manifest mapping, one SHA-256 per chunk) plus the B3 answer; the whole recalled list must equal the verified evidence, not only the answer | one query |

Warm rows run at `k = 1, 3, 5` over the fixed curve cohort, the same 27 queries at every point, so cross-size cost
compares one workload. Quality, L3 traces and B3 counts also cover each point's own eligible queries, labelled as
the point population.

Two rows are reported as `unavailable`, never as zero, because nothing measures them:

- **Process-cold opens.** Restart persistence is the evidence of #106 and #107, so no row opens a Store in a fresh JVM.
- **Retained heap footprint.** The harness has no probe for it.

**Wrapper overhead.** The Neuron wrapper overhead is `warm.b2 − warm.b2c` per query: the adapter with the Store calls
it must make removed. It is never computed as `B2 − B1`, which would charge Neuron for the Store's per-token fan-out.

## Checks

All checks are semantic and are judged outside every timed window. Each name is prefixed `n<N>.`:

| Check | Meaning |
| :--- | :--- |
| `family.corpora` | every point corpus validated, and every `corpusHash` is distinct from the others and from the base |
| `ingestion.*` | manifest complete, content fidelity, and readback with `topK(N)` (#105) |
| `store.freshHandleReadback` | a freshly opened handle reads every chunk back and returns exactly the manifest's atoms. This is fresh-handle consistency, not persistence evidence |
| `recall.*`, `fidelity.*`, `neuron.b2EqualsB2c` | the full #107 observation of the point's eligible queries: B0, B1, B2 and B2c with fidelity, prefix stability and B2 = B2c |
| `cycle.traceIntegrity` | every verified cycle of every eligible query at every `k` passes #108's real-port checks: it replays identically, runs `MEMORY_RECALL → REASONING → EVALUATION`, terminates `COMPLETED` with one port call whose request equals the codec Signals at limit `k` and whose recall answers `COMPLETE` with resolvable references, never fails closed, admits a prefix of the recorded response, attaches only admitted references and admits exactly the verified B2 rows |
| `answer.citationsEligible` | every B3 citation over B1 and B2 evidence at every `answerK` lies within `E(q)` and in bounds, and every evidence recall completed |
| `perf.matchesVerified` | after the window, every timed sample, warm-ups included, equals the verified result: mapped chunks, order and bit-exact scores, equal cycle runs, equal answers. A row is also withheld before timing when its verified reference is itself invalid: a failed ingestion check of the root under test withholds `ingest.remember`, and a failed ingestion, readback or recall check (for example B2 = B2c) withholds every recall-based row; a trace-integrity violation at `k` withholds `warm.cycle` at `k`; a failed evidence recall or an ineligible citation withholds the answer rows. A mismatch, a throw or an invalid reference fails the run and the row is listed as `invalidated` (or `unavailable` after a throw), never published, so **no published row times a degraded path** |

`HtmlKnowledgeScalingRunnerTest` injects an empty port into the timed windows only. It requires exactly
`perf.matchesVerified` to fail and the affected B2, cycle and B2 first-recall rows to be withheld as invalidated. A second
test gives the verified cycles an empty port and requires `cycle.traceIntegrity` to fail and `warm.cycle` to be
withheld.

## Metrics

- **Quality.** The [#107 metrics](html-knowledge-recall.md#metrics) for B1 and B2 at each `k`, over the curve
  population and the point population: Recall, MRR, `NoAnswerFalsePositiveRate`, outcomes and abstention, overall and
  per query category.
- **B3 answers.** Mechanical counts at each `answerK`: answered or abstained, split by a non-empty relevant set;
  answers citing a relevant chunk; and ineligible citations. Each answer row carries its claims and their checked
  citations. The human verdicts of `html-l4-judgments-v1` are keyed to `html-kc-v1` answers, so each row's
  `supported`, `contradicted`, flags, key-claim edges, matchings and reviewer fields are `null` with `verdictSource`
  stating why, except the protocol's deterministic rules: an eligible exact quote is `supported: true` (every B3
  claim is one), a claim without an eligible citation is `supported: false`, `anyUnsupportedClaim` follows from those,
  and `conflictDisclosed` is `false` when the eligible citations do not cover both chunks of the conflicting pair,
  which includes every abstention on a conflicting-source query.
  Each row's `outcome` is the component's shape (`ANSWERED`, `ABSTAINED`, `RECALL_FAILED`) and `status` the protocol's
  primary status: abstentions are classified mechanically (`CORRECT_ABSTENTION`, `EVIDENCE_MISS_ABSTENTION`,
  `WRONG_ABSTENTION`), and answered rows are `null` because their first conditions (`premiseAccepted`,
  `anyContradictedClaim`) need a human verdict.
- **Text policy.** Generated chunks come from authored Apache-2.0 templates, so every point corpus is publishable and
  claim text is written; a redacted corpus would carry each claim's SHA-256 instead.
- **Resources.** Per row: the median and p95 of whole iterations, and those divided by the operations an iteration
  batches (an amortized cost per query or chunk, not a percentile of single operations), throughput, allocated bytes per operation, and GC and RSS
  deltas. Per point: persisted atoms (distinct atoms of the fresh-handle readback), root bytes and files, and Store
  calls per B2 recall.

## Results (2026-10-22)

**Environment.** Full run on JDK 27 (G1, max heap 3.8 GB), AMD Ryzen 7 7730U, Neuron `8ca0013+dirty` (this
change, uncommitted), Store checkout `3dd6012`. The Store roots were on `tmpfs` (memory-backed), so I/O costs are a
lower bound. Every timed warm row ran the 27 curve queries at every point.

**Dataset hashes.**

- Base `corpusHash 1934ef6f…`, `queryHash e91b0404…`.
- Point hashes: `526d273a…` (N = 25), `2bc130bf…` (N = 100), `b35200e2…` (N = 500).

`allChecksPassed: true` (37 checks).

### Quality over the curve population (27 queries: 17 answerable, 10 non-answerable)

| Layer | Metric | N = 25 | N = 100 | N = 500 |
| :--- | :--- | ---: | ---: | ---: |
| B1 | Recall@1 / @3 / @5 | 0.412 / 0.657 / 0.735 | 0.412 / 0.578 / 0.706 | 0.412 / 0.578 / 0.627 |
| B1 | HIT@5 | 12 / 17 | 12 / 17 | 10 / 17 |
| B2 | Recall@1 / @3 / @5 | 0.225 / 0.343 / 0.539 | 0.176 / 0.255 / 0.284 | 0.176 / 0.176 / 0.235 |
| B2 | HIT@5 | 7 / 17 | 4 / 17 | 3 / 17 |
| B1, B2 | NoAnswerFalsePositiveRate@k | 0.800 | 0.800 | 0.800 |

The no-answer false-positive rate is the expected effect of threshold `0.0`: every query with a token returns
results, and only the two blank queries abstain. It does not change with N.

### B3 answers (point population)

- **Over B1 evidence.** B3 answers every answerable query at every N and abstains on the two blank queries. The
  answers that cite a relevant chunk are 8 of 17 (N = 25), 12 of 22 (N = 100) and 10 of 22 (N = 500).
- **Over B2 evidence.** The same counts are 5, 3 and 3, and B3 abstains on 2 answerable queries at N ≥ 100.
- **Citations.** No citation is ineligible at any point.
- **`answerK`.** The counts are identical across `answerK` because B3 reads only the first evidence chunk; this is
  documented behaviour of [B3](html-knowledge-answers.md), not a defect.

### Footprint

| N | persisted atoms | root bytes | root files | Store calls per B2 recall |
| ---: | ---: | ---: | ---: | ---: |
| 25 | 25 | 27,916 | 5 | 10.63 |
| 100 | 100 | 101,954 | 5 | 10.63 |
| 500 | 500 | 491,135 | 5 | 10.63 |

The root grows by about 1 KB per chunk, and the file count stays constant. Store calls per B2 recall are averaged
over the curve queries, so they are equal at every point.

### Cost (exploratory; amortized median per operation at k = 5, allocation per operation)

A warm-row iteration times all 27 curve queries and an ingestion iteration all chunks, so each value is the
iteration median divided by the operations: an amortized cost. A single slow query is diluted, not visible.


| Row | N = 25 | N = 100 | N = 500 |
| :--- | ---: | ---: | ---: |
| `setup.corpus` | 0.94 ms | 3.8 ms | 15 ms |
| `ingest.remember` (per chunk) | 0.67 ms, 43 KiB | 0.36 ms, 34 KiB | 0.20 ms, 30 KiB |
| `open.b1` | 1.3 ms, 163 KiB | 0.69 ms, 185 KiB | 0.46 ms, 307 KiB |
| `open.b2` | 0.75 ms, 60 KiB | 0.37 ms, 83 KiB | 0.30 ms, 205 KiB |
| `firstRecall.b1` | 0.61 ms, 258 KiB | 1.1 ms, 793 KiB | 1.6 ms, 3.4 MiB |
| `firstRecall.b2` | 1.7 ms, 918 KiB | 2.6 ms, 2.3 MiB | 7.8 ms, 9.7 MiB |
| `warm.b1` (per query) | 0.11 ms, 102 KiB | 0.21 ms, 240 KiB | 0.90 ms, 976 KiB |
| `warm.b2c` (per query) | 0.97 ms, 905 KiB | 4.0 ms, 2.1 MiB | 8.4 ms, 8.7 MiB |
| `warm.b2` (per query) | 0.96 ms, 911 KiB | 3.2 ms, 2.1 MiB | 8.7 ms, 8.7 MiB |
| `warm.cycle` (per query) | 1.1 ms, 923 KiB | 2.9 ms, 2.1 MiB | 8.7 ms, 8.7 MiB |
| `warm.qa.b3` (per query) | 17 µs, 9.8 KiB | 10 µs, 9.4 KiB | 6 µs, 9.3 KiB |
| `warm.qa.b1+b3` (per query) | 0.14 ms, 116 KiB | 0.34 ms, 254 KiB | 0.91 ms, 989 KiB |

**Reopen versus first recall.** Opening a handle is cheap and does not grow with N: 0.3 to 1.3 ms, falling as the
JVM warms. The cost that grows with N is the first call on the fresh handle, in both latency and allocation.

**Neuron wrapper overhead.** `warm.b2 − warm.b2c` is within run-to-run noise at every point. It takes both signs,
from −0.81 ms to +2.09 ms per query; the large magnitudes all come from N = 100, the noisiest point of this run,
and at N = 25 and N = 500 the difference stays within about 0.3 ms of a B2 recall that makes about ten Store calls. This harness
cannot resolve the adapter's own cost next to those calls. That cost is measured in isolation by the
[#49 JMH benchmarks](resonance-store-adapter-overhead.md).

**Ordering effects.** The first point and the first rows of a point carry JIT warm-up. Examples are the N = 25
ingestion at 0.67 ms per chunk against 0.20 ms at N = 500, and the opens, which get faster as the run proceeds. One
intermediate full run of this change, taken while the machine was busy, had several N = 25 rows three to five times
slower than this one, with the same allocation. Read latency trends only across the warm rows, and prefer allocation.

**Allocation.** Allocation per operation is far more stable than latency: it repeats to within 1% across `k` and
between the quick and full runs.

## Findings

Each finding states what was observed and the narrowest next step. None is a speculative fix, and none was filed
automatically.

1. **Neuron codec/port, quality.** As distractors are added, B2 loses relevant evidence faster than B1. On the curve
   population, B2 Recall@5 is 0.539 → 0.284 → 0.235 and HIT@5 is 7 → 4 → 3, while B1 is 0.735 → 0.706 → 0.627.
   - N = 100 and N = 500 hold the same 33 base chunks, so every relevant chunk lost between them was displaced by a
     generated distractor.
   - The token-packing codec sends one Store query per distinct token and keeps the best candidate per atom. A
     distractor that strongly matches one generic token can therefore outrank a chunk that matches the whole question.
   - Recommended: a Neuron experiment issue that compares merge rules or codecs over this family; see the
     [worked example](#worked-example-from-observation-to-a-scoped-issue).
2. **Neuron codec/port, resource.** One B2 recall makes 10.6 Store calls on average, one per distinct token. So
   `warm.b2c` and `warm.b2` cost about 9 to 15 times `warm.b1` and allocate about 9 times as much: 8.7 MiB per query
   at N = 500. The wrapper itself is not measurably the cause (see above).
   - Recommended: a Neuron issue that adds Store calls per recall as an explicit diagnostic of the port and evaluates
     a bounded-fan-out codec. A Store-side batch API is a separate Store issue only if that experiment needs one.
3. **Store ranking, resource.**
   - Warm B1 latency and allocation per query grow about linearly with N: 0.11 ms → 0.21 ms → 0.90 ms and
     102 KiB → 240 KiB → 976 KiB per query, about 2 KiB per stored atom at N = 500.
   - The first recall on a fresh handle grows the same way (0.61 → 1.1 → 1.6 ms, 258 KiB → 793 KiB → 3.4 MiB),
     while the open itself stays below 1.3 ms and 310 KiB.
   - This matches a full scan of all stored atoms per query and is expected at this scale.
   - Recommended: compare with the Store's own corpus-scaling benchmark before filing anything. Open a Store issue
     about per-query allocation only if that benchmark shows the same O(N) bytes per query. A rewrite of Store indexing
     is a non-goal here.
4. **Experiment, measurement.** Ordering effects are visible on this laptop. The first point and the open rows carry
   JIT warm-up, and the wrapper overhead is unresolvable.
   - Recommended experiment change: per-point warm-up of all rows before measuring, or one forked JVM per point. Rely
     on allocation and on #49 for wrapper cost until then.

### Worked example: from observation to a scoped issue

Finding 1, written with the template:

```text
Title:              [monada-neuron] B2 token-packing recall loses relevant evidence to lexical distractors as
                    the corpus grows
Observed:           curve population (27 queries), B2 Recall@5 0.539 / 0.284 / 0.235 and HIT@5 7 / 4 / 3 at
                    N = 25 / 100 / 500; B1 0.735 / 0.706 / 0.627 on the same queries and roots
Expected:           B2 tracks B1 within the codec effect measured at html-kc-v1 (#107); N = 100 and N = 500
                    hold the same base chunks, so losses between them come from generated distractors only
Reproduction:       ./gradlew :monada-neuron-evaluation:runHtmlKnowledgeScaling
                    neuron.commit <sha>, store.checkoutCommit 3dd6012, corpus.base.hash 1934ef6f…,
                    query.hash e91b0404…, generator html-kd-v1 seed 111
Affected layer:     Neuron codec/port
Severity:           quality (a metric moves along the curve; every semantic check passed)
Evidence:           scaling.points[*].quality.curve (layer B2, k 5); checks n*.neuron.b2EqualsB2c pass, so the
                    adapter reproduces the Store-side control exactly and the loss comes from the merge rule, not
                    from the wrapper
Repository:         monada-neuron
Recommendation:     experiment: add a second codec or merge rule (for example, summed per-atom scores across
                    Signals) as an evaluation-only B2 variant and report it on the same scaling family, labelled
                    in-sample; no change to the production adapter until it wins on a held-out query set
```

A Store defect would be written the same way, with repository `monada-resonance-store`. A failed
`n<N>.ingestion.readback` or `store.freshHandleReadback`, for example, is a correctness finding in Store persistence.
Its reproduction is the `--sizes` point and `--store-root` directory that keep the failing root.

### Follow-up triage template

The report ends with this template; copy one block per finding:

```text
Title:              [<repository>] <one-line observation>
Observed:           <value, row and size point>
Expected:           <baseline: the same metric at N=25, the protocol rule, or the documented contract>
Reproduction:       ./gradlew :monada-neuron-evaluation:runHtmlKnowledgeScaling \
                      -PbenchmarkArgs="--sizes <N,...> --seed <seed>"
                    neuron.commit, store.checkoutCommit, corpus.base.hash, nN.corpus.hash,
                    query.hash, generator.version (local paths redacted)
Affected layer:     corpus/chunking | Store persistence | Store ranking | Neuron codec/port |
                    reasoning | grounded answer
Severity:           correctness (a semantic check failed) | quality (a metric moves along the
                    curve) | resource (latency, allocation or footprint trend)
Evidence:           html-knowledge-scaling.json paths and the attached report
Repository:         monada-resonance-store | monada-neuron
Recommendation:     <experiment change, or bug with the narrowest reproduction>
```

## Limits

- **One machine, one run, `tmpfs`.** Latency is exploratory. Persistent storage, other CPUs or a quieter machine will
  differ, and `perf.*` values are comparable only under the protocol's environment-equality rule.
- **Small query set.** The curve uses 27 queries (17 answerable). A one-query change moves Recall@5 by about 0.06.
- **Synthetic distractors.** They are lexical competitors written from a few templates, not real documentation, so
  quality at N = 500 describes this family, not a real corpus of that size.
- **In-process.** Process-cold opens and retained heap are unavailable. Restart persistence is not re-proven here.
- **L4 is mechanical at scale.** There are no human support or contradiction verdicts beyond `html-kc-v1`.
- **No optimization.** Nothing in this change alters the Store, the adapter or the core. No SIMD, GPU or off-heap
  path is activated from one run.

## Refinements of `scaling-v1`

The protocol lets #111 refine the extension in its own documentation. The size points, query workload and cross-size
rule are kept unchanged. These refinements fix what the extension left open:

- the generator `html-kd-v1`, its default seed and the prefix property across sizes;
- `CorpusBounds.SCALING`;
- in-process measurement, with process-cold reported as unavailable;
- the cycle at every `k`;
- mechanical L4 counts;
- `B2 − B2c` as the only wrapper-overhead measure;
- `perf.matchesVerified` as the guard against timing a degraded path.
