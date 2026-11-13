# Synthetic Issue S204-1: Record Vector batch resonance at 1,000,000 pairs

(Synthetic issue written for the #204 evaluation. It is not on GitHub. Treat it as the issue under review.)

## Background

The PR #35 study in `docs/benchmarks/baseline-methodology.md` compares the Scalar and Vector batch resonance
evaluators up to 100,000 pairs. Large recall batches can reach one million pairs, and nothing records how the
backends compare there.

## Goal

Measure Scalar against Vector batch resonance (SoA overload) at 1,000,000 pairs and record the result, and what it
means, in `docs/benchmarks/baseline-methodology.md`.

## Non-goals

- No production code, benchmark code, or default change.
- No change to the committed PR #35 numbers.

## Acceptance criteria

- AC1: `baseline-methodology.md` records a Scalar versus Vector result at 1,000,000 pairs, with the command and the
  environment used.
- AC2: Every performance statement the change makes is supported by its measurement under the repository's
  benchmarking rules (`AGENTS.md` "Testing and Benchmarking" and the project's benchmarking skills).
- AC3: The committed PR #35 numbers are unchanged.
- AC4: No production code, benchmark code, or default changes.
