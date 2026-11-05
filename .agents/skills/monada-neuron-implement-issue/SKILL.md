---
name: monada-neuron-implement-issue
description: Use when asked to implement a Monada Neuron GitHub Issue by number end to end. Resolves the issue and its linked context, clarifies material unknowns with the human, implements, loops through independent subagent verification and corrections until a current PASS, and only then commits, pushes, and opens an evidence-backed PR (never merges).
---

# Implement an Issue with Independent Verification

## Purpose

Drive one GitHub Issue from its number to a pull request whose claims are backed by an independent verification of the exact published change. This skill is the orchestration half of epic #190. It owns context gathering, clarification, implementation, corrections, and publication. It delegates:

- task shape and required reading to `monada-neuron-agent-task-workflow` ("Before Work", "Task Shape");
- verification to `monada-neuron-independent-verification`, run as a separate subagent: its request contract, snapshot identity, status rules, result block, and revalidation rules apply unchanged;
- the PR title, headings, and publish checklist to `monada-neuron-create-pull-request`.

Do not restate or override their rules here. `AGENTS.md` and accepted ADRs take precedence over this skill, and platform, repository, and user instructions (permissions, "commit only when asked") take precedence over every step below.

## Invocation

```text
implement issue <N>            # issue in the current repository
implement issue <owner/repo>#<N>
implement issue <issue URL>
```

The invocation may add constraints ("no PR", "draft PR", "stop before commit"); record them and honor them.

1. Resolve the repository from the argument, else from `gh repo view --json nameWithOwner` in the current checkout. Resolve the issue with `gh issue view <N> --repo <owner/repo> --json number,title,state,body,comments,labels,url`.
2. If the issue does not exist, is a pull request, is closed, or the repository is ambiguous, stop and ask the human. Never guess a number or substitute a similar issue.
3. Look for existing work: `gh pr list --repo <owner/repo> --state all --search "<N>"` and branches containing the number. If a branch or open PR already exists, continue it rather than starting a parallel one, and ask before rewriting someone else's commits.

## State machine

Work moves only along these transitions. Keep the current state and the latest verification result in the progress notes or task list.

| From | Event | To |
| --- | --- | --- |
| `GATHER` | context read, checklist built | `CLARIFY` if a material unknown exists, else `IMPLEMENT` |
| `CLARIFY` | human answered | `GATHER` (fold the answer into the checklist) |
| `CLARIFY` | no answer | stays; only work independent of the question may proceed |
| `IMPLEMENT` | change ready | `SELF-CHECK` |
| `SELF-CHECK` | own checks run | `VERIFY` (also when a check failed and the failure is understood as out of scope, so the reviewer can judge it) |
| `VERIFY` | `PASS` on the current snapshot | `PUBLISH GATE` |
| `VERIFY` | `NEEDS CHANGES` | `FIX` |
| `VERIFY` | `BLOCKED` | `RESOLVE` |
| `FIX` | fixes and regression coverage done | `SELF-CHECK` |
| `RESOLVE` | environment provided or human answered | `VERIFY` (same-snapshot recheck allowed) |
| `RESOLVE` | cannot be resolved | `ESCALATE` |
| `PUBLISH GATE` | gate allows and publishing is authorized | `PUBLISH` |
| `PUBLISH GATE` | gate denies | `VERIFY` on the current snapshot |
| `PUBLISH GATE` | not authorized | `REPORT` (verified, unpublished) |
| `PUBLISH` | PR created and re-fetched | `REPORT` |
| any | a stop condition below | `ESCALATE` |
| `ESCALATE` | human decision | the state that decision unblocks |

`REPORT` and `ESCALATE` are the only end states. There is no transition from `FIX`, `RESOLVE`, or `BLOCKED` to `PUBLISH`.

## GATHER: context and traceable checklist

1. Read the issue body and every comment. Follow the parent epic (`Parent: #`), issues and PRs it references or that reference it (`gh issue view <n>`, and `gh api repos/<owner>/<repo>/issues/<N>/timeline` for cross-references), sibling work items listed in the epic, and the specs and ADRs cited. Read a linked item only as far as it constrains this issue.
2. Do the required reading in `monada-neuron-agent-task-workflow` "Before Work" for the affected area, including the matching implementation skill, on an up-to-date base (`git fetch` and inspect `origin/<base>`).
3. Build the checklist, one row per requirement, with stable ids:
   - `AC1..ACn`: the issue's acceptance criteria, quoted in order;
   - `IM1..IMn`: implied constraints from the issue's scope, non-goals, verification section, dependencies, `AGENTS.md`, or ADRs (for example skill mirroring, "no runtime change", required ADR), each with its source;
   - `CL1..CLn`: clarifications the human gave, with where they said it.

   Each row tracks: requirement, source, planned change, evidence, and where the evidence lands in the PR. These ids are passed unchanged as the verifier's `criteria`. Do not add requirements the sources do not support.

