# Forge Routing Temporal Replay Evaluation (Issue #67)

## Question

Does consuming validated routing feedback change worker-route recommendations **safely**, and can a harness show it
honestly? Safely means:

- no future outcome leaks into an earlier decision;
- deriving feedback without consuming it changes nothing;
- neutral evidence moves no state;
- no credit breaks the quality gates;
- the state is reproducible from its inputs.

Honestly means the harness reports quality, cost and coverage faithfully:

- an unknown is reported as unknown, never as zero;
- a counterfactual is never invented;
- a partial cost is never presented as a complete one;
- whole-chain cost is never attributed to a stage or a route twice.

The harness compares three arms ([`ReplayArm`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/ReplayArm.java))
on identical bounded fixtures:

| Arm | Decisions read | Feedback |
| :--- | :--- | :--- |
| `static` | an empty preference: the #62 rule baseline | none derived |
| `derived-not-consumed` | the snapshot of a bound state that never consumes | derived for every visible outcome, then dropped |
| `adaptive` | the snapshot of the caller-owned `RoutingStateStore` (ADR 0030) | derived and consumed once visible |

The harness is offline and evaluation-only. It adds no routing API of its own: it uses the published core API, including
the checkpoints and compaction of #89 ([ADR 0031](../adr/0031-routing-state-checkpoint-and-ledger-compaction.md)). It makes
**no claim** that adaptive routing improves quality or cost online:
- **Synthetic fixtures** know every route's outcome, but they are constructed, not observed.
- **Store exports** only hold the executed route's outcome, so a divergent replayed choice has no outcome at all.

ADR 0026 and the architecture document record that routing quality, cost and adaptation effect are measured only
offline, by this harness, and make no online or causal claim; ADR 0029, ADR 0030 and the routing contract make no
effectiveness claim for the illustrative, untuned reference values. This harness provides the reproducible workload and
controls those documents asked for, not an effectiveness result.

## Running

```bash
./gradlew :monada-neuron-evaluation:runForgeRoutingEvaluation                              # full (960-task workload)
./gradlew :monada-neuron-evaluation:runForgeRoutingEvaluation -PbenchmarkArgs="--quick"     # smoke (240-task workload)
./gradlew :monada-neuron-evaluation:test                                                   # correctness; also runs integrationTest when the Store is present
./gradlew :monada-neuron-evaluation:test -x :monada-neuron-evaluation:integrationTest      # store-free correctness, when the Store is present
./gradlew :monada-neuron-evaluation:runForgeRoutingStoreReplay                             # real Store arm (optional)
./gradlew :monada-neuron-evaluation:integrationTest                                        # Store arm correctness (optional)
./gradlew test                                                                             # includes integrationTest when the Store is present
```

**Store-free correctness.** Without the sibling Store checkout, `integrationTest` does not exist and
`./gradlew :monada-neuron-evaluation:test` is already store-free (`-x` would fail on the missing task). With the
checkout present, `test` also runs `integrationTest`, so exclude it with `-x :monada-neuron-evaluation:integrationTest`.

Quote arguments that contain spaces, e.g. `-PbenchmarkArgs='--quick --output-dir "/tmp/benchmark reports"'`.

**Options.** `runForgeRoutingEvaluation` takes `--quick`, `--output-dir <dir>` (default `build/reports/benchmarks`) and
`--store-arm`. `runForgeRoutingStoreReplay` takes `--quick` and `--output-dir`.

**Outputs.** `forge-routing-replay.{json,md}` and `forge-routing-store-replay.{json,md}`.

**Exit status** of `runForgeRoutingEvaluation` (and of `runForgeRoutingStoreReplay`, which has no status 3):

| Status | Meaning |
| :--- | :--- |
| 0 | every semantic check passed |
| 1 | a semantic check failed |
| 2 | invalid arguments |
| 3 | a requested prerequisite is unavailable |
| 4 | the harness itself failed (an unexpected exception, or the report could not be written); no verdict was reached |

A harness failure is never reported as a failed check, so CI can tell a crash from a regression. Latency and allocation
never affect the exit status or any test.

**The Store arm is optional.** It needs the sibling checkout `../monada-resonance-store`, exactly as the adapter module
does (ADR 0018):
- Without it, the `integration` source set and `runForgeRoutingStoreReplay` do not exist, and
  `runForgeRoutingEvaluation --store-arm` prints an `UNAVAILABLE` message and exits 3. It never silently passes.
- With it, any failing phase (recording, export, import or replay) becomes a failed check in the report, never a crash
  that hides the rest.

No network, credentials, live provider or private task data is used.

## Architecture

```text
monada-neuron-evaluation
  ├── main/.../evaluation/routing         fixtures, temporal replay engine, summaries, digests, checks, runner
  ├── test/.../evaluation/routing         focused JUnit tests (run by ./gradlew test and CI `check`)
  ├── integration/.../evaluation/routing  StoreReplaySource, StoreReplayEvaluation, runner        (optional)
  └── integrationTest/.../routing         StoreReplayEvaluationTest                               (optional)
        │ integration depends on
        ▼
monada-neuron-resonance-adapter ──► monada-resonance-store (composite build: monada-api, monada-core, monada-evaluation)
```

**Dependencies.**
- The store-free code uses only the public routing API of the root project.
- Store types appear only in the `integration` source set.
- The Store's `monada-evaluation` module (`CostToAcceptedOutcome`, Store #99) is an `integration`-only dependency.

