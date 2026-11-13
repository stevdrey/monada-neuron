## Independent verification result

<!-- verification v1 status=PASS base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=a5c80cdbb8d236967ffbaa579f23aca55b432e18 tree=38adc75303a00261a5e6452b27f6b27fe25bcdba identical=yes criteria=8 satisfied=8 blocking_open=0 -->
**Status:** PASS
**Issue:** #208 (parent #199)
**Snapshot:** base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=a5c80cdbb8d236967ffbaa579f23aca55b432e18 tree=38adc75303a00261a5e6452b27f6b27fe25bcdba (identical at start and end: yes; clean worktree, tree == `HEAD^{tree}`, also recomputed through a throwaway index at the end)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | Test reproduces each case on main first, interruptible + rejecting executor | SATISFIED | C1: the new `BoundedParallelAeonCoordinatorAbnormalExitTest` copied onto base `b7f5d2d` → 9 of 10 fail (interrupt and rejection, direct and contextual; only the decision-5 regression test passes, as it should). C2: all 10 pass on head. Reviewer probes C5/C6 (real thread pools) also fail on base and pass on head. |
| AC2 | After abnormal exit every submitted task is done or cancelled; interrupt status preserved | SATISFIED | `BoundedParallelAeonCoordinator.java` direct `try`/`catch (Throwable)` around submit+reconcile loop → `cancelAfterAbnormalExit(tasks, nextToReconcile, …)`; contextual per-wave `try`/`catch` → `cancelAfterAbnormalExit(waveTasks, 0, …)`; rejected task is created before `execute`, so it is cancelled too. Tests assert `isCancelled`/`isDone` on every recorded task, `runAll()` processes nothing abandoned, interrupt flag set after exit (C2). Reviewer mutants Ma/Mb/Mc are killed (C4). Real-pool probes C5: running tasks are interrupted, queued task never runs, caller flag preserved. |
| AC3 | Input-failure semantics and oracle equivalence unchanged | SATISFIED | C3 `./gradlew test` 2093 tests, 0 failures (1721 core / 292 evaluation / 80 adapter); C7 `./gradlew formalPilotCheck` 5/5 pass; decision-5 `cancelTasks` before `rethrowDeterministicFailure` kept in place; `inputFailureStillRethrowsTheSameInstanceAndCancelsSiblings` asserts same instance, 0 suppressed. All six regenerated mutant patches apply cleanly to head (C8). |
| AC4 | Contextual path inspected, fixed or documented | SATISFIED | Fixed (per-wave handler) and documented (ADR 0016 decision 7 "Context after an abnormal contextual exit"); four contextual tests, killed by mutant Ma (C4); real-pool probe C5. |
| AC5 | ADR 0016 (or follow-up) states the abnormal-exit contract | SATISFIED | `docs/adr/0016-bounded-parallel-aeon-input-coordination.md:53` decision 7 plus new Consequences bullet; pilot report `docs/experiments/formal-pilot-aeon-failure-semantics-v1.md` marks the observations resolved. |
| IM1 (implied) | Cancellation over existing `tasks` window; no new collections on normal path | SATISFIED | Diff adds no collection or allocation on the normal path (only `try`/`catch`; `cancelTasks` loop now stops at first `null`). Complexity: the issue body asked for `O(maxParallelism)`; the owner's issue comment "Revised complexity expectation" revises the direct path to `O(inputCount)` worst case (contextual stays one wave), and ADR 0016 decision 7 *Cost* records it. Covered by `directPathCancelsTheStalledInputWhenLaterInputsCompletedFirst`, killed by mutant Mb (C4). |
| IM2 (implied) | No measurable cost on the normal path | SATISFIED | Structural: the normal path gains only exception-table entries, no allocation or extra calls (diff of `src/main`). No benchmark exists or was run for the coordinator; the change makes no measured-gain claim. |
| IM3 (implied) | Contracts/Store ownership unchanged; decisions 3, 5, 6 unchanged; no forced stop; pilot not a CI gate | SATISFIED | `git diff b7f5d2d HEAD -- src/main | grep '^[+-].*public'` → empty; only `BoundedParallelAeonCoordinator` internals touched; no change to `.github`, `build.gradle.kts`, `settings.gradle.kts`; cancellation is `cancel(true)` only; formalPilotCheck stays a separate task. |

### Findings
None.

### Optional improvements
- The decision-5 path still calls `cancelTasks(tasks, nextToReconcile + 1, nextToSubmit)` directly (not via `cancelAfterAbnormalExit`), so a throwable raised inside that cancellation would replace the input failure rather than be suppressed. Not reachable in practice on JDK 27 (`Thread.interrupt` cannot throw; `done()` only adds to an unbounded `LinkedBlockingQueue`), unchanged from base, and the ADR's "never replaces it" sentence is scoped to the decision-7 handler.
- No repository test exercises the contextual path with a real thread pool and a running cooperative task; reviewer probe C5 shows it works. A copy of that probe would pin it.

### Boundary impact
| Surface | Change to it | Status | Rung | Evidence |
| --- | --- | --- | --- | --- |
| `BoundedParallelAeonCoordinator.coordinate` direct path (concurrent) | Abnormal-exit handler cancels unreconciled window and rethrows the original throwable | cleared | R4 | C1, C2, C4, C5 |
| `BoundedParallelAeonCoordinator.coordinate` contextual path (concurrent, `CognitiveContext`) | Per-wave handler cancels the wave; context left as documented, no synthetic events | cleared | R4 | C2 (context trace assertions), C4, C5 |
| Aeon coordinator public API / published artifact | None: no public signature, module, or package change | cleared | R4 | `git diff … src/main | grep public` empty |
| Formal pilot harness and mutant patches | Mutant patches regenerated against new layout; harness unchanged | cleared | R4 | C7, C8 (all six apply) |

