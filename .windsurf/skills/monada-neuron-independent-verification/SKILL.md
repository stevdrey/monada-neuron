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
- Do **not** modify the reviewed snapshot: no edits, commits, pushes, stashes, branch switches, PRs, issue comments, or merges in the author's checkout. Repro tests and experiments go in an isolated copy and are reported, not applied.
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

A result is valid only for the snapshot it names. The identity is the base SHA, the head SHA, and the git tree of the working directory: tracked and untracked, non-ignored files, written through a throwaway index, so the author's index is untouched. A git tree records each path's contents, its executable bit, and whether it is a symlink, so a mode or file-type change alters the identity even when the contents do not.

```bash
base=$(git rev-parse <base-ref>)
head=$(git rev-parse HEAD)
tmp=$(mktemp -d)
cp "$(git rev-parse --git-path index)" "$tmp/index" 2>/dev/null || :  # stat cache only; optional
GIT_INDEX_FILE=$tmp/index git add -A
tree=$(GIT_INDEX_FILE=$tmp/index git write-tree)
rm -rf "$tmp"
dirty=$(git status --porcelain | wc -l)
echo "base=$base head=$head tree=$tree dirty_entries=$dirty"
```

- For a clean committed snapshot, `tree` equals `git rev-parse HEAD^{tree}`; prefer that case.
- The fingerprint excludes ignored files, and in a repository with `core.fileMode=false` it does not see executable-bit changes. When a required check depends on ignored files (local configuration, build output) or on permissions in such a repository, record that as a limitation in the result.
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
   - compatibility: public API, JPMS exports, serialized or persisted formats, consumers;
   - resource behavior: allocation in hot paths, unbounded growth, leaks;
   - documentation, ADR, and skill-mirror consistency (`diff -r .agents/skills .windsurf/skills` for skill changes);
   - scope control: unrelated changes, violated non-goals, cognitive and memory ownership boundaries.
   Probe the whole input class a changed check, parser, or condition accepts or rejects, not only the examples in the issue or the tests. Record every probe class as exercised or not applicable (with a reason) in the result; an unrecorded class is not covered.
   Distinguish a **demonstrated** defect (reproduced, with steps and observed output) from a **suspected** risk (reasoned, not reproduced). Only demonstrated defects or unmet criteria may be blocking on their own; a suspected risk is blocking only when it makes a required criterion unverifiable.
4. **Execute in isolation.** Run checks in an environment that cannot alter the author's checkout:
   - committed snapshot: `git worktree add --detach <scratch-dir> <head>`;
   - uncommitted snapshot: wrap the fingerprinted tree in an unreferenced commit, `snap=$(git commit-tree "$tree" -p "$head" -m verification-snapshot)`, then `git worktree add --detach <scratch-dir> "$snap"`. Confirm that `git -C <scratch-dir> rev-parse HEAD^{tree}` equals `tree`. This carries contents, executable bits and symlinks exactly; copying files by hand can lose them. The commit creates objects but no ref or branch, and the author's working tree, index and refs are unchanged.
   Run the repository minimum when code, build, or tests are affected, every `expected_checks` entry, and targeted repro or edge-case tests you write in the scratch copy. Manual checks of real behavior (running a CLI, a consumer, a scenario) count when they are recorded. For each check record command, working directory, exit status, the decisive lines of output, and any artifact path. Remove the scratch worktree afterwards (`git worktree remove`).
5. **Decide statuses** using the rules below, re-fingerprint the snapshot, and return the result.

A required check that cannot run (missing toolchain, network, sibling repository, permission, or time) is reported as `NOT RUN` with the reason. It is never inferred from the author's claim, from CI that ran on a different snapshot, or from reading the code.

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

When unsure between `PASS` and anything else, it is not `PASS`.

## Result (output contract)

Return exactly this Markdown block to the orchestrator. Keep it factual and proportional to the change.

```markdown
## Independent verification result

**Status:** PASS | NEEDS CHANGES | BLOCKED
**Issue:** #<n> (parent #<epic>)
**Snapshot:** base=<sha> head=<sha> tree=<git tree sha> dirty_entries=<n> (identical at start and end: yes/no)
**Recheck of:** <previous snapshot head/tree, or "first run">

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | ... | SATISFIED | C1, `path/File.java:42` |

### Findings
- **F1 [blocking|non-blocking] [demonstrated|suspected]** `path:line` — observed vs expected.
  Reproduce: `<exact command or steps>` → `<observed output>`.

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
```

The `Checks run` and `Checks not run` lines use the PASS / FAIL / NOT RUN vocabulary of the `How to test / verify` heading in `monada-neuron-create-pull-request`, so the orchestrator can transfer them into the PR without reinterpretation. Findings carry ids so a recheck can report each one as resolved or still open.

## Revalidation

After the orchestrator applies fixes, it invokes a **new** verification on the new snapshot. The reviewer:

1. recomputes the snapshot identity and confirms it differs from `previous_result`;
2. re-evaluates every criterion touched by the new diff (`git diff <previous-tree> <new-tree>`, which also shows mode and file-type changes) and every criterion whose evidence came from a check, re-running those checks on the new snapshot;
3. reports each previous finding as `resolved` (with evidence) or `open`, using its original id;
4. expects regression coverage for each fixed defect: a test or check that fails on the old snapshot and passes on the new one, or a stated reason why none is practical (a non-blocking finding if missing);
5. probes the fix itself for new edge cases, re-running every applicable probe class against the changed code, because a fix that makes the reported input work can still be wrong for the rest of its input class.

The orchestrator's claim that something is fixed is not evidence. A recheck that cannot re-run a required check is `BLOCKED`, regardless of the previous result.

## Truthful and safe evidence

- Report only what was observed on the named snapshot; mark everything else as an assumption or limitation.
- Quote the decisive lines of output, not whole logs. Never include secrets, tokens, credentials, personal data, or private host details in the result; redact them if they appear in output.
- Commands must be copyable and reproducible by a human from the stated working directory.
- An empty finding list is fine; do not invent issues to look thorough, and do not omit known gaps to look clean.

## Handoff

The result goes to the orchestrator only. The orchestrator owns corrections, commit, push, PR creation (`monada-neuron-create-pull-request`), and any human communication. This skill never publishes, merges, or closes issues. Publication as verified requires a `PASS` whose snapshot equals the snapshot being published.

## Validation

`validation.md` records the scenarios (pass, defect, missing requirement, blocked environment, corrected snapshot) that exercised these rules and the observed results; `validation-fixtures.sh` rebuilds the fixtures.
