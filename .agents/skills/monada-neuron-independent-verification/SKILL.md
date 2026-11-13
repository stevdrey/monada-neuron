---
name: monada-neuron-independent-verification
description: Use when an implementing agent needs an independent subagent to verify a Monada Neuron issue's requirements against an exact change snapshot, actively search for defects and edge cases, and return a PASS / NEEDS CHANGES / BLOCKED result with reproducible evidence before any commit, push, or PR.
---

# Independent Verification of Issue Requirements

## Purpose

Certify, or refuse to certify, that a concrete change snapshot meets the requirements of a GitHub Issue. The reviewer establishes a requirement-to-evidence mapping, probes realistic edge cases, runs real checks where feasible, and returns a structured result to the implementing agent (the orchestrator).

This skill is the validation half of the loop in epic #190. The orchestrator drives implementation, corrections, and publication; this skill only verifies and reports. Follow `AGENTS.md`, use the review shape of `monada-neuron-agent-task-workflow` and `docs/specs/pr-review-checklist.md`, and produce evidence in the form `monada-neuron-create-pull-request` expects. Do not restate their rules here.

## Independence rules

- Run as a **separate subagent** with its own context. The orchestrator invokes it; the agent that wrote the change must not perform this review in its own context. If no subagent capability exists, the only valid result is `BLOCKED` (reason: no independent reviewer). A self-review is never reported as an independent verification.
- Treat every author statement (commit messages, PR drafts, "tests pass", summaries) as a hypothesis to check, not as evidence.
- Do **not** modify the reviewed snapshot: no edits, commits, pushes, stashes, branch switches, PRs, issue comments, or merges in the author's checkout. The only exception is the snapshot ref described under step 4 of the procedure. Repro tests and experiments go in an isolated copy and are reported, not applied.
- Do not silently fix defects. Report them with enough detail for the orchestrator to fix them.
- Respect repository, platform, and user permissions. A check that needs an action you are not permitted to take is not run; say so.

## Verification request (input contract)

The orchestrator passes a request containing facts, not verdicts:

| Field | Content |
| --- | --- |
| `repository` | `owner/name` and the local path of the checkout to review. |
| `issue` | Issue number, plus parent epic and linked issues/specs/ADRs the orchestrator found. |
| `criteria` | The acceptance criteria as an ordered list with stable ids (`AC1`, `AC2`, ...), quoted from the issue, plus any clarifications the human gave (with their source). |
| `base` | Base ref and its SHA (usually `main`). |
| `snapshot` | Snapshot identity as defined below. |
| `expected_checks` | Required commands (at least the repository minimum, e.g. `./gradlew test`, when code is affected) and issue-specific verification. |
| `environment` | Known constraints: available tools, network, sibling repositories, permissions, time budget. |
| `previous_result` | For a recheck: the prior result, its snapshot identity, and the orchestrator's list of findings it claims to have fixed. Empty on the first run. |

Missing fields are resolved from the repository and the issue when possible. If the issue, criteria, or snapshot cannot be identified, return `BLOCKED`.

The reviewer reads the issue itself; `criteria` from the orchestrator is checked against the issue text, and a mismatch (dropped, reworded, or added criteria) is a finding.

## Snapshot identity

A result is valid only for the snapshot it names. The identity is the base SHA, the head SHA, and the git tree of the working directory, written through a throwaway index so the author's index is untouched. The tree covers every tracked file, including tracked files that match ignore rules, plus untracked files that are not ignored. A git tree records each path's contents, its executable bit, and whether it is a symlink, so a mode or file-type change alters the identity even when the contents do not.

```bash
base=$(git rev-parse <base-ref>)
head=$(git rev-parse HEAD)
tmp=$(mktemp -d)
git ls-files -z | GIT_INDEX_FILE=$tmp/index git update-index --add --remove -z --stdin
GIT_INDEX_FILE=$tmp/index git add -A
tree=$(GIT_INDEX_FILE=$tmp/index git write-tree)
rm -rf "$tmp"
echo "base=$base head=$head tree=$tree"
```

