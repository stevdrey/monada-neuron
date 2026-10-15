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

The harness is offline and evaluation-only. It adds no routing API, and nothing in the core
(`src/main`) or the adapter changes. It makes **no claim** that adaptive routing improves quality or cost online:
- **Synthetic fixtures** know every route's outcome, but they are constructed, not observed.
- **Store exports** only hold the executed route's outcome, so a divergent replayed choice has no outcome at all.

ADR 0026, ADR 0029, ADR 0030 and the routing contract all keep their no-effectiveness-claim stance. This harness
provides the reproducible workload and controls those documents asked for, not an effectiveness result.

## Running

```bash
./gradlew :monada-neuron-evaluation:runForgeRoutingEvaluation                              # full (960-task workload)
./gradlew :monada-neuron-evaluation:runForgeRoutingEvaluation -PbenchmarkArgs="--quick"     # smoke (240-task workload)
./gradlew :monada-neuron-evaluation:test                                                   # correctness only, store-free
./gradlew :monada-neuron-evaluation:runForgeRoutingStoreReplay                             # real Store arm (optional)
./gradlew :monada-neuron-evaluation:integrationTest                                        # Store arm correctness (optional)
./gradlew test                                                                             # includes integrationTest when the Store is present
```

Quote arguments that contain spaces, e.g. `-PbenchmarkArgs='--quick --output-dir "/tmp/benchmark reports"'`.

**Options.** `runForgeRoutingEvaluation` takes `--quick`, `--output-dir <dir>` (default `build/reports/benchmarks`) and
`--store-arm`. `runForgeRoutingStoreReplay` takes `--quick` and `--output-dir`.

**Outputs.** `forge-routing-replay.{json,md}` and `forge-routing-store-replay.{json,md}`.

**Exit status of `runForgeRoutingEvaluation`:**

| Status | Meaning |
| :--- | :--- |
| 0 | every semantic check passed |
| 1 | a semantic check failed |
| 2 | invalid arguments |
| 3 | a requested prerequisite is unavailable |

Latency and allocation never affect the exit status or any test.

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
- Cohorts are bounded at ≤ 256.
- Observations are bounded at ≤ 4096, so a full rebuild stays possible.

**Order and timing.**
- **Decision order.** Tasks are decided in total order: cutoff first, then every `DecisionRef` field in code-point
  order.
- **Visibility.** The cutoff is inclusive, as the routing contract defines it ("Ordinals and cutoff"). Before the
  decision at cutoff `c`, exactly the pending observations with `ledgerSequence <= c` are delivered, in
  `(ledgerSequence, experienceRef, revision)` order. An outcome, acceptance, correction or usage observed after `c`
  cannot reach that decision, and a decision's own outcome must be visible strictly after its cutoff.
- **Horizon.** A fixture carries `horizon`: the export's examined watermark. For a Store export that is its
  high-watermark; otherwise it defaults to the latest cutoff or observation position, and nothing earlier is accepted.
  After the last decision, every remaining observation is delivered and the adaptive state's watermark is advanced to
  the horizon. The final state is therefore the state as of everything the export examined, not only as of its last
  observation. In the Store fixture, the unfinished execution appends events after the last finished observation,
  which makes the two differ.

**Outcomes and feedback.**
- **Synthetic outcomes** are built for the replayed decision's route through the public API
  ([`ObservationFactory`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/ObservationFactory.java)),
  so they answer exactly that decision. Their sample reference is `exp-sha256:` plus the SHA-256 of a length-prefixed
  UTF-8 encoding of every `DecisionRef` field. Opaque tokens may contain any separator, so plain concatenation could
  merge two decisions in the ledger.
- **Exported outcomes** answer the logged decision. They are delivered whatever the replay advises, because they really
  were observed. When the replay advises any route other than the logged one, the sample is `MISSING_COUNTERFACTUAL`,
  and it carries no outcome, cost or attempts.
- **Feedback** is derived with `AdaptiveStateDefinition.feedbackPolicy(evidencePolicy)` against the bound state's
  `TargetBindings`. Only the adaptive arm calls `consume`.
