# pstack Skill Audit (`pstack-skill-audit/1`)

## Status

| Field | Value |
| :--- | :--- |
| Audit version | `pstack-skill-audit/1` |
| Issue | [#200](https://github.com/stevdrey/monada-neuron/issues/200) (Agent Skills 1/5, Epic [#199](https://github.com/stevdrey/monada-neuron/issues/199)) |
| Audited Neuron revision | `main` at `f596ec4892900e2df37e64faefde50adcde1f02c` |
| Audited upstream | `backnotprop/pstack` at `08547969e1dffaa6457ea65141cc62c1d9a2eef1` (plugin version 0.15.36), compared with `cursor/plugins` at `de8ee15dd1194627d21eb5fcd41cb1873208dabb` |
| Audit date | 2026-11-10 |
| Consumers | [#201](https://github.com/stevdrey/monada-neuron/issues/201), [#202](https://github.com/stevdrey/monada-neuron/issues/202), [#203](https://github.com/stevdrey/monada-neuron/issues/203), [#204](https://github.com/stevdrey/monada-neuron/issues/204) |

This audit decides which techniques from six pstack skills are worth piloting in Monada Neuron, and on what terms. It
is a scoping document. It changes no code, runtime behavior, skill, CI job, or dependency, and it installs nothing.

**No ADR.** None of the ADR triggers in `AGENTS.md` applies: the audit fixes no cognitive boundary, module ownership,
signal representation, memory ownership, concurrency model, persistence or interoperability contract, hardware
acceleration strategy, preview or incubator API dependency, or data layout. Selections are inputs to a pilot, and the durable adoption decision belongs to #204. #204 should add an ADR only if what it adopts
meets one of those triggers.

**What this audit does not claim.** It does not show that any selected practice finds more defects or costs less than
the current process. That comparison is #204. The audit read upstream instructions and did not run them.

## Outcome by skill

| Upstream skill | Decision | Pilot | Reason in one line |
| :--- | :--- | :--- | :--- |
| `benchmark-checklist` | ADAPT | #201 | Several questions fill real gaps. Its tooling and "ballpark" exception do not fit, and it has nothing on reference equivalence or knowledge evaluation. |
| `create-formal-verification-skill` | ADAPT, narrowed | #202 | Its quality bar suits one bounded model. A repository-wide target hunt and a generated permanent skill do not. |
| `statechart` | REJECT the tool, keep rules SC2 to SC6 | #202 | The rules on invariants, counterexamples, and replay are useful. The tool adds a TypeScript runtime and a networked Java/TLC install. |
| `interrogate` | ADAPT, without fan-out | #203 | The proof requirement is useful. Fan-out on every review would be the automatic multi-model review the Epic excludes. The matrix keeps four of its techniques and rejects the others. |
| `blast-radius` | ADAPT | #203 | The evidence ladder and "the one fact it is safe because of" sharpen existing probes. |
| `architect` | REJECT the workflow, ADAPT a screen | #203 | Four of its eight design red flags map to Neuron boundaries. The sketch-and-implement workflow would be a second implementation loop. |

`arena` was read only because `interrogate`, `blast-radius`, and `architect` depend on it. It is rejected with them
(techniques IR4, BR7, AR2). The other 51 of the 58 upstream skills, including `poteto-mode`, the `principle-*` skills,
`reflect`, and `recall`, were not assessed and are not selected. No wholesale installation of pstack is proposed.

## Decision vocabulary

- **ADOPT**: carry the rule into an existing Neuron skill or checklist, or into the report of the pilot that uses it,
  changing only wording and project nouns. No upstream file is installed.
- **ADAPT**: keep the idea and change the mechanics, as the matrix states.
- **REJECT**: do not carry forward, with one reason: `conflict` (contradicts an Epic non-goal, ADR, or rule),
  `duplicate` (an existing safeguard already covers it), `out-of-scope` (outside the Epic or the pilot Issue that
  would carry it), or `tool` (unacceptable tooling, dependency, or data flow).

## Boundary: agent practices versus the system under construction

Everything in this audit governs **how coding agents work on the repository**. None of it is part of Monada Neuron's
cognition. Several upstream words collide with Neuron concepts, and the collisions must stay visible.

| Upstream term | Meaning upstream | Neuron concept with the same word | Rule |
| :--- | :--- | :--- | :--- |
| `recall` skill | Rebuild an agent's working context from chat history | `MEMORY_RECALL`, `ResonanceMemoryPort` (ADR 0010, ADR 0018) | Agent session memory is not long-term memory. Nothing here reads or writes Monada Resonance Store (ADR 0001). |
| Routing (`poteto-mode`), `arena runners` | Send a prompt to a playbook; choose reviewer models | Forge route recommendation (ADR 0026) | ADR 0026 separates workflow-stage, worker/model, and hardware-backend choices. Agent tool selection is none of them and produces no `RoutingDecision`. |
| Skill | Instructions for a coding agent | Capability, `ActionCapability` boundary (ADR 0011); Aeon capability | A skill never becomes a runtime capability. |
| State, statechart | A model of code written for checking | Cycle stages `PERCEPTION` to `ACTION` (`docs/architecture.md`) | A model describes the code and does not define it. Changing a stage needs an ADR, not a model edit. |
| Evaluate, reflect, judge | Rate agent output | Hypothesis evaluation and adaptation (ADR 0020, ADR 0021); `monada-neuron-evaluation` | Agent review scores are never feedback to a cycle. |

Consequences for every selected practice:

- Nothing it produces goes into `src/main`, the published artifact, or a runtime classpath. ADR 0025 keeps the core
  artifact free of runtime dependencies.
- Core cognition stays usable without any agent tool, LLM, or vendor API (ADR 0002).
- Task text and model output never enter Neuron through these practices (ADR 0033).

## Evidence Neuron already requires

The selected practices extend the following. They must not restate or replace them.

| Safeguard | Source | Already covers | Do not duplicate |
| :--- | :--- | :--- | :--- |
| Performance record | `AGENTS.md` "Testing and Benchmarking"; `monada-neuron-performance-optimization` "Baseline Required" | Workload, size, JVM/JDK, flags, warm-up, method, before and after | The list of baseline fields |
| Reference implementations | Design principle 6; ADR 0013 (`ScalarBatchResonanceEvaluator`), ADR 0016 (`DeterministicAeonCoordinator`), ADR 0017 (reference backends) | Optimized paths checked against an oracle, with a tolerance | Oracle rules or tolerances |
| JMH discipline | `monada-neuron-evaluation-benchmarking` "Microbenchmarking"; `:monada-neuron-evaluation:jmh`, `jmhResonanceStoreAdapter`; `docs/benchmarks/baseline-methodology.md` | Forks, warm-up, mode, state scope, dead-code elimination | JMH configuration guidance |
| Comparative report | `monada-neuron-evaluation-benchmarking` "Comparative Experiments" ("Preferred report") | Question, environment, workload, reference, candidate, correctness check, results, decision | A parallel report template; P1 extends this one |
| SIMD, parallel, FFM, GPU experiments | `monada-neuron-evaluation-benchmarking`; `docs/specs/pr-review-checklist.md` "SIMD / Native / GPU" | Tails, small inputs, scaling, transfer and warm-up cost, crossover, fallback | End-to-end accelerator rules |
| Pre-registered protocols | `docs/experiments/*.md`; design principle 13 | Hypothesis, arms, seeds, verdict rule fixed before results | Evaluation protocol design |
| Independent verification | `monada-neuron-independent-verification` (#191, #195) | Separate reviewer, snapshot identity, demonstrated versus suspected findings, probes, status rules, revalidation | Reviewer independence, result contract, verification line |
| Implementation loop | `monada-neuron-implement-issue` (#192) | Issue-to-PR state machine, clarification, fix rounds, escalation, publication gate | Any second implementation or fix loop |
| PR format | `monada-neuron-create-pull-request` | Headings, exact commands, PASS / FAIL / NOT RUN | PR body rules |
| Review checklist | `docs/specs/pr-review-checklist.md` | Architecture, Java 27, algorithms, performance, concurrency, testing, docs | Existing questions |
| CI gates | `.github/workflows/ci.yml` | ADR numbers, skill mirror, fingerprint and gate self-tests with mutants, `check`, `consumerSmokeTest`, `jpmsConsumerTest`, `forgeHostConsumerTest`, Store adapter, integration tests, `embeddedHostConsumerTest` | Consumer verification paths |

**Precedent worth reusing.** The self-test of `publication-gate.sh` has a `--mutants` mode that shows every check is
needed, which is the same discipline as upstream's rule that a check must be seen to fail (FV4). Baseline
methodology's "Full-Cycle Validity (Issue #48)" records a real case where a benchmark named `FullCycle` measured only
the perception stage, which is the failure BC9 targets.

## Adopt, adapt, reject matrix

Paths such as `§Q3` refer to sections of the upstream `SKILL.md` at the audited revision. Techniques are paraphrased,
apart from short phrases and section names.

### `benchmark-checklist` (for #201)

| Id | Upstream technique | Existing Neuron coverage | Decision | Delta or reason |
| :--- | :--- | :--- | :--- | :--- |
| BC1 | Write the claim you expect to ship before running (§Before) | Evaluation skill asks for an engineering question, not a claim | ADOPT | Required by #201 ("explicit claim"). |
| BC2 | Read the script for what it times and ignores; record machine load and cores (§Before) | Baseline records OS and CPU, not load | ADAPT | Add load at run time. Replace `uptime`/`nproc`-only advice with whichever equivalent the host has. |
| BC3 | Name the limiter, profiling in a run you do not report (§Q1) | Performance skill "Profiling" finds hot paths; no report field | ADAPT | Use JFR/JMC or async-profiler, not `node --cpu-prof` or `py-spy`. Require a limiter for explanatory and adoption claims. |
| BC4 | Each side tuned as it ships, or no winner (§Q2) | `AGENTS.md` records flags; no rule that both sides are tuned | ADAPT | State per side: `--add-modules jdk.incubator.vector`, backend selection (ADR 0017), parallelism threshold (ADR 0016 defaults it off). |
| BC5 | Check the result against physical limits and removable share (§Q3) | None | ADOPT | Arithmetic bound on the claimed gain. |
| BC6 | Count errors and check outputs are correct (§Q4) | "Correctness Baseline First" | ADAPT | Check against the reference. Latency-only failures stay diagnostics, because protocols such as the `html-knowledge` family state latency never affects a verdict. |
| BC7 | At least 5 alternating runs, median and range (§Q5) | JMH forks; three-fork comparisons in baseline methodology | ADAPT | JMH keeps forks and its own error statistics. Alternation applies only to non-JMH system runs. |
| BC8 | Report a micro result as a share of the end-to-end path (§Q6) | End-to-end rule for GPU and accelerators | ADAPT | Extend only the "share of the whole" field to CPU micro results. |
| BC9 | Confirm the work happened inside the timed region (§Q7) | JMH dead-code note; #48 | ADOPT | Require a work counter or terminal-status assertion in the report. |
| BC10 | Verdict vocabulary: faster, slower, no measurable difference, inconclusive (§Report) | None | ADOPT | Gives "unsupported claim" a precise outcome. |
| BC11 | One run suffices for a requested ballpark (§intro) | Every claim needs a baseline | REJECT `conflict` | Conflicts with the Epic non-goal of no speedup claim without warm-up, end-to-end measurements, and independently checked semantics. A single run may be labeled exploratory but cannot support a stated gain. |
| BC12 | One PR number; pointers to `Opening a PR`, `Perf issue`, `Hillclimb` playbooks (§Report, §Other) | `monada-neuron-create-pull-request` owns PR format and has no primary-number rule | REJECT `out-of-scope` | #201 integrates with Neuron's checklist and skills, and PR format does not change here. The playbooks are pstack workflow. |
| BC13 | Upstream gap: no reference equivalence, tails, tolerance, gold labels, abstention, holdouts, or leakage | Partly in protocols | (Neuron addition) | #201 must write these itself. Upstream offers nothing to reuse. |

### `create-formal-verification-skill` (for #202)

| Id | Upstream technique | Existing Neuron coverage | Decision | Delta or reason |
| :--- | :--- | :--- | :--- | :--- |
| FV1 | Choose the strongest promise in the repository (§Choose) | #202 names one narrow behavior | ADAPT | Strongest within the narrow shortlist below. No repository-wide hunt. |
| FV2 | The question chooses the tool; TLA+ is the default for ordering questions (§Tool) | "Prefer JDK capabilities", "Keep dependencies minimal" | ADAPT | No default. #202 records why each candidate was chosen or not. |
| FV3 | True to the code, with one model step per indivisible code step (§Good) | None | ADOPT | #202 "exact code-to-model correspondence". |
| FV4 | Able to fail: each property shown failing on a broken variant, by following the check once on a scratch break (§Good, §Prove) | Gate `--mutants` precedent | ADOPT | #202 "controlled negative case". |
| FV5 | Findings stay findings: never weaken a property to pass (§Good) | "Do not silently normalize the mismatch" (`AGENTS.md`) | ADOPT | A failure in current code becomes an Issue, and #202 changes no product code. |
| FV6 | State what is verified, under which bounds, and what is left out (§Good) | Epic acceptance criterion | ADOPT | Includes fairness, bounds, and exclusions. |
| FV7 | Property, harm, and counterexample readable in plain sentences (§Good) | None | ADOPT | A reviewer must not need the tool's language. |
| FV8 | One command, reproducible tool version, short run (§Good) | PR evidence rules | ADAPT | Not a CI gate. Cost is recorded for #204. |
| FV9 | Record covered code and the commit last confirmed (§Good) | None | ADAPT | Lives in the pilot's report. No automated staleness check before #204. |
| FV10 | Generate a project-local verification skill (§Generate) | Epic: skills only under `.agents/skills/` | REJECT `out-of-scope` | Not a #202 deliverable. A permanent skill before the go/no-go would pre-empt #204, which may revisit it. |
| FV11 | Delegate results to `/statechart`, drift to `/maintain-formal-verification-skill` | None | REJECT `tool` | Couples the pilot to pstack's own tooling. |

### `statechart` (for #202)

| Id | Upstream technique | Existing Neuron coverage | Decision | Delta or reason |
| :--- | :--- | :--- | :--- | :--- |
| SC1 | TypeScript CLI that installs Java and TLC through `mise` on first run (§Run) | None | REJECT `tool` | See "Source and security review". Its Java is Temurin 25, not the project's 27. |
| SC2 | Every agreed rule is an invariant, never weakened (§Write) | FV5 | ADOPT | Same rule as FV5; carry it once. |
| SC3 | Give correct end states an explicit self-loop so they are not deadlocks (§Write) | None | ADAPT | Applies only if #202 selects TLA+. |
| SC4 | Report the shortest path to each broken rule (§Read) | None | ADAPT | Any mechanism in #202 reports a minimal counterexample in code terms. |
| SC5 | Replay each counterexample against real code, one step per action; assume the model is wrong until shown otherwise (§Hand off) | None | ADOPT | The strongest rule in this skill. It bridges a model finding to `[demonstrated]`. |
| SC6 | List rules not checked because a run hit a bound (§Read) | FV6 | ADAPT | Folded into FV6 reporting. |
| SC7 | Mermaid chart, reader-word view module, Pele renderer (§Pick, §Show, `references/render.md`) | None | REJECT `tool` | The view module is code the tool imports and runs, and Pele is an extra npm install. #202 needs no chart. |

### `interrogate` (for #203)

| Id | Upstream technique | Existing Neuron coverage | Decision | Delta or reason |
| :--- | :--- | :--- | :--- | :--- |
| IR1 | A finding survives only when a run proves it (§intro) | Verifier: only demonstrated defects are blocking on their own | ADAPT | Keep `[suspected]` findings as non-blocking, as the verifier requires. Upstream drops them. A suspected risk that makes a criterion unverifiable stays blocking. |
| IR2 | Report findings; do not apply fixes (§intro) | Independence rules | REJECT `duplicate` | Already mandatory. |
| IR3 | State intent from the message, commits, and PR text (§Frame) | Criteria come from the Issue; author statements are hypotheses | REJECT `conflict` | Using the author's text as the standard would weaken independence. |
| IR4 | Fan out adversaries through `arena` worktrees, by default on two model families (§Fan out) | One independent subagent | REJECT `conflict` | P3 runs on every review, so fan-out there would be the automatic multi-model review the Epic excludes. Without a settings file it would also send code to a second model family. |
| IR5 | Each finding gives title, exact command, printed output, and the output the intent requires (§Fan out) | `Reproduce:` line gives command and observed output | ADAPT | Add "required by" (a criterion, ADR, or contract). Three-tag finding head unchanged. |
| IR6 | Rerun each finding's command before keeping it (§Verify) | Revalidation by a new reviewer; orchestrator cannot overrule | ADAPT | The same reviewer reruns its reproducer from a clean scratch copy before reporting it blocking. The orchestrator does not filter findings. |
| IR7 | Report dropped findings, with count and reason (§Output) | None | ADAPT | Count retracted findings under `Residual risks and limitations`, as false-positive data for #204. |
| IR8 | Run a prototype against a design or plan (§Fan out) | None | REJECT `out-of-scope` | #203 reviews an exact diff or commit. |

### `blast-radius` (for #203)

| Id | Upstream technique | Existing Neuron coverage | Decision | Delta or reason |
| :--- | :--- | :--- | :--- | :--- |
| BR1 | Five-rung evidence ladder: said, pointed at line, walked the failure, ran it, reproduced in the app (§How sure) | Demonstrated versus suspected | ADAPT | Map rungs 1 to 3 to `[suspected]` for a defect, and rungs 4 and 5 to `[demonstrated]`. A violation of a written contract reaches rung 4 only when a recorded command shows the violating code or text beside the clause it breaks. That is how #203's "direct proof of violated contract" fits the verifier's rule, which does not change. Otherwise it stays `[suspected]`, or becomes an `UNMET` or `PARTIAL` criterion row. |
| BR2 | Find the one fact the change is safe because of (§Steps 2) | None | ADOPT | Stated with its rung, or marked unproven. |
| BR3 | Look where grep stops: library source, timing, data formats, flags (§Steps 3) | Probe class "compatibility" | ADAPT | Name Neuron's surfaces, listed under P4. |
| BR4 | Rate each risk by real chance and cost; separate confirmed from cleared (§Steps 4) | Findings and probes | ADAPT | Keep confirmed and cleared. Drop chance estimates, which no reviewer can measure. |
| BR5 | Cheapest test or repro to run before merging (§Hand back) | `Reproduce:` line; regression coverage on revalidation | ADAPT | The `Reproduce:` line stays as it is. Add only the smallest regression check the reviewer recommends. |
| BR6 | Listing callers is not the job (§intro) | None | ADOPT | A caller list alone is not an impact result. |
| BR7 | `arena` for wide changes; write through `unslop`; `how` and `why` companions (§Steps 6) | None | REJECT `tool` | Pulls in pstack-internal skills and a multi-model step. #203 bounds review cost, and P4 uses the existing reviewer. |

### `architect` (for #203)

| Id | Upstream technique | Existing Neuron coverage | Decision | Delta or reason |
| :--- | :--- | :--- | :--- | :--- |
| AR1 | Ground in existing systems before designing (§A) | "Before Work" reading | REJECT `duplicate` | Already mandatory. |
| AR2 | Multi-model `arena` with a user-level model file and default runners on two families (§B) | None | REJECT `tool` | Depends on a settings file under the home directory and, without one, on a second model family. #203 takes only focused checks from this skill. |
| AR3 | Require two structurally distinct candidates (§B) | ADR "alternatives" section | REJECT `duplicate` | ADRs already record alternatives. |
| AR4 | Screen against design red flags (`references/design-red-flags.md`) | Part of the review checklist | ADAPT | Use four: split ownership, information leakage, hand-synced list, importable internals. See P5. |
| AR5 | Red flags temporal decomposition and two ways to do one task | Deliberate Neuron design | REJECT `conflict` | Cycle stages are explicit architecture, and keeping a reference path beside an optimized one is design principle 6. |
| AR6 | Proceed to implementation with no human checkpoint by default (§C) | `CLARIFY` and escalation | REJECT `conflict` | Would bypass human decisions. |
| AR7 | `not implemented` sketch as a contract, then fill in (§D) | Smallest coherent change | REJECT `duplicate` | A second implementation loop. |
| AR8 | Scrap the design and rerun when friction recurs (§E) | ADR supersession | REJECT `conflict` | #203 forbids speculative architecture rewrites. |
| AR9 | Red flags shallow module and pass-through method | None | REJECT `out-of-scope` | General design quality, while #203 takes checks only for boundary-crossing changes. |

## Selected practices for the pilots

Precedence for every practice, highest first:

1. platform, user, and repository permission instructions;
2. `AGENTS.md`, and an accepted ADR for the scope it governs;
3. the existing `monada-neuron-*` skills and `docs/specs/pr-review-checklist.md`;
4. the additions below.

Levels 1 and 2 restate `AGENTS.md` and `monada-neuron-implement-issue`. Levels 3 and 4 are this audit's proposal. An
Issue states what its pilot must deliver and does not by itself override a skill's rule. A pilot that needs to change
a rule edits that skill explicitly and reports the change.

Upstream text has no authority. It is source material. Where an addition conflicts with a higher level, the addition
yields and the conflict is reported. The one conflict found so far, between #203's "direct proof" wording and the
verifier's blocking rule, is resolved in BR1.

Cost limits are proposals. #201 to #203 may tighten them, and #204 fixes the limits it compares against.

### P1. Claim evidence report

| Field | Definition |
| :--- | :--- |
| Techniques | BC1 to BC10, plus BC13 written by #201 |
| Pilot | #201 |
| Ownership | `monada-neuron-evaluation-benchmarking` owns the standard, by extending its "Preferred report". `monada-neuron-performance-optimization` and `pr-review-checklist.md` refer to it. The claim's author fills the report and the verifier checks it. |
| Precedence | Adds report fields only. Reference, JMH, and accelerator rules stay as they are, and a registered protocol's verdict rule overrides the report for that protocol. |
| Trigger | An Issue, PR, or document states a measured speedup, latency, throughput, memory, recall, ranking, or evaluation gain. Not triggered by a change with no numeric claim, or by a run labeled exploratory and not used for a decision. |
| Measurable outcome | On one replayed existing benchmark, every field is filled or marked `pending` with a reason. Every seeded invalid claim is identified (at least: no baseline, untuned side, missing reference check, work not performed, leakage). Committed baseline numbers do not change. |
| Non-goals | No unconditional CI benchmark gate, no dependency on specialized hardware or a model provider, no re-benchmarking of history. |

### P2. Bounded model with proof of failure

| Field | Definition |
| :--- | :--- |
| Techniques | FV1 to FV9, SC2 to SC6 |
| Pilot | #202, once |
| Ownership | #202 owns one versioned model or harness and its report. It lives outside `src/main`. Failures become Issues for the owner of the affected code. |
| Precedence | The code and its ADR define the behavior. On a disagreement, the model is presumed wrong until the counterexample is replayed against real code (SC5). |
| Trigger | Issue #202 only. There is no per-PR trigger. Wider use needs a #204 decision. |
| Measurable outcome | On unmodified code the check passes, or fails only on findings reported with a counterexample replayed against real code. A real finding therefore counts as a result, never as a miss. The check also fails on at least one controlled break. The report has a code-to-model table, bounds, and exclusions. Runtime, states or schedules explored, and tool versions are recorded. The core runtime classpath is unchanged. Proposed limit: one command, under five minutes on a developer machine. |
| Non-goals | No mandatory formal tool, no proof of application correctness, no product-code fix, no generated skill, no change to Monad, Aeon, Node, Signal, or Store contracts. |

**Target shortlist for #202 (recommendation; #202 chooses).**

1. `BoundedParallelAeonCoordinator` failure semantics (ADR 0016 decision 5): sibling cancellation, and the earliest
   input index's exception is the one rethrown. This is the only one of the three with real thread interleavings, and
   `DeterministicAeonCoordinator` gives an existing oracle. Interruption is cooperative, so a model must state what
   it assumes about processors that ignore it.
2. Cross-cycle feedback consumption (ADR 0021 "Observable consumption"): the stage runs if and only if an
   `AdaptationCognitiveStageResult` exists. It is smaller, but sequential.
3. All-or-nothing compaction (ADR 0031): rich state, though single-threaded and outside the examples #202 lists.

**Candidate mechanisms.** The version catalog lists JUnit and Guava, and `monada-neuron-evaluation` declares JMH. The
repository has no TLA+, jcstress, or property-based library. A JUnit-driven enumeration of schedules needs no new dependency. TLC would need a recorded version and a
check that it runs on JDK 27, which this audit did not perform.

### P3. Proof-graded findings

| Field | Definition |
| :--- | :--- |
| Techniques | IR1, IR5, IR6, IR7, BR1, BR5 |
| Pilot | #203 |
| Ownership | `monada-neuron-independent-verification` owns the format. `pr-review-checklist.md` refers to it. The existing single reviewer does the work. |
| Precedence | Status rules, the three-tag finding head, and the `verification v1` line do not change. `publication-gate.sh` takes its verdict from that line. New detail goes in the finding body, and the retraction count goes under `Residual risks and limitations`. |
| Trigger | Any review run under `monada-neuron-independent-verification` that raises a finding. |
| Measurable outcome | A historical or seeded defect is reported blocking with a reproducer that fails before the fix and passes after it. A no-defect sample yields no blocking finding. `publication-gate-check.sh` and its `--mutants` mode still pass. Retracted-finding counts and tool cost are recorded. |
| Non-goals | No swarm or multi-model review, no new status, no second fix loop, no orchestrator filtering of findings. |

### P4. Boundary impact trace

| Field | Definition |
| :--- | :--- |
| Techniques | BR2, BR3, BR4, BR6 |
| Pilot | #203 |
| Ownership | A conditional section of the existing verifier procedure, performed by the existing reviewer. It extends the "compatibility" probe rather than adding another list. |
| Precedence | Does not replace `./gradlew check` or consumer tasks. If a surface is touched, the consumer task for it must run or be reported `NOT RUN`. |
| Trigger | The diff touches any of: Monad, Aeon, Node, or Signal contracts; an adapter; the Store boundary; a host facade or routing contract; published API or module names; a serialized format; or a concurrent, SIMD, or native path. |
| Surfaces to trace | Reflective Vector loading and scalar fallback (ADR 0013, ADR 0017, ADR 0032). Automatic module names and the absence of a descriptor (ADR 0025, ADR 0032). Canonical bytes (ADR 0039, ADR 0048) and checkpoints (ADR 0031). Consumer fixtures under `consumer-fixture/`. `monada-neuron-resonance-adapter`. Cancellation, allocation, memory lifetime, and fallback on optimized paths. |
| Measurable outcome | On a boundary-crossing sample, each touched surface is listed as confirmed or cleared with a ladder rung. The safety fact reaches rung 4 or is marked unproven. Review time is recorded. |
| Non-goals | No caller inventory for its own sake, no probability scores, no consumer run for an untouched surface, no redesign proposals. |

### P5. Boundary red-flag screen

| Field | Definition |
| :--- | :--- |
| Techniques | AR4 |
| Pilot | #203 |
| Ownership | "Cognitive Architecture" section of `pr-review-checklist.md`. |
| Precedence | Each flag is a question tied to a named rule. A flag alone is `[suspected]`. It can block only under the verifier's existing rule: as a `[demonstrated]` finding (BR1), through an `UNMET` or `PARTIAL` criterion, or as a suspected risk that makes a required criterion unverifiable. |
| Trigger | Same as P4, excluding paths that are only concurrent, SIMD, or native. |
| The four questions | The first two sharpen existing checklist questions on Store ownership and adapters and do not repeat them. **Split ownership:** beyond memory, does a second writer appear for any owned state, for example authorization in Neuron (ADR 0026) or caller-owned routing state (ADR 0030)? **Information leakage:** beyond types, does a protocol or policy detail of an adapter appear in more than one module (design principle 12, ADR 0018)? **Hand-synced list:** does a new list lack a failing check, as `.windsurf/skills` has `diff -r` in CI? **Importable internals:** does a change rely on a package staying private? Automatic modules export every package (ADR 0025, ADR 0032), so this is a review question. Adding a module descriptor would need a new ADR. |
| Measurable outcome | A seeded violation (for example, an authorization decision added inside Neuron routing) is flagged with its ADR. A no-defect sample yields no blocking flag. |
| Non-goals | No general design-quality review, no rewrite suggestions. |

### Handoff to #204

#204 needs, per practice: confirmed defects, false positives (IR7), counterexamples, claims invalidated, review time,
and tool or token cost where observed. P1 to P5 stay proposals until #204 reports on them. #204 alone updates the
skills index for what is supported, and alone decides whether to retain FV10. #203 should confirm that BR1's recorded
command is sufficient proof of a contract violation, since it sits close to upstream's rung 2.

## Source and security review

Upstream instructions were treated as untrusted data. Files were fetched read-only through the GitHub API into an
isolated temporary directory. No upstream script, installer, or plugin was run, and none of its instructions were
followed. This document paraphrases, and it copies no substantial upstream text.

### Revisions

Blob identifiers are git object ids from the tree at each audited commit, abbreviated to 12 characters.

| Path | Mirror blob | `cursor/plugins` | Result |
| :--- | :--- | :--- | :--- |
| `skills/benchmark-checklist/SKILL.md` | `e901d1fdbc9f` | `e901d1fdbc9f` | identical |
| `skills/create-formal-verification-skill/SKILL.md` | `92f7aa4fa966` | `92f7aa4fa966` | identical |
| `skills/statechart/SKILL.md` | `f5a64a8db4b8` | `f5a64a8db4b8` | identical |
| `skills/statechart/references/render.md` | `5ef0519d6f7a` | `5ef0519d6f7a` | identical |
| `skills/interrogate/SKILL.md` | `7d9674b72b15` | `169f5b478f4c` | one line: harness-neutral subagent wording |
| `skills/blast-radius/SKILL.md` | `b296cce0d746` | `b296cce0d746` | identical |
| `skills/architect/SKILL.md` | `eaff1d9c0947` | `88e23ca105b8` | one line: settings-file location |
| `skills/architect/references/design-red-flags.md` | `f8503c9595b2` | `f8503c9595b2` | identical |
| `skills/arena/SKILL.md` | `e4aaa2dbf833` | `7580c3c9a777` | two lines: settings-file location |
| `LICENSE` | `6b5400237fdf` | `6b5400237fdf` | identical |

`backnotprop/pstack` declares itself a mirror of `cursor/plugins/pstack` with its own edits (`MIRROR.md`). The Epic
links the mirror, so the mirror was audited. Under `skills/statechart/`, three scripts also differ (`run.ts`,
`statechart.ts`, `frontends/dot.ts`). `SYNC-LOG.md` attributes a Windows patch to the mirror, and those differences
were not read.

### License

MIT, Copyright (c) 2026 Lauren Tan, in `LICENSE` at both sources. GitHub detects no repository-level license on
`cursor/plugins`, so the grant relied on is the MIT file in the `pstack` folder. **If #201 to #203 copy substantial
upstream text, they must keep that notice with it.** Paraphrasing into Neuron's own rules, as this audit recommends,
does not raise the question.

### Tooling and security implications

| Skill | Tooling, access, and transitive dependency | Implication |
| :--- | :--- | :--- |
| All audited | Each sets `disable-model-invocation: true`. All except `statechart` name other pstack skills or playbooks. | Manual-only behavior depends on the host honoring a frontmatter key. Using those as written needs the skills they name. The mirror logs near-daily checks against a source that can change, and its last pstack sync was 2026-10-28, so any reuse pins a revision. |
| `benchmark-checklist` | Instructions only; suggests profilers | Low. Refers to `poteto-mode` playbooks. |
| `create-formal-verification-skill` | Instructions only; refers to `statechart` and `maintain-formal-verification-skill` | Low alone. Writes a persistent skill, which is why FV10 is rejected. |
| `statechart` | 407 files, mostly test corpus. A `sh` wrapper launches TypeScript under Node 22.18 to 22.x or 23.6 and later, Bun 1.1.5 or later, or Deno 2.0 or later (`--allow-run`). The first run installs `java@temurin-25` and TLC 1.7.4 through `mise`, and needs the network. TLC's download is pinned by SHA-256 in `run.ts`. Java is not. Child processes inherit the environment. `worker.ts` dynamically imports the view module. `references/render.md` tells the agent to install the npm package `pele@1.9.0` outside the repository for image output. | Rejected as a dependency: adds a JavaScript runtime and network installs to a Java project, on a JDK other than 27. Reused rules need none of it. `TLA2TOOLS` and `JAVA` can bypass `mise`, if #202 ever evaluates TLC directly. |
| `interrogate`, `architect`, `arena` | Spawn subagents, in worktrees where possible, and read model lists from a home-directory settings file. Without that file, defaults name two model families. `interrogate` also copies uncommitted changes into each worktree. | Without a settings file, repository code goes to a second model family, and the settings file is configuration outside the repository. This is the main reason for IR4 and AR2. |
| `blast-radius` | Instructions only; the `arena` step is optional | Low once BR7 is removed. |

A static scan of the audited instructions and non-test `statechart` scripts found no instruction to hide actions or
reach credentials. The data paths it found leaving the machine are the multi-model fan-out in the row above and the
`mise` and npm installs for `statechart`. This scan was a keyword search plus a full read of each audited
`SKILL.md`, not a line-by-line audit of the scripts.

### Reproducing the source facts

```bash
gh api repos/backnotprop/pstack/git/trees/08547969e1dffaa6457ea65141cc62c1d9a2eef1?recursive=1 --jq '.tree[] | select(.path | test("^skills/(benchmark-checklist|create-formal-verification-skill|statechart|interrogate|blast-radius|arena)/SKILL.md$|^skills/statechart/references/render.md$|^skills/architect/|^LICENSE$")) | "\(.path) \(.sha)"'
```

```bash
gh api repos/cursor/plugins/git/trees/de8ee15dd1194627d21eb5fcd41cb1873208dabb?recursive=1 --jq '.tree[] | select(.path | test("^pstack/skills/(benchmark-checklist|create-formal-verification-skill|statechart|interrogate|blast-radius|arena)/SKILL.md$|^pstack/skills/statechart/references/render.md$|^pstack/skills/architect/|^pstack/LICENSE$")) | "\(.path) \(.sha)"'
```

## Review against accepted ADRs

| ADR | Constraint | Result for selected practices |
| :--- | :--- | :--- |
| 0001, 0010, 0018 | Long-term memory, recall, and ranking belong to the Store, behind a port and one adapter | Respected. Nothing touches memory. P5 makes a violation easier to flag. |
| 0002 | External frameworks and model runtimes never own the core | Respected. The practices are agent guidance and leave the runtime untouched. |
| 0003, 0013, 0014, 0015, 0017 | End-to-end evidence, reference oracles, deterministic fallback | Respected. P1 adds report fields and leaves oracles in place. BC13 is written by #201 against these ADRs. |
| 0009, 0016 | Bounded sequential context, sequential oracle | Respected. P2 models existing behavior read-only, with `DeterministicAeonCoordinator` remaining the oracle. |
| 0011, 0020, 0021 | Capability, evaluation, and feedback boundaries | Respected. See the term-collision table. |
| 0022, 0023, 0024, 0026, 0033 | Host facade and perception, Forge routing ownership, metadata-only adoption | Respected. Agent tool choice is not routing, and no task text enters Neuron. |
| 0025, 0032 | Dependency-free artifact, automatic module names | Respected. P2 requires an unchanged runtime classpath. P5 records that importable internals cannot be enforced without a new ADR. |
| 0030, 0031, 0039, 0048 | Caller-owned routing state, compaction, canonical receipts, checkpoint bytes | Respected. P2 may model ADR 0031 read-only, P4 traces these as surfaces, and P5 uses ADR 0030 as an example. None of the formats or owners changes. |
| 0051, 0052 and other registered protocols | Verdict rules fixed before results | Respected. P1 yields to a registered protocol's verdict rule. |

The table covers every ADR this audit cites, and none of those conflicts with a selected practice. No ADR mentions
agent skills or independent verification. Another ADR can become relevant only through the diff under review, and
that review applies it as usual. Rejected techniques tagged `conflict` name what they conflict
with in the matrix.

## Limitations

- Upstream was read, not run. Claims about its behavior come from its instructions and a keyword scan of its scripts.
- `statechart`'s test corpus and the three scripts that differ between mirror and canonical source were not reviewed.
- The canonical comparison is at git-blob level for the listed files. Only the three differing `SKILL.md` files were
  diffed.
- Whether TLC runs on JDK 27 is unknown.
- Measurable outcomes and cost limits are proposals for #201 to #204, and no pilot has run yet.
- The audited revisions are a snapshot. A later pilot that reuses upstream material must compare against the current
  upstream and update the revision it relies on.
