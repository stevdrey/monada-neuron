---
name: monada-neuron-evaluation-benchmarking
description: Use when designing correctness baselines, performance experiments, JMH benchmarks, regression checks, or comparisons between execution strategies, and when stating or reviewing a measured speedup, latency, throughput, memory, recall, ranking, or evaluation claim (claim evidence report).
---

# Monada Neuron Evaluation and Benchmarking

## Context

Read `AGENTS.md`, `docs/architecture.md`, `docs/design-principles.md`, and the performance-related skills before creating a benchmark or making a performance claim.

## Principle

A benchmark should answer a specific engineering question.

Examples:

- Does a compact adjacency layout reduce memory and traversal time at target node counts?
- Does Vector API SIMD improve a signal transform over the scalar baseline?
- At what batch size does GPU execution beat CPU SIMD end-to-end?
- Does off-heap state reduce GC pressure enough to justify explicit memory management?

Avoid benchmark suites that collect numbers without a decision they are meant to inform.

## Correctness Baseline First

Before comparing performance, establish a deterministic reference implementation and expected output.

For numeric implementations, define tolerances before comparing scalar, SIMD, parallel, native, or GPU results.

For graph/ranking/state operations, define ordering and identity semantics explicitly.

## Microbenchmarking

Use JMH when measuring operations small enough to be affected by:

- JIT compilation;
- dead-code elimination;
- constant folding;
- warm-up;
- loop optimizations;
- timer overhead.

A JMH benchmark should document:

- state scope;
- parameters/data sizes;
- forks;
- warm-up iterations;
- measurement iterations;
- benchmark mode;
- JVM flags;
- setup/teardown behavior.

Do not create hand-written repeated loops and treat them as reliable microbenchmarks unless JMH is genuinely impractical and the limitation is stated.

## System / End-to-End Benchmarks

For larger cognitive flows, record:

- node/signal counts;
- graph density;
- signal dimensions;
- history/cache settings;
- memory backend;
- concurrency level;
- batch size;
- external adapter behavior or stubs;
- elapsed latency and throughput;
- peak/retained memory when relevant;
- allocation/GC observations when relevant.

## Comparative Experiments

A comparison should keep semantics and workload constant.

Preferred report:

```text
Question
Environment
Workload
Reference implementation
Candidate implementation
Correctness check
Warm-up / measurement method
Results
Memory/allocation observations
Trade-offs
Decision / recommendation
```

When the result supports a stated numerical claim, the claim evidence report below replaces this list.

## Claim Evidence Report

### When it applies

Fill a claim evidence report when an Issue, PR, ADR, or document states a measured speedup, latency, throughput,
memory, allocation, recall, ranking, or evaluation gain. The template is `claim-evidence-report.md` next to this
skill, and `claim-environment.sh` prints its environment block. A filled example is
`docs/benchmarks/claim-evidence-simd-resonance.md`.

It does not apply to a change that states no numeric gain, or to a run labeled `exploratory` and not used for a
decision. An exploratory run never supports a stated gain.

The report adds fields only. The reference, JMH, and accelerator rules in this skill stay as they are. When a
registered protocol (for example `docs/experiments/html-knowledge-v1.md` or `bilingual-sense-v2.md`) defines its own
verdict rule, that rule decides the verdict for that protocol, and the report records how.

This is a review standard, not a CI gate. No benchmark becomes a required check, and no claim requires specialized
hardware, a GPU, or an external model provider. When the environment needed to replay a claim is unavailable, the
field is `pending` with the reason.

### Required fields

The claim's author fills every field, or writes `pending: <reason>`. A reviewer checks it.

1. **Claim**: the sentence that will ship, written before the run, with metric, direction, size, and scope.
2. **Target metric and decision**: the metric that decides, its unit, and what the result changes.
3. **Baseline and variant**: the classes, configurations, and revisions on each side, each tuned as it ships
   (flags such as `--add-modules jdk.incubator.vector`, backend selection under ADR 0017, parallelism thresholds
   under ADR 0016).
4. **Workload**: the benchmark or fixture, its revision or content hash, seed, sizes, and the input distribution,
   including which code paths it does not reach.
5. **Commands**: exact commands that reproduce the numbers, with output paths.
6. **Environment**: JDK vendor and build, JVM flags, OS, CPU model, logical CPUs, SIMD features, memory, and machine
   load at run time.
7. **Method**: warm-up, measurement iterations, forks or repetitions, and mode. Non-JMH system runs alternate
   baseline and variant over at least five runs. JMH keeps its forks and error statistics.
8. **Results and variability**: per side, central value with error or range, not a single number. A gain is a
   factor above 1 when the variant is better: baseline/variant for lower-is-better metrics (latency, allocation,
   memory) and variant/baseline for higher-is-better metrics (throughput). Bounded scores such as recall or MRR are
   compared as a difference, variant minus baseline. Name the direction in the column heading, for example
   `Speedup Scalar/Vector`, so a gain cannot be read inverted.
9. **Semantic check**: how the variant was shown to produce the reference result on the measured workload.
10. **Work performed**: a counter, checksum, or terminal-status assertion showing that the timed region did the
    claimed work.
11. **Bound check**: an arithmetic limit on the gain (lane count, share of removable work, memory bandwidth), and
    whether the result is within it.
12. **Limiter**: for a claim that explains a gain or adopts a design, the measured cause (JFR/JMC, async-profiler,
    `-prof gc`, hardware counters), from a run whose numbers are not reported.
13. **Share of end to end**: for a micro result, its share of the path users run.
14. **Limits**: what the result does not cover (sizes, hardware, input ranges, JDK).
15. **Verdict**: see below.

### Verdicts