- **Corrections.** A correction that returns `RebuildRequired` triggers a rebuild: `rebuild` runs over everything
  delivered so far and the staged store is swapped in. A failed rebuild means the fixture is invalid.

**Data structures.**
- Tasks are sorted once.
- Pending observations arrive while the replay runs, so they sit in a binary heap: `O(log P)` per insertion or
  removal, with `P ≤ 2·tasks`.
- The outcome oracle is a `TreeMap` keyed by route: deterministic order, `R ≤ 32`.
- There is no cache, parallelism or SIMD. The workload is small and the reference algorithm is the point.

**Cost.**
- Time is `O(T (R log R + C) + P log P)`, plus `O(N log N + N·L)` per correction rebuild.
- Retained memory is the state (`O(C + L)`), the delivered observations (`O(P)`) and, when recording, one sample per
  task and one event per delivery.
- Nothing is retained between runs. There is no clock, no randomness, no background task and no session.

## Fixtures (`forge-routing-replay-v2`)

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

**Hand-calculated costs.** Amounts are hypothetical API-equivalent USD per chain:

| Case | Cost |
| :--- | :--- |
| `sub-a` attempt | 0.0420 |
| `sub-b` attempt | 0.0525 |
| Repaired chain (`0.0420 + 0.0315`) | 0.0735, counted once |
| Unknown-usage chain | `PARTIAL` lower bound 0.0270 |
| Two-currency chain | 0.0420 USD and 0.0390 EUR, never summed together |

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
| `leakage.future-outcomes` | Flipping every outcome or correction visible at or after task 6's cutoff, including usage and cost, leaves every earlier decision and snapshot watermark unchanged. A replay truncated there equals the prefix of the full replay. Later adaptive decisions do change. |
| `leakage.late-correction` | A correction visible six slots later changes no earlier decision; the adaptive arm rebuilt when it arrived. |
| `leakage.snapshot-watermark` | Every decision read a snapshot that examined nothing after its inclusive cutoff. |
| `control.derived-not-consumed-equals-static` | Same decision sequence on every fixture, with feedback derived. |
| `control.static-equals-cold-start-baseline` | Static decisions equal `decide` over `RoutingPreference.empty`: #62 behavior is unchanged. |
| `control.neutral-feedback-leaves-state-unchanged` | Neutral-only consumption leaves cohort Nodes and support equal to the reset baseline, and decisions equal static. |
| `eligibility.violations-zero` | Across all arms and stages, none of the following occurs: a reward without a positive, stage-local candidate; a penalty without a negative candidate; credit for a route, stage or scope other than the executed one; a neutral rule that moves a Node. |
| `state.incremental-equals-rebuild` | The final adaptive state digest equals a reset copy rebuilt from every delivered observation, including after correction-triggered rebuilds, and its watermark equals the fixture horizon. |
| `cases.cheap-invalid` | Few tokens with a proven security failure: gate failure, `NOT_ACCEPTED`, cost `UNAVAILABLE`, penalized and never rewarded. |
| `cases.repaired-accepted` | 2 attempts, validated acceptance, 0.0735 USD counted once; chain-only attribution earns no credit. |
| `cases.unknown-usage` | Unknown and not-measured counters are counted as such, not as zero; the cost is a separate `PARTIAL` lower bound. |
| `cases.incomparable-context-and-stage` | The same task under two context fingerprints, and the same task routed for two stages, are incomparable pairs (`context`, `stageKind`); identical keys form the only comparable pair. |
| `cases.currencies-not-summed` | Complete costs are summed per currency only. |
| `cases.abstention-and-no-eligible-route` | A tier-capped stage abstains, an unserved stage has no eligible route, and both are `NOT_EXECUTED`. |
| `cases.missing-counterfactual` | Adaptive choices of a route the log never executed are reported as coverage gaps with no outcome; an executed but unexported outcome is `UNOBSERVED`. |
| `adaptation.improvement-and-degradation-visible` | On fixtures whose direction is known, the report shows both an improvement (+16 validated acceptances) and a degradation (−5). This tests the harness's sensitivity. No improvement is required on the measured workload. |
| `bounds.respected` | Tasks, cohorts, ledger and observations are within bounds, and a 1025-task fixture is rejected. |
| `store.*` (optional arm) | Covers seven points (listed below). |

