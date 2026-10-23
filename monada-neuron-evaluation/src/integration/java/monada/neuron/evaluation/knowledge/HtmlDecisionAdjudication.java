package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The independent adjudication {@value #VERSION} of the {@value HtmlDecisionGold#VERSION} labels (Issue #152): for
 * every case and every judged {@link Item}, a verdict {@code agree} or {@code change} with a reason and the reviewer.
 * The reviewer must not be the author of {@value DecisionRuleBaseline#VERSION} nor a drafter of the labels, so the
 * labels are no longer judged only by the side that wrote the rules.
 *
 * <p>Format: comment lines, the header {@value #HEADER}, then one row per case and item. {@code proposed} is {@code -}
 * for {@code agree}; for {@code change} it is the new value in the gold column's own syntax, except that a changed
 * {@code evidenceSpans} lists {@code chunkId "exact quote"} items (a quote holds no {@code |}): each quote must occur exactly once in its
 * normalized chunk and is resolved here to {@code chunkId start end sha256}, so the reviewer never counts offsets.
 *
 * <p>A malformed row, an unknown case or item, a repeated row, a no-op change or a revision that breaks a gold
 * invariant throws {@link CorpusValidationException}. A missing row or a reviewer who is not independent is reported
 * through {@link #missing()} and {@link #dependentReviewers()}, which the runner turns into failing checks. Only the
 * runner and the worksheet read this class; no selector, experiment or trace type references it.
 */
final class HtmlDecisionAdjudication {

    /** Version of the bundled adjudication. */
    static final String VERSION = "html-decision-v1-adjudication";

    static final String HEADER = "caseId\titem\tverdict\tproposed\treason\treviewer";

    /**
     * The recorded author of {@value DecisionRuleBaseline#VERSION} (Issue #112). Kept here, not in the rule class, so
     * the rule table and its version stay untouched.
     */
    static final String RULE_AUTHOR = "claude-opus-5-5";

    static final String FILE = "adjudication.tsv";
    private static final String RESOURCE = "/" + HtmlDecisionCases.VERSION + "/" + FILE;
    private static final String AGREE = "agree";
    private static final String CHANGE = "change";
    private static final String ABSENT = "-";
    private static final int REVIEWER_COLUMN = 8;
    private static final int DRAFTED_BY_COLUMN = 9;

    /** A judged part of a gold label, with its {@code gold.tsv} column. */
    enum Item {
        EXPECTED("expected", 1),
        ACCEPTABLE("acceptable", 2),
        INELIGIBLE("ineligible", 3),
        ASSUMPTIONS("assumptions", 4),
        PERMISSIONS("permissions", 5),
        RATIONALE("rationale", 6),
        EVIDENCE_SPANS("evidenceSpans", 7);

        private final String label;
        private final int column;

        Item(String label, int column) {
            this.label = label;
            this.column = column;
        }

        String label() {
            return label;
        }

        /** Returns whether {@code -} is a valid changed value: the empty list. */
        boolean isList() {
            return this == ACCEPTABLE || this == INELIGIBLE || this == ASSUMPTIONS;
        }
    }

    /**
     * One recorded verdict.
     *
     * @param caseId the case
     * @param item the judged item
     * @param change whether the reviewer changes the label
     * @param proposed the new value in gold syntax (spans resolved), or {@code -} when agreeing
     * @param reason why
     * @param reviewer the independent reviewer
     */
    record Verdict(String caseId, Item item, boolean change, String proposed, String reason, String reviewer) {
    }

    private final List<Verdict> verdicts;
    private final List<String> missing;
    private final Set<String> dependentReviewers;
    private final List<List<String>> revisedTable;
    private final String hash;
    private final String origin;

    private HtmlDecisionAdjudication(List<Verdict> verdicts, List<String> missing, Set<String> dependentReviewers,
            List<List<String>> revisedTable, String hash, String origin) {
        this.verdicts = List.copyOf(verdicts);
        this.missing = List.copyOf(missing);
        this.dependentReviewers = Set.copyOf(dependentReviewers);
        this.revisedTable = revisedTable;
        this.hash = hash;
        this.origin = origin;
    }

    /** Loads the bundled adjudication of {@code gold}, or nothing while no adjudication has been recorded. */
    static Optional<HtmlDecisionAdjudication> bundled(HtmlDecisionCases cases, HtmlDecisionGold gold,
            HtmlKnowledgeCorpus corpus) {
        if (HtmlDecisionAdjudication.class.getResource(RESOURCE) == null) {
            return Optional.empty();
        }
        return Optional.of(parse(HtmlKnowledgeCorpus.bundledTable(HtmlDecisionCases.VERSION, FILE), RESOURCE, cases,
                gold, corpus));
    }

    /**
     * Parses an adjudication of {@code gold} strictly and rebuilds the revised labels.
     *
     * @throws CorpusValidationException on a malformed row, an unknown case or item, a repeated row, a no-op change,
     *         an unresolvable quote or a revision that violates a gold invariant
     */
    static HtmlDecisionAdjudication parse(String text, String origin, HtmlDecisionCases cases, HtmlDecisionGold gold,
            HtmlKnowledgeCorpus corpus) {
        var byCase = new LinkedHashMap<String, Map<Item, Verdict>>();
        var verdicts = new ArrayList<Verdict>();
        for (var row : HtmlKnowledgeCorpus.rows(text, HEADER, origin)) {
            var caseId = row.required(0, "caseId");
            if (!cases.contains(caseId)) {
                throw row.reject("verdict for unknown case " + caseId);
            }
            var item = itemOf(row, row.required(1, "item"));
            var verdict = row.required(2, "verdict");
            if (!verdict.equals(AGREE) && !verdict.equals(CHANGE)) {
                throw row.reject("verdict must be exactly agree or change, was '" + verdict + "'");
            }
            var change = verdict.equals(CHANGE);
            var proposed = row.cells().get(3).strip();
            if (!change && !proposed.equals(ABSENT)) {
                throw row.reject("an agree verdict proposes nothing ('-')");
            }
            if (change) {
                if (proposed.isEmpty() || proposed.equals(ABSENT) && !item.isList()) {
                    throw row.reject("a change of " + item.label() + " needs a proposed value");
                }
                if (item == Item.EVIDENCE_SPANS) {
                    proposed = resolveSpans(row, proposed, corpus);
                }
                if (proposed.equals(cellOf(gold, caseId, item))) {
                    throw row.reject("a change must differ from the current " + item.label());
                }
            }
            var recorded = new Verdict(caseId, item, change, proposed, row.required(4, "reason"),
                    row.required(5, "reviewer"));
            if (byCase.computeIfAbsent(caseId, ignored -> new EnumMap<>(Item.class)).put(item, recorded) != null) {
                throw row.reject("repeated verdict for " + caseId + " " + item.label());
            }
            verdicts.add(recorded);
        }
        var missing = new ArrayList<String>();
        for (var decisionCase : cases.cases()) {
            var judged = byCase.getOrDefault(decisionCase.caseId(), Map.of());
            Arrays.stream(Item.values()).filter(item -> !judged.containsKey(item))
                    .forEach(item -> missing.add(decisionCase.caseId() + " " + item.label()));
        }
        var excluded = new TreeSet<>(gold.drafters());
        excluded.add(RULE_AUTHOR);
        var dependent = verdicts.stream().map(Verdict::reviewer).filter(excluded::contains)
                .collect(Collectors.toCollection(TreeSet::new));
        var revised = revise(gold, byCase);
        if (verdicts.stream().anyMatch(Verdict::change)) {
            HtmlDecisionGold.parse(render(revised, "# revision rebuilt from " + origin + "\n"), origin,
                    HtmlDecisionGold.REVISION_VERSION, cases, corpus);
        }
        return new HtmlDecisionAdjudication(verdicts, missing, dependent, revised, CorpusDigests.sha256(text), origin);
    }

    /** Returns every verdict in file order. */
    List<Verdict> verdicts() {
        return verdicts;
    }

    /** Returns the {@code caseId item} pairs without a verdict, in case and item order. */
    List<String> missing() {
        return missing;
    }

    /** Returns the reviewers who wrote the rules or drafted a label, and so are not independent. */
    Set<String> dependentReviewers() {
        return dependentReviewers;
    }

    /** Returns the reviewers, sorted. */
    Set<String> reviewers() {
        return verdicts.stream().map(Verdict::reviewer).collect(Collectors.toCollection(TreeSet::new));
    }

    long changes() {
        return verdicts.stream().filter(Verdict::change).count();
    }

    long agreements() {
        return verdicts.size() - changes();
    }

    /**
     * Returns the gold rows with every change applied, in {@code gold.tsv} order. Every row's reviewer becomes its
     * adjudicator, and a row with a change is drafted by its adjudicator; a revision file must hold exactly these.
     */
    List<List<String>> revisedTable() {
        return revisedTable;
    }

    /** Renders {@link #revisedTable()} as the {@value HtmlDecisionGold#REVISION_VERSION} file with its changelog. */
    String revisionText(HtmlDecisionCases cases) {
        var header = new StringBuilder("# " + HtmlDecisionGold.REVISION_VERSION + " gold labels of " + cases.version()
                + " (Issue #152), read only by the scorer. Rebuilt from " + HtmlDecisionGold.VERSION
                + " and the independent adjudication " + VERSION + " (sha256 " + hash + ").\n");
        header.append("# Same columns and syntax as gold.tsv; reviewer is the independent adjudicator. Any change is a"
                + " new gold version with a new frozen hash.\n");
        verdicts.stream().filter(Verdict::change).forEach(verdict -> header.append("# changelog: ")
                .append(verdict.caseId()).append(' ').append(verdict.item().label()).append(" (")
                .append(verdict.reviewer()).append("): ").append(verdict.reason()).append('\n'));
        return render(revisedTable, header.toString());
    }

    String hash() {
        return hash;
    }

    String origin() {
        return origin;
    }

    /** Renders the fill-in template: one row per case and item, verdict, proposed, reason and reviewer blank. */
    static String template(HtmlDecisionCases cases) {
        var tsv = new StringBuilder("# " + VERSION + " template: fill verdict (agree or change), proposed ('-' for"
                + " agree), reason and reviewer on every row\n");
        tsv.append(HEADER).append('\n');
        for (var decisionCase : cases.cases()) {
            for (var item : Item.values()) {
                tsv.append(String.join("\t", decisionCase.caseId(), item.label(), "", "", "", "")).append('\n');
            }
        }
        return tsv.toString();
    }

    private static Item itemOf(HtmlKnowledgeCorpus.Row row, String label) {
        return Arrays.stream(Item.values()).filter(item -> item.label().equals(label)).findFirst()
                .orElseThrow(() -> row.reject("'" + label + "' is not one of " + Arrays.stream(Item.values())
                        .map(Item::label).toList()));
    }

    private static String cellOf(HtmlDecisionGold gold, String caseId, Item item) {
        return gold.cells().stream().filter(cells -> cells.getFirst().equals(caseId)).findFirst().orElseThrow()
                .get(item.column);
    }

    private static List<List<String>> revise(HtmlDecisionGold gold, Map<String, Map<Item, Verdict>> byCase) {
        var revised = new ArrayList<List<String>>();
        for (var cells : gold.cells()) {
            var row = new ArrayList<>(cells);
            var judged = byCase.getOrDefault(cells.getFirst(), Map.of());
            var adjudicators = judged.values().stream().map(Verdict::reviewer).collect(Collectors.toCollection(
                    TreeSet::new));
            var changers = judged.values().stream().filter(Verdict::change).map(Verdict::reviewer)
                    .collect(Collectors.toCollection(TreeSet::new));
            judged.values().stream().filter(Verdict::change).forEach(verdict -> row.set(verdict.item().column,
                    verdict.proposed()));
            if (!adjudicators.isEmpty()) {
                row.set(REVIEWER_COLUMN, String.join("+", adjudicators));
            }
            if (!changers.isEmpty()) {
                row.set(DRAFTED_BY_COLUMN, String.join("+", changers));
            }
            revised.add(List.copyOf(row));
        }
        return List.copyOf(revised);
    }

    private static String render(List<List<String>> table, String comments) {
        var text = new StringBuilder(comments).append(HtmlDecisionGold.HEADER).append('\n');
        table.forEach(row -> text.append(String.join("\t", row)).append('\n'));
        return text.toString();
    }

    /** Resolves {@code chunkId "quote"} items to sorted {@code chunkId start end sha256} spans. */
    private static String resolveSpans(HtmlKnowledgeCorpus.Row row, String proposed, HtmlKnowledgeCorpus corpus) {
        var spans = new ArrayList<JudgedQuery.EvidenceSpan>();
        for (var item : new HtmlKnowledgeCorpus.Row(row.origin(), List.of(proposed)).list(0)) {
            var space = item.indexOf(' ');
            if (space < 1 || item.length() < space + 3 || item.charAt(space + 1) != '"' || !item.endsWith("\"")) {
                throw row.reject("a changed span must be 'chunkId \"exact quote\"', was '" + item + "'");
            }
            var chunkId = item.substring(0, space);
            var quote = item.substring(space + 2, item.length() - 1);
            var chunk = corpus.chunk(chunkId).orElseThrow(() -> row.reject("span names " + chunkId
                    + ", which is not a chunk of " + corpus.version()));
            var text = chunk.text();
            var at = text.indexOf(quote);
            if (quote.isBlank() || at < 0) {
                throw row.reject("the quote is not in " + chunkId + ": \"" + quote + "\"");
            }
            if (text.indexOf(quote, at + 1) >= 0) {
                throw row.reject("the quote occurs more than once in " + chunkId + ": \"" + quote + "\"");
            }
            var start = text.codePointCount(0, at);
            spans.add(new JudgedQuery.EvidenceSpan(chunkId, start, start + quote.codePointCount(0, quote.length()),
                    CorpusDigests.sha256(quote)));
        }
        spans.sort(Comparator.comparing(JudgedQuery.EvidenceSpan::chunkId)
                .thenComparingInt(JudgedQuery.EvidenceSpan::start).thenComparingInt(JudgedQuery.EvidenceSpan::end));
        return spans.stream().map(span -> span.chunkId() + " " + span.start() + " " + span.end() + " "
                + span.sha256()).collect(Collectors.joining("|"));
    }
}