- The throwaway index is built fresh from the author's list of tracked paths, not copied from the author's index. `update-index --add --remove` re-hashes every tracked path from disk and ignores ignore rules, so tracked ignored files stay in the tree and deleted ones drop out. A copied index would carry skip-worktree and assume-unchanged bits, and a stale stat cache, that let `git add -A` skip edits. `git add -A` then adds the untracked, non-ignored files.
- For a clean committed snapshot, `tree` equals `git rev-parse HEAD^{tree}`; prefer that case.
- Hashing every tracked file costs one full read of the checkout per fingerprint.
- Limitations to record in the result when a required check depends on them:
  - untracked ignored files (local configuration, build output) are excluded from the fingerprint;
  - in a repository with `core.fileMode=false`, executable-bit changes are not seen;
  - in a sparse checkout, tracked files that are absent from disk appear as deleted.
- `fingerprint-check.sh` in this directory runs this snippet, extracted from this file, against regression cases.
- Compute the identity at the **start** and again at the **end** of the review. If it differs from the request or changed during the review, the result is `BLOCKED` (stale snapshot) and no criterion may be reported as satisfied.
- Any later change to the snapshot invalidates the result. A previous `PASS` never carries over to a new snapshot.

## Procedure

1. **Gather context.** Read the issue, its comments, the parent epic, linked issues, relevant specs and ADRs, and the documents `AGENTS.md` requires for the affected area (including the matching skill under `.agents/skills/`). Read the full diff `git diff <base> <tree>`, which includes untracked files and mode changes, the surrounding implementation, and the existing tests.
2. **Build the requirement-to-evidence matrix.** One row per acceptance criterion. Add rows for implied constraints from `AGENTS.md`, ADRs, or the issue's boundaries (non-goals, "no runtime change", mirroring) and mark them as implied. Do not invent requirements. A materially ambiguous criterion becomes a finding that names the question a human must answer.
3. **Probe deliberately.** For each changed behavior, consider and record which apply:
   - empty, minimal, maximal, and boundary inputs; invalid and `null` inputs; duplicates and ordering;
   - failure paths, error messages, partial failure, retry and recovery;
   - text, encoding, and locale: non-ASCII and Unicode look-alikes (digits, signs, whitespace), default versus `C` locale, decimal separators, line endings;
   - determinism and iteration order; floating-point tolerance;
   - concurrency, cancellation, and shared state, when the change is concurrent;
   - compatibility: public API, JPMS exports, serialized or persisted formats, consumers; when the change crosses a boundary listed under "Boundary impact trace", this class is exercised by that trace;
   - resource behavior: allocation in hot paths, unbounded growth, leaks;
   - numerical claims: when the change states a measured gain, check its claim evidence report against the invalid-claim rules in `monada-neuron-evaluation-benchmarking` ("Claim Evidence Report"), and replay it when the environment allows; a claim with no report, a report with status `UNSUPPORTED` or `PENDING`, or a stated claim broader than the report's statement to ship is an unmet criterion only when an acceptance criterion requires the claim, and a non-blocking finding otherwise;
   - documentation, ADR, and skill-mirror consistency (`diff -r .agents/skills .windsurf/skills` for skill changes);
   - scope control: unrelated changes, violated non-goals, cognitive and memory ownership boundaries.
   Probe the whole input class a changed check, parser, or condition accepts or rejects, not only the examples in the issue or the tests. Record every probe class as exercised or not applicable (with a reason) in the result; an unrecorded class is not covered.
   Distinguish a **demonstrated** defect (reproduced, with steps and observed output) from a **suspected** risk (reasoned, not reproduced). Only demonstrated defects or unmet criteria may be blocking on their own; a suspected risk is blocking only when it makes a required criterion unverifiable. "Proof-graded findings" below defines what counts as demonstrated and what a finding must carry.
