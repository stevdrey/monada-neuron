## Independent verification result

<!-- verification v1 status=PASS base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=a5c80cdbb8d236967ffbaa579f23aca55b432e18 tree=38adc75303a00261a5e6452b27f6b27fe25bcdba identical=yes criteria=8 satisfied=8 blocking_open=0 -->
**Status:** PASS
**Issue:** #208 (parent #199)
**Snapshot:** base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=a5c80cdbb8d236967ffbaa579f23aca55b432e18 tree=38adc75303a00261a5e6452b27f6b27fe25bcdba (identical at start and end: yes)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | Each case reproduced on main first, with interruptible and rejecting executor | SATISFIED | C2: the new `BoundedParallelAeonCoordinatorAbnormalExitTest` copied onto base `b7f5d2d` fails 9 of 10 tests (interrupt and rejection, direct and contextual paths); the 10th (`inputFailureStillRethrowsTheSameInstanceAndCancelsSiblings`) is a regression guard and passes on base, as expected. Same 10 pass on the snapshot (C1). |
| AC2 | After abnormal exit every submitted task is done/cancelled; caller interrupt preserved | SATISFIED | `BoundedParallelAeonCoordinator.java:260-306` (direct `try`/`catch (Throwable)` -> `cancelAfterAbnormalExit`), `:384-412` (contextual per-wave handler), `:874-893` (`cancelTasks` includes the rejected, created task). Interrupt flag restored in `awaitCompletedTaskIndex`/`awaitTask` and not cleared by the handler. Tests assert `isCancelled`/`isDone` on every recorded task, `Thread.currentThread().isInterrupted()`, `InterruptedException` cause, and `processedInputs == 0` after draining the recording executor; all fail on base (C2), pass on snapshot (C1). |
| AC3 | Input-failure semantics and oracle equivalence unchanged | SATISFIED | C1: `./gradlew test` 1721 core / 0 failed, evaluation 0 failed, adapter 80 / 0 failed; `formalPilotCheck` all 5 properties HOLD and both refutation controls REFUTED as expected (workloads=98 schedules=17500). Decision-5 order kept: `cancelTasks` still runs before `rethrowDeterministicFailure` (`:281-283`, `:467-469`). |
| AC4 | Contextual path inspected, fixed or documented | SATISFIED | Fixed at `:384-412`; ADR 0016 decision 7 documents context state per exit point; 4 contextual tests fail on base (C2), pass on snapshot (C1). |
| AC5 | ADR 0016 or follow-up states abnormal-exit contract | SATISFIED | `docs/adr/0016-bounded-parallel-aeon-input-coordination.md` decision 7 and Consequences bullet. |
| IM1 (implied) | Cancellation over existing `tasks` window; no new normal-path collections | SATISFIED (against the owner's revised expectation, see F1) | No new collection allocated (diff of `coordinate`); direct cleanup is `O(inputCount)` worst case, contextual `O(maxParallelism)`, as stated in ADR 0016 decision 7 *Cost* and in the owner's issue comment "Revised complexity expectation". Under the original issue-body wording (`O(maxParallelism)`) the direct path would not meet it. |
| IM2 (implied) | No measurable cost on the normal path | SATISFIED | Diff inspection: the normal path only gains `try` regions (exception-table entries, no executed instructions) and one extra `tasks[i] != null` loop condition in `cancelTasks`, which runs only on failure/budget-exhaustion paths. No benchmark run (see limitations). |
| IM3 (implied) | Contracts, Store ownership, decisions 3/5/6, no forcible stop, pilot not a CI gate | SATISFIED | `git diff --stat b7f5d2d HEAD`: only `BoundedParallelAeonCoordinator.java` (private internals), one new test, ADR 0016, and the pilot report/mutant patches under `docs/experiments/` changed; no public API, `build.gradle.kts`, CI, `.agents`/`.windsurf` change; cancellation stays `cancel(true)` (cooperative). |

### Findings
- **F1 [non-blocking] [demonstrated] [open]** verification request `criteria` IM1 vs issue #208 — the request quotes the issue-body expectation "`O(maxParallelism)` per abnormal exit" but omits the owner's later issue comment "Revised complexity expectation", which accepts `O(inputCount)` worst case for the direct path. The snapshot implements and documents the revised bound (ADR 0016 decision 7 *Cost*). This review evaluated IM1 against the revised text. The comment carries a Claude Code signature, so whether a human made the revision cannot be confirmed from the issue alone; the orchestrator should confirm it with the owner if that matters.
  Reproduce: `gh issue view 208 --comments` -> first comment "## Revised complexity expectation ... The direct path's abnormal-exit cleanup is `O(inputCount)` in the worst case, and that is accepted."

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | rejection of the initial window (first submission) vs a refill; interrupt set before the first wait; stalled input 0 with later inputs complete (`directPathCancelsTheStalledInputWhenLaterInputsCompletedFirst`); later-wave rejection with earlier waves reconciled. Empty input list returns before the handler (unchanged). |
| failure paths / partial failure | yes | interrupt, `RejectedExecutionException`, reconciliation failure (closed context), input failure; secondary failure while cancelling is attached as suppressed (`cancelAfterAbnormalExit`, reviewed by reading; no test injects a throwing `cancel`). |
| text/encoding/locale | n/a | no text parsing or formatting changed |
| determinism and ordering | yes | decision-5 ordering (cancel before suppressed attach) checked in code and by formal pilot properties; recording executors make new tests deterministic. |
| concurrency/cancellation/shared state | yes | real-thread test `directPathInterruptsARunningCooperativeTaskWhenTheCallerIsInterrupted`; 17500 explored schedules in `formalPilotCheck` (pilot still excludes interrupt/rejection, documented). |
| compatibility (API, JPMS, persisted formats) | yes | only private methods changed; exception types unchanged (`IllegalStateException` with `InterruptedException` cause, `RejectedExecutionException` rethrown as is). |
| resource behavior | yes | no new allocation on normal path; cleanup bound per F1/IM1; rejected task cancelled so it cannot run later. |
| documentation/ADR/skill-mirror | yes | ADR 0016 decision 7 matches code; snapshot pilot report updated; no skill changes, so no mirror check needed. |
| scope / boundaries | yes | see IM3. |

### Checks run
- `cd <scratch worktree at a5c80cd> && ./gradlew test formalPilotCheck --no-daemon` (cwd `<workspace>/eval204-scratch-bconcf-1`, sibling `../monada-resonance-store` resolved, adapter module included) — PASS — exit 0, `BUILD SUCCESSFUL in 5m 8s`; JUnit XML: core `:test` 1721 tests 0 failures 0 errors (10 in `BoundedParallelAeonCoordinatorAbnormalExitTest`), evaluation 0 failures, adapter 80 tests 0 failures; formalPilotCheck 5 tests 0 failures, `NO_RUNNABLE_TASK_AFTER_END: HOLDS`, `MATCHES_SEQUENTIAL_ORACLE: HOLDS`, both refutation controls `REFUTED as expected`.
- `./gradlew :test --tests 'monada.neuron.aeon.BoundedParallelAeonCoordinatorAbnormalExitTest' --no-daemon` on base `b7f5d2d` with the snapshot's new test file copied in (cwd `<workspace>/eval204-scratch-bconcf-2`) — FAIL as expected — `10 tests completed, 9 failed`; this is the reproduction on main for AC1 and the mutation evidence for AC2/AC4.

### Checks not run
- Regeneration of the six formal-pilot mutants (`docs/experiments/.../logs/mutants-issue-208.txt`) — NOT RUN — time budget; not required by any criterion (AC3 requires `formalPilotCheck` to pass, which it did). The author's mutant log is unverified.
- Normal-path benchmark — NOT RUN — no harness requested; IM2 judged by diff inspection.

### Residual risks and limitations
- Cancellation remains cooperative: a processor already running that ignores interruption can outlive `coordinate` (accepted by ADR 0016 and the issue's non-goals).
- The formal pilot still excludes caller interrupt and executor rejection; those are covered only by the 10 unit tests.
- No test injects a failure from `FutureTask.cancel`/`done()` during cleanup, so the suppressed-secondary branch of `cancelAfterAbnormalExit` is checked by reading only.
- Scratch worktrees exclude ignored local files; the evaluation-module count came from a recursive glob of its `build/test-results`.
- `./gradlew test` in the base worktree with a test filter failed in `:monada-neuron-resonance-adapter:test` because the filter matched no tests there; the root-only `:test` run was used instead (not a snapshot defect).
