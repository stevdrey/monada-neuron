# HTML Knowledge B2 Recall Diagnosis

## Question

The [scaling profile](html-knowledge-scaling.md) (#111) showed that B2, the Neuron port, loses relevant evidence faster
than B1, the direct Store, as the corpus grows. On the 27-query curve cohort, B2 Recall@5 fell 0.539 → 0.284 → 0.176
(N = 25, 100, 500) while B1 stayed at 0.735 → 0.706 → 0.647. **Where is the evidence lost?** The candidates are:

- the decomposition of one question into independent single-token Store queries;
- each per-token query's own candidate list;
- the comparison of raw scores across different one-token queries;
- deduplication;
- the final top-`k` merge.

This page covers [#135](https://github.com/stevdrey/monada-neuron/issues/135). It is a **research-only diagnostic**:

- It changes no production path. The adapter, the `token-packing v1` codec, `Signal`, the Store and the existing
  `runHtmlKnowledgeRecall` and `runHtmlKnowledgeScaling` results are unchanged.
- Every merge alternative is replayed offline over captured Store lists and is labelled **research-only, in-sample
  exploratory**. None is substituted for B2, and none is a candidate for adoption without a held-out evaluation.
- It is not a protocol extension, so there is no protocol version change. It reuses `html-knowledge-protocol-v1` with
  the `scaling-v1` corpus family.
- Results come from one synthetic corpus family on one machine. They are not production performance claims and do not
  generalize causally beyond this corpus.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeRecallDiagnosis
```

```bash
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Diagnosis*'
```

| Option | Meaning |
| :--- | :--- |
| `--points base-25,base-33,n100,n500` | a subset of the ladder, in ladder order; default all four |
| `--seed <n>` | distractor generator seed, default `111` |
| `--output-dir <dir>` | default `build/reports/benchmarks` |
| `--store-root <dir>` | keep the point roots (`<point>/root`) there instead of a deleted temporary directory; it must be absent or empty |

Exit status: `0` all checks passed, `1` a semantic check failed, `2` invalid usage or rejected input. Nothing is
timed. A full run takes about 8 s on the machine below.

| Output | Content |
| :--- | :--- |
| `html-knowledge-recall-diagnosis.json` | `allChecksPassed`, the checks, the provenance metadata, and the payload `diagnosis`. Per point, the payload holds: the corpus identity; every system's Recall/Precision/MRR@k, HIT, false evidence and failed count, overall and by group (`arity:*`, `category:*`); the candidate ceilings; reason counts; one `fates` row per relevant chunk, query and `k`; the `displacers` (non-relevant B2 top-5 slots and the token that won each one); one `tokens` row per query Signal (document frequency, hits, top-1 score); the comparability summary; the worst regressions; cost; failed and negative queries; and `captures`, every Store hit per query, Signal and pool as `[rank, chunkId, opaqueReference, score]` |
| `html-knowledge-recall-diagnosis.md` | the checks, the metadata and a readable summary of the same |

Generated reports are not committed.

## How it works

### Controlled ladder

Every point is built with the #111 corpus rule, so it holds the same base prefix and the same generated distractors
(`html-kd-v1`, seed 111) as `scaling-v1`:

| Point | N | Base chunks | Distractors | Same as |
| :--- | ---: | ---: | ---: | :--- |
| `base-25` | 25 | 25 | 0 | #111 N = 25 |
| `base-33` | 33 | 33 (complete) | 0 | new |
| `n100` | 100 | 33 | 67 | #111 N = 100 |
| `n500` | 500 | 33 | 467 | #111 N = 500 |

- **Study A, base growth:** `base-25 → base-33`. Only the base corpus changes.
- **Study B, distractor growth:** `base-33 → n100 → n500`. The complete base stays fixed, so base-knowledge growth
  cannot masquerade as distractor pressure.

Every point scores the same fixed cohort, the 27 queries of the #111 curve population (17 answerable, 10
non-answerable). The cohort is used for **descriptive diagnosis only**, never as a tuning or validation set.

### Pipeline per point

1. Import the point corpus into a fresh root, with the ingestion checks of #105.
2. Take the verified #107 observation over the cohort: B0, B1, B2 and B2c, with source and reference fidelity, prefix
   stability and B2 = B2c at limits 1, 3, 5 and 6.
3. **Capture, labels-free** (`SignalHitCapture`). For each query, the production codec emits its Signals. Each Signal
   decodes to its single-token Store query, which is sent at four pool sizes: pools 1, 3 and 5 are the adapter's own
   `topK(k)` at each `k`, and pool 20 is a research-only expansion. Every hit is recorded with its Signal index, Store
   rank, bit-exact raw score, mapped chunk id and opaque reference; raw atom ids never reach the report. A throw, a
   non-finite score, an unmapped atom or changed content fails the query. A failed query is listed and never scored as
   zero.
4. **Replay** (`MergeCounterfactuals`). Every rule merges the identical captured lists:

   | Rule | Aggregate per atom | Status |
   | :--- | :--- | :--- |
   | `max-score` | best raw score over Signals (tie: Signal index, then Store rank) | production (B2) |
   | `sum-score` | sum of raw scores over the Signals that retrieved it | research-only |
   | `token-count` | number of Signals that retrieved it, then best raw score | research-only |
   | `rrf` | `Σ 1 / (60 + rank)` | research-only |
   | `normalized-max` | best of `score / top score of the same Signal's list` | research-only |

   Each rule runs at pool `k`, so only the merge changes, and at pool 20, a separately labelled candidate-recall change.
5. **Score** (`RecallLossAttribution`, the only component that reads gold labels). For every relevant chunk of every
   answerable query at every `k`, it assigns exactly one reason:

   | Reason | Meaning |
   | :--- | :--- |
   | `RETRIEVED` | in B2's top `k` |
   | `TIE_BROKEN_OUT` | in a pool-`k` list with the cutoff score; lost on the Signal-order or Store-rank tie-break |
   | `OUTRANKED_IN_MERGE` | in a pool-`k` list, but its best raw score is below B2's rank-`k` score |
   | `RECOVERABLE_WITH_LARGER_POOL` | in no pool-`k` list, but in a pool-20 list |
   | `ABSENT_FROM_ALL_TOKEN_LISTS` | in no list, even at pool 20 |
   | `MISSING_FROM_CORPUS` | not in the point corpus (missing gold evidence) |

   The **candidate ceiling** is the mean fraction of relevant chunks in the union of a query's Signal lists. It bounds
   the Recall@k that any merge of those candidates can reach. B2's loss therefore splits into a candidate-retrieval
   part (`1 − ceiling`) and a merge part (`ceiling − B2`).

### Checks

Each check is prefixed by its point:

- the ingestion checks of #105;
- `recall.completed`, `fidelity.source`, `fidelity.reference`, `neuron.b2EqualsB2c` and `recall.prefixStability`
  from #107;
- `diagnosis.captureCompleted`: every captured Store call returned faithfully;
- `diagnosis.maxScoreReplayEqualsB2`: the offline max-score replay over pool `k` equals B2 and B2c at limit `k` for
  `k = 1, 3, 5` (chunks, order and bit-exact scores), so every counterfactual starts from the candidates B2 itself
  merged;
- `diagnosis.attributionConsistent`: every relevant chunk is attributed once per `k`, and `RETRIEVED` equals the
  relevant chunks B2 returned.

`ladder.corpora` checks that every point has its own hash and holds every cohort query's relevant chunks.

## Results (2026-10-22)

**Environment.** JDK 27, AMD Ryzen 7 7730U, 16 GB. Neuron at `ffe9c86` plus this change (uncommitted at run time,
`ffe9c86+dirty`). Store checkout `3f77b7c`. Roots on `tmpfs`.

**Dataset hashes.**

- Base `corpusHash 1934ef6f…`, `queryHash e91b0404…`.
- Point hashes: `base-25` `526d273a…`, `base-33` `a147d825…`, `n100` `d01e53eb…`, `n500` `bd37bc18…`. The
  `base-25`, `n100` and `n500` hashes are #111's.
- Threshold 0.0; `k` = 1, 3, 5; seed 111.

`allChecksPassed: true` (45 checks). B2 = B2c held exactly at every point and limit, and the max-score replay
reproduced B2 bit for bit. No query failed.

**Baseline reproduction.** The `base-25`, `n100` and `n500` rows reproduce the #111 report exactly.

### B1 vs B2 (17 answerable cohort queries, 23 relevant chunks)

| Point | B1 R@1 | B2 R@1 | B1 R@3 | B2 R@3 | B1 R@5 | B2 R@5 | B1 MRR@5 | B2 MRR@5 | B1 HIT@5 | B2 HIT@5 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base-25 | 0.412 | 0.225 | 0.657 | 0.343 | 0.735 | 0.539 | 0.571 | 0.411 | 12 | 7 |
| base-33 | 0.412 | 0.196 | 0.637 | 0.343 | 0.735 | 0.480 | 0.571 | 0.360 | 12 | 6 |
| n100 | 0.412 | 0.176 | 0.578 | 0.196 | 0.706 | 0.284 | 0.554 | 0.244 | 12 | 3 |
| n500 | 0.412 | 0.176 | 0.549 | 0.176 | 0.647 | 0.176 | 0.539 | 0.176 | 11 | 3 |

False evidence is 8 of 10 non-answerable queries for B1 and B2 at every point: 4 no-answer, 3 out-of-domain and 1
false-premise query return results at threshold 0.0, and the 2 blank queries return none.

### Controlled studies (Recall@5)

| Study | From → to | B1 Δ | B2 Δ |
| :--- | :--- | ---: | ---: |
| A: base growth, no distractors | base-25 → base-33 | +0.000 | −0.059 |
| B: distractor growth, complete base | base-33 → n100 | −0.029 | −0.196 |
| B: distractor growth, complete base | n100 → n500 | −0.059 | −0.108 |

Of #111's N = 25 → 100 drop in B2 (−0.255), −0.059 is base growth and −0.196 is distractor growth.

### Where relevant chunks are lost (k = 5, 23 relevant chunks)

| Point | Ceiling pool 5 | Ceiling pool 20 | B2 R@5 | Retrieved | Tie broken out | Outranked in merge | Recoverable with pool 20 | Absent at pool 20 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base-25 | 1.000 | 1.000 | 0.539 | 13 | 0 | 10 | 0 | 0 |
| base-33 | 1.000 | 1.000 | 0.480 | 12 | 0 | 11 | 0 | 0 |
| n100 | 0.853 | 1.000 | 0.284 | 7 | 0 | 13 | 3 | 0 |
| n500 | 0.765 | 0.882 | 0.176 | 3 | 0 | 15 | 3 | 2 |

- No chunk was ever missing from its corpus.
- At `k = 1` and `k = 3` the same pattern holds, with more `RECOVERABLE_WITH_LARGER_POOL` because the per-token pool
  is smaller. For example, at `n500`, `k = 1`: 3 retrieved, 11 outranked, 7 recoverable, 2 absent.
- Of the chunks B2 lost at `k = 5`, B1's full-text query retrieved 6 of 10 (`base-25`), 7 of 11, 11 of 16 and 13 of
  20 (`n500`).

### Merge counterfactuals over identical candidates

Research-only, in-sample exploratory. Each cell is Recall@5 / MRR@5 / HIT@5 (of 17); false evidence is 8 of 10 for
every rule at every point.

| Rule | Pool | base-25 | base-33 | n100 | n500 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| B1 (full text) | — | 0.735 / 0.571 / 12 | 0.735 / 0.571 / 12 | 0.706 / 0.554 / 12 | 0.647 / 0.539 / 11 |
| max-score (B2) | k | 0.539 / 0.411 / 7 | 0.480 / 0.360 / 6 | 0.284 / 0.244 / 3 | 0.176 / 0.176 / 3 |
| sum-score | k | 0.824 / 0.676 / 14 | 0.735 / 0.657 / 12 | 0.539 / 0.490 / 7 | 0.500 / 0.471 / 8 |
| token-count | k | 0.775 / 0.348 / 12 | 0.735 / 0.353 / 11 | 0.539 / 0.431 / 7 | 0.559 / 0.456 / 9 |
| rrf | k | 0.745 / 0.370 / 11 | 0.706 / 0.330 / 11 | 0.559 / 0.431 / 8 | 0.539 / 0.426 / 8 |
| normalized-max | k | 0.539 / 0.399 / 8 | 0.510 / 0.337 / 8 | 0.314 / 0.232 / 5 | 0.255 / 0.211 / 4 |
| max-score | 20 | 0.539 / 0.411 / 7 | 0.480 / 0.360 / 6 | 0.284 / 0.244 / 3 | 0.176 / 0.176 / 3 |
| sum-score | 20 | 0.824 / 0.627 / 14 | 0.824 / 0.627 / 14 | 0.735 / 0.642 / 12 | 0.529 / 0.456 / 8 |
| token-count | 20 | 0.451 / 0.306 / 7 | 0.255 / 0.265 / 4 | 0.196 / 0.122 / 2 | 0.392 / 0.286 / 5 |
| rrf | 20 | 0.304 / 0.198 / 4 | 0.255 / 0.164 / 4 | 0.176 / 0.066 / 2 | 0.392 / 0.286 / 5 |
| normalized-max | 20 | 0.539 / 0.399 / 8 | 0.510 / 0.337 / 8 | 0.314 / 0.232 / 5 | 0.255 / 0.211 / 4 |

Max-score is invariant to the pool size, ties included. A hit the larger pool adds to Signal `m`'s list scores at
most that list's `k`-th hit. The `k` distinct atoms already in the list therefore each have a best hit at least that
strong, and on an equal score that best hit comes from Signal `m` or an earlier one, at a smaller rank. So under the
production order (score, then Signal index, then rank), all `k` of them precede the added hit, and max-score's top `k`
cannot change. A test pins this.

### Score comparability and cost (k = 5)

| Point | Spearman ρ(df, top-1) | Median df | Non-relevant B2 slots | of which distractors | won via df > median | B2 Store calls/query | Hits/query pool 5 | Distinct atoms pool 5 | Hits/query pool 20 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base-25 | 0.512 | 1 | 72 of 85 | 0 | 51 | 10.63 | 41.9 | 17.7 | 165.0 |
| base-33 | 0.539 | 2 | 73 of 85 | 0 | 46 | 10.63 | 41.9 | 21.1 | 166.9 |
| n100 | 0.338 | 2 | 78 of 85 | 60 | 50 | 10.63 | 41.9 | 33.4 | 167.4 |
| n500 | 0.243 | 2 | 82 of 85 | 80 | 49 | 10.63 | 41.9 | 37.6 | 167.4 |

- ρ and the median are computed over the same 226 (query, token) pairs whose pool-5 list is non-empty, so stop
  tokens the Store answers with nothing are left out. "df" is the number of corpus chunks whose distinct tokens
  contain the token, computed from chunk text only.
- B1 makes 1 Store call per query; B2 makes 10.63 at every `k`. Pool 20 keeps the call count and multiplies the hits
  scanned by about 4.
- Latency and allocation were not measured; see the #111 rows.

Further Store-side observations from the captured lists:

- The ten tokens `a`, `an`, `and`, `for`, `in`, `of`, `on`, `the`, `to`, `with` return no result at all.
- At threshold 0.0, zero-score hits fill per-token pools: 369 of 1130 pool-5 hits at `base-25`, 29 at `n500`.
- Some tokens absent from every chunk's token set still return non-zero scores. For example, `what` (df 0) scores
  0.42 against a distractor at `n500`. A single-token Store score is therefore not plain term presence. The Store's
  scoring internals are out of scope here.

### Worst regressions (k = 5)

| Point | Query | Tokens | B1 → B2 | Fate of each relevant chunk | Top-5 slots taken by |
| :--- | :--- | ---: | :--- | :--- | :--- |
| base-33 | `q:exact-term-01` | 7 | 1.0 → 0.0 | `chk:links#00` outranked (best: token `is`, rank 4) | base chunks via `element`, `is`, `html` |
| base-33 | `q:paraphrase-02` | 8 | 1.0 → 0.0 | `chk:lists-and-text-semantics#00` outranked (best: `shows`, rank 1) | `which`, `shows`, `where`, `sequence` |
| n100 | `q:code-example-03` | 10 | 1.0 → 0.0 | `chk:lists-and-text-semantics#00` outranked (best: `steps`, rank 1, 0.221) | distractors via `example` (df 7) and `what` (df 0) |
| n500 | `q:distractor-03` | 13 | 1.0 → 0.0 | `chk:headings-and-structure#04` outranked (best: `document`, rank 1) | 5 distractors via `is` (df 223) |
| n500 | `q:conflicting-source-02` | 13 | 1.0 → 0.0 | `chk:images-and-media#01` outranked; `chk:legacy-notes#00` recoverable (rank 7 of `browsers`) | distractors via `its` (df 154) and `do` |
| n500 | `q:multi-evidence-01` | 15 | 1.0 → 0.0 | both required chunks outranked (best: `it`, `image`) | distractors via `do` and `it` (df 78) |

`q:code-example-03` shows the mechanism most plainly. Its relevant chunk is the top hit of both `steps` and `ordered`
(0.221). At `n500`, the tokens `what` (0.422) and `example` (0.343) each place distractors above it, so the merge
never sees that two tokens agree on it.

## Answers to the research questions

1. **Decomposition.** Decomposition discards the token co-occurrence the full-text query keeps.
   - Without distractors, the decomposition loses no candidate at `k = 5`: the pool-5 ceiling is 1.000 at `base-25`
     and `base-33`, yet B2 retrieves only 13 and 12 of 23 relevant chunks.
   - Under distractor pressure, candidate loss appears. The ceiling falls to 0.853 (`n100`) and 0.765 (`n500`), and
     at `n500` two chunks are absent from every token list, even at pool 20.
   - The decomposition matters mainly because what it hands the merge is a set of independent raw scores with no
     record of agreement. Rules that do count agreement (`sum-score`, `token-count`, `rrf`) recover much of the gap
     over the very same candidates.
2. **Candidate loss.** At `k = 5`, the dominant reason at every point is `OUTRANKED_IN_MERGE`: 10, 11, 13 and 15 of
   23 relevant chunks.
   - Per-token candidate absence (`RECOVERABLE_WITH_LARGER_POOL` + `ABSENT_FROM_ALL_TOKEN_LISTS`) is 0, 0, 3 and 5.
     It grows at small `k`, because the adapter's per-token pool equals `k`.
   - No chunk was lost to a tie-break (`TIE_BROKEN_OUT` = 0 everywhere).
   - Deduplication never removed a distinct relevant atom. Under max-score it can only free slots, by collapsing one
     atom's repeated hits (41.9 hits become 17.7 to 37.6 distinct atoms at pool 5).
   - A larger pool alone does not help B2: max-score at pool 20 equals pool `k`, and provably must.
3. **Cross-query scores.** Raw scores from different one-token queries are not comparable as used.
   - Token document frequency correlates positively with a token's top-1 score (ρ 0.51–0.54 without distractors,
     0.34 and 0.24 with them).
   - 60 % to 71 % of the non-relevant B2 slots are won through tokens with above-median df (46–51 of 72–82).
   - With distractors, generic tokens such as `is` (df 223 at `n500`), `its`, `do`, `content` and `example` take most
     displacing slots, and 80 of 82 non-relevant slots at `n500` are distractors.
   - Normalizing each token's list by its own top score (`normalized-max`) barely helps (0.255 at `n500`). The issue
     is the absence of agreement, not only the scale.
4. **Merge attribution.** On identical candidates, merge effects can be isolated from per-token retrieval.
   - Replacing the max-score rule by the in-sample `sum-score` rule raises Recall@5 from 0.539/0.480/0.284/0.176 to
     0.824/0.735/0.539/0.500. Without distractors that closes the whole gap to B1 (it exceeds B1 at `base-25`); at
     `n100` and `n500` it closes 60 % and 69 % of it.
   - Tie breaks and deduplication have no measurable effect here.
   - The full-text B1 query keeps more relevant evidence at every point. Its higher score is a ranking outcome over
     this corpus, not evidence of semantic understanding.
   - Agreement-based rules are fragile at pool 20. `token-count` and `rrf` collapse there (to 0.18–0.45), because
     long, low-score tails from generic tokens accumulate votes. Pool size and merge rule interact and must be frozen
     together.
5. **Wave/resonance alignment (design hypotheses only).** The data favors representations that let the merge see
   agreement across a query's components. Two candidates fit Neuron's amplitude/frequency/phase model without changing
   the `Signal` contract:
   - A superposition-style recombination, in which per-Signal contributions add, like `sum-score`, instead of the
     strongest single component winning.
   - The currently constant amplitude carrying a per-token weight, so that generic components contribute less.

   Any such weight must not come from gold labels. Corpus statistics belong to the Store, so a weight derived from them
   is a boundary question (see the decision table). These are hypotheses for a follow-up experiment, not physical
   semantics and not a proposal to change `Signal`.

## Decision table

No implementation issue was filed and no production behavior changed. These are recommendations for maintainer
review.

| Option | Decision | Target | Follow-up scope |
| :--- | :--- | :--- | :--- |
| Keep the baseline | **Yes, for now.** B2 stays the production path and the reference of every comparison; B2 = B2c stays the integrity check. | — | none |
| Investigate the merge | **Recommended first.** 75 % to 100 % of the relevant chunks B2 loses at `k = 5` were among its own candidates. | `monada-neuron` (evaluation, then the adapter only if justified) | An experiment that freezes one agreement-aware rule (for example sum of raw scores at pool `k`) and its pool size before running it on an independently held-out query set that is not the #111 curve cohort. It reports quality, Store calls and allocation, and keeps max-score as the reference. Production adoption would need its own ADR. |
| Investigate the codec | **Second.** Candidate loss appears only with distractors (5 of 23 chunks at `n500`, more at small `k`), and the 10.6× Store-call fan-out is the cost finding of #111. | `monada-neuron` | An evaluation-only codec comparison: bounded fan-out, dropping tokens the Store returns nothing for, or weighted amplitudes, measured against `token-packing v1` on the same ladder. No `Signal` change. |
| File a focused Store issue | **Not yet.** Nothing here shows a Store defect: stop-token handling, non-lexical single-token scores and zero-score hits at threshold 0.0 are observations, not failures. | `monada-resonance-store` | Only if the merge or codec experiment needs Store-side statistics (for example token document frequency) or a multi-query API. That request would be scoped from that experiment's evidence. |

## Limits

- The corpus is one synthetic family: 33 authored base chunks and template distractors, seed 111. The cohort has 17
  answerable queries and 23 relevant chunks, so one chunk moves Recall@k by about 0.03 to 0.06 per query.
- The cohort contains no single-token query, so `arity:single-token` is empty. Single- versus multi-token effects
  cannot be separated here.
- Every alternative is in-sample: it was evaluated on the same cohort it is described on, and none was selected or
  tuned. Any improvement quoted above is **in-sample exploratory**.
- Threshold 0.0 lets every non-answerable query except the blanks return evidence through every system. False
  evidence does not discriminate between merge rules here.
- Cost is reported as Store calls and candidate counts only. No time or allocation was measured, and the expanded pool
  scans about 4× more hits.