4. **Execute in isolation.** Run checks in an environment that cannot alter the author's checkout:
   - committed snapshot: `git worktree add --detach <scratch-dir> <head>`;
   - uncommitted snapshot: wrap the fingerprinted tree in a commit, `snap=$(git commit-tree "$tree" -p "$head" -m verification-snapshot)`, and keep it reachable with `git update-ref "refs/verification/$tree" "$snap"` so a later recheck can still diff against it. Then run `git worktree add --detach <scratch-dir> "$snap"`. This carries contents, executable bits and symlinks exactly; copying files by hand can lose them. The `refs/verification/` ref is the one allowed exception to leaving the author's refs untouched; it is not a branch or tag, and the author's working tree and index stay unchanged. Delete it (`git update-ref -d`) once the orchestrator no longer needs the snapshot for a recheck.
   - in both cases, confirm `git -C <scratch-dir> status --porcelain` is empty. Record as a limitation that ignored files (local configuration, build output) are absent from the scratch copy, and that eol or filter attributes (`.gitattributes`, `core.autocrlf`, clean/smudge filters) can make checked-out bytes differ from the author's files.
   - paths outside the checkout: when the build resolves paths relative to the checkout's parent, place the scratch worktree so those paths resolve the same way, or report the affected checks as NOT RUN. In this repository, `settings.gradle.kts` includes the adapter module only when `../monada-resonance-store` exists (ADR 0018). A check that passed only because a module dropped out of the build is not evidence.
   Run the repository minimum when code, build, or tests are affected, every `expected_checks` entry, and targeted repro or edge-case tests you write in the scratch copy. Manual checks of real behavior (running a CLI, a consumer, a scenario) count when they are recorded. For each check record command, working directory, exit status, the decisive lines of output, and any artifact path. Remove the scratch worktree afterwards (`git worktree remove`).
5. **Decide statuses** using the rules below, re-fingerprint the snapshot, and return the result.

A required check that cannot run (missing toolchain, network, sibling repository, permission, or time) is reported as `NOT RUN` with the reason. It is never inferred from the author's claim, from CI that ran on a different snapshot, or from reading the code.

## Proof-graded findings

A review is adversarial: look for the input, schedule, or consumer that breaks the change. It is also evidence-only: a finding is graded by the proof behind it, never by how plausible it sounds.

**Evidence ladder.** Every finding states the highest rung its evidence reached on this snapshot:

| Rung | Evidence | Evidence kind |
| --- | --- | --- |
| R1 | Stated: the reviewer believes it. | `[suspected]` |
| R2 | Located: a cited line or clause where it happens. | `[suspected]` |
| R3 | Walked: a written trace of the inputs or schedule through the code to the wrong outcome. | `[suspected]` |
| R4 | Ran: a recorded command on the snapshot (a test, a repro program, a check) prints the wrong outcome. | `[demonstrated]` |
| R5 | Reproduced end to end: the wrong outcome also appears through a real consumer path (a consumer fixture task, the published artifact, a host facade, a scenario run). | `[demonstrated]` |

**Contract violations.** A violated written contract (an acceptance criterion, an accepted ADR decision, a spec clause, a rule in `AGENTS.md` or `docs/design-principles.md`) is demonstrated only at R4 or above, and what R4 needs depends on what the clause constrains:

- a clause about **behavior** (what code does at run time: ordering, cancellation, a thrown exception, a value, a complexity bound) needs a run that shows the behavior, such as a failing test or a repro program whose output contradicts the clause; showing the code beside the clause is R2;
- a clause about **text or structure** (a dependency, an import, a package or module name, a mirrored file, a statement a document must or must not make) is met at R4 by a recorded command whose output shows the violating text and the clause it breaks, for example `grep -n` or `sed -n` over both files.

A violation that does not reach R4 stays `[suspected]`, or becomes an `UNMET` or `PARTIAL` criterion row when a criterion names the contract.

**What a finding carries.** The bold head keeps its three tags. The body adds:

```text
- **F1 [blocking] [demonstrated] [open]** `path:line` — observed vs expected.
  Required by: <criterion id, ADR and decision, spec clause, or rule, with the clause quoted or cited>.
  Rung: R4 — <what was run>.
  Reproduce: `<exact command or steps>` → `<observed output>`; required: `<output the requirement demands>`.
  Rerun: <clean scratch copy, command rerun, same result>.
  Smallest regression check: <the cheapest test or command that fails now and must pass after the fix>.
```

- `Required by:` is mandatory. An observation that no criterion, ADR, spec, or rule requires is not a finding: list it under `Optional improvements`, where it never blocks and is not counted.
- `Rerun:` is mandatory for a `[blocking] [demonstrated]` finding. Before tagging it blocking, rerun its reproducer from a fresh scratch worktree of the snapshot. A reproducer that does not fail again keeps the finding, tagged `[non-blocking]`, with both runs recorded in `Rerun:` (an intermittent failure is still evidence, especially on a concurrent path); it never blocks on a single unrepeated run.
- `Smallest regression check:` is required for `[demonstrated]` findings. It recommends one check, not a fix.
- A `[suspected]` finding states its rung and what would raise it to R4 (the command that was not run, or why it could not run).
- A finding names the smallest change that would satisfy the requirement it cites. It never proposes a redesign, a new abstraction, or an architecture rewrite; when only a redesign would satisfy a requirement, say so as a question for the human.

