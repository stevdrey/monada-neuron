# Scenario Validation of the Independent Verification Skill

This file records how `SKILL.md` was exercised for issue #191: which scenarios ran, what each independent reviewer actually returned, and how the skill changed in response. Results are observed outputs, not expectations.

## Method

- **Fixtures.** `validation-fixtures.sh <empty-dir>` builds five throwaway git repositories that share one fake issue (`ISSUE.md`: a `stats.sh mean` command with acceptance criteria AC1–AC4, plus AC5 in the blocked fixture) and a shell test runner (`tests/run.sh`). Each fixture has a base commit `main` and a snapshot on `topic`. The script in this directory produces the final (round 4) fixtures. Commit SHAs change from build to build because of timestamps, but the snapshot trees are reproducible.
- **Reviewer.** Each run was a fresh `general-purpose` subagent in Claude Code (Opus 5.5). It was told only to follow this skill and was given a verification request (repository path, issue, criteria ids, base, snapshot, expected checks, environment, previous result). It was never told the expected outcome.
- **Environment.** Fedora Linux, GNU bash 5.3.9, GNU Awk 5.3.2, shellcheck, default locale `en_US.UTF-8`, no network required.
- **Safety check.** Before and after each round, the fixtures' `HEAD`, `git status --porcelain` and `git worktree list` were compared. No reviewer modified a reviewed checkout, and every reviewer removed its scratch worktree.

| Fixture | Snapshot content | Intended outcome |
| --- | --- | --- |
| `pass` | Meets AC1–AC4 | `PASS` |
| `defect` | Validation regex `^[0-9]+$` rejects negatives and decimals; tests use only positive integers; commit says "all tests pass" | `NEEDS CHANGES` with reproduction |
| `missing` | AC3 (reject non-numeric input) not implemented and not tested | `NEEDS CHANGES`, AC3 `UNMET` |
| `blocked` | Like `pass`, plus AC5 requiring `tests/golden.sh` against an absent sibling repository `../stats-golden` | `BLOCKED`, no fabricated pass |
| `corrected` | `defect` plus a fix commit and regression tests; rechecked against the `defect` result | Prior result stale; new run on the new snapshot |

## Results

### Round 1: initial skill

| Scenario | Observed status | Key evidence | Assessment |
| --- | --- | --- | --- |
| missing | NEEDS CHANGES | AC3 `UNMET`: `./stats.sh mean 1 x` → `0.50`, exit 0. AC4 `PARTIAL`: no AC3 case. | As intended. |
| defect | NEEDS CHANGES | AC1 `PARTIAL`: `./stats.sh mean -1 2.5` → `error: not a number: -1`, exit 2. A test added in scratch reproduces the failure. The author's "all tests pass" was treated as a hypothesis. | As intended. |
| pass | NEEDS CHANGES | Found a real defect the fixture author had missed: under `en_US.UTF-8`, bash `[0-9]` matches non-ASCII digits, so `./stats.sh mean 10 ²` → `5.00`, exit 0. Reproduced independently. | Correct verdict. The fixture was wrong, not the reviewer. |
| blocked | BLOCKED | AC5 `NOT VERIFIABLE`: `tests/golden.sh` exits 3 (`../stats-golden not found`), reported as NOT RUN. It also flagged that the reviewed commit itself adds AC5 to `ISSUE.md`, a question about where the criterion came from that needs a human. | As intended. It did not probe non-ASCII digits, so it missed the same locale defect. |
| corrected | **PASS** | F1/F2 resolved, with regression proof: the new tests fail on the old snapshot. | **False PASS.** The snapshot still had the `[0-9]` locale defect (`mean 10 ²` → `5.00`). The reviewer never probed it. |

**Change 1.** The probe list gained a text/encoding/locale class. The reviewer must probe the whole input class of a changed check, not only the examples. The result gained a `Probes` table that records each class as exercised or n/a, and `PASS` now requires it. Revalidation must re-run every probe class against the changed code.

### Round 2: fixtures with an explicit ASCII digit class; refined skill

| Scenario | Observed status | Key evidence | Assessment |
| --- | --- | --- | --- |
| corrected (round-1 snapshot, rerun) | NEEDS CHANGES | New blocking F6: `LC_ALL=en_US.UTF-8 ./stats.sh mean 1 ١` → `0.50`, exit 0. It noted that the earlier round had missed this. | The refined skill turns the false PASS into the correct verdict. |
| pass (v2, before refinement) | PASS | Probed non-ASCII digits and `de_DE`. | Consistent. |
| corrected (v2, before and after refinement) | PASS, then NEEDS CHANGES | After refinement: `POSIXLY_CORRECT=1 LC_ALL=de_DE.UTF-8 ./stats.sh mean -1 2.5` → `0,50`, exit 0, because awk parses numbers by locale. Reproduced independently. | Real, environment-dependent defect. Fixtures then pinned awk to `LC_ALL=C`. |

