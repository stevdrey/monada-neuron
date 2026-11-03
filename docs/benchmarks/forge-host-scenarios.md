# Forge Host Scenario Evaluation (Issue #103)

## Question

Do the Forge integration seams of issues #95 to #101 hold together when a host sits between each routing decision and
its execution? The suite replays Forge like task sequences through the #67 replay harness
([`forge-routing-evaluation.md`](forge-routing-evaluation.md)) with a scripted person who follows, overrides, defers or
declines the advice, and checks that:

- identical fixtures replay to identical metrics, decision and feedback digests, receipt chain digest and recovery
  digest (AC-1);
- stale scope, a foreign evaluation policy or feature schema, and future evidence never enter an earlier decision
  (AC-2);
- a human disposition alone earns zero learned credit, and an override credits only the executed route under existing
  rules, which under v1 is no route at all (AC-3);
- a restart from an encoded checkpoint matches a from scratch rebuild, or reports plainly that it cannot (AC-4);
- the static and adaptive arms report quality, cost and coverage over observed samples only (AC-5).

It proves contract consistency over synthetic data. It is not evidence of routing quality, agent quality,
authorization, causal effect or economic value, and no figure below is a billing claim.

## Running

```bash
# The whole suite, its checks and its report (no timing; a few seconds)
./gradlew :monada-neuron-evaluation:runForgeHostScenarioEvaluation

# Write the report somewhere else (default build/reports/benchmarks)
./gradlew :monada-neuron-evaluation:runForgeHostScenarioEvaluation -PbenchmarkArgs="--output-dir build/host"

# The suite's JUnit tests, also part of ./gradlew test
./gradlew :monada-neuron-evaluation:test --tests 'monada.neuron.evaluation.routing.Host*' --tests '*RecoveryProbeTest'

# #67 must stay unchanged beside it
./gradlew :monada-neuron-evaluation:runForgeRoutingEvaluation -PbenchmarkArgs="--quick"
```

The runner prints the Markdown report and writes `forge-host-scenarios.json` and `forge-host-scenarios.md`. Exit status:
0 when every check passes, 1 when one fails, 2 for an invalid argument, 4 when the harness itself failed. There is no
optional prerequisite, so status 3 is never used.

## How the host is modeled

Everything lives in `monada-neuron-evaluation`, package `monada.neuron.evaluation.routing`, and uses only the public
routing API. Core Neuron code does not change.

- `TemporalReplay` gains a package private `HostStep` seam. `HostStep.FOLLOW`, the default, runs the advised route of
  every selection and nothing otherwise, which is exactly the #67 behavior; a test replays every #67 fixture in every
  arm and rebuild strategy with and without it and finds identical decision, feedback and state digests and samples.
- `HostScript` is the scripted person: one `HostAction` per task index, the same in every arm. It binds each choice
  through the public `AdviceDisposition` factories over `AdviceReceipt.of(decision)` and records one
  `HostDecisionRecord` per decision. The disposition goes to that record and nowhere else: neither
  `RoutingFeedbackPolicy.derive` nor `RoutingStateStore.consume` has a disposition parameter.
- Only a `Selected` decision executes. An alternative over a selection is the first ranked candidate that is not the
  advised route, and its scripted outcome is generated with `executedRoute` set to that route. An alternative over an
  abstention binds `AlternativeProposed` but runs nothing (spec 0010), because v1 feedback only answers a selection.
  A selection the host defers or declines gets the coverage `HOST_NOT_EXECUTED` and no outcome.
- Each run gets a fresh replay and a fresh script, because a script records what it saw.

## Scenarios (`forge-host-scenarios-v1`)

Scope `forge-host`, the #67 evidence policy (`forge-gates@1`, mandatory `TESTS` and `SECURITY`), the reference
adaptive definition and lexicographic policy with an auto select tier cap of 1, the #67 token prices and positions.
Catalog `host-cat/1` lists `sub-a` (tier 1: implement, review, docs), `sub-b` (tier 1: implement, review), `api-x` and
`api-y` (tier 2: qa). Every task scripts an outcome for every route its catalog lists.

| Scenario | Tasks | What it holds |
| :--- | ---: | :--- |
| `cold-start` | 16 | empty state, both subscription routes cold, every task followed, every route passes |
| `missing-features` | 16 | every odd task leaves `CATEGORY`, `LANGUAGES` and `CHANGE_SIZE` unknown; the host records the readiness assessment and still follows |
| `catalog-change` | 24 | tasks 0 to 11 under `host-cat/1`, 12 to 23 under `host-cat/2` (`sub-b` removed, `sub-c` added) |
| `disposition` | 32 | blocks of four cycle implement, qa, implement, docs; inside each block the person follows, picks an alternative, defers and declines |
| `delayed-acceptance` | 24 | outcomes visible 8 slots after their decision; `sub-a` unavailable for tasks 0 to 3; `sub-a` fails tests, `sub-b` passes |
| `restart` | 32 | as `delayed-acceptance` with a 1 slot delay, plus a correction to task 20's outcome and one to task 4's, both visible after task 15's cutoff |

