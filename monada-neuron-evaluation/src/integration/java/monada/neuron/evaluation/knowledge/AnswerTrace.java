package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The raw L4 rows the answer phase writes and the orchestrator scores (Issue #109). One {@link AnswerRow} per root,
 * evidence layer, {@code answerK}, query and component; the evidence set is recorded as the chunk ids the layer
 * returned, and every citation carries the two mechanical facts the phase verified on the Store-returned text:
 * {@code eligible} (its chunk is in {@code E(q)} and its bounds are valid for that chunk's text) and {@code exactQuote}
 * (the claim text equals the cited text). The rows hold no gold label.
 */
final class AnswerTrace {

    /** Layers an L4 evaluation can bind to. */
    static final List<String> LAYERS = List.of("B1", "B2");

    /** The {@code answerK} values. */
    static final List<Integer> ANSWER_KS = List.of(1, 3, 5);

    private static final String NONE = "-";
    private static final String HEADER = "root\tlayer\tanswerK\tqueryId\tcomponent\tkind\tevidence\toutcome\tdetail"
            + "\tclaim\ttext\tcitations\teligible\texactQuote";
    private static final int COLUMNS = 14;

    private AnswerTrace() {
    }

    /** What happened to one question. */
    enum Outcome {
        /** The component answered with at least one claim. */
        ANSWERED,
        /** The component abstained. */
        ABSTAINED,
        /** The bound recall failed, so the component was not run; never scored. */
        RECALL_FAILED
    }

    /**
     * One cited span with the phase's verification.
     *
     * @param citation the span
     * @param eligible its chunk is in {@code E(q)} and its bounds are valid for that chunk's Store-returned text
     * @param exactQuote the claim text equals the code points the citation covers
     */
    record CitedSpan(Citation citation, boolean eligible, boolean exactQuote) {

        CitedSpan {
            Objects.requireNonNull(citation, "citation must not be null");
            if (exactQuote && !eligible) {
                throw new IllegalArgumentException("an ineligible citation cannot be an exact quote");
            }
        }
    }

    /**
     * One emitted claim.
     *
     * @param text the claim text
     * @param citations its citations, in emitted order
     */
    record ClaimRow(String text, List<CitedSpan> citations) {

        ClaimRow {
            Objects.requireNonNull(text, "text must not be null");
            citations = List.copyOf(citations);
        }
    }

    /**
     * The answer of one component to one query over one evidence set.
     *
     * @param root the Store root ({@code full} or {@code removed})
     * @param layer the evidence layer, {@code B1} or {@code B2}
     * @param answerK the evidence-set size bound
     * @param queryId the query
     * @param component the component name
     * @param kind the component kind
     * @param evidence the chunk ids of {@code E(q)} in rank order
     * @param outcome what happened
     * @param detail the abstention or failure reason, empty for an answer
     * @param claims the claims of an answer, empty otherwise
     */
    record AnswerRow(String root, String layer, int answerK, String queryId, String component,
            AnswerComponent.Kind kind, List<String> evidence, Outcome outcome, String detail, List<ClaimRow> claims) {

        AnswerRow {
            Objects.requireNonNull(root, "root must not be null");
            Objects.requireNonNull(layer, "layer must not be null");
            Objects.requireNonNull(queryId, "queryId must not be null");
            Objects.requireNonNull(component, "component must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            evidence = List.copyOf(evidence);
            claims = List.copyOf(claims);
            if ((outcome == Outcome.ANSWERED) == claims.isEmpty()) {
                throw new IllegalArgumentException(outcome + " row of " + queryId + " has " + claims.size()
                        + " claims");
            }
        }

        /** Returns the citations of every claim, in emitted order. */
        List<CitedSpan> citations() {
            return claims.stream().flatMap(claim -> claim.citations().stream()).toList();
        }

        private List<String> render() {
            var head = List.of(root, layer, Integer.toString(answerK), queryId, component, kind.name(),
                    list(evidence), outcome.name(), QueryText.escape(detail));
            if (claims.isEmpty()) {
                return List.of(String.join("\t", head) + "\t0\t\t" + NONE + "\t" + NONE + "\t" + NONE);
            }
            var lines = new ArrayList<String>();
            for (var index = 0; index < claims.size(); index++) {
                var claim = claims.get(index);
                lines.add(String.join("\t", head) + "\t" + (index + 1) + "\t" + QueryText.escape(claim.text()) + "\t"
                        + list(claim.citations().stream().map(span -> span.citation().render()).toList()) + "\t"
                        + list(claim.citations().stream().map(span -> Boolean.toString(span.eligible())).toList())
                        + "\t"
                        + list(claim.citations().stream().map(span -> Boolean.toString(span.exactQuote())).toList()));
            }
            return lines;
        }
    }

    /**
     * Every answer row of one answer phase.
     *
     * @param queryHash the {@code queryHash} of the workload
     * @param rows the rows, in run order
     */
    record Snapshot(String queryHash, List<AnswerRow> rows) {

        Snapshot {
            Objects.requireNonNull(queryHash, "queryHash must not be null");
            rows = List.copyOf(rows);
        }

        /** Renders {@code # queryHash}, the header, one line per claim (or per claimless row) and {@code end\t<n>}. */
        String toTsv() {
            var tsv = new StringBuilder("# queryHash ").append(queryHash).append('\n').append(HEADER).append('\n');
            var lines = 0;
            for (var row : rows) {
                for (var line : row.render()) {
                    tsv.append(line).append('\n');
                    lines++;
                }
            }
            return tsv.append("end\t").append(lines).append('\n').toString();
        }

        /** Parses {@link #toTsv} output strictly; an empty, truncated or inconsistent file is rejected. */
        static Snapshot parseTsv(String tsv) {
            var lines = tsv.split("\n", -1);
            if (lines.length < 4 || !lines[0].startsWith("# queryHash ") || !lines[1].equals(HEADER)
                    || !lines[lines.length - 1].isEmpty()
                    || !lines[lines.length - 2].equals("end\t" + (lines.length - 4))) {
                throw new IllegalArgumentException("not a complete answer snapshot");
            }
            var rows = new ArrayList<AnswerRow>();
            String[] open = null;
            var claims = new ArrayList<ClaimRow>();
            for (var i = 2; i < lines.length - 2; i++) {
                var cells = lines[i].split("\t", -1);
                if (cells.length != COLUMNS) {
                    throw new IllegalArgumentException("malformed answer row " + (i + 1));
                }
                var claimIndex = Integer.parseInt(cells[9]);
                if (claimIndex <= 1) {
                    if (open != null) {
                        rows.add(row(open, claims));
                    }
                    open = cells;
                    claims = new ArrayList<>();
                } else if (open == null || !Arrays.equals(open, 0, 9, cells, 0, 9)
                        || claimIndex != claims.size() + 1) {
                    throw new IllegalArgumentException("claim " + claimIndex + " on line " + (i + 1)
                            + " does not continue its answer");
                }
                if (claimIndex > 0) {
                    claims.add(claim(i + 1, cells));
                } else if (!cells[10].isEmpty() || !cells[11].equals(NONE)) {
                    throw new IllegalArgumentException("claimless row " + (i + 1) + " carries a claim");
                }
            }
            if (open != null) {
                rows.add(row(open, claims));
            }
            return new Snapshot(lines[0].substring("# queryHash ".length()), rows);
        }

        private static AnswerRow row(String[] cells, List<ClaimRow> claims) {
            return new AnswerRow(cells[0], cells[1], Integer.parseInt(cells[2]), cells[3], cells[4],
                    AnswerComponent.Kind.valueOf(cells[5]), parseList(cells[6]), Outcome.valueOf(cells[7]),
                    QueryText.unescape("detail", cells[8]), claims);
        }

        private static ClaimRow claim(int line, String[] cells) {
            var citations = parseList(cells[11]);
            var eligible = parseList(cells[12]);
            var exact = parseList(cells[13]);
            if (eligible.size() != citations.size() || exact.size() != citations.size()) {
                throw new IllegalArgumentException("citation flags of line " + line + " do not match its citations");
            }
            var spans = new ArrayList<CitedSpan>(citations.size());
            for (var index = 0; index < citations.size(); index++) {
                spans.add(new CitedSpan(Citation.parse(citations.get(index)), bool(eligible.get(index)),
                        bool(exact.get(index))));
            }
            return new ClaimRow(QueryText.unescape("claim of line " + line, cells[10]), spans);
        }
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? NONE : String.join("|", values);
    }

    private static List<String> parseList(String cell) {
        return cell.equals(NONE) ? List.of() : List.of(cell.split("\\|", -1));
    }

    private static boolean bool(String cell) {
        return switch (cell) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("not a boolean: " + cell);
        };
    }
}
