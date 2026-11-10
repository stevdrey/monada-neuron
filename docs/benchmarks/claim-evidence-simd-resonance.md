# Claim Evidence Report: Vector API Batch Resonance (`claim-evidence/simd-resonance/1`)

## Status

| Field | Value |
| :--- | :--- |
| Report version | `claim-evidence/simd-resonance/1` |
| Issue | [#201](https://github.com/stevdrey/monada-neuron/issues/201) (Agent Skills 2/5, Epic [#199](https://github.com/stevdrey/monada-neuron/issues/199)), practice P1 of [`pstack-skill-audit/1`](../experiments/pstack-skill-audit-v1.md) |
| Standard | `.agents/skills/monada-neuron-evaluation-benchmarking/SKILL.md`, "Claim Evidence Report" |
| Claim audited | [ADR 0013](../adr/0013-vector-api-batch-resonance-backend.md), "Consequences", and [baseline methodology](baseline-methodology.md#1-vector-api-simd-for-batch-resonance-completed---adr-0013), "Retention evidence" |
| Replayed at | `main` at `923c9e510b7893bdc85ecb64411e1a4d47c9dd10`, 2026-11-10 |

This report is the worked example of the claim evidence standard. It replays one existing SIMD claim, fills every
field, and gives a verdict. It then runs the invalid-claim rules against seeded claims, and applies the knowledge
evaluation fields to a replay of the `html-knowledge-recall` retrieval claim.

**Committed numbers are unchanged.** The PR #35 table in ADR 0013 and in the baseline methodology stays as it was.
The SIMD replay ran on different hardware and a different JDK, so it adds a second measurement and does not replace
the first. The recall replay reproduced its committed table exactly. No production code, benchmark, test, or default
changed.

## Claim evidence: Vector versus Scalar batch resonance, 1,000 to 100,000 pairs

| Field | Value |
| :--- | :--- |
| Claim | "On the representative 1,000 to 100,000-pair SoA workloads, Vector completes batches in approximately 1.4 ms or less and is 6.2x to 6.7x faster than Scalar in this study." (ADR 0013) |
| Source | ADR 0013, "Consequences"; numbers from the PR #35 study in `baseline-methodology.md` |
| Target metric and decision | Mean batch latency (`avgt`, ns/op) of `scoreBatch` over SoA arrays. Decision: whether the Vector backend is retained (ADR 0013) |
| Baseline | `ScalarBatchResonanceEvaluator.INSTANCE`, SoA overload. It is the reference oracle and runs as it ships: `StrictMath.IEEEremainder` three times and `StrictMath.cos` once per pair, with per-pair input validation |
| Variant | `VectorBatchResonanceEvaluator.INSTANCE`, SoA overload, as it ships: `DoubleVector.SPECIES_PREFERRED`, magic-number round-to-nearest phase wrapping, `VectorOperators.COS`, scalar lane reduction for any lane with a phase magnitude above 1,000, and the scalar formula for the tail |
| Tuning of each side | Both run in the same JMH forks with `--add-modules jdk.incubator.vector`. Neither goes through backend selection (ADR 0017), so the dispatch threshold does not affect either. No side has a flag the other lacks |
| Workload | `ScalarResonanceBenchmark` (file SHA-256 prefix `84127397a058ab05`) and `DeterministicWorkloadGenerator.generateFrequencyStateBatches` (prefix `64c7ba2d1a02e39a`), seed `42`, sizes 1,000, 10,000, 100,000. Amplitudes are uniform in [0.05, 1.0), frequencies in [0, 100), phases in [0, 2π) |
| Paths not reached | The workload never reaches zero amplitude, zero or equal frequency, or a phase magnitude above 1,000. The Vector scalar-reduction branch and those branches of the formula are therefore not measured |
| Commands | See [Reproduce](#reproduce) |
| Environment | See [Environment](#environment) |
| Method | JMH 1.37, `-bm avgt -f 3 -wi 2 -i 3 -r 1s -prof gc`, one thread, `Blackhole` consumption of the result buffer. Each size and side has 9 measurement iterations over 3 forks. Same method as PR #35 |
| Semantic check | Scalar and Vector compared on the benchmark workload itself (seed 42) at 1, 3, 4, 5, 7, 1,000, 1,001, 1,003, 10,000 and 100,000 pairs: maximum absolute difference `1.110e-16`, within the ADR 0013 tolerance of `1.0e-12`. `BatchResonanceEvaluatorTest` covers tails from 0 to 1,025, large phases up to `Double.MAX_VALUE`, and edge values with other data |
| Work performed | Each invocation writes all `n` scores into a result buffer that `Blackhole` consumes, so the stores cannot be eliminated. The semantic-check run printed a non-zero scalar checksum for each size (for example `13625.073990274395` at 100,000 pairs) and `zeroScores=0` at every size. Allocation per operation scales linearly with `n` on the Scalar side, which confirms that `n` pairs were scored |
| Bound check | `SPECIES_PREFERRED` is `S_256_BIT`, four double lanes, on both machines. Lane parallelism alone bounds the gain at 4x. Every measured ratio, PR #35's and this replay's, is above 4x |
| Limiter | Partly measured. The two sides use different arithmetic, so the excess over 4x is not attributable to lanes. A diagnostic run, whose numbers are not part of the result, measured with `ThreadMXBean` after warm-up that `StrictMath.cos` allocates 32.00 B per call on Zulu 27+35, and `StrictMath.IEEEremainder` and `Math.cos` allocate nothing. This matches the Scalar side's 32 B per pair. How much of the time difference comes from lanes, from the cosine implementation, and from that allocation is `pending`: it needs a profiler run (JFR or async-profiler) or a variant with the same arithmetic |
| Share of end to end | `pending`, and excluded by the narrowed statement. At this revision no class in `src/main/java` outside `monada.neuron.resonance` and `monada.neuron.runtime.selection` calls a `BatchResonanceEvaluator`, so no cognitive cycle path includes this cost yet |
| Limits | Two x86_64 AVX2 machines, four-lane species only. No AArch64, AVX-512, or JDK other than Temurin 26+35 and Zulu 27+35. SoA only, not the `FrequencyState[]` overload. Only the input ranges listed above |

### Results

PR #35 (committed, unchanged): Intel Core i7-6500U, Temurin 26+35. This replay: AMD Ryzen 7 7730U, Zulu 27+35.
Values are JMH mean ± 99.9% error over 9 iterations. The ratio range uses the minimum and maximum iteration of each
side.

| Metric | Pairs | Scalar | Vector | Ratio Scalar/Vector | Result |
| :--- | ---: | ---: | ---: | ---: | :--- |
| PR #35 latency, ns/op | 1,000 | 78,002.71 | 12,620.64 | 6.18 | better |
| PR #35 latency, ns/op | 10,000 | 956,206.48 | 142,138.04 | 6.73 | better |
| PR #35 latency, ns/op | 100,000 | 9,287,027.59 | 1,405,598.31 | 6.61 | better |
| Replay latency, ns/op | 1,000 | 30,643.0 ± 541.5 | 6,129.0 ± 161.5 | 5.00 (4.78 to 5.16) | better |
| Replay latency, ns/op | 10,000 | 485,756.2 ± 6,311.3 | 62,480.0 ± 1,972.8 | 7.77 (7.44 to 8.03) | better |
| Replay latency, ns/op | 100,000 | 5,389,986.7 ± 180,603.3 | 632,652.8 ± 18,870.9 | 8.52 (7.95 to 8.96) | better |
| Replay `gc.alloc.rate.norm`, B/op | 1,000 | 32,000.20 | 0.04 | n/a | better |
| Replay `gc.alloc.rate.norm`, B/op | 10,000 | 320,003.11 | 0.40 | n/a | better |
| Replay `gc.alloc.rate.norm`, B/op | 100,000 | 3,200,034.40 | 4.02 | n/a | better |

Per-fork means were within 5% of each other on every row (for example Scalar at 100,000 pairs: 5,518,770,
5,349,669, and 5,301,521 ns/op).

Observations:

- Vector is faster at every size on both machines, and the "1.4 ms or less" part of the claim holds here (0.63 ms
  at 100,000 pairs).
- The ratio does not reproduce on this machine: 5.0x to 8.5x here against 6.2x to 6.7x in PR #35. It rises with
  size here and is flat there. The range is hardware-dependent, so a single range for both is not supported.
- In the committed PR #35 table, Scalar at 128 pairs (23,883.53 ns) is slower than at 256 pairs (16,402.92 ns). A
  run that takes longer for half the work points to noise or a fork outlier in that row. It is outside this claim's
  sizes and was not replayed. It is recorded here and not corrected there.

### Verdict

- Status: `NARROWED`.
- Invalid-claim rules that fired against the claim as written: `IC6` and `IC5`. The 6.2x to 6.7x gain exceeds the four-lane
  bound, and ADR 0013 names no limiter. Its context ("benefit substantially from CPU vector registers") reads the
  gain as a SIMD effect, while the two sides use different arithmetic and the reference allocates 32 B per pair.
  `IC5` fires because the PR #35 table publishes means only, with no error or range, although it ran three forks. The
  narrowed statement therefore marks that range as central values only.
  `IC1` does not fire: PR #35 named its machine and JDK. The replay is a different machine, which the results table
  states.
- Statement to ship: "On the `ScalarResonanceBenchmark` SoA workload (phases in [0, 2π), 1,000 to 100,000 pairs),
  the shipped Vector backend completed batches faster than the shipped scalar reference: 6.2x to 6.7x on an Intel
  i7-6500U with Temurin 26+35 (ratio of means; no error was published), and 5.0x to 8.5x (iteration range 4.8x to
  9.0x) on an AMD Ryzen 7 7730U with Zulu 27+35. Both machines have four-lane AVX2, but the two backends use different
  arithmetic and the scalar reference allocates 32 B per pair in `StrictMath.cos`, so the ratio is not a measure of
  lane parallelism alone, and the cause of the excess over 4x is not measured. This is a microbenchmark result, and its share of a cognitive
  cycle is not measured."
- Pending fields and what would resolve them:
  - Limiter split: a JFR or async-profiler run of both sides at 100,000 pairs, or a scalar variant that uses
    `VectorOperators.COS`-equivalent arithmetic, to separate lanes from arithmetic and allocation.
  - Share of end to end: measurable once a cognitive path calls a `BatchResonanceEvaluator`.
- ADR 0013's retention decision does not depend on the attribution: it rests on equivalence, the allocation gate, and
  Vector being faster, all of which this replay confirms. Whether to amend ADR 0013's wording is a decision for its
  owner, not for this report.

## Reproduce

Run the environment block before and after, the benchmark, and the semantic check. Output paths are outside the
repository. `$OUT` is any scratch directory. `$TOOLCHAIN_JAVA` is the `bin/java` of the Gradle toolchain JDK 27 that
the JMH task runs on (`./gradlew -q javaToolchains` lists its location); here it was Zulu 27+35.

```bash
.agents/skills/monada-neuron-evaluation-benchmarking/claim-environment.sh --java "$TOOLCHAIN_JAVA" > "$OUT/env-before.md"
```

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-bm avgt -f 3 -wi 2 -i 3 -r 1s -prof gc -rf json -rff $OUT/simd-claim.json -p batchSize=1000,10000,100000 ScalarResonanceBenchmark.benchmark(Scalar|Vector)BatchSoA"
```

```bash
.agents/skills/monada-neuron-evaluation-benchmarking/claim-environment.sh --java "$TOOLCHAIN_JAVA" > "$OUT/env-after.md"
```

The semantic check is a single-file program run against the classes the JMH task just built. It scores the benchmark
workload with both backends and prints the maximum absolute difference, a checksum, and the number of zero scores
per size:

```java
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.resonance.ScalarBatchResonanceEvaluator;
import monada.neuron.resonance.VectorBatchResonanceEvaluator;

public class SemanticCheck {
    public static void main(String[] args) {
        for (int n : new int[]{1, 3, 4, 5, 7, 1000, 1001, 1003, 10000, 100000}) {
            var pair = new DeterministicWorkloadGenerator().generateFrequencyStateBatches(n);
            double[] s = new double[n];
            double[] v = new double[n];
            ScalarBatchResonanceEvaluator.INSTANCE.scoreBatch(pair.first(), pair.second(), s, 0, n);
            VectorBatchResonanceEvaluator.INSTANCE.scoreBatch(pair.first(), pair.second(), v, 0, n);
            double max = 0;
            double sum = 0;
            int zeros = 0;
            for (int i = 0; i < n; i++) {
                max = Math.max(max, Math.abs(s[i] - v[i]));
                sum += s[i];
                if (s[i] == 0.0) {
                    zeros++;
                }
            }
            System.out.printf("n=%d maxAbsDiff=%.3e withinTol=%b checksum=%.12f zeroScores=%d%n",
                    n, max, max <= 1e-12, sum, zeros);
        }
    }
}
```

```bash
"$TOOLCHAIN_JAVA" --add-modules jdk.incubator.vector \
  -cp build/classes/java/main:monada-neuron-evaluation/build/classes/java/main "$OUT/SemanticCheck.java"
```

The equivalence tests that cover tails, large phases, and edge values:

```bash
./gradlew test --tests 'monada.neuron.resonance.BatchResonanceEvaluatorTest'
```

The limiter diagnostic called `StrictMath.cos`, `StrictMath.IEEEremainder`, and `Math.cos` one million times each on
inputs in [-π, π) after 20 warm-up passes, and read `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` before
and after. It is a diagnostic, and its timing is not reported.

## Environment

| Field | Before | After |
| :--- | :--- | :--- |
| Captured (UTC) | 2026-11-10T21:56:15Z | 2026-11-10T21:58:02Z |
| OS | Linux 7.2.15-300.fc45.x86_64 x86_64 | same |
| CPU | AMD Ryzen 7 7730U with Radeon Graphics | same |
| Logical CPUs | 16 | same |
| SIMD features | avx avx2 fma sse4_2 | same |
| Memory | 15.0 GiB total, about 2.5 GiB available | same |
| Load average (1, 5, 15 min) | 5.05 2.82 1.61 | 3.61 3.12 1.87 |
| JDK | Zulu27.28+101-CA (build 27+35), from the Gradle toolchain | same |
| JVM flags | `--add-modules=jdk.incubator.vector` (passed twice by the task), `-Dfile.encoding=UTF-8`, locale properties | same |
| Vector API preferred species | `Species[double, 4, S_256_BIT]` | same |

The machine was a shared developer laptop, not idle. A load average near 5 on 16 logical CPUs leaves cores for one
JMH thread, and the per-fork spread stayed within 5%. It is still a caveat: this replay is not a controlled-host
measurement. The before and after rows were captured with `/proc/loadavg` and `date` around the run, and the
remaining rows with `claim-environment.sh` right after it. That deviates from the template, which runs the script
both before and after. The rows it would add before the run (CPU, species, JDK) cannot change during a run, and the
load average, which can, was captured both times.

## Seeded invalid claims

Each claim below is deliberately wrong. Checking it against the rules must flag it, and the rule that fires is the
outcome. S4 and S8 restate defects that already happened in this repository.

| Id | Seeded claim | Rule that fires | Why |
| :--- | :--- | :--- | :--- |
| S1 | "The Vector API scores 100,000 pairs in 0.63 ms, an 8x improvement." (Only the Vector row is cited.) | `IC1` | No baseline is named. "8x" has nothing to divide by in the evidence given. |
| S2 | "`AdaptiveBatchResonanceEvaluator` is 8x slower than direct Vector at 100,000 pairs." (Adaptive run without `--add-modules jdk.incubator.vector`, Vector run with it.) | `IC2` | Without the module, Adaptive falls back to Scalar (ADR 0013, decision 4). The sides had different flags, so the run compares Scalar with Vector, not dispatch with direct calls. |
| S3 | "Replacing `StrictMath.cos` with `Math.cos` makes the scalar reference 2x faster." (No equivalence run.) | `IC3` | `Math.cos` may differ from `StrictMath.cos` by an ulp, and the reference is the oracle. No tolerance is stated and no comparison on the workload was run. |
| S4 | "A full cognitive cycle takes 41.98 µs on Java 27." (Historical `CognitiveCycleBenchmark` row.) | `IC4` | Issue #48: the cycle ended in `STAGE_LIMIT_REACHED` after perception, so four of five stages did no work. A terminal-status assertion would have caught it. |
| S5 | "Vector is 8.5x faster." (One fork, one iteration, no error.) | `IC5` | One run with no variability. On this machine the 9-iteration range at 100,000 pairs alone spans 7.95x to 8.96x. |
| S6 | "SIMD gives an 8.5x speedup on four lanes." | `IC6` | The four-lane bound is 4x. The two sides use different arithmetic and the reference allocates per pair, so the excess is not attributable to lanes. No limiter has isolated its cause. |
| S7 | "The Vector backend makes Neuron's cognitive cycle 8x faster." | `IC7` | A batch-resonance microbenchmark is stated as an application result, and no cognitive path calls the batch evaluator at this revision. |
| S8 | "With a threshold of 0.35, B1 abstains correctly on every `no-answer` query in `html-kq/1`." (Threshold picked by looking at those queries.) | `IC9` | `html-knowledge-recall.md` says any threshold chosen by looking at this set is `in-sample (tuned)`. The scored set doubles as the tuning set. |
| S9 | "B1 Recall@5 is 0.795, so Neuron answers about 80% of questions correctly." | `IC10` | Recall@5 measures retrieval. Evidence use and answer quality are separate outcomes, and abstention queries are outside that mean. |
| S10 | "B2c beats B1 because its median latency is lower." | `IC11` | In the `html-knowledge` family latency is a diagnostic that never decides a verdict. |

Control: the claim replayed above came out `NARROWED`. It was not `UNSUPPORTED`, and its committed numbers were not
changed. The rules flag every seeded claim and leave the verified part of the real claim standing.

## Applying the knowledge fields to an existing protocol

The knowledge, recall, and ranking fields are checked here against `html-knowledge-recall` (#107). Its documented
claim is the judged-recall table in [html-knowledge-recall.md](html-knowledge-recall.md#results-2026-10-19-final-harness), with the statement
that scores are unchanged across harness revisions and Store checkouts. That claim was replayed with the sibling
`../monada-resonance-store` checkout at `a7f7af24a0dbd1f22e45ccb8a61846ed609938c4`, which ADR 0018 requires for the
adapter build. That working tree had uncommitted changes in six files, all under the Store's `book-queries-v3`
dataset: resources and a test in its `monada-evaluation` module, and `QUERIES-V3.md`. None of them belongs to
`html-kq/1`, which lives in this repository. The command was:

```bash
./gradlew :monada-neuron-evaluation:runHtmlKnowledgeRecall \
  -PbenchmarkArgs="--store-root $OUT/store --output-dir $OUT/out"
```

The run finished on 2026-11-10 between 22:01:03Z and 22:01:09Z, with a load average of about 2 on the machine
described under [Environment](#environment). `allChecksPassed` was `true`, with all 26 checks passing. The query set
hash was `e91b04045664c48695f34e16bc74e63879ddbe8e11ca4613914538cff9e9060f`, and the threshold was `t = 0.0`. Every
committed aggregate was reproduced exactly, so the committed table needs no change:

| Layer | Recall@1 | Recall@5 | MRR@5 | Coverage@5 | FullEvidence@5 | NoAnswerFPR@5 | Matches committed |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | :--- |
| B0 | 0.470 | 0.818 | 0.682 | 0.818 | 1.000 | 0.800 | yes |
| B1 | 0.500 | 0.795 | 0.638 | 0.818 | 1.000 | 0.800 | yes |
| B2 | 0.152 | 0.598 | 0.351 | 0.682 | 0.333 | 0.800 | yes |

Verdict: `SUPPORTED` for the retrieval claim as written, with no invalid-claim rule firing. The protocol's own rule
(`allChecksPassed`) decides it, and the latency rows play no part in the verdict (IC11).

| Field | Where `html-knowledge-recall` satisfies it |
| :--- | :--- |
| Provenance | The query set is `html-kq/1`, delivered by #107 with independently curated gold labels, and the report records run identity, versions, and hashes |
| Gold labels | Phase JVMs read a labels-free workload; only the orchestrator's `RecallMetrics` reads labels, and a test checks that no gold chunk id or claim leaks into the workload |
| Abstention | `NoAnswerFalsePositiveRate` and `AbstentionRecall` are reported per layer |
| Holdouts | No threshold is tuned (`t = 0.0`). A threshold chosen on this set must be labeled `in-sample (tuned)` |
| Leakage | The labels-free workload test above |
| Outcome separation | Recall@k, MRR, and Coverage measure retrieval. `FullEvidence@5` covers multi-evidence retrieval. Answer quality belongs to `html-knowledge-answers.md`, a separate protocol |
| Protocol verdict rule | `allChecksPassed` and the protocol's semantic checks; latency is excluded (IC11) |
| Replay | Done, see above. Answers (`html-knowledge-answers.md`) and evidence use (`html-knowledge-evidence-trace.md`) were not replayed |

## Cost

Recorded for the adoption decision in #204.

| Item | Cost |
| :--- | :--- |
| JMH replay (6 benchmark-size pairs, 3 forks) | 1 min 47 s wall clock, including the Gradle build |
| `html-knowledge-recall` replay | 6 s wall clock with an up-to-date build |
| `claim-environment.sh` | under 1 s, one short JVM start for the species probe |
| Semantic check and limiter diagnostic | a few seconds each |
| Writing the report | not timed |
| New dependencies, hardware, or providers | none |