Each compared metric gets one result: `better`, `worse`, `no measurable difference` (the intervals overlap or the
difference is below the stated noise), or `inconclusive` (the method cannot tell).

The claim gets one status:

- `SUPPORTED`: every field is filled, no invalid-claim rule fires, and the results match the claim.
- `NARROWED`: the measurement holds only for a smaller statement, which the report writes out. Ship that statement.
  A field may stay `pending` only when that statement excludes what the field would establish (for example,
  "microbenchmark; share of the end-to-end path not measured").
- `UNSUPPORTED`: an invalid-claim rule fires against the claim as written and no smaller statement survives, or the
  results contradict the claim. Name the rule.
- `PENDING`: a required field is `pending` and the statement depends on it. The claim cannot ship as a measured
  result.

Narrowing never edits committed numbers. A report that disagrees with a committed table records the disagreement
next to it and leaves the table as it was, as the Issue #48 correction did.

### Invalid-claim rules

| Id | The claim is invalid when |
| :--- | :--- |
| IC1 | It has no baseline, or the baseline ran on a different workload, revision, or machine without saying so. |
| IC2 | One side is not tuned as it ships, or the sides receive different flags, threads, or data. |
| IC3 | The variant has no semantic check against the reference on the measured workload, or tolerance is unstated. |
| IC4 | Nothing shows the timed region did the work (dead code, early exit, truncated cycle, as in Issue #48). |
| IC5 | It reports one run, or a central value without variability, or alternating runs were not used outside JMH. |
| IC6 | The gain exceeds its arithmetic bound and no limiter explains the excess, or it attributes the gain to a cause no measurement isolates. |
| IC7 | A micro or kernel-only result is stated as an end-to-end or application result. |
| IC8 | Commands, environment, or workload revision are missing, so nobody can replay it. |
| IC9 | Evaluation data leaks: gold labels, holdout items, or near-duplicates reach the system under test, or a threshold was chosen on the data it is scored on. |
| IC10 | Evaluation outcomes are mixed: retrieval, evidence use, and answer quality are reported as one number, or abstention is not scored. |
| IC11 | A measurement the protocol excludes decides the verdict, for example latency in the `html-knowledge` family. |

### SIMD, native, and accelerated paths

In addition to "CPU SIMD Experiments" and "GPU / Accelerator Experiments" below:

- check the variant against the scalar or reference implementation at tail lengths (not multiples of the lane count),
  small inputs, boundary values, and the input ranges that take a different code path, with an explicit tolerance;
- state whether the variant and the reference use the same arithmetic. When they do not (an intrinsic instead of
  `StrictMath`, a different reduction order), the gain is not attributable to lanes alone;
- the bound check uses the lane count of the measured species. A gain above it needs a limiter;
- report the end-to-end cost, including layout conversion, off-heap copies, transfer, compilation, warm-up, and the
  fallback path on hosts without the capability.

### Knowledge, recall, and ranking evaluations

- **Provenance**: the corpus and query set revisions or hashes, and who wrote the gold labels and when, relative to
  the system under test.
- **Gold labels**: independent of the system's output, and readable only by the scorer, never by the system under
  test.
- **Abstention**: queries with no answer in the corpus are scored, with their false-positive rate.
- **Holdouts**: thresholds, grids, and weights are chosen on a split that the reported score does not use.
- **Leakage**: no gold label, holdout item, or near-duplicate reaches the system under test, and a test or check
  shows it.
- **Outcome separation**: retrieval (did the right evidence come back), evidence use (was it used), and answer
  quality (was the answer right) are reported separately.

## CPU SIMD Experiments

Compare at least:

- scalar reference;
- candidate Vector API implementation;
- multiple input lengths, including non-vector-multiple tails;
- small inputs where vector setup may not pay off;
- realistic target sizes.

When portability matters, test representative x64 and AArch64 hardware if available.

## CPU Parallel Experiments

Compare multiple task sizes and worker counts. Do not assume maximum thread count is optimal.

Observe:

- scaling by core count;
- contention;
- memory bandwidth saturation;
- task scheduling overhead;
- false sharing when applicable.

## FFM / Off-Heap Experiments

Include allocation/lifetime cost and any data copying required to move between heap and native memory.

Measure GC benefit separately from raw compute speed.

## GPU / Accelerator Experiments

Always include end-to-end time.

Break down when possible:

- host preparation;
- host-device transfer;
- kernel compilation/warm-up;
- kernel execution;
- synchronization;
- device-host transfer;
- result materialization.

Evaluate multiple batch/problem sizes and report the crossover point where the accelerator becomes beneficial.

A kernel-only speedup is diagnostic information, not the final application performance result.

## Memory Benchmarks

When comparing representations, include approximate or measured retained memory per:

- node;
- edge;
- signal element;
- history entry;
- cache entry.

Use heap histograms/profilers or controlled sizing where possible instead of relying only on theoretical field counts.

## Protected vs Exploratory Baselines

Use two classes of benchmarks when useful:

### Protected

Small, deterministic, stable, CI-friendly workloads that catch semantic/performance regressions without relying on specific high-end hardware.

### Exploratory

Large, hardware-specific, real-world workloads used for design decisions and optimization research.

Do not make GPU availability or a specific CPU model a requirement for the normal test suite unless the repository explicitly adopts that infrastructure.

## Regression Thresholds

Avoid fragile nanosecond-level CI gates.

Prefer:

- coarse regression thresholds;
- operation/allocation invariants;
- protected data sizes;
- trend reporting;
- local exploratory performance runs for hardware-sensitive optimization.

## Acceptance

A benchmark is useful when it is reproducible, protects semantic equivalence, isolates the engineering question, accounts for JVM/runtime effects, includes the relevant resource metric, and produces evidence that can support an architecture or implementation decision.
