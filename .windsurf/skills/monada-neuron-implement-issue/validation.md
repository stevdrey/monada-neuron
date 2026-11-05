# Scenario Validation of the Issue Implementation Skill

This file records how `SKILL.md` was exercised for issue #192: which scenarios ran, what the orchestrator and each independent reviewer actually did, and how the skill changed in response. Results are observed outputs, not expectations.

## Method

- **Fixtures.** `validation-fixtures.sh <empty-dir>` builds five scenario directories. Each has a clone `repo/` of a local bare `origin.git`, a fake `bin/gh`, and `issue.md` with a fake issue #8 (add `stats.sh max` next to an existing `stats.sh mean`; parent #1). The fake `gh` serves `issue view 8` from `issue.md`, answers `repo view`, `pr list` and `pr view`, records every call in `gh-calls.log`, and stores the arguments of `pr create` in `pr-create.args`. Nothing reaches GitHub. Base trees are reproducible across builds; the `defect` working tree reproduces the reviewed snapshot `b05c63ea…` exactly.
- **Orchestrator.** In `pass`, `defect` and `blocked` the main Claude Code session (Opus 5.5) acted as the orchestrator and followed `SKILL.md`, because a nested subagent cannot be relied on to start further subagents. In `ambiguous` and `no-reviewer` a fresh `general-purpose` subagent was the orchestrator. It was given only the skill path, the invocation `implement issue 8, and open a PR when it is done`, and the environment, and was never told the expected outcome.
- **Reviewer.** Every verification was a fresh `general-purpose` subagent told only to follow `monada-neuron-independent-verification/SKILL.md`, with a request in that skill's format. No reviewer was told the expected status.
- **Gate.** `publication-gate.sh` was run on every saved result before any commit or push.
- **Environment.** Fedora Linux, GNU bash 5.3.9, GNU Awk 5.3.2, git, jq, shellcheck; no network.

| Fixture | Setup | Scenario |
| --- | --- | --- |
| `pass` | clear issue (AC1–AC5) | pass, stale result after a post-verification edit, publish |
| `defect` | clear issue; an uncommitted first attempt seeds the maximum with 0 and tests only positive numbers | failed requirement, correction, revalidation |
| `ambiguous` | AC5 (print the argument as typed) contradicts AC6 (two decimals) | ambiguity |
| `blocked` | clear issue plus AC6: `tests/golden.sh` against an absent sibling `../stats-golden` | unavailable test environment |
| `no-reviewer` | clear issue; the orchestrator may not start subagents | unavailable reviewer |

## Results

### pass: PASS, stale result refused, publish

| Step | Observed |
| --- | --- |
| IMPLEMENT, SELF-CHECK | `max` seeded from the first value, 7 new cases; `tests/run.sh` 12/12 ok. |
| VERIFY | **PASS** on tree `118af19f…` (uncommitted). 11/11 mutants killed, 14 probe classes; non-blocking: `max -0 0` prints `-0.00`, the runner does not assert empty stderr. |
| Stale check | After appending a line to `README.md`: `DENY: checkout tree a68eec76… differs from verified tree 118af19f… (stale result)`. Reverting the edit restored `ALLOW`. |
| PUBLISH GATE | Publish mode before committing: `DENY: HEAD^{tree} differs from the verified tree; commit the verified snapshot first`. After committing exactly the verified tree: `ALLOW: HEAD 17e31719… carries the verified tree 118af19f…`. |
| PUBLISH | `git push -u origin 8-add-max`; the origin branch equals the gated HEAD. Fake `gh pr create` received title `[Issue-8] Add a stats.sh max command` and a body with the six headings of `monada-neuron-create-pull-request` in order, the checks with PASS status, the AC traceability list, `Closes #8`, `Related to #1`, and the open non-blocking findings. `gh pr view` re-fetched it. |

### defect: four rounds of correction and revalidation

