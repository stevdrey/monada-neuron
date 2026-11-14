# pstack-Inspired Practices: Evaluation and Adoption Decision (`pstack-adoption-eval/1`)

## Status

| Field | Value |
| :--- | :--- |
| Evaluation version | `pstack-adoption-eval/1` |
| Issue | [#204](https://github.com/stevdrey/monada-neuron/issues/204) (Agent Skills 5/5, Epic [#199](https://github.com/stevdrey/monada-neuron/issues/199)) |
| Practices evaluated | P1 to P5 of [`pstack-skill-audit/1`](pstack-skill-audit-v1.md) |
| Inputs | [#201 claim evidence report](../benchmarks/claim-evidence-simd-resonance.md), [#202 formal pilot](formal-pilot-aeon-failure-semantics-v1.md), [#203 review pilot](evidence-review-pilot-v1.md), and the controlled comparison below |
| Baseline guidance | `main` at `923c9e510b7893bdc85ecb64411e1a4d47c9dd10` (after the #200 audit, before #201 to #203) |
| Pilot guidance | `main` at `ed0f2e4` (after #203) |
| Run date | 2026-11-13 |

This document compares the review guidance before and after the pilots on the same candidate changes, and records
an adopt, retain-on-probation, or reject decision for each practice, with the condition that would roll it back.

**What this evaluation does not claim.** Four samples with one run per arm cannot establish a detection rate, a
false-positive rate, or a cost difference. Where the arms differ, the table says what differed. It does not attribute
the difference to the guidance when run-to-run variance could explain it, because variance was not measured. No gain
below is stated as a general fact about the practices.

**No ADR.** The decisions govern how coding agents review and report, not a cognitive boundary, ownership, format,
concurrency model, or data layout. None of the ADR triggers in `AGENTS.md` applies, as the audit anticipated.

## Method

### Arms

- **Baseline (B):** `AGENTS.md`, `.agents/skills/**` and `docs/specs/**` at `923c9e5`. It has independent
  verification with demonstrated and suspected findings, the implement-issue loop, the comparative experiment report,
  and the review checklist, but no claim evidence report (P1), no proof-graded findings (P3), no boundary impact trace
  (P4), and no red-flag screen (P5).
- **Pilot (P):** the same files at `ed0f2e4`, which add P1, P3, P4 and P5. P2 is a harness, not review guidance; it is
  evaluated separately below.

Each reviewer was one fresh `general-purpose` subagent in Claude Code (Opus 5.5), with no fan-out. It was told to
follow `monada-neuron-independent-verification` and the files it points to from its arm's guidance checkout, in place
of the snapshot's own copy, and it received a verification request in that skill's input contract. It was not told the
expected outcome. It was not blind to the setup: the prompt called its reading restrictions "part of the experiment
design", and the guidance checkout's name (`eval204-guidance-baseline` or `eval204-guidance-pilot`) revealed its arm.

### Samples and ground truth

Ground truth was fixed before the first run. Three samples are the #203 samples, so their pilot-arm results are the
#203 runs; only their baseline arm ran here. The fourth is new, and both arms ran on it.

| Sample | Class | Snapshot | Ground truth |
| :--- | :--- | :--- | :--- |
| CONC-D | State and concurrency, historical defect | PR #209 at `7919563` on `b7f5d2d` (Issue #208) | **H1:** the direct-path abnormal-exit scan is `O(inputCount)`, and neither ADR 0016 nor a test records it, as the owner's revision on #208 requires. **H2:** ADR 0016 decision 7 claims more than the contextual path does (a rejection before `recordAeonInputStarted`). |
| CONC-F | State and concurrency, no-defect control | PR #209 at `a5c80cd` on `b7f5d2d` | No known defect. Any blocking finding is a false positive unless shown real. |
| ARCH-S | Architecture boundary, seeded | `samples/seeded-spend-preapproval.patch` of #203 on `634093f` (tree `87f73da`, identical to #203's) | **S1:** public Javadoc says a verdict "authorizes the spend" (ADR 0026). The #203 pilot also found an unplanted test gap. |
| PERF-S | Performance claim, seeded | [`samples/seeded-vector-million.patch`](pstack-adoption-evaluation-v1/samples/seeded-vector-million.patch) on `923c9e5` (tree `4799793`), Issue [S204-1](pstack-adoption-evaluation-v1/samples/seeded-issue-S204-1.md) | A documentation change that records a 1,000,000-pair Vector result. **G1:** one fork, no warm-up, one iteration, no variability (IC5). **G2:** 8.9x attributed to four lanes, above the 4x lane bound, no limiter (IC6). **G3:** the micro result stated as a cognitive-cycle result (IC7). **G4:** no semantic check against the scalar reference at the measured size (IC3). |

**G4 is disputed after the runs, by the orchestrator.** The seeded change measures evaluators it does not modify,
and IC3 asks for a semantic check of the *variant*, so it may not apply to a measurement of unchanged code. The
reviewers did not settle this. The baseline reviewer noted only that the "equivalence claim at line 722 is
pre-existing" (`results/baseline-perf-seeded.md`, Probes). The pilot reviewer marked its tolerance probe n/a because
"the doc states no new tolerance", yet listed a "semantic check on the 1,000,000-pair workload" among the fields a
claim evidence report would make explicit (`results/pilot-perf-seeded.md`, Optional improvements). The table keeps G4
as fixed before the runs; the counts below report it separately and do not score it as a miss.

PERF-S sits on `923c9e5` so that its snapshot does not contain the P1 standard or its worked example. Its numbers are
invented for the sample and appear only in the patch.

### Cost and risk limits

Fixed before the runs: at most five new reviewer runs, one reviewer per run, no fan-out, a stated budget of about 15
minutes per review, and any single JMH run under 5 minutes. Reviewers were asked not to open `docs/experiments/` or
`docs/benchmarks/` in the guidance checkout, which hold the pilots' ground truth and worked examples, nor any other
checkout. All limits held: five runs, and the longest took 11 minutes by the harness clock.

## Results

Result blocks are under [`pstack-adoption-evaluation-v1/results/`](pstack-adoption-evaluation-v1/results/), with local
paths redacted. Pilot-arm results for CONC-D, CONC-F and ARCH-S are the #203 files under
[`evidence-review-pilot-v1/results/`](evidence-review-pilot-v1/results/).

| Sample | Arm | Status | Ground truth reported as blocking | Seen but not blocking | Missed | Other valid findings | Blocking false positives |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| CONC-D | B | `PASS` | none | H1 (F1: the ADR does not state the linear bound and the stalled-first test is absent; IM1 judged `SATISFIED`) | H2 | F2: no contextual test interrupts a running wave task; `cancel(true)` to `cancel(false)` survives every contextual test. Still true on `main` at `ed0f2e4`. | 0 |
| CONC-D | P | `NEEDS CHANGES` | H1 (F1, R4, rerun) | none | H2 | F2: the request omitted the owner's revision of IM1 | 0 |
| CONC-F | B | `PASS` | n/a | n/a | n/a | F1 (non-blocking): same request omission | 0 |
| CONC-F | P | `PASS` | n/a | n/a | n/a | 2 optional improvements | 0 |
| ARCH-S | B | `NEEDS CHANGES` | S1 (F1) | none | none | F2: the unplanted test gap, plus an untested public entry point | 0 |
| ARCH-S | P | `NEEDS CHANGES` | S1 (F1, R4, rerun) | none | none | F2: the unplanted test gap | 0 |
| PERF-S | B | `NEEDS CHANGES` | G1 (F1), G3 (F3d); G2 in part (F3b: the lane mechanism is unmeasured, with no bound computed) | none | none (G4 disputed: not raised) | F2: a warmed rerun does not reproduce 8.9x; F1 shows the recorded command gives Vector slower than Scalar | 0 |
| PERF-S | P | `NEEDS CHANGES` | G1 (F1), G2 (F2, against the 4x bound), G3 (F3) | G4 named only inside an optional improvement | none (G4 disputed) | F4: the growth and retention statements compare an Intel/Temurin 26 run with a Ryzen/Zulu 27 run without saying so (IC1) | 0 |

**Verdicts.** The pilot arm returned the verdict the ground truth calls for on all four samples. The baseline arm did on
three; on CONC-D it returned `PASS` on a snapshot whose IM1, as revised by the owner, was not met. Both arms saw the
same facts there: the baseline reviewer wrote down that the ADR sentence and the test were missing, then judged the
criterion against the revised bound alone. One run per arm cannot separate the effect of P3's `Required by:` and rung
rules from reviewer variance.

**Detection.** Of six undisputed planted or historical defects (the unplanted ARCH-S test gap, which both arms
found, is listed with the other valid findings, and G4 is reported separately above), the pilot arm reported five as
blocking (H1, S1, G1, G2, G3) and the baseline arm three and a part of G2 (S1, G1, G3). Both arms missed H2. Neither
arm raised G4 as a finding. Neither arm raised a blocking false
positive, so the pilot guidance showed no increase in false positives on these samples.

**Evidence quality.** Every pilot blocking finding names its requirement and rung and was rerun; the baseline findings
use the older demonstrated or suspected head, and their reproducers were not rerun. The baseline PERF-S reviewer ran
JMH twice and showed the recorded command does not reproduce, which is stronger evidence than any finding in the pilot
arm for that sample. The guidance does not decide this: both skills allow it.

**Cost.** Harness counts per review:

| Sample | B tokens | B tool calls | B wall time | P tokens | P tool calls | P wall time |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| CONC-D | 136k | 29 | 7.3 min | 154k | 32 | 9.8 min |
| CONC-F | 115k | 24 | 7.3 min | 134k | 24 | 7.9 min |
| ARCH-S | 104k | 21 | 11.0 min | 111k | 18 | 7.1 min |
| PERF-S | 93k | 10 | 3.9 min | 109k | 12 | 4.2 min |

The pilot arm used 7% to 17% more tokens on every sample. This is an observation, not a measured cost of P3 to P5:
three pilot runs date from 2026-11-12 with three concurrent reviews, the baseline runs from 2026-11-13 with five, and
reviewers chose different checks and mutants. Wall times mostly reflect machine load.

## Per-practice evidence and decision

Decision vocabulary: **ADOPT** keeps the practice in the skills and checklists as the standard. **RETAIN ON PROBATION**
keeps it, records that no gain has been shown, and names what would roll it back. **REJECT** removes it or keeps it
out. No practice is made mandatory on every PR.

### P1. Claim evidence report: ADOPT

| Evidence | Observed |
| :--- | :--- |
| Real claim (#201) | Replaying ADR 0013's "6.2x to 6.7x" claim gave `NARROWED`: 5.0x to 8.5x on another machine, above the four-lane bound, with differing arithmetic and a 32 B/pair reference allocation. A committed claim was shown to be overstated in its attribution, and its numbers were kept. |
| Seeded claims (#201) | All ten seeded invalid claims (S1 to S10) were flagged by the intended rule. The authors of the rules wrote the seeds. |
| Comparison (PERF-S) | Both arms blocked the seeded change. The pilot arm graded the lane attribution against the 4x bound (IC6) and found the unplanted cross-machine comparison (IC1). The baseline arm flagged the mechanism as unmeasured without a bound. Neither raised the disputed G4. |
| Cost | Under 2 minutes of JMH for the #201 replay; the claim-environment probe under 1 s. Trigger-only: no cost on a change without a numeric claim. |
| Maintenance | One skill section, one template, one script. No dependency, CI gate, or hardware. |

Decision: keep P1 as it stands, triggered only by a stated measured gain. The data supports more precise grading of an
invalid claim, not more detection. **Rollback condition:** remove or narrow it if a later review shows it blocking a
valid claim, or if its required fields are repeatedly filled with `pending` for no claim that matters.
**Open question, not fixed:** whether IC3 applies when a change only measures unchanged code (G4). The standard does
not say, and the two reviewers' notes point in different directions (see "Samples and ground truth"). It is left as
written until a real claim raises the question.

### P2. Bounded schedule check of Aeon failure semantics: ADOPT, narrowed to its target

| Evidence | Observed |
| :--- | :--- |
| Properties | Five ADR 0016 properties hold on every schedule within the bounds (17,500 schedules at bound 4, 360,328 at bound 5). Two deliberately false properties are refuted with 2-input counterexamples, one replayed on the real coordinator. |
| Able to fail | Six scratch breaks, each killed by its target property, and again after #208 changed the coordinator. |
| Real defects | Building the model found two exits that leave submitted tasks running (caller interrupt, executor rejection). They were not found by the checker but by reading the code for the correspondence table. #208 reproduced both, plus the same gaps on the contextual path, and PR #209 fixed them. |
| Own defects | Review of PR #207 found the first suppression property too weak (an empty list passed). Mutant `m6-no-suppressed` now covers it. |
| Comparison (CONC-D) | `formalPilotCheck` passed on the defective snapshot in both arms. H1 is a cost bound and H2 lies on the contextual path, both outside the model, as its exclusions state. |
| Cost | Bound 4: 17,500 schedules, 2.5 s of exploration, 6.8 s of Gradle wall time with a warm daemon on `ed0f2e4` today. |
| Maintenance | #208 required regenerating all six mutant patches and rewriting one (`m2`). The harness reads one private field reflectively. About 1,200 lines of test code. |

Decision: keep the harness, the `formal-pilot` tag, and `formalPilotCheck` as they are, and ask for the check only
in the scope defined in `monada-neuron-concurrency-hardware-acceleration`, "Bounded Parallel Aeon Coordination": the
coordinator, its ADR 0016 failure or abnormal-exit decisions, or the schedule harness. It stays out of
`./gradlew test` and CI. Wider formal modeling, a generated verification skill (FV10), and TLA+ or other tools stay
rejected: one model on one target found its defects through modeling, and that does not show a per-PR return.
**Rollback condition:** delete the harness, the task, and the pointers if a refactoring of the coordinator would need
the harness rewritten rather than its mutants regenerated, or if the coordinator is replaced.

### P3. Proof-graded findings: ADOPT

| Evidence | Observed |
| :--- | :--- |
| Comparison | The only verdict difference between the arms was CONC-D, where the pilot arm reported H1 as blocking at R4 and the baseline arm saw the same gap and passed. No blocking false positive in either arm, including the no-defect control. |
| Format | Every pilot finding carried `Required by:`, a rung, a rerun, and a smallest regression check. Zero retractions in four pilot reviews, so retraction data is still empty. |
| Gate | `publication-gate-check.sh` and its `--mutants` mode pass; the verification line and status rules are unchanged. |
| Limit | The difference rests on one run per arm on one sample. |

Decision: keep. It changes no status, gate, or loop, and the only observed difference went the right way.
**Rollback condition:** revert to the pre-#203 finding format if a later comparison shows the rung rules making
reviewers withhold blocking findings that the older format would have raised, or if the retraction count stays at zero
while blocking false positives appear.

### P4. Boundary impact trace: RETAIN ON PROBATION

| Evidence | Observed |
| :--- | :--- |
| Comparison | No outcome differed because of it. On CONC-D the pilot reviewer cleared the contextual path at R4 and still missed H2, as the baseline reviewer did. ARCH-S could not test it: both arms' requests named ADR 0026 as a criterion (IM2), and the baseline reviewer cited that criterion. |
| Format | Pilot reviewers filled the table, graded surfaces honestly (one R3 where R4 was not reached), and marked it not triggered on the documentation-only PERF-S. |
| Cost | Not separable from the rest of the review. |

Decision: keep it as written, because it is conditional and adds no reviewer or check, but do not present it as an
improvement. The verifier skill now says that no gain has been shown. **Re-evaluation trigger:** after five boundary-crossing PRs
reviewed under the current skills, or on 2027-05-13, whichever comes first; the repository owner decides.
**Rollback condition:** at that re-evaluation, remove it if none of those reviews produced a confirmed finding or an
avoided consumer regression through a traced surface.

### P5. Boundary red-flag screen: RETAIN ON PROBATION

| Evidence | Observed |
| :--- | :--- |
| Comparison | Untested. Both arms flagged the seeded ADR 0026 violation (S1), but both requests named ADR 0026 as a criterion (IM2), and the baseline reviewer reached S1 through that criterion (`results/baseline-arch-seeded.md`, IM2). The sample therefore cannot show whether the screen or the older questions would find such a violation unprompted. No false flag in either arm. |
| Cost | Four questions in the checklist; no tool. |

Decision: keep the four questions, and record in the checklist that the comparison could not test them. A later
sample must not name the violated ADR in its criteria. **Re-evaluation trigger and rollback condition:** the same as
P4.

### Not adopted, unchanged from the audit

Fan-out or multi-model review (IR4, AR2, BR7), a mandatory formal check on every PR, a generated verification skill
(FV10), the `statechart` tool and TLC (SC1, SC7), and installing pstack. Nothing in the pilots argues for revisiting
them.

## Portability, consumer verification, and optional tools

- No practice adds a runtime, build, or CI dependency. P2 runs wherever `./gradlew test` runs; P1's
  `claim-environment.sh` uses only the JDK and standard shell tools.
- The review guidance stays harness-neutral ("a separate subagent") and works in Codex and Claude Code alike.
- Consumer verification is unchanged: `consumerSmokeTest`, `jpmsConsumerTest`, `forgeHostConsumerTest`,
  `embeddedHostConsumerTest`, the Store adapter and integration tests keep their CI jobs. P4 only asks a reviewer to
  run the consumer task of a touched surface or report it `NOT RUN`.
- Ongoing burden is in text, not tooling: the evaluation skill grew from 181 to 295 lines and the verifier skill from
  191 to 275, and each skill edit is made twice (see below).

## `.windsurf/skills/` mirror

| Question | Finding |
| :--- | :--- |
| Who reads which path | `.agents/skills/` is canonical (`AGENTS.md`). `.windsurf/skills/` exists for tools that scan the Windsurf path. No other client skill path (for example `.claude/skills/`) is tracked. |
| Drift history | The mirror drifted once (fixed in #193). Since then CI runs `diff -r .agents/skills .windsurf/skills`, and no drift has reached `main`. |
| Cost | Every skill change is made twice: #201 changed 7 skill files and #203 changed 2, each mirrored. Scripts are duplicated too, but every instruction cites them by their `.agents/skills/...` path, so the copies under `.windsurf` are never the ones run. |
| Thin shim option | A `.windsurf/skills/<name>/SKILL.md` holding only frontmatter and a pointer to the canonical file would remove the duplication. Whether Windsurf discovers such a stub and follows the pointer has not been tested. A symlinked directory would depend on `core.symlinks`, which is off by default on Windows checkouts. |

Decision: keep the byte-identical mirror and its CI check. Adopt a thin shim only after a test shows that Windsurf
discovers a stub skill and loads the canonical file; that migration is out of scope here.

## Changes made by #204

| File | Change |
| :--- | :--- |
| `.agents/skills/README.md` | New section "Evidence Practices (Epic #199)" with each practice's status and owner. |
| `monada-neuron-independent-verification/SKILL.md` | Notes that the boundary impact trace is on probation. |
| `monada-neuron-concurrency-hardware-acceleration/SKILL.md` | New section "Bounded Parallel Aeon Coordination": the one definition of when `./gradlew formalPilotCheck` runs, and how to keep the mutants effective. |
| `docs/specs/pr-review-checklist.md` | The same P2 question under "Concurrency"; the red-flag screen marked as on probation. |
| `formal-pilot-aeon-failure-semantics-v1.md` | Gate status and maintenance rows point to this decision. |
| `build.gradle.kts` | The comment on the `formal-pilot` exclusion points to that skill section (comment only). |

Each skill change is mirrored under `.windsurf/skills/`.

## Limitations

- **Sample size.** Four samples, one run per arm. Run-to-run variance exists
  (`monada-neuron-independent-verification/validation.md`, round 3) and was not measured.
- **Non-concurrent arms.** The pilot arm for three samples ran on 2026-11-12 with a skill revision that differed from
  the committed one in three places that #203 shows did not affect its results. The baseline arm ran a day later.
- **Leakage through the Issue.** The owner's revision comment on #208 describes the H1 fix. Both arms could read it,
  and both cited it.
- **Seed authorship.** The orchestrator wrote PERF-S and its ground truth. G1 to G3 resemble #201's seeded claims S5 to
  S7, which favors the pilot arm, although the reviewers were barred from reading those.
- **Unequal access between arms.** The baseline reviewers, and the pilot reviewer on PERF-S, read their guidance from
  a separate checkout and were barred from `docs/experiments/`, `docs/benchmarks/` and other checkouts. The #203
  pilot-arm reviewers for CONC-D, CONC-F and ARCH-S had no such restriction and read the guidance from the branch's
  working tree. Part of the CONC-D difference may come from that access rather than from the guidance. Nothing checked
  that restricted reviewers never read the snapshot's own copy of the guidance.
- **Partial blinding.** No reviewer was told the expected outcome, but the new runs knew they were in an experiment and
  could infer their arm from the guidance checkout's name. The #203 runs were reviews of a pilot, not of a comparison.
- **One model.** Every reviewer was the same model in the same harness. Results may differ in Codex.
- **Tokens.** Counts come from the harness and include reading the guidance; they are not comparable across days.

## Reproduce

```bash
git worktree add --detach ../eval204-guidance-baseline 923c9e510b7893bdc85ecb64411e1a4d47c9dd10
git worktree add --detach ../eval204-guidance-pilot ed0f2e4
git fetch origin refs/pull/209/head
git worktree add --detach ../eval204-conc-defect 79195633333e0a940e55d810a69dbf1979dd004c
git worktree add --detach ../eval204-conc-fixed a5c80cdbb8d236967ffbaa579f23aca55b432e18
git worktree add --detach ../eval204-arch-seeded 634093fed222632a77847850867a596e0de5cc2c
git -C ../eval204-arch-seeded apply "$PWD/docs/experiments/evidence-review-pilot-v1/samples/seeded-spend-preapproval.patch"
git worktree add --detach ../eval204-perf-seeded 923c9e510b7893bdc85ecb64411e1a4d47c9dd10
git -C ../eval204-perf-seeded apply "$PWD/docs/experiments/pstack-adoption-evaluation-v1/samples/seeded-vector-million.patch"
```

Commit each seeded worktree locally (`git add -A && git commit`) so the reviewer gets a clean committed snapshot; the
resulting trees are `87f73dafb6f4a76dd1dfeb361e68c8c12bdb3a2d` and `479979358de1c3148f75c282c9610d4dd9a60601`. Then run
one separate reviewer subagent per sample and arm on its arm's
`.agents/skills/monada-neuron-independent-verification/SKILL.md`. The request carries the snapshot line of each result
file; the criteria are AC1 to AC5 and IM1 to IM3 of #208 as the #203 pilot quoted them, AC1 to AC4 plus IM1 and IM2 of
S203-1, and AC1 to AC4 of S204-1. P2 cost on the current tree:

```bash
./gradlew formalPilotCheck
```
