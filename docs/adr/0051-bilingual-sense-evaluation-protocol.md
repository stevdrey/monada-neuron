# ADR 0051: Pre-Registered Bilingual Sense Evaluation Protocol and Its Boundaries

## Status

Accepted (issue #184; Epic #182; follows the feasibility report of #183; ADR 0050 unchanged and binding)

## Context

Epic #182 researches a bounded language task: selecting an English or Spanish expression for an ambiguous word in
context, or abstaining. The feasibility report ([bilingual-sense-feasibility.md](../experiments/bilingual-sense-feasibility.md))
found no openly redistributable, human-annotated dataset for both directions, so the evaluation needs new annotation
over Tatoeba and Europarl text. It left the data, partition and evaluation rules to a preregistration that must be
frozen before any corpus is downloaded or labeled.

Three choices outlive one experiment: where the evaluation lives, how an optional Wave LLM Demo representation relates
to ADR 0050, and how a result may or may not lead to sentence translation.

## Decision

1. **The protocol is `bilingual-sense/1`** ([bilingual-sense-v1.md](../experiments/bilingual-sense-v1.md)). It freezes
   sources and checksum policy, lemma candidates and forms, the inventory freeze rule, family and near-duplicate
   rules, keyed-hash split assignment with a calibration reserve, annotation caps, data gates, arms and grids, one
   validation-only selection rule, metrics, margins, checks, resource limits and a per-direction verdict rule.
   Changing any of them after the count audit starts is a new protocol version, and the held-out split is single-use.
2. **Evaluation-only ownership.** Harness, arms and checks belong to `monada-neuron-evaluation`. The Neuron candidate
   reproduces the evidence formula of `ReferenceHypothesisEvaluationPolicy` and is verified bit-identical against it;
   no core type is changed or repurposed. Core gains no type, dependency, `Signal` change or default. Corpora, annotation tools and aligners stay
   offline tooling. The annotated dataset is evaluation data, not cognitive memory; persistent memory stays with
   Monada Resonance Store.
3. **The wave arm is optional and cannot satisfy ADR 0050.** A token-id waveform is a derived encoding of the same
   text. The protocol documents its 455-id collisions, vocabulary-order dependence, rank bound and shared unknown wave,
   and compares it against bucket and bag-of-words controls with the same classifier. No result of it, positive or
   negative, counts as a "real, externally labeled signal" or re-opens the NCM wave pipeline. It never depends on DJL,
   PyTorch or a wave-llm-demo artifact.
4. **Directions are separate decisions.** `EN→ES` and `ES→EN` are gated, scored and decided independently and never
   pooled.
5. **Sentence translation needs its own decision.** It may be proposed only after both directions are GO, as a new
   issue with its own protocol, data and ADR. A NO-GO or NOT TESTABLE in either direction closes it for Epic #182.

## Alternatives Considered

- **Use an existing benchmark as held-out evidence.** Rejected by the feasibility audit: `ES→EN` has only automatic
  labels, and the `EN→ES` sets are small, closed or of unknown terms.
- **Random sentence-level splits.** Rejected: Tatoeba translation clusters and Europarl sessions repeat context and
  wording, so sentence splits would leak.
- **Select thresholds on the held-out split, or report the best arm afterwards.** Rejected: it makes the margin
  meaningless. All selection uses validation, with one rule for every arm.
- **Make the wave arm required, or run it through wave-llm-demo's neural classifier.** Rejected: it would add an
  external ML dependency and confound representation with model capacity. The re-implemented encoding with matched
  controls isolates the representation.
- **Machine or LLM labels to replace scarce annotators.** Rejected: meaning must come from human annotation; without
  the staffing the protocol requires, a direction is a data no-go.

## Consequences

- #185 runs only the order registered in the feasibility report, with the gates of the protocol. It may stop with a data
  no-go before any system is run; that is an accepted outcome.
- The default runtime, `Signal`, the hypothesis policy and the opt-in NCM stage are unchanged.
- A GO in a direction supports further research on that direction only; it enables nothing by default.

## Follow-Up

- #185: count audit, splits, annotation and gates; then, only if they pass, the comparison and report.