In `disposition`, qa is served only by the tier 2 routes, so the tier cap abstains with `POLICY_TRADEOFF_UNRESOLVED`
and ranks two candidates; docs is served by `sub-a` alone, so an alternative there falls back to the advised route and
is rejected with `ALTERNATIVE_EQUALS_ADVICE`.

## Probes

- **Catalog probe.** Task 12's request decided over an empty preference under `host-cat/1` and under `host-cat/2`, with
  the same `TrustedRoutingProfile`, compared by `ScenarioComparator`.
- **Foreign evidence probe.** After the `delayed-acceptance` adaptive run, three observations that differ from its first
  delivered one only in scope (`forge-host-other`), evaluation policy version (`forge-gates@2`) or the outcome's feature
  schema version are derived with the real decision and request and consumed into the run's final state.
- **Restart probe.** After the `restart` adaptive run, the suite acts as a host: it rebuilds a fresh store from the
  observations delivered by task 15's cutoff, checkpoints it, encodes it to `routing-checkpoint/1`, then decodes and
  assesses it with `CheckpointRecovery` against fresh target stores. The replay's own checkpoints cannot serve here:
  `RebuildStrategy.CHECKPOINT_INTERVAL` is 32 deliveries, and a correction to an early outcome invalidates every
  checkpoint above it, so the run never keeps one between task 15 and task 20.

| Restart case | How it is built | Expected |
| :--- | :--- | :--- |
| `post-checkpoint-correction` | export without task 4's correction | `Compatible`, `rebuildFrom` rebuilds, state digest equals a scratch rebuild of the same export |
| `pre-checkpoint-correction` | whole export | `rebuildFrom` stops with `SUPERSEDES_COMPACTED`; `RecoveryPlan.after` is recorded; the scratch fallback equals the run's final state |
| `corrupt-bytes` | byte `length / 2` inverted | `{UNDECODABLE}`, first action `OBTAIN_READABLE_CHECKPOINT` |
| `foreign-scope` | target for scope `forge-host-other` | `{FOREIGN_SCOPE}`, first action `FIX_HOST_CONFIGURATION` |
| `foreign-policy` | target for `forge-gates@2` | `{FOREIGN_EVALUATION_POLICY}`, first action `FIX_HOST_CONFIGURATION` |
| `export-behind` | `ExportState.Observed(checkpointCutoff - 1)` | `{EXPORT_BEHIND_CHECKPOINT}`, first action `OBTAIN_EXPORT` |

The suite never calls `rebuildFrom` on a blocked assessment.

## Checks

Each check fails when it would otherwise pass vacuously.

| Check | Verifies | AC |
| :--- | :--- | :--- |
| `host.deterministic` | two fresh builds and runs of the whole suite give identical lines for every metadata entry, including every fixture, decision, feedback, state, receipt chain and recovery digest, and the same check results | AC-1 |
| `host.leakage.future-evidence` | on `delayed-acceptance` and `restart`, flipping every outcome visible from task 12's cutoff, or removing every later task, leaves each decision at or before it, its watermark and its delivered count unchanged in both arms; fails unless a later adaptive decision changes | AC-2 |
| `host.leakage.foreign-evidence` | the three foreign observations derive `NoUpdate` with `BINDING_SCOPE_MISMATCH`, `BINDING_PARTITION_MISMATCH` and `FEATURE_SCHEMA_MISMATCH`, each `consume` is `Unchanged`, and the state digest is unchanged | AC-2 |
| `host.disposition.no-credit-path` | every applied update answers a followed task that ran the advice; every task the host ran nothing for has no observation or feedback event; fails unless every variant bound, one was rejected and one credit landed | AC-3 |
| `host.override.executed-route-only` | every alternative execution derives `NoUpdate(PROVENANCE_MISMATCH)` naming `EXECUTED_ROUTE`, and the cohorts equal the same run where the alternatives ran nothing; fails unless an alternative ran | AC-3 |
| `host.recovery.parity` | the two correction cases end as the table says with parity, and the `CHECKPOINT` and `SCRATCH` adaptive runs of `restart` decide and learn identically | AC-4 |
| `host.recovery.unrecoverable-reported` | each blocked case reports exactly its blocker set and first plan action and was never rebuilt | AC-4 |
| `host.arms.observed-only` | only `OBSERVED` samples carry an outcome, acceptance or cost; every feedback event and observation names the route the host ran; alternatives are summarized in their own row | AC-5 |
| `host.catalog-change.compared` | the catalog probe is `Compared` with `CATALOG` among its differences and `sub-b` standing `NotInCatalog` after; no decision after the switch selects `sub-b` | AC-5 |
| `control.static-wiring` | the #67 check over every host scenario: the static arm keeps no state and derives nothing | AC-5 |
| `eligibility.violations-zero` | the #67 check over every host run | AC-3 |
| `bounds.respected` | the #67 check over every host fixture | AC-1 |

