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
- `requestOrdinal` must be strictly increasing in replay order, as the contract requires per scope.
- The evidence policy must be the partition's evaluation policy (same id and version), so feedback is never assessed
  under a policy the requests do not name.
- The observations of one logged decision must be revisions of one sample: one `experienceRef`, strictly increasing
  revisions (gaps allowed) and ledger sequences, and every corrected revision superseding the same original position,
  which is revision 0's ledger sequence when revision 0 is listed. That is how the Store translator versions a sample.
  Two separately referenced samples can therefore never credit one execution twice.
- Cohorts are bounded at ≤ 256.
- Observations are bounded at ≤ 4096, so a full rebuild stays possible.

**Order and timing.**
- **Decision order.** Tasks are decided in total order: cutoff first, then `requestOrdinal` (the caller's issuance
  order), then the other `DecisionRef` fields in code-point order. A batch of requests that share a cutoff therefore
  replays in issuance order, never in the order of its opaque ids.
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
  removal. `P` is bounded by the fixture's observation limit, 4,096, not by its task count: a synthetic task adds at
  most two observations when it executes, but a chosen-route-only fixture queues every logged revision before the first
  decision.
- The outcome oracle is a `TreeMap` keyed by route: deterministic order, `R ≤ 32`.
- There is no cache, parallelism or SIMD. The workload is small and the reference algorithm is the point.

**Cost.**
- Time is `O(T (R log R + C) + P log P)`, plus `O(N log N + N·L)` per correction rebuild.
- Retained memory is the state (`O(C + L)`), the delivered observations (`O(P)`) and, when recording, one sample per
  task and one event per delivery.
- Nothing is retained between runs. There is no clock, no randomness, no background task and no session.

## Fixtures (`forge-routing-replay-v3`)

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
| Repaired chain (`0.0420 + 0.0315`) | 0.0735, counted once; the repair attempt id is `repair-` plus 32 hex digits of the SHA-256 of the routed attempt id, 39 code points whatever that id's length |
| Unknown-usage chain, including one later corrected to accepted | `PARTIAL` lower bound 0.0270 |
| Two-currency chain | 0.0420 USD and 0.0390 EUR, never summed together |

`ReplayCost` mirrors the Store's `CostToAcceptedOutcome` invariants and is never stricter: an unavailable cost has no
amount and each currency appears at most once, but the number of currencies is not capped and a partial or
unavailable cost may come without reasons, as the Store allows.

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
| `eligibility.violations-zero` | Across all arms and stages, none of the following occurs: a reward without a positive, stage-local candidate; a penalty without a negative candidate; credit for a scope, stage, cohort bucket or route other than the executed decision's; a neutral rule that moves a Node. |
| `state.incremental-equals-rebuild` | The final adaptive state digest equals a reset copy rebuilt from every delivered observation, including after correction-triggered rebuilds, and its watermark equals the fixture horizon. |
| `cases.cheap-invalid` | Few tokens with a proven security failure: gate failure, `NOT_ACCEPTED`, cost `UNAVAILABLE`, penalized and never rewarded. |
| `cases.repaired-accepted` | 2 attempts, validated acceptance, 0.0735 USD counted once; chain-only attribution earns no credit. |
| `cases.unknown-usage` | Unknown and not-measured counters are counted as such, not as zero; the cost is a separate `PARTIAL` lower bound. |
| `cases.incomparable-context-and-stage` | The same task under two context fingerprints, and the same task routed for two stages, are incomparable pairs (`context`, `stageKind`); identical keys form the only comparable pair. |
| `cases.currencies-not-summed` | Complete costs are summed per currency only. |
| `cost.completeness-consistent` | Across every fixture and arm, no observed sample whose routed-stage usage is incomplete reports an `AVAILABLE` cost (252 such samples in the full run). Incomplete means `MEASURED` with an unknown or not-measured counter, or with no counter at all (unrecorded); a declared `NO_BILLABLE_USAGE` is complete. |
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
- deterministic replay, comparing decisions, feedback and learned state (including state presence) for every arm;
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
- routed-stage tokens per kind: the exact known sum (a `BigInteger`, so large legal counters never wrap), plus separate
  counts of known, unknown, not-measured and absent counters;
- feedback per score rule or no-update reason, state updates, credits and eligibility violations.

**Chain figures** are whole-execution facts, reported once per execution, identified by scope, task and execution, and
never per stage or route:
- attempts and rework;
- cost status counts;
- the per-currency sum of `AVAILABLE` costs;
- the per-currency `PARTIAL` lower bound.

**Pairing** reports directly comparable versus incomparable pairs of observed executions of one task, with the
differing fields named. A pair needs two different executions: two observations of one execution, such as two routed
attempts, are never paired. The key is the contract's ("Direct comparable pairs"): scope, task, `stageKind`, the three
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
- **Iterations.** Quick: 1 warmup and 3 measured. Full: 3 warmup and 10 measured.
- **Timed window.** Each iteration times `replay.run` without recording samples or events. That includes constructing
  and binding the fresh state; only the validation of the previous run is untimed. Every row records this as
  `timedWindow`.