## CLARIFY: material unknowns

An unknown is material when a reasonable reading of the sources leads to a different implementation, scope, interface, or verdict: conflicting criteria, an undefined term or threshold, a dependency contract that is not agreed, a missing environment, or a scope decision the issue leaves open.

- Ask the human one concrete question per unknown, with the options and their consequences, then wait. Do not start work that depends on the answer; independent rows may proceed.
- Record the answer as a `CL` row with its source. An answer that changes or drops an acceptance criterion is a scope change: the PR must then say `Related to #N` instead of `Closes #N` unless the issue itself is updated.
- Choices with a conventional default that do not change the verdict are not material: take the default and note it.
- Never invent a requirement, a threshold, or a result to avoid asking.

## IMPLEMENT and SELF-CHECK

Implement the smallest coherent change that satisfies the checklist, following the implementation skills for the area. Then run the repository minimum when code, build, or tests are affected (`./gradlew test`) and the issue's own verification commands, and record the observed outcomes. Self-checks are the author's evidence; they never replace the independent result.

## VERIFY: independent subagent

1. Start a **new** subagent for every verification (never reuse the implementing context) and instruct it to follow `.agents/skills/monada-neuron-independent-verification/SKILL.md`.
2. Pass the request fields that skill defines: `repository`, `issue` (with epic and linked items found in `GATHER`), `criteria` (all checklist rows, ids unchanged, quoted), `base` (ref and SHA), `snapshot` (computed with that skill's fingerprint snippet, at hand-off time), `expected_checks`, `environment`, and `previous_result` (empty on the first run; on a recheck, the full previous result and the finding ids claimed fixed with what changed).
3. Pass facts only. Do not tell the reviewer the expected status, do not summarize the change as correct, and do not pass self-check outcomes as evidence (they may be listed as claims to check).
4. Do not edit the checkout while the reviewer runs. An edit during review makes the result `BLOCKED` (stale) by the verifier's own rules.
5. Save the returned result block verbatim, verification line included (outside the repository or in an ignored path, for example the session scratch directory) for the publication gate and for the next recheck. On a recheck, pass `previous_result` inline or as a file that already exists when the subagent starts; a reviewer that cannot read it can only recheck what it can see.

If no subagent capability exists, or the subagent cannot read the checkout or run the required checks, the state is `RESOLVE` with cause "no independent reviewer" or "verification environment unavailable". A self-review in this context is never reported as independent verification.

## FIX and revalidation

- Fix every blocking finding and every `UNMET` or `PARTIAL` criterion. Fix the defect class a finding names, not only its reproduction: a fix or test that makes the reported input pass while a neighbouring input of the same class still fails comes back as a new finding in the next round. Add regression coverage that fails on the previous snapshot and passes on the new one, or state why none is practical.
- Non-blocking findings: fix them or record them with a reason for the PR's `Notes for reviewers` or `Risk & rollout`.
- If you believe a finding is wrong, you cannot overrule the reviewer. Either change the code so the question disappears, or escalate it to the human with the evidence on both sides. A human decision becomes a `CL` row passed in the next request.
- Then `SELF-CHECK` and `VERIFY` again with `previous_result`. The new reviewer checks the new snapshot; an earlier review never approves a fix.

## Stale results

A result is current only while the fingerprint of the checkout equals the result's `tree`, the base ref still resolves to the result's `base`, and `HEAD` is the result's `head` or a single commit on top of it whose tree is that `tree`. Any edit, rebase, merge of the base, amend, or base movement makes it stale. A stale `PASS` is not a `PASS`: verify again. The publication gate below checks this mechanically.

## Stop and escalation conditions

Go to `ESCALATE`, report to the human with the latest result block, the open findings, and what was tried, and wait for a decision when:

- the same finding is reported open after three `FIX` rounds, counting a finding the reviewer describes as the remainder of an earlier finding's defect class as the same finding even under a new id;
- the number of open blocking findings plus unmet criteria has not decreased for two consecutive verifications;
- a `BLOCKED` cause (missing environment, permission, sibling repository, unanswered question) cannot be resolved by the agent;
- the orchestrator disputes a finding;
- a fix would require violating a non-goal, an ADR, or an architecture boundary;
- the work needs an action the agent is not permitted to take.

Escalation is a real stop, not a pause before publishing anyway.

## PUBLISH GATE

1. Confirm authorization. Commit, push, and PR creation require the user's authorization: an invocation that asks for a PR, or an explicit yes. Otherwise stop at `REPORT` with the verified, unpublished change.
2. Run `git fetch origin` first and pass the remote-tracking ref (`origin/<base>`, the ref `GATHER` verified against) as `<base-ref>`, so the gate compares the verified base with the base as it is now. A local `<base>` branch is not updated by a fetch. Before committing a verified uncommitted snapshot, run the gate in `--before-commit` mode: it allows only when the checkout is still exactly the verified snapshot.

   ```bash
   .agents/skills/monada-neuron-implement-issue/publication-gate.sh --before-commit <result-file> origin/<base>
   ```

3. Commit exactly that tree (stage everything the fingerprint included; nothing else), then run the gate in publish mode before pushing:

   ```bash
   .agents/skills/monada-neuron-implement-issue/publication-gate.sh <result-file> origin/<base>
   ```

   The gate reads only the result's verification line (the HTML comment the verifier's output contract places right after the result heading), never the Markdown, and refuses a result with no such line, more than one, or one that does not match the contract exactly. Publish mode allows only when that line says `status=PASS`, `identical=yes`, at least one criterion with `satisfied` equal to `criteria`, and `blocking_open=0` (required checks reported NOT RUN stay with the verifier's PASS rule, because the result does not mark which checks are required), the base ref still resolves to the verified base, the checkout and `HEAD^{tree}` equal the verified tree, and `HEAD` is the verified head or one commit directly on top of it. Any denial means `VERIFY` again on what you intend to publish.
4. The gate is a mechanical check, not a substitute for reading the result. It cannot see whether the reviewer was independent, whether the criteria passed to it were complete, or whether the verification line agrees with the Markdown below it; those remain the orchestrator's responsibility. A line that disagrees with the Markdown makes the result invalid: `VERIFY` again.

## PUBLISH: commit, push, PR

- Branch: reuse the issue's branch when one exists, otherwise create one from the base; never push to `main`.
- Push with `git push -u origin <branch>`; never force-push a shared branch without the human's approval.
- Create the PR with `monada-neuron-create-pull-request`. Map the verification result into its headings without adding new top-level headings:
  - `How to test / verify`: the result's `Checks run` and `Checks not run` lines with their PASS / FAIL / NOT RUN status unchanged, followed by a traceability list `AC1 — <short criterion> — SATISFIED — <evidence>` for every checklist row, and the verified snapshot (`base`, `head`, `tree`) with the number of verification rounds;
  - `Risk & rollout`: the result's residual risks and limitations;
  - `Notes for reviewers`: non-blocking findings left open, disputed findings and their human decision, scope clarifications;
  - `Why`: `Closes #N` only when every acceptance criterion is satisfied without a scope reduction; otherwise `Related to #N`. Reference the epic as `Related to #<epic>`.
- After creating the PR, fetch it again and confirm the title, body, and head SHA. The PR head must be the commit the publish-mode gate allowed: the verified head itself, or the single commit on top of it that carries the verified tree. If it is any other commit, the PR is not verified: say so and verify again.
- Do not merge, approve, enable auto-merge, or close the issue.

### When verification cannot pass

If the loop ends in `ESCALATE` or `RESOLVE` without a current `PASS`, do not publish as verified and do not report success. Only if the human explicitly asks for it, open a **draft** PR whose `How to test / verify` starts with `Independent verification: NOT PASSED (<status and cause>)`, lists the open findings or blockers, and uses `Related to #N`.

## REPORT

The orchestrator owns the `refs/verification/<tree>` refs that reviewers leave for rechecks. Once the PR is published, or the work is abandoned, delete the refs of the trees this orchestration had verified, one by one (`git update-ref -d refs/verification/<tree>`). Never delete the whole `refs/verification/` namespace: refs are shared by every worktree of the repository, and other verification loops may still need theirs. While a recheck may still follow, keep the ref of the latest reviewed snapshot.

End with a short report to the human: the end state, the verification status and snapshot it applies to, the number of rounds, the PR link or the reason nothing was published, checks not run, and what the human needs to decide, if anything. Report outcomes exactly as observed.

## Validation

`validation.md` records the scenarios (pass, failed requirement with correction and revalidation, stale result after a post-verification edit, ambiguity, unavailable reviewer, unavailable test environment) and their observed results. `validation-fixtures.sh` rebuilds the fixtures; `publication-gate-check.sh` is the self-test of `publication-gate.sh`.
