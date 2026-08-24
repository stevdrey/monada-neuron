# ADR 0013: Java 26 Vector API Batch Resonance Backend

## Status

Accepted

## Context

Resonance scoring between `FrequencyState` pairs is a core computational operation in Monada Neuron (defined in [ADR 0006](0006-deterministic-scalar-resonance-metric.md)). The portable reference `ScalarResonanceMetric` computes normalized resonance as:

```text
score = amplitudeSimilarity * frequencySimilarity * phaseSimilarity
```

Issue #14 and [ADR 0003](0003-performance-first-java26-and-heterogeneous-compute.md) identified batch resonance evaluation as a primary candidate for CPU SIMD optimization. Baseline measurements recorded ~68–103 ns per single score and ~7.1M pairs/second in sequential scalar loops over 100,000 pairs.

Because resonance computation is purely mathematical and element-wise independent, batch evaluation can benefit substantially from CPU vector registers (AVX2, AVX-512, ARM Neon) via the Java 26 Vector API (`jdk.incubator.vector`).

## Decision

1. **Batch Contract & Data Layout**:
   - Define `BatchResonanceEvaluator` as the primary bulk scoring contract.
   - Introduce `FrequencyStateBatch` as a contiguous, immutable Structure-of-Arrays (SoA) batch container (`double[] amplitudes, frequencies, phases`).
   - Support both direct primitive contiguous arrays (SoA) and Object arrays (`FrequencyState[]`) without exposing Vector API types to public cognitive contracts.

2. **Portable Reference & Oracle**:
   - Provide `ScalarBatchResonanceEvaluator` as the deterministic portable reference implementation with zero incubator dependencies.
   - Preserves numerical equivalence with `ScalarResonanceMetric` across all valid states and edge cases.

3. **Vector API SIMD Backend**:
   - Implement `VectorBatchResonanceEvaluator` using `jdk.incubator.vector.DoubleVector` and `DoubleVector.SPECIES_PREFERRED`.
   - Vectorize amplitude similarity via boolean zero-masking and `min.div(max)`.
   - Vectorize frequency similarity via equality and single-zero XOR masks.
   - Vectorize phase wrapping modulo $2\pi$ via IEEE 754 round-to-nearest arithmetic and compute cosine similarity via `VectorOperators.COS`.
   - Protect numerical equivalence on large phase values ($|p| > 1000.0$) through automatic scalar lane reduction via `StrictMath.IEEEremainder` to avoid floating-point multiplication precision loss with $1/(2\pi)$ and preserve tolerance $\le 1.0\times 10^{-12}$ up to `Double.MAX_VALUE`.
   - Process tail elements ($length \pmod{vectorWidth}$) deterministically via the scalar oracle formula.

4. **Capability Detection & Adaptive Fallback**:
   - Implement `AdaptiveBatchResonanceEvaluator` as the default system evaluator (`BatchResonanceEvaluator.defaultEvaluator()`).
   - Dynamically checks `VectorBatchResonanceEvaluator.isAvailable()`.
   - Dispatches workloads with $batchSize \ge 4$ pairs to the Vector API backend and routes smaller workloads or environments lacking incubator flags to `ScalarBatchResonanceEvaluator`.

## Empirical Evidence

The review-before-merge study was executed on Linux x86_64 with Java 26
(Temurin-26+35), Intel Core i7-6500U (AVX2), four available CPUs, and
`DoubleVector.SPECIES_PREFERRED` length `4`. It used one JMH thread, two
one-second warmups, three one-second measurement iterations, three forks,
`avgt`, and `-prof gc`. `docs/benchmarks/baseline-methodology.md` records the
reproducible commands and profiler evidence.

The table gives mean batch latency in ns and JMH `gc.alloc.rate.norm` in bytes
per pair. Adaptive results were measured after setting its threshold to `4`.

