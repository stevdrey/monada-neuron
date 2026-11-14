# Monada Neuron Agent Skills

This directory contains project-specific skills for coding agents working on Monada Neuron.

Use the smallest relevant skill set for the task. `AGENTS.md` remains the repository-wide source of operating rules.

## Skills

| Skill | Use when |
| --- | --- |
| `monada-neuron-agent-task-workflow` | Preparing implementation plans, Issues, PR reviews, or agent-scoped work. |
| `monada-neuron-create-pull-request` | Creating or updating PRs with an `[Issue-N]` summary/title and standardized evidence-based description. |
| `monada-neuron-implement-issue` | Implementing a GitHub Issue end to end from its number: gathering linked context, clarifying unknowns, looping through independent verification and fixes, and publishing a PR only on a current PASS. |
| `monada-neuron-independent-verification` | Independently verifying, as a subagent, that a change snapshot meets an Issue's requirements, probing edge cases, and returning PASS / NEEDS CHANGES / BLOCKED evidence before commit, push, or PR. |
| `monada-neuron-cognitive-architecture` | Changing Monad, Aeon, Node, Signal, resonance, evolution, action, or adapter boundaries. |
| `monada-neuron-java-27-implementation` | Implementing or reviewing Java 27 code and modern JDK features. |
| `monada-neuron-data-structures-algorithms` | Selecting or changing collections, graph layouts, queues, indexes, numeric buffers, or algorithms. |
| `monada-neuron-performance-optimization` | Optimizing latency, throughput, memory, allocation, locality, or hot loops from a measured baseline. |
| `monada-neuron-concurrency-hardware-acceleration` | Adding concurrency, SIMD, FFM/native memory, shared-memory integration, GPU, or accelerator paths. |
| `monada-neuron-evaluation-benchmarking` | Designing correctness baselines, performance experiments, JMH benchmarks, or regression gates; stating or reviewing a measured performance or evaluation claim with a claim evidence report. |
| `monada-neuron-executive-summary` | Generating or publishing high-level, non-technical executive summaries in English Markdown for GitHub Issues. |

## Evidence Practices (Epic #199)

Practices adapted from pstack and evaluated in `docs/experiments/pstack-adoption-evaluation-v1.md`. None is mandatory
on every PR, and none adds a reviewer, fan-out, or dependency.

| Practice | Status | Lives in | Applies when |
| --- | --- | --- | --- |
| Claim evidence report (P1) | Adopted | `monada-neuron-evaluation-benchmarking`, "Claim Evidence Report" | A change states a measured performance or evaluation gain. |
| Bounded schedule check (P2) | Adopted, narrowed | `monada-neuron-concurrency-hardware-acceleration`, "Bounded Parallel Aeon Coordination" | As defined in that section: the coordinator, its ADR 0016 failure or abnormal-exit decisions, or the schedule harness. |
| Proof-graded findings (P3) | Adopted | `monada-neuron-independent-verification`, "Proof-graded findings" | Every independent verification that raises a finding. |
| Boundary impact trace (P4) | On probation (re-evaluate after five boundary-crossing PRs or on 2027-05-13) | `monada-neuron-independent-verification`, "Boundary impact trace" | A change crosses a cognitive, host, format, or optimized-path boundary. |
| Boundary red-flag screen (P5) | On probation, untested (re-evaluate after five boundary-crossing PRs or on 2027-05-13) | `docs/specs/pr-review-checklist.md`, "Cognitive Architecture" | The same boundary changes, except paths that are only concurrent, SIMD, or native. |

`.windsurf/skills/` stays a byte-identical mirror checked by `diff -r` in CI. A thin pointer shim is not adopted until
a client test shows it is discovered and followed.

## Skill Combination Examples

A vectorized signal-processing change will normally use:

- `monada-neuron-data-structures-algorithms`;
- `monada-neuron-performance-optimization`;
- `monada-neuron-concurrency-hardware-acceleration`;
- `monada-neuron-java-27-implementation`.

A new Aeon generally starts with:

- `monada-neuron-cognitive-architecture`;
- `monada-neuron-java-27-implementation`;
- `monada-neuron-evaluation-benchmarking`.

A milestone or feature completion update will normally use:

- `monada-neuron-agent-task-workflow`;
- `monada-neuron-executive-summary`.

Implementing an Issue end to end starts with `monada-neuron-implement-issue`, which orchestrates:

- `monada-neuron-agent-task-workflow` to shape the task;
- `monada-neuron-independent-verification`, invoked as a separate subagent, until it returns `PASS` on the final snapshot;
- `monada-neuron-create-pull-request`, carrying that result's checks into `How to test / verify`.
