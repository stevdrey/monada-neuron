# HTML Knowledge Grounded Answers (`l4-answer-v1`)

## Question

After `html-kc-v1` is saved in a real embedded Monada Resonance Store, can a question such as "What is the HTML a
element for?" get a short response that is **tied to what the Store actually returned**, and does the answerer
**abstain** when that evidence does not support an answer?

This page covers Experiment Knowledge 6/8, [#109](https://github.com/stevdrey/monada-neuron/issues/109). It implements
the protocol's [grounded answer (L4)](../experiments/html-knowledge-v1.md#grounded-answer-l4-delivered-by-109)
extension `l4-answer-v1`, over the same corpus, query set, codec, threshold and adapter configuration as
[judged recall](html-knowledge-recall.md) and the [evidence trace](html-knowledge-evidence-trace.md).

Neuron has no general text generator, and this page does not add one. It establishes a transparent, evaluation-only
**floor**:
- deterministic extractive answers that quote retrieved Store text verbatim;
- an optional, provider-neutral generator seam, exercised only by a labelled test double.

Retrieval success and answer faithfulness are reported separately. Stored text is external memory: nothing here trains
model weights or claims learning.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeAnswers
```

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeAnswers -PbenchmarkArgs='--ask "What is the HTML a element for?"'
```

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeAnswers -PbenchmarkArgs='--ask "Who painted the Mona Lisa?"'
```

```bash
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Answer*'
```

Options, exit statuses and output preparation are the same as for
[persistence](html-knowledge-persistence.md#running). With `--store-root`, the directory holds the two Store roots
`full` and `removed`.

`--ask` is an unscored demonstration. It imports the corpus into a temporary root and prints the B1 and B2 evidence.
For each, it prints what B3 and B3c answer: the quoted sentence, plus the cited chunk, its code-point span, its
document and section, and its content hash. When the evidence does not support an answer, it prints
`INSUFFICIENT_EVIDENCE` with the reason. A citation is never a URL. It reads no gold label. If the import fails any
check, it prints the failed checks, answers nothing and exits `1`.

Outputs, under `build/reports/benchmarks/` of `monada-neuron-evaluation`:

| File | Content |
| :--- | :--- |
| `html-knowledge-answers.json` | the single machine-readable report: `allChecksPassed`, the checks, the required metadata, and the scored payload under `answers` (`null` when nothing was scored). The payload holds the `limits`, the judgments' version, hash, missing and stale entries, the components, one aggregate per root, layer, `answerK` and component, and one row per answer with its claim-level artifacts |
| `html-knowledge-answers.md` | the same, readable |
| `html-kq-v1-answers.tsv` | every raw answer row of the answer JVM. Its first line states `corpusHash` and `allChecksPassed`: it is not a verified result on its own |
| `html-l4-judgment-template.tsv` | every claim, flag and key-claim edge that still lacks a human verdict, with the matcher's proposal and the context needed to judge it. After a complete run it holds only its header |

## How a run works

1. The orchestrator writes the labels-free workload (`queryHash`, query ids and texts).
2. A **seed** JVM imports `html-kc-v1` into root `full`, and `html-kc-v1-without-links` (28 chunks) into root
   `removed`, exactly as in [#108](html-knowledge-evidence-trace.md#how-a-run-works).
3. An **answer** JVM reopens both roots and reads every chunk back. For every root, query, evidence layer and
   `answerK` it then:
   - binds `E(q)`;
   - runs every component on the question and `E(q)` alone;
   - checks every citation against the Store-returned text.

   The whole run is executed twice in that JVM, and the two results must be equal.
4. The orchestrator scores the answers with `AnswerMetrics`, the only reader of gold labels and of the judgments.

### Evidence binding

`E(q)` is the first `answerK` (1, 3 or 5) chunks of one layer's limit-5 list, with the **content the Store returned**:

- **`B1`**: `MonadaMemory.resonate(q).topK(5).threshold(0.0)`.
- **`B2`**: the production `ResonanceStoreMemoryAdapter`. It returns opaque references and scores only, so the content
  comes from the B2c control for the same atoms, as the protocol requires. B2 and B2c must agree reference by
  reference, with bit-exact scores. The B2c merge is the shared `HtmlKnowledgeRecall.mergeCodecResults`, not a copy.

Each row records `E(q)` with every chunk's rank and score, and the protocol's `tieAtCutoff` at `answerK`: whether
the scores at ranks `answerK` and `answerK + 1` are bit-exactly equal. At `answerK = 5` the rank-6 score comes from a
separate limit-6 lookahead call, which never enters `E(q)`. That call must extend the limit-5 list under #107's prefix
rule: the same chunks with bit-exact scores, except an equal-score substitution inside a tie that straddles the cutoff.
If it does not, the recall fails, so `tieAtCutoff` never describes a different list than `E(q)`. Every returned atom must map through the root's
manifest, and its content must hash to the corpus `sha256`. Otherwise
the recall fails (`completed`) and that query and layer get no L4 row. Corpus text is never substituted. Evaluations
bound to different layers, roots or `answerK` values are separate report rows.

### Components

| Component | Kind | Rule | Tuning |
| :--- | :--- | :--- | :--- |
| `B3` | extractive baseline | the protocol's B3 (see below); abstains on an empty `E(q)` or a best score of 0 | none |
| `B3c` | extractive baseline | B3, but it also abstains unless the chosen segment contains at least half of the query's distinct tokens | **in-sample (tuned)** |
| `B3s` | extractive, conservative | the first segment, scanning every chunk of `E(q)` in rank order, that contains **every** content token of the question (its distinct tokens minus a fixed list of generic English function words that names no HTML term); otherwise `INSUFFICIENT_EVIDENCE` | **in-sample** (designed after review) |
| `generator-test-double` | `TEST_DOUBLE` | echoes the first segment of the first excerpt, citing it; never judged, never scored for quality | none |

B3 takes the first chunk of `E(q)` and splits its text into segments (`EvidenceSegmenter`):
- each fenced code block is one segment;
- the remaining prose is split after `.`, `?` or `!` followed by whitespace or the end of the text, and at blank lines;
- segments are trimmed, and empty ones are dropped.

Each segment is scored by the number of distinct query tokens it contains, using the protocol tokens. The highest score
wins, and ties go to the earliest segment. The answer is that segment as a single claim, citing its exact code-point
span. A B3 claim is therefore always a verbatim quote of retrieved Store text, and it cannot invent a tag, attribute or
example. The cost is `O(chunkLength)`.

**Why B3c exists, and why it is labelled tuned.** Before any code was written, a dry run of protocol B3 on the recorded
#107 rows showed that it answers every non-blank query. That includes every off-topic and unanswerable question, and
the questions whose supporting chunk was removed, because function words such as "the" always score at least 1. B3
is kept unchanged as the protocol floor. B3c adds one coverage rule, chosen after seeing that behavior, so every B3c
result on `html-kq-v1` is `in-sample (tuned)`. No held-out query set exists yet.

**Why B3s exists.** Review of #109 found that neither floor meets the issue's answer/abstention acceptance cases. B3
answers the novice `<a>` question off target and answers unanswerable and removed-document questions. B3c still answers
the `<a>` question off target. B3s is an explicitly named, conservative, evaluation-only demonstration of safe
abstention:
- it answers only when one retrieved sentence holds every content word of the question;
- otherwise it abstains;
- it uses no threshold, ratio or gold label: its only fixed parameter is a generic function-word list.

It was designed after seeing these failures, so its results on `html-kq-v1` are in-sample like B3c's. B3 and B3c stay
unchanged and are still reported.

**The generator seam.** `GroundedAnswerGenerator` is an evaluation-only port. It takes a question and bounded excerpts
(at most 5, each a prefix of at most 4096 code points of the Store-returned text) and returns a proposed response with
citations. #109 ships only the deterministic `TestDoubleAnswerGenerator`:
- no model, provider, prompt or network;
- its rows are labelled `TEST_DOUBLE`;
- only its citation mechanics are reported (`CitationFidelity`). Every gold-relative value, including
  `CitationPrecision` and `CitationRecall`, stays undefined for it.

A real local or remote model is future opt-in work under the protocol's fixture, cost and privacy rules.

### Judgments

The protocol requires human verdicts for whatever no rule can decide. They live in the frozen resource
`html-l4-judgments-v1/judgments.tsv`: 111 verdicts, each with a reason, a reviewer and a `draftedBy` note. They were
drafted by Claude from the judgment template, then reviewed and approved as drafted by the reviewer of record,
`stevdrey`.

The runner checks the file's SHA-256 against `HtmlKnowledgeAnswerRunner.FROZEN_JUDGMENTS_HASH`
(`48bf215c…7423b`). A judgment is keyed by the query plus the exact claim text and citations, so a changed answer never
inherits an old verdict.

| Item | Who decides |
| :--- | :--- |
| `supported` | **rule**: a claim with an eligible citation that quotes it exactly is supported (`exact-quote`), and a claim with no eligible citation is not. Any other claim needs a human verdict, but no judged component produced one |
| `contradicted` | human, for every claim (53, all `false`) |
| key-claim edges | the matcher proposes (every B0 token of the `keyClaim` occurs in the claim); a human accepts or rejects **every** candidate edge (58: 8 accepted, 3 of them overriding the matcher). A claim whose `supported` verdict is still pending has its edges listed in the same template pass; if it is then ruled unsupported, those edge verdicts are consumed, not reported stale |
| `premiseAccepted` | human, `FALSE_PREMISE` answers only (5, all `false`) |
| `premiseCorrected` | rule: a supported claim matches the first (refutation) `keyClaim` in the final matching |
| `conflictDisclosed` | rule `false` when the eligible citations do not cover both chunks of the pair, otherwise human (no answer cited both) |

The report keeps the matcher-only and the final matchings and counts the reviewer overrides, so a different human
judgment can never hide behind identical totals.

## Checks

Quality numbers are never checks. The run fails only on these:

| Check | Meaning |
| :--- | :--- |
| `seed.<root>.ingestion.*` | both roots were imported and read back with matching bytes |
| `answer.<root>.persistence.readback`, `.noUnmappedAtoms` | the answer JVM read every chunk of both roots back, and every returned atom maps through the manifest |
| `process.restart` | the seed and answer phases ran in separate JVM processes |
| `answer.completed` | every bound recall completed with verified Store content |
| `answer.citationsEligible` | every citation of every component names a chunk of `E(q)` with valid, non-empty bounds. Exact quoting is counted in the detail but is not required: an eligible non-exact claim goes to a human `supported` verdict |
| `answer.deterministic` | a second execution in the answer JVM reproduced every answer row |
| `answer.control.removedNeverCited` | no answer over the root without `doc:links` cites one of its chunks |
| `answer.control.emptyEvidenceAbstains` | every component abstains on every query when `E(q)` is empty |
| `answer.scored` | the snapshot holds exactly one row per root, layer, `answerK`, query and component, and the scorer accepted it |
| `judgment.complete` | every claim, flag and candidate edge of every judged answer has a verdict |
| `judgment.noStaleEntries` | every recorded verdict belongs to an answer of this run |
| `generator.testDoubleUnscored` | no `TEST_DOUBLE` row got a status, a verdict, an edge, a matching, a judgment lookup or a gold-relative metric |
| `judgment.hashFrozen` | the judgments file is the approved one |

## Metrics

Per answer row, as in the protocol:
- the primary status (the first matching row of the status table) and the flags;
- `CitationFidelity`, `CitationPrecision` and `CitationRecall` (via `M(q)` over citation groups);
- `ClaimSupport`;
- `KeyClaimCoverage`, both final and matcher-only.

Per root, layer, `answerK` and component:
- the count of each status;
- the macro means under the undefined-value rules;
- `AbstentionPrecision` and `AbstentionRecall`;
- **retrieval complete**: answerable queries whose `E(q)` holds every required chunk. This is reported next to the
  answer statuses, never merged into them;
- the number of answered rows.

While any judged row of a group is unjudged, the group withholds its metric means and both abstention values (JSON
`null`, "incomplete" in the Markdown), so a partial population is never published as a number.

## Results (2026-10-21)

Measured on Neuron `2744995` (clean tree) and Store `3391195` (clean checkout), with Azul OpenJDK 27 on Linux, an AMD
Ryzen 7 7730U and Store roots on `tmpfs`. Each run used one seed JVM and one answer JVM. Two full runs on that commit
gave byte-identical `answers` payloads, with all 21 checks passing and no row unscored or unjudged.

The table covers the full root at `answerK = 5`:

| Layer | Component | Retrieval complete | `SUPPORTED_CORRECT` | `SUPPORTED_PARTIAL` | `SUPPORTED_OFF_TARGET` | `FALSE_PREMISE_CORRECTED` / `UNADDRESSED` | `CONFLICT_HIDDEN` / `EVIDENCE_MISSING` | `ANSWERED_UNANSWERABLE` | Abstentions: correct / evidence miss / wrong | KeyClaimCoverage | AbstentionPrecision | AbstentionRecall |
| :--- | :--- | ---: | ---: | ---: | ---: | :--- | :--- | ---: | :--- | ---: | ---: | ---: |
| B1 | B3 | 17/22 | 4 | 3 | 11 | 1 / 2 | 1 / 1 | 7 | 2 / 0 / 0 | 0.261 | 1.000 | 0.200 |
| B1 | B3c | 17/22 | 2 | 0 | 1 | 0 / 0 | 0 / 0 | 0 | 10 / 5 / 14 | 0.667 | 0.345 | 1.000 |
| B2 | B3 | 11/22 | 0 | 0 | 17 | 0 / 3 | 0 / 2 | 7 | 2 / 0 / 1 | 0.000 | 0.667 | 0.200 |
| B2 | B3c | 11/22 | 0 | 0 | 2 | 0 / 0 | 0 / 0 | 0 | 10 / 10 / 10 | 0.000 | 0.333 | 1.000 |
| B1 | B3s | 17/22 | 2 | 0 | 0 | 0 / 0 | 0 / 0 | 0 | 10 / 5 / 15 | 1.000 | 0.333 | 1.000 |
| B2 | B3s | 11/22 | 2 | 0 | 0 | 0 / 0 | 0 / 0 | 0 | 10 / 11 / 9 | 1.000 | 0.333 | 1.000 |

`ClaimSupport` and `CitationFidelity` are 1.000 in every row: every claim is an eligible exact quote. No answer is
`UNSUPPORTED`, `CONTRADICTED` or `FALSE_PREMISE_ACCEPTED`. B3 answers do not depend on `answerK`, because B3 uses only
the first chunk. A larger `answerK` changes only whether retrieval counts as complete, which can turn an evidence-miss
abstention into a wrong one: at `answerK = 1`, B3c on B1 has 11 evidence-miss and 8 wrong abstentions.

The generator test double cites eligibly in every row (`CitationFidelity` 1.000). Its answers are not judged.
`tieAtCutoff` is `false` in every row: no evidence set ends inside a tie group, as #107 found for B1 and B2.

Answer latency, a diagnostic only, was measured as one answer over `E(q)` of B1 at `answerK = 5` on the full root, 32
samples, with recall excluded. Medians: B3 42 µs, B3c 37 µs, B3s 122 µs (it scans every chunk of `E(q)`), test double 25 µs.

Observations, not tuned away:

- **A cited extract only from actual hits.** Every citation names a chunk the Store returned in `E(q)`, with its exact
  span and content hash. The novice question "What is the HTML a element for?" is answered from `chk:images-and-media#01`
  (B1's first hit, about `alt` text), not from `chk:links#00`, which B1 ranks third. The quote is verbatim and grounded,
  but `SUPPORTED_OFF_TARGET`. The floor cannot do better than the first retrieved chunk.
- **Retrieval bounds the answer.** B3 on B1 is fully correct for 4 queries (`exact-term-03`, `-04`, `-05` and
  `paraphrase-02`), partial for 3, and corrects one false premise (`false-premise-01`). On B2, the token-by-token
  recall rarely ranks the relevant chunk first, so no B3 answer is correct.
- **Protocol B3 does not abstain.** It answers all 30 non-blank queries, including the 8 non-blank unanswerable ones
  (`ANSWERED_UNANSWERABLE` 7, plus `false-premise-03`), so its `AbstentionRecall` is 0.200: only the two blank queries.
- **B3c abstains safely but too often.** It abstains on every unanswerable query (`AbstentionRecall` 1.000), but also
  on 14 answerable queries whose required chunks were all retrieved (`WRONG_ABSTENTION`). It answers only 3 queries,
  2 of them correctly. Lexical coverage is a poor proxy for support.
- **Removing the supporting document.**
  - On the `removed` root, no answer cites a `doc:links` chunk.
  - The four queries whose relevant chunks are all in `doc:links` cannot be answered correctly: at B1 `answerK = 5`,
    `multi-evidence-02` drops from `SUPPORTED_PARTIAL` to `SUPPORTED_OFF_TARGET`.
  - B3 still answers all four from other chunks. B3c abstains on three of them (`EVIDENCE_MISS_ABSTENTION`) but
    answers `exact-term-01` off target, as it does on the full root. Removal alone does not force abstention: only
    the coverage rule does, and not always.
- **B3s meets the acceptance cases by abstaining.**
  - **The `<a>` question:** B3s abstains, because no retrieved sentence holds both "HTML" and "element", on every
    root, layer and `answerK`.
  - **Unanswerable questions:** it abstains on every one (`AbstentionRecall` 1.000).
  - **Removed document:** it abstains on all four queries whose relevant chunks were removed with `doc:links`.
  - **What it answers:** only `exact-term-03` (defer) and `exact-term-05` (sandbox), both `SUPPORTED_CORRECT`, on
    both layers and roots at `answerK = 5`. Scanning past the first chunk is what lets it answer them from B2.
  - **The cost:** 15 wrong abstentions on B1 at `answerK = 5`. Safe abstention with lexical evidence is cheap only
    because it rarely answers. A relevant answer to the `<a>` question needs more than lexical matching.
- **Conflicts are not reconciled.** No answer cites both chunks of the conflicting-source pair, so none can disclose
  the conflict. The answers are `CONFLICT_HIDDEN` when both chunks were retrieved, and `CONFLICT_EVIDENCE_MISSING`
  otherwise.
- **The reviewer overrode the matcher three times**, each time accepting a quote that states the key claim in other
  words (`exact-term-03`, `false-premise-01`, `multi-evidence-01`). Matcher-only and final coverage are both in the
  report.

## Limits

- B3s demonstrates safe abstention, not answering ability. Its function-word list was fixed after review, and its
  results here are in-sample.
- B3 and B3c are lexical floors. They quote one sentence of the first retrieved chunk. They never combine chunks,
  never rephrase, and do not understand the question.
- B3c's rule is in-sample (tuned), and there is no held-out query set. A threshold or rule chosen on these 32 queries
  says nothing about others.
- The human verdicts were drafted by Claude and approved by one reviewer, with no second reviewer and no
  adjudication. The judge is never the component under test.
- `TEST_DOUBLE` rows test the seam and the citation checks, not model quality. No model was run.
- A supported claim is grounded in the cited Store text. That makes it faithful to the evidence, not necessarily true,
  and a true claim without an eligible citation is never credited.
- Latency is a single-machine diagnostic and never affects a verdict.

## Refinements of `l4-answer-v1`

No definition, formula or status of the extension changed, so its version is unchanged. This page fixes what the
extension leaves to #109:

- **Support rule.** `supported` is decided mechanically for an exact quote of an eligible citation (`exact-quote`). A
  human verdict is required only for a non-exact claim with an eligible citation.
- **Refutation key claim.** The refutation `keyClaim` of a `FALSE_PREMISE` query is its first `keyClaim`. This holds
  for all three such queries of `html-kq-v1`.
- **Conflict disclosure.** `conflictDisclosed` is `false` by rule when the eligible citations do not cover both
  chunks of the pair.
- **Retrieval in every row.** Each row keeps `E(q)` with ranks and scores and its `tieAtCutoff`, from a limit-6
  lookahead at `answerK = 5` that must extend the limit-5 list.
- **Citations and completeness.** A citation must cover at least one code point. Eligibility, not exact quoting, is
  the semantic check. The scored snapshot must be the complete row matrix.
- **Components and layers.** The components are B3, B3c (in-sample, tuned), the conservative B3s (in-sample,
  designed after review) and the `TEST_DOUBLE` generator.
  `TEST_DOUBLE` rows are never judged and get no status. Both `B1` and `B2` are evaluated at `answerK` 1, 3 and 5,
  on the full root and on the root without `doc:links`.
- **Judgments.** They are kept in `html-l4-judgments-v1`, keyed by the exact answer and frozen by hash. A missing
  verdict leaves the row unjudged and fails `judgment.complete`, and an unused verdict fails
  `judgment.noStaleEntries`.
- **Task name.** `runHtmlKnowledgeAnswers`, with its `--ask` demonstration.
