package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The per-query evidence trace of one experiment arm (Issue #108), as the trace phase JVM records it and the
 * orchestrator scores it. A row holds chunk ids, never atom ids, so rows of different roots stay comparable; a
 * reference the resolver could not resolve appears as {@code ?<reference>}.
 *
 * <p>It separates what the cycle <b>carries</b> (Signals and opaque references, summarized by counts and the resolved
 * chunk ids of the references) from what the external host <b>resolves</b> (chunk ids and digests, see
 * {@link RecalledReferenceResolver}).
 */
final class EvidenceTrace {

    /** Prefix of a reference that did not resolve to a verified chunk. */
    static final String UNRESOLVED_PREFIX = "?";

    private static final String HEADER = String.join("\t", "arm", "queryId", "inputQueryId", "inputSignals",
            "requestMatchesCodec", "recordedStatus", "recorded", "memoryStatus", "admitted", "usedBySignal",
            "attached", "selected", "traceAmbiguous", "stages", "termination", "signalBudgetExhausted",
            "outputSignals", "decision", "contrast", "recalledEqualsB2");
    private static final int COLUMNS = 20;
    private static final String NONE = "-";

    private EvidenceTrace() {
    }

    /** The experiment arms, in the fixed order the trace phase runs them (real port first). */
    enum Arm {
        /** The production adapter over the full reopened root. */
        MEMORY,
        /** A port that answers {@code COMPLETE} with no results (memory disabled). */
        MEMORY_EMPTY,
        /** The full root, with each slot sent the text of another query. */
        SWAPPED,
        /** The production adapter over a root imported without the removed document. */
        REMOVED,
        /** The full root under a signal budget that admits at most one recalled result. */
        TIGHT_BUDGET,
        /** A closed adapter, which answers {@code UNAVAILABLE}. */
        UNAVAILABLE,
        /** A port that throws, which must fail the cycle at {@code MEMORY_RECALL}. */
        THROWING;

        /**
         * Whether this arm recalls through an open production adapter, so its recorded response can be compared with a
         * direct B2 recall of the same request on the same root.
         */
        boolean recallsThroughAdapter() {
            return this == MEMORY || this == SWAPPED || this == REMOVED || this == TIGHT_BUDGET;
        }

        /**
         * Whether the port must answer {@code COMPLETE} in this arm: every adapter arm (the tight budget truncates
         * admission, not the port call) and the memory-empty port. {@link #UNAVAILABLE} must answer
         * {@code UNAVAILABLE}, and {@link #THROWING} records no response.
         */
        boolean expectsCompleteRecall() {
            return recallsThroughAdapter() || this == MEMORY_EMPTY;
        }

        /** The recorded status a trace row of this arm must carry. */
        String expectedRecordedStatus() {
            return switch (this) {
                case UNAVAILABLE -> "UNAVAILABLE";
                case THROWING -> "none";
                default -> "COMPLETE";
            };
        }
    }

    /** One recalled result at the port boundary: its chunk (or unresolved reference) and score. */
    record Hit(String chunkId, double score) {

        Hit {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
        }
    }

    /**
     * The memory-empty contrast of one query ({@code l3-evidence-v1}): the real-port run against the memory-empty run,
     * over the stage results after {@code MEMORY_RECALL} and the cycle output Signals.
     *
     * @param differs any compared component differs
     * @param differsInSignals the output Signals of a post-recall stage or of the cycle differ
     * @param differsInArtifacts a typed artifact (hypotheses, evaluation) of a post-recall stage differs
     * @param firstDifferingStage the first differing post-recall stage kind, or {@code none}
     * @param realOutputSignals cycle output Signal count of the real-port run
     * @param emptyOutputSignals cycle output Signal count of the memory-empty run
     * @param terminationDiffers the two runs terminated differently
     */
    record Contrast(boolean differs, boolean differsInSignals, boolean differsInArtifacts, String firstDifferingStage,
            int realOutputSignals, int emptyOutputSignals, boolean terminationDiffers) {

        Contrast {
            Objects.requireNonNull(firstDifferingStage, "firstDifferingStage must not be null");
        }

        private String render() {
            return String.join(",", Boolean.toString(differs), Boolean.toString(differsInSignals),
                    Boolean.toString(differsInArtifacts), firstDifferingStage, Integer.toString(realOutputSignals),
                    Integer.toString(emptyOutputSignals), Boolean.toString(terminationDiffers));
        }

        private static Contrast parse(String cell) {
            var parts = cell.split(",", -1);
            if (parts.length != 7) {
                throw new IllegalArgumentException("malformed contrast " + cell);
            }
            return new Contrast(bool(parts[0]), bool(parts[1]), bool(parts[2]), parts[3], Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]), bool(parts[6]));
        }
    }

    /**
     * One query through one arm.
     *
     * @param arm the arm
     * @param queryId the slot being traced (the query whose labels score this row)
     * @param inputQueryId the query whose text was sent; differs from {@code queryId} only in {@link Arm#SWAPPED}
     * @param inputSignals the codec Signals of the sent text (the cycle inputs)
     * @param requestMatchesCodec the recorded request Signals equal the codec Signals, with the memory-stage limit
     * @param recordedStatus the port's response status, or {@code none} when no response was recorded
     * @param recorded the port's full response in returned order
     * @param memoryStatus the admitted memory-stage result's status, or {@code none} without a memory result
     * @param admitted chunks of the admitted results, in order
     * @param usedBySignal admitted chunks whose Signal appears in the output of a stage after {@code MEMORY_RECALL}
     * @param attached chunks of every {@code MemoryReferenceEvidence} the reasoning stage attached
     * @param selected chunks of the evidence of the hypotheses the evaluation stage selected
     * @param traceAmbiguous whether the trace key of an admitted Signal is not unique (trace values undefined)
     * @param stages the stage kinds that produced a result, in order
     * @param termination the cycle termination, or {@code FAILED:<stage>} when the cycle threw
     * @param signalBudgetExhausted the cycle snapshot's signal budget flag
     * @param outputSignals the cycle output Signal count
     * @param decision the host decision
     * @param contrast the memory-empty contrast; present only in {@link Arm#MEMORY}
     * @param recalledEqualsB2 whether the recorded response equals a direct B2 recall of the same request on the same
     *     root; present exactly for the arms that {@linkplain Arm#recallsThroughAdapter() recall through the adapter}
     */
    record Row(Arm arm, String queryId, String inputQueryId, int inputSignals, boolean requestMatchesCodec,
            String recordedStatus, List<Hit> recorded, String memoryStatus, List<String> admitted,
            List<String> usedBySignal, List<String> attached, List<String> selected, boolean traceAmbiguous,
            List<String> stages, String termination, boolean signalBudgetExhausted, int outputSignals,
            EvidenceDecision decision, Optional<Contrast> contrast, Optional<Boolean> recalledEqualsB2) {

        Row {
            Objects.requireNonNull(arm, "arm must not be null");
            Objects.requireNonNull(queryId, "queryId must not be null");
            Objects.requireNonNull(inputQueryId, "inputQueryId must not be null");
            Objects.requireNonNull(recordedStatus, "recordedStatus must not be null");
            recorded = List.copyOf(recorded);
            Objects.requireNonNull(memoryStatus, "memoryStatus must not be null");
            admitted = List.copyOf(admitted);
            usedBySignal = List.copyOf(usedBySignal);
            attached = List.copyOf(attached);
            selected = List.copyOf(selected);
            stages = List.copyOf(stages);
            Objects.requireNonNull(termination, "termination must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(contrast, "contrast must not be null");
            Objects.requireNonNull(recalledEqualsB2, "recalledEqualsB2 must not be null");
        }

        /** The used chunks: carried by Signal or attached as evidence, distinct, in admitted order. */
        List<String> used() {
            var used = new HashSet<String>(usedBySignal);
            used.addAll(attached);
            return admitted.stream().filter(used::contains).distinct().toList();
        }

        private String render() {
            return String.join("\t", arm.name(), queryId, inputQueryId, Integer.toString(inputSignals),
                    Boolean.toString(requestMatchesCodec), recordedStatus, hits(recorded), memoryStatus,
                    list(admitted), list(usedBySignal), list(attached), list(selected), Boolean.toString(traceAmbiguous),
                    list(stages), termination, Boolean.toString(signalBudgetExhausted),
                    Integer.toString(outputSignals), decision.name(), contrast.map(Contrast::render).orElse(NONE),
                    recalledEqualsB2.map(String::valueOf).orElse(NONE));
        }
    }

    /**
     * The rows of one trace phase.
     *
     * @param queryHash the {@code queryHash} of the workload the rows answer
     * @param rows rows in arm order, then workload order
     */
    record Snapshot(String queryHash, List<Row> rows) {

        Snapshot {
            Objects.requireNonNull(queryHash, "queryHash must not be null");
            rows = List.copyOf(rows);
        }

        /** Returns the rows of {@code arm}, in workload order. */
        List<Row> rows(Arm arm) {
            return rows.stream().filter(row -> row.arm() == arm).toList();
        }

        /** Returns the row of {@code queryId} in {@code arm}. */
        Optional<Row> row(Arm arm, String queryId) {
            return rows.stream().filter(row -> row.arm() == arm && row.queryId().equals(queryId)).findFirst();
        }

        /** Renders {@code # queryHash}, the header, one line per row and the terminator {@code end\t<rows>}. */
        String toTsv() {
            var tsv = new StringBuilder("# queryHash ").append(queryHash).append('\n').append(HEADER).append('\n');
            rows.forEach(row -> tsv.append(row.render()).append('\n'));
            return tsv.append("end\t").append(rows.size()).append('\n').toString();
        }

        /** Parses {@link #toTsv} output strictly; an empty or truncated file is rejected. */
        static Snapshot parseTsv(String tsv) {
            var lines = tsv.split("\n", -1);
            if (lines.length < 4 || !lines[0].startsWith("# queryHash ") || !lines[1].equals(HEADER)
                    || !lines[lines.length - 1].isEmpty()
                    || !lines[lines.length - 2].equals("end\t" + (lines.length - 4))) {
                throw new IllegalArgumentException("not a complete evidence trace snapshot");
            }
            var rows = new ArrayList<Row>(lines.length - 4);
            for (var i = 2; i < lines.length - 2; i++) {
                var cells = lines[i].split("\t", -1);
                if (cells.length != COLUMNS) {
                    throw new IllegalArgumentException("malformed evidence trace row " + (i + 1));
                }
                rows.add(new Row(Arm.valueOf(cells[0]), cells[1], cells[2], Integer.parseInt(cells[3]),
                        bool(cells[4]), cells[5], parseHits(cells[6]), cells[7], parseList(cells[8]),
                        parseList(cells[9]), parseList(cells[10]), parseList(cells[11]), bool(cells[12]),
                        parseList(cells[13]), cells[14], bool(cells[15]), Integer.parseInt(cells[16]),
                        EvidenceDecision.valueOf(cells[17]),
                        cells[18].equals(NONE) ? Optional.empty() : Optional.of(Contrast.parse(cells[18])),
                        cells[19].equals(NONE) ? Optional.empty() : Optional.of(bool(cells[19]))));
            }
            return new Snapshot(lines[0].substring("# queryHash ".length()), rows);
        }
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? NONE : String.join("|", values);
    }

    private static List<String> parseList(String cell) {
        return cell.equals(NONE) ? List.of() : List.of(cell.split("\\|", -1));
    }

    private static String hits(List<Hit> hits) {
        return list(hits.stream().map(hit -> hit.chunkId() + "@" + Double.toHexString(hit.score())).toList());
    }

    private static List<Hit> parseHits(String cell) {
        return parseList(cell).stream().map(item -> {
            var at = item.lastIndexOf('@');
            if (at <= 0) {
                throw new IllegalArgumentException("malformed hit " + item);
            }
            return new Hit(item.substring(0, at), Double.parseDouble(item.substring(at + 1)));
        }).toList();
    }

    private static boolean bool(String cell) {
        if (!cell.equals("true") && !cell.equals("false")) {
            throw new IllegalArgumentException("not a boolean: " + cell);
        }
        return Boolean.parseBoolean(cell);
    }

    /** Returns whether every element of {@code values} is distinct. */
    static boolean distinct(List<?> values) {
        return new HashSet<>(values).size() == values.size();
    }
}