| Round | Tree | Reviewer status | Decisive evidence | Orchestrator action |
| --- | --- | --- | --- | --- |
| 1 | `b05c63ea` | NEEDS CHANGES | F1: `max -3 -1 -2` → `0.00`; F2: no negative case, AC4 `PARTIAL`. Gate: `DENY: status is 'NEEDS CHANGES'`. | Seeded from the first value; added all-negative, single-negative and `-1 2.5` cases. The new cases fail on the round-1 `stats.sh`. |
| 2 | `7406e95f` | NEEDS CHANGES | F1, F2 resolved by id with regression proof. New F4: an `int()`-comparison mutant passes all 12 cases. | Added `max 2.5 2.7` and `max -1.5 -1.2`, which kill that mutant. |
| 3 | `46774c61` | NEEDS CHANGES | F4 resolved. New F7, "the remainder of F4's defect class": mutants comparing at one decimal or none still pass. | Swept the class: third- and fourth-decimal cases, positive and negative. Mutants comparing at 0, 1 or 3 decimals and an output-truncating mutant are all killed; comparing at 2 decimals is equivalent under two-decimal output. |
| 4 | `d35a025a` | NEEDS CHANGES | F7 resolved: the 0-, 1- and 3-decimal and truncation mutants each fail 2–3 cases. New F8 refutes the orchestrator's equivalence claim: the 2-decimal-comparison mutant passes all 17 cases but prints `-0.00` for `max -0.001 0.001`. New F9 (non-blocking, needs a human): `max -0 0` → `-0.00` but `max 0 -0` → `0.00`, while `mean` prints `0.00`. | **ESCALATE.** Open blocking findings plus unmet or partial criteria were 4, 2, 2, 2: no decrease for two consecutive verifications, and the orchestrator had disputed a finding. Both are stop conditions. |

The escalation put one decision to the human: must `max` print `0.00` whenever the largest value is zero, as `mean` does? That answer decides whether F9 is blocking and what the F8 test should expect. Nothing was committed, pushed or opened, and the gate refused the round-4 result (`DENY: status is 'NEEDS CHANGES'`). The scenario was not continued past the escalation, so this fixture never reached `PASS`.

Rounds 2 to 4 show why a previous review never approves a fix. Each fix passed the author's own run and the reviewer's checks, and each recheck still found a new gap in the same input class. Round 4 also shows that the orchestrator cannot overrule the reviewer: its equivalence claim was itself checked and refuted. Round 3 caused Change 2 below.

### ambiguous: CLARIFY stops dependent work

The orchestrator subagent went `GATHER → CLARIFY` and stopped. It identified that AC5 and AC6 contradict each other for `max 2 10.0` (`10.0` versus `10.00`), asked one question with three options and their consequences for `Closes` versus `Related to`, and noted that AC2 and AC3 were independent but left the checkout clean because a partial change could not be verified or published in the run. Observed side effects: `git status` empty, `HEAD` unchanged, origin has only `main`, no `pr create` call.

### blocked: required environment unavailable

The reviewer returned **BLOCKED**: AC1–AC5 `SATISFIED` with mutation evidence, AC6 `NOT VERIFIABLE` with `tests/golden.sh` NOT RUN (`../stats-golden` absent, exit 3). It checked the golden script's mechanics against synthetic data and explicitly did not count that as evidence. The gate returned `DENY: status is 'BLOCKED', not PASS`. The orchestrator could not provide `../stats-golden`, so it went `RESOLVE → ESCALATE`. Nothing was committed, pushed, or opened.

### no-reviewer: unavailable reviewer

The orchestrator subagent went `GATHER → IMPLEMENT → SELF-CHECK → VERIFY → RESOLVE → ESCALATE`. It implemented `max` and ran `tests/run.sh` (13/13), labelled that as its own evidence only, did not self-review in place of the independent check, did not run the gate or publish although the invocation asked for a PR, and asked whether an independent reviewer can be provided or whether a draft PR marked `NOT PASSED` is explicitly wanted. Observed side effects: two uncommitted files, origin unchanged, no `pr create` call.

## Changes to the skill caused by the runs

