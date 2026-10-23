# HTML Knowledge-Memory Evaluation Protocol (`html-knowledge-protocol-v1`)

## Status

| Field | Value |
| :--- | :--- |
| Protocol version | `html-knowledge-protocol-v1` |
| Issue | [#104](https://github.com/stevdrey/monada-neuron/issues/104) (Experiment Knowledge 1/8) |
| Implementation | Corpus contract delivered by #105 ([ingestion](../benchmarks/html-knowledge-ingestion.md)); ingestion readback, post-reopen (restart) and L2 attribution delivered by #106 ([persistence](../benchmarks/html-knowledge-persistence.md)); query set `html-kq-v1`, L1/L2 judged recall and baselines B0 to B2c delivered by #107 ([recall](../benchmarks/html-knowledge-recall.md)); L3 evidence use (`l3-evidence-v1`) delivered by #108 ([evidence trace](../benchmarks/html-knowledge-evidence-trace.md)); L4 grounded answers (`l4-answer-v1`), baseline B3 and the generator seam delivered by #109 ([grounded answers](../benchmarks/html-knowledge-answers.md)); relevance feedback versus a no-feedback control (`feedback-v1`) delivered by #110 ([relevance feedback](../benchmarks/html-knowledge-feedback.md)); the rest of this document is the contract that issue #111 implements. |
| Scope | Monada Neuron `monada-neuron-evaluation` optional `integration` / `integrationTest` source sets only |
| Extensions | `l3-evidence-v1` (#108), `l4-answer-v1` (#109), `feedback-v1` (#110), `scaling-v1` (#111), `decision-v1` (#112) |

The **core** protocol (`html-knowledge-protocol-v1`) covers the corpus and query contracts, L1/L2 retrieval
metrics, baselines B0 to B2c, thresholds, run conditions, comparison rules and the report. The sections on evidence
use, grounded answers, relevance feedback and scaling are **extensions** with their own versions. Each states the
minimum requirements of its delivering issue; that issue may refine its extension in its own documentation by
bumping only that extension's version (`l3-evidence-v2`, ...), never by weakening a core rule.

Any change to a core definition, formula, label rule or required report field creates a new core version (`-v2`,
...) with a changelog entry at the end of this file. A report names the core version and the version of every
extension it uses. A comparison requires equal core versions and equal versions of every extension involved in the
compared metrics. Results produced under different corpus or query-set versions are not compared, except as points
of one [scaling family](#scaling-family-delivered-by-111).

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
| L1 Store retrieval | ingestion fidelity, persistence, ranked recall of chunks | `MonadaMemory.remember` / `resonate(query).topK(5).threshold(t)` | `store.*` | #105, #106, #107 |
| L2 Neuron recall | recall through the production memory port and codec | `ResonanceStoreMemoryAdapter.recall` | `neuron.recall.*` | #106, #107 |
| L3 Evidence use | whether recalled references reach downstream cycle artifacts | `ResonanceMemoryCognitiveStage` and later stages | `neuron.evidence.*` | #108 |
| L4 Grounded answer (optional) | claim support, citation fidelity, abstention | an explicit answer component behind a seam | `answer.*` | #109 |
| L5 Routing | worker/model route choice | `RoutingPolicy` | — | out of scope; see [forge-routing-evaluation.md](../benchmarks/forge-routing-evaluation.md) |
| L6 Resources | latency, allocation, scaling | all of the above | `perf.*` | #106 to #111 |

Rules:

- A report never combines layers into a single score. A good L1 result is not evidence for L3 or L4, and an
  L4 answer is not evidence that L2 recalled anything.
- Every L4 evaluation is **bound to exactly one retrieval layer** (see [Evidence binding](#evidence-binding)),
  so an answer is always traced back to what that layer actually retrieved.
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
| `sourceSpan` | `[start, end)` range of the chunk in its normalized source document, in code points (see [Spans](#spans-and-offsets)) |
| `sha256` | SHA-256 of the UTF-8 bytes of the normalized chunk text, lowercase hex |
| `documentSha256` | SHA-256 of the normalized source document (the authored document file, or the user-supplied external snapshot) |
| `conflictPair`, `conflictRole` | id of the conflicting-source pair and the member's role (`CURATED` or `CONTRADICTING`); absent for every other chunk |

Normalization before hashing and before `remember`: UTF-8, Unicode NFC, `\n` line endings, trimmed leading
and trailing whitespace. Code examples inside a chunk are delimited by lines consisting of exactly three
backticks (a fenced block) and are kept byte-for-byte after normalization.

### Spans and offsets

Every span in this protocol (`sourceSpan`, gold `evidenceSpans`, answer citations) is a half-open range
`[start, end)` of **Unicode code point** offsets into the normalized text it refers to, with
`0 ≤ start < end ≤ length`. A span digest is the SHA-256 (lowercase hex) of the UTF-8 bytes of exactly that
substring, with no further normalization.

### Scale and bounds

- Fixed initial scale: **20 to 40 short chunks**, each at most 1 KiB of normalized UTF-8, at most 8 chunks per
  document. Bounds are checked at load time and a violation fails the run.
- Chunking happens at heading, sentence or code-unit boundaries. A code example is never split.
- The corpus deliberately includes: confusable chunks (for example `<ol>` vs `<ul>`, `<b>` vs `<strong>`),
  chunks whose key fact is in a code example, cross-topic distractors sharing vocabulary, and exactly one
  documented **conflicting-source pair** (two chunks with contradicting claims sharing one `conflictPair`, exactly
  one of them with `conflictRole = CURATED`).
- Load-time corpus invariants: exactly one `conflictPair` exists, with exactly two members and exactly one
  `CURATED`; each normalized source document matches its `documentSha256`; and the `sourceSpan` substring of that
  document equals the chunk's normalized text. A violation fails the run before hashing or import.
- Scaling corpora with distinct sizes (#111) are separate, named versions and never replace `html-kc-v1`.

### Store identity mapping

`MonadaMemory.remember` returns a `KnowledgeAtom` with a Store-generated id. The harness never predicts or
assumes that id (see the [errata](#errata-store-atom-ids) on how the Store currently derives it). The harness writes a **manifest** per import: `chunkId → atomId → opaque reference`, where the
opaque reference is the adapter's `OpaqueReference.of(atomId)` (`rs-` plus 32 hex characters of SHA-256).
The manifest is the only way a retrieved atom or Neuron reference is mapped back to a `chunkId`. It is built
from the import, never from gold labels. It holds no content hashes: content checks always compare against the
corpus `sha256`. Importing the same corpus twice into the same root is detected and fails explicitly. Because
atom ids are Store-owned, the manifest never participates in a dataset hash or a cross-run comparison.

#### Errata: Store atom ids

The first edition of this section called atom ids random. In the current Store they are **content-derived**
(`KnowledgeAtom` uses a name-based UUID of the UTF-8 content), and `remember` is **idempotent**: remembering an
existing text returns the existing atom and writes nothing. The rules above are unchanged, and two consequences are
enforced by the harness rather than by the Store:

- Two chunks with identical normalized text would collapse into one atom, so the corpus loader rejects them.
- The Store cannot report a second import of the same corpus into the same root, so the harness refuses any target
  root that already holds files.

Ids are still mapped only through each root's manifest and compared across roots only as mapped chunk ids, so a
future Store with random ids requires no protocol change.

### Dataset hashes

`corpusHash` and `queryHash` are the SHA-256 (lowercase hex) of a canonical byte serialization, so formatting,
file layout or map order cannot change them and any change to a covered field does:

- Every field is encoded as UTF-8 bytes preceded by its byte length as a 4-byte big-endian unsigned integer;
  an absent value is encoded as length `0xFFFFFFFF` with no bytes. Integers are encoded as their decimal string.
- The serialization starts with the version string (`corpusVersion` or `queryVersion`), then the record count,
  then the records sorted by id (`chunkId` or `queryId`, compared by code point).
- Corpus record fields, in order: `chunkId`, `documentId`, `topic`, `section`, `source`, `license`,
  `attribution`, `sourceSpan.start`, `sourceSpan.end`, `sha256`, `documentSha256`, `conflictPair`,
  `conflictRole`. The chunk text is covered through `sha256`.
- Query record fields, in order: `queryId`, `category`, `text`, `answerability`, then `required` and
  `supporting` (each as a count followed by its `chunkId`s sorted by code point), then `evidenceSpans` (count,
  then each span as `chunkId`, `start`, `end`, `sha256`, sorted by `(chunkId, start, end)`), then `keyClaims`
  (count, then each claim in curated order), then `avoidedTerms` (count, then the terms sorted by code point).

### Delivered implementation (#105)

| Item | Location or rule |
| :--- | :--- |
| Corpus files | `monada-neuron-evaluation/src/integration/resources/html-kc-v1/`: `documents.tsv`, `chunks.tsv`, `documents/<slug>.md` |
| Size | 7 originally authored documents (Apache-2.0), 33 chunks, conflict pair `conflict:img-alt` (`chk:images-and-media#01` `CURATED`, `chk:legacy-notes#00` `CONTRADICTING`) |
| Chunk ids | `chk:<slug>#nn`, ordinals from `00` |
| Chunking policy | `heading-section-v1`: each ATX heading starts a section, a heading-only section merges into the next, text before the first heading is a `(preamble)` section; an oversize section is split at blank-line blocks, then at sentence ends; headings that do not fit with the following paragraph keep its first sentences, and headings that fit nothing after them form their own chunk; a fence (an opening line starting with three backticks, a closing line of exactly three backticks) is never split; an oversize code block, heading or sentence is rejected |
| Label check | loading rechunks every document and requires `chunks.tsv` (ids, `section`, spans, `sha256`) to equal the canonical result |
| Bounds | as [above](#scale-and-bounds), plus at most 16 documents of at most 32 KiB each; label tables at most 64 KiB |
| Command | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeIngestion` (see [the ingestion page](../benchmarks/html-knowledge-ingestion.md)) |

### External sources

An external tutorial (for example MDN's *HTML: Creating the content*) is **opt-in only**: the user supplies a
locally downloaded, version-pinned snapshot; test code never downloads it; a requested but missing snapshot
fails explicitly. MDN prose is CC BY-SA 2.5: credit, source link, notice of modifications and share-alike
apply. External snapshots and text derived from them are **never committed** without an explicit licensing
review, and never run in default CI.

#105 supports exactly one external source: the Markdown file of MDN's *HTML: Creating the content* from a
user-supplied `mdn/content` checkout, pinned by commit and by the SHA-256 of its raw bytes. It is a separate corpus
version `html-kc-ext-mdn-v1` with single-document bounds (at most 256 KiB, at most 100 chunks, the two-digit ordinal range, of at most 1 KiB) and no
conflict pair. It is imported into its own root. Every chunk declares `CC-BY-SA-2.5`, the attribution
"Mozilla Contributors", the page URL with the pinned revision, and the modifications made (front matter removed,
normalized, chunked). Reports, labels and manifests built from it contain ids, spans and digests, never its text:
its `section` headings are rendered as `sha256:<hex>`.

## Query set and gold labels (`html-kq-v1`, delivered by #107)

### Record

| Field | Rule |
| :--- | :--- |
| `queryId` | `q:<category>-<nn>` |
| `category` | one of the categories below |
| `text` | the query text exactly as sent to every system under test |
| `answerability` | `ANSWERABLE`, `NO_ANSWER_IN_CORPUS`, `OUT_OF_DOMAIN`, `FALSE_PREMISE`, `CONFLICTING`, `BLANK` |
| `required` | set of `chunkId` that together contain the answer, or the refutation of a false premise; empty exactly when no chunk is relevant |
| `supporting` | set of `chunkId` that are relevant but not required; must be empty when `required` is empty |
| `evidenceSpans` | `(chunkId, start, end, sha256)` for each expected supporting passage; validated against the corpus text at load |
| `keyClaims` | short, pre-enumerated factual claims a correct answer makes (used by L4 only); a `FALSE_PREMISE` query always includes the claim that refutes its premise |
| `avoidedTerms` | `paraphrase` queries only: the distinctive terms of the relevant chunks that the query text deliberately avoids; empty for other categories |

Load-time invariants (a violation fails the run before anything is scored, serialized or imported):

- `chunkId` is unique within the corpus and its ordinal belongs to its `documentId`; `queryId` is unique within
  the query set.
- No list or set field contains a repeated entry: `required`, `supporting`, `evidenceSpans`, `keyClaims` and
  `avoidedTerms`.
- `supporting` is empty when `required` is empty, and `required ∩ supporting = ∅`.
- `answerability` is fixed by `category` (table below) and agrees with the relevant set: `ANSWERABLE` and
  `CONFLICTING` have `required ≠ ∅`; `NO_ANSWER_IN_CORPUS`, `OUT_OF_DOMAIN` and `BLANK` have `Rel(q) = ∅`; only
  `FALSE_PREMISE` may have either.
- A `CONFLICTING` query has exactly the two chunks of the conflicting-source pair as `required`.
- `text` is empty or whitespace-only exactly for `blank` queries, and query texts are unique within the set.
- `|Rel(q)| ≤ 5`, the largest evaluated limit, so `HIT`, `FullEvidence@5` and `Recall@5 = 1` are always
  attainable. For `k < |Rel(q)|` the lower ceiling of `Recall@k` is inherent to that `k`.
- Every `evidenceSpans` entry names a chunk in `Rel(q)`, matches the corpus text by digest, and every `required`
  chunk has at least one span. A query with `Rel(q) = ∅` has no spans.
- Every query with `Rel(q) ≠ ∅` has at least one `keyClaim`; a query with `Rel(q) = ∅` may have none.
- A `paraphrase` query has a non-empty `avoidedTerms`, and no token (B0 tokenization) of an avoided term occurs
  among the tokens of its `text`.

The relevant set used by retrieval metrics is `Rel(q) = required ∪ supporting`.

### Categories

Each category is reported on its own and has at least two queries; at least 30 % of all queries have an
empty relevant set. Total size: **25 to 40 queries**.

Retrieval metrics split queries only by `Rel(q)` (empty or not). At L4, abstention statuses and the set-level
abstention metrics also use `Rel(q)`, while `answerability` selects the statuses of answered queries. A
`FALSE_PREMISE` query whose premise is refuted by a chunk has that chunk in `Rel(q)` and is scored as
answerable at L1/L2 (the refutation is the evidence); one with no refuting chunk has `Rel(q) = ∅`.

| Category | Intent | Answerability (fixed) |
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

`post-reopen` is a **run condition**, not a category: the whole query set is re-run against the same Store root
from a new process, and results are compared with the first run (see [Run conditions](#run-conditions)).

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

- `k ∈ {1, 3, 5}`, fixed. Each system is queried once with limit 5 and threshold `t` (see
  [Baselines](#baselines)); `Ret_k(q)` is the prefix of that list. A separate check queries with limits 1 and 3
  and requires each result to equal the corresponding prefix, except inside a score-tie group that straddles the
  cutoff: there the limit-`k` result may hold any members of that tie group with the same scores, and the query is
  flagged `tieAtCutoff` (see [Ties](#ties-order-and-provenance)). Any other difference is a failed semantic check.
- `Ret_k(q)`: the first `k` results in the order the system returned them, each mapped to a `chunkId` through
  the manifest. Fewer than `k` results is allowed; `|Ret_k(q)| ≤ k`.
- `Ret(q)` = `Ret_5(q)`, the full returned list.
- `Rel(q)`: the gold relevant set. `A` = queries with `Rel(q) ≠ ∅`; `U` = queries with `Rel(q) = ∅`.
- `rank_q`: 1-based position of the first relevant result in `Ret_k(q)`, undefined if none.

### Undefined values and aggregation

These rules apply to every metric in this protocol (L1 to L4):

- A per-query ratio whose denominator is 0 is **undefined**, serialized as JSON `null`. It is never reported
  as `0`, `1` or `NaN`.
- An aggregate is the **macro mean** of the defined per-query values of its population, reported with
  `nDefined` and `nUndefined`. When `nDefined = 0` (including an empty population), the aggregate is undefined.
- A per-query value that the protocol marks as undefined for another reason (for example `traceAmbiguous` in
  L3) follows the same rule.

### Answerable queries (`q ∈ A`)

| Metric | Per-query formula | Aggregate |
| :--- | :--- | :--- |
| `Recall@k` | `|Rel(q) ∩ Ret_k(q)| / |Rel(q)|` | mean over `A` |
| `Precision@k` | `|Rel(q) ∩ Ret_k(q)| / k` | mean over `A` |
| `HitPrecision@k` | `|Rel(q) ∩ Ret_k(q)| / |Ret_k(q)|`, undefined when `Ret_k(q) = ∅` | mean over `A` (defined values only) |
| `MRR@k` | `1 / rank_q`, `0` if no relevant result within `k` | mean over `A` |
| `Coverage@k` | `1` if `Rel(q) ∩ Ret_k(q) ≠ ∅`, else `0` | mean over `A` |
| `FullEvidence@k` | `1` if `required(q) ⊆ Ret_k(q)`, else `0` | mean over `multi-evidence` queries (undefined if there are none) |

`Precision@k` uses the fixed denominator `k`, so returning fewer results is not rewarded; `HitPrecision@k`
shows the precision of what was actually returned. Both are always reported together.

### Non-answerable queries (`q ∈ U`)

Queries with no relevant chunk are **excluded** from every denominator above, and are scored by their own
metrics. Their count is always reported next to `|A|`, so they cannot disappear from a report.

| Metric | Formula |
| :--- | :--- |
| `NoAnswerFalsePositiveRate@k` | fraction of `q ∈ U` with `Ret_k(q) ≠ ∅`; undefined when `U = ∅` |
| `AbstentionPrecision` | `|{q ∈ U : Ret(q) = ∅}| / |{q : Ret(q) = ∅}|`; undefined when no query returned nothing |
| `AbstentionRecall` | `|{q ∈ U : Ret(q) = ∅}| / |U|`; undefined when `U = ∅` |

These are set-level ratios over the query population, not means of per-query values.

### Completed recalls only

Only completed recalls are scored. B2 must answer with status `COMPLETE`, and every B1/B2c Store call must
return without an exception. Any other result (`FAILED`, `UNAVAILABLE`, `PARTIAL` or `TIMED_OUT` from the port, or
an exception from the Store) is a failed semantic check: the query gets no outcome and the run exits non-zero,
so an operational failure can never be counted as an abstention. At L1/L2 "abstention" means an empty result
list in a completed recall, after the configured threshold.

`BLANK` queries are reported in `U` and also separately, because a blank query returning nothing is a contract
check, not a quality signal. `CONFLICTING` queries are in `A` with **both** chunks of the pair `required`, so a
`HIT` means both sources were retrieved. Which side is correct is judged at L4 through `keyClaims` and
`contradicted`, not through `required`.

### Per-query outcome

Every query gets exactly one outcome per layer and `k`. The outcome is the **first** row below, in order, whose
condition holds; the conditions are evaluated in this order and later rows assume earlier ones failed.
Outcomes are reported as counts per category; they are never collapsed into a single accuracy.

| Order | Outcome | Applies to | Condition |
| :--- | :--- | :--- | :--- |
| 1 | `HIT` | `A` | `required(q) ⊆ Ret_k(q)` (`required(q)` is non-empty for every `q ∈ A`) |
| 2 | `PARTIAL` | `A` | `Rel(q) ∩ Ret_k(q) ≠ ∅` |
| 3 | `MISS_WITH_RESULTS` | `A` | `Ret_k(q) ≠ ∅` |
| 4 | `NO_EVIDENCE` | `A` | otherwise (`Ret_k(q) = ∅`) |
| 1 | `CORRECT_ABSTENTION` | `U` | `Ret_k(q) = ∅` |
| 2 | `FALSE_EVIDENCE` | `U` | otherwise |

A query that returns every `required` chunk but not every `supporting` chunk is a `HIT`; `Recall@k` still
shows the missing supporting chunk.

`NO_EVIDENCE` is its own outcome. It is a miss for an answerable query, but it is reported separately from
`MISS_WITH_RESULTS` and is never called "zero accuracy": returning nothing and returning the wrong thing are
different failure modes.

### Ties, order and provenance

- Metrics use the returned order. The scorer records score ties; a tie group that straddles the cutoff `k` is
  flagged as `tieAtCutoff` on the query row. Within a tie group the system's order is used as returned, and
  the run is deterministic, so a repeated run reproduces it.
- **Source fidelity** (L1): for every returned atom, the SHA-256 of the UTF-8 bytes of its returned `content`,
  **as returned and without normalizing it again**, equals the corpus `sha256` of the mapped chunk. Chunks are
  stored already normalized, so any change, including a normalization-equivalent one (boundary whitespace,
  line endings, decomposed Unicode), fails. A mismatch or an unmapped atom is a failed
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

L1 and L2 run on the **same persisted snapshot** (same root, same manifest), with the same limit and the same
threshold `t`.

### Attributing L1/L2 differences

The production adapter does more than encode: for each query Signal it calls
`resonate(queryEncoder.encode(signal)).topK(maxResults).threshold(t)` on the Store, then merges all candidates by
score descending, then query-Signal index, then Store rank, keeps the best candidate per atom, and cuts the list
to `maxResults` (`ResonanceStoreMemoryAdapter`). A difference between L1 and L2 can therefore come from the codec
**or** from the adapter. To separate them, every L2 run includes the control **B2c (codec-direct)**:

1. the harness encodes the same query Signals with the same codec instance and records the emitted query texts;
2. it sends each text directly to the Store with the same limit and `t`;
3. it applies the merge rule above itself.

B2 must equal B2c exactly (same atoms, order and scores); a difference is an **adapter defect** and a failed
semantic check. Only the remaining difference between B1 and B2c is reported as a **codec effect**, compared
side by side without claiming parity.

## Evidence use (L3, delivered by #108)

Extension `l3-evidence-v1`: minimum requirements; #108 may refine them in its own documentation. Delivered by #108:
see [the evidence trace page](../benchmarks/html-knowledge-evidence-trace.md) for the cycle, the controls and the
refinements it fixes.

Signals carry no identifiers ([ADR 0005](../adr/0005-signals-without-identifiers.md)), and the core does not
attach `MemoryReferenceEvidence` to anything on its own. L3 therefore traces evidence through what the code
actually exposes:

- **Cycle inputs**: for query `q`, the cycle inputs are the Signals that the L2 codec (the same instance) produces
  for `q`. The memory stage builds its recall request from its own input Signals, which are the `PERCEPTION`
  outputs and may differ from the codec Signals; the recording decorator therefore records the actual request
  Signals, and each query row reports `requestMatchesCodec` and whether the recalled chunks equal B2's for `q`.
  `RelevantEvidenceUsed` is scored against `Rel(q)` with these flags recorded next to it.
- **Recalled**: the port's full response is captured at the port boundary by a recording decorator (the same
  pattern as the `ReplayedMemoryResponse` row of the integration evaluation). It must have status `COMPLETE`
  (see [Completed recalls only](#completed-recalls-only)); the `PARTIAL` status that admission
  later gives the stage result is expected and is not an error. The stage result alone cannot give
  this count: when the cycle admits only part of the output, `withAdmittedOutputSignals` trims the response to the
  admitted result prefix (status `PARTIAL`).
- **Admitted**: `ResonanceMemoryCognitiveStage` emits the pass-through input Signals followed by the recalled
  Signals, so in the admitted `ResonanceMemoryStageResult` the recalled slice is the **last**
  `r = response().results().size()` elements of `outputSignals()`, and `outputSignals().size() - r` is the
  pass-through count. Element `j` of that slice is `response().results().get(j).signal()` and carries that
  result's reference, which the manifest maps to a `chunkId`.
- **Trace key**: later stages may rewrite Signals, so tracing uses a documented **trace key function** `τ(Signal)`
  recorded in the report (default: full value equality of the `Signal` record; an L3 run may declare another,
  for example the frequency state, which the existing integration fixture keeps stable across stages).
- **Used**: an admitted chunk is used when `τ` of its admitted Signal equals `τ` of a Signal in the
  `outputSignals()` of a stage result that comes after `MEMORY_RECALL`. A stage that passes a Signal through
  unchanged counts: "used" measures that evidence is **carried**, not that it influenced anything; influence is
  what `MemoryEmptyContrast` probes.
- **Ambiguity**: when two admitted Signals share a `τ` value, or an admitted Signal shares one with a cycle input
  or a `PERCEPTION` output Signal, the query is marked `traceAmbiguous` and its trace-based values are undefined.
  The same applies when `τ` of an admitted Signal also appears in the output of any stage after `MEMORY_RECALL`
  in the memory-empty run of the same query (see `MemoryEmptyContrast` below, which is therefore mandatory in
  every L3 run): a key that the cycle reproduces without memory is not evidence that memory was carried.
- A `MemoryReferenceEvidence` counts as use only when an explicit, documented component attaches it; its
  reference must then map through the manifest to a chunk admitted in the same cycle.

The L3 run records the memory-stage result limit and the signal budget.

| Metric | Per-query definition | Undefined when |
| :--- | :--- | :--- |
| `AdmittedFraction` | admitted results / results in the recorded port response | the recorded response has no results |
| `UsedFraction` | used admitted chunks / admitted chunks | nothing admitted, or `traceAmbiguous` |
| `RelevantEvidenceUsed` | `1` if a used chunk is in `Rel(q)`, else `0`; over `A` only | `traceAmbiguous` |
| `MemoryEmptyContrast` | see below | never |

**Trace integrity** is a semantic check, not a metric: the admitted results are an ordered prefix of the recorded
port response, the recalled slice of `outputSignals()` equals their Signals in order, every admitted reference
maps through the manifest to a `chunkId`, and every attached `MemoryReferenceEvidence` maps to an admitted chunk.
Any violation fails the run.

**`MemoryEmptyContrast`**: the same cycle (same inputs, configuration and seed) runs once with the real port and
once with a port that answers `COMPLETE` with no results.

- **Isolation**: post-recall stages mutate state (for example `FeedbackAdaptationCognitiveStage` changes Node
  state), so each arm starts from a fixture rebuilt independently from the same seed by a generator that derives
  every identifier from the seed (as `DeterministicWorkloadGenerator` does), with a fresh `PrimaryMonad` and
  fresh capability state. Both fixtures must be value-equal before the arms run; no topology object is shared
  between arms, and the order is fixed (real port first).
- **Projection**: the `MEMORY_RECALL` stage itself is excluded, because its real-port output contains the
  recalled Signals by construction. Compared are the ordered list of **complete** stage results after
  `MEMORY_RECALL`, by value equality of every component (output Signals and typed artifacts such as hypotheses,
  evaluations, adaptation decisions, action outcomes and coordination results; a different number of stages
  counts as a difference), and the cycle `outputSignals`. A component without value equality is a failed check;
  the comparison never falls back to identity.

Per query the report records `differs` (boolean), `differsInSignals` and `differsInArtifacts`, the first
differing post-recall stage kind (or none), the output Signal counts of both runs, and `terminationDiffers`
separately, since the recall's own budget use can change the
termination. The aggregate is the count and
fraction of queries with `differs = true`, reported separately for `A` and `U`.

L3 shows that evidence flows through the cycle. It is explicitly **not** a measure of reasoning quality.

## Grounded answer (L4, delivered by #109)

Extension `l4-answer-v1`: minimum requirements. #109 refines them in its own documentation
([refinements of `l4-answer-v1`](../benchmarks/html-knowledge-answers.md#refinements-of-l4-answer-v1)).

L4 scores an explicit, optional answer component, only over queries whose bound recall completed (see
[Completed recalls only](#completed-recalls-only)). An answer is either an abstention or a non-empty list of
claims, each with citations `(chunkId, start, end)` into the normalized chunk text.

### Evidence binding

Every L4 evaluation declares `evidenceLayer` (`B1` or `B2`), that layer's configuration, and `answerK` (one of 1, 3,
5). The **evidence set** is `E(q) = Ret_answerK(q)` of that layer in the same run. Its chunk text is always content
the Store actually returned and that passed source fidelity: for `B1`, B1's returned content; for `B2`, whose
results carry only opaque references, the content returned by the B2c control for the same atoms (B2 = B2c is
already required). Corpus text is never substituted for Store output. Every L4 rule that refers to retrieved
evidence uses `E(q)`: citation fidelity, abstention classification and the input of B3. Evaluations bound to
different layers are separate report rows; evidence sets are never united or intersected.

### Claim judgments and flags

A citation is **eligible** when its `chunkId` is in `E(q)` and its bounds are valid for that chunk's text in `E(q)`.
This single definition is used by citation fidelity, citation groups and claim support.

For each emitted claim, `supported` and `contradicted` are judged as follows. A claim with no eligible citation is
`supported = false` deterministically. For every other claim, the human reviewer records `supported` (an eligible
citation's text entails the claim) and `contradicted` (the claim contradicts the curated evidence of the query),
each with a reason and the reviewer identifier. When two reviewers judge the same run, every disagreement is
resolved by a recorded adjudication.
A `keyClaim` is **matched** by an emitted, supported claim. A **candidate edge** is a pair (supported claim,
`keyClaim`); the deterministic matcher accepts an edge when every token (B0 tokenization) of the `keyClaim` occurs
in the claim. A human reviewer examines **every** candidate edge, not only the accepted ones, and may accept or
reject it; the reviewer's verdict on an edge replaces the matcher's. From the accepted edges, the matching is
computed deterministically: a maximum-cardinality one-to-one matching (one claim never matches two `keyClaims`)
and, among matchings of that size, the one whose edge list, sorted by (`keyClaim` index in curated order, claim
index in emitted order), is lexicographically smallest. The matcher-only matching uses the same rule over the
matcher's accepted edges. The report gives both the
matcher-only and the final counts, plus the number of reviewer overrides, so the two are never conflated.

Per answer the judge also records the flags `anyUnsupportedClaim`, `anyContradictedClaim`, `premiseAccepted`
(`FALSE_PREMISE` queries only; the answer treats the premise as true), `premiseCorrected` (`FALSE_PREMISE`
queries only; a supported claim matches the query's refutation `keyClaim`) and `conflictDisclosed` (`CONFLICTING`
queries only; the answer states both claims of the pair and cites both chunks). Flags are always reported,
independently of the primary status.

### Primary answer status

Each answer gets exactly one primary status: the **first** row, in order, whose condition holds.

| Order | Status | Condition |
| :--- | :--- | :--- |
| abstention 1 | `CORRECT_ABSTENTION` | abstained and `Rel(q) = ∅` |
| abstention 2 | `EVIDENCE_MISS_ABSTENTION` | abstained and `required(q) ⊄ E(q)` (incomplete retrieval, not an answer error) |
| abstention 3 | `WRONG_ABSTENTION` | abstained although every `required` chunk was in `E(q)` |
| answer 1 | `FALSE_PREMISE_ACCEPTED` | `premiseAccepted` |
| answer 2 | `CONTRADICTED` | `anyContradictedClaim` |
| answer 3 | `UNSUPPORTED` | `anyUnsupportedClaim`, **even if the claim is true** |
| answer 4 | `FALSE_PREMISE_CORRECTED` | answerability `FALSE_PREMISE` and `premiseCorrected` |
| answer 5 | `FALSE_PREMISE_UNADDRESSED` | answerability `FALSE_PREMISE` (neither accepts nor corrects the premise) |
| answer 6 | `ANSWERED_UNANSWERABLE` | answerability `NO_ANSWER_IN_CORPUS`, `OUT_OF_DOMAIN` or `BLANK` |
| answer 7 | `CONFLICT_EVIDENCE_MISSING` | answerability `CONFLICTING` and `required(q) ⊄ E(q)` (disclosure cannot be judged without both sources) |
| answer 8 | `CONFLICT_HIDDEN` | answerability `CONFLICTING` and not `conflictDisclosed` |
| answer 9 | `CONFLICT_SURFACED` | answerability `CONFLICTING` and `conflictDisclosed` |
| answer 10 | `SUPPORTED_CORRECT` | at least one `keyClaim` exists and every `keyClaim` is matched |
| answer 11 | `SUPPORTED_PARTIAL` | at least one `keyClaim` matched |
| answer 12 | `SUPPORTED_OFF_TARGET` | otherwise (all claims supported, none matches a `keyClaim`) |

For example, an answer to a conflicting-source query that silently repeats the non-curated claim is
`CONTRADICTED` with `conflictDisclosed = false`. `UNSUPPORTED` exists because an answer can be right by prior
knowledge or word overlap without using the stored evidence. **Grounding is mandatory**: a true but unsupported
claim is not credited.

### Metrics

| Metric | Per-query formula | Undefined when |
| :--- | :--- | :--- |
| `CitationFidelity` | eligible citations / citations | no citations |
| `CitationPrecision` | `M(q) / citations` | no citations |
| `CitationRecall` | `M(q) / gold evidenceSpans` | no gold spans |
| `ClaimSupport` | supported claims / emitted claims | abstention |
| `KeyClaimCoverage` | matched `keyClaims` / `keyClaims`, over answered (not abstained) queries | abstention, or no `keyClaims` |

`M(q)` is the size of a **maximum one-to-one matching** between the answer's citation groups and the query's
gold `evidenceSpans`. Before the matching, the eligible citations of one answer to the same chunk are merged into
**citation groups**: the connected components of the "overlaps in at least one code point" relation, so identical,
nested and overlapping variants form one group. A citation group and a gold span can be matched when they name
the same `chunkId` and some citation of the group overlaps the span in at least one code point. Each group and
each gold span is matched at most once, so neither duplicate nor overlapping citations can earn credit for several
spans; the matching size is unique even when several matchings reach it. Every emitted citation, eligible or not,
counts in the `CitationPrecision` denominator, and the report records `mergedCitations`.
Aggregates follow the [undefined-value rules](#undefined-values-and-aggregation); abstentions are counted by
status, not hidden in the claim metrics.

| Set-level metric | Formula |
| :--- | :--- |
| `AbstentionPrecision` | abstentions with `Rel(q) = ∅` / abstentions; undefined when nothing abstained |
| `AbstentionRecall` | abstentions with `Rel(q) = ∅` / queries with `Rel(q) = ∅`; undefined when there are none |

Judging follows the rules of [Claim judgments and flags](#claim-judgments-and-flags): claim verdicts and every
candidate key-claim edge are reviewed and recorded with the query row. **The judge is never the component under
test**, and no model judges its own answers.

## Baselines

| Id | Baseline | Purpose |
| :--- | :--- | :--- |
| B0 | exact-token overlap over the same normalized chunks (see below) | trivial lexical reference, scalar `O(Q·N)` |
| B1 | Store direct: `MonadaMemory.resonate(query).topK(5).threshold(t)` | raw Store retrieval (L1) |
| B2 | Neuron port: `ResonanceStoreMemoryAdapter.recall` with `maxResults = 5`, adapter threshold `t` and a documented non-oracle codec | Neuron memory port (L2) |
| B2c | codec-direct control for B2 (see [Attributing L1/L2 differences](#attributing-l1l2-differences)) | separates adapter from codec effects |
| B3 | deterministic extractive answer (see below) | answer floor for L4 (#109) |

**Tokens** (B0, B3): maximal runs of code points for which `Character.isLetterOrDigit` is true, lowercased with
`Locale.ROOT`. **B0** scores each chunk by the number of distinct query tokens it contains, drops chunks with
score 0, orders by score descending then `chunkId`, and returns the first 5.

**B3** takes the first chunk of `E(q)` and splits its normalized text into segments: each fenced code block is one
segment; the remaining prose is split after `.`, `?` or `!` followed by whitespace or the end of the text, and at
blank lines; segments are trimmed and empty segments dropped. Each segment is scored by the number of distinct
query tokens it contains. The highest-scoring segment wins, ties going to the earliest segment in source order.
B3 answers with that segment as its single claim, citing its exact span; it abstains when `E(q)` is empty or the
best score is 0.

**Threshold `t`**: B1, B2 and B2c always use the same explicit `t`. The default is `t = 0.0`, which is the current
default of the Store's `MonadaQuery`; the report records `t` and the Store commit. B0 has no threshold.

Fairness: every baseline sees the same chunks, the same normalization, the same `k` and, for B1/B2, the same
persisted snapshot. B0 is a reference, not a search engine to be tuned; it is not extended with stemming,
synonyms or weighting.

## Thresholds and sweeps

- Store defaults are never changed; `t` is passed explicitly to B1, B2 and B2c.
- A threshold sweep is **exploratory**: a fixed, documented grid (for example `0.0` to `0.9` step `0.1`), a
  fixed seed, the same `t` for B1/B2/B2c at every point, and the full curve (`Recall@k`,
  `NoAnswerFalsePositiveRate@k`, `AbstentionPrecision`) reported for every grid point.
- Any parameter chosen by looking at results on a query set (a threshold, codec parameters, a trace key) is
  **tuned on that set**. Every later result on that set with that parameter is labeled `in-sample (tuned)`,
  whatever its version name; renaming or re-versioning the same queries does not make them independent.
- An out-of-sample quality claim requires a **held-out query set** curated under the same label rules, frozen
  with its own `queryHash` **before** the sweep, and not run or inspected while tuning.

## Run conditions

- **Determinism**: two runs with the same corpus, query set, configuration and seed produce identical semantic
  results (outcomes, mapped chunk ids, order and scores). A difference is a failed check.
- **Cross-root comparisons**: atom ids are Store-owned and never assumed stable, so any comparison between results from different
  roots (separate runs, the two #110 arms) compares chunk ids mapped through each root's own manifest, their
  order and their scores, never raw atom ids. Comparisons within one root (for example B2 = B2c) may compare
  atoms directly.
- **Ingestion readback** (a semantic check, not a metric): the public Store API has no export or read by id, so
  every manifest entry is read back through the read-only `resonate`: for each chunk,
  `resonate(<normalized chunk text>).topK(B).threshold(0.0)`, with `B` the corpus size (bounded: at most 40, or
  N ≤ 500 for a scaling family), must return that chunk's atom, and the SHA-256 of its returned content bytes
  must equal the corpus `sha256`. The readback runs in the ingestion process after import and again in the
  restarted process, so a chunk that was lost or corrupted and is never recalled by a query is still detected.
  If the Store later offers a bounded export, a new protocol version prefers it.
- **Post-reopen (restart)**: the Store API has no close operation (`MonadaMemory` exposes none, and
  `ResonanceStoreMemoryAdapter.close()` only marks the adapter unavailable), so a reopen inside one process
  cannot prove that no original handle is alive. The persistence condition therefore uses **separate
  processes**: ingestion runs in one JVM process that exits with status 0 before a new JVM process opens the
  same root and reruns the full query set. Mapped chunk ids, order and scores must equal the first run. The
  report records both processes (process id and exit status). A rerun through a fresh handle inside the same
  process is reported separately as `fresh-handle consistency` and is never presented as persistence evidence.
  Delivered by #106 ([`runHtmlKnowledgePersistence`](../benchmarks/html-knowledge-persistence.md)) over a fixed probe
  set; #107 reruns its judged query set under the same restart condition.
- **Warm / cold**: as defined by the [integration
  evaluation](../benchmarks/resonance-store-integration.md#measurement-method): "cold" is a freshly opened handle in
  an already warmed JVM, "warm" is repeated use of one open handle. Process-cold numbers, if ever reported, are a
  separate, labeled row.
- **Bounds**: `k ≤ 5`, bounded result readback, bounded corpus and query sizes as above; no network, crawler,
  provider call, credential or live-web fetch.

### Required report fields

`protocolVersion` and the version of every extension used, `corpusVersion`, `corpusHash`, `queryVersion`,
`queryHash`, held-out `queryHash` when one exists, `in-sample (tuned)` labels where they apply, `seed`, `k` values,
threshold `t`, codec name, version and parameters, L3 trace key and memory-stage limit, L4 `evidenceLayer` and
`answerK`, the L4 answer component (name, version, parameters, and any external model, provider, prompt template and
decoding settings; `B3` needs only its name and protocol version), matcher-only vs final key-claim counts and
reviewer overrides, reopen mode (`restart` or `fresh-handle`), run mode/profile, JDK vendor and version, OS and
architecture, CPU model, available processors, total physical memory, storage type and file system of the Store
root, `hostId` (hashed), GC and heap settings, Neuron version and commit (`+dirty` when uncommitted), Store checkout
commit (`+dirty` when the Store checkout has uncommitted changes, as `StoreMetadata` already reports), and the
persisted Store manifest (version, encoder, normalizer, dimensions, vector format).

### Resources (L6)

Latency is reported as **median and p95** per row. Allocated bytes per operation are reported separately, only
when measured (the existing `EvaluationMetricsCollector` reports unavailable telemetry as unavailable). Setup,
ingestion, reopen, first recall and warm recall are separate rows. Every row records its sampling, as the
[integration evaluation](../benchmarks/resonance-store-integration.md#measurement-method) already does:
`warmupIterations`, `measurementIterations`, `operationsPerIteration`, `measurementMethod`
(`EvaluationMetricsCollector` wall clock or JMH, with JMH forks and modes when used), `validationInWindow` and
`gcTelemetryScope`. Rows with different sampling are not compared as equals. Latency and allocation never
affect a semantic verdict or the exit status.

## Comparison and regression

A comparison between a baseline configuration and a changed configuration is valid only when `corpusHash`,
`queryHash`, `protocolVersion`, the versions of the extensions involved and `seed` are equal (the only dataset
exception is a [scaling family](#scaling-family-delivered-by-111)). Rows with different seeds are labeled
`non-comparable` and no delta is computed. It reports per-metric deltas and a **per-query diff**: queries whose
outcome changed, with the old and new retrieved chunk ids.

Quality comparisons may deliberately vary the system configuration. **`perf.*` deltas** additionally require equal
JDK vendor and version, OS and architecture, CPU model, available processors, total physical memory, the storage
type and file system of the Store root, a stable `hostId` (a hash, never the plain host name), GC and heap settings,
measurement method, per-row sampling (`warmupIterations`, `measurementIterations`, `operationsPerIteration`) and
`gcTelemetryScope`. When any of these differs, the rows are reported side by side and labeled `non-comparable`,
never as a latency or allocation change.

A change is flagged as a **regression** (to be explained, fixed or documented, not a numeric gate) when:

- a run is not deterministic, or post-reopen (restart) results differ;
- source fidelity, reference fidelity, prefix stability, trace integrity or B2 = B2c fails;
- a query that was `HIT` for a given layer and `k` is no longer `HIT` for the same layer and `k`;
- a non-answerable query moves to `FALSE_EVIDENCE` without a documented configuration change that explains it;
- a required report field is missing.

Known weak queries (for example paraphrases the lexical encoder misses) are listed as concrete follow-up
candidates. They are not removed or relabeled to improve a score.

## Relevance feedback (delivered by #110)

Extension `feedback-v1`: minimum requirements; #110 may refine them in its own documentation. Delivered by `runHtmlKnowledgeFeedback`;
its refinements are listed in [relevance feedback](../benchmarks/html-knowledge-feedback.md#refinements-of-feedback-v1).
The statement below that the Store's public API has no read of stored feedback still holds; #110 complements the
behavioral audit with a storage-level read of each root's log, at the segment its manifest records, through the
Store's public `FileFeedbackStore`.

**Ownership.** Relevance state belongs to Monada Resonance Store. Feedback is written only through the Store's
public `MonadaMemory.feedback(query, atomId, signal)` and persists in the Store root. Neuron holds no relevance
state, and the Neuron adapter stays a read-only `ResonanceMemoryPort`. This is retrieval-rank learning inside the
Store; it is not Neuron cognition, evaluation adaptation or worker-route adaptation.

**Every other experiment records zero feedback.** `MonadaMemoryOptions.defaults()` enables feedback-aware
ranking, so a single event changes later rankings. Outside the #110 treatment arm, the harness never calls
`feedback`, and every L1 to L4 run starts from a freshly imported root.

**Design.**

1. Two independent roots (`control` and `treatment`) are imported from the same corpus with the same options.
   Before any event, both must return identical results for the whole query set, compared by mapped chunk ids,
   order and scores (see [Cross-root comparisons](#run-conditions)); otherwise the run fails.
2. The query set is split deterministically (this split is internal to #110 and is not the tuning held-out set of
   [Thresholds and sweeps](#thresholds-and-sweeps)). Queries are first grouped by their Store feedback key under
   the configured key strategy (with the default `ExactQueryKeyStrategy` the key is the raw query text, and texts
   are unique, so groups are single queries). Within each category, groups are sorted by their smallest
   `queryId` and assigned alternately to `calibration` and `held-out`, starting with `calibration`; a group is
   never split. On the B2 path, a held-out query whose codec-emitted Store query text has the same feedback key
   as any calibration event is marked `keyCollision` and reported as non-comparable.
3. Feedback events are derived **only from calibration queries and their labels**. For each calibration query with
   `Rel(q) ≠ ∅`, the treatment arm's B1 `Ret_5(q)` is inspected once, before any event is recorded: each relevant
   returned chunk gets `POSITIVE` and each non-relevant returned chunk gets `NEGATIVE`, keyed by the exact query
   text, with at most 5 events per query and at most one event per `(queryId, chunkId)`. Events are applied in
   canonical order and every event is recorded (query id, query key, `chunkId`, opaque reference, signal).
   `feedbackHash` uses the [dataset-hash](#dataset-hashes) field encoding: the version string `html-kf-v1`, the
   event count, then the events sorted by `(queryId, chunkId)` (code point order), each as `queryId`, `queryKey`
   (exact query text), `chunkId`, `signal` (enum name, `POSITIVE` or `NEGATIVE`) and `delta` (absent when the
   three-argument `feedback` overload is used). The opaque reference depends on Store-owned atom ids, so it is recorded
   but not hashed. A duplicate event fails the run.
4. Both arms are scored at fixed `k` and `t`, through B1 and then B2 (reported as separate paths), and again
   after a [restart](#run-conditions) of both roots, on three separately labeled populations:
   - **`calibration-with-events`, `in-sample (tuned)`**: calibration queries whose key received events. This is
     the only population on which the Store's key-scoped feedback can change results, so the intervention's
     effect is measured here, always labeled in-sample and never presented as generalization. Events are keyed by
     the raw query text, which only B1 sends; on the B2 path each query row reports how many of its
     codec-emitted keys received events, and a query with none is labeled `not exercised (key mismatch)` and
     excluded from B2 deltas, so a zero B2 delta is never read as feedback failing to reach Neuron;
   - **`calibration-without-events`**: calibration queries that received no event (every query with
     `Rel(q) = ∅`, and answerable queries whose `Ret_5` was empty). Under a key strategy that maps each distinct
     text to its own key, treatment and control must be identical, so this population is a leak check that also
     covers no-answer behavior under feedback;
   - **`held-out`, transfer check**: keys that received no event. Under a key strategy that maps each distinct
     text to its own key (the default `ExactQueryKeyStrategy`), treatment and control **must** be identical, and a
     difference is reported as a cross-key leak defect. Only when the run configures a strategy that maps
     distinct texts to shared keys (recorded in the report) does this population measure transfer.
5. **Persistence audit** (a semantic check, not a quality metric). Held-out queries never received events, so
   they cannot show that events persisted, and the Store's public API has no read of stored feedback. The audit
   therefore replays the **calibration** query keys, which did receive events, through B1:
   - before the restart, at least one calibration key must return different results (mapped chunk ids, order
     or scores) in the treatment arm than in the control arm; otherwise persistence is reported as
     `unverified (no observable effect)`, never as passed;
   - after the restart, each arm must reproduce its own pre-restart calibration results exactly (mapped chunk
     ids, order and scores).

   The audit is a separate check from the in-sample quality scoring above, even though both use calibration keys.

**Scoring.** For each population the report gives paired per-query results: outcome transitions (control →
treatment), deltas of the L1/L2 metrics, top-k churn `|Ret_5^T(q) Δ Ret_5^C(q)|`, abstention changes, and every
regression. Held-out labels and results are never used to choose events, and no event is recorded for a held-out
query key. An absence of in-sample effect is a valid result and is reported as such. Deltas describe this synthetic
dataset only and are not generalized.

## Scaling family (delivered by #111)

Extension `scaling-v1`: minimum requirements; #111 may refine them in its own documentation.

**Size points.** N ∈ {25, 100, 500} chunks, with `k ∈ {1, 3, 5}`. Each size is its own frozen corpus version
`html-kc-v1-scale-<N>` with its own `corpusHash`. With `B` the number of chunks in `html-kc-v1`, it contains the
first `min(N, B)` base chunks in `chunkId` order plus `N − min(N, B)` generated distractors.

**Generated distractors.** A versioned generator (`generatorVersion`, `seed`) expands authored templates into
chunks with ids `chk:synthetic-<nnnn>#00`, source `generated:<generatorVersion>:<seed>`, license `Apache-2.0`, and
the same normalization, hashing and size bounds as authored chunks. The templates are reviewed once, under the
label-integrity rules, as irrelevant to every `html-kq-v1` query; changing a template bumps `generatorVersion`.
The same generator version and seed always produce the same chunks, in the same order.

**Query workload.** `html-kq-v1`, unchanged, at every size. Gold labels stay valid because generated chunks are
irrelevant by construction. Label invariants (spans, digests, relevant sets) are validated once against the
**full base corpus** `html-kc-v1`. When `B > N`, queries with a relevant chunk missing at that point are removed
from that point **before** any point-local check or scoring, reported as non-comparable for that point and listed
by id.

**Cross-size rule.** Results at different sizes form a curve only within one **scaling family**: equal
`protocolVersion`, `queryHash`, base `corpusHash`, `generatorVersion` and `seed`, with each point's own
`corpusHash` recorded. Every point of the curve is computed over the **same query population**: the queries
eligible at every point of the family (a query removed at any point is excluded from the whole curve and listed),
so only the corpus size changes along the curve. Per-point results over each point's own eligible set may be
reported separately, labeled as a series that is not comparable across points. Points are reported as a curve by
N, never as before/after deltas, and `perf.*` points also need the environment equality above.

## Report shape

Each delivered experiment writes a machine-readable JSON report and a concise Markdown report under
`build/reports/benchmarks/` (the existing default), containing the required fields, aggregate metrics per
layer, `k` and category, outcome counts, and one row per query: `queryId`, category, answerability, expected
chunk ids, returned chunk ids with ranks and scores, `tieAtCutoff`, outcome, and diagnostics for misses (plus,
where measured, L3 trace values and L4 status, flags and evidence layer). Each L4 query row also carries its
claim-level artifacts, so a different human judgment can never hide behind identical counts:

- every emitted claim with its text and citations, each citation marked eligible or not;
- the `supported` and `contradicted` verdict of each claim and its source (matcher or reviewer);
- every candidate key-claim edge with the matcher's verdict, plus the matcher-only and final matchings;
- every reviewer verdict on a candidate edge: the edge, `accept` or `reject`, a reviewer identifier and the
  reason.

Undefined values are JSON `null`. The process exits non-zero only on a failed semantic check.

## Decision-quality extension

Issues [#112](https://github.com/stevdrey/monada-neuron/issues/112) and
[#113](https://github.com/stevdrey/monada-neuron/issues/113) use the same corpus to test requirements-based
technical choices. Their protocol is specified in those issues; this protocol only fixes the boundaries. Issue #112
delivers the single-turn extension `decision-v1`
([HTML design decisions](../benchmarks/html-decision.md)) with the case set `html-decision-v1` and the corpus
supplement `html-kd-v1`:

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

Rows marked **delivered** exist. The other commands are **planned**: their names are provisional and each
issue fixes the exact task name in its own documentation.

| Issue | Status | Command (provisional) | Purpose |
| :--- | :--- | :--- | :--- |
| #105 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeIngestion` | import `html-kc-v1` into a temporary Store, write the manifest |
| #106 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgePersistence` | persistence across separate JVMs, reopen and source-level readback through Neuron |
| #107 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeRecall` | L1/L2 judged recall with B0/B1/B2/B2c over `html-kq-v1`, rerun in a reopen JVM and in a fresh-root replica JVM |
| #108 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeEvidenceTrace` | L3 evidence use in a bounded cognitive cycle, with memory-empty, swapped, removed-document, tight-budget and failed-recall controls |
| #109 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeAnswers` | L4 grounded answers with B3, the in-sample B3c variant, abstention, a `TEST_DOUBLE` generator seam and frozen human judgments; `--ask` answers one question, unscored |
| #110 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeFeedback` | [relevance feedback](#relevance-feedback-delivered-by-110) vs a no-feedback control: one control and three key-strategy treatment roots, seed, feedback and restarted reopen JVMs, a feedback-disabled reference |
| #112 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlDecisionBenchmark` | [HTML design decisions](../benchmarks/html-decision.md) under explicit constraints: Store-only, raw-Store-plus-rules, Neuron-plus-rules and no-evidence arms, the host rule table printed, an optional labelled generator arm |
| #111 | **delivered** | `./gradlew :monada-neuron-evaluation:runHtmlKnowledgeScaling` | L6 profile over the [scaling family](#scaling-family-delivered-by-111): every phase a separate row, semantic validity checked outside every timed window |

All planned tasks require the sibling `../monada-resonance-store` checkout, like the existing integration
source set, and do not change `./gradlew test` when it is absent.

## Changelog

- `html-knowledge-protocol-v1` (issue #104): initial core protocol, with the initial extensions
  `l3-evidence-v1`, `l4-answer-v1`, `feedback-v1` and `scaling-v1`.
- Errata, no version change (issue #105): Store atom ids are content-derived and `remember` is idempotent. The
  earlier text described them as random. No definition, formula, label rule or report field changes; see
  [Errata: Store atom ids](#errata-store-atom-ids). The corpus contract is delivered as `html-kc-v1`, together
  with the external snapshot corpus `html-kc-ext-mdn-v1`. Before its first release, `html-kc-v1` was revised in
  review: the curated `alt` chunk now states the narrow omission cases of the HTML Standard, and two other absolute
  claims (labels, `required`, later also the validation-bypass cases and `download`) were qualified. A final sweep,
  backed by an independent sentence-by-sentence check against the HTML Standard and CSP Level 3, qualified the
  remaining absolute statements: base URL resolution, the `<pre>` leading newline, `formmethod`, `button` submit
  behavior, parser-inserted script blocking, CSP nonces, hashes and `'strict-dynamic'`, landmark scope and `dl`
  groups. Its version is unchanged because no result was produced with the
  earlier text.
- No version change (issue #106): the ingestion readback and post-reopen (restart) conditions are implemented by
  `runHtmlKnowledgePersistence`, with a seed JVM and a separate reopen JVM, over a fixed probe set rather than the
  #107 query set. No definition, formula, label rule or report field changes.
- No version change (issue #107). No definition, formula, label rule or report field changes.
  - **Query set.** Delivered as `html-kq-v1`: 32 queries in
    `monada-neuron-evaluation/src/integration/resources/html-kq-v1/queries.tsv`, scored by `runHtmlKnowledgeRecall`
    ([recall](../benchmarks/html-knowledge-recall.md)).
    - Errata before merge: `q:false-premise-03` gained its required refutation keyClaim. The `queryHash` changed from
      `97b29b68…` to `e91b0404…`. No L1/L2 result changed.
  - **Codec and inputs.** The L2 codec is the documented, text-only `token-packing v1`. The phase JVMs read a
    labels-free workload, so only the scorer reads gold labels.
  - **Queried limits.** Every system (B0, B1, B2, B2c) is queried at limits 1, 3 and 5, plus a limit-6 lookahead used
    only for `tieAtCutoff` at k = 5 and never for `Ret_k`. Prefix stability and B2 = B2c are checked at every limit,
    with their full expected comparison count. Scores are compared bit-exactly.
  - **Failures.** An operational or fidelity failure of a measured query leaves a failure marker, so the query gets no
    outcome instead of being scored as an abstention. A non-finite Store score fails its B1 or B2c layer.
  - **Determinism and readback.** A reopen JVM checks restart determinism and repeats the full-corpus readback once, as
    required. A `replica` JVM imports into its own fresh root to check fresh-root determinism.
  - **Report integrity.** Exchanged phase files end with a count line, so a truncated file is rejected. The single
    JSON report carries the verdict and the scored payload. The knowledge runners record the required run-mode and
    environment fields.
  - **Semantic boundary.** Only protocol-required operations are checks:
    - the measured recalls with their fidelity, B2 = B2c and prefix stability;
    - the ingestion of the root under test;
    - the one required restarted-process readback;
    - restart and fresh-root determinism.
  - **Latency diagnostics.** Every other call exists only for latency, never decides `allChecksPassed`, catches its
    own exceptions, and on failure is excluded from its row and recorded in `excludedSamples` (`perf.warmup.failures`
    for the warm-up). These calls are the cold first-recall samples, the warm-up pass, the timed opens, readback
    repetitions 2 to 5 and the scratch timing imports. The observation opens its own handles separately.
  - **Latency rows.** Every latency row reports median and p95 with its sampling fields. Timing scratch roots and the
    replica root sit beside the root under test, on the same storage.
- No version change (issue #108). No definition, formula, label rule or report field changes. `l3-evidence-v1` is
  delivered by `runHtmlKnowledgeEvidenceTrace` ([evidence trace](../benchmarks/html-knowledge-evidence-trace.md)),
  which fixes what the extension left open:
  - the cycle `MEMORY_RECALL -> REASONING -> EVALUATION` with an evaluation-only evidence consumer;
  - the default trace key and memory-stage limit 5;
  - a reference resolver that trusts only chunks read back in the trace process;
  - the host decision, and the controls beyond the mandatory memory-empty contrast.
- No version change (issue #109). No definition, formula, status or report field changes. `l4-answer-v1` is delivered
  by `runHtmlKnowledgeAnswers` ([grounded answers](../benchmarks/html-knowledge-answers.md)), which fixes what the
  extension left open:
  - an exact quote of an eligible citation is `supported` by rule; other claims with an eligible citation need a human
    verdict;
  - the refutation `keyClaim` of a `FALSE_PREMISE` query is its first `keyClaim`;
  - `conflictDisclosed` is `false` by rule when the eligible citations do not cover both chunks of the pair;
  - the components B3, B3c (`in-sample (tuned)`), the conservative B3s (in-sample, designed after review: it abstains
    unless one retrieved sentence holds every content token of the question) and a `TEST_DOUBLE` generator that is
    never judged;
  - the human verdicts `html-l4-judgments-v1`, keyed by the exact answer and frozen by hash.
- No version change (issue #110). No definition, formula, label rule or report field changes. `feedback-v1` is
  delivered by `runHtmlKnowledgeFeedback` ([relevance feedback](../benchmarks/html-knowledge-feedback.md)), keeping
  its event rule, `feedbackHash` (`html-kf-v1`), populations and persistence audit, and refining what it left open:
  - one treatment root per public Store feedback key strategy (exact, normalized, lexically enriched), each
    imported like the control, plus `in-sample (tuned)` surface variants of the calibration queries;
  - the split (`html-kf-split-v1`) groups queries by key under every strategy, so one split serves every arm;
  - a storage-level read-back of each root's feedback log, checked against the plan and across the restart;
  - a feedback-disabled reopen of each treatment root, which must reproduce the control bit for bit;
  - prefix stability of treatment arms after events is a diagnostic, because the Store's feedback-aware candidate
    pool depends on the requested limit.
- No version change (issue #111). No definition, formula, label rule or report field changes. `scaling-v1` is delivered
  by `runHtmlKnowledgeScaling` ([scaling profile](../benchmarks/html-knowledge-scaling.md)), which fixes what the
  extension left open:
  - the generator `html-kd-v1` (default seed 111): authored templates on web topics no `html-kq-v1` query asks about,
    one single-chunk document per distractor, prefix-stable across sizes;
  - the point bounds `CorpusBounds.SCALING` (25 to 500 chunks, at most 512 documents, the authored per-chunk and
    per-document limits); `B` is 33, so `html-kc-v1-scale-25` holds no distractor and removes five queries from the
    curve;
  - in-process measurement: "cold" is a freshly opened handle in a warm JVM, process-cold rows are reported as
    unavailable, and restart persistence stays the evidence of #106 and #107;
  - the generator `html-kd-v1` draws from `java.util.Random`, whose algorithm Java SE specifies, so a generator version
    and seed name the same chunks on every JDK;
  - the cycle runs with memory-stage and reasoning limit `k` at every `k`; L4 rows at scale are scored and rendered by
    the #109 scorer with `html-l4-judgments-v1`, which covers only answers identical to `html-kc-v1` answers, so other
    verdicts are reported missing and judgment completeness is not a check;
  - the Neuron wrapper overhead is reported as `B2` minus the `B2c` Store-side work, never as `B2` minus `B1`;
  - the curve population is fixed by all three size points, even when a run measures a subset, and every timed warm
    row runs that cohort, so cross-size cost compares one workload;
  - a row with any timed sample that differs from the verified result is withheld as invalidated, never published;
  - a corpus whose documents are authored or generated (`generated:<generatorVersion>:<seed>`) from authored
    Apache-2.0 templates is publishable, so scaling reports carry claim text in their per-query answer rows.
- No version change (issue #135). No definition, formula, label rule or report field changes, and no new extension.
  `runHtmlKnowledgeRecallDiagnosis` ([B2 recall diagnosis](../benchmarks/html-knowledge-recall-diagnosis.md)) is a
  research-only diagnostic over `html-knowledge-protocol-v1` and the `scaling-v1` family:
  - it adds the ladder point `html-kc-v1-scale-33` (the complete base, no distractor) so base growth and distractor
    growth are studied separately, and scores the fixed #111 curve cohort at every point;
  - it captures every per-Signal Store hit labels-free, replays the production merge bit-exactly, and replays
    alternative merge rules and an expanded per-token pool offline, all labelled research-only and in-sample;
  - gold labels are read only by its scorer, after the capture; B2 = B2c remains the integrity check of the unchanged
    production path.
- No core version change (issue #112). New extension `decision-v1`, delivered by `runHtmlDecisionBenchmark`
  ([HTML design decisions](../benchmarks/html-decision.md)):
  - `html-kc-v1` is unchanged (hash and 33 chunks). The facts the decision cases need and `html-kc-v1` lacks (module
    scripts, `defer`/`async` on inline scripts, the `iframe` `allow` attribute, isolation versus integration) are an
    authored one-document supplement `html-kd-v1`, imported beside it as the combined corpus `html-kc-v1-with-kd-v1`.
    Results over the combined corpus are not compared with `html-kc-v1` results.
  - The case set `html-decision-v1` (selector-visible: requirement, typed constraints, candidates, contrastive pairs)
    and its gold labels (expected outcome, acceptable and ineligible choices, conditional options, permissions,
    rationale and exact spans) are separate files; only the scorer reads the gold, and its hash is frozen.
  - Decisions come from an evaluation-only host rule baseline, printed in every report, never from Neuron; the
    raw-Store arm with the same rules is the matched reference for the Neuron arm.
  - **Deviation from the boundary above:** the acceptable-option sets are not *independently* curated. They were
    drafted by the author of the rule baseline before any system output was inspected and are reviewed by the
    reviewer of record. Every `decision-v1` report states this; an independent re-labelling (a new gold version) is
    follow-up work, and no result of `decision-v1` may be presented as independently judged.
  - Errata before merge: the two CSP cases now state that `script-src` is `'self'` (own-origin script files allowed,
    no `'unsafe-inline'`), because a policy that only omits `'unsafe-inline'` may also block external sources
    (`'none'`), so the external choice did not follow from the stated constraints. The `restrictiveCsp` constraint,
    the S4 reason and two gold rationales say so; the case hash changed from `888e0c77…` to `ed860dc6…` and the gold
    hash from `a1db13ba…` to `5506175c…`. No version change: no result was published with the earlier text.
  - Choice, rationale and citation validity are scored separately: a correct choice counts as grounded only when
    every claim quotes an eligible span exactly and a citation overlaps a gold span; the host's reason text is a
    per-rule template and is not judged.
