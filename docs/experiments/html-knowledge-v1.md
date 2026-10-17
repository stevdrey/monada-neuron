# HTML Knowledge-Memory Evaluation Protocol (`html-knowledge-protocol-v1`)

## Status

| Field | Value |
| :--- | :--- |
| Protocol version | `html-knowledge-protocol-v1` |
| Issue | [#104](https://github.com/stevdrey/monada-neuron/issues/104) (Experiment Knowledge 1/8) |
| Implementation | **None.** This document is the contract that issues #105 to #111 implement. |
| Scope | Monada Neuron `monada-neuron-evaluation` optional `integration` / `integrationTest` source sets only |

Any change to a definition, formula, label rule or required report field below creates a new protocol
version (`-v2`, ...) with a changelog entry at the end of this file. A report always names the protocol
version it was produced under. Results produced under different protocol, corpus or query-set versions are
not compared.

## Question

When a small, versioned HTML learning corpus is stored in a real, temporary embedded Monada Resonance Store
through its published API:

1. Does the **Store** return the chunks that independently curated gold labels say are relevant, with exact
   provenance, and does it return nothing relevant when the corpus holds no answer?
2. Does **Neuron's production memory port** (`ResonanceStoreMemoryAdapter` as a `ResonanceMemoryPort`)
   recall the same evidence through a documented, non-oracle Signal codec?
3. Does a Neuron cognitive cycle actually **carry** the recalled evidence into downstream artifacts?
4. Optionally, can a grounded answer be formed whose every claim is supported by cited, retrieved evidence,
   and which abstains when no evidence supports an answer?

Each question is scored separately. Quality and latency are reported separately.

## What this protocol does not claim

- Stored text is **external memory**. Remembering a chunk does not train model parameters, and nothing in
  this protocol measures or claims a learned model of HTML.
- **Recall is not reasoning.** A relevant chunk in the top-k proves retrieval, not understanding.
- **Retrieved evidence is not automatically true.** The corpus deliberately contains a conflicting source;
  correctness is judged against curated labels, not against whatever was retrieved.
- **Fluent answer generation is not a core capability** of Neuron. Answer-layer scores (L4) only apply to
  an explicit, optional answer component and are never attributed to the core.
- The Store's text encoder is deterministic and lexical/frequency based. Paraphrase or concept coverage is
  not assumed; it is measured and reported as observed.
- No numeric success threshold (for example "Recall@5 ≥ 0.8") is set before baselines are measured.

## Evaluation layers

| Layer | Measures | Component under test | Metric namespace | Delivered by |
| :--- | :--- | :--- | :--- | :--- |
| L1 Store retrieval | ingestion fidelity, persistence, ranked recall of chunks | `MonadaMemory.remember` / `resonate(query).topK(k)` | `store.*` | #105, #106, #107 |
| L2 Neuron recall | recall through the production memory port and codec | `ResonanceStoreMemoryAdapter.recall` | `neuron.recall.*` | #106, #107 |
| L3 Evidence use | whether recalled references reach downstream cycle artifacts | `ResonanceMemoryCognitiveStage` and later stages | `neuron.evidence.*` | #108 |
| L4 Grounded answer (optional) | claim support, citation fidelity, abstention | an explicit answer component behind a seam | `answer.*` | #109 |
| L5 Routing | worker/model route choice | `RoutingPolicy` | — | out of scope; see [forge-routing-evaluation.md](../benchmarks/forge-routing-evaluation.md) |
| L6 Resources | latency, allocation, scaling | all of the above | `perf.*` | #106 to #111 |

Rules:

- A report never combines layers into a single score. A good L1 result is not evidence for L3 or L4, and an
  L4 answer is not evidence that L2 recalled anything.
- L4 is only scored over queries whose L1/L2 evidence is recorded in the same run, so an answer can always be
  traced back to what was actually retrieved.
- HTML technical choices (see [Decision-quality extension](#decision-quality-extension)) are not routing
  decisions and are never scored with the L5 metrics.

## Corpus contract (`html-kc-v1`, delivered by #105)

### Identity and provenance

| Field | Rule |
| :--- | :--- |
| `corpusVersion` | `html-kc-v1`; any change to text, chunking or labels bumps it |
| `documentId` | `doc:<slug>`, lowercase ASCII, stable across versions when the document is unchanged |
| `chunkId` | `chk:<slug>#<nn>`, two-digit ordinal in document order |
| `topic`, `section` | authored labels (for example `links`, `headings`, `forms`, `semantics`, `media`, `scripts`) |
| `source` | `authored-fixture`, or the source URL plus a pinned revision/date |
| `license`, `attribution` | declared per chunk; `Apache-2.0` for authored chunks |
| `sourceSpan` | character range of the chunk in its normalized source document |
| `sha256` | SHA-256 of the normalized chunk text |

Normalization before hashing and before `remember`: UTF-8, Unicode NFC, `\n` line endings, trimmed leading
and trailing whitespace. Code inside a chunk is kept byte-for-byte after normalization.

### Scale and bounds

- Fixed initial scale: **20 to 40 short chunks**, each at most 1 KiB of normalized UTF-8, at most 8 chunks per
  document. Bounds are checked at load time and a violation fails the run.
- Chunking happens at heading, sentence or code-unit boundaries. A code example is never split.
- The corpus deliberately includes: confusable chunks (for example `<ol>` vs `<ul>`, `<b>` vs `<strong>`),
  chunks whose key fact is in a code example, cross-topic distractors sharing vocabulary, and exactly one
  documented **conflicting-source pair** (two chunks with contradicting claims, one marked as the curated
  correct one).
- Scaling corpora with distinct sizes (#111) are separate, named versions and never replace `html-kc-v1`.

### Store identity mapping

`MonadaMemory.remember` returns a `KnowledgeAtom` with a Store-generated id, which is not stable across
imports. The harness writes a **manifest** per import: `chunkId → atomId → opaque reference`, where the
opaque reference is the adapter's `OpaqueReference.of(atomId)` (`rs-` plus 32 hex characters of SHA-256).
The manifest is the only way a retrieved atom or Neuron reference is mapped back to a `chunkId`. It is built
from the import, never from gold labels. Importing the same corpus twice into the same root is detected and
fails explicitly.

### External sources

An external tutorial (for example MDN's *HTML: Creating the content*) is **opt-in only**: the user supplies a
locally downloaded, version-pinned snapshot; test code never downloads it; a requested but missing snapshot
fails explicitly. MDN prose is CC BY-SA 2.5: credit, source link, notice of modifications and share-alike
apply. External snapshots and text derived from them are **never committed** without an explicit licensing
review, and never run in default CI.

## Query set and gold labels (`html-kq-v1`, delivered by #107)

### Record

| Field | Rule |
| :--- | :--- |
| `queryId` | `q:<category>-<nn>` |
| `category` | one of the categories below |
| `text` | the query text exactly as sent to every system under test |
| `answerability` | `ANSWERABLE`, `NO_ANSWER_IN_CORPUS`, `OUT_OF_DOMAIN`, `FALSE_PREMISE`, `CONFLICTING`, `BLANK` |
| `required` | set of `chunkId` that together contain the answer (empty when not answerable) |
| `supporting` | set of `chunkId` that are relevant but not required |
| `evidenceSpans` | `(chunkId, start, end, sha256)` for each expected supporting passage |
| `keyClaims` | short, pre-enumerated factual claims a correct answer makes (used by L4 only) |

The relevant set used by retrieval metrics is `Rel(q) = required ∪ supporting`.

### Categories

Each category is reported on its own and has at least two queries; at least 30 % of all queries have an
empty relevant set. Total size: **25 to 40 queries**.

Retrieval metrics split queries only by `Rel(q)` (empty or not); `answerability` drives L4 scoring. A
`FALSE_PREMISE` query whose premise is refuted by a chunk has that chunk in `Rel(q)` and is scored as
answerable at L1/L2 (the refutation is the evidence); one with no refuting chunk has `Rel(q) = ∅`.

| Category | Intent | Typical answerability |
| :--- | :--- | :--- |
| `exact-term` | key term appears verbatim in the relevant chunk | `ANSWERABLE` |
| `paraphrase` | answer exists, but the query avoids the chunk's distinctive terms | `ANSWERABLE` |
| `multi-evidence` | answer needs two or more `required` chunks | `ANSWERABLE` |
| `code-example` | the fact is only visible in a code example | `ANSWERABLE` |
| `distractor` | query shares vocabulary with an irrelevant chunk | `ANSWERABLE` |
| `no-answer` | in-domain HTML question the corpus does not cover | `NO_ANSWER_IN_CORPUS` |
| `out-of-domain` | unrelated to HTML | `OUT_OF_DOMAIN` |
| `false-premise` | presupposes something the corpus contradicts or that is false | `FALSE_PREMISE` |
| `conflicting-source` | touches the conflicting-source pair | `CONFLICTING` |
| `blank` | empty or whitespace-only query | `BLANK` |

`post-reopen` is a **run condition**, not a category: the whole query set is re-run after the Store root is
closed and reopened, and results are compared with the pre-close run (see [Run conditions](#run-conditions)).

### Label integrity and leakage

- Labels are curated by a human author **before** any system output is inspected, and frozen by hashing the
  query file (`queryHash`). They are never generated, filled in or corrected from the output of a component
  that is judged with them.
- Paraphrase queries must not reuse the distinctive terms of their relevant chunks; the curator records which
  terms were avoided.
- A label change after a run is a new query-set version with a changelog line saying why. Re-labelling a
  query because a system missed it is not allowed.
- The gold map is read only by the scorer. No recall path, codec or answer component may read it.

## Retrieval metrics (L1 and L2)

### Notation

- `k ∈ {1, 3, 5}`, fixed.
- `Ret_k(q)`: the first `k` results in the order the system returned them, each mapped to a `chunkId` through
  the manifest. Fewer than `k` results is allowed; `|Ret_k(q)| ≤ k`.
- `Ret(q)`: the full returned list (bounded by the largest `k`, 5).
- `Rel(q)`: the gold relevant set. `A` = queries with `Rel(q) ≠ ∅`; `U` = queries with `Rel(q) = ∅`.
- `rank_q`: 1-based position of the first relevant result in `Ret_k(q)`, undefined if none.

### Answerable queries (`q ∈ A`)

| Metric | Per-query formula | Aggregate |
| :--- | :--- | :--- |
| `Recall@k` | `|Rel(q) ∩ Ret_k(q)| / |Rel(q)|` | mean over `A` |
| `Precision@k` | `|Rel(q) ∩ Ret_k(q)| / k` | mean over `A` |
| `HitPrecision@k` | `|Rel(q) ∩ Ret_k(q)| / |Ret_k(q)|`, undefined when `Ret_k(q) = ∅` | mean over `A` with non-empty results, with that count reported |
| `MRR@k` | `1 / rank_q`, `0` if no relevant result within `k` | mean over `A` |
| `Coverage@k` | `1` if `Rel(q) ∩ Ret_k(q) ≠ ∅`, else `0` | mean over `A` |
| `FullEvidence@k` | `1` if `required(q) ⊆ Ret_k(q)`, else `0` | mean over `multi-evidence` queries |

`Precision@k` uses the fixed denominator `k`, so returning fewer results is not rewarded; `HitPrecision@k`
shows the precision of what was actually returned. Both are always reported together.

### Non-answerable queries (`q ∈ U`)

Queries with no relevant chunk are **excluded** from every denominator above, and are scored by their own
metrics. Their count is always reported next to `|A|`, so they cannot disappear from a report.

| Metric | Formula |
| :--- | :--- |
| `NoAnswerFalsePositiveRate@k` | fraction of `q ∈ U` with `Ret_k(q) ≠ ∅` |
| `AbstentionPrecision` | `|{q ∈ U : Ret(q) = ∅}| / |{q : Ret(q) = ∅}|`, undefined when no query returned nothing |
| `AbstentionRecall` | `|{q ∈ U : Ret(q) = ∅}| / |U|` |

At L1/L2 "abstention" means an empty result after the configured threshold. `BLANK` queries are reported in
`U` and also separately, because a blank query returning nothing is a contract check, not a quality signal.
`CONFLICTING` queries are in `A` (both chunks of the pair are `supporting`, the curated one also `required`).

### Per-query outcome

Every query gets exactly one outcome per layer and `k`. Outcomes are reported as counts per category; they
are never collapsed into a single accuracy.

| Outcome | Applies to | Condition |
| :--- | :--- | :--- |
| `HIT` | `A` | `required(q) ⊆ Ret_k(q)` |
| `PARTIAL` | `A` | some but not all relevant chunks in `Ret_k(q)` |
| `MISS_WITH_RESULTS` | `A` | results returned, none relevant |
| `NO_EVIDENCE` | `A` | no results returned |
| `CORRECT_ABSTENTION` | `U` | no results returned |
| `FALSE_EVIDENCE` | `U` | results returned for a query with no relevant chunk |

`NO_EVIDENCE` is its own outcome. It is a miss for an answerable query, but it is reported separately from
`MISS_WITH_RESULTS` and is never called "zero accuracy": returning nothing and returning the wrong thing are
different failure modes.

### Ties, order and provenance

- Metrics use the returned order. The scorer records score ties; a tie group that straddles the cutoff `k` is
  flagged as `tieAtCutoff` on the query row. Within a tie group the system's order is used as returned, and
  the run is deterministic, so a repeated run reproduces it.
- **Source fidelity** (L1): for every returned atom, the SHA-256 of its returned `content` after
  normalization equals the manifest hash of the mapped chunk. A mismatch or an unmapped atom is a failed
  semantic check, not a metric.
- **Reference fidelity** (L2): every Neuron result reference maps through the manifest to a `chunkId`. An
  unmapped reference is a failed semantic check.

## Neuron recall (L2) codec rule

The default codecs (`CanonicalSignalQueryEncoder`, `HashedSignalDecoder`) are interim and non-semantic, and
the existing `FixtureSignalCodec` of the [integration oracle](../benchmarks/resonance-store-integration.md)
is a bounded lookup keyed by the fixture. **Neither a lookup codec nor any codec that reads query ids or gold
labels may be used for L2 quality metrics.** An L2 run must use a codec that:

- maps a query to Signals as a function of the query text only;
- is documented (name, version, parameters) in the report;
- is the same for every query and every compared configuration.

L1 and L2 run on the **same persisted snapshot** (same root, same manifest), with the same `k` and the same
adapter `threshold`. Because the Store sees the raw query text in L1 and an encoded Signal in L2, the two are
compared side by side without claiming parity; a difference is reported as a codec effect, not a Store or
adapter defect.

## Evidence use (L3, delivered by #108)

A recalled reference is **used** when it appears in a downstream artifact of the same cycle: a Signal admitted
by `ResonanceMemoryCognitiveStage`, or a `MemoryReferenceEvidence` attached to a hypothesis. Metrics:

| Metric | Definition |
| :--- | :--- |
| `AdmittedFraction` | admitted recalled Signals / recalled results, with the budget recorded |
| `TraceIntegrity` | fraction of downstream evidence references that map to a chunk recalled in the same cycle (must be 1) |
| `RelevantEvidenceUsed@k` | fraction of `q ∈ A` with at least one relevant chunk among the used references |
| `MemoryEmptyContrast` | per-query difference in downstream artifacts between the real port and a port that returns no results |

L3 shows that evidence flows through the cycle. It is explicitly **not** a measure of reasoning quality.

## Grounded answer (L4, delivered by #109)

L4 scores an explicit, optional answer component. Every answer is a list of claims, each with citations to
`chunkId` spans taken from the same run's retrieved evidence.

### Graded answer status

| Status | Meaning |
| :--- | :--- |
| `SUPPORTED_CORRECT` | all `keyClaims` present, every emitted claim supported by a cited span |
| `SUPPORTED_PARTIAL` | some `keyClaims` present, every emitted claim supported |
| `UNSUPPORTED` | a claim is not supported by its cited evidence, **even if the claim is true** |
| `CONTRADICTED` | a claim contradicts the curated evidence |
| `CORRECT_ABSTENTION` | abstained on a non-answerable query |
| `WRONG_ABSTENTION` | abstained although the query is answerable and relevant evidence was retrieved |
| `FALSE_PREMISE_CORRECTED` / `FALSE_PREMISE_ACCEPTED` | false premise rejected with evidence / answered as if true |
| `CONFLICT_SURFACED` / `CONFLICT_HIDDEN` | conflicting sources disclosed / one side silently chosen |

`UNSUPPORTED` exists because an answer can be right by prior knowledge or word overlap without using the
stored evidence. **Grounding is mandatory**: a true but unsupported claim is not credited.

### Metrics

| Metric | Formula |
| :--- | :--- |
| `CitationFidelity` | fraction of citations whose chunk is in the run's retrieved set and whose span hash matches the manifest |
| `CitationPrecision` / `CitationRecall` | cited spans vs gold `evidenceSpans` (overlap counts as a match) |
| `ClaimSupport` | supported emitted claims / emitted claims |
| `KeyClaimCoverage` | matched `keyClaims` / `keyClaims`, over `A` |
| `AbstentionPrecision`, `AbstentionRecall` | as in L1/L2, at answer level |

Judging: a deterministic matcher compares claims with the pre-enumerated `keyClaims` and spans; disagreements
and unmatched claims go to a human reviewer, whose verdict is recorded with the query row. **The judge is
never the component under test**, and no model judges its own answers.

## Baselines

| Id | Baseline | Purpose |
| :--- | :--- | :--- |
| B0 | exact-token overlap over the same normalized chunks: tokens split on non-alphanumerics, case-folded, score = shared distinct tokens, ties broken by `chunkId`, empty when the score is 0 | trivial lexical reference, scalar `O(Q·N)` |
| B1 | Store direct: `MonadaMemory.resonate(query).topK(k)` with Store defaults | raw Store retrieval (L1) |
| B2 | Neuron port: `ResonanceStoreMemoryAdapter.recall` with a documented non-oracle codec | Neuron memory port (L2) |
| B3 | deterministic extractive answer: the sentence of the top-ranked retrieved chunk with the highest token overlap with the query, citing it; abstain when nothing is retrieved | answer floor for L4 (#109) |

Fairness: every baseline sees the same chunks, the same normalization, the same `k` and, for B1/B2, the same
persisted snapshot. B0 is a reference, not a search engine to be tuned; it is not extended with stemming,
synonyms or weighting.

## Thresholds and sweeps

- Store defaults are never changed. The adapter `threshold` used for B2 is stated in the report.
- A threshold sweep is **exploratory**: a fixed, documented grid (for example `0.0` to `0.9` step `0.1`), a
  fixed seed, and the full curve (`Recall@k`, `NoAnswerFalsePositiveRate@k`, `AbstentionPrecision`) reported
  for every grid point.
- A threshold picked from a sweep is not used to report quality on the same query set in the same run. It
  becomes the configured value only through a new protocol or query-set version, before the next measured run.

## Run conditions

- **Determinism**: two runs with the same corpus, query set, configuration and seed produce identical semantic
  results (outcomes, mapped chunk ids, order and scores). A difference is a failed check.
- **Post-reopen**: after the full query set runs, the Store root is closed and reopened (`MonadaMemory.open` on
  the same path, a fresh adapter on the same path); the rerun must produce identical mapped chunk ids, order
  and scores. Delivered by #106.
- **Warm / cold**: as defined by the [integration evaluation](../benchmarks/resonance-store-integration.md#measurement-method):
  "cold" is a freshly opened handle in an already warmed JVM, "warm" is repeated use of one open handle.
  Process-cold numbers, if ever reported, are a separate, labeled row.
- **Bounds**: `k ≤ 5`, bounded result readback, bounded corpus and query sizes as above; no network, crawler,
  provider call, credential or live-web fetch.

### Required report fields

`protocolVersion`, `corpusVersion`, `corpusHash`, `queryVersion`, `queryHash`, `seed`, `k`, adapter
`threshold`, codec name/version, run mode/profile, JDK vendor and version, OS and architecture, available
processors, GC and heap settings, Neuron version and commit (`+dirty` when uncommitted), Store checkout commit,
and the persisted Store manifest (version, encoder, normalizer, dimensions, vector format).

### Resources (L6)

Latency is reported as **median and p95** per row. Allocated bytes per operation are reported separately, only
when measured (the existing `EvaluationMetricsCollector` reports unavailable telemetry as unavailable). Setup,
ingestion, reopen, first recall and warm recall are separate rows. Latency and allocation never affect a
semantic verdict or the exit status.

## Comparison and regression

A comparison between a baseline configuration and a changed configuration is valid only when `corpusHash`,
`queryHash` and `protocolVersion` are equal. It reports per-metric deltas and a **per-query diff**: queries
whose outcome changed, with the old and new retrieved chunk ids.

A change is flagged as a **regression** (to be explained, fixed or documented, not a numeric gate) when:

- a run is not deterministic, or post-reopen results differ;
- source or reference fidelity fails;
- a query that was `HIT` is no longer `HIT`;
- a non-answerable query moves to `FALSE_EVIDENCE` without a documented configuration change that explains it;
- a required report field is missing.

Known weak queries (for example paraphrases the lexical encoder misses) are listed as concrete follow-up
candidates. They are not removed or relabeled to improve a score.

## Report shape

Each delivered experiment writes a machine-readable JSON report and a concise Markdown report under
`build/reports/benchmarks/` (the existing default), containing the required fields, aggregate metrics per
layer, `k` and category, outcome counts, and one row per query: `queryId`, category, answerability, expected
chunk ids, returned chunk ids with ranks and scores, `tieAtCutoff`, outcome, and diagnostics for misses. The
process exits non-zero only on a failed semantic check.

## Decision-quality extension

Issues [#112](https://github.com/stevdrey/monada-neuron/issues/112) and
[#113](https://github.com/stevdrey/monada-neuron/issues/113) use the same corpus to test requirements-based
technical choices. Their protocol is specified in those issues; this protocol only fixes the boundaries:

- Choices are judged against **independently curated acceptable-option sets** with explicit required and
  forbidden constraints; choice, rationale and citation validity are scored separately, and correct abstention
  is an outcome.
- Matched scenarios differ in one requirement that should, or should not, change the recommendation. Initial
  pairs: `div` vs `iframe` (grouping content vs embedding a separate document), `ol` vs `ul` (whether order
  matters), and `script` with inline code vs an external `src` (reuse, loading behavior, Content Security Policy).
- A deterministic rule baseline in the test harness is reported separately from Neuron's recalled evidence and
  from any optional generator.
- Multi-turn context is explicitly **caller-supplied** input, never implicit Neuron state.
- An HTML choice is not a worker/model route; `RoutingPolicy` and its metrics are not used.

## Planned commands

Only `./gradlew test` exists today. The commands below are **planned**: the names are provisional and each
issue fixes the exact task name in its own documentation.

| Issue | Status | Command (provisional) | Purpose |
| :--- | :--- | :--- | :--- |
| #105 | planned | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeIngestion` | import `html-kc-v1` into a temporary Store, write the manifest |
| #106 | planned | `./gradlew :monada-neuron-evaluation:integrationTest` | persistence, reopen and source-level readback through Neuron |
| #107 | planned | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeRecall` | L1/L2 judged recall with B0/B1/B2 |
| #108 | planned | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeEvidenceTrace` | L3 evidence use in a cognitive cycle |
| #109 | planned | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeAnswers` | L4 grounded answers with B3 and abstention |
| #110 | planned | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeFeedback` | relevance feedback vs a no-feedback control |
| #111 | planned | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeScaling` | L6 scaling profile |

All planned tasks require the sibling `../monada-resonance-store` checkout, like the existing integration
source set, and do not change `./gradlew test` when it is absent.

## Changelog

- `html-knowledge-protocol-v1` (issue #104): initial protocol.
