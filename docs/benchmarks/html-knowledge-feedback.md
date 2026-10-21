# HTML Knowledge Relevance Feedback (`feedback-v1`)

## Question

Monada Resonance Store already accepts opt-in relevance feedback: `MonadaMemory.feedback(query, atomId, POSITIVE |
NEGATIVE)` records a query-scoped score adjustment in the Store root. Does that make later retrieval better than a
Store that never received feedback? Specifically:

- does it improve the same query when it is asked again?
- does it carry over to a re-asked query that differs only in surface form?
- does it carry over to queries that never received feedback?
- does it reach Neuron's memory port?

And is the feedback really persisted, unchanged, across a restart?

This page covers Experiment Knowledge 7/8, [#110](https://github.com/stevdrey/monada-neuron/issues/110). It implements
the protocol extension [`feedback-v1`](../experiments/html-knowledge-v1.md#relevance-feedback-delivered-by-110) over
`html-kc-v1` and `html-kq-v1`, and refines it as listed under [Refinements](#refinements-of-feedback-v1).

## What this is, and what it is not

Four different things in this repository can be called "feedback" or "adaptation". This experiment measures only the
first one.

| Mechanism | Owner | Touched here |
| :--- | :--- | :--- |
| **Retrieval-rank feedback**: query-scoped score adjustments persisted in a Store root and applied by `FeedbackAwareResonanceIndex` | Monada Resonance Store | **measured** |
| Cognitive adaptation: `FeedbackAdaptationCognitiveStage`, the `runFeedbackLoop` evaluation | Monada Neuron core | no |
| Evaluation adaptation: thresholds or parameters chosen on a query set | evaluation harnesses | no; nothing is tuned |
| Worker-route adaptation: `RoutingStateStore` learning from validated worker outcomes ([ADR 0029](../adr/0029-routing-feedback-credit-assignment.md), [ADR 0030](../adr/0030-caller-owned-adaptive-routing-state.md)) | Monada Neuron routing, fed by Forge worker outcomes | no |

Retrieval-rank feedback stores and replays score adjustments. It does not store new facts and does not train model
weights. Its effect on a re-asked query is **in-sample**: the events were chosen with that query's own labels, so a
better ranking for it shows that the mechanism acts and persists, not that the system learned anything that
generalizes. No answer, accepted claim or Q&A result produces feedback. Neuron holds no relevance state, and the
adapter stays a read-only `ResonanceMemoryPort`. Store and Forge are unchanged.

## Running

Requires the sibling `../monada-resonance-store` checkout, like every integration task. CI runs the tests in the
adapter job as part of `:monada-neuron-evaluation:integrationTest`.

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeFeedback
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeFeedback -PbenchmarkArgs="--store-root /tmp/html-kq-feedback"
./gradlew :monada-neuron-evaluation:integrationTest --tests 'monada.neuron.evaluation.knowledge.*Feedback*'
```

Options, exit statuses and output preparation are the same as for [persistence](html-knowledge-persistence.md#running).
With `--store-root`, the four arm roots are kept under that directory, which must be absent or empty. Outputs, under
`build/reports/benchmarks/` of `monada-neuron-evaluation`:

| File | Content |
| :--- | :--- |
| `html-knowledge-feedback.json` | The single machine-readable report: `allChecksPassed`, the checks, metadata and environment. The scored payload is under `feedback` (`null` when nothing was scored). It holds the hashes, the arms and their persisted logs, the split, every event, the comparisons, every per-query pair, the diagnostics, the follow-up candidates and the `scope` statement. |
| `html-knowledge-feedback.md` | The same report in readable form. The direct Store and the Neuron adapter paths are separate sections. |
| `html-kq-v1-feedback-rows.tsv` | Raw scored rows from the restarted process: limit-5 B1 and B2 results of every arm, with feedback-aware ranking on and off, and hexadecimal scores. The first line states the hashes and `allChecksPassed`. |
| `html-kq-v1-feedback-events.tsv` | Every stored event of every arm: query, stored query key, chunk id, opaque reference, signal and hexadecimal delta. It contains no atom ids and no timestamps. |
| `html-kq-v1-feedback-split.tsv` | One row per query: category, side and key group. |

## How a run works

### Arms

There are four independent roots, each a fresh import of the same corpus with default options:

| Arm | Key strategy | Events |
| :--- | :--- | :--- |
| `control` | `ExactQueryKeyStrategy` (the default) | none |
| `treatment-exact` | `ExactQueryKeyStrategy`: the raw query text | the plan |
| `treatment-normalized` | `NormalizedQueryKeyStrategy`: the normalized text | the plan |
| `treatment-enriched` | `LexicallyEnrichedQueryKeyStrategy`: sorted normalized and expanded terms | the plan |

The Store records each event under the key computed when it is written. When it ranks a query, it looks events up by
that query's key. Each strategy therefore needs its own root, with the events written through it.

All option sets derive from one `MonadaMemoryOptions.defaults()` and differ only in two ranking-side settings: the
key strategy and `feedbackAwareRanking`. The persisted encoding profile records neither. The check
`profile.strategyNeutral` opens every root with every option set to show this.

### Split (`html-kf-split-v1`)

The split reads only query id, category and text, never a label. It works in two steps:

1. **Group.** Queries whose effective keys are equal under any of the three strategies form one group,
   transitively. A blank query is a group of its own.
2. **Assign.** Within each category, groups are sorted by their smallest query id and assigned alternately to
   calibration and held-out, starting with calibration. A group is never split.

`split.valid` requires every category with two or more queries to have queries on both sides. With `html-kq-v1` no key
groups merge, so the result is 19 calibration and 13 held-out queries, covering every category.

### Events (`html-kf-v1`)

Events come only from calibration queries and their labels. For each calibration query with a non-empty relevant set
and a non-blank text, the B1 `Ret_5` of `treatment-exact` before any event is inspected once:

- each relevant returned chunk gets `POSITIVE`;
- each non-relevant returned chunk gets `NEGATIVE`;
- relevant chunks that were not returned get nothing.

`FeedbackAssigner`, together with `RecallMetrics`, is the only reader of labels. It rejects any held-out query.
`feedback.planDerived` also checks that no event key equals a held-out query's key under any strategy.

Events are applied in canonical `(queryId, chunkId)` order through the three-argument `feedback` overload, which uses
the Store default deltas of ±0.05. The bounds are 5 events per query and 80 in total. `feedbackHash` follows the
protocol definition exactly.

### Surface variants (`feedback-variants-v1`)

Each non-blank calibration query gets three variants:

- `case`: upper case;
- `spacing`: every space doubled;
- `punctuation`: the final `?` removed, or added when there is none.

That makes 54 variants. A variant is scored with its source query's labels and is always `in-sample (tuned)`.
`variants.keyPreserving` requires each variant to keep its normalized and lexically enriched keys and to change its
exact key, and every workload text to be distinct.

### Phases

Each phase runs in its own JVM. The phase JVMs read only the labels-free workload (86 texts: the 32 queries and the
54 variants) and the labels-free plan.

1. **`seed`** (before any event):
   - import every arm;
   - check that each feedback log exists and is empty;
   - observe every arm through B0, B1, B2 and B2c at limits 1, 3, 5 and the lookahead 6, each with its own options;
   - check `profile.strategyNeutral`.
2. **Orchestrator**, before any event:
   - `arms.preFeedbackEqual`: all four seed snapshots must be identical (chunk ids, order, bit-exact scores);
   - `split.valid` and `variants.keyPreserving`;
   - derive the plan (`feedback.planDerived`).

   If any of these fails, no event is recorded.
3. **`feedback`**:
   - record the plan in each treatment arm;
   - read every arm's feedback log back at the storage level (see below);
   - observe every arm again, before any restart.
4. **`reopen`** (a restarted process):
   - read every chunk back (`persistence.readback`);
   - read every feedback log back;
   - observe every arm with feedback-aware ranking on, then every arm with it off.

**Reading the log back.** `MonadaMemory` has no public read of stored feedback. The harness therefore reads the root's
log through the Store's public `FileFeedbackStore`, after checking that the log exists, because that constructor
creates a missing log. Atom ids are mapped to chunk ids and opaque references through the arm's own manifest, and
creation times are dropped.

The protocol's path order is followed per query: B1 and B2c are recorded before B2.

## Checks

The per-phase checks are the recall checks of [#107](html-knowledge-recall.md#checks), prefixed with the phase and
the arm. The added checks are:

| Check | Meaning |
| :--- | :--- |
| `seed.<arm>.feedback.logEmpty`, `feedback.control.feedback.none` | Before any event, every log is empty. After the treatment arms received theirs, the control's log is still empty. |
| `seed.profile.strategyNeutral` | 32 opens: every root with every strategy, feedback-aware ranking on and off, matches the persisted profile. |
| `arms.preFeedbackEqual` | Equal content and equal pre-feedback rankings in all arms. |
| `split.valid`, `variants.keyPreserving`, `feedback.planDerived` | See above. |
| `feedback.<arm>.feedback.applied` | Every planned `feedback` call returned. |
| `feedback.persisted.<arm>` | The stored log holds exactly the plan, in order. Each event has the arm's computed key, the planned chunk and signal, and the default delta, compared bit for bit. |
| `feedback.persistenceAudit.<arm>` | Before the restart, at least one calibration query with events returns a different B1 result than the control. Without a difference the result is `unverified (no observable effect)`, which fails. |
| `feedback.logUnchangedAcrossRestart.<arm>` | The restarted process reads the same events and the same log bytes (SHA-256). |
| `determinism.restartEqual.<arm>` | The restarted process reproduces every row of the feedback phase, so feedback-adjusted rankings survive the restart. |
| `determinism.controlStable` | The control is identical in all three phases. |
| `feedback.disabledEqualsControl.<arm>` | With feedback-aware ranking disabled, the treatment root reproduces the control bit for bit, for every query and every layer. This includes no-answer, out-of-domain and blank queries. |
| `feedback.heldOutUnchanged.<arm>`, `feedback.calibrationWithoutEventsUnchanged.<arm>` | Leak checks: these queries are identical in the treatment and the control in every layer. A B2 or B2c row whose codec token shares an event key is a `keyCollision`; it is reported and left out of the comparison. |
| `process.restart` | Four distinct processes (three phases and the orchestrator), and every phase finished. |

**Prefix stability on treatment arms is a diagnostic, not a check.** With a positive adjustment, the Store widens its
candidate pool to `topK*4`, so the pool depends on the requested limit. A limit-1 list therefore need not be the
prefix of the limit-5 list. The control and every feedback-disabled observation keep the check.

## Metrics

Each treatment arm is paired with the control, both taken from the restarted process. The comparison is made for each
path (direct Store B1 and Neuron adapter B2) and each population.

**Populations:**

| Population | Role |
| :--- | :--- |
| `calibration-with-events` | `in-sample (tuned)`: the re-asked query. This is the only population whose keys received events. |
| `calibration-without-events` | Leak check. |
| `held-out` | Transfer check. |
| `key-variant/<transform>` | `in-sample (tuned)`: variants of queries with events. |

**Per query**, the report gives:

- the control → treatment outcome transition at k = 5;
- Δ`Recall@5`, Δ`Precision@5` and Δ`MRR@5`;
- churn `|Ret_5^T Δ Ret_5^C|` and the Jaccard distance;
- whether the lists changed only in order, or only in scores;
- each relevant chunk's rank in the control and in the treatment (6 means absent);
- how many Store query texts of the path have a key that received events (`exercisedKeys`).

**Per population**, the report gives means with `nDefined`, the transitions, the number of changed queries, mean churn,
the no-answer false-positive rate and abstention recall in each arm, and every regression and improvement.

A **regression** is any of:

- a lost `HIT`;
- `CORRECT_ABSTENTION` → `FALSE_EVIDENCE`;
- a lower `Recall@5` or `MRR@5`.

Every regression is listed; none is a gate.

**How B2 is counted.** B2 sends one Store query per codec token, so its keys are token keys. A B2 query in an event
population whose token keys received no event is `not exercised (key mismatch)` and excluded from the deltas. A zero B2
delta is therefore never read as "feedback does not reach Neuron".

## Results (2026-10-21)

Clean run on JDK 27, AMD Ryzen 7 7730U, Store checkout `74b5be3`:

- `feedbackHash ed7ac05b…`
- `splitHash 6e50ab9e…`
- `variantHash 63baabb1…`
- `allChecksPassed: true`

The plan has 65 events (14 `POSITIVE`, 51 `NEGATIVE`) on 13 calibration queries. Each treatment log holds 65 events
of about 18 KB.

### Direct Store (B1)

| Population (per treatment arm) | exact | normalized | lexically enriched |
| :--- | :--- | :--- | :--- |
| `calibration-with-events` (13) | 13 changed; ΔRecall@5 +0.038, ΔPrecision@5 +0.015, ΔMRR@5 +0.164; 4 improved, 0 regressed | same as exact | same as exact |
| `key-variant/*` (13 each) | 0 changed | same effect as the re-asked query | same effect as the re-asked query |
| `calibration-without-events` (6) | 0 changed | 0 changed | 0 changed |
| `held-out` (13) | 0 changed: **no effect** | 0 changed: **no effect** | 0 changed: **no effect** |

The four in-sample improvements are:

| Query | Change |
| :--- | :--- |
| `q:conflicting-source-01` | `PARTIAL` → `HIT`; Recall@5 0.5 → 1.0, MRR@5 0.2 → 1.0 |
| `q:exact-term-01` | MRR@5 0.333 → 1.0 |
| `q:multi-evidence-03` | MRR@5 0.5 → 1.0 |
| `q:distractor-01` | MRR@5 0.333 → 0.5 |

On the other calibration queries, the churn comes from demoted non-relevant chunks. The two paraphrase misses stay
misses: no relevant chunk was returned, so there was nothing to promote.

No regression, and no change to no-answer behavior, occurred in any comparable pair.

### Neuron adapter (B2)

Under every strategy, 0 of the 52 B2 queries in event populations send a codec token whose key received events. Every
such query is `not exercised (key mismatch)`. The held-out and without-events populations are identical to the
control, and there is no `keyCollision`.

So with the token-packing codec, the Store's query-scoped feedback cannot reach the Neuron path. That is a property of
how the keys line up. It is not evidence that the adapter drops feedback.

### Persistence and reference

Every feedback log and every arm's results are byte- and bit-identical across the restart. With feedback-aware ranking
disabled, each treatment root reproduces the control exactly, including every no-answer, out-of-domain and blank
query.

### Cost

Exploratory only, never a verdict.

| Operation | Median | p95 |
| :--- | ---: | ---: |
| One `feedback` call (append) | 0.11 ms to 0.20 ms | |
| B1 limit-5 recall, restarted process, `control` | 0.16 ms | 0.28 ms |
| B1 limit-5 recall, restarted process, `treatment-exact` | 0.27 ms | 0.39 ms |
| B1 limit-5 recall, restarted process, feedback ranking disabled | 0.08 ms | |

The phase JVMs took about 4.4 s, 7.7 s and 10.2 s.

The difference between the control and the disabled run is the cost of re-reading the (empty) feedback log on every
query. The treatment pays for the 65-event log on top of that.

## Diagnostics and follow-up candidates

Each report lists its own follow-up candidates. None is tuned away or filed automatically. In this run:

- **Store: prefix stability.** Feedback-aware ranking is not prefix-stable across `topK`. In every treatment arm, for
  example, `q:conflicting-source-01` returns a different rank 1 at limit 1 than at limit 5.
- **Store: exact key.** `ExactQueryKeyStrategy`, the default, does not carry feedback to a re-asked query that
  differs only in case, spacing or final punctuation. The normalized and lexically enriched strategies do. All 54
  variants have exactly their source's base ranking (`variants.baseRankingEqualsSource`), so the difference comes from
  the key alone.
- **Neuron: codec keys.** The per-token query keys of the B2 codec never match a query-scoped feedback key. Store
  feedback can affect Neuron recall only through a codec or key strategy that lines those keys up.
- **Store, from source review:**
  - every `execute()` re-reads the whole feedback log;
  - there is no public read of stored feedback;
  - the `FileFeedbackStore` constructor creates a missing log.

## Limits

- **Small sample.** There are 13 calibration queries with events and 13 held-out queries, from one curated
  synthetic-scale corpus. The deltas describe this dataset only, and the in-sample deltas are not evidence of
  generalization.
- **One event rule.** The protocol's rule is used: one event per returned chunk, with the default deltas. Other
  deltas, repeated events and events on unreturned relevant chunks were not explored. Choosing among them on these
  queries would be tuning.
- **Held-out transfer is zero by construction** for distinct texts under these key strategies. The only open question
  was a hidden cross-key leak, and none occurred.
- **No new facts.** Feedback reorders existing atoms; it adds nothing. A query with no relevant atom in the Store stays
  unanswerable.

## Refinements of `feedback-v1`

The protocol lets #110 refine the extension in its own documentation. These refinements keep the event rule,
`feedbackHash` (`html-kf-v1`), the three populations and the persistence audit unchanged:

- **Key-strategy treatment arms.** Three treatment arms instead of one, one per public Store key strategy, each with
  its own identically imported root. This adds the `key-variant/*` in-sample probes.
- **Union-key split.** Queries are grouped by key under every strategy, not only the configured one, so a single split
  serves every arm. `split.valid` requires coverage of every category with two or more queries.
- **Storage-level read-back.** It complements the protocol's behavioral audit: `feedback.persisted.<arm>` and
  `feedback.logUnchangedAcrossRestart.<arm>` read the stored events directly.
- **Feedback-disabled reference.** In the restarted process, each treatment root reopened with feedback-aware ranking
  off must equal the control (`feedback.disabledEqualsControl.<arm>`).
- **Prefix stability on treatment arms** after events is a diagnostic, for the Store reason given under
  [Checks](#checks).
- **Pre-feedback list source.** The events are derived from `treatment-exact`'s pre-feedback snapshot, which
  `arms.preFeedbackEqual` requires to equal every other arm's.

No ADR is needed. The change is evaluation-only, in the optional `integration` source sets. It changes no module
ownership, persistence or interoperability contract, or Signal representation. Relevance state stays in the Store,
and the adapter stays read-only.