Concurrent-path checklist: determinism — recording executors make tests deterministic, input-order results unchanged (C3); cancellation/interrupt on every exit — direct and contextual handlers catch `Throwable` (C2, C5); hot-path allocation — none added; memory lifetime / SIMD / native — n/a; reference equivalence — formalPilotCheck oracle property passes (C7); fallback — sequential oracle untouched. Consumer tasks (`consumerSmokeTest`, `jpmsConsumerTest`, host consumer tasks) not run: no published API, module, or format surface touched; the adapter module's tests ran as part of C3.

Safety fact: every task the coordinator has created (including one whose `execute` was rejected) from the first unreconciled index on is passed to `cancel(true)` before any throwable leaves `coordinate`, and the interrupt flag is restored before the wrapping `IllegalStateException` is thrown — R4 (C2, C4, C5).

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | rejection of first initial-window submission, of a refill, of first submission of a later contextual wave; maxParallelism 2 and 3; inputs 2–6 |
| duplicates/ordering | yes | stalled input 0 while inputs 1..5 complete (window scan beyond `maxParallelism`) |
| failure paths / partial failure | yes | caller interrupt while waiting, executor rejection, reconciliation failure (context closed by processor), input failure (decision 5 unchanged) |
| concurrency/cancellation/shared state | yes | real `FixedThreadPool` interrupt (direct: existing test; contextual: probe C5); saturated `ThreadPoolExecutor` with `AbortPolicy` (probe C5); `cancel(false)` mutant killed |
| determinism / iteration order | yes | full suite and formalPilotCheck oracle equivalence (C3, C7) |
| floating-point tolerance | n/a | no numeric computation changed |
| text/encoding/locale | n/a | no text input or parsing in the change |
| compatibility (API, JPMS, formats, consumers) | yes | no public signature/module/format change; adapter module built and tested |
| resource behavior | yes | no new allocation on normal path; cleanup at most once per failed call; worst-case `O(inputCount)` scan accepted by owner comment |
| numerical claims | n/a | no measured-gain claim made |
| documentation/ADR/skill-mirror consistency | yes | ADR decision 7 matches code (handler placement, suppressed-on-cancel, context cases asserted by tests); pilot report updated; no skill change |
| scope control | yes | only coordinator, its new test, ADR 0016, pilot report, mutant patches and log changed |

### Checks run
- C1 `./gradlew :test --tests 'monada.neuron.aeon.BoundedParallelAeonCoordinatorAbnormalExitTest'` (cwd scratch worktree of base `b7f5d2d` with the head test file copied in) — FAIL as expected — `10 tests completed, 9 failed`; reproduces both cases on main for both paths.
- C2 same command on scratch worktree of head `a5c80cd` — PASS — 10/10 (part of C3, and rerun in C4 baseline runs).
- C3 `./gradlew test` (cwd scratch worktree of head, `../monada-resonance-store` resolving, adapter module included) — PASS — `BUILD SUCCESSFUL`; JUnit XML: core 1721, evaluation 292, adapter 80 tests, 0 failures, 0 errors.
- C4 reviewer mutants on head, each run with the AbnormalExit test class then reverted: Ma remove contextual `cancelAfterAbnormalExit` → 4 failed; Mb direct cancellation start `nextToReconcile + 1` → 4 failed (incl. stalled-input test); Mc `cancel(false)` → 1 failed — all killed.
- C5 reviewer probe `ReviewerProbeTest` (scratch only, not applied) on head, run 3 times — PASS — contextual real-pool interrupt interrupts both running wave tasks, caller gets `IllegalStateException` caused by `InterruptedException` with flag set; saturated `ThreadPoolExecutor(1,1,queue 1,AbortPolicy)` direct path → `RejectedExecutionException`, running task interrupted, queued task never processed.
- C6 same probe on base — FAIL as expected — `2 tests completed, 2 failed`.
- C7 `./gradlew formalPilotCheck` (cwd scratch worktree of head) — PASS — `BoundedParallelFailureSemanticsPilotTest` 5 tests, 0 failures.
- C8 `git apply --check` of each of the six `docs/experiments/formal-pilot-aeon-failure-semantics-v1/mutants/*.patch` on head — PASS — all apply. (Mutant kill counts in `logs/mutants-issue-208.txt` were not replayed.)

### Checks not run
- Replay of the six pilot mutants against formalPilotCheck — NOT RUN — time budget; the claim in `logs/mutants-issue-208.txt` is unverified by this review; affects no criterion (AC3 requires baseline formalPilotCheck pass, which ran).
- Consumer fixture tasks — NOT RUN — no published API, module, or format surface touched; affects none.

### Residual risks and limitations
- The request's IM1 quotes the issue body's `O(maxParallelism)`; the owner's later issue comment revises the direct path to `O(inputCount)` worst case. IM1 was judged against the revised expectation.
- IM2 is judged structurally; no benchmark was run.
- Ignored files (local config, build output) are absent from the scratch worktrees; eol/filter attributes could make bytes differ (none observed to matter).
- Cancellation is cooperative: a processor ignoring interruption may still run briefly after `coordinate` throws (accepted by ADR 0016 and the issue's non-goals).
- Retracted findings: 0.
- Review cost: about 7 minutes wall time (17:06:34 to 17:13:03 -06:00), 17 commands.
