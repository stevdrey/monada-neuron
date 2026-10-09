---
name: monada-neuron-create-pull-request
description: Use when drafting, opening, or updating a Monada Neuron pull request. Enforce the [Issue-N] PR title/summary prefix and the evidence-based What/Why/Changes/Verification/Risks/Reviewer Notes format.
---

# Create a Pull Request

## Trigger

Apply this skill whenever an agent creates a pull request or prepares/updates its title or description. Follow `AGENTS.md` and the relevant implementation skills. This skill concerns PR preparation, not permission to merge.

## Required inputs and checks

1. Identify the target repository, base branch (usually `main`), head branch, and linked GitHub Issue number.
2. Read the linked Issue, its Spec Context and acceptance criteria; inspect the current base branch and the actual diff (`git diff base...head` or the GitHub compare API). Do not write the description from the Issue alone.
3. Inspect relevant tests, docs/ADRs, migrations, deployment considerations, and changes to public interfaces.
4. Run available relevant verification before opening the PR, or explicitly record why a command was not executed. Never claim unobserved test outcomes.
5. If there is no verified Issue number, do not fabricate one. Obtain or resolve a real issue reference before publishing an Issue-prefixed PR. For explicitly issue-free maintenance PRs, use `[No-Issue]` and explain why in `Why`.

## Title and summary naming

- **Required title format:** `[Issue-<number>] <imperative, concise change summary>` (example: `[Issue-72] Add deterministic signal routing tests`).
- The title is the GitHub PR **summary**. Always put the prefix at its beginning, without leading spaces, and do not duplicate an existing prefix.
- Use the actual linked Issue number, not the PR number. If one PR covers multiple issues, use the primary issue in the prefix and reference the others in `Why`.
- For a separately requested one-line summary, start with exactly the same `[Issue-<number>]` prefix.
- Prefer issue-specific wording over generic labels such as `Fixes` or `Updates`.
- Mention the issue explicitly in the body (e.g. `Closes #72`) only when the full issue is actually resolved; otherwise write `Related to #72`. Avoid automatic closing language for partial deliveries.

## Description: mandatory headings and order

Use **exactly these top-level headings in this order**, modeled on https://github.com/stevdrey/dokene/pull/161:

### What

One concise paragraph explaining the delivered capability, its scope, and where it stops. State user or system-visible behavior rather than only implementation details.

### Why

Explain the problem, the linked Issue, relevant Spec Context/ADR or decision, why this work is needed now, and any important corrections discovered while implementing. Include `Closes #N` or `Related to #N` as appropriate.

### Changes

Group actual implementation changes by meaningful area using bold subsection labels and focused bullets. Cover modules, APIs, data model, architecture, tests, docs, and migrations when applicable. Describe what the diff really contains; avoid exhaustive file dumps or speculative features.

### How to test / verify

Give exact, copyable commands (with working directory or prerequisites), indicate whether each was **PASS**, **FAIL**, or **NOT RUN**, and summarize what it checks. For example, `./gradlew test`. List targeted tests and manual checks only when relevant. Link verification instructions/spec acceptance criteria when available.

### Risk & rollout

State compatibility, migration, operational/performance/security implications, rollout steps, rollback or recovery approach, and residual risks. For changes with no deployment impact, explicitly say so. Separate verified guarantees from assumptions and untested scenarios.

### Notes for reviewers

Call out non-obvious design decisions, trade-offs, highest-risk code paths, unresolved questions, and useful files to inspect first. For low-complexity PRs, use `No additional reviewer notes.` rather than inventing issues.

## Quality rules

- Write everything in English, including titles, description, commands, and comments.
- Be concrete and evidence-based; distinguish implemented behavior, proposed follow-ups, and unverified assertions.
- Link relevant Issue/Spec/ADR paths or URLs; document scope boundaries and non-goals.
- Do not copy Dokene-specific domains, tables, or commands from the reference PR.
- Preserve the current repository's Java 27, architecture, performance, and documentation requirements when applicable.
- Never state `tests pass` unless the actual results are available.
- Avoid excessive text proportional to the change, but do not omit a required heading.

## Publish checklist

Before calling GitHub PR create/update:

- [ ] The title begins with `[Issue-N]` matching the verified linked Issue.
- [ ] The linked Issue and base-versus-head diff were reviewed.
- [ ] All six headings exist in the correct order and have substantive, truthful content.
- [ ] Tests and verification status are precise and reproducible.
- [ ] Risks, rollout, rollback, and known limitations are covered or explicitly not applicable.
- [ ] The body accurately reflects the current head revision.

After creating or updating the PR, fetch it again to confirm the stored title and description. Do **not** merge as part of this skill.

## Copy-ready skeleton

```markdown
PR title: [Issue-123] Implement concise capability name

## What

Describe the delivered behavior and the scope boundary.

## Why

Explain the motivating problem and linked spec/ADR.
Closes #123

## Changes

**Core**
- Describe concrete changes.

**Tests and documentation**
- Describe actual updates.

## How to test / verify

- `./gradlew test` — NOT RUN (state why), or PASS/FAIL (state observed result and coverage).

## Risk & rollout

Describe compatibility, rollout, rollback, and residual risks. If not applicable, explain why.

## Notes for reviewers

Identify relevant decisions, important files, and unresolved follow-ups.
```
