## Independent verification result

<!-- verification v1 status=NEEDS_CHANGES base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=79195633333e0a940e55d810a69dbf1979dd004c tree=f42c8183f9ac61897ad5edcb231a227907a79d34 identical=yes criteria=8 satisfied=7 blocking_open=1 -->
**Status:** NEEDS CHANGES
**Issue:** #208 (parent #199; related #202)
**Snapshot:** base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=79195633333e0a940e55d810a69dbf1979dd004c tree=f42c8183f9ac61897ad5edcb231a227907a79d34 (identical at start and end: yes; tree equals `HEAD^{tree}`, clean committed snapshot)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | Test reproduces each case on main first, interruptible and rejecting executor | SATISFIED | C1: the snapshot's `BoundedParallelAeonCoordinatorAbnormalExitTest` run against base code: 7 of 8 fail (interrupt direct/contextual, real-pool interrupt, rejection initial/refill, contextual rejection, contextual reconcile failure); only the decision-5 regression test passes, as expected. |
| AC2 | After abnormal exit every submitted task done or cancelled; interrupt status preserved | SATISFIED | C2 (8 new tests pass on head), C4 probes P1–P4 (stalled first input + interrupt, contextual rejection in wave 2, real saturated `ThreadPoolExecutor` rejection, contextual real-pool interrupt): all tasks done/cancelled, interrupt flag set after ISE with `InterruptedException` cause. `BoundedParallelAeonCoordinator.java:303-306,409-412,874-893`. |
| AC3 | Input-failure semantics and oracle equivalence unchanged | SATISFIED | C2 `./gradlew test` (1719 core + 732 evaluation + 80 adapter, 0 failures), C3 `./gradlew formalPilotCheck` (5 tests, 0 failures), C5 mutant script: baseline PASS, all six mutants KILLED with counts identical to the committed `mutants-issue-208.txt` and `mutants.txt`. Decision-5 cancel-before-suppress order kept at `:279-283`. |
| AC4 | Contextual path inspected, fixed or documented | SATISFIED | Handler per wave `:384-412`; ADR 0016 decision 7 second paragraph documents context prefix; tests `contextualPath*` (3) plus probes P2, P4. |
| AC5 | ADR 0016 states the abnormal-exit contract | SATISFIED | `docs/adr/0016-bounded-parallel-aeon-input-coordination.md:53-72,104-107` (decision 7 and consequence). |
| IM1 | Cancellation over existing window in O(maxParallelism) per abnormal exit; no new collections on normal path | PARTIAL | No new collections on the normal path (diff). The O(maxParallelism) bound does not hold on the direct path (F1, demonstrated); the owner's later revision on the issue accepts O(inputCount) but conditions it on recording in ADR 0016 decision 7 and a stalled-first-input regression test, neither present in this snapshot. |
| IM2 | No measurable cost on the normal path | SATISFIED | Structural: the normal path gains only `try` regions (exception tables, no executed instructions) and no allocation; the issue itself prescribes this shape. No benchmark run (see limitations). |
| IM3 | Contracts, Store ownership, ADR decisions 3/5/6 unchanged; no forced stop; pilot not a CI gate | SATISFIED | `git diff --stat` touches only the coordinator (private methods), one test, ADR 0016 (additions only, decisions 1-6 text unchanged), pilot doc/logs/mutant patches; no `build.gradle.kts`, CI, skill, or public-signature change; cancellation stays `cancel(true)`. |