**Retractions.** A finding withdrawn before the result is returned is not listed as a finding. Retract only when the finding itself was wrong: the cited clause did not say what was assumed, the reproducer tested something other than the snapshot's behavior, or the behavior lies outside the diff and every criterion. A reproducer that does not fail again is not a reason to retract (see `Rerun:`). Record the number of retracted findings and a one-line reason each under `Residual risks and limitations`, as `Retracted findings: <n> — <reasons>`. The orchestrator never filters findings; only the reviewer retracts its own.

**Bounded cost.** One reviewer does the whole review: no fan-out to further reviewers, no second model, no repeated passes over untouched code. Trace only the surfaces the diff touches. Record under `Residual risks and limitations`, as `Review cost: <wall time>, <number of commands run>`, plus tokens or tool cost when the harness shows them.

## Boundary impact trace

A conditional part of probing, done by the same reviewer. It extends the compatibility probe class and replaces neither `./gradlew check` nor any consumer task.

This trace is on probation (`docs/experiments/pstack-adoption-evaluation-v1.md`, P4): the #204 comparison showed no outcome it changed. Apply it as written, and do not cite it as evidence that a review is stronger.

**Trigger.** The diff touches any of: Monad, Aeon, Node, or Signal contracts; an adapter; the Monada Resonance Store boundary; a host facade or routing contract; published API, module names, or the published artifact; a serialized or persisted format; or a concurrent, SIMD, or native path. Otherwise write `Boundary impact: not triggered — <reason>` and skip this section.

**Steps.**

1. List the touched surfaces from the diff. A list of callers is not an impact result: for each surface, say what the change does to it.
2. Mark each touched surface `confirmed` (a defect or risk exists, with its finding id) or `cleared` (why it holds), with the rung of that evidence.
3. State the **safety fact**: the one fact the change is safe because of (for example, "every submitted task is cancelled before the exception leaves `coordinate`"). Give its rung, or write `unproven`. An unproven safety fact on a surface that a required criterion covers makes that criterion `NOT VERIFIABLE` or `PARTIAL`.
4. Look where grep stops. These surfaces are reached by reflection, naming, bytes, or another build, so a text search of the diff misses them:
   - reflective Vector API loading and the scalar fallback (ADR 0013, ADR 0017, ADR 0032);
   - automatic module names and the absence of a module descriptor (ADR 0025, ADR 0032): every package is exported, so a "private" package is still importable;
   - canonical bytes (ADR 0039, ADR 0048) and checkpoints (ADR 0031);
   - the consumer fixtures under `consumer-fixture/` and the `monada-neuron-resonance-adapter` module (present only when `../monada-resonance-store` exists, ADR 0018).
