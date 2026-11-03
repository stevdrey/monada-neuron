# ADR 0050: NCM Investment Decision After the Consolidated Ablation

## Status

Accepted (issue #130; Epic #120; closes gate G5 of `ncm-wave-cognition/2` under ADR 0034; keeps ADR 0044's opt-in
stage and ADR 0049's feedback unchanged)

## Context

Contract `ncm-wave-cognition/2` makes the consolidated report of #130 binding for further investment in Next-Concept
Modeling (Section 15, gate G5). The earlier gates left this state:

- G1, G1h, G1s and G4: go.
- G2 (spectral resonance as a pair score): no-go.
- G3 (hierarchical prediction, H3 and H6): no-go.
- H5 (validated feedback): negative.

Issue #130 had to:

- add control D (Haar and coherence features);
- run every available control over the tiny, medium and large workloads, with resource measurements;
- show that numeric prediction does not rely on the Resonance Store;
- publish a go/no-go verdict without enabling NCM by default.

The protocol `ncm-ablation/1` ([protocol](../experiments/ncm-ablation-v1.md)) was pre-registered before any held-out
result. It runs every control on the medium workload, which carries every gate. Tiny and large run subsets: C and D
on both, plus A and B at large. Coherence and feedback are therefore validated at the medium scale only (protocol
coverage matrix). It judges every arm by the registered G3 rule and adds no margin. Its results are in
[ncm-ablation.md](../benchmarks/ncm-ablation.md).

## Decision

1. **The verdict is NO-GO.** No arm passes the registered G3 rule in any repeat. The arms tested were:
   - C: DFT magnitudes and hierarchy;
   - C+coh: C plus phase coherence;
   - D-haar: Haar magnitudes;
   - D-haar+coh: control D;
   - E: validated feedback, which reproduces H5 negative.

   Per component:
   - The hierarchy adds nothing over a flat decision on the same scores: −0.002 to −0.006.
   - Coherence adds nothing: forced accuracy moves by at most ±0.002.
   - Haar features are worse than DFT magnitudes: −0.018 to −0.039 forced accuracy.
   - Abstention is barely informative, and no arm detects noise usefully. C and C+coh abstain less on noise than on
     clean evidence. D-haar never abstains. D-haar+coh abstains on 3 of 2,016 noise windows and on no clean
     window: it meets the direction of the N11 condition, but it fails H3 and H6.
2. **No further investment in the NCM wave pipeline.** That covers optimized transforms, SIMD or accelerator
   backends for it, a Haar-capable core `ConceptModel`, feedback over D (E-D), and default integration. The
   reference implementations stay, as a correctness baseline and for the opt-in stage.
3. **NCM stays an experimental opt-in.** The following remain available to a host that composes them explicitly:
   - `WaveCognitionReasoningStage` (ADR 0044);
   - `ConceptFeedbackPolicy` (ADR 0049);
   - the spectral types of ADR 0035 to ADR 0042.

   Nothing composes them by default. A runtime without them is unchanged (fixture N13).
4. **Control D lives in the evaluation module only.** The core predictor stays DFT-only. The reference scorer
   `FeatureConceptScorer` mirrors the core rules for the ablation, and is verified bit-identical over DFT features. It
   is not a production API.
5. **Re-opening needs new evidence.** Any new NCM investment needs a new issue with its own pre-registered protocol,
   on real, externally labeled signals, beating a simple baseline (B or a flat decision) by a registered margin. Out-of-distribution
   detection (abstaining on noise) is the first gap to address.

## Alternatives Considered

- **Conditional go on the large workload.** C beats A and B there (leaf 0.18, root 0.60), but those figures are
  informational: one seed, no threshold selection. The protocol fixed the medium workload as the quality evidence.
  Promoting an informational figure after the fact would change the rule after seeing results.
- **Tune the Haar arm.** A different selection rule or held-out depth selection might change D's thresholds. That
  would be a new protocol version chosen after seeing the result, and D's forced accuracy, which no threshold
  changes, is already below C's.
- **Build a Haar-capable core model first and then evaluate D.** This was rejected for this issue: it changes a core
  type without evidence. The evaluation-side scorer with a bit-identity check gives the same answer without that
  cost.
- **Remove the experimental code.** Rejected. It is tested, isolated, opt-in and cheap to keep. It documents a
  negative result, and it is the baseline any future protocol would compare against.

## Consequences

- Epic #120 ends with a documented negative result, which contract Section 15 allows.
- The default runtime, `Signal`, `FrequencyState`, `ScalarResonanceMetric` and the stage lifecycle are unchanged.
- The evaluation module gains a reproducible ablation harness: `runNcmAblation`, about 2 minutes. A future protocol
  can reuse it as its baseline.
- Timings show that the reference O(N²) DFT dominates the end-to-end cost (about 91% at N = 256, 95% at N = 4,096).
  Under this decision, that cost is not optimized.

## Follow-Up

- None is opened by this decision. A future proposal must start from Decision item 5.
