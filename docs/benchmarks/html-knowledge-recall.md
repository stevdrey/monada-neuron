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
| `html-knowledge-recall.json` | the single machine-readable report. It holds `allChecksPassed`, the semantic checks, the required metadata (run identity, versions, hashes, codec, phase JVMs) and the orchestrator environment. The scored payload sits in the same object under `recall`, or is `null` when nothing was scored. That payload has: aggregates per layer and `k` with `nDefined`/`nUndefined`; a `byCategory` object with every metric aggregate, `NoAnswerFalsePositiveRate` and the outcome counts per category; the abstention metrics; one row per query (expected chunk ids, returned ids with rank and score, `tieAtCutoff`, outcome, per-query metrics, miss diagnostics); the B1/B2c codec effect; and the follow-up candidates |
| `html-knowledge-recall.md` | the same, readable: checks and metadata, then the scored summary (aggregates, quality and outcomes by category, codec effect, per-query table, follow-ups) |
| `html-kq-v1-recall.tsv` | raw rows of the seed phase (query, layer, limit, rank, chunk id, hexadecimal score), including the limit-6 lookahead and any `<failed>` markers. Its first line states `corpusHash` and `allChecksPassed`: it is not a verified result on its own |

## Query set `html-kq-v1`

`monada-neuron-evaluation/src/integration/resources/html-kq-v1/queries.tsv`: 32 queries, curated from the corpus text
and frozen (`queryHash 97b29b684bc6d1e9943c4578613467ccae8d8a0dbc393cc291153bcf6a714dab`) before any system output was
inspected. Lists are `|`-separated, `-` is empty, a span is `chunkId start end sha256` in code points of the normalized
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
| B0 | `LexicalOverlapBaseline`: distinct query tokens contained in each chunk; score 0 dropped; score descending, then `chunkId` | 5, 6 |
| B1 | `MonadaMemory.resonate(text).topK(limit).threshold(0.0)` with default options | 1, 3, 5, 6 |
| B2 | `ResonanceStoreMemoryAdapter.recall` with `TokenPackingSignalCodec` as query encoder, the production `HashedSignalDecoder`, default options and `t = 0.0` | 1, 3, 5, 6 |
| B2c | the codec's query texts sent directly to the Store with the same limit and `t`, merged by the adapter's rule | 5, 6 |

Tokens are maximal `Character.isLetterOrDigit` runs lowercased with `Locale.ROOT` (`HtmlTokens`), shared by B0, the
paraphrase check and the codec. `t = 0.0` is the Store's `MonadaQuery` default; nothing was tuned on this set, and no
threshold sweep was run.

Every system is **scored only on its limit-5 list**: `Ret_k` is a prefix of that list, as the protocol requires. The
limit-6 **lookahead** exists only to set `tieAtCutoff` at k = 5, that is, whether ranks 5 and 6 share a score. Without
it, a tie that straddles the k = 5 cutoff could never be seen. Limits 1 and 3 serve the prefix-stability check.

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
| `recall.completed` | every B1/B2c Store call returned and every B2 recall answered `COMPLETE`, at every limit. |
| `fidelity.source` | every B1 and B2c atom maps through the manifest, and its returned content hashes to the corpus `sha256`, as returned. |
| `fidelity.reference` | every B2 reference maps through the manifest. |
| `neuron.b2EqualsB2c` | at limits 5 and 6, B2 equals the codec-direct control: same atoms, same order, bit-exact scores. A difference would be an adapter defect. |
| `recall.prefixStability` | each shorter list is the prefix of the next longer one: B1/B2 limits 1 and 3 against 5, and B1/B2/B2c limit 5 against the lookahead. The only allowed exception is an equal-score substitution at the cutoff. |

Any failure of a query in a layer, at any limit, leaves exactly one `<failed>` marker for that query and layer. This
covers an exception, a non-`COMPLETE` status, an unmapped atom or reference, and a content mismatch. The scorer then
gives the query no outcome in that layer: it is excluded from every aggregate and counted as unscored, never as an
abstention.

The orchestrator adds two checks:
- `process.restart`: seed and reopen ran in separate JVMs, as for [persistence](html-knowledge-persistence.md).
- `determinism.restartEqual`: every row of B0, B1, B2 and B2c at every limit is identical in the reopen JVM.

The seed phase also carries the three `ingestion.*` checks of #105.

## Results (2026-10-18, after review round 1)

Environment:
- Neuron `97002b3+dirty` (this change, uncommitted);
- Store checkout `7d971ea`;
- Azul OpenJDK 27 on Linux;
- one seed JVM and one reopen JVM.

Two full runs gave byte-identical `recall` payloads and raw rows, and every check passed. Scores are unchanged from
the first edition: scoring still reads the limit-5 lists.

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

- **No-answer behavior.** With `t = 0.0` the Store returns five atoms for every non-blank query. Every `no-answer`,
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
  - B1 and B2c differ in top-5 order for 30 of 32 queries;
  - B2 misses every paraphrase;
  - B2 retrieves only one side of both conflicting-source queries.

  This measures the codec, not the adapter: B2 = B2c held for every query.
- **Ties.** The lookahead flags a tie straddling the k = 5 cutoff for 23 queries in B0, whose integer overlap scores
  tie widely, and for none in B1, B2 or B2c.
- **Latency.** Each query is first sent once through B1 and B2, unmeasured (`perf.warmupQueries`). The measured pass
  then takes one warm limit-5 sample per query on the same open handles. The samples are recorded as
  `<phase>.perf.*` and are never part of a verdict:
  - B1: median about 0.2 ms;
  - B2: median about 1.5 ms, since it makes one Store query per token.

Follow-up candidates (listed in the report and never relabeled):
- B1 misses `q:exact-term-02` and `q:paraphrase-01/03/04`, and returns `q:conflicting-source-01` only `PARTIAL`.
- B2 also misses `q:exact-term-01`, `q:distractor-03` and `q:paraphrase-02`.
- Every empty-gold query is `FALSE_EVIDENCE`.

## Limits

- The query set is **in-sample**: it has no held-out counterpart, so these numbers describe this synthetic set only.
- The codec is one documented, text-only design. A semantic codec could close part of the B1/B2 gap; that is future
  work and needs a new codec version.
- Results within one root are compared by atom. Across roots, they are compared by mapped chunk id, order and score.
- Store and Forge code are unchanged, and the core library is unchanged.