### Findings
- **F1 [blocking] [demonstrated] [open]** `src/main/java/monada/neuron/aeon/BoundedParallelAeonCoordinator.java:304` with `docs/adr/0016-bounded-parallel-aeon-input-coordination.md:63-65` — the direct-path abnormal-exit cleanup scans the whole unreconciled window, which grows to `inputCount`, not `maxParallelism`; the ADR's cost sentence ("a scan of the unreconciled window (direct)"), read next to line 28 ("submits at most `maxParallelism` propagation tasks at once"), does not state this.
  Required by: IM1, issue #208 "Algorithm and Data-Structure Expectations": "Cancellation over the existing `tasks` window, in `O(maxParallelism)` per abnormal exit." The owner's revision comment on #208 (2026-11-12T22:32Z) accepts `O(inputCount)` for the direct path but states it is recorded in "ADR 0016 decision 7 (*Cost*)" and covered by the regression test `directPathCancelsTheStalledInputWhenLaterInputsCompletedFirst`; neither exists in this snapshot (`grep -n "inputCount\|O(" docs/adr/0016-...md` → no match in decision 7; the test name is absent from `src/test`).
  Rung: R4 — probe P1 in a scratch worktree, with a print-only counter added to `cancelAfterAbnormalExit` (`<review-dir>/instrument.sh`).
  Reproduce: maxParallelism=2, 6 inputs; executor records submission 1 (input 0) without running it, runs the rest inline, interrupts the caller on submission 6; `./gradlew :test --tests 'monada.neuron.aeon.ReviewProbeAbnormalExitTest.p1StalledFirstInputDirectInterrupt'` → `REVIEW-SCAN abnormalExitScanned=6 maxParallelism=2` and `P1 submitted=6 ... stalledCancelled=true`; required: scan ≤ 2 (issue body) or, under the owner's revision, the linear bound stated in ADR 0016 decision 7 plus a stalled-first-input test.
  Rerun: fresh scratch worktree of head 7919563, same patch and probe, same output (`abnormalExitScanned=6 maxParallelism=2`).
  Smallest regression check: a test with the P1 schedule (input 0 stalled, later inputs completed, then caller interrupt) asserting input 0 is cancelled, plus the ADR 0016 decision 7 cost sentence naming the `O(inputCount)` worst case with at most `maxParallelism` tasks in flight. Correctness itself holds (input 0 is cancelled); only the bound and its record are missing.
- **F2 [non-blocking] [demonstrated] [open]** verification request, IM1 — the request quotes IM1 from the issue body only and omits the owner's revision comment on #208 that replaces the `O(maxParallelism)` bound for the direct path.
  Required by: independent-verification skill, input contract: "a mismatch (dropped, reworded, or added criteria) is a finding".
  Rung: R4 — `gh api repos/stevdrey/monada-neuron/issues/208/comments --jq '.[0].body'` → "## Revised complexity expectation ... The direct path's abnormal-exit cleanup is `O(inputCount)` in the worst case, and that is accepted."
  Smallest regression check: pass the revision as a clarification of IM1 (with its source) in the next request.

### Optional improvements
- `cancelTasks` now stops at the first `null` slot instead of skipping nulls; correct because tasks are created contiguously (documented in its Javadoc), but a future non-contiguous creation order would silently stop cancelling early.

### Boundary impact
| Surface | Change to it | Status | Rung | Evidence |
| --- | --- | --- | --- | --- |
| Direct concurrent path (`coordinate` without context) | `try/catch Throwable` around submit/await/reconcile loop cancels `[nextToReconcile, first null)` and rethrows | cleared for correctness; confirmed (F1) for cost bound | R4 | C2, C4 P1, P3 |
| Contextual concurrent path (`EXPERIMENTAL_PARALLEL`) | per-wave `try/catch` cancels the whole wave and rethrows; context left with prefix | cleared | R4 | C2, C4 P2, P4 |
| `cancelTasks` / new `cancelAfterAbnormalExit` (private) | stop at first null; secondary failure suppressed onto original | cleared | R3 | contiguous creation in `submitDirectTasks` `:823-845` and wave loop `:388-401`; no `done()` hook or `cancel` path that throws was reachable to exercise the suppress branch |
| Formal pilot harness and mutants | patches regenerated, harness unchanged | cleared | R4 | C3, C5 |
| Public API / JPMS / published artifact / consumer fixtures | no signature change (private methods only) | cleared | R4 | `git diff b7f5d2d HEAD -- src/main` shows only private members and method bodies |

