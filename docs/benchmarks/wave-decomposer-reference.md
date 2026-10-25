# Reference Wave Decomposer Baselines (Issues #123 and #124)

## Question

What does one decomposition by the scalar reference `DftWaveDecomposer` cost, end to end, for the window sizes of
contract `ncm-wave-cognition/1` section 14.1? This is a correctness reference with O(N²) time; the figures are a
baseline for a later optional FFT backend, which must match this reference within a documented tolerance. No speed
claim is made and no dependency is justified by these numbers.

## Workload

- One seeded window (`SplittableRandom(0x5EED_0123)`, samples uniform in `[-10, 10]`), `sampleRate = N` Hz,
  `SampleUnit.HERTZ`, built once per trial.
- Measured operation: `DftWaveDecomposer.decompose(wave)`: source energy, twiddle tables, O(N²) sums,
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
| Neuron commit | `ad61569` (clean tree); the first baseline was `27df194+dirty` |

## Results

| N | Bins | Multiply-adds (≈ N²/2) | Time (µs/op) | Allocation (B/op) | Allocation at `27df194` (B/op) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 5 | 32 | 0.43 ± 0.34 | 952 | 1,208 |
| 64 | 33 | 2,048 | 4.3 ± 0.1 | 6,104 | 7,480 |
| 256 | 129 | 32,768 | 44 ± 1 | 23,768 | 28,984 |
| 1,024 | 513 | 524,288 | 452 ± 19 | 94,427 | 115,004 |
| 4,096 | 2,049 | 8,388,608 | 10,676 ± 1,067 | 377,116 | 459,151 |

The first baseline (`27df194`) also copied the samples and let the pattern copy the four output arrays again; the
review fixes in `ad61569` removed both copies. Its times (0.54, 7.5, 67, 688 and 13,703 µs/op) were measured in a
noisier session, so the time difference is not attributed to the change.

## Reading the results

- **Time** grows towards N²: ×9 to ×10 per ×4 in N from 64 to 1,024, where O(N) work and fixed per-call costs
  are still visible, and ×20 from 1,024 to 4,096, where the samples and the two twiddle tables (96 KiB together)
  probably no longer fit the L1 data cache. That is the expected cost of a direct DFT and the reason the contract
  caps `N` at 4,096.
- **Allocation** is O(N) and deterministic: about 92 bytes per sample. The arrays (two twiddle tables and the four
  output arrays, which the pattern takes over) are about 28 bytes per sample. The rest, about 32 bytes per
  trigonometric call, is allocated inside `StrictMath.cos` and `StrictMath.sin`: the JDK's Java fdlibm port
  allocates a small argument-reduction buffer for most arguments. The twiddle tables keep that to `2N` calls per
  window instead of `N²`, with the same values the contract formula names.
- Error bars are wide at small N and at 4,096 on this laptop CPU (frequency scaling); a rerun is expected to vary
  by that much, not by orders of magnitude.

## Haar multiresolution strategy (issue #124)

### Question

What does one full-depth `HaarWaveDecomposer` decomposition cost next to the DFT reference at the same fixed `N`
(contract section 5.1 expects O(N)), and does it keep bounded, linear working memory
([ADR 0037](../adr/0037-haar-multiresolution-decomposer.md))?

### Workload and command

- The same seeded window as above (`SplittableRandom(0x5EED_0123)`, `[-10, 10]`, `sampleRate = N` Hz), full depth
  `L = log2 N`, `N ∈ {8, 64, 256, 1024, 4096}`.
- Measured operation: `HaarWaveDecomposer.decompose(wave)`: length check, source energy, the `L` level passes, frame
  and `SpectralPattern` construction (validation and Parseval check). Nothing is cached between calls.
- `HaarWaveDecomposerBenchmark` (same JMH settings as `DftWaveDecomposerBenchmark`), run in the same session as the
  DFT so both rows share one environment:

```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-prof gc WaveDecomposerBenchmark"
```

Environment as in the table above; Neuron commit `d3edc8a+dirty` (the #124 working tree before its first commit).

### Results

| N | Levels | Haar time (µs/op) | Haar allocation (B/op) | DFT time, same session (µs/op) | DFT allocation (B/op) | DFT / Haar time |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 3 | 0.12 ± 0.01 | 408 | 0.38 ± 0.06 | 952 | ×3 |
| 64 | 6 | 0.64 ± 0.05 | 1,976 | 4.7 ± 0.4 | 6,104 | ×7 |
| 256 | 8 | 2.8 ± 0.3 | 7,352 | 47 ± 5 | 23,768 | ×17 |
| 1,024 | 10 | 12.9 ± 1.1 | 28,856 | 490 ± 37 | 94,427 | ×38 |
| 4,096 | 12 | 62 ± 5 | 114,872 | 12,242 ± 2,190 | 377,126 | ×198 |

### Reading the results

- **Time** grows by ×4.5 to ×4.8 per ×4 in `N`: linear, plus fixed per-call costs at small `N`. About 15 ns per
  sample at `N = 4096`, most of it the energy sum, the pattern's validation and its Parseval recomputation, which
  are also O(N). The DFT rows reproduce the earlier baseline within its error bars.
- **Allocation** is O(N) and deterministic: about 28 bytes per sample, exactly the four output arrays (24 bytes per
  coefficient, taken over by the pattern) plus the single `N / 2` working array (4 bytes per sample), and a constant
  for the frame, pattern and result. There is no quadratic buffer and, unlike the DFT, no trigonometric call, so no
  `StrictMath` argument-reduction allocation.
- The two strategies answer different questions (global frequency bins vs scale and position), so the ratio is a
  resource comparison only, not a reason to prefer one; choosing one remains a host decision.

## Limitations

- One machine, one fork; numbers are indicative, not a performance promise.
- No FFT, SIMD, native or GPU path exists, by design (contract section 1.2). Any later backend reports its own
  end-to-end figures and its tolerance against this reference.
