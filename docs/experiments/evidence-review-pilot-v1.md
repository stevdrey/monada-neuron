# Evidence-Only Adversarial and Boundary Impact Review Pilot (`evidence-review-pilot/1`)

## Status

| Field | Value |
| :--- | :--- |
| Pilot version | `evidence-review-pilot/1` |
| Issue | [#203](https://github.com/stevdrey/monada-neuron/issues/203) (Agent Skills 4/5, Epic [#199](https://github.com/stevdrey/monada-neuron/issues/199)) |
| Practices piloted | P3 proof-graded findings, P4 boundary impact trace, P5 boundary red-flag screen (`docs/experiments/pstack-skill-audit-v1.md`) |
| Neuron revision | `main` at `634093fed222632a77847850867a596e0de5cc2c` plus this change |
| Run date | 2026-11-12 |
| Consumer | [#204](https://github.com/stevdrey/monada-neuron/issues/204) (evaluation and adoption decision) |

This pilot puts the practices that the #200 audit selected for #203 into the existing review guidance. It then runs that
guidance on three review samples with known ground truth. It adds no runtime code, CI job, dependency, or reviewer.

**No ADR.** None of the ADR triggers in `AGENTS.md` applies: the change governs how coding agents review, not a
cognitive boundary, ownership, format, or data layout. The audit leaves the adoption decision to #204.

## What changed

| Practice | Where | Change |
| :--- | :--- | :--- |
| P3 (IR1, IR5, IR6, IR7, BR1, BR5) | `monada-neuron-independent-verification`, new section "Proof-graded findings" | A five-rung evidence ladder fixes when a finding is `[demonstrated]` (R4 or R5). There is a rule for contract violations. Each finding body adds `Required by:`, `Rung:`, `Rerun:` and `Smallest regression check:`. Observations that no requirement demands go under `Optional improvements` and never block. A reproducer that does not fail again on the rerun keeps its finding as non-blocking (IR6 gates only the blocking tag, and IR1 keeps unproven findings). Only findings that were themselves wrong are retracted, and those are counted. Review cost is recorded. Fan-out and redesign proposals are excluded. |
| P4 (BR2, BR3, BR4, BR6) | Same skill, new section "Boundary impact trace" and result section `Boundary impact` | When a change crosses a boundary, each touched surface is confirmed or cleared with a rung. One safety fact is stated with its rung or marked unproven. The trace checks the surfaces a grep misses. Concurrent, SIMD and native paths get a fixed list of checks. Consumer tasks run for touched surfaces only. |
| P5 (AR4) | `docs/specs/pr-review-checklist.md`, "Cognitive Architecture" | Four boundary questions, each tied to an ADR or design principle: split ownership, information leakage, hand-synced list, importable internals. |
| Review template | `pr-review-checklist.md`, new "Evidence Standard"; "Merge Recommendation" | Defines a blocking defect, a hypothesis, and an optional improvement for human and agent reviews. Open blocking defects and unmet criteria rule out "Ready to merge" ("Ready after small fixes" when each has a small, named fix, "Not ready" otherwise), a `NOT VERIFIABLE` required criterion marks the review incomplete, and hypotheses and optional improvements never decide the recommendation. |
| Review shape | `monada-neuron-agent-task-workflow`, "Review Shape" | One paragraph that points to the above. |

**Unchanged, by design (audit P3 precedence).** The verification line, the three-tag finding head, the status rules,
`publication-gate.sh`, the implement-issue state machine and its stop conditions, and the single-reviewer loop all stay
as they were. The only new body lines and sections are Markdown for people, and the gate never reads them. The
canonical skills stay under `.agents/skills/`, with a byte-identical `.windsurf/skills/` mirror. The instructions are
harness-neutral ("a separate subagent"), so they work in Codex and in Claude Code alike.

### Confirmation requested by the audit (BR1)

The audit asked whether a recorded command that shows the violating text beside the clause it breaks is enough proof of
a contract violation, since that sits close to upstream's rung 2 ("pointed at a line"). The answer depends on what the
clause constrains, so the skill now splits the rule in two:

- For a **text or structure** clause (a dependency, a name, a mirrored file, a statement a document must not make), the
  violation *is* the text, and a recorded `grep`/`sed` over both files is R4. The seeded sample shows this: the Javadoc
  claims an authorization that ADR 0026 forbids.
- For a **behavior** clause (ordering, cancellation, a complexity bound), code shown beside the clause is only R2. R4
  needs a run. The historical sample shows this: the reviewer instrumented the cleanup scan and printed
  `abnormalExitScanned=6 maxParallelism=2` rather than arguing from the loop bounds.

## Method

- **Reviewer.** Each sample had one fresh `general-purpose` subagent in Claude Code (Opus 5.5) and no fan-out. It was
  told to follow the new skill and checklist from this branch's working tree. It got a verification request in the
  skill's input contract and was not told the expected outcome. The three ran at the same time on one machine and shared
  the Gradle daemon cache.
- **Skill revision.** The three reviews followed the working-tree skill as it stood before verification round 1 of this
  change. That revision differed from the committed text in three places: a reproducer that did not fail again was
  retracted rather than kept as non-blocking, the finding example used a `markdown` fence, and the contract rule said
  "only at R4". No sample retracted a finding or had a rerun that failed to repeat, so the results do not depend on
  these differences.
- **Environment.** Fedora Linux, JDK 27 through the Gradle toolchain. The `../monada-resonance-store` sibling resolved,
  so the adapter module was in every build. Snapshots were detached worktrees, and every reviewer left them unmodified.
- **Ground truth.** It was fixed before the runs:

| Sample | Snapshot | Issue | Ground truth |
| :--- | :--- | :--- | :--- |
| Historical defect | PR #209 commit `7919563` against `b7f5d2d` | #208 | The PR's own review of this commit raised two defects. **H1:** the direct-path abnormal-exit scan is `O(inputCount)`, not the Issue's `O(maxParallelism)`, and nothing records this. **H2:** ADR 0016 decision 7 claims more than the contextual path does: an executor rejection before reconciliation records no `AeonInputStarted`. Both were fixed in `a5c80cd`. |
| No-defect | PR #209 commit `a5c80cd` against `b7f5d2d` (tree identical to merged `634093f`) | #208 | No known defect. It merged after review. |
| Seeded | `samples/seeded-spend-preapproval.patch` on `634093f` | Synthetic S203-1 (`samples/seeded-issue-S203-1.md`) | **S1:** a plausible cost-threshold helper whose public Javadoc says a verdict "authorizes the spend" and lets Forge skip "its own authorization or quota check". This is the split-ownership violation of ADR 0026 that the audit names as P5's seeded case. The seed's own tests pass. |

The #208 requests listed AC1 to AC5 and IM1 to IM3, quoted from the Issue body. Both requests were identical except
for the snapshot.

## Results

The full result blocks are under `evidence-review-pilot-v1/results/`. Only local paths were redacted.

| Sample | Status | Blocking findings | Ground truth found | Other findings | Retracted | Wall time | Tokens | Commands |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Historical defect | `NEEDS CHANGES` | 1 (F1, R4, rerun in a fresh worktree) | H1 found. **H2 missed:** the contextual path was cleared and AC5 marked `SATISFIED`. | F2, non-blocking and valid: the request had left out the owner's later comment on #208 revising IM1 | 0 | 9.8 min | 154k | 32 |
| No-defect | `PASS` | 0 | Nothing to find, and no blocking false positive | 2 optional improvements, kept apart from findings | 0 | 7.9 min | 134k | 24 |
| Seeded | `NEEDS CHANGES` | 2 (F1 and F2, both R4 and rerun) | S1 found as F1 at R4. It cites ADR 0026 lines 33 and 37 to 39 and Forge Routing Contract v1 lines 61 and 215, with the smallest change (reword), not a redesign. | F2: a real gap that was **not seeded**. Mutants that pre-approve `NotMeasured`, or that ignore the dimension, pass the seed's tests. Checked against the seed's test file. | 0 | 7.1 min | 111k | 18 |

Wall time and tokens come from the harness for each subagent. Command counts are the harness's tool-call counts. The
reviewers' own `Review cost` lines report fewer commands, because they counted shell commands only.

**Against the audit's measurable outcomes.**

| Practice | Outcome required by the audit | Observed |
| :--- | :--- | :--- |
| P3 | A historical or seeded defect is reported blocking with a reproducer that fails before the fix and passes after it | Partly met. Historical F1 was reported blocking at R4 on `7919563`: its probe cancels input 0 after scanning 6 tasks. The fix in `a5c80cd` documents the `O(inputCount)` cost in ADR 0016 and adds `directPathCancelsTheStalledInputWhenLaterInputsCompletedFirst`, which the no-defect reviewer killed with a mutant. The scan probe was not rerun on `a5c80cd`, and it would still report 6, so "passes after the fix" is not shown for the historical sample. Seeded F1 and F2 each name a regression check (a `grep` that must come back empty, and two assertions), but neither was run against a fixed version. |
| P3 | A no-defect sample yields no blocking finding | Met (`PASS`, `blocking_open=0`). |
| P3 | `publication-gate-check.sh` and its `--mutants` mode still pass | Met: all checks passed, and 35 of 35 mutants were killed. `fingerprint-check.sh` also passes. |
| P3 | Retracted-finding counts and tool cost are recorded | Met: 0 retracted in every sample. Cost is in the table above. |
| P4 | Each touched surface is confirmed or cleared with a rung, and the safety fact reaches R4 or is unproven | Met in all three. The historical reviewer graded one surface R3 (the suppress branch it could not reach) instead of claiming R4. |
| P5 | A seeded violation is flagged with its ADR, and a no-defect sample yields no blocking flag | Met (seeded F1, and the no-defect `PASS`). |

## Limitations and observations for #204

- **One miss (H2).** The historical reviewer checked decision 7 against the code paths and found them consistent. It
  did not check each new universal statement in the ADR ("any abnormal contextual exit ...") against each exit case. A
  rejection during submission happens before `recordAeonInputStarted`, which contradicts that statement. The pilot did
  not tune the skill to this miss. One sample cannot show whether a new probe rule would be general or overfitted, so
  #204 should count H2 as a false negative of this guidance.
- **Request completeness is the orchestrator's job.** Historical F2 shows that the reviewer reads Issue comments, and
  that it reports a mismatch with the request as the input contract requires. The no-defect reviewer used the same
  comment to judge IM1. The cause was the orchestrator, which built IM1 from the Issue body alone.
- **Historical F1 cannot be replayed from the repository.** Its probe (`ReviewProbeAbnormalExitTest`) and the
  print-only `instrument.sh` counter lived only in the reviewer's scratch directory and were not kept. To rebuild them:
  `maxParallelism=2` and 6 inputs; the executor holds input 0 unrun and runs the other inputs inline; it interrupts the
  caller on submission 6; then count the tasks `cancelAfterAbnormalExit` scans. The committed result file is unchanged.
- **Sample size.** There were three samples and one run each. The reviewer is a single model, and run-to-run variance
  was not measured (`monada-neuron-independent-verification/validation.md`, round 3, shows that it exists).
- **Seeded sample.** The seed author wrote both the defect and the synthetic issue, so it tests whether the screen
  catches a planted ADR 0026 violation, not how often such violations occur. Its unplanned F2 counts as a real defect
  of the sample, not of Neuron.
- **Cost.** Each review cost 7 to 10 minutes and 111k to 154k tokens. That includes a full `./gradlew test`, plus
  `formalPilotCheck`, consumer tasks, mutants and probes where the reviewer chose them. These numbers do not compare
  against a review without P3 to P5. That comparison belongs to #204.

## Reproduce

```bash
# Seeded sample: apply on main in a scratch worktree, then review it with the verifier skill.
git worktree add --detach ../review-seeded 634093fed222632a77847850867a596e0de5cc2c
git -C ../review-seeded apply "$PWD/docs/experiments/evidence-review-pilot-v1/samples/seeded-spend-preapproval.patch"
# Historical samples: PR #209 commits.
git fetch origin refs/pull/209/head
git worktree add --detach ../review-historical-defect 79195633333e0a940e55d810a69dbf1979dd004c
git worktree add --detach ../review-historical-fixed a5c80cdbb8d236967ffbaa579f23aca55b432e18
```

Each review then runs a separate subagent on
`.agents/skills/monada-neuron-independent-verification/SKILL.md`, with the request fields shown in the header of each
result file (base, snapshot, criteria from the Issue). Gate self-tests:

```bash
bash .agents/skills/monada-neuron-independent-verification/fingerprint-check.sh
bash .agents/skills/monada-neuron-implement-issue/publication-gate-check.sh
bash .agents/skills/monada-neuron-implement-issue/publication-gate-check.sh --mutants
diff -r .agents/skills .windsurf/skills
```