## Controls

- The static arm is the #62 rule baseline; it keeps no state, so its applied credit is 0 by construction and is reported
  as such.
- The "without alternatives" reference for the override check is the same adaptive run where every alternative runs
  nothing, never a run where the disposition was skipped: a `PROVENANCE_MISMATCH` derivation is recordable and leaves a
  ledger marker, so only a run without the execution is a fair comparison.
- The #67 replay runs unchanged beside the suite: `HostStep.FOLLOW` reproduces it digest for digest.

## Sample results (one run, not a gate)

Run on 2026-11-02, JDK 27 (Eclipse Adoptium, OpenJDK 64 Bit Server VM), macOS, no flags beyond
`--add-modules jdk.incubator.vector`, no warm up (nothing is timed). All 12 checks passed. Costs are hypothetical API
equivalent USD at the #67 list prices, modeled, not billing.

| Scenario | Arm | Validated accepted | Not accepted | No outcome | Learned basis | Applied credits | Modeled cost (USD) |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `cold-start` | static | 16 | 0 | 0 | 0 | 0 | 0.6720 |
| `cold-start` | adaptive | 16 | 0 | 0 | 0 | 16 | 0.6720 |
| `delayed-acceptance` | static | 4 | 20 | 0 | 0 | 0 | 0.2180 |
| `delayed-acceptance` | adaptive | 13 | 11 | 0 | 9 | 24 | 0.7085 |
| `disposition` (advised row) | static | 6 | 0 | 22 | 0 | 0 | 0.2520 |
| `disposition` (advised row) | adaptive | 6 | 0 | 22 | 0 | 6 | 0.2520 |
| `restart` | static | 6 | 26 | 0 | 0 | 0 | 0.3020 |
| `restart` | adaptive | 28 | 4 | 0 | 24 | 32 | 1.5135 |

- `disposition`: 8 follows, 8 alternatives, 8 deferrals, 8 declines per arm. Bound: 6 `Followed`, 6
  `AlternativeProposed` (4 over a selection, which ran, and 2 over the abstention, which did not), 8 `Deferred`, 8
  `Declined`. Rejected: 2 `ADVICE_NOT_SELECTED`, 2 `ALTERNATIVE_EQUALS_ADVICE`. Coverage of the advised row: 6
  `OBSERVED`, 14 `HOST_NOT_EXECUTED`, 8 `NOT_EXECUTED` (the abstentions); the 4 alternative runs are `OBSERVED` in their
  own row. All 6 applied credits answer followed tasks; all 4 overrides derived `PROVENANCE_MISMATCH` and credited
  nothing.
- `missing-features`: 8 tasks per arm miss each of `CATEGORY`, `LANGUAGES` and `CHANGE_SIZE`; every task still ran.
- `catalog-change`: the probe compared with differences `[CATALOG]`, route change `SAME_ROUTE`, `sub-b`
  `Eligible -> NotInCatalog`, `sub-c` `NotInCatalog -> Eligible`.
- `cold-start` never reaches a learned basis in 16 tasks; every adaptive decision there is `COLD_START`.
- Receipts: 608 to 718 bytes each. Checkpoint: 381 bytes. The restart plans were `[REPLAY_FROM_CHECKPOINT]` after the
  later correction and `[USE_OLDER_CHECKPOINT, REBUILD_FROM_SCRATCH]` after the earlier one.
- `host.deterministic` compared 1105 metadata lines across two fresh runs.

## Evidence limits

- The fixtures are synthetic, small (16 to 32 tasks, 144 in all) and fully enumerated; every route passes or fails by
  index arithmetic. The adaptive arm's better acceptance in `delayed-acceptance` and `restart` follows from how the
  fixtures were written, not from any property of real work.
- No Store arm: the suite never reads a real Resonance Store export.
- The foreign evidence probe covers three ways an observation can be foreign, not every one.
- Nothing is timed, so the suite says nothing about latency, allocation or throughput.

## Remaining adoption blockers

- **Host scenarios over a real Store export.** An optional integration arm, like #67's `runForgeRoutingStoreReplay`,
  once Forge records dispositions beside executions.
- **No learning from overrides under v1.** When the host runs another route than the advised one, v1 derives
  `PROVENANCE_MISMATCH` and credits nothing. If Forge needs to learn from overrides, that is a contract v2 proposal
  (credit by executed route with its own provenance rule), never a change to v1.
- **A correction to an outcome at or before a checkpoint cannot be replayed from that checkpoint.** v1 recovery falls
  back to an older checkpoint or a scratch rebuild there, as `RecoveryPlan.after` says; a host must keep the export
  needed for that fallback.
