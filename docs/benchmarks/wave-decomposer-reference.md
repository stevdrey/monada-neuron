# Reference DFT Wave Decomposer Baseline (Issue #123)

## Question

What does one decomposition by the scalar reference `DftWaveDecomposer` cost, end to end, for the window sizes of
contract `ncm-wave-cognition/1` section 14.1? This is a correctness reference with O(N²) time; the figures are a
baseline for a later optional FFT backend, which must match this reference within a documented tolerance. No speed
claim is made and no dependency is justified by these numbers.

## Workload

- One seeded window (`SplittableRandom(0x5EED_0123)`, samples uniform in `[-10, 10]`), `sampleRate = N` Hz,
  `SampleUnit.HERTZ`, built once per trial.
- Measured operation: `DftWaveDecomposer.decompose(wave)`: sample copy, source energy, twiddle tables, O(N²) sums,
  frame and `SpectralPattern` construction (with its validation and copy). Nothing is cached between calls.
- `N ∈ {8, 64, 256, 1024, 4096}` (4,096 is `SampledWave.MAX_SAMPLES`).

## Command

```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-prof gc DftWaveDecomposerBenchmark"
```

`DftWaveDecomposerBenchmark` (`monada-neuron-evaluation`, `monada.neuron.evaluation.jmh`): average time, 1 fork,
3 warm-up and 5 measurement iterations of 1 s, `-prof gc` for normalized allocation.

## Environment

| Item | Value |
| --- | --- |
| JDK | OpenJDK 27 (Zulu 27.28+101-CA, build 27+35), JMH 1.37 |
| JVM flags | `--add-modules=jdk.incubator.vector` (evaluation module default), default GC and heap |
| OS / architecture | Linux 7.2 (Fedora 44), x86_64 |
| CPU | AMD Ryzen 7 7730U, 16 available processors (the reference is single-threaded) |
| Neuron commit | branch of issue #123, measured before commit (`+dirty`) |

## Results

| N | Bins | Multiply-adds (≈ N²/2) | Time (µs/op) | Allocation (B/op) |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 5 | 32 | 0.54 ± 0.21 | 1,208 |
| 64 | 33 | 2,048 | 7.5 ± 0.8 | 7,480 |
| 256 | 129 | 32,768 | 67 ± 33 | 28,984 |
| 1,024 | 513 | 524,288 | 688 ± 35 | 115,004 |
| 4,096 | 2,049 | 8,388,608 | 13,703 ± 2,023 | 459,151 |

## Reading the results

- **Time** grows towards N²: ×9 to ×10 per ×4 in N from 64 to 1,024, where O(N) work and fixed per-call costs
  are still visible, and ×20 from 1,024 to 4,096, where the samples and the two twiddle tables (96 KiB together)
  probably no longer fit the L1 data cache. That is the expected cost of a direct DFT and the reason the contract
  caps `N` at 4,096.
- **Allocation** is O(N) and deterministic: about 112 bytes per sample. The arrays (sample copy, two twiddle tables,
  four output arrays and the pattern's four copies) are about 48 bytes per sample. The rest, about 32 bytes per
  trigonometric call, is allocated inside `StrictMath.cos` and `StrictMath.sin`: the JDK's Java fdlibm port
  allocates a small argument-reduction buffer for most arguments. The twiddle tables keep that to `2N` calls per
  window instead of `N²`, with the same values the contract formula names.
- The 256 and 4,096 rows have wide error bars on this laptop CPU (frequency scaling); a rerun is expected to vary by
  that much, not by orders of magnitude.

## Limitations

- One machine, one fork; numbers are indicative, not a performance promise.
- No FFT, SIMD, native or GPU path exists, by design (contract section 1.2). Any later backend reports its own
  end-to-end figures and its tolerance against this reference.
