## Independent verification result

<!-- verification v1 status=PASS base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=79195633333e0a940e55d810a69dbf1979dd004c tree=f42c8183f9ac61897ad5edcb231a227907a79d34 identical=yes criteria=8 satisfied=8 blocking_open=0 -->
**Status:** PASS
**Issue:** #208 (parent #199)
**Snapshot:** base=b7f5d2d045bc6acb4dec277245cfb8903039f8c0 head=79195633333e0a940e55d810a69dbf1979dd004c tree=f42c8183f9ac61897ad5edcb231a227907a79d34 (identical at start and end: yes)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | Tests reproduce each case on main first, with interruptible and rejecting executors | SATISFIED | C3: the snapshot's `BoundedParallelAeonCoordinatorAbnormalExitTest` run against base code fails 7 of 8 tests (direct and contextual, interrupt and rejection); the 8th (`inputFailureStillRethrowsTheSameInstanceAndCancelsSiblings`) is a decision-5 regression guard and passes on base as expected. |
| AC2 | After abnormal exit every submitted task is done or cancelled; caller interrupt status preserved | SATISFIED | `BoundedParallelAeonCoordinator.java:255-306` (direct try/catch), `:381-412` (contextual per-wave try/catch), `:874-893` (`cancelAfterAbnormalExit`, `cancelTasks`); C1; C4 reviewer probes (stalled-first input + rejection, stalled-first input + interrupt, contextual running-task interrupt) all pass; C5 mutations Mu1/Mu2/Mu3 each killed by the author's tests. |
| AC3 | Input-failure semantics and oracle equivalence unchanged | SATISFIED | C1 (`./gradlew test`: root 1719, evaluation 292, adapter 80 tests, 0 failures); C2 (`formalPilotCheck`: all 5 properties HOLD over 98 workloads / 17500 schedules; negative controls HOLD); in-loop `cancelTasks` before `rethrowDeterministicFailure` kept at `:282`; mutant patches all apply to the snapshot (C6). |
| AC4 | Contextual path inspected, and fixed or documented | SATISFIED | Fixed at `:381-412`; three contextual tests (interrupt, rejection, reconciliation failure); context-prefix contract in ADR 0016 decision 7, second paragraph; C5 Mu1 killed by 3 contextual tests. |
| AC5 | ADR 0016 (or follow-up) states the abnormal-exit contract | SATISFIED | `docs/adr/0016-bounded-parallel-aeon-input-coordination.md` decision 7 and new Consequences bullet. |
| IM1 (implied) | Cancellation over the existing `tasks` window, O(maxParallelism) per abnormal exit (revised by owner comment on #208 to O(inputCount) worst case for direct path, O(maxParallelism) contextual); no new collections on normal path | SATISFIED | Direct cleanup scans `[nextToReconcile, first null)` of the existing `tasks` array, contextual scans one wave; no new collection added (diff). See F1 for the criteria/ADR wording mismatch. |
| IM2 (implied) | No measurable cost on normal path | SATISFIED | By inspection: the normal path gains only a `try` region (no allocation, no extra calls); `cancelTasks` loop change runs only on cancellation paths. No benchmark run (see limitations). |
| IM3 (implied) | Contracts and Store ownership unchanged; decisions 3/5/6 unchanged; no forcible stop; pilot not a CI gate | SATISFIED | Diff touches only the coordinator's private methods/`coordinate` bodies, one new test class, ADR 0016, and pilot docs/mutant patches; `cancel(true)` is cooperative; `build.gradle.kts` unchanged (`formalPilotCheck` still excluded from `test`). |

### Findings
- **F1 [non-blocking] [demonstrated] [open]** `docs/adr/0016-bounded-parallel-aeon-input-coordination.md` decision 7 — the request's IM1 quotes the original `O(maxParallelism)` bound, but the owner's later comment on #208 revises it to `O(inputCount)` worst case for the direct path and says the revision and a `directPathCancelsTheStalledInputWhenLaterInputsCompletedFirst` regression test are recorded. In this snapshot the ADR only says "a scan of the unreconciled window (direct)" without stating the linear worst case, and no stalled-first-input test exists. Behavior is correct: reviewer probe `probeStalledFirstInputThenRejection` (inputs=5, maxParallelism=2, input 0 stalled, 1 and 2 inline, 3 rejected) shows task 0 and 3 cancelled and the scan covering 4 tasks.
  Reproduce: in a scratch worktree of the snapshot, add the probe from C4 and run `./gradlew :test --tests 'monada.neuron.aeon.ReviewerProbeAbnormalExitTest'` → `tests="11" failures="0"`; `grep -n "stalled\|inputCount" docs/adr/0016-*.md` → no statement of the linear bound.
- **F2 [non-blocking] [demonstrated] [open]** `src/test/java/monada/neuron/aeon/BoundedParallelAeonCoordinatorAbnormalExitTest.java` — no contextual test checks that an already *running* wave task is interrupted (`cancel(true)`); the mutation `cancel(true)` → `cancel(false)` survives all contextual tests and is killed only by the direct-path running-task test, because both paths share `cancelTasks`. A reviewer probe (`probeContextualRunningTaskInterrupted`) passes on the snapshot and kills that mutation.
  Reproduce: C5 Mu2 → only `directPathInterruptsARunningCooperativeTaskWhenTheCallerIsInterrupted` and the reviewer's contextual probe fail.

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | rejection of first window vs refill; window full (2 of 3); 2-input running case; empty input list path unchanged (returns before try) |
| duplicates/ordering | yes | stalled-first input with later inputs completing first (probes P1, P2); reconciliation order unchanged (pilot C2) |
| failure paths / partial failure | yes | interrupt while waiting, executor rejection (initial and refill), reconciliation failure (closed context), input failure + abnormal handler, secondary failure while cancelling (by inspection: attached as suppressed, `secondary != exit` guard) |
| text/encoding/locale | n/a | no text parsing or formatting in the change |
| determinism / floating point | yes | recording executors make schedules deterministic; pilot exhaustive exploration HOLDS; no numeric change |
| concurrency/cancellation/shared state | yes | real-thread interrupt of running task on direct (author) and contextual (reviewer probe) paths; interrupt-status preservation asserted; mutations Mu1-Mu3 |
| compatibility (API, JPMS, persisted formats) | yes | only private methods changed; no public signature, export, or format change |
| resource behavior | yes | no new collections or allocations on normal path; cleanup bounded by created tasks (F1) |
| documentation/ADR/skill mirror | yes | ADR 0016 decision 7 + consequence; pilot report "Resolved by #208"; no skill files changed so no mirror check needed |
| scope control / boundaries | yes | diff limited to coordinator, one test class, ADR 0016, pilot docs and mutant patches |

### Checks run
- `./gradlew test formalPilotCheck --console=plain` (cwd scratch worktree of head `7919563`, sibling `../monada-resonance-store` present so adapter module included) — PASS — exit 0, `BUILD SUCCESSFUL in 4m 50s`; root 1719 tests, evaluation 292, resonance-adapter 80, 0 failures/errors. (C1)
- `./gradlew formalPilotCheck` (same invocation as C1) — PASS — exhaustive report `workloads=98 schedules=17500`, all five properties `HOLDS within bounds (violating schedules: 0)`; negative controls HOLD. (C2)
- `./gradlew :test --tests 'monada.neuron.aeon.BoundedParallelAeonCoordinatorAbnormalExitTest'` (cwd scratch worktree of base `b7f5d2d` with the snapshot's new test file copied in) — FAIL as expected — `8 tests completed, 7 failed`; all four direct and three contextual abnormal-exit tests fail on base. (C3, AC1 evidence)
- `./gradlew :test --tests 'monada.neuron.aeon.ReviewerProbeAbnormalExitTest'` (cwd scratch worktree of snapshot + reviewer probe class) — PASS — `tests="11" failures="0" errors="0"` (8 copied author tests + 3 probes). (C4)
- Mutations on snapshot, running author tests + probes (C5) — PASS (all killed): Mu1 remove contextual `cancelAfterAbnormalExit` → 3 author contextual tests fail; Mu2 `cancel(true)`→`cancel(false)` → author direct running-task test fails (contextual only by reviewer probe, F2); Mu3 direct cleanup start `nextToReconcile + 1` → 3 author direct tests fail.
- `git apply --check` of the six mutant patches under `docs/experiments/formal-pilot-aeon-failure-semantics-v1/mutants/` against the snapshot — PASS — all apply. (C6)

### Checks not run
- JMH or other normal-path benchmark — NOT RUN — no harness requested and change adds no normal-path work; affects IM2 (satisfied by inspection only).
- Re-running the six mutants through `formalPilotCheck` — NOT RUN — time budget; the committed log `logs/mutants-issue-208.txt` was read but not reproduced; affects nothing required.

### Residual risks and limitations
- Ignored files (local config, build output) are absent from the scratch worktrees; no eol/filter attributes were observed to matter.
- A concurrent run on base (C3 invocation without `:` prefix) showed `:monada-neuron-resonance-adapter:test FAILED` on the base worktree; it was likely caused by two worktrees building the shared included build `../monada-resonance-store` at the same time, was not investigated, and does not concern the snapshot (C1 adapter tests 80/80 pass).
- Cancellation is cooperative: a running processor that ignores interruption can still run after `coordinate` throws (accepted by ADR 0016).
- The formal pilot still excludes interrupt and rejection; those cases rest on unit tests and reviewer probes only.
- Scratch worktrees `eval204-scratch-bconcd-1` and `-2` were removed.
