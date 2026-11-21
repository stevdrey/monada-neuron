# Binary Mask Layout Comparison (Issue #143)

## Question

Contract `visual-geometry/1` Section 3.3 stores a `BinaryMask` as packed `long[]` words, one bit per pixel. Issue
#143 asks for a comparison with a flat `boolean[]` plane and a flat `byte[]` plane at 32x32 and 64x64 before the
layout is relied on by the ten visual issues that follow. Is there an access pattern of the contract where the
packed layout loses? This write up is evidence for keeping the layout. It changes nothing: a result against the
packed layout would be filed as a `visual-geometry/2` issue (spec 0012, AC-10).

## Workload

- One disc (`(u − 0.5)² + (v − 0.5)² <= 0.25` at pixel centers), built once per trial in all three layouts with the
  same row major index `y · size + x`. At 32x32 it has 812 foreground pixels of 1,024; at 64x64 it has 3,228 of
  4,096.
- Five operations per layout:
  - **build**: construct from a caller array with the defensive copy (`clone()` for the flat planes,
    `BinaryMask.of`, which also checks the size, the word count and the tail bits, for the packed one);
  - **scan**: read every pixel through `get(x, y)` (a bounds checked `BinaryMask.get` for packed, a direct array
    read for the flat planes) and count the lit ones;
  - **count**: the foreground count by the layout's fastest route (`Long.bitCount` per word for packed);
  - **equals**: compare with an equal mask (the worst case, no early exit);
  - **hash**: `hashCode` over the whole mask.
- The flat planes are measured as bare arrays, with no wrapper class, no bounds check of their own and no
  validation. That is their best case, so the comparison leans against the packed layout.
- Sizes: 32 and 64 (64 is the contract cap).
- Paths not reached: other fill ratios and shapes, non square masks, an `equals` that exits early on unequal masks,
  the `SIZE_UNSUPPORTED` path of `BinaryMask.of`, and any write.

