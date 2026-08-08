# Monada Neuron Agent Skills

This directory contains project-specific skills for coding agents working on Monada Neuron.

Use the smallest relevant skill set for the task. `AGENTS.md` remains the repository-wide source of operating rules.

## Skills

| Skill | Use when |
| --- | --- |
| `monada-neuron-agent-task-workflow` | Preparing implementation plans, Issues, PR reviews, or agent-scoped work. |
| `monada-neuron-cognitive-architecture` | Changing Monad, Aeon, Node, Signal, resonance, evolution, action, or adapter boundaries. |
| `monada-neuron-java-26-implementation` | Implementing or reviewing Java 26 code and modern JDK features. |
| `monada-neuron-data-structures-algorithms` | Selecting or changing collections, graph layouts, queues, indexes, numeric buffers, or algorithms. |
| `monada-neuron-performance-optimization` | Optimizing latency, throughput, memory, allocation, locality, or hot loops from a measured baseline. |
| `monada-neuron-concurrency-hardware-acceleration` | Adding concurrency, SIMD, FFM/native memory, shared-memory integration, GPU, or accelerator paths. |
| `monada-neuron-evaluation-benchmarking` | Designing correctness baselines, performance experiments, JMH benchmarks, or regression gates. |

## Skill Combination Examples

A vectorized signal-processing change will normally use:

- `monada-neuron-data-structures-algorithms`;
- `monada-neuron-performance-optimization`;
- `monada-neuron-concurrency-hardware-acceleration`;
- `monada-neuron-java-26-implementation`.

A new Aeon generally starts with:

- `monada-neuron-cognitive-architecture`;
- `monada-neuron-java-26-implementation`;
- `monada-neuron-evaluation-benchmarking`.
