---
name: monada-neuron-executive-summary
description: Use when generating or publishing high-level, non-technical executive summaries in English Markdown for Issues, Pull Requests, milestones, or stakeholder updates in Monada Neuron.
---

# Monada Neuron Executive Summary Skill

## Purpose

This skill guides the generation of clear, high-level, non-technical executive summaries in English Markdown for Monada Neuron.

Use this skill whenever requested to summarize implementations, pull requests, architectural milestones, or issue progress for non-technical stakeholders, product managers, or external audiences.

## Core Principles

1. **Audience-Centric (Non-Technical Focus)**:
   - Write for readers who may not have deep Java or internal class knowledge.
   - Replace low-level class names and internal mechanics (`Node.transition`, `ArrayList`, `ThreadMXBean`, `JMH Level.Iteration`) with intuitive concepts and relatable analogies (e.g., "decision speed", "system dashboard", "wave resonance", "memory footprint", "isolated laboratory").

2. **Always in English**:
   - Deliver the executive summary strictly in English even if the user request was in another language.

3. **Standard Structure & Visual Formatting**:
   - Use GitHub Markdown with emoji section markers to improve visual hierarchy and readability.
   - Use bullet points and bold emphasis for scannability.

## Standard Executive Summary Template

```markdown
## 🌟 Executive Summary: <Title / Milestone / Issue / PR>

### 🎯 What is this about?
<2-3 sentences explaining the high-level context of Monada Neuron and what problem this milestone solves in plain language.>

---

### 💡 Why is this important?
<Explain the tangible value: why stakeholders should care, how it improves reliability, performance, or readiness for future capabilities.>

---

### 🧩 What was built?
- 🧪 **<Key Capability 1>**: <Plain-language description of component or feature.>
- 🌊 **<Key Capability 2>**: <Plain-language description of behavior or benchmark.>
- 🔄 **<Key Capability 3>**: <Plain-language description of lifecycle or workflow.>
- 📊 **<Key Capability 4>**: <Plain-language description of reporting/diagnostics.>

---

### ✅ Key Takeaways & Impact
- **<Value 1>**: <Concrete positive outcome, e.g., zero guesswork, empirical validation.>
- **<Value 2>**: <Quality assurance outcome, e.g., rock-solid test stability, zero memory leaks.>
- **<Value 3>**: <Next steps enabled by this work, e.g., readiness for hardware acceleration.>
```

## Checklist Before Posting

- [ ] Is the summary written entirely in English?
- [ ] Is the tone professional, accessible, and free of unnecessary code jargon?
- [ ] Are key concepts explained with intuitive metaphors or plain business value?
- [ ] Did you verify whether the destination is an **Issue comment** (`gh issue comment <id>`) or a **Pull Request comment** (`gh pr comment <id>`)?