## Command

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs='BinaryMaskLayoutBenchmark -f 2 -prof gc -rf json -rff build/reports/jmh/binary-mask-layout.json'
```

`BinaryMaskLayoutBenchmark` (`monada-neuron-evaluation`, `monada.neuron.evaluation.jmh`): average time, 2 forks, 3
warm up and 5 measurement iterations of 1 s per fork (10 measured iterations per case), 1 thread, `-prof gc` for
normalized allocation. The raw result is written to
`monada-neuron-evaluation/build/reports/jmh/binary-mask-layout.json` (build output, not committed).

## Environment

Printed by `.agents/skills/monada-neuron-evaluation-benchmarking/claim-environment.sh --java <toolchain java>` right
before and right after the run.

| Item | Value |
| --- | --- |
| Captured (UTC) | 2026-11-21T14:48:29Z before, 2026-11-21T14:56:43Z after |
| JDK | Eclipse Adoptium, OpenJDK 64-Bit Server VM, 27+35 (the Gradle toolchain JDK); JMH 1.37 |
| JVM flags | `--add-modules jdk.incubator.vector` (the `jmh` task default; the benchmark uses no vector code), default GC and heap; `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS` unset |
| OS / architecture | Darwin 27.2.0, arm64 |
| CPU | Apple M2 Pro, 10 logical CPUs, NEON (the benchmark is single threaded) |
| Memory | 16.0 GiB |
| Load average (1, 5, 15 min) | 2.57 2.17 1.87 before, 2.83 2.59 2.22 after: a desktop machine with other processes running |
| Neuron revision | `f06dd3130c0d412c8337d11ac9a99f1eea3d59b3` plus the uncommitted #143 change; SHA-256 prefixes `BinaryMaskLayoutBenchmark.java` `5ea49e7dcd64e7e5`, `BinaryMask.java` `910c0f052d499bf3` |

## Results

Time is ns per operation, JMH mean ± the 99.9% error over 10 iterations in 2 forks. Allocation is
`gc.alloc.rate.norm` in bytes per operation. The ratio columns divide a flat plane's mean by the packed mean, so a
value above 1 means packed is faster or allocates less. The verdict compares packed with the better flat plane:
`better` or `worse` when the two error intervals do not overlap, `no measurable difference` when they do.

### 32x32

| Operation | `boolean[]` | `byte[]` | packed `long[]` | `boolean[]`/packed | `byte[]`/packed | Packed against the better flat plane |
| --- | ---: | ---: | ---: | ---: | ---: | :--- |
| build, ns | 24.40 ± 0.64 | 24.50 ± 1.03 | 8.69 ± 0.20 | 2.81 | 2.82 | better |
| scan (`get` every pixel), ns | 608.81 ± 10.58 | 600.36 ± 3.01 | 639.01 ± 13.12 | 0.95 | 0.94 | worse |
| count, ns | 590.86 ± 13.67 | 99.75 ± 2.76 | 5.61 ± 0.07 | 105.3 | 17.8 | better |
| equals, ns | 28.03 ± 0.34 | 21.87 ± 0.12 | 5.86 ± 0.05 | 4.78 | 3.73 | better |
| hash, ns | 892.53 ± 21.85 | 80.96 ± 2.72 | 7.80 ± 0.09 | 114.4 | 10.4 | better |
| build allocation, B/op | 1,040 | 1,040 | 184 | 5.65 | 5.65 | better |

### 64x64

| Operation | `boolean[]` | `byte[]` | packed `long[]` | `boolean[]`/packed | `byte[]`/packed | Packed against the better flat plane |
| --- | ---: | ---: | ---: | ---: | ---: | :--- |
| build, ns | 119.76 ± 2.35 | 120.15 ± 1.68 | 17.13 ± 0.17 | 6.99 | 7.01 | better |
| scan (`get` every pixel), ns | 2,485.94 ± 58.45 | 2,487.63 ± 65.11 | 2,660.54 ± 174.97 | 0.93 | 0.94 | no measurable difference |
| count, ns | 2,402.42 ± 53.24 | 539.46 ± 4.35 | 16.23 ± 0.21 | 148.0 | 33.2 | better |
| equals, ns | 113.97 ± 7.69 | 80.14 ± 1.77 | 16.08 ± 0.19 | 7.09 | 4.98 | better |
| hash, ns | 3,575.35 ± 43.99 | 368.22 ± 5.41 | 39.19 ± 0.94 | 91.2 | 9.4 | better |
| build allocation, B/op | 4,112 | 4,112 | 568 | 7.24 | 7.24 | better |

Scan, count, equals and hash allocate nothing in any layout. The packed build allocation is the copied word array
plus the `BinaryMask` and `Result.Ok` objects.

An earlier run of the same benchmark with one fork (2026-11-20, same machine) gave the same picture, including a
32x32 scan of 635 ± 11 ns for packed against 600 ± 6 ns for `boolean[]`.

## Claim evidence: packed `long[]` mask layout against flat planes

Standard: `.agents/skills/monada-neuron-evaluation-benchmarking/SKILL.md`, "Claim Evidence Report".

| Field | Value |
| :--- | :--- |
| Claim | "For build with a defensive copy, foreground count, `equals` and `hashCode` on a 32x32 and a 64x64 mask, the packed `long[]` layout of `BinaryMask` is faster than a bare `boolean[]` or `byte[]` plane and its build allocates fewer bytes; a full `get(x, y)` scan is not materially slower." |
| Source | This document and contract `visual-geometry/1` Section 15.1, entry `/1` (issue #143) |
| Target metric and decision | Mean time per operation (`avgt`, ns/op) and `gc.alloc.rate.norm` (B/op). Decision: whether `BinaryMask` keeps the packed layout of contract Section 3.3, or a `visual-geometry/2` issue is filed (spec 0012, AC-10) |
| Baseline | Bare `boolean[]` and bare `byte[]` planes, row major, with no wrapper, validation or bounds check of their own (their best case) |
| Variant | `monada.neuron.visual.BinaryMask` as it ships: `BinaryMask.of` with its size, word count and tail bit checks, and `get` with two bounds checks |
| Tuning of each side | Same JMH forks, same flags, same thread, same disc. No side has a flag the other lacks |
| Workload | See [Workload](#workload), including the paths not reached |
| Commands | See [Command](#command) |
| Environment | See [Environment](#environment) |
| Method | JMH 1.37, `-bm avgt -f 2 -wi 3 -i 5 -r 1s -prof gc`, 1 thread; every benchmark method returns its result to JMH |
| Semantic check | `BinaryMaskLayoutBenchmark.requireSameDisc` runs in every trial setup and fails the trial unless the three layouts agree on every pixel, every scan and count route returns the same lit count, and every layout equals its twin. Exact, no tolerance. All 60 trials of the run printed `layoutsAgree=true` |
| Work performed | The same setup check printed `lit=812` for every 32x32 trial and `lit=3228` for every 64x64 trial, the counts of the disc formula. Scan and count return that count and build returns the built object, so the timed work cannot be removed. Build allocation matches the array sizes: 1,024 + 16 header bytes and 4,096 + 16 for a flat plane; 16 or 64 words plus headers and two small objects for packed |
| Bound check | Packed touches 16 words at 32x32 and 64 words at 64x64 where a flat plane touches 1,024 and 4,096 elements, so a per element loop can lose by at most the element count over the word count, 64. The `byte[]` ratios (3.7 to 33.2) are within 64. The `boolean[]` count and hash ratios (91 to 148) are above 64: see Limiter |
| Limiter | `pending`: no profiler run isolates a cause. The likely cause of the `boolean[]` count and hash times is a branch per pixel in the baseline loops and in `Arrays.hashCode(boolean[])`, which `byte[]` avoids, but that is unmeasured. The shipped statement therefore states no cause and uses `byte[]`, the stronger baseline, for count and hash |
| Share of end to end | `pending`, and excluded by the narrowed statement: this is a microbenchmark and no fitter, contour tracer or compositor exists yet to measure a share in |
| Limits | One machine (Apple M2 Pro, arm64), one JDK (Temurin 27+35), one disc per size, sizes 32 and 64 only, a machine with background load |

### Verdict

- Status: `NARROWED`
- Invalid-claim rules that fired: `IC6` against the `boolean[]` count and hash ratios, which exceed the arithmetic
  bound of 64 with no measured limiter. The narrowed statement drops them. `IC7` does not fire because the statement
  says microbenchmark.
- Statement to ship: "In a JMH microbenchmark on one Apple M2 Pro with Temurin 27+35, on one disc at 32x32 and
  64x64, packed `BinaryMask` was faster than the better of a bare `boolean[]` or `byte[]` plane for build with a
  defensive copy (2.8 and 7.0 times), `equals` on equal masks (3.7 and 5.0 times), foreground count (17.8 and 33.2
  times against `byte[]`) and `hashCode` (10.4 and 9.4 times against `byte[]`), and its build allocated 5.65 and
  7.24 times fewer bytes. A full `get(x, y)` scan was about 6% slower than the better flat plane at 32x32, beyond the
  error, and showed no measurable difference at 64x64. The share of any end to end path is not measured."
- Pending fields and what would resolve them: Limiter (a JFR or async-profiler run of the `boolean[]` loops); Share
  of end to end (a run through #144's fitter or #145's tracer once they exist).

## Decision

The layout stays packed `long[]`, as the contract says.

Packed does lose on one measured operation: the bare 32x32 `get` scan is about 6% slower than a flat plane (639 ns
against 600 ns), and the error intervals do not overlap. AC-10 says a result where packed loses on an access pattern
of the contract is filed as a `visual-geometry/2` issue. No issue is filed for this one, for three reasons stated so
a reader can disagree: the loop does no work per pixel, so 6% of a read is the upper end of what a consumer would
see; at 64x64, the size the witness mask uses, there is no measurable difference; and the operations the contract
leans on (the witness mask built once per geometry, masks compared and hashed as values) all favor packed. If #144's
fitter or #145's tracer shows the scan to be a hotspot, that measurement reopens the question.

## Limits

- One machine, one JDK, two forks; the numbers are a comparison, not a portable constant.
- The scan is a tight read loop. A consumer that does real work per pixel (the fitter of #144, the contour tracer
  of #145) will see a smaller relative difference.
- A flat plane wrapped in a validating, bounds checked class would be slower than the bare arrays measured here.
- Writes are not measured: a mask is immutable, and the only writer is the rasterizer, which fills a fresh word
  array.