1. **Previous result must exist before a recheck starts.** In defect round 2 the orchestrator named a previous-result file that it wrote only after starting the reviewer. The reviewer reported the missing file and fell back to a shorter summary. `VERIFY` step 5 now requires `previous_result` inline or in a file that already exists.
2. **Fix the class, not the example.** Defect rounds 2 and 3 each fixed exactly the reproduction in the finding, and the next round found a neighbouring input of the same class. `FIX` now requires fixing the defect class a finding names.
3. **Escaped pipes in result tables.** The reviewers write `\|` inside table cells. `publication-gate.sh` now removes escaped pipes before splitting columns, and the self-test has a row with `\|` in the criterion and evidence cells.
4. **Machine-readable verification line (follow-up to #192).** Scraping the Markdown kept needing fixes for escaped pipes, header and separator rows, extra text after values, unfilled template text, and the position of the `resolved` marker. The verifier's output contract now has one HTML-comment line after the result heading with the status, snapshot, criteria counts and open blocking findings, and a fixed `[open|resolved]` state tag in each finding's head. `publication-gate.sh` reads only that line. The results saved by the runs above predate it, so the gate would now refuse them for a missing verification line; the runs are not repeated.

## Acceptance-criteria coverage (issue #192)

| Requirement | Shown by |
| --- | --- |
| Invocation by issue number resolves the issue and linked context | `Invocation` and `GATHER` in `SKILL.md`; both orchestrator subagents resolved the repository and issue #8 through `gh`, checked for existing PRs and branches, and tried the parent epic and timeline. |
| Material unknowns trigger clarification and block dependent work | `ambiguous`: stopped in `CLARIFY` with a concrete question; nothing implemented or published. |
| Explicit implement → validate → fix → revalidate transitions, stale-result invalidation, stop condition | State machine table; `defect` rounds 1–4 (findings resolved by id, regression proof each round) ending in `ESCALATE` by the no-progress and disputed-finding stop conditions; `pass` stale check refused by the gate; `ESCALATE` also in `blocked` and `no-reviewer`. |
| No commit, push or PR before independent verification of the final change; permissions respected | Gate refused NEEDS CHANGES, BLOCKED, stale and uncommitted states; `ambiguous`, `blocked`, `no-reviewer` and the unfinished `defect` rounds published nothing; `pass` committed exactly the verified tree and pushed only after `ALLOW`. |
| PR evidence traces each requirement, states commands, outcomes and limitations | `pass` fake PR body: six headings, PASS/FAIL/NOT RUN lines, AC traceability list, verified snapshot, open findings. |
| Mirrored skill; representative scenarios | `diff -r .agents/skills .windsurf/skills`; the five scenarios above; `publication-gate-check.sh`. |
| Uses the task and PR skills without duplicating them | `SKILL.md` delegates required reading to `monada-neuron-agent-task-workflow`, the request, statuses and revalidation to `monada-neuron-independent-verification`, and the title, headings and checklist to `monada-neuron-create-pull-request`. |

## Limitations

- No scenario went from `NEEDS CHANGES` through corrections to a final `PASS`: `defect` stopped at the escalation, by design of the stop rules, and `pass` passed on its first round. The gate's handling of a corrected snapshot that passes is covered by `publication-gate-check.sh`, not by a reviewer run.
- The fixtures are small shell programs, not Monada Neuron Java changes. They exercise the orchestration rules and the gate, not Gradle builds or JMH.
- In three scenarios the orchestrator was the same main session that wrote the skill and the fixtures, and it knew the intended outcomes. Only the `ambiguous` and `no-reviewer` orchestrators and every reviewer were blind to them.
- The `no-reviewer` restriction was simulated by instruction; the subagent was not technically deprived of an agent tool.
- The fake `gh` does not serve the parent epic #1 or the timeline API, so the epic and cross-reference steps were attempted but returned errors.
- Saved results used by the gate and as `previous_result` were abridged copies: the Status, Snapshot and criteria Id and Status columns are verbatim, the evidence text is shortened. The `defect` round-1 recheck request named an abridged file.
- The reviewers in these runs were given only the issue's `AC` ids, not the orchestrator's `IM` rows, so they named the implied scope row themselves (`AC-impl`, `implied`).
- In the `blocked` run, AC6 sat after the issue's `## Verification` heading; the reviewer still treated it as required. The fixture now places it with the other criteria; the repository trees are unchanged.
- Push and PR creation went to a local bare repository and a fake `gh`. A real GitHub PR was not created by these runs.