### Round 3: final probing rules, all scenarios rerun

| Scenario | Observed status | Key evidence |
| --- | --- | --- |
| pass | PASS | 14 probe classes, including Unicode digits, three locales and 50 000 arguments. Non-blocking: rounding mode, `-0.00`, accepted numeric forms. One non-blocking note: rounding edges untested. |
| defect | NEEDS CHANGES | AC1 `UNMET` (`mean -1 2.5` rejected), AC3 `PARTIAL` (`mean ١ ٢` → `0.00`, exit 0), AC4 `PARTIAL`. |
| missing | NEEDS CHANGES | AC3 `UNMET` (`mean 1 abc` → `0.50`, exit 0), AC4 `PARTIAL`. |
| blocked | BLOCKED | AC1–AC4 `SATISFIED`, AC5 `NOT VERIFIABLE` (golden check NOT RUN). It flagged again that AC5 was added by the reviewed commit. |
| corrected | NEEDS CHANGES | New blocking finding: no test exercises rounding. A truncating mutant (`mean 0 1 1` → `0.66`) still passes the whole suite, so AC4 is `PARTIAL`. |

The pass and corrected reviewers saw the same untested-rounding gap in equivalent tests but rated it differently (non-blocking versus blocking).

**Change 2.** A criterion that requires tests is `SATISFIED` only when every behavior it names has a test that fails for a plausible wrong implementation, shown by a cheap mutation where practical. The fixtures gained rounding cases and a non-ASCII-digit regression case.

### Round 4: final skill

| Scenario | Observed status | Key evidence |
| --- | --- | --- |
| pass | **PASS** | 6/6 tests. Mutation run: 12 of 13 mutants killed. The survivor, dropping awk's `LC_ALL=C`, is not a behavior AC1–AC4 name, so it is reported as a non-blocking finding. |
| corrected | **PASS** | Recheck of the round-3 `defect` result. Prior blocking findings F1–F3 were reported as resolved: the new tests fail on the pre-fix commit and pass on the snapshot. Surviving mutants (locale-dependent test, substring stderr match, last-argument-only validation) were reported as non-blocking. |

The round-3 `defect`, `missing` and `blocked` runs were not repeated after Change 2. That change only adds a condition to `SATISFIED` for test-coverage criteria, so it cannot turn their `NEEDS CHANGES` or `BLOCKED` into `PASS`. This is reasoning, not an observed rerun.

## Acceptance-criteria coverage (issue #191)

| Requirement | Shown by |
| --- | --- |
| Independent subagent, inputs, scope, status, handoff | Every run used a separate subagent with only the skill and the request. All returned the defined result block and modified nothing. |
| Evidence-backed status per criterion, tied to the snapshot | Every result named base, head and tree, confirmed the snapshot was identical at start and end, and cited checks or locations for each criterion. |
| Deliberate edge-case probing; reproducible findings | Locale, Unicode, rounding, precision and mutation probes. Every blocking finding has a copyable reproduction, and the orchestrator re-ran the decisive ones independently. |
| Real checks; unavailable required checks give BLOCKED | `tests/run.sh` was run in isolated worktrees every time. The absent golden repository gave `BLOCKED` with the check NOT RUN in both round 1 and round 3. |
| Changed implementation invalidates certification; independent revalidation | Corrected-snapshot runs recomputed identity, re-ran checks, reported prior findings by id, and demanded and verified regression coverage. Round 2 overturned a previous PASS. |
| Truthful, safe, limitations stated | Every result lists checks not run and residual risks (awk implementation, locales, unspecified rounding). No secrets appear in any output. |

## Limitations

- The fixtures are small shell programs, not Monada Neuron Java changes. They exercise the decision rules, not Gradle or JMH workflows.
- Reviewer judgement varies between runs: two runs on equivalent code rated one gap differently (round 3). The skill now narrows this for test-coverage criteria, but severity judgement is not deterministic.
- A reviewer that could not spawn subagents was not simulated. The BLOCKED rule for that case is specified but untested.
- Integration with the orchestrator skill (#192) has not been exercised. The request and result contract here is the interface that skill should consume.
- The scenario runs above used the earlier fingerprint (a `sha256` over `git diff --binary <base>` plus untracked file contents). Every reviewed snapshot was a clean commit, so identity rested on the commit SHA and the change does not affect those results. Review of the PR showed that the earlier fingerprint ignored file modes, so it was replaced by a git tree written through a throwaway index. A later review found that seeding that index from the author's index carried skip-worktree bits, which hid edits to those files, so the index now starts empty. Checked by hand: an edit to a skip-worktree file changes the tree; an untracked script at 0644 and at 0755, and a symlink versus a copy with the same content, give different trees. A clean checkout gives `HEAD^{tree}`. A worktree created from the snapshot commit keeps the executable bit. The snippet also works in a linked worktree and in a repository with no index file, and the author's index is unchanged.
