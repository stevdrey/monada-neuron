# Title "x"

**Generated At:** `2026-01-01T00:00:00Z`

## Semantic Checks

Overall: **FAIL**

| Check | Result | Detail |
| :--- | :--- | :--- |
| `c.one` | PASS | detail \| pipe |
| `c.two` | FAIL | line1 line2 "q" |

## Feedback Loop Metadata

| Key | Value |
| :--- | :--- |
| a.key | `quote " and \ backslash	tab` |
| b.key | `value \| with pipe and newline` |

Latency, allocation, and GC values below are machine-specific exploratory diagnostics, not pass/fail gates.

## Run Configuration

| Setting | Value |
| :--- | :--- |
| **Deterministic Seed** | `42` |
| **Execution Mode** | `Quick (Smoke Mode)` |
| **Default Warmup Iterations** | `1` |
| **Default Measurement Iterations** | `3` |
| **Operation Count Semantics** | `ops | per "iteration"` |

## Environment Metadata

| Property | Value |
| :--- | :--- |
| **Java Version** | `27` |
| **Java Vendor** | `Vendor "Q"` |
| **JVM Name** | `VM` |
| **OS** | `Linux (amd64)` |
| **Processors** | `4` |
| **Max Heap** | `1024.00 MB` |
| **Active GCs** | `G1 Young, G1 Old` |

## Workload Benchmark Results

| Benchmark | Scale | Mean Latency | Median (p50) | p95 | p99 | Throughput | Alloc / Op | RSS delta |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |

## Diagnostic Details