Safety fact: every task created by `coordinate` lies in a contiguous prefix of `tasks`/`waveTasks`, everything before `nextToReconcile` (or before the current wave) is already done, and the single catch handler cancels the rest before any exception leaves the call — R4 (C1 base failures vs C2/C4 head passes).
Concurrent-path checks: determinism/iteration order unchanged (C3, C5); cancellation and interrupt on every exit exercised for interrupt, rejection (recording and real `ThreadPoolExecutor`), reconcile failure, input failure; no allocation added to the hot path; no `Arena`/`MemorySegment`; oracle equivalence C3/C5; no capability fallback involved.

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | initial-window rejection (submission 2 of 2), refill rejection, rejection in second contextual wave, stalled first input with N=6 > maxParallelism=2 |
| failure paths / partial failure | yes | interrupt while waiting (pre-set and mid-wait), rejection, contextual reconcile failure (context closed), input failure (same instance, no suppressed) |
| text/encoding/locale | n/a | no text input in the change |
| determinism / floating point | yes | formal pilot exhaustive check and mutant counts identical to committed logs (C3, C5) |
| concurrency/cancellation/shared state | yes | real pools: cooperative task interrupted after caller interrupt (direct test, P4) and after rejection (P3); caller interrupt flag preserved (tests, P1, P4) |
| compatibility | yes | no public/JPMS change; adapter module in the build (80 tests) |
| resource behavior | yes | cleanup scan size measured (F1); no new normal-path allocation |
| numerical claims | n/a | no measured gain claimed |
| docs/ADR/skill mirror | yes | ADR 0016 decision 7, pilot report "Resolved by #208" counts verified (C5); no skill files changed |
| scope control | yes | diff limited to coordinator, one test, ADR 0016, pilot docs/patches/log |

### Checks run
- C1 `./gradlew test --tests 'monada.neuron.aeon.BoundedParallelAeonCoordinatorAbnormalExitTest'` (cwd scratch worktree of base b7f5d2d with the snapshot's test file copied in) — FAIL (expected) — 8 tests, 7 failed, `inputFailureStillRethrowsTheSameInstanceAndCancelsSiblings` passed; e.g. "task 0 must be cancelled", "task 1 must be done or cancelled", "running task was interrupted".
- C2 `./gradlew test` (cwd scratch worktree of head 7919563, `../monada-resonance-store` resolved) — PASS — BUILD SUCCESSFUL; 1719 core, 732 evaluation (unit + integration), 80 resonance-adapter tests, 0 failures.
- C3 `./gradlew formalPilotCheck` (same cwd) — PASS — task executed, 5 tests, 0 failures.
- C4 `./gradlew :test --tests 'monada.neuron.aeon.ReviewProbeAbnormalExitTest'` (cwd scratch worktree of head + reviewer probe test) — PASS — P1–P4 pass; P1 with instrumentation prints `REVIEW-SCAN abnormalExitScanned=6 maxParallelism=2` (rerun in a fresh worktree: same).
- C5 `TMPDIR=<scratch> scripts/formal-pilot-mutants.sh` (cwd scratch worktree of head) — PASS — exit 0; baseline PASS, m1–m6 KILLED; kill lines identical to `docs/experiments/formal-pilot-aeon-failure-semantics-v1/logs/mutants-issue-208.txt`, which match `mutants.txt`.

### Checks not run
- `consumerSmokeTest`, `jpmsConsumerTest`, `forgeHostConsumerTest`, `embeddedHostConsumerTest` — NOT RUN — no published surface, module, or public signature touched; affects none.
- Normal-path benchmark (JMH) — NOT RUN — no harness for this path requested; IM2 rests on structural evidence.

### Residual risks and limitations
- The secondary-failure branch of `cancelAfterAbnormalExit` (failure raised while cancelling) was not exercised; no reachable throwing `cancel` path was found.
- Executors that throw `RejectedExecutionException` because the caller was interrupted inside `execute` (blocking-submit executors) propagate that exception unchanged rather than the documented `IllegalStateException`; not probed, executor-specific.
- Ignored files (local configuration, build output) are absent from scratch worktrees; the mutant script copies to a temporary dir without the sibling store (core-only, as designed).
- Retracted findings: 0.
- Review cost: about 9 minutes wall time (17:06–17:15 local), 24 commands; tokens not shown by the harness.