5. For a concurrent, SIMD, or native path, check and record: determinism of results and iteration order; cancellation, interruption, and failure propagation on every exit; allocation on the hot path; memory lifetime (`Arena` and `MemorySegment` ownership, no use after close); equivalence with the reference implementation within its documented tolerance, including tails and small inputs; and the fallback when the capability is absent.
6. For each touched surface that has a consumer task, run it (`consumerSmokeTest`, `jpmsConsumerTest`, `forgeHostConsumerTest`, `embeddedHostConsumerTest`, the adapter module's tests) or report it `NOT RUN` with the reason. `embeddedHostConsumerTest` and the adapter module's tests exist only when the adapter module is included, that is when `../monada-resonance-store` resolves (ADR 0018); when it is absent, report them `NOT RUN`, never `FAIL` or `PASS`. Do not run consumer tasks for untouched surfaces.

No probability scores, no redesign proposals, and no inventory for its own sake. Report the trace in the result's `Boundary impact` section.

## Status rules

Per criterion:

- `SATISFIED` — evidence on this snapshot demonstrates it (a check result, a reproduced behavior, or a cited diff location for documentation-only criteria).
- `UNMET` — evidence shows it is not met or not implemented.
- `PARTIAL` — part of it is met; the result names the missing part.
- `NOT VERIFIABLE` — the evidence it needs could not be obtained; the result names what is missing.

A criterion that requires tests ("covers", "is tested") is `SATISFIED` only when every behavior it names has a test that would fail for a plausible wrong implementation of that behavior. Show it with a cheap mutation in the scratch copy where practical. A passing suite that leaves a named behavior unconstrained makes the criterion `PARTIAL`.

Overall:

- `PASS` — every required criterion is `SATISFIED` on the identified, unchanged snapshot; every required check ran and passed; every applicable probe class was exercised and recorded; no blocking finding is open.
- `NEEDS CHANGES` — at least one criterion is `UNMET` or `PARTIAL`, or a blocking finding is open. Every such item has a location and reproduction or a precise description of what is missing.
- `BLOCKED` — verification cannot be completed honestly: no independent subagent, unidentifiable or stale snapshot, a required check or environment unavailable, any required criterion `NOT VERIFIABLE`, or an ambiguity only a human can resolve. If demonstrated defects were also found, report them too, so the orchestrator can fix them while the blocker is resolved.

Precedence: `BLOCKED` over `NEEDS CHANGES` over `PASS`. A `BLOCKED` result still lists every demonstrated defect and every `UNMET` or `PARTIAL` criterion.

When unsure between `PASS` and anything else, it is not `PASS`.

## Result (output contract)

Return exactly this Markdown block to the orchestrator. Keep it factual and proportional to the change.

```markdown
## Independent verification result

<!-- verification v1 status=PASS|NEEDS_CHANGES|BLOCKED base=<sha|none> head=<sha|none> tree=<sha|none> identical=yes|no criteria=<n> satisfied=<n> blocking_open=<n> -->
**Status:** PASS | NEEDS CHANGES | BLOCKED
**Issue:** #<n> (parent #<epic>)
**Snapshot:** base=<sha> head=<sha> tree=<git tree sha> (identical at start and end: yes/no)
**Recheck of:** <previous snapshot head/tree, or "first run">

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | ... | SATISFIED | C1, `path/File.java:42` |

### Findings
- **F1 [blocking|non-blocking] [demonstrated|suspected] [open|resolved]** `path:line` — observed vs expected.
  Required by: <criterion, ADR decision, spec clause, or rule>.
  Rung: R1–R5 — <evidence>.
  Reproduce: `<exact command or steps>` → `<observed output>`; required: `<output the requirement demands>`.
  Rerun: <for blocking demonstrated findings, and for findings downgraded after a rerun (both runs)>.
  Smallest regression check: <for demonstrated findings>.

### Optional improvements
- <observation no requirement demands; never blocking, never counted>, or "none".

### Boundary impact
Boundary impact: not triggered — <reason>. (Or the table below.)

| Surface | Change to it | Status | Rung | Evidence |
| --- | --- | --- | --- | --- |
| `<surface>` | ... | confirmed (F1) / cleared | R4 | C2 |

Safety fact: <the one fact the change is safe because of> — R<n> | unproven.

### Probes
| Class | Exercised | Inputs or reason |
| --- | --- | --- |
| boundary/empty/invalid | yes | `...` |
| text/encoding/locale | n/a | no text input in the change |

### Checks run
- `<command>` (cwd `<dir>`) — PASS | FAIL — <what it checks, decisive output>.

### Checks not run
- `<command>` — NOT RUN — <reason>; affects <criteria ids>.

### Residual risks and limitations
- <untested scenarios, environment differences, assumptions>.
- Retracted findings: <n> — <one-line reason each>.
- Review cost: <wall time>, <number of commands run>.
```

The first non-blank line after the heading is the **verification line**: one HTML comment that summarizes the block for tools such as the publication gate of `monada-neuron-implement-issue`, which read it instead of the Markdown. Write it exactly once, in this form, with single spaces and nothing else on the line:

- `status` is `PASS`, `NEEDS_CHANGES`, or `BLOCKED`, the overall status with the space replaced by `_`;
- `base`, `head`, `tree` are the full SHAs of the end-of-review fingerprint, as on the `Snapshot` line. A value that could not be computed (unreadable checkout, unresolvable base ref, failed fingerprint) is the literal `none`, never a guess; any `none` requires `status=BLOCKED` and `identical=no`, and the `Snapshot` line names what was unavailable;
- `identical` is `yes` only when the fingerprints at the start and end of the review are equal;
- `criteria` is the number of rows in the criteria table, implied rows included; `satisfied` is the number of those rows whose status is `SATISFIED`. When the issue or its criteria could not be identified, the table is empty and both are `0` (status `BLOCKED`);
- `blocking_open` is the number of findings tagged both `[blocking]` and `[open]`.

Numbers are ASCII decimals without sign or leading zeros. The line states the same facts as the Markdown below it; a result whose line is missing, duplicated, malformed, or disagrees with the Markdown is not a valid result.

Each finding's bold head carries exactly three tags in this order: severity, evidence kind, and state. The state is `[open]` on a first run and for every finding still open on a recheck, and `[resolved]` only for a previous finding a recheck shows fixed; nothing outside the bold head changes the state.

The evidence kind follows the rung under "Proof-graded findings": `[demonstrated]` only at R4 or R5. The body lines added there (`Required by:`, `Rung:`, `Rerun:`, `Smallest regression check:`), the `Optional improvements` and `Boundary impact` sections, and the `Retracted findings` and `Review cost` lines add detail for people; they change neither the verification line nor how its counts are computed.

The `Checks run` and `Checks not run` lines use the PASS / FAIL / NOT RUN vocabulary of the `How to test / verify` heading in `monada-neuron-create-pull-request`, so the orchestrator can transfer them into the PR without reinterpretation. Findings carry ids so a recheck can report each one as resolved or still open.

## Revalidation

After the orchestrator applies fixes, or resolves a blocker such as a missing environment or an unanswered question, it invokes a **new** verification. The reviewer:

1. recomputes the snapshot identity and compares it with `previous_result`. If the identity is unchanged (a recheck after a `BLOCKED` for environment or a human answer), it re-runs every check that was NOT RUN or blocked, re-evaluates every `NOT VERIFIABLE` or ambiguity-blocked criterion with the new environment or answer, and skips step 2;
2. if the snapshot changed, re-evaluates every criterion touched by the new diff (`git diff <previous-tree> <new-tree>`, which also shows mode and file-type changes) and every criterion whose evidence came from a check, re-running those checks on the new snapshot. If the previous tree is no longer in the repository (pruned, or reviewed in another clone), it uses `git diff <previous-head> <new-tree>` instead and records that the fix diff is approximate;
3. reports each previous finding with its original id and the state tag `[resolved]` (with evidence) or `[open]`, and counts the open blocking ones in `blocking_open`;
4. expects regression coverage for each fixed defect: a test or check that fails on the old snapshot and passes on the new one, or a stated reason why none is practical (a non-blocking finding if missing);
5. probes the fix itself for new edge cases, re-running every applicable probe class against the changed code, because a fix that makes the reported input work can still be wrong for the rest of its input class.

The orchestrator's claim that something is fixed is not evidence. A recheck that cannot re-run a required check is `BLOCKED`, regardless of the previous result.

## Truthful and safe evidence

- Report only what was observed on the named snapshot; mark everything else as an assumption or limitation.
- Quote the decisive lines of output, not whole logs. Never include secrets, tokens, credentials, personal data, or private host details in the result; redact them if they appear in output.
- Commands must be copyable and reproducible by a human from the stated working directory.
- An empty finding list is fine; do not invent issues to look thorough, and do not omit known gaps to look clean.

## Handoff

The result goes to the orchestrator only. The orchestrator owns corrections, commit, push, PR creation (`monada-neuron-create-pull-request`), and any human communication. This skill never publishes, merges, or closes issues. Publication as verified requires a `PASS` for the change being published: the same base and tree, with `HEAD` either the verified head or a single commit on top of it that records exactly the verified tree (the orchestrator committing a verified uncommitted snapshot).

## Validation

`validation.md` records the scenarios (pass, defect, missing requirement, blocked environment, corrected snapshot) that exercised these rules and the observed results; `validation-fixtures.sh` rebuilds the fixtures. The proof-graded findings and boundary impact trace were exercised for issue #203 on a historical defect, its corrected snapshot, and a seeded boundary violation; `docs/experiments/evidence-review-pilot-v1.md` records those runs.