| Pairs | Scalar ns / B-pair | Vector ns / B-pair | Adaptive ns / B-pair |
| :--- | ---: | ---: | ---: |
| 1 | 94.05 / 32.000658 | 92.11 / 32.000642 | 90.97 / 32.000637 |
| 4 | 319.97 / 32.000558 | 77.09 / 0.000134 | 85.44 / 0.000149 |
| 8 | 644.04 / 32.000562 | 127.65 / 0.000111 | 131.41 / 0.000114 |
| 16 | 1,290.07 / 32.000563 | 222.93 / 0.000097 | 219.49 / 0.000095 |
| 32 | 3,310.88 / 32.000722 | 424.88 / 0.000093 | 429.79 / 0.000093 |
| 64 | 6,859.80 / 32.000750 | 904.86 / 0.000098 | 789.44 / 0.000086 |
| 128 | 23,883.53 / 32.001301 | 1,606.12 / 0.000087 | 1,598.69 / 0.000087 |
| 256 | 16,402.92 / 32.000451 | 3,308.66 / 0.000090 | 3,193.23 / 0.000087 |
| 1,000 | 78,002.71 / 32.000547 | 12,620.64 / 0.000088 | 12,641.00 / 0.000089 |
| 10,000 | 956,206.48 / 32.000670 | 142,138.04 / 0.000099 | 122,491.23 / 0.000085 |
| 100,000 | 9,287,027.59 / 32.000650 | 1,405,598.31 / 0.000098 | 1,383,684.78 / 0.000096 |

The allocation gate is satisfied at every representative size: at 1,000,
10,000, and 100,000 pairs, Vector and Adaptive are below `0.0001 B/pair`,
well within the required 105% of Scalar's approximately `32.0006 B/pair`.
An allocation-enabled JFR capture of Vector SoA at 100,000 pairs recorded only
JFR/JMH support allocations (two `byte[]` drain buffers, one `char[]`, and one
JFR `StringPool` table); it recorded no allocation stack in Monada code or the
Vector API hot path.

The minimum tested size where Vector was at least 5% faster than Scalar in
each of the three forks, and remained so for every larger tested size, was
four pairs. At one pair, one fork was slower than Scalar, so it is not a valid
crossover. `DEFAULT_CROSSOVER_THRESHOLD` is therefore `4`.

### Equivalence & Precision

- Numerical equivalence verified across deterministic and randomized finite inputs with tolerance $\le 1.0\times 10^{-12}$.
- Full boundary test coverage for zero amplitude, zero frequency, wrapped phase ($0, \pi/2, \pi, 2\pi$), extreme phase values (`Double.MAX_VALUE`), and odd tail sizes.

## Consequences

- The SIMD backend is retained: it satisfies the pre-merge allocation gate and preserves scalar-oracle equivalence.
- On the representative 1,000 to 100,000-pair SoA workloads, Vector completes batches in approximately 1.4 ms or less and is 6.2x to 6.7x faster than Scalar in this study.
- Incubator module dependency is isolated: callers running without `--add-modules jdk.incubator.vector` continue executing portably via the scalar backend.
- Core domain model remains independent of hardware-specific types.

## Alternatives Considered

- **Retain only the scalar batch evaluator:** rejected because the measured 1,000-plus pair
  workloads exceed the Issue #25 throughput hypothesis while preserving the scalar implementation
  as the semantic oracle and fallback.
- **Use only `FrequencyState[]` object arrays:** rejected for the accelerated hot path because
  object indirection and field extraction reduce locality and add avoidable overhead compared with
  the contiguous SoA representation. The batch contract still accepts object arrays for callers
  that already hold that layout.
- **Use native or GPU acceleration:** rejected for this decision because it would add deployment,
  transfer, capability-detection, and ownership complexity beyond the CPU SIMD experiment. Any
  later native or accelerator path must retain the scalar reference and establish its own boundary.

## Follow-up Work

- Revalidate numerical equivalence, vector availability, crossover behavior, allocation evidence, and JMH evidence when
  upgrading the JDK or when the incubating Vector API changes status, packaging, or semantics.
- Migrate the isolated backend and its module configuration if the Vector API becomes standard or
  changes incompatibly; keep `ScalarBatchResonanceEvaluator` available throughout that migration.
- Remove the Vector API backend, its experimental configuration, and benchmark claims if it no
  longer provides reproducible end-to-end benefit on supported hardware, without changing the
  scalar batch contract or resonance formula.
