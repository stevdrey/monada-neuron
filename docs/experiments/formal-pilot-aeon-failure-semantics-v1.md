# Bounded Formal Pilot: Parallel Aeon Failure Semantics (`formal-pilot-aeon-failure/1`)

## Status

| Field | Value |
| :--- | :--- |
| Pilot version | `formal-pilot-aeon-failure/1` |
| Issue | [#202](https://github.com/stevdrey/monada-neuron/issues/202) (Agent Skills 3/5, Epic [#199](https://github.com/stevdrey/monada-neuron/issues/199)) |
| Scope source | [`pstack-skill-audit-v1.md`](pstack-skill-audit-v1.md), practice P2 (techniques FV1 to FV9, SC2 to SC6) |
| Code checked | `BoundedParallelAeonCoordinator` direct path, blob `2af372e13d35`, and `DeterministicAeonCoordinator` as oracle, blob `6070537ba47e` |
| Last confirmed at | `main` at `9861b56b81404c3a2c5908daa38a78809dc428e7` (product code is unchanged by this pilot) |
| Date | 2026-11-10 |
| Consumer | [#204](https://github.com/stevdrey/monada-neuron/issues/204) (go/no-go) |
| Result | All five ADR 0016 properties hold on every schedule within the bounds. Both deliberately false properties and all six scratch breaks are caught. No finding against the code. |

**What this pilot claims.** Within the model and bounds stated below, every schedule of the direct path satisfies the
five properties in "Properties". **What it does not claim.** It is not a proof that the coordinator, or the
application, is correct. It says nothing outside the bounds, about the contextual path, about interruption of a task
that is already running, or about the exclusions listed below.

**No ADR.** The pilot adds a test-only harness, a Gradle task that is not part of `test`, and this report. It fixes no
cognitive boundary, concurrency model, contract, data layout, or dependency, so none of the ADR triggers in
`AGENTS.md` applies. Whether to adopt the technique is #204's decision. No skill is generated (FV10).

## Reproduce

```bash
./gradlew formalPilotCheck                          # bound 4: 17,500 schedules, 2 to 4 s of exploration
./gradlew formalPilotCheck -PformalPilotMaxInputs=5 # bound 5: 360,328 schedules, 27 to 88 s (host load)
scripts/formal-pilot-mutants.sh                     # baseline plus six scratch breaks in a temporary copy
```

`formalPilotCheck` writes `build/reports/formal-pilot/exhaustive-max-inputs-<n>.txt` and `negative-controls.txt`.
The logs recorded for this report are in [`formal-pilot-aeon-failure-semantics-v1/logs/`](formal-pilot-aeon-failure-semantics-v1/logs/).
`./gradlew test` excludes the pilot through the JUnit tag `formal-pilot`; the pilot's sources still compile with the
other tests, so a refactoring that breaks them is seen.

## Target choice (FV1)

The audit shortlisted three candidates. This pilot takes the first.

| Candidate | Chosen | Reason |
| :--- | :--- | :--- |
| `BoundedParallelAeonCoordinator` failure semantics (ADR 0016 decision 5) | Yes | The only candidate with real thread interleavings, where tests that run one schedule say least. `DeterministicAeonCoordinator` is an existing oracle (decision 6). |
| Cross-cycle feedback consumption (ADR 0021) | No | Sequential; ordinary example tests already cover each branch. |
| All-or-nothing compaction (ADR 0031) | No | Single-threaded and outside the behaviors #202 lists. |

Within the coordinator, the pilot checks the **direct** path (`coordinate` without a `CognitiveContext`). The contextual
path is used only under `ContextualParallelism.EXPERIMENTAL_PARALLEL`, has a separate wave structure, and is excluded.

## Mechanism choice (FV2)

| Mechanism | Chosen | Reason |
| :--- | :--- | :--- |
| JUnit-driven exhaustive schedule enumeration of the real code | Yes | No new dependency (JUnit is already the test platform). Runs the unmodified coordinator, so the model cannot drift from the code in what it executes, and every counterexample is already a replay on real code (SC5). |
| TLA+/PlusCal with TLC | No | Needs an unpinned download and a JVM check on JDK 27 that nobody has done (the audit's SC1 note). A separate spec would also need its own correspondence argument and replay step. |
| jcstress | No | New dependency and harness. It samples schedules under real contention; it does not enumerate them, and it cannot produce a minimal counterexample. |
| Property-based library (for example jqwik) | No | New dependency. It randomizes inputs, while the hard part here is the schedule, which a library does not control. |
| Stress test with real threads | No | Cannot show which schedules were covered; a pass carries no bound. |

The technique is stateless model checking in the CHESS sense: the scheduler is under the harness's control, and each
schedule is a fresh run that replays a prefix of choices and then explores depth-first.

## Model

### Code-to-model correspondence (FV3)

Line numbers are those of blob `2af372e13d35` of `src/main/java/monada/neuron/aeon/BoundedParallelAeonCoordinator.java`.

| Model element | Code | How the harness realizes it |
| :--- | :--- | :--- |
| Coordinator step | `coordinate`, lines 226 to 297 | Runs unmodified on its own thread, with threshold 1 and `INDEPENDENT_READ_ONLY`, so the direct parallel path is always taken (lines 242 and 756 to 765). |
| `Submit(i)` | `submitDirectTasks`, `executor.execute(tasks[i])`, line 824 | The harness is the `Executor`; it records the `FutureTask` and does not run it. |
| `Complete(i)`: task `i` runs to completion in one step | `FutureTask.run` with the body at lines 807 to 817 and `done()` at lines 820 to 822 | The harness calls `run()` on the recorded task. The scripted engine returns a result unique to input `i` or throws `InputFailure(i)`. |
| Choice point "worker finishes while the coordinator is between steps" | Any point where the coordinator calls `execute` (lines 254 and 285) | Inside `execute`, the harness either returns or first completes one pending task, and chooses again. |
| Choice point "coordinator waits" | `completedTaskIndexes.take()`, line 831 | The harness waits until the coordinator thread is `WAITING` and the completion queue is empty, then completes one pending task. |
| `Consume(i)`, `Reconcile`, `Fail(f)` | Lines 267 to 282, `collectCompletedFailures` (860 to 876), `cancelTasks` (851 to 858), `rethrowDeterministicFailure` (895 to 908) | Not modeled separately: the real code runs them between two harness choices, so they are one atomic coordinator step. |
| Refill | Lines 284 to 294 | Real code; observed through the next `execute`. |
| Oracle | `DeterministicAeonCoordinator.coordinate`, lines 39 to 70 of blob `6070537ba47e` | Run on a fresh copy of the same workload before each schedule. |

The harness finds the completion queue reflectively in the submitted anonymous `FutureTask` (the coordinator does not
expose it). If that capture disappears, the harness fails with a message naming `submitDirectTasks`; it never passes
silently.

### States and actions

A state is: which tasks are submitted, which have completed (with success or failure), which are cancelled, and
where the coordinator is (inside `execute`, waiting in `take()`, or ended). The scheduler's actions are `Complete(i)`
for any submitted task that is neither done nor cancelled, plus "return from `execute`" when the coordinator is
inside it. Every other step is the real coordinator's.

### Fairness assumptions

- Every submitted task that is not cancelled eventually runs to completion: the executor does not starve or reject
  tasks, and propagation terminates.
- The coordinator thread is not interrupted by its caller.

Under these assumptions the explorer also requires that the coordinator never waits while no task can run, and that
it ends; a run that neither waits nor ends within 10 seconds counts as a violation. The harness then interrupts the
coordinator and joins it for up to 10 more seconds; if it still has not ended, its outcome is recorded as `did not
end` and the run is reported like any other counterexample.

### Bounds (FV6, SC6)

| Bound | Default (`formalPilotCheck`) | Also run |
| :--- | :--- | :--- |
| Inputs | 1 to 4 | 1 to 5 |
| `maxParallelism` | 2 to `inputs + 1` (2 is the smallest window on the parallel path; `inputs + 1` covers a window larger than the workload) | same |
| Failing inputs | Every subset (all `2^inputs` masks) | same |
| Schedules | All, within the atomicity of "Model" | same |

No property went unchecked because of a bound: both explorations ran to completion. Bound 6 did not complete within
10 minutes and was stopped; it is not part of the evidence.

### Exclusions

- **Contextual path** (`coordinate` with a `CognitiveContext`, lines 300 to 397 and `reconcileContextualWave`).
- **A task interrupted while it runs.** A task here completes in one step, so `cancel(true)` reaches only tasks that
  have not started. ADR 0016 already states that a processor that ignores interruption cannot be stopped; the
  existing test `cancelsInterruptibleSiblingAfterEarliestFailure` covers the cooperative case on one schedule.
- **A task completing inside the coordinator's own reaction**, between `collectCompletedFailures` and `cancelTasks`.
  Such a task's failure is neither suppressed nor cancelled. ADR 0016 attaches only *completed* concurrent failures,
  so this is allowed behavior, but the pilot does not exercise it.
- **Caller interruption of the coordinator, executor rejection, and failures thrown from `done()`.** See
  "Observations". Since #208 the first two are covered by unit tests rather than by the explorer.
- **Shared exception instances.** Each failing input throws its own instance. With one instance thrown by several
  inputs, `rethrowDeterministicFailure` skips the primary by identity, and repeated `addSuppressed` calls on a shared
  instance would accumulate across calls; that is not checked.
- **Eligibility and fallback** (threshold, `maxParallelism == 1`, `SEQUENTIAL_ONLY`): fixed so the parallel path is
  taken; existing tests cover the fallback.
- **Memory model.** Runs use real threads with the harness serializing them, so data races that need truly
  simultaneous execution are outside the model.

## Properties (FV7)

Each property is a plain sentence; the code is `FailureSemanticsProperty` in
`src/test/java/monada/neuron/aeon/formal/`.

| Property | Source | Sentence | Harm if broken |
| :--- | :--- | :--- | :--- |
| `MATCHES_SEQUENTIAL_ORACLE` | ADR 0016 decisions 3, 5, 6 | `coordinate` returns exactly the sequential oracle's results in input order, or throws the exception of the same input as the oracle (the earliest failing input). | The result or the reported failure would depend on thread timing. |
| `SUPPRESSES_COMPLETED_LATER_FAILURES` | Decision 5 | The suppressed exceptions are exactly the failures of later inputs whose tasks had completed when `coordinate` threw, each once, in input order. In this model no task completes while the coordinator reconciles, so those are the later failing inputs in the run's completion order. | Diagnostics of concurrent failures would be lost, duplicated, or in an order that depends on thread timing. |
| `BOUNDED_IN_ORDER_SUBMISSION` | Decision 1 | No more tasks are submitted than there are inputs, no submission leaves more than `maxParallelism` tasks unfinished, and every task that runs propagates the input matching its submission position. A task cancelled before it runs is not checked for its input; its position is still counted. | A caller-supplied executor would receive more work than the configured bound. |
| `NO_RUNNABLE_TASK_AFTER_END` | Decision 5 | When `coordinate` returns or throws, every submitted task has completed or been cancelled, so none can still start. | Discarded work would keep using CPU after the call failed. |
| `NO_WAIT_WITHOUT_PENDING_WORK` | Progress under the fairness assumptions | The coordinator never waits for a completion while no submitted task can run, and always ends. | The calling cognitive cycle would hang. |

### Deliberately false properties (negative controls)

These are stronger than ADR 0016. The check must refute them, which shows it can fail and that it explores
completion orders other than input order.

| Property | Sentence | Smallest counterexample |
| :--- | :--- | :--- |
| `EVERY_LATER_FAILURE_IS_SUPPRESSED` | Every failing input after the thrown one is attached as suppressed. | 2 inputs, window 2, both fail. Schedule: `execute(task 0)`, `execute(task 1)`, task 0 completes with `InputFailure(0)` while the coordinator waits; the coordinator throws `InputFailure(0)` with no suppressed exception and cancels task 1 before it runs. |
| `FIRST_COMPLETED_FAILURE_IS_THROWN` | The thrown exception is the failure that completed first. | 2 inputs, window 2, both fail. Task 1 completes with `InputFailure(1)` first, then task 0 with `InputFailure(0)`; the coordinator throws `InputFailure(0)` with `InputFailure(1)` suppressed. |

The first counterexample is the precise meaning of ADR 0016's "subsequent *completed* concurrent exceptions": the
suppressed set depends on the schedule, and the thrown exception does not. The test
`smallestCounterexampleReplaysIdenticallyOnTheRealCoordinator` replays it from its choice list and gets the same
events and outcome (SC5).

## Results

JDK: OpenJDK 64-Bit Server VM 27+35 (Azul Zulu 27.28+101-CA), Gradle 9.8.0, Linux x86-64, 16 hardware threads,
JUnit 5.10.0. No JVM flags beyond the project's `--add-modules jdk.incubator.vector`.

| Bound | Workloads | Schedules | Longest schedule | Exploration time | Gradle wall time | Properties that hold | Negative controls refuted |
| :--- | ---: | ---: | ---: | ---: | ---: | :--- | :--- |
| 4 inputs | 98 | 17,500 | 8 events | 1.7 to 4.1 s | about 8 s with a warm daemon | 5 of 5 | 2 of 2 (3,654 and 5,419 schedules) |
| 5 inputs | 258 | 360,328 | 10 events | 26.6 to 83.4 s | 28 to 87.9 s | 5 of 5 | 2 of 2 (87,276 and 147,104 schedules) |

Times are single observations on one machine (same host, different load: the slower bound-5 run shared the CPU with other builds; an independent rerun took 26.6 s of exploration) and are not a performance claim. Schedule and violation counts are exact and reproduced identically. The schedule count grows by about 20 times per extra input, so bound 6 is impractical with this approach.

### Scratch breaks (FV4)

`scripts/formal-pilot-mutants.sh` copies the checkout to a temporary directory, applies each patch from
[`mutants/`](formal-pilot-aeon-failure-semantics-v1/mutants/) to the coordinator, and runs the bound-4 check. The
checkout is never edited. Each property is shown failing on at least one break.

| Mutant | Change | Target property | Result | Smallest counterexample |
| :--- | :--- | :--- | :--- | :--- |
| `m1-first-completed-failure` | Throw a failure as soon as its task completes | `MATCHES_SEQUENTIAL_ORACLE` | Killed (3,499 schedules), also `SUPPRESSES_COMPLETED_LATER_FAILURES` (3,904) | 2 inputs, both fail; task 1 completes first; `InputFailure(1)` is thrown where the oracle throws `InputFailure(0)`. |
| `m2-no-sibling-cancel` | Drop `cancelTasks` on the failure path | `NO_RUNNABLE_TASK_AFTER_END` | Killed (6,446) | 2 inputs, input 0 fails first; task 1 is still runnable after `coordinate` threw. |
| `m3-unbounded-window` | Submit every input at once | `BOUNDED_IN_ORDER_SUBMISSION` | Killed (6,376) | 3 inputs, window 2; 3 tasks in flight after `execute(task 2)`. |
| `m4-reversed-suppressed` | Attach suppressed failures in reverse order | `SUPPRESSES_COMPLETED_LATER_FAILURES` | Killed (2,997) | 3 inputs, all fail; `InputFailure(0)` suppresses `[2, 1]`. |
| `m5-lost-refill` | Never submit the last input | `NO_WAIT_WITHOUT_PENDING_WORK` | Killed (184), also `MATCHES_SEQUENTIAL_ORACLE` (184) | 3 inputs, window 2, no failure; after tasks 0 and 1 complete, the coordinator waits with nothing to run. |
| `m6-no-suppressed` | Drop `addSuppressed` entirely | `SUPPRESSES_COMPLETED_LATER_FAILURES` | Killed (9,245) | 2 inputs, both fail; task 1 completes, then task 0; `InputFailure(0)` is thrown with no suppressed exception where `[1]` had completed. |

The baseline (no patch) passes in the same run. Full output: [`logs/mutants.txt`](formal-pilot-aeon-failure-semantics-v1/logs/mutants.txt).

### Findings (FV5)

None. On unmodified code no property is violated within the bounds, so there is no counterexample to replay and no
Issue to open against the coordinator.

**Correction during review.** The first version of the suppression property only checked that the suppressed
exceptions were later, distinct, and in order, so an empty list always passed: a scratch break that drops
`addSuppressed` entirely survived it (found by the automated review of PR #207). The property now requires the
suppressed list to equal the completed later failures, and mutant `m6-no-suppressed` shows it. The same review found
that a coordinator that ignores the harness's interrupt left the outcome empty and aborted reporting; it is now an
explicit `did not end` outcome, covered by the test `aCoordinatorThatNeverEndsIsReportedAsAViolation`.

A second, local review hardened the harness for broken coordinators and the script for odd checkouts:
- A coordinator thread that outlives its schedule is rejected by the closed executor, so it can no longer add
  choices to the path the explorer reads. The test above now runs the real harness with a propagation that ignores
  the interrupt, and checks that the released thread ends without changing the path.
- The sequential oracle is computed once per workload instead of once per schedule.
- Waiting for the coordinator yields up to 1,024 times, then parks with a growing delay of up to 100 µs. Bound 4
  explores as fast as with the former unbounded yield loop (about 1.7 s in both over three runs each), and a
  coordinator that never reacts no longer keeps a core busy for the whole timeout.
- An invalid `-PformalPilotMaxInputs` (not an integer, below 2, or above 30) fails with a message that names the
  value, instead of silently running bound 4.
- The mutant script skips tracked files deleted from the working tree, and reports a mutant whose build fails as
  NOT KILLED and continues with the next one, instead of stopping the run.

### Observations outside the properties

These are not violations of ADR 0016, which speaks only of input-task failures, and the pilot did not explore them:

1. If the caller interrupts the coordinator thread while it waits (`awaitCompletedTaskIndex`, lines 829 to 836), it
   throws `IllegalStateException` without cancelling tasks still in flight.
2. If the executor rejects a refill submission (line 824), the exception leaves earlier tasks in flight uncancelled.

Both leave discarded work running after the call ends. Neither has been reproduced by a test. Whether that is
acceptable is a question for the owner of ADR 0016, tracked in
[#208](https://github.com/stevdrey/monada-neuron/issues/208).

**Resolved by #208.** `BoundedParallelAeonCoordinatorAbnormalExitTest` reproduced both observations on the code
above, and the same gaps on the contextual path. ADR 0016 decision 7 now states the abnormal-exit contract: every
submitted task is done or cancelled before any exception leaves `coordinate`, and the caller's interrupt status is
preserved. The direct path now cancels its unreconciled window from a single handler around the submission loop, which
also replaces the separate `cancelTasks` call on the input-failure path; the contextual path cancels the current wave
the same way. The line numbers in this report still refer to blob `2af372e13d35`. The six mutant patches were
regenerated against the new layout with the same intent, except that `m2-no-sibling-cancel` now drops the single
abnormal-exit cancellation, which is where sibling cancellation lives. The pilot's model and exclusions are unchanged:
it still assumes no caller interrupt and no rejection, so those cases are covered by the unit tests, not by the
explorer. In the rerun the baseline passes, every mutant is killed by its target property, and every count is the
same as in the table above: [`logs/mutants-issue-208.txt`](formal-pilot-aeon-failure-semantics-v1/logs/mutants-issue-208.txt).

## Cost, portability, and maintainability (FV8)

| Aspect | Observation |
| :--- | :--- |
| Dependencies | None added. JUnit and the JDK only; no runtime, CI, or published-artifact change. |
| Tool availability | Runs wherever `./gradlew test` runs. No download, network access, or second language. |
| Runtime | One command; bound 4 explores in 2 to 4 s, bound 5 in 27 to 88 s, both under the audit's five-minute limit. |
| Portability | Relies on `Thread.getState()` and an empty-queue check to detect that the coordinator waits; both are standard Java SE. Each schedule gets a fresh coordinator thread, so a coordinator that never ends cannot reach the next schedule; reusing threads would remove that isolation. A 10-second control timeout turns a harness that loses control into a reported violation, not a hang; a coordinator that also ignores the interrupt is reported as `did not end`. |
| Size | About 1,200 lines of test code in 8 files, 6 mutant patches, one script. |
| Maintenance risk | The harness reads one private detail reflectively: the completion queue captured by the direct task. A refactoring of `submitDirectTasks` can break it; the harness then fails with a message that names the method. Line numbers in the correspondence table need refreshing when the coordinator changes. Nothing checks staleness automatically before #204 decides. |
| Gate status | Not a CI gate and not part of `./gradlew test`. Adoption, wider use, or a CI job is #204's decision. |

## Files

- `src/test/java/monada/neuron/aeon/formal/`: `ScheduledCoordinatorRun` (harness and scripted executor),
  `ChoicePath` (depth-first choice replay), `ScheduleExplorer` (bounds, statistics, smallest counterexamples),
  `FailureSemanticsProperty` (properties), `ScheduleRun`, `ScheduleWorkload`, `InputFailure`, and the tagged
  test `BoundedParallelFailureSemanticsPilotTest`.
- `build.gradle.kts`: the `formalPilotCheck` task and the `formal-pilot` exclusion from `test`.
- `scripts/formal-pilot-mutants.sh` and `formal-pilot-aeon-failure-semantics-v1/mutants/*.patch`.
- `formal-pilot-aeon-failure-semantics-v1/logs/`: the summaries and mutant log recorded for this report.
