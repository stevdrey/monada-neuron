# HTML Design Decisions (`decision-v1`)

> **Gold version.** Results are scored against `html-decision-v1-gold-v2`, the labels after the independent
> case-by-case adjudication of [#152](https://github.com/stevdrey/monada-neuron/issues/152) by `gpt-6.1-sol`, a model
> from another vendor that did not write the rules. The earlier results against the authored, preliminary
> `html-decision-v1-gold` are kept [for reference](#results-with-the-authored-gold-v1-preliminary-superseded); results
> are comparable only within one gold version. What stays open is listed under [Limits](#limits).

## Question

A factual question ("Which tag runs JavaScript?") tests lookup. A design question ("Should I use `<div>` or `<iframe>`
to embed another HTML document?") needs the requirement, its constraints, the alternatives and evidence for the choice.
When `html-kc-v1` is stored in a real embedded Monada Resonance Store, **can the current knowledge-recall and Neuron
evidence path supply the cited facts that a context-dependent HTML recommendation needs, and does the answer change
exactly when a requirement changes?**

This page covers [#112](https://github.com/stevdrey/monada-neuron/issues/112), Experiment Decision 1/2. It implements
the protocol's [decision-quality extension](../experiments/html-knowledge-v1.md#decision-quality-extension) as
`decision-v1`, reusing the corpus contract, the Store and adapter configuration, the `token-packing v1` codec, the
[evidence-trace](html-knowledge-evidence-trace.md) cycle and the [grounded-answer](html-knowledge-answers.md)
citation model and generator seam.

What decides is stated plainly, and kept apart in every report:

| Layer | Owner | What it does here |
| :--- | :--- | :--- |
| Knowledge retrieval | Monada Resonance Store | returns the top-5 chunks for the case query (arm A) |
| Neuron evidence use | Monada Neuron | a bounded `MEMORY_RECALL -> REASONING -> EVALUATION` cycle through the production memory port admits, carries and selects evidence (arm B+R) |
| Decision rules | **the test host, evaluation-only** | `host-rules-v1` maps the case's typed constraints to a choice and cites the evidence segments it needs; printed verbatim in every report |
| Optional generator | the #109 seam | free text over the Neuron evidence, only when configured (arm C) |

The rules were written by hand. Neuron does not infer them, and a correct decision here is **not** a new reasoning
capability. HTML design advice is not worker/model routing: `RoutingPolicy` is not used.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task. The task is opt-in and never
part of `./gradlew test`.

```bash
./gradlew :monada-neuron-evaluation:runHtmlDecisionBenchmark
```

```bash
./gradlew :monada-neuron-evaluation:runHtmlDecisionBenchmark -PbenchmarkArgs='--generator test-double'
```

```bash
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Decision*'
```

`--output-dir` and `--store-root` work as for [persistence](html-knowledge-persistence.md#running). `--generator`
accepts only `test-double`: arm C then runs `TestDoubleAnswerGenerator`, labelled `TEST_DOUBLE`. Without it, arm C is
reported as not configured. No model, provider, credential or network is used.

Outputs, under `build/reports/benchmarks/` of `monada-neuron-evaluation`:

| File | Content |
| :--- | :--- |
| `html-decision.json` | `allChecksPassed`, the checks, the metadata (including the per-step latency and allocation), `semanticDigest`, and the scored payload under `decisions` (`null` when nothing was scored): limits, the four layers, the rule table, case and gold versions and hashes, aggregates per arm, every pair per arm and every row |
| `html-decision.md` | the same, readable |
| `html-decision-v1-trace.tsv` | every raw row: evidence, Neuron trace, outcome, choice, options, exclusions, constraints used, permissions, claims, verified citations and rule |

`semanticDigest` is the SHA-256 of the trace and the scored payload. Neither holds a time, pid or host value, so two
runs over the same inputs have the same digest; compare it to compare report semantics across runs.

Exit status: `0` all checks passed, `1` a semantic check failed, `2` invalid usage or rejected input. A check fails on
fixture corruption, provenance or metric integrity; no accuracy threshold gates the run.

## How a run works

### Corpus: `html-kc-v1` with the `html-kd-v1` supplement

`html-kc-v1` lacks facts the cases need: module scripts are deferred by default, `defer` and `async` are ignored on an
inline classic script, the `iframe` `allow` attribute grants only listed features, and an `iframe` isolates where
copied markup integrates. Editing `html-kc-v1` would change its hash and the 33-chunk base every earlier result is
pinned to. Instead, one authored document `script-loading-and-permissions.md` (Apache-2.0, references to MDN and the
HTML Standard by URL only, no copied text) is the supplement `html-kd-v1` (4 chunks, bounds `DECISION_SUPPLEMENT`).
The run imports both as one validated corpus `html-kc-v1-with-kd-v1` (37 chunks, `corpusHash` `5df0fbc3…`).

### Cases and gold

`html-decision-v1` has 17 cases and 12 contrastive pairs in three families:

- **embed** (`div` / `iframe`; constraints `separateDocument`, `integrateWithPage`, `powerfulFeatures`): a card of the
  same document, a separately hosted page that must stay isolated, the same with the camera, a separate document that
  must also share styles and the accessibility tree, and a request that must match the page's styles but does not say
  whether the content is a separate document;
- **list** (`ol` / `ul`; `orderMatters`): install steps, ingredients, a ranking, footer links, and an unstated order
  (abstain);
- **script** (inline, external, inline with nonce, inline or external with `defer`, module; `moduleCode`,
  `externalFileAllowed`, `runAfterParse`, `restrictiveCsp`, `reusedAcrossPages`): a one-page snippet, shared code, a
  CSP whose `script-src` is `'self'` (own-origin files allowed, no `'unsafe-inline'`) with and without the ability to
  add a file, run-after-parse with and without a file, and module code.

Every pair holds two cases of one family with equal candidates that differ in exactly one constraint; the loader rejects
anything else.

`cases.tsv` is the only fixture a selector sees. A gold file holds, per case, the expected outcome (`DECIDE`,
`CONDITIONAL` or `ABSTAIN`), acceptable and ineligible choices, the `choice@assumption` options of a conditional
answer, the expected permission grant, a human-written rationale and exact supporting spans
(`chunkId start end sha256`, validated against the corpus). Two gold versions exist; only the scorer reads either.

| Gold version | File | Hash | Provenance |
| :--- | :--- | :--- | :--- |
| `html-decision-v1-gold` (authored, preliminary) | `gold.tsv` | `5506175c…` | drafted by Claude (`draftedBy=claude-opus-5-5`), the author of the rules, before any system output was inspected; reviewed by `stevdrey` |
| `html-decision-v1-gold-v2` (active) | `gold-v2.tsv` | `d9d480d8…` | `gold.tsv` with every change of the independent adjudication applied; `reviewer=gpt-6.1-sol` on every row, `draftedBy=gpt-6.1-sol` on the 11 changed rows |

### Independent adjudication (#152)

`adjudication.tsv` (`html-decision-v1-adjudication`, hash `ff8cd6db…`) holds one verdict per case and item: `expected`,
`acceptable`, `ineligible`, `assumptions`, `permissions`, `rationale` and `evidenceSpans`, 17 × 7 = 119 rows, each
`agree` or `change` with a reason and the reviewer. The reviewer is `gpt-6.1-sol`, recorded with the date 2026-10-22;
it is not the rule author and drafted no label, which the runner checks. It saw only the worksheet that
`./gradlew :monada-neuron-evaluation:htmlDecisionAdjudicationWorksheet` writes (requirement, typed constraints,
candidates, the current label, the text of its spans and every corpus chunk), not the rules, their reasons or any
system output; the file records the SHA-256 of that worksheet and of the template, which match the generator as of
commit `0967b14`. The generator was corrected afterwards (list values in file order instead of sorted, `|` escaped in
table cells), so a worksheet generated now has a different hash; the content the reviewer judged is unchanged. A changed span is written as an
exact quote and resolved mechanically to offsets and digest. `gold-v2.tsv` is rebuilt from `gold.tsv` and the
adjudication, its header comments are the changelog (one line per change with its reason), and the runner checks that
it holds exactly the adjudicated labels. The rules were not changed or tuned to the new labels.

Verdicts: 85 agree, 34 change, in 11 of 17 cases. The list family and `case:embed-card-01` are unchanged. The changes
that alter an outcome or a choice set:

| Case | v1 | v2 |
| :--- | :--- | :--- |
| `case:embed-ambiguous-01` | `ABSTAIN` | `DECIDE div` (`iframe` ineligible): `integrateWithPage=yes` decides, whatever the source document is |
| `case:embed-help-01` | `CONDITIONAL div@integration-preferred \| iframe@isolation-preferred` | `DECIDE div` (`iframe` ineligible): integration is required, so isolation is not an open priority |
| `case:script-snippet-01` | `DECIDE inline \| external` | `DECIDE inline \| external \| external+defer`; only `module` ineligible |
| `case:script-shared-01` | `DECIDE external` | `DECIDE external \| external+defer \| inline`; only `module` ineligible |
| `case:script-csp-01` | `DECIDE external` | `DECIDE external \| external+defer` |
| `case:script-csp-inline-01` | `inline+nonce@nonce-or-hash-available` | `inline+nonce@matching-nonce-authorized-by-csp`: a hash is a different mechanism from the named candidate |
| `case:script-defer-external-01`, `case:script-defer-inline-01`, `case:script-module-01` | | more candidates ineligible (for example plain `external` where a classic head script would run during parsing) |

The other changes rewrite rationales and spans: 7 rationales and 10 span lists (added isolation, default-script and
module-loading passages, narrower quotes). Consequences for the design of the case set:

- In v1 the gold outcome changed in 12 of 12 pairs; in v2 in 9 of 12. `pair:embed-document`, `pair:embed-unknown` and
  `pair:script-reuse` now hold two cases with the same gold outcome, so for them a consistent arm must *not* change
  its answer.
- The embed family no longer has an `ABSTAIN` or `CONDITIONAL` case in v2; the only abstention left is
  `case:list-ambiguous-01`. The case set is unchanged (non-goal of #152); new contrastive cases would be a new case-set
  version.

### Query

Each case has one retrieval query for every arm: the distinct tokens of its candidates and then of its requirement,
without function words, capped at the codec's 32 tokens. It is a fixed function of the case text.

### Arms

| Arm | Evidence the selector sees |
| :--- | :--- |
| A | none: the Store top-5 for the query is recorded and scored for coverage only |
| A+R | the Store top-5 (verified mapping and content), with the same rules: the matched reference for B+R |
| B+R | the chunks the Neuron cycle's `EVALUATION` stage selected, when the host evidence decision is `SUPPORTED`; their content is the B2c control's for the same atoms, which must list exactly the chunks the cycle admitted |
| R0 | nothing: the rules must abstain on every case |
| C | optional: the generator over the B+R evidence; free text, citations scored only |

The whole matrix runs twice and must be identical. Store recall, the Neuron cycle and the selector step are then
measured separately (2 warm-ups, 5 iterations of all 17 cases each). A step is timed only when the verified pass
passed every check and every verified reference row of the step completed. Every timed result must equal the verified
one in full (Store hits with scores; the whole Neuron trace with admitted and selected chunks; the whole
recommendation, over exactly the evidence the verified B+R rows saw), or the row is withheld.

### Host rules (`host-rules-v1`)

Per family the rules are tried in table order. A rule matches when every constraint it lists has the listed value; when
its known constraints match but one is `unknown`, it is *undecided* and the baseline abstains with that constraint's
clarifying question. A matched rule's required cues must each match an evidence segment (all cue tokens present); the
segment's exact text becomes a cited claim. A missing cue abstains with `insufficient evidence`. Exclusions carry a
host-written reason and are cited when their cue is found. Only `E4` (`powerfulFeatures=yes`) grants a permission,
`allow` with exactly the requested features. The report prints all 14 rules.

The rules never read a case id, the requirement text, a gold label, the corpus or the Store.

## Checks

| Check | Fails when |
| :--- | :--- |
| `seed.ingestion.*`, `reopen.persistence.readback` | the import or the readback through a new handle is not exact |
| `decision.completed` | a recall failed, or the B2c content differs from the chunks the Neuron cycle admitted |
| `decision.selectorCitationsVerified` | a rule citation lies outside the evidence its arm saw, or a claim does not quote its span exactly |
| `control.emptyEvidenceAbstains` | the rules decide anything without evidence |
| `decision.deterministic` | the second pass differs |
| `generator.labelled` | (arm C only) a generator row hides its kind or carries a structured decision |
| `perf.matchesVerified` | a timed result differs from the verified pass, a step's verified reference rows did not complete, or nothing could be measured because a verified check failed |
| `decision.scored` | the scorer rejects the row matrix |
| `gold.hashFrozen` | the active gold file (`gold-v2.tsv` when it exists, else `gold.tsv`) differs from its frozen hash |
| `adjudication.hashFrozen` | `adjudication.tsv` differs from its frozen hash |
| `adjudication.complete` | a case and item has no verdict |
| `adjudication.independent` | a reviewer names the rule author or a label drafter in any spelling (compared lowercase, letters and digits only, by containment) |
| `gold.matchesAdjudication` | `gold-v2.tsv` is missing although a label changed, exists although none changed, or differs from `gold.tsv` with the changes applied |

Fixture corruption (a changed span or digest, a missing or orphan gold label, overlapping acceptable and ineligible
choices, a non-candidate choice, a pair differing in zero or two constraints; in the adjudication an unknown case or
item, a repeated row, a no-op change, a quote that is not exactly once in its chunk, or a revision that breaks a gold
invariant) is rejected at load and exits `2`.

## Metrics

Per row: a verdict (`CORRECT_DECISION`, `CORRECT_CONDITIONAL`, `CORRECT_ABSTENTION`, `EVIDENCE_MISS_ABSTENTION` when
the arm saw none of the gold chunks, `WRONG_ABSTENTION`, `WRONG_CHOICE`, `UNLISTED_CHOICE`, `OVERCONFIDENT`, `HEDGED`,
`INCOMPLETE_CONDITIONAL`, `NOT_SCORED`); the ratios `evidenceCoverage`, `citationFidelity`, `citationGoldOverlap`,
`unsupportedClaimRate` and `exclusionsCited`; and the flags `candidateValid`, `constraintsSatisfied`, `contradiction`
(an ineligible choice named, or an acceptable one excluded), `noEvidenceCertainty`, `unrequestedPermission` (any grant other than exactly the expected one) and
`missingPermission`, and `rationaleGrounded`, judged apart from the choice: every claim quotes an eligible span exactly
and a citation overlaps a gold span (an abstention is grounded when it asks a clarifying question or names missing
evidence). A conditional answer must state exactly the gold `choice@assumption` options, no fewer
(`INCOMPLETE_CONDITIONAL`) and no more (`OVERBROAD_CONDITIONAL`), never a single "best tag". The only recognized grant
is `allow=requested-features-only`. Per pair, for A+R and B+R: whether the observed outcome changes exactly when the gold outcome does. R0 abstains by
design and is not paired.

Negative tests build rows with fabricated citations, altered quotes, `defer` on inline code, an excluded acceptable
choice, an unrequested `allow`, certainty without evidence and a constant answer across a pair, and check that each is
flagged; a constraint-ignoring selector run over the real Store scores 0 of 12 consistent pairs.

## Results (gold v2, 2026-10-22)

JDK 27, AMD Ryzen 7 7730U, default heap, temporary Store root; gold `html-decision-v1-gold-v2`; two runs, identical
`semanticDigest` `207c9b6d…`; every check passed, including the four adjudication checks.

| Arm | Correct | Correct, grounded rationale | Evidence-miss abstentions | Wrong abstentions | Wrong choice | Hedged/incomplete conditional | Contradictions | Unrequested permissions | Citation fidelity | Pairs consistent |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A+R | 11/17 | 11 | 1 | 3 | 1 | 1 | 2 | 0 | 1.000 | 9/12 |
| B+R | 7/17 | 7 | 3 | 6 | 0 | 1 | 0 | 0 | 1.000 | 10/12 |
| R0 | 1/17 | 1 | 16 | 0 | 0 | 0 | 0 | 0 | n/a | n/a |

- **The rules now disagree with the independent labels.** With unchanged rules and Store, A+R drops from 14 to 11 and
  B+R from 9 to 7. Most of the drop is label-driven, not retrieval: both arms abstain on `case:embed-ambiguous-01`
  (the rules ask whether the content is a separate document; v2 decides `div`); A+R answers `case:embed-help-01`
  conditionally with `iframe`, now ineligible (its only wrong choice); both arms state
  `inline+nonce@nonce-or-hash-available` on `case:script-csp-inline-01`, while v2 expects the assumption
  `matching-nonce-authorized-by-csp` (an incomplete conditional, since assumption keys are matched exactly).
- **One correct row contradicts the labels.** On `case:script-shared-01` A+R decides `external` (correct) but its rule
  excludes `inline`, which v2 accepts.
- **The Neuron path still loses evidence the Store returns.** B+R abstains with missing evidence where A+R decides on
  the embed cases and on the snippet and shared script cases; the cause is the B2 per-token merge loss of
  [#135](html-knowledge-recall-diagnosis.md), unchanged.
- **Pair consistency is not a quality gain for B+R.** B+R's 10/12 against A+R's 9/12 comes from the three pairs whose
  gold outcome no longer changes: B+R abstains on both cases of all three, which is "consistent" without being
  correct. A+R is inconsistent on all three, including `pair:script-reuse`, where both of its answers are correct but
  differ.
- **R0** abstains everywhere, as required; its only "correct" row is the one remaining `ABSTAIN` case.

Latency and thread allocation per case (median of 5 iterations; GC and RSS deltas are JVM-wide):

| Step | Median per case | Allocated per case |
| :--- | ---: | ---: |
| Store recall (`RecalledEvidence.direct`, k = 5) | 0.38 ms | 152 KB |
| Neuron cycle (`execute` + `decide`, k = 5) | 2.85 ms | 2.46 MB |
| Host rule step (evaluation-only) | 0.08 ms | 69 KB |

The Neuron cycle sends one Store query per query token (up to 32), which accounts for most of its time and allocation.

## Results with the authored gold v1 (preliminary, superseded)

Kept for reference only: scored against `html-decision-v1-gold` before the adjudication; not comparable with the gold
v2 results above. Two runs, identical `semanticDigest` `81a41842…`; every check passed.

| Arm | Correct | Correct, grounded rationale | Evidence-miss abstentions | Wrong abstentions | Wrong choice | Overconfident | Contradictions | Unrequested permissions | Citation fidelity | Pairs consistent |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A+R | 14/17 | 14 | 2 | 1 | 0 | 0 | 0 | 0 | 1.000 | 12/12 |
| B+R | 9/17 | 9 | 7 | 1 | 0 | 0 | 0 | 0 | 1.000 | 7/12 |
| R0 | 2/17 | 2 | 15 | 0 | 0 | 0 | 0 | 0 | n/a | n/a |

- **Retrieval decides the outcome.** The rules made no wrong, overconfident or contradicting choice in any arm, granted
  no unrequested permission and cited only exact spans of evidence they saw; every correct row also has a grounded
  rationale (its citations overlap the gold spans). Every failure is an abstention because a
  required fact was not retrieved.
- **The Neuron path loses evidence the Store returns.** With the same rules, B+R is correct on 9 cases and A+R on 14.
  Every Neuron cycle decided `SUPPORTED` and selected all five admitted chunks, so the loss is in recall, not in
  evidence use: B+R missed the gold chunk of all five embed cases and of the two plain script cases. This is the known
  per-token merge degradation of B2 diagnosed in [#135](html-knowledge-recall-diagnosis.md), now observed on design
  queries; it is not fixed here.
- **Ambiguity is not manufactured.** Both underspecified cases abstain with the right clarifying question in every arm,
  from the undecided-rule rule, independent of evidence.
- **Both arms miss two cases.** `case:list-install-01` (the Store did not return the list chunk for its query) and
  `case:embed-video-01` (no `iframe` chunk in the top 5). `case:script-defer-external-01` abstains although it saw
  part of its evidence: the `defer` chunk from `html-kc-v1` was not retrieved.
- **R0** abstains everywhere, as required; its 2 "correct" rows were the two v1 ABSTAIN cases.

In that run the per-case medians were 0.33 ms (Store), 3.78 ms (Neuron cycle) and 0.10 ms (rules).

## Limits

- The decisions come from hand-written host rules over constraints the case author stated; correctness measures
  whether the evidence path delivers the facts the rules cite, not decision intelligence. A rule table written next to
  the cases can mirror them.
- Cue matching is lexical, not understanding.
- Only A+R versus B+R isolates the Neuron path, and only for these 17 cases; no intelligence gain is claimed.
- **Adjudication scope.** The active gold is independent of the rules by authorship: every item of every label was
  judged by `gpt-6.1-sol`, which did not write the rules and saw neither them nor any system output. What remains
  open: the adjudicator is one model, not a human domain expert and not a panel, so there is no inter-reviewer
  agreement; it judged a draft written by the rule author rather than labelling from scratch, so anchoring on that
  draft is possible; and the case set was written by the same author as the rules, which the adjudication does not
  change.
- Assumption keys of a conditional answer are matched exactly; a renamed key in the gold counts as a missing option
  even when the stated condition means nearly the same.
- The host reason text is a fixed template per rule and is not judged; the rationale is judged only through its cited
  evidence (`rationaleGrounded`).
- The corpus is small and authored; results do not transfer to other corpora. Multi-turn context is out of scope
  (Issue #113).
- Arm C is exercised only with a test double; no model result exists.