As before, production cognition never depends on evaluation code. In the store-free code, cost is a local mirror,
[`ReplayCost`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/ReplayCost.java). It has the
same `AVAILABLE`/`PARTIAL`/`UNAVAILABLE` statuses and the same "no amount when unavailable" invariant, with one amount
per currency.

## Replay algorithm

**Inputs.** A `ReplayFixture` holds one state partition, one routing policy and at most `MAX_TASKS = 1024` tasks. Each
task carries:
- the caller-supplied `RoutingRequest`: identity, fingerprints and its decision `cutoff`;
- the catalog snapshot in force at that cutoff;
- an `OutcomeOracle`, one of:
  - `KnownForEveryRoute`: synthetic, with a scripted outcome per route;
  - `ChosenRouteOnly`: an export, holding the logged decision and the observed revisions of its outcome.

**Construction checks.** These hold before any replay, and an oversized fixture is rejected, never truncated:
- Every outcome must be visible strictly after its decision.
- `requestOrdinal` must be unique per scope. The contract numbers a scope's requests in issue order and does not tie
  that order to cutoffs, so a request issued later may be decided against an earlier snapshot.
- One sample reference answers one task: the replay joins every observation to its decision by `experienceRef`, so
  two tasks may never share one.
- The evidence policy must be the partition's evaluation policy (same id and version), so feedback is never assessed
  under a policy the requests do not name.
- The observations of one logged decision must be revisions of one sample: one `experienceRef`, strictly increasing
  revisions (gaps allowed) and ledger sequences, and every corrected revision superseding the same original position,
  which is revision 0's ledger sequence when revision 0 is listed. That is how the Store translator versions a sample.
  Two separately referenced samples can therefore never credit one execution twice. A revision read again at a later
  export cutoff is the same claim, as core `RebuildOrder` treats it: the copies are merged into the one read later, and
  two different claims of one revision are rejected.
- A scripted repair chain is never attributed `STAGE_LOCAL` (contract `StageAttribution`), so a repair's evidence
  cannot credit the routed route. A chain records its stages apart from its attempts, and the observation reports
  that stage count as its attribution's `chainStageCount`: `STAGE_LOCAL` needs exactly one stage, `CHAIN_ONLY` at least
  two, and a repair chain is `CHAIN_ONLY` with two or more stages and `NONE` otherwise. An acceptance won by a repair
  has at least two attempts in every revision, so the reported attempts and rework never contradict the observation's
  second attempt.
- Cohorts are bounded at ≤ 256.
- Observations are bounded at ≤ 4096, so a full rebuild stays possible. A synthetic task counts as the most it can
  deliver through a route its catalog lists as available for its stage (none, one, or two when that route's outcome has
  a correction); a logged task counts its listed revisions.

**Order and timing.**
- **Decision order.** Tasks are decided in total order: cutoff first, then `requestOrdinal` (the caller's issuance
  order), then the other `DecisionRef` fields in code-point order. A batch of requests that share a cutoff therefore
  replays in issuance order, never in the order of its opaque ids.
- **Visibility.** The cutoff is inclusive, as the routing contract defines it ("Ordinals and cutoff"). Before the
  decision at cutoff `c`, exactly the pending observations with `ledgerSequence <= c` are delivered, in
  `(ledgerSequence, experienceRef, revision)` order. An outcome, acceptance, correction or usage observed after `c`
  cannot reach that decision, and a decision's own outcome must be visible strictly after its cutoff.
- **State as of the cutoff.** The adaptive state then examines through `c` itself (`processedCutoff = c`), as the
  contract requires a replay to rebuild "the state as of each request's cutoff", whether or not an observation sits
  exactly at `c`. The static and derived-not-consumed arms read no learned state.
- **Horizon.** A fixture carries `horizon`: the export's examined watermark. For a Store export that is its
  high-watermark; otherwise it defaults to the latest cutoff, observation position or export cutoff an observation was
  read at, and nothing earlier is accepted. A fixture derived from another (flipped, truncated, corrected) keeps its
  horizon.
  After the last decision, every remaining observation is delivered and the adaptive state's watermark is advanced to
  the horizon. The final state is therefore the state as of everything the export examined, not only as of its last
  observation. In the Store fixture, the unfinished execution appends events after the last finished observation,
  which makes the two differ.

**Outcomes and feedback.**
- **Synthetic outcomes** are built for the replayed decision's route through the public API
  ([`ObservationFactory`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/ObservationFactory.java)),
  so they answer exactly that decision. Their sample reference is `exp-sha256:` plus the SHA-256 of the
  `CanonicalEncoder` encoding of every `DecisionRef` field as a typed list (texts as UTF-16 code units prefixed by
  their count, the ordinal as a typed long). Opaque tokens may contain any separator and even unpaired surrogates, so
  plain concatenation or a charset encoding could merge two decisions in the ledger. An observation depends only on its
  task, the route and the decision's cohort binding, catalog version and routing policy, so it is generated once per
  such key and reused by every later run, in every arm: it stands for a ledger read.
- **Exported outcomes** answer the logged decision. They are delivered whatever the replay advises, because they really
  were observed. When the replay advises any route other than the logged one, the sample is `MISSING_COUNTERFACTUAL`,
  and it carries no outcome, cost or attempts.