The `store.*` checks cover:
- temporary-store cleanup;
- a complete and explained import: exactly one revision-0 observation per finished execution (`exe-0` to `exe-14`),
  each answering its own logged decision, and the unfinished execution rejected as `MISSING_OUTCOME`;
- two independent recordings exporting identical fixtures and Store export digests;
- deterministic replay;
- the derived-not-consumed control, zero eligibility violations and snapshot watermarks;
- missing counterfactuals;
- Store costs reaching the samples unchanged, with incremental state equal to a rebuild.

## Reporting

Each arm has a [`ReplaySummary`](../../monada-neuron-evaluation/src/main/java/monada/neuron/evaluation/routing/ReplaySummary.java),
reported per routed stage kind and in total.

**Stage figures** cover:
- decisions and selections;
- abstentions by reason, and no-eligible-route counts;
- selections by basis: `COLD_START` and `HOST_PRIORITY` count as baseline fallback;
- coverage: `OBSERVED`, `MISSING_COUNTERFACTUAL`, `UNOBSERVED` or `NOT_EXECUTED`;
- validated acceptances and gate failures;
- routed-stage tokens per kind: the known sum, plus separate counts of known, unknown, not-measured and absent counters;
- feedback per score rule or no-update reason, state updates, credits and eligibility violations.

**Chain figures** are whole-execution facts, reported once per execution and never per stage or route:
- attempts and rework;
- cost status counts;
- the per-currency sum of `AVAILABLE` costs;
- the per-currency `PARTIAL` lower bound.

**Pairing** reports directly comparable versus incomparable pairs of observed executions of one task, with the
differing fields named. The key is the contract's ("Direct comparable pairs"): scope, task, `stageKind`, the three
fingerprints and the evaluation policy id and version. The replay adds the routing policy id and version and the policy's
`ResourceObjective` binding. Every identity is a separate component, never a concatenation, because opaque tokens may
contain any separator. Cohort summaries are observational.

**Comparison with the static arm.** Each `vsStatic` line compares the same task's decision to the static decision, per
task. It reports changed decisions, gained and lost validated acceptances, and changes that cannot be scored
(`unscored`) because one side has no observed outcome.

## Measurement method

- **Instrument.** `EvaluationMetricsCollector`, the same one the other checked evaluations use.
- **Operation.** One op is one replayed decision; an iteration replays the whole workload fixture under one arm.
- **Iterations.** Quick: 1 warmup and 3 measured. Full: 3 warmup and 10 measured.
- **Timed window.** The replay runs without recording samples or events.
- **Validation.** Each run replays without recording samples, but it still keeps a collision-free `DecisionTrace` of
  every decision: the advised `RouteKey`, compared by full id and version and never by hash, plus packed kind, basis,
  abstention and coverage, and the snapshot watermark. The arrays are sized once per run, so filling them allocates
  nothing per decision. The trace also confirms the unrecorded (timed) path decides exactly as the recorded path the
  checks verified. Each measured run must match its untimed reference on decision trace, delivered observations and
  learned-state digest. The comparison runs outside
  the timed window (`validationInWindow=false`): in the next iteration's untimed setup, and once more after the
  collector returns, because the collector runs no setup after its last iteration.
- **Setup.** Fixture construction, the reference run and all semantic checks run before measurement.
- **GC telemetry.** JVM-wide, so it includes setup and validation.
- **Retained memory** (`modeledRetainedStateBytes`) is a **model**:
  - 400 B per cohort and 350 B per ledger entry;
  - not a measurement;
  - kept apart from measured allocation per op and measured latency.

## Sample results (exploratory, not a gate)

**Environment:** Linux 7.2.9 (Fedora 44, x86_64), 16 processors, OpenJDK 27 (Zulu 27+35), G1, 3832 MB max heap. Full
mode.

