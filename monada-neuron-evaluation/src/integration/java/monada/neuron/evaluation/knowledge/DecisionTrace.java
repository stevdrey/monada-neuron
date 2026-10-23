package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerTrace.CitedSpan;
import monada.neuron.evaluation.knowledge.AnswerTrace.ClaimRow;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The trace of the decision benchmark (Issue #112): one row per case and arm, recording the Store evidence, the Neuron
 * trace where the arm used Neuron, the selector and its exact rule, the decision or abstention, the excluded
 * alternatives and every citation with its verification. It holds no gold label; {@link DecisionMetrics} scores it.
 */
final class DecisionTrace {

    private DecisionTrace() {
    }

    /** The evaluated arms, in run order per case. */
    enum Arm {
        /** Knowledge retrieval only: the Store's top-k for the case query; no decision. */
        STORE("A", "Store retrieval only: MonadaMemory.resonate(query).topK(k); no decision is made"),
        /** The host rules over the raw Store evidence: the matched reference that isolates the Neuron path. */
        STORE_RULES("A+R", "host rule baseline over the raw Store top-k (matched reference for B+R)"),
        /** The host rules over the evidence a Neuron cycle selected. */
        NEURON_RULES("B+R", "Neuron MEMORY_RECALL -> REASONING -> EVALUATION cycle through the production memory port;"
                + " the host rules see only the evidence of a SUPPORTED decision"),
        /** The host rules with no evidence at all: must abstain. */
        NO_EVIDENCE_RULES("R0", "host rule baseline with an empty evidence set (control: must abstain)"),
        /** An optional generator behind the #109 seam, over the Neuron evidence; only when configured. */
        GENERATOR("C", "optional GroundedAnswerGenerator over the B+R evidence; free text, citations scored only");

        private final String label;
        private final String description;

        Arm(String label, String description) {
            this.label = label;
            this.description = description;
        }

        String label() {
            return label;
        }

        String description() {
            return description;
        }
    }

    /** What an arm produced for a case. */
    enum Outcome {
        RETRIEVED,
        DECIDED,
        CONDITIONAL,
        ABSTAINED,
        RECALL_FAILED,
        FREE_TEXT
    }

    /**
     * One evidence hit.
     *
     * @param chunkId the chunk the hit maps to
     * @param rank its 1-based rank
     * @param score the score returned
     */
    record Hit(String chunkId, int rank, double score) {

        String render() {
            return chunkId + "@" + Double.toHexString(score);
        }
    }

    /**
     * What the Neuron cycle did for one case.
     *
     * @param querySignals the number of query Signals the codec emitted
     * @param decision the host-derived evidence decision of the cycle
     * @param admitted the chunks the memory stage admitted, in rank order
     * @param selected the chunks of the evidence the {@code EVALUATION} stage selected
     */
    record NeuronTrace(int querySignals, EvidenceDecision decision, List<String> admitted, List<String> selected) {

        NeuronTrace {
            Objects.requireNonNull(decision, "decision must not be null");
            admitted = List.copyOf(admitted);
            selected = List.copyOf(selected);
        }

        String render() {
            return decision + " signals=" + querySignals + " admitted=" + join(admitted) + " selected="
                    + join(selected);
        }
    }

    /**
     * One row.
     *
     * @param caseId the case
     * @param arm the arm
     * @param selector the selector or generator name, or {@code "-"} for {@link Arm#STORE}
     * @param kind its kind, or {@code "-"}
     * @param evidence the evidence the selector saw (for {@link Arm#STORE}, what the Store returned)
     * @param neuron the Neuron trace, for {@link Arm#NEURON_RULES} and {@link Arm#GENERATOR}
     * @param outcome the outcome
     * @param recommendation the selector's recommendation, for a selector arm that ran
     * @param claims every evidence claim with its verified citations
     * @param exclusionCitations the verified citations of the excluded alternatives
     * @param detail a failure or free-text detail, or {@code "-"}
     */
    record Row(String caseId, Arm arm, String selector, String kind, List<Hit> evidence, Optional<NeuronTrace> neuron,
            Outcome outcome, Optional<DecisionRecommendation> recommendation, List<ClaimRow> claims,
            List<CitedSpan> exclusionCitations, String detail) {

        Row {
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(arm, "arm must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            evidence = List.copyOf(evidence);
            claims = List.copyOf(claims);
            exclusionCitations = List.copyOf(exclusionCitations);
            var expected = recommendation.map(Row::outcomeOf);
            if (expected.isPresent() && expected.get() != outcome) {
                throw new IllegalArgumentException(caseId + " " + arm + ": outcome " + outcome
                        + " does not match its recommendation");
            }
        }

        /** Returns the outcome a recommendation implies. */
        static Outcome outcomeOf(DecisionRecommendation recommendation) {
            return switch (recommendation) {
                case Decided _ -> Outcome.DECIDED;
                case Conditional _ -> Outcome.CONDITIONAL;
                case Abstained _ -> Outcome.ABSTAINED;
            };
        }

        /** Returns every verified citation of the row: claims first, then exclusions. */
        List<CitedSpan> citations() {
            var all = new ArrayList<CitedSpan>();
            claims.forEach(claim -> all.addAll(claim.citations()));
            all.addAll(exclusionCitations);
            return List.copyOf(all);
        }
    }

    /** Header of the rendered trace. */
    static final String HEADER = "caseId\tarm\tselector\tkind\tevidence\tneuron\toutcome\tchoice\talsoValid\toptions"
            + "\texcluded\tconstraintsUsed\tpermissions\tclaims\tcitations\truleId\tdetail";

    /**
     * Renders rows as TSV, one line per row, for inspection and for the run's semantic digest. Free text is escaped as
     * in the workload files; list items are {@code |}-separated and {@code -} is empty.
     */
    static String toTsv(String caseHash, String corpusHash, List<Row> rows) {
        var tsv = new StringBuilder("# caseHash ").append(caseHash).append("\n# corpusHash ").append(corpusHash)
                .append('\n').append(HEADER).append('\n');
        for (var row : rows) {
            var recommendation = row.recommendation();
            tsv.append(String.join("\t", row.caseId(), row.arm().label(), row.selector(), row.kind(),
                    join(row.evidence().stream().map(Hit::render).toList()),
                    row.neuron().map(NeuronTrace::render).orElse("-"), row.outcome().name(),
                    recommendation.map(DecisionTrace::choice).orElse("-"),
                    recommendation.map(value -> value instanceof Decided decided ? join(decided.alsoValid()) : "-")
                            .orElse("-"),
                    recommendation.map(value -> value instanceof Conditional conditional
                            ? join(conditional.options().stream().map(DecisionRecommendation.Option::render).toList())
                            : "-").orElse("-"),
                    recommendation.map(DecisionTrace::excluded).orElse("-"),
                    recommendation.map(DecisionTrace::constraintsUsed).orElse("-"),
                    recommendation.map(value -> value instanceof Decided decided ? join(decided.permissions()) : "-")
                            .orElse("-"),
                    join(row.claims().stream().map(claim -> QueryText.escape(claim.text()).replace("|", "\\p"))
                            .toList()),
                    join(row.citations().stream().map(span -> span.citation().render() + ":"
                            + (span.eligible() ? "eligible" : "ineligible") + (span.exactQuote() ? ":exact" : ""))
                            .toList()),
                    recommendation.map(DecisionRecommendation::ruleId).orElse("-"),
                    QueryText.escape(detail(row)))).append('\n');
        }
        return tsv.append("end\t").append(rows.size()).append('\n').toString();
    }

    /** Returns the decided choice, or {@code "-"}. */
    static String choice(DecisionRecommendation recommendation) {
        return recommendation instanceof Decided decided ? decided.choice() : "-";
    }

    private static String excluded(DecisionRecommendation recommendation) {
        var excluded = switch (recommendation) {
            case Decided decided -> decided.excluded();
            case Conditional conditional -> conditional.excluded();
            case Abstained _ -> List.<DecisionRecommendation.Exclusion>of();
        };
        return join(excluded.stream().map(exclusion -> exclusion.choice() + (exclusion.citations().isEmpty()
                ? ":uncited" : ":cited")).toList());
    }

    private static String constraintsUsed(DecisionRecommendation recommendation) {
        return switch (recommendation) {
            case Decided decided -> join(decided.constraintsUsed());
            case Conditional conditional -> join(conditional.constraintsUsed());
            case Abstained _ -> "-";
        };
    }

    private static String detail(Row row) {
        if (row.recommendation().orElse(null) instanceof Abstained abstained) {
            return abstained.reason() + (abstained.clarifyingQuestion().equals("-") ? ""
                    : "; ask: " + abstained.clarifyingQuestion());
        }
        return row.detail();
    }

    /** Joins with {@code |}; {@code -} for an empty list. */
    static String join(List<String> values) {
        return values.isEmpty() ? "-" : values.stream().collect(Collectors.joining("|"));
    }
}
