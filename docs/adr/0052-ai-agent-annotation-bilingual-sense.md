# ADR 0052: AI-Agent Annotation for the Bilingual Sense Protocol

## Status

Accepted (issue #188; Epic #182; amends ADR 0051; ADR 0050 unchanged and binding)

## Context

ADR 0051 accepted `bilingual-sense/1`, which needs two independent bilingual human annotators and a human
adjudicator, and rejected machine or LLM labels as a replacement for scarce annotators. No human annotators can be
engaged. Under v1's staffing condition both directions would stop as a data no-go.

The project owner asked for a protocol in which distinct AI agents annotate and adjudicate. The corpus snapshot had
already been downloaded (step 2 started), but nothing had been counted, split or labeled and no system had read the
held-out split. Epic #182 states that "a human-annotated label or validated source supplies meaning", so the change
conflicts with both ADR 0051 and the epic unless the evidence the labels can support is redefined explicitly.

## Decision

1. **`bilingual-sense/2`** ([bilingual-sense-v2.md](../experiments/bilingual-sense-v2.md)) replaces v1's human
   annotation with two AI annotators from different model families and an AI adjudicator, with locked source-only
   pass A, diagnostic pass B, two-call source-first adjudication enforced by a deterministic orchestrator, frozen
   prompts and output schema, a configuration commit before calibration, a hash-chained raw-output log, sealed
   held-out outputs and fixed retry rules. Every other v1 rule, including the split key, gates, arms and margins,
   is kept.
2. **Agent labels are a distinct provenance class, `agent-gold`.** They are never called human annotation. Inter-agent
   agreement is reported as consistency, not correctness.
3. **Results are exploratory.** Every verdict carries a label status, `agent-labeled` or, after a pre-declared owner
   audit passes, `agent-labeled, audited`. A GO is an *exploratory* GO; no result is independently human-validated
   evidence, and an audited one is not human-equivalent.
4. **Epic #182's acceptance is amended**, not silently relaxed: an exploratory decision on `agent-gold` can close the
   epic, and the decision must state that no human-validated evidence was produced. Sentence translation stays closed
   for the epic unless a later version adds human-annotated held-out data and both directions are GO on it.
5. **Ownership is unchanged.** Agents, orchestrator and logs are offline tooling outside the Gradle build. No
   provider SDK, client or model dependency enters any Neuron module; the runtime, `Signal`, defaults and memory
   ownership are unchanged. Nothing here bears on ADR 0050 or establishes semantic grounding.

## Alternatives Considered

- **Keep v1 and stop with a data no-go.** Honest but uninformative; the owner prefers exploratory evidence clearly
  labeled as such.
- **Agents from one provider in separate sessions.** Rejected: sessions of one model share its errors; v2 requires
  different families and still discloses residual correlation.
- **Treat agent labels as equivalent to human annotation.** Rejected: there is no reference showing it, and it would
  misstate the evidence.
- **The owner as sole human annotator.** Rejected: one annotator cannot give agreement or adjudication, and the owner
  designed the protocol. The owner may instead audit a sample, which bounds but does not validate label quality.
- **Allow agents tools or dictionary lookups.** Rejected: unlogged sources and non-reproducible calls.

## Consequences

- ADR 0051's rejection of machine labels no longer governs annotation; its other decisions stand.
- #185 may resume only under the prerequisites in v2's
  [effect on #185](../experiments/bilingual-sense-v2.md#effect-on-185-and-epic-182), and reports label status in its
  title and summary.
- A GO supports further research only under a protocol with human-annotated held-out data; a NO-GO is conditioned on
  agent labels.
- The held-out split remains single-use; once read at step 8 it cannot be reused by a human-annotated version.

## Follow-Up

- Configuration commit registering A1, A2 and J before step 4.
- Steps 2 to 7 under v2, recorded in the feasibility report; then #185.
- Update Epic #182 and #185 to reference this decision.