- **Validation.** Each run replays without recording samples, but it still keeps a collision-free `DecisionTrace` of
  every decision: the advised `RouteKey`, compared by full id and version and never by hash, plus packed kind, basis,
  abstention and coverage, and the snapshot watermark. The arrays are sized once per run, so filling them allocates
  nothing per decision. The trace also confirms the unrecorded (timed) path decides exactly as the recorded path the
  checks verified. Each measured run must match its untimed reference on decision trace, on every delivered
  observation by value and order (with the decision and request each answers, not only their count) and on
  learned-state digest. The comparison runs outside the timed window (`validationInWindow=false`): in the next iteration's untimed setup, and once more after the
  collector returns, because the collector runs no setup after its last iteration.
- **Setup.** Fixture construction, the reference run and all semantic checks run before measurement.
- **GC telemetry.** JVM-wide, so it includes the untimed validation.
- **Retained memory** (`modeledRetainedStateBytes`) is a **model**:
  - 400 B per cohort and 350 B per ledger entry;
  - not a measurement;
  - kept apart from measured allocation per op and measured latency.

## Sample results (exploratory, not a gate)

**Environment:** Linux 7.2.9 (Fedora 44, x86_64), 16 processors, OpenJDK 27 (Zulu 27+35), G1, 3832 MB max heap. Full
mode.

| Benchmark | Scale | Mean per iteration | p95 per iteration | Mean per decision (derived) | Alloc per decision |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `RoutingReplay.static` | 960 tasks | 5.88 ms | 9.85 ms | 6.1 µs | 6.51 KB |
| `RoutingReplay.derived-not-consumed` | 960 tasks, 20 cohorts | 20.48 ms | 25.63 ms | 21.3 µs | 32.00 KB |
| `RoutingReplay.adaptive` | 960 tasks, 20 cohorts, 910 ledger entries, 51 rebuilds | 502.32 ms | 599.29 ms | 523.2 µs | 513.08 KB |
| `StoreRoutingReplay.static` | 16 exported tasks | 1.66 ms | 2.27 ms | 104.0 µs | 9.43 KB |
| `StoreRoutingReplay.derived-not-consumed` | 16 exported tasks | 3.16 ms | 4.20 ms | 197.6 µs | 17.78 KB |
| `StoreRoutingReplay.adaptive` | 16 exported tasks | 3.18 ms | 3.98 ms | 198.9 µs | 20.23 KB |

These are single runs on one machine, and this one ran under a load average of about 4 from other work on the
machine. Across the full runs made for this PR, adaptive mean latency per iteration varied between 397 ms and 578 ms,
`static` between 3.27 ms and 18.38 ms, and `derived-not-consumed` between 16.7 ms and 48.0 ms. The 48.0 ms run was
traced to a real regression, not noise: a reflective encoding in the per-observation sample reference. It was removed
before this run, which measured 20.5 ms. The `static` outlier was immediately followed by 5.72 ms and 5.82 ms with
identical digests. Differences of this size are noise, not a trend. The Store per-decision figures include a
fixed per-iteration cost (a fresh state and bindings) spread over only 16 decisions.

Modeled retained state at the end of the workload: 0 B for static, 8,000 B for derived-not-consumed (20 cohorts) and
326,500 B for adaptive (20 cohorts plus 910 ledger entries). This is a model, not a measurement.

**Workload figures (full, 960 tasks).** The workload is constructed, so its arm difference is a property of its
residues, not evidence about real routing:

| Arm | Validated acceptances | `AVAILABLE` / `PARTIAL` / `UNAVAILABLE` cost | Available USD | Attempts / rework | Baseline fallback |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `static` | 380 of 910 observed | 346 / 34 / 530 | 16.2120 | 936 / 26 | 910 |
| `derived-not-consumed` | 380 of 910 | 346 / 34 / 530 | 16.2120 | 936 / 26 | 910 |
| `adaptive` | 767 of 910 | 697 / 70 / 143 | 38.1990 | 970 / 60 | 139 |

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
| `fixture.workload.digest` (full) | `61bfa1c50c0d1174a312ac12380e628ebaaade95ec6c7720d8947b725b26b62b` |
| `workload.reportDigest` (full) | `143fed9a218acd70a74dd4ae0affc2395cfc379e49cd851c20205e9d7ced052f` |
| `store.fixtureDigest` | `a5408e2dd889eaad33a97ecd3f9d7c1a0a610caef31d31f9fe09be0e2e6753b8` |
| `store.exportDigest` (Store tooling) | `f11276157baf9565a14ea07a386502ac8c5a8b3656a6744fb2861f00b4ae2ee8` |
| `store.reportDigest` | `954d5e7a0882298746c160fdf1001a5b0469d75d3fc6bc88bdf7ee22b8cb4d74` |

- **Digests.** All are SHA-256 over a canonical, typed, length-prefixed encoding (`CanonicalEncoder`), never over
  `toString`:
  - records are walked component by component, and every text is written as its UTF-16 code units prefixed by
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
- **Checkpoint deferred.** `RoutingStateCheckpoint` and ledger compaction are core work tracked in #89 (ADR 0030); #67's
  scope excluded core changes. This harness supplies the evidence such a checkpoint must satisfy: incremental state
  equals a rebuild from scratch, digest for digest.
- **No core change.** The reference adaptive values are not revisited, and nothing in the core is tuned.
