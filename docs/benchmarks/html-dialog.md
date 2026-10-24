# HTML Multi-Turn Follow-Ups (`dialog-v1`)

> **Scope.** Inline labels of this suite are drafted by the author of the context rules and are **not independently
> adjudicated**; labels reused from `html-decision-v1` inherit the [#152](https://github.com/stevdrey/monada-neuron/issues/152)
> adjudication. See [Limits](#limits).

## Question

A user asks "How do I execute JavaScript on a page?" and then "Should it be inline or in another file?". The second
turn is under-specified on its own, and a later "the site now sends a strict CSP" should revise or qualify earlier
advice. **When a host supplies the earlier turns as explicit, immutable context, and every turn queries the real
reopened Store and runs the Neuron evidence path, is the advice consistent, relevant and revised exactly when it
should be — and which component holds the conversation?**

This page covers [#113](https://github.com/stevdrey/monada-neuron/issues/113), Experiment Decision 2/2. It builds on
[HTML design decisions](html-decision.md) (#112): same corpus `html-kc-v1-with-kd-v1`, same Store and adapter
configuration, same Neuron cycle, the unchanged host rules `host-rules-v1` and the same per-turn scorer.

## Who holds conversational state

| Layer | Owner | What it does here |
| :--- | :--- | :--- |
| Conversation context | **the evaluation host** | an explicit, immutable `dialog-context-v1` per turn: the carried user turns with their typed statements, and the advice the arm gave for each |
| Context resolution | **the evaluation host** | `host-context-v1` (`DialogContextAssembler`), hand-written rules printed in every report |
| Knowledge retrieval | Monada Resonance Store | the reopened root is queried on every turn; nothing is written |
| Evidence use | Monada Neuron | one request-scoped `MEMORY_RECALL -> REASONING -> EVALUATION` cycle per turn through the production memory port; nothing is kept between turns |
| Decision | the test host | `host-rules-v1` from #112, unchanged |

Neuron has no conversation memory, chat session or learning here, and this suite adds none: the only state between
turns is the host's map of the advice it recorded, local to one pass. This is the request-scoped, caller-owned
context of [ADR 0009](../adr/0009-bounded-ephemeral-cognitive-context.md) and
[ADR 0023](../adr/0023-request-scoped-host-execution-context.md); the suite is evaluation-only and records no new
architecture decision. `RoutingPolicy` is not used: an HTML choice is not a worker route.

## Running

Requires the sibling `../monada-resonance-store` checkout. Opt-in; never part of `./gradlew test` on its own (the
integration tests run under `./gradlew test` only when the adapter module is present, like every integration test).

```bash
./gradlew :monada-neuron-evaluation:runHtmlDialogBenchmark
```

```bash
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Dialog*'
```

`--output-dir` and `--store-root` work as for [persistence](html-knowledge-persistence.md#running). No model,
provider, credential or network is used.

Outputs, under `build/reports/benchmarks/` of `monada-neuron-evaluation`:

| File | Content |
| :--- | :--- |
| `html-dialog.json` | `allChecksPassed`, the checks, the metadata (trial configuration, bounds, versions, hashes, per-turn queries, per-step latency and allocation), `semanticDigest`, and the scored payload under `dialogs`: limits, state ownership, the context rules, aggregates per arm, ablation deltas, the change table and every row |
| `html-dialog.md` | the same, readable, with a per-turn table, errors and possible follow-ups |
| `html-dialog-v1-trace.tsv` | every raw row: question, carried context with advice, used turns, assembly, resolved constraints with provenance, query, change, then the #112 decision columns |

`semanticDigest` is the SHA-256 of the trace and the scored payload, which hold no time, pid or host value; two runs
over the same inputs give the same digest and a byte-identical trace. Exit status: `0` all checks passed, `1` a
semantic check failed (fixture, provenance, isolation or metric integrity, never an accuracy threshold), `2` invalid
usage or rejected input.

## How a run works

### Dialogs: `html-dialog-v1`

Seven dialogs, 24 turns, at most four turns each (`turns.tsv`, the only fixture the system sees). Each turn has the
user's utterance and what a host would know about it as typed data: the family it names (or none, for an ellipsis),
`set:` statements (new facts) and `change:` statements (explicit revisions) over the #112 constraints, the candidates,
which earlier turns the host carries, the antecedent of an ellipsis, and requirements outside the rubric.

| Dialog | Turns | What it tests |
| :--- | :--- | :--- |
| `script-intro` | intro → "inline or another file?" → reused on every page → strict CSP | clarification, elliptical carry-over, a change that need not revise, a change that narrows the acceptable set |
| `script-dom` | one page, timing unknown → must run after parse → no file allowed → nothing changed | clarifying a missing constraint, a reversal into a conditional answer, a conditional answer that stays conditional |
| `list-order` | install steps → "order means nothing" → "order matters" without a change marker | reversal `ol` → `ul`, contradiction |
| `embed-partner` | product card → partner page → needs the camera → "what about security?" | reversal `div` → `iframe`, a caveat (`allow`) without reversal, a requirement outside the rubric |
| `orphan-ellipsis` | "and if it is external?" as the first turn → a self-contained script turn | insufficient antecedent |
| `topic-switch` | a list turn → an unrelated script turn → its elliptical follow-up | no cross-topic carry-over |
| `lost-context` | help articles → a long restatement → a turn whose antecedent the host did not carry → a long restatement | unseen earlier turn, context budget exhaustion |

### Labels: `html-dialog-v1-gold`

`gold.tsv` is read only by the scorer. 16 turns reuse the adjudicated `html-decision-v1-gold-v2` label of the #112
case whose family, constraints and candidates they resolve to (`goldRef`); the loader assembles every turn with its
full context and rejects a reference that does not resolve to exactly that case. 8 turns (intro, CSP with reuse, an
unknown timing constraint, the contradiction, the unmodeled security question, the orphan ellipsis, the unseen
antecedent and the budget overrun) carry inline labels in the #112 syntax with spans validated against the corpus.

Each label also states:

- `change`: `first`, `revise` (no previously acceptable answer stays acceptable, or the answer moves to or from an
  abstention), `qualify` (same acceptable choices, other options or permissions), `keep` (same label) or `any`
  (overlapping but different acceptable sets). The loader recomputes it from consecutive labels and rejects a
  mismatch. Revision is scored only for `revise` and `qualify`; `keep` and `any` **do not require a change** and are
  credited by correctness alone.
- `withoutContext`: `same` for a self-contained turn, `abstain` for one that is under-specified alone. An elliptical
  turn must be `abstain`, so it is never credited as understood without context.

### Context rules: `host-context-v1`

Applied in order, by `DialogContextAssembler`; a held turn becomes a host abstention, still recorded with its Store
and Neuron evidence, rather than an invented assumption:

1. **C1 version** — any other context version is `REJECTED`.
2. **C2 count** — a turn above 4, or more than 3 carried turns, is `REJECTED`.
3. **C3 scope** — the current turn and every carried turn must belong to the dialog, and carried turns must precede
   the current turn in ascending order, or the context is `REJECTED`: no cross-dialog carry-over, even with an empty
   history.
4. **C4 budget** — above 1024 UTF-8 bytes of carried and current utterances and statements the turn is
   `BUDGET_EXHAUSTED`; nothing is truncated.
5. **C5 antecedent** — an ellipsis whose antecedent was not carried is `MISSING_ANTECEDENT` and asks.
6. **C6 family** — the turn's family, else the latest carried one; a turn naming another family starts afresh; none is
   `NO_FAMILY` and asks. A statement or candidate outside the resolved family (its family was not carried) is
   `REJECTED`.
7. **C7 constraints** — start unknown; apply carried statements then the current ones; `change:` overrides; a `set:`
   on a known different value, or two statements of one constraint in one turn, is `CONTRADICTION` and asks which
   holds.
8. **C8 rubric** — a requirement outside the #112 constraints is `UNSUPPORTED`.
9. **C9 case** — candidates are the turn's, else the latest carried, else the family's; the requirement is the current
   utterance then carried utterances newest first; the query is the #112 query rule over them. Advice is reported,
   never used to decide.

### Arms

| Arm | Context | Evidence | Selector |
| :--- | :--- | :--- | :--- |
| A | full | Store top-5 for the assembled query | none (retrieval only, scored apart) |
| A+R | full | the same Store top-5 | `host-rules-v1` |
| B+R | full | the chunks a SUPPORTED Neuron cycle selected | `host-rules-v1` |
| B+R:no-context | current turn only | its own Neuron cycle | `host-rules-v1` |
| B+R:evidence-removed | full | B+R's evidence minus every chunk of the topics `scripts`, `embedding`, `structure`, `lists` | `host-rules-v1` |

The removed topics are a fixed host mapping from the decision families to corpus topics, not a gold label.

### Checks

`dialog.completed`, `dialog.selectorCitationsVerified`, `context.noLeakage` (every resolved constraint comes from the
current turn or a carried earlier turn of the same dialog; a no-context row uses no earlier turn),
`control.noContextElliptical` (without context every elliptical turn abstains), `control.evidenceRemoved` (the control
saw no decision-topic chunk), `dialog.deterministic` (two passes equal), `perf.matchesVerified`, `dialog.scored`,
`gold.hashFrozen` (`350006a7…`) and `gold.reusedHashFrozen` (`d9d480d8…`), plus the seed and reopen checks.

## Results

Run on 2026-10-23, commit `de3babb` plus this change, AMD Ryzen 7 7730U, JDK 27 (Zulu 27.0.0+35), temporary Store root
on tmpfs. All checks passed; two runs gave the semantic digest `640cc1a6…` (byte-identical traces).

| Arm | Judged | Correct | Conditional | Revision (`revise`/`qualify`) | No change required (`keep`/`any`) | Unrequired revisions | Constraint adherence | Unjustified certainty | Abstention precision / recall | Clarifications asked (precision) | Evidence coverage |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A+R | 24 | 15 | 1/3 | 3/10 | 4/5 | 3 | 0.900 | 0 | 0.500 / 1.000 | 5 (1.000) | 0.563 |
| B+R | 24 | 15 | 2/3 | 2/10 | 4/5 | 1 | 1.000 | 0 | 0.467 / 1.000 | 5 (1.000) | 0.493 |
| B+R:no-context | 24 | 21 (control label) | 0/0 | n/a | n/a | 0 | 1.000 | 0 | 0.857 / 1.000 | 18 (1.000) | 0.288 |
| B+R:evidence-removed | 24 | 7 | 0/3 | n/a | n/a | 0 | n/a | 0 | 0.292 / 1.000 | 5 (1.000) | 0.000 |

Against each turn's own label (the question the dialog asks): A+R 15/24, B+R 15/24, no-context 10/24,
evidence-removed 7/24. Ablation deltas: **context +5** (B+R over no-context), **evidence +8** (B+R over evidence
removed), **Neuron path 0** (B+R over A+R).

Change sensitivity, B+R, expected change per label against the observed change:

| Expected | FIRST | UNCHANGED | QUALIFIED | REVISED |
| :--- | ---: | ---: | ---: | ---: |
| first | 9 | 0 | 0 | 0 |
| revise | 0 | 6 | 0 | 3 |
| qualify | 0 | 1 | 0 | 0 |
| keep | 0 | 3 | 0 | 1 |
| any | 0 | 1 | 0 | 0 |

What the numbers say:

- **The host holds every turn it cannot answer instead of guessing.** The contradiction, the orphan ellipsis, the
  unseen antecedent, the budget overrun and the unmodeled security question are host abstentions in every
  full-context arm (5 of 5), each with its reason and, where one exists, a clarifying question; the evidence-removed
  control abstained on all 24 turns and claimed no support.
- **Context, not Neuron, carries the dialog.** Without context the elliptical turns all abstain, as required, and
  only self-contained turns are answered (10/24); with the host's context, 15/24. That gain is the host rule C6/C7
  carrying typed statements forward, which is context stuffing by design, not reasoning.
- **Revisions fail mostly on retrieval, not on the rules.** Of the 9 B+R `revise` turns, 3 revised and 6 stayed
  unchanged. Of those 6, 3 abstained because the evidence the rule needs was missing (`script-dom#2`,
  `list-order#2`, `embed-partner#2`), and 3 are correct host abstentions after an earlier evidence-miss abstention,
  so there was no advice to revise (`list-order#3`, `embed-partner#4`, `lost-context#3`). The follow-up query is led
  by the follow-up's own words ("The partner page is a video call, so it needs the camera"), and the Store or the
  Neuron cycle no longer returns the chunk the earlier topic needs (arm A coverage drops to 0.000 on
  `embed-partner#3`). With evidence the rules revise correctly: `script-dom#3` turns `external+defer` into
  the conditional `module@module-compatible`, and `script-dom#4` keeps it unchanged where no change is required.
- **Known #112 misses carry over unchanged.** `topic-switch#3` is `INCOMPLETE_CONDITIONAL` exactly as
  `case:script-csp-inline-01` (the rule's assumption key differs from the adjudicated one), and `lost-context#1` is
  `WRONG_CHOICE` for A+R as `case:embed-help-01`.

Measured per turn, in this run: the assembler 0.02 ms and 19 KB allocated; the Neuron cycle 2.4 ms and 3.1 MB
(`EvaluationMetricsCollector`, 2 warm-ups and 5 iterations, thread-allocated bytes; each window validated against the
verified pass).

### Possible follow-ups

- Follow-up query policy: the assembled query puts the current utterance first, so later turns retrieve for the
  follow-up words and lose the earlier topic. A host-side policy that keeps the family's decisive terms in the query,
  measured against this suite, is the obvious next experiment; related to the B2 recall loss of
  [#135](https://github.com/stevdrey/monada-neuron/issues/135).
- Independent adjudication of the 8 inline labels, as [#152](https://github.com/stevdrey/monada-neuron/issues/152) did
  for `html-decision-v1`.

## Limits

- Conversational state is held by the evaluation host. Neuron runs one request-scoped cycle per turn and keeps nothing;
  nothing here is a chat session, conversation memory or learning from the dialog.
- The assembler reads typed statements the dialog author wrote, not the utterance text: carrying a constraint forward
  is a host rule. The no-context control shows how much depends on it.
- Decisions come from the unchanged `host-rules-v1`, with its lexical cues and limits (see
  [HTML design decisions](html-decision.md#limits)); a correct turn is not evidence of a reasoning capability.
- Inline labels are drafted by the author of the assembler and not independently adjudicated; reused labels inherit
  `html-decision-v1-gold-v2`.
- Abstention precision and recall count every abstention. Only an abstention that asks a question is a clarification
  request; the labels do not mark which abstaining turns require a question (a budget overrun or an unsupported
  requirement abstains without one), so clarification precision is reported and clarification recall is not.
- Revision is scored only where the labels require one; a stable answer and a still-acceptable change both pass a
  `keep` or `any` turn.
- Seven short authored dialogs over a small authored corpus; results do not transfer, and no accuracy threshold gates
  CI.