| Benchmark | Scale | Mean | p95 | Alloc / op |
| :--- | :--- | :--- | :--- | :--- |
| `RoutingReplay.static` | 960 tasks | 6.20 ms | 7.03 ms | 6.88 KB |
| `RoutingReplay.derived-not-consumed` | 960 tasks, 20 cohorts | 22.60 ms | 23.92 ms | 27.13 KB |
| `RoutingReplay.adaptive` | 960 tasks, 20 cohorts, 910 ledger entries, 51 rebuilds | 489.93 ms | 533.35 ms | 489.56 KB |
| `StoreRoutingReplay.static` | 16 exported tasks | 0.86 ms | 1.03 ms | 7.48 KB |
| `StoreRoutingReplay.derived-not-consumed` | 16 exported tasks | 1.82 ms | 2.27 ms | 16.16 KB |
| `StoreRoutingReplay.adaptive` | 16 exported tasks | 2.54 ms | 3.16 ms | 19.96 KB |

These are single runs on one machine. Across the four full runs made for this PR, adaptive mean latency varied between
397 ms and 490 ms, so differences within that range are noise, not a trend.

Modeled retained state at the end of the workload: 0 B for static, 8,000 B for derived-not-consumed (20 cohorts) and
326,500 B for adaptive (20 cohorts plus 910 ledger entries). This is a model, not a measurement.

**Workload figures (full, 960 tasks).** The workload is constructed, so its arm difference is a property of its
residues, not evidence about real routing:

| Arm | Validated acceptances | `AVAILABLE` / `PARTIAL` / `UNAVAILABLE` cost | Available USD | Attempts / rework | Baseline fallback |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `static` | 380 of 910 observed | 349 / 31 / 530 | 16.3380 | 936 / 26 | 910 |
| `derived-not-consumed` | 380 of 910 | 349 / 31 / 530 | 16.3380 | 936 / 26 | 910 |
| `adaptive` | 767 of 910 | 698 / 69 / 143 | 38.2515 | 970 / 60 | 139 |

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

- **Adaptive cost.** The adaptive arm is the slowest by roughly an order of magnitude, and it was not profiled. The
  complexity bound points at correction-triggered rebuilds, each `O(N·L)` over every delivered observation (51 rebuilds
  with a ledger of up to 910 entries). That is the case for the deferred `RoutingStateCheckpoint` and compaction
  (below), not a claim that they are needed at production scale.
- **Unvalidated reference values.** The reference adaptive values (`adaptive-routing-state/1`) are untuned. The drift
  scenario shows they can follow stale rewards for five tasks. Nothing here validates them.
- **Measurement noise.** Latency and allocation are single-machine figures over small fixtures. RSS deltas are dominated
  by JIT and GC noise.

## Reproducibility

| Digest | Value |
| :--- | :--- |
| `fixture.workload.digest` (full) | `1b9f63e350be74de2594d0f982e2b10a5357d6c03f5e645387fbd343ea5f6f1c` |
| `workload.reportDigest` (full) | `35d9fae2a2b52d64b9ad8c35feba473f23fa9f46c471cbad73232d51e8b97914` |
| `store.fixtureDigest` | `2c8ad462aa292c92a9c1311f85966f6c4e428db73fa91690c78c92087873ca66` |
| `store.exportDigest` (Store tooling) | `f11276157baf9565a14ea07a386502ac8c5a8b3656a6744fb2861f00b4ae2ee8` |
| `store.reportDigest` | `df6bee795f6240561ed91a73a32de3abd3c1e3bbd92f00893055c3a1787ff151` |

- **Digests.** All are SHA-256 over canonical lines: records render in declaration order, every collection in a defined
  order, and Node doubles as exact bit patterns. The report digest orders summaries by arm, whatever order the caller
  passes them in, and rejects two summaries of the same arm. They exclude timings, timestamps and environment.
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
- **Checkpoint deferred.** ADR 0030 deferred `RoutingStateCheckpoint` and ledger compaction to #67, but the issue's
  allowed scope excludes core changes. This harness instead verifies the equivalence such a checkpoint must satisfy:
  incremental state equals a rebuild from scratch, digest for digest. The core checkpoint type and compaction remain
  follow-up work (see ADR 0030).
- **No core change.** The reference adaptive values are not revisited, and nothing in the core is tuned.