- **Feedback** is derived with `AdaptiveStateDefinition.feedbackPolicy(evidencePolicy)` against the bound state's
  `TargetBindings`. Only the adaptive arm calls `consume`.
- **Corrections.** A correction that returns `RebuildRequired` triggers a rebuild: `rebuild` runs over everything
  delivered so far and the staged store is swapped in. Each observation's derivation is the one made at its delivery,
  because the bindings it read never change during a run; `rebuildFromScratch`, the equivalence check, derives every
  observation again. A failed rebuild means the fixture is invalid. The adaptive arm's
  [`RebuildStrategy`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/RebuildStrategy.java)
  decides where a rebuild starts:
  - `SCRATCH` (the default, and every other check's reference) replays everything delivered so far.
  - `CHECKPOINT`: the state takes a `RoutingStateCheckpoint` every 32 deliveries, keeps the newest 4 and compacts its
    ledger to the oldest of them.
    - A correction drops every kept checkpoint at or after its `supersedesSequence` (contract Section 7).
    - A rebuild starts with `rebuildFrom` from the newest kept checkpoint strictly below the affected sequence, replaying
      only later observations; with none kept, it starts from scratch.
    - A correction of a compacted revision (`BeyondCheckpoint`) is rebuilt the same way.
    - The cadence is illustrative, not tuned.

**Data structures.**
- Tasks are sorted once.
- Pending observations arrive while the replay runs, so they sit in a binary heap: `O(log P)` per insertion or
  removal. `P` is bounded by the fixture's observation limit, 4,096, not by its task count: a synthetic task adds at
  most two observations when it executes, but a chosen-route-only fixture queues every logged revision before the first
  decision.
- The outcome oracle is a `TreeMap` keyed by route in natural order (a caller's comparator is never kept):
  deterministic order, `R ≤ 32`.
- Two caches, both pure: generated synthetic observations, per task and decision provenance (`O(T·R)`), and each
  delivered observation's derivation within a run, by identity (`O(P)`). There is no parallelism or SIMD; the workload
  is small and the reference algorithm is the point.

**Cost.**
- Time is `O(T (R log R + C) + P log P)`, plus `O(N log N + N·L)` per correction rebuild.
- Retained memory is the state (`O(C + L)`), the delivered observations and their derivations (`O(P)`) and, when
  recording, one sample per task and one event per delivery.
- Between runs only the generated synthetic observations are kept, a pure function of the fixture. There is no clock,
  no randomness, no background task and no session.

## Fixtures (`forge-routing-replay-v5`)

[`SyntheticRoutingFixtures`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/SyntheticRoutingFixtures.java)
builds every fixture by index arithmetic. Any content change must bump `VERSION`.

**Shared configuration.**
- **Scope and evaluation policy.** All fixtures share one scope, `forge-replay`, and one evaluation policy,
  `forge-gates@1` (mandatory `TESTS` and `SECURITY`).
- **Reference values.** They use the reference adaptive definition `adaptive-routing-state/1` and the reference
  lexicographic policy (min support 3, auto-select tier cap 1).
- **Catalog:**
  - two interchangeable subscription routes, `sub-a` and `sub-b`, with the same tier and priority;
  - one tier-2 API route, `api-x`, that serves only `qa`;
  - a `sub-a` outage catalog, which lets `sub-b` gather evidence on-policy.
- **Positions.**
  - Task `i` is decided at `4096 (i + 1)`.
  - Its outcome becomes visible `d` slots later, at `4096 (i + 1 + d) + 1 + i`.
  - A correction is visible at `4096 (i + 1 + d + c) + 2049 + i`.
  - Positions are unique and outcomes land strictly between two decisions for `i < 2047`; at `i = 2047` a correction
    would land on the next decision's cutoff.

**Why `api-x` is kept off the learned stages.** A learned preference applies only when **every** eligible route's cohort
has enough support. An eligible route that is never chosen would therefore pin every decision to the baseline. This is
an observed consequence of the #62 rule, not a fixture accident.

| Scenario | Tasks | Purpose |
| :--- | :--- | :--- |
| `workload` | 240 quick / 960 full | Measured workload with seven features (listed below). |
| `improvement` | 24 | `sub-a` always fails tests and `sub-b` always passes. |
| `degradation` | 24 | Drift: `sub-b` passes during a six-task outage, then fails; `sub-a` always passes. |
| `neutral` | 16 | Environmental, cancelled, pending and imported-claim outcomes only. |
| `cases` | 13 | Named edge cases (listed below). |
| `chosen-route-only` | 16 | A store-like log written by the static policy; the last execution is never exported. |

The `workload` scenario has:
- two learned stages, `implement` and `review`;
- four change-size buckets per stage;
- a `sub-a` outage over the first eighth of the tasks;
- delays of one to three slots;
- a correction every 17th task;
- unknown usage every 11th task, a repair every 13th and a `qa` abstention every 19th.

The `cases` scenario holds:
- a cheap invalid route;
- a repaired acceptance;
- unknown usage;
- an incomparable context pair, an incomparable stage pair (one task routed for `implement` and for `review`) and a
  comparable pair;
- two currencies;
- a tier-cap abstention;
- a stage with no eligible route;
- a late security correction.

**Hand-calculable costs.** Amounts are hypothetical API-equivalent USD per chain, priced from each attempt's tokens at
the same list prices the Store arm records (0.015 USD per 1000 input and 0.080 USD per 1000 output tokens), from one
token table shared with the Store arm:

| Case | Tokens (input / output) | Cost |
| :--- | :--- | :--- |
| `sub-a` attempt | 1200 / 300 | 0.0420 |
| `sub-b` attempt | 1500 / 400 | 0.0545 |
| Repair attempt | 500 / 300 | 0.0315 |
| Repaired chain (`0.0420 + 0.0315`) | routed plus repair: two attempts over two stages (`CHAIN_ONLY`) | 0.0735, counted once; the repair attempt id is `repair-` plus 32 hex digits of the SHA-256 of the routed attempt id's canonical encoding, 39 code points whatever that id's length |
| Unknown-usage chain (output unknown) | 900 / unknown | `PARTIAL` lower bound 0.0135 (the known input only) |
| Unknown-usage chain repaired | 900 / unknown, plus repair | `PARTIAL` lower bound 0.0450 (the repair attempt's known cost is kept) |
| Two-currency chain | 1200 / 300 | 0.0420 USD and 0.0390 EUR, never summed together |

A correction keeps its chain's attempts: correcting a repaired acceptance to a rejection leaves two attempts. A scripted
outcome or correction carries a priced chain only for an acceptance that can be validated: accepted, of validated origin
(a correction keeps the original's origin, so an imported claim's correction is never priced), and from a completed
invocation or a repair. Anything else is rejected at construction, as the Store prices only validated acceptances.

`ReplayCost` mirrors the Store's `CostToAcceptedOutcome` invariants and is never stricter: an unavailable cost has no
amount, each currency appears at most once and no amount or reason is null, but the number of currencies is not capped
and a partial or unavailable cost may come without reasons, as the Store allows.

**Store arm.** [`StoreReplaySource`](../../monada-neuron-evaluation/src/integration/java/monada/neuron/evaluation/routing/StoreReplaySource.java)
records the `chosen-route-only` truth into a real temporary execution memory:
- Each of the 16 tasks is decided at the Store's high-watermark before it is recorded, and its routing envelope is
  published.
- The execution is recorded with the stage usage, judged evidence and outcome. The last execution never finishes.
- The history is read back through `StoreExperienceTranslator` (#64).
- Costs come from the Store's `ExperienceExportReport` (#99), priced at the list prices 0.015 USD per 1000 input tokens
  and 0.080 USD per 1000 output tokens.
- An accepted `sub-b` chain therefore costs `1500 × 0.015/1000 + 400 × 0.080/1000 = 0.0545 USD`.

## Semantic checks

Each check guards against passing vacuously. For example, equal decisions are rejected as evidence when no feedback was
derived, and unchanged earlier decisions are rejected as evidence when the mutation cannot change any decision at all.

| Check | Verified |
| :--- | :--- |
| `replay.deterministic` | Two replays of freshly built fixtures give identical fixture, decision, feedback, learned-state and report digests and sample order. |
| `leakage.future-outcomes` | Flipping every outcome or correction visible at or after task 6's cutoff, including usage and cost, leaves every earlier decision, snapshot watermark and count of visible deliveries unchanged. A replay truncated there equals the prefix of the full replay. Later adaptive decisions do change. |
| `leakage.late-correction` | A correction visible six slots later changes no earlier decision, snapshot watermark or count of visible deliveries, so an early delivery that leaves every route unchanged still fails; the adaptive arm rebuilt when it arrived. |
| `leakage.snapshot-watermark` | Every decision, in the scenarios and in their flipped, truncated and late-corrected replays, read a snapshot that examined nothing after its inclusive cutoff, and every adaptive snapshot is the state as of its cutoff (the other arms read none). |
| `control.derived-not-consumed-equals-static` | Same decision sequence on every fixture, with feedback derived. |
| `control.static-wiring` | The control arm is wired as one: its decisions equal the policy's over `RoutingPreference.empty`, it keeps no state, delivers and derives nothing, and selects only by `COLD_START` or `HOST_PRIORITY`. The expected decisions come from the same policy, so this checks the harness, not #62's behavior, which #62's own tests cover. |
| `control.neutral-feedback-leaves-state-unchanged` | Neutral-only consumption leaves cohort Nodes and support equal to the reset baseline, and decisions equal static. |
| `eligibility.violations-zero` | Across all arms and stages, none of the following occurs: a reward or penalty that the observation's own fields do not admit, read directly and not through `OutcomeEvaluator` (a reward needs a validated, stage-local acceptance of the routed attempt itself, a completed invocation and every mandatory dimension passing on it; a penalty needs a stage-local, validated, settled outcome of a non-environmental, non-cancelled invocation with a mandatory dimension failing on the routed attempt); a reward or penalty without the matching evaluator candidate; credit to any cohort key other than the answered decision's (compared whole: scope, stage, cohort bucket, route); a neutral rule that moves a Node. The evaluator verdict is the one the policy acts on, so it is reported for consistency, not as independent evidence. |
| `state.incremental-equals-rebuild` | The final adaptive state digest (cohort Nodes, support, the core `routing-ledger-digest/1` and the watermark) equals a reset copy rebuilt from every delivered observation, including after correction-triggered rebuilds, and its watermark equals the fixture horizon. |
| `state.checkpoint-equals-rebuild` | With the `CHECKPOINT` strategy, the adaptive arm's decision trace, deliveries, derivations and state digest equal the `SCRATCH` run's. The final state rebuilt from the checkpoint it was compacted to equals it too. At least one fixture must compact its ledger and start a rebuild from a checkpoint. |
| `cases.cheap-invalid` | Few tokens with a proven security failure: gate failure, `NOT_ACCEPTED`, cost `UNAVAILABLE`, penalized and never rewarded. |
| `cases.repaired-accepted` | 2 attempts, validated acceptance, 0.0735 USD counted once per execution (however many of its stages were routed); chain-only attribution earns no credit. |
| `cases.unknown-usage` | Unknown and not-measured counters are counted as such, not as zero; the cost is a separate `PARTIAL` lower bound of 0.0135 USD. |
| `cases.incomparable-context-and-stage` | The same task under two context fingerprints, and the same task routed for two stages, are incomparable pairs (`context`, `stageKind`); identical keys form the only comparable pair. |
| `cases.currencies-not-summed` | Complete costs are summed per currency only: exactly USD and EUR are reported, USD equals 0.3675 (the repaired chain plus seven `sub-a` chains, calculated from the token table) and EUR equals 0.0390. |
| `cost.completeness-consistent` | Across every fixture and arm, no observed sample whose routed-stage usage is incomplete reports an `AVAILABLE` cost (252 such samples in the full run), and no sample reports an `AVAILABLE` or `PARTIAL` cost to an accepted outcome without a validated acceptance. Incomplete means `MEASURED` with an unknown or not-measured counter, or with no counter at all (unrecorded); a declared `NO_BILLABLE_USAGE` is complete. |
| `cases.abstention-and-no-eligible-route` | A tier-capped stage abstains, an unserved stage has no eligible route, and both are `NOT_EXECUTED`. |
| `cases.missing-counterfactual` | Adaptive choices of a route the log never executed are reported as coverage gaps with no outcome; an executed but unexported outcome is `UNOBSERVED`; chain figures count each observed execution exactly once. |
| `adaptation.improvement-and-degradation-visible` | On fixtures whose direction is known, the report shows both an improvement (+16 validated acceptances) and a degradation (−5). This tests the harness's sensitivity. No improvement is required on the measured workload. |
| `bounds.respected` | Tasks, cohorts, ledger and observations are within bounds, and a 1025-task fixture is rejected. |
| `measurement.<arm>` | Present only when a measured run differs from the run the checks verified; the row is then missing and the check fails. |
| `store.*` (optional arm) | Covers seven points (listed below). |

The `store.*` checks cover:
- cleanup of both temporary stores (each is closed and its directory deleted, whatever failed before);
- a complete and explained import: exactly one revision-0 observation per finished execution (`exe-0` to `exe-14`),
  each answering its own logged decision, and the unfinished execution rejected as `MISSING_OUTCOME`;
- two independent recordings exporting identical fixtures and Store export digests;
- deterministic replay, comparing decisions, feedback and learned state (including state presence) for every arm;
- the derived-not-consumed control, zero eligibility violations and snapshot watermarks, each with its own verdict and
  detail;
- missing counterfactuals;
- Store costs reaching every observed sample unchanged, compared field by field (status, each currency amount and each
  reason code) with the Store's own `CostToAcceptedOutcome`, not through the export's mapping, and the accepted amount
  recomputed from the token table and prices; a priced cost only on a sample the replay also found validated-accepted;
  counts are over observed samples only (4 `AVAILABLE`, 11 `UNAVAILABLE`);
- incremental state equal to a rebuild.

The Store report records the Neuron and Store commits (`neuron.commit`, `store.commit`) beside its export digest.

## Reporting

Each arm has a [`ReplaySummary`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/ReplaySummary.java),
reported per routed stage kind and in total.

**Stage figures** cover:
- decisions and selections;
- abstentions by reason, and no-eligible-route counts;
- selections by basis: `COLD_START` and `HOST_PRIORITY` count as baseline fallback;
- coverage: `OBSERVED`, `MISSING_COUNTERFACTUAL`, `UNOBSERVED` or `NOT_EXECUTED`;
- validated acceptances and gate failures;
- routed-stage tokens per kind: the exact known sum (a `BigInteger`, so large legal counters never wrap), plus separate
  counts of known, unknown, not-measured and absent counters;
- feedback per score rule or no-update reason, state updates, credits and eligibility violations.

**Chain figures** are whole-execution facts, reported once per execution, identified by scope, task and execution, and
never per stage or route. They come from the execution's terminal observed sample, the last one issued (greatest
`requestOrdinal`, not list position, since a later-issued request may be decided at an earlier cutoff), which knows the
most about the chain (a later attempt sees the repair and the acceptance an earlier one could not):
- attempts and rework;
- cost status counts;
- the per-currency sum of `AVAILABLE` costs;
- the per-currency `PARTIAL` lower bound;
- `withoutValidatedAcceptance`: executions reporting an `AVAILABLE` or `PARTIAL` cost to an accepted outcome without a
  validated acceptance. Such a cost cannot exist, so it is counted here and never summed; it is 0 on every fixture.

**Pairing** reports directly comparable versus incomparable pairs of observed executions of one task, with the
differing fields named. A pair needs two different executions: two observations of one execution, such as two routed
stages, are never paired. A pair is comparable when some routed stage of one and some routed stage of the other have
matching keys; otherwise it is incomparable by the fields of its closest stage pair. The key is the contract's ("Direct comparable pairs"): scope, task, `stageKind`, the three
fingerprints and the evaluation policy id and version. The replay adds the routing policy id and version and the policy's
`ResourceObjective` binding. Every identity is a separate component, never a concatenation, because opaque tokens may
contain any separator. Cohort summaries are observational.

**Comparison with the static arm.** Each `vsStatic` line compares the same task's decision to the static decision, per
task. It reports changed decisions, gained and lost validated acceptances, and changes that cannot be scored
(`unscored`) because one side has no observed outcome.

## Measurement method

- **Instrument.** `EvaluationMetricsCollector`, the same one the other checked evaluations use.
- **Operation and latency scope.** An iteration replays the whole workload fixture under one arm. The collector's latency
  distribution (mean and percentiles) is **per replay iteration**, while throughput and allocation are **per replayed
  decision**. Each row states this as `latencyScope` and adds the derived `meanNanosPerDecision`.
- **Iterations.** Quick: 3 measured. Full: 10 measured.
- **Warm-up.** Budgeted per arm (`ReplayMeasurement.Warmup`), not a fixed count, because the arms' replays differ by
  two orders of magnitude: three iterations of a 3 ms replay end long before the compiler does. Each arm runs
  unrecorded replays until it has done at least the minimum count **and** accumulated the time budget of replay, never
  more than a cap that only bounds a pathologically fast replay. The first and the last warm-up run are validated
  against the reference, so a fast arm's warm-up is not dominated by digests; every timed run is still validated.
  Quick: at least 1 replay and 50 ms, at most 100. Full: at least 3 replays and 2,000 ms, at most 100,000. The run
  configuration's warm-up count is that minimum (the policy text says so and states the budget and cap). Every row
  records what actually ran:
  - `warmupIterations` and `warmupReplayMillis`;
  - `warmupMinIterations`, `warmupBudgetMillis` and `warmupMaxIterations`;
  - `referenceRunMillis`, the duration of the untimed reference run. That run also generates the reused observations,
    so it is not a steady-state figure.

  In full mode that comes to about 500–700 warm-up replays for static, 165–190 for derived-not-consumed, 6–7 for
  adaptive, and 15,000–46,000 for the 16-task Store rows; the cap is never reached.
- **Timed window.** Each iteration times `replay.run` without recording samples or events. For the arms that keep state
  that includes constructing and binding the fresh state; the static arm builds and binds none
  (`stateResetPerIteration=false`). Every arm reuses the synthetic observations generated by the untimed reference
  run, so no arm's window pays for the harness's SHA-256 sample references, and an adaptive rebuild reuses each
  observation's delivery-time derivation. Only the validation of the previous run is untimed. Every row records its
  arm's window as `timedWindow`.
- **Validation.** Each run replays without recording samples, but it still keeps a collision-free `DecisionTrace` of
  every decision: the advised `RouteKey`, compared by full id and version and never by hash, plus packed kind, basis,
  abstention and coverage, and the snapshot watermark. The arrays are sized once per run, so filling them allocates
  nothing per decision. The trace also confirms the unrecorded (timed) path decides exactly as the recorded path the
  checks verified. Each measured run must match its untimed reference on decision trace (which also records how many
  observations each decision could see), on every delivered observation by value and order (with the decision and
  request each answers, not only their count), on the count of derivations per rule and reason (so a measured
  derived-not-consumed run must actually derive) and on learned-state digest, computed once per row. The comparison runs outside the timed window (`validationInWindow=false`): in the next iteration's untimed setup, and once more after the
  collector returns, because the collector runs no setup after its last iteration.
- **Setup.** Fixture construction, the reference run and all semantic checks run before measurement; each row's
  reference must equal the recorded run the checks verified, which is reused rather than replayed again.
- **One implementation.** The store-free and Store rows share `ReplayMeasurement`, so their diagnostics and labels
  cannot drift apart.
- **Checkpointed row.** `RoutingReplay.adaptive-checkpointed` measures the adaptive arm with the `CHECKPOINT` strategy.
  - It is validated against the same verified from-scratch run as `RoutingReplay.adaptive`, because both must decide,
    deliver, derive and learn identically.
  - Its timed window includes taking checkpoints, compacting and starting rebuilds from a checkpoint.
  - Every row reports `rebuildStrategy`, `rebuilds`, `rebuildsFromCheckpoint`, `replayedObservations`, `checkpoints`,
    `compactedEntries`, the live `ledgerEntries` and the `ledgerDigestEntries` the state has accounted for.
- **GC telemetry.** JVM-wide, so it includes the untimed validation.
- **Retained memory** (`modeledRetainedStateBytes`) is a **model**:
  - 400 B per cohort and 350 B per ledger entry;
  - in the checkpointed row, also the checkpoints the run still keeps (at most 4): 64 B per checkpoint cohort and
    350 B per retained marker, counted from the actual kept checkpoints (`keptCheckpoints`, `keptCheckpointMarkers`);
  - not a measurement;
  - kept apart from measured allocation per op and measured latency.

## Sample results (exploratory, not a gate)

**Environment:** Linux 7.2.9 (Fedora 44, x86_64), 16 processors, OpenJDK 27 (Zulu 27+35), G1, 3832 MB max heap. Full
mode.

| Benchmark | Scale | Mean per iteration | p95 per iteration | Mean per decision (derived) | Alloc per decision |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `RoutingReplay.static` | 960 tasks | 3.17 ms | 3.66 ms | 3.3 µs | 6.85 KB |
| `RoutingReplay.derived-not-consumed` | 960 tasks, 20 cohorts | 10.35 ms | 10.65 ms | 10.8 µs | 18.80 KB |
| `RoutingReplay.adaptive` | 960 tasks, 20 cohorts, 910 ledger entries, 51 rebuilds | 345.38 ms | 383.79 ms | 359.8 µs | 362.59 KB |
| `StoreRoutingReplay.static` | 16 exported tasks | 43.08 µs | 63.77 µs | 2.7 µs | 6.45 KB |
| `StoreRoutingReplay.derived-not-consumed` | 16 exported tasks, 2 cohorts | 87.75 µs | 102.42 µs | 5.5 µs | 12.44 KB |
| `StoreRoutingReplay.adaptive` | 16 exported tasks, 2 cohorts | 117.39 µs | 142.94 µs | 7.3 µs | 15.27 KB |

The rows above are single runs on one machine: the workload rows from one full-mode run, the Store rows from another.

**Rebuild from checkpoints (#89).** These two rows come from one later full-mode run on the same machine, so they
compare within that run:

| Benchmark | Rebuilds (from a checkpoint) | Replayed observations | Live / digested ledger entries | Mean per iteration | p95 per iteration | Mean per decision (derived) | Alloc per decision |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `RoutingReplay.adaptive` | 51 (0) | 24,349 | 910 / 910 | 322.69 ms | 337.86 ms | 336.1 µs | 357.53 KB |
| `RoutingReplay.adaptive-checkpointed` | 51 (50) | 893 | 34 / 910 | 18.43 ms | 19.21 ms | 19.2 µs | 28.93 KB |

Both rows reach the same decision trace, deliveries, derivations and state digest, and the report digest is unchanged.
The checkpointed row explicitly compacted 31 entries; each rebuild from a checkpoint also leaves only the later entries.
It was a single run, not repeated five times like the table below.
Over five full-mode runs of each evaluation, run alternately, the means ranged:

| Benchmark | Mean per iteration, min–max over 5 runs | max / min |
| :--- | :--- | :--- |
| `RoutingReplay.static` | 2.78–3.26 ms | 1.17 |
| `RoutingReplay.derived-not-consumed` | 9.79–11.75 ms | 1.20 |
| `RoutingReplay.adaptive` | 334.8–346.2 ms | 1.03 |
| `StoreRoutingReplay.static` | 34.3–57.2 µs | 1.67 |
| `StoreRoutingReplay.derived-not-consumed` | 87.8–93.2 µs | 1.06 |
| `StoreRoutingReplay.adaptive` | 117.2–142.1 µs | 1.21 |

The light workload rows now sit at the steady state that a separate probe measured: 200 unrecorded replays, median of
the last 50, gave static 2.6–6.3 ms and derived-not-consumed 8.5–14.2 ms. Their medians varied less than their means:
2.71–3.17 ms and 9.49–10.63 ms. Before the budgeted warm-up, three warm-up iterations left these rows inside JIT
warm-up. In five interleaved runs they ranged 7.4–20.9 ms (static) and 13.7–35.1 ms (derived-not-consumed).

The Store static row stays the noisiest. Its 10 measured iterations last about 40 µs each, so one slow iteration moves
the mean (its median ranged 31.4–45.9 µs). That is timer and scheduling noise over a tiny window, not missing warm-up:
each Store row ran at least 15,000 warm-up replays.

Earlier runs of this harness are not comparable with these rows either:
- they warmed every arm up for a fixed three iterations, so their light rows include JIT warm-up (above), and the
  Store rows, at 0.8–2.4 ms per iteration, were mostly warm-up;
- they timed the generation of synthetic observations (a SHA-256 per observation) in the derived-not-consumed and
  adaptive windows only;
- they re-derived every delivered observation on each adaptive rebuild.

Every arm now reuses generated observations, and a rebuild reuses each observation's delivery-time derivation. The
Store per-decision figures include a fixed per-iteration cost (a fresh state and bindings, for the arms that keep
state) spread over only 16 decisions.

Modeled retained state at the end of the workload: 0 B for static, 8,000 B for derived-not-consumed (20 cohorts) and
326,500 B for adaptive (20 cohorts plus 910 ledger entries). This is a model, not a measurement.

**Workload figures (full, 960 tasks).** The workload is constructed, so its arm difference is a property of its
residues, not evidence about real routing:

| Arm | Validated acceptances | `AVAILABLE` / `PARTIAL` / `UNAVAILABLE` cost | Available USD | Attempts / rework | Baseline fallback |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `static` | 380 of 910 observed | 346 / 34 / 530 | 16.3880 | 938 / 28 | 910 |
| `derived-not-consumed` | 380 of 910 | 346 / 34 / 530 | 16.3880 | 938 / 28 | 910 |
| `adaptive` | 767 of 910 | 697 / 70 / 143 | 39.5750 | 973 / 63 | 139 |

The `PARTIAL` lower bounds are 0.5220 USD (static, derived-not-consumed) and 1.1340 USD (adaptive), reported apart from
the available sums.

**Arm comparison on the workload.** Against static, the adaptive arm changed 771 decisions. It gained a validated
acceptance on 464 tasks and lost one on 77. That loss count is the scenario's own drift, and it is reported, not
hidden.

**Cost.** Available USD rises with more accepted chains, because a cost to an *accepted* outcome only exists once there
is an acceptance. It is not a per-task price comparison. Pairs whose key does not match are reported, never compared.

**Store arm (16 exported tasks).**
- Static and derived-not-consumed both observe 15 tasks and accept 4; one task is `UNOBSERVED`.
- The adaptive arm switches to `sub-b` at decision 7, once three `sub-a` failures are visible. Each decision is made
  at the Store's high-watermark, which includes the previous execution's outcome, and the cutoff is inclusive.
- Its 9 later choices are `MISSING_COUNTERFACTUAL`, because the log never executed them.
- Its validated acceptances therefore stay at the 4 the log observed. The replay cannot say whether those 9 choices
  would have been accepted.

Observations, not conclusions:

- **Adaptive cost.** The adaptive arm is the slowest by more than an order of magnitude (about 30 times
  derived-not-consumed on the workload). Correction-triggered rebuilds
  dominate it: each re-applies every delivered observation, `O(N·L)` (51 rebuilds with a ledger of up to 910 entries),
  even though it no longer re-derives them.
  - Starting the same rebuilds from checkpoints (#89) replays 893 instead of 24,349 observations and reaches the
    identical state.
  - Its mean per iteration drops to about the derived-not-consumed level.
  - This is evidence for the checkpoint, not a claim that production needs it.
- **Unvalidated reference values.** The reference adaptive values (`adaptive-routing-state/1`) are untuned. The drift
  scenario shows they can follow stale rewards for five tasks. Nothing here validates them.
- **Measurement noise.** Latency and allocation are single-machine figures over small fixtures. The budgeted warm-up
  keeps the light arms within about 1.2× across runs, but the Store static row's 40 µs iterations still move by up to
  1.7× (see above). RSS deltas are dominated by JIT and GC noise.

## Reproducibility

| Digest | Value |
| :--- | :--- |
| `fixture.workload.digest` (full) | `1e55e2e210ae3d1659f89c1730148f9c0247229952b35bfd4c18fc169137d9ea` |
| `workload.reportDigest` (full) | `b6576ce929214606a9a5c4905a73ca44b98a7322877721190cea52021c59a6aa` |
| `store.fixtureDigest` | `67fafedc160568d3004becfb49654f120267df2cd0cd882614072dfe814556b7` |
| `store.exportDigest` (Store tooling) | `f11276157baf9565a14ea07a386502ac8c5a8b3656a6744fb2861f00b4ae2ee8` |
| `store.reportDigest` | `2ce3cca46803be0e404ea5d696888c893e24648fec12bac7692f76c6e644d72e` |

- **Digests.** All are SHA-256 over a canonical, typed, length-prefixed encoding (`CanonicalEncoder`), never over
  `toString`:
  - records are walked component by component (their components reflected once per class), and every text is written
    as its UTF-16 code units prefixed by
    their count. A separator inside an opaque token can therefore never make two inputs hash alike, and no charset
    replacement can merge two texts: an unpaired surrogate stays distinct from `?`. Synthetic sample references and
    repair attempt ids are SHA-256 over the same encoding;
  - identities (fixture id and version, policy id and version) are separate entries;
  - lists and arrays keep their order, while sets and maps are sorted by their elements' own encodings;
  - doubles are encoded as exact bit patterns;
  - the report digest orders summaries by arm, whatever order the caller passes them in, and rejects two summaries of
    the same arm.

  They exclude timings, timestamps and environment.
- **Stable across runs.** The report embeds every fixture, decision, feedback, state and report digest. Tests assert
  that verdicts and digests are identical across runs (`RoutingReplayEvaluationTest`, `StoreReplayEvaluationTest`).
- **Mode dependence.** The `workload` digests depend on the mode, because quick mode uses 240 tasks. The scenario
  digests do not.

## Limits and remaining evidence gaps

- **No online or causal claim.** Synthetic outcomes are scripted. Store replay covers only the logged route, and
  divergent choices are coverage gaps. Off-policy estimation (propensities, importance weighting) is out of scope and
  would need logged propensities that Forge does not record.
- **Single-stage routing only.** Store exports support one routed stage per execution (#64). Stage cost is the routed
  stage's own usage, and chain cost comes from Store tooling. Neither is split across stages.
- **Store arm is fixed and small.** It is 16 executions with no corrections, one currency and a static logging policy.
  Store corrections, multi-attempt chains and priced partial coverage are exercised synthetically, not through the
  Store.
- **No actual billing.** Actual billing is never populated by the #64 translator. Only hypothetical API-equivalent cost
  is reported.
- **Checkpoint cadence untuned.** The core `RoutingStateCheckpoint` and compaction are implemented by #89 (ADR 0031,
  contract Section 23). The harness's cadence (every 32 deliveries, 4 kept) is one illustrative policy over one
  constructed workload. The Store arm has no corrections, so it never rebuilds from a checkpoint.
- **No tuning.** The reference adaptive values are not revisited, and nothing in the core is tuned.
