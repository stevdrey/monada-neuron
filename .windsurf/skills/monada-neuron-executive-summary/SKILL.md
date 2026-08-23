---
name: monada-neuron-executive-summary
description: Use when generating or publishing high-level, non-technical executive summaries in English Markdown for GitHub Issues in Monada Neuron.
---

# Monada Neuron Executive Summary Skill

## Purpose

This skill guides the generation of clear, high-level, non-technical executive summaries in English Markdown specifically for **GitHub Issues** in Monada Neuron.

Use this skill whenever requested to summarize implementation progress, completed deliverables, architectural outcomes, or milestones directly on a GitHub Issue for stakeholders, product managers, and non-technical audiences.

## Core Principles

1. **Issue-Centric Scope**:
   - This summary is strictly meant for **GitHub Issues** (to communicate outcomes to issue creators and stakeholders), not for Pull Requests (which use standard developer PR review workflows).
   - When posting via GitHub CLI, always target the issue comment command:
     ```bash
     gh issue comment <issue-number> --body "<markdown-summary>"
     ```

2. **Audience-Centric (Non-Technical Focus)**:
   - Write for readers who may not have deep Java or internal class knowledge.
   - Replace low-level class names and internal mechanics (`Node.transition`, `ArrayList`, `ThreadMXBean`, `JMH Level.Iteration`) with intuitive concepts and relatable analogies (e.g., "decision speed", "system dashboard", "wave resonance", "memory footprint", "isolated laboratory").

3. **Always in English**:
   - Deliver the executive summary strictly in English even if the user request was in another language.

4. **Standard Structure & Visual Formatting**:
   - Use GitHub Markdown with emoji section markers to improve visual hierarchy and readability.
   - Use bullet points and bold emphasis for scannability.

## Standard Executive Summary Template

```markdown
## 🌟 Executive Summary: <Title / Milestone / Issue #N>

### 🎯 What is this issue about?
<2-3 sentences explaining the high-level context of Monada Neuron and what problem this issue resolves in plain language.>

---

### 💡 Why is this important?
<Explain the tangible value: why stakeholders should care, how it improves reliability, performance, or readiness for future capabilities.>

---

### 🧩 What was delivered?
- 🧪 **<Key Capability 1>**: <Plain-language description of delivered capability.>
- 🌊 **<Key Capability 2>**: <Plain-language description of behavior or benchmark.>
- 🔄 **<Key Capability 3>**: <Plain-language description of lifecycle or workflow.>
- 📊 **<Key Capability 4>**: <Plain-language description of reporting/diagnostics.>

---

### ✅ Key Takeaways & Business Value
- **<Value 1>**: <Concrete positive outcome, e.g., zero guesswork, empirical validation.>
- **<Value 2>**: <Quality assurance outcome, e.g., rock-solid test stability, zero memory leaks.>
- **<Value 3>**: <Next steps enabled by this work, e.g., readiness for hardware acceleration.>
```

## Checklist Before Posting

- [ ] Is the summary written entirely in English?
- [ ] Is the tone professional, accessible, and free of unnecessary code jargon?
- [ ] Are key concepts explained with intuitive metaphors or plain business value?
- [ ] Did you verify that the target is a **GitHub Issue** (`gh issue comment <issue-number>`)?
