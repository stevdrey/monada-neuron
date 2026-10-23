package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.JudgedQuery.EvidenceSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The gold labels {@value #VERSION} of the decision case set (Issue #112): for each case the expected outcome, the
 * acceptable and ineligible choices, the conditional options a conditional answer must state, the expected permission
 * grant, a human-written rationale and the exact supporting spans in the corpus.
 *
 * <p>Only {@link DecisionMetrics} and the runner read this class; no selector, experiment or trace type references it,
 * and a test scans their bytecode for that. Load-time invariants: exactly one row per case, every choice a candidate of
 * its case, acceptable and ineligible disjoint, outcome-consistent fields, and every span inside its chunk with a
 * matching digest. A violation throws {@link CorpusValidationException}.
 *
 * <p>Two versions share this format: the authored {@value #VERSION} ({@code gold.tsv}) and, once the independent
 * adjudication of Issue #152 changes a label, the {@value #REVISION_VERSION} revision ({@code gold-v2.tsv}). Results
 * are comparable only within one version.
 */
final class HtmlDecisionGold {

    /** Version of the authored gold labels. */
    static final String VERSION = "html-decision-v1-gold";

    /**
     * Version of the gold labels revised by the independent adjudication (Issue #152); it exists only when the
     * adjudication changes a label, and its rows are exactly {@link HtmlDecisionAdjudication#revisedTable()}.
     */
    static final String REVISION_VERSION = "html-decision-v1-gold-v2";

    /** Header row of {@code gold.tsv}. */
    static final String HEADER = "caseId\texpected\tacceptable\tineligible\tassumptions\tpermissions\trationale"
            + "\tevidenceSpans\treviewer\tdraftedBy";

    private static final String FILE = "gold.tsv";
    private static final String REVISION_FILE = "gold-v2.tsv";
    private static final String RESOURCE = "/" + HtmlDecisionCases.VERSION + "/" + FILE;
    private static final String REVISION_RESOURCE = "/" + HtmlDecisionCases.VERSION + "/" + REVISION_FILE;

    /** The expected kind of answer. */
    enum Expected {
        /** One of the acceptable choices, stated without conditions. */
        DECIDE,
        /** The listed options, each with the assumption it depends on. */
        CONDITIONAL,
        /** No choice: the requirement does not determine one, so the answer is to ask. */
        ABSTAIN
    }

    /** The permission grant a good answer makes; written in {@code gold.tsv} as its exact lowercase label. */
    enum Permissions {
        /** Grant no extra permission. */
        NONE,
        /** Grant exactly the features the requirement asks for. */
        REQUESTED;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One option of a conditional answer.
     *
     * @param choice the choice
     * @param assumption the assumption key it depends on
     */
    record Option(String choice, String assumption) {

        String render() {
            return choice + "@" + assumption;
        }
    }

    /**
     * The gold label of one case.
     *
     * @param caseId the case
     * @param expected the expected outcome
     * @param acceptable choices credited as correct (the options' choices for {@link Expected#CONDITIONAL})
     * @param ineligible choices that are wrong for this requirement
     * @param options the options a conditional answer must state; empty unless conditional
     * @param permissions the expected permission grant
     * @param rationale the human-written expected rationale
     * @param evidence the exact supporting spans, sorted by chunk, start and end
     * @param reviewer the reviewer of record
     * @param draftedBy who drafted the label
     */
    record Gold(String caseId, Expected expected, Set<String> acceptable, Set<String> ineligible, List<Option> options,
            Permissions permissions, String rationale, List<EvidenceSpan> evidence, String reviewer,
            String draftedBy) {

        Gold {
            acceptable = Set.copyOf(acceptable);
            ineligible = Set.copyOf(ineligible);
            options = List.copyOf(options);
            evidence = List.copyOf(evidence);
        }

        /** Returns the chunks the gold spans lie in, in span order without repeats. */
        List<String> evidenceChunks() {
            return List.copyOf(new LinkedHashSet<>(evidence.stream().map(EvidenceSpan::chunkId).toList()));
        }
    }

    private final String version;
    private final Map<String, Gold> byCase;
    private final List<List<String>> cells;
    private final String hash;
    private final String origin;

    private HtmlDecisionGold(String version, Map<String, Gold> byCase, List<List<String>> cells, String hash,
            String origin) {
        this.version = version;
        this.byCase = byCase;
        this.cells = cells;
        this.hash = hash;
        this.origin = origin;
    }

    /** Loads and validates the bundled {@value #VERSION} labels against {@code cases} and {@code corpus}. */
    static HtmlDecisionGold bundled(HtmlDecisionCases cases, HtmlKnowledgeCorpus corpus) {
        return parse(HtmlKnowledgeCorpus.bundledTable(HtmlDecisionCases.VERSION, FILE), RESOURCE, VERSION, cases,
                corpus);
    }

    /** Loads the bundled {@value #REVISION_VERSION} labels, or nothing when no adjudication changed a label. */
    static Optional<HtmlDecisionGold> bundledRevision(HtmlDecisionCases cases, HtmlKnowledgeCorpus corpus) {
        if (HtmlDecisionGold.class.getResource(REVISION_RESOURCE) == null) {
            return Optional.empty();
        }
        return Optional.of(parse(HtmlKnowledgeCorpus.bundledTable(HtmlDecisionCases.VERSION, REVISION_FILE),
                REVISION_RESOURCE, REVISION_VERSION, cases, corpus));
    }

    /** Parses and validates a {@value #VERSION} table, as the loader does. */
    static HtmlDecisionGold parse(String text, String origin, HtmlDecisionCases cases, HtmlKnowledgeCorpus corpus) {
        return parse(text, origin, VERSION, cases, corpus);
    }

    /** Parses and validates a gold table of {@code version}, as the loader does. */
    static HtmlDecisionGold parse(String text, String origin, String version, HtmlDecisionCases cases,
            HtmlKnowledgeCorpus corpus) {
        var byCase = new LinkedHashMap<String, Gold>();
        var cells = new ArrayList<List<String>>();
        for (var row : HtmlKnowledgeCorpus.rows(text, HEADER, origin)) {
            var gold = goldOf(row);
            if (!cases.contains(gold.caseId())) {
                throw row.reject("gold label for unknown case " + gold.caseId());
            }
            validate(row, gold, cases.byId(gold.caseId()), corpus);
            if (byCase.put(gold.caseId(), gold) != null) {
                throw row.reject("repeated gold label for " + gold.caseId());
            }
            cells.add(row.cells().stream().map(String::strip).toList());
        }
        var missing = cases.cases().stream().map(HtmlDecisionCases.DecisionCase::caseId)
                .filter(caseId -> !byCase.containsKey(caseId)).toList();
        if (!missing.isEmpty()) {
            throw new CorpusValidationException(origin, "cases without a gold label: " + missing);
        }
        return new HtmlDecisionGold(version, Map.copyOf(byCase), List.copyOf(cells), CorpusDigests.sha256(text),
                origin);
    }

    /** Returns the gold label of {@code caseId}. */
    Gold of(String caseId) {
        return Objects.requireNonNull(byCase.get(caseId), "no gold label for " + caseId);
    }

    /** Returns the version of these labels. */
    String version() {
        return version;
    }

    /** Returns the stripped cells of every data row in file order, so a revision can be rebuilt and compared. */
    List<List<String>> cells() {
        return cells;
    }

    /** Returns everyone who drafted a label of this file. */
    Set<String> drafters() {
        return Set.copyOf(byCase.values().stream().map(Gold::draftedBy).toList());
    }

    /** Returns the SHA-256 of the normalized gold file. */
    String hash() {
        return hash;
    }

    String origin() {
        return origin;
    }

    private static Gold goldOf(HtmlKnowledgeCorpus.Row row) {
        var expected = enumOf(row, Expected.values(), row.required(1, "expected"));
        var options = new ArrayList<Option>();
        for (var item : row.list(4)) {
            var parts = item.split("@", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw row.reject("assumption '" + item + "' must be choice@assumption");
            }
            options.add(new Option(parts[0], parts[1]));
        }
        var spans = new ArrayList<EvidenceSpan>();
        for (var item : row.list(7)) {
            var parts = item.split(" ", -1);
            if (parts.length != 4) {
                throw row.reject("span '" + item + "' must be 'chunkId start end sha256'");
            }
            try {
                spans.add(new EvidenceSpan(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        parts[3]));
            } catch (NumberFormatException e) {
                throw row.reject("span offsets must be integers");
            }
        }
        return new Gold(row.required(0, "caseId"), expected, new LinkedHashSet<>(row.list(2)),
                new LinkedHashSet<>(row.list(3)), options,
                permissionsOf(row, row.required(5, "permissions")),
                row.required(6, "rationale"), spans, row.required(8, "reviewer"), row.required(9, "draftedBy"));
    }

    private static Permissions permissionsOf(HtmlKnowledgeCorpus.Row row, String label) {
        return Arrays.stream(Permissions.values()).filter(value -> value.label().equals(label)).findFirst()
                .orElseThrow(() -> row.reject("permissions must be exactly none or requested, was '" + label + "'"));
    }

    private static <E extends Enum<E>> E enumOf(HtmlKnowledgeCorpus.Row row, E[] values, String label) {
        return Arrays.stream(values).filter(value -> value.name().equals(label)).findFirst()
                .orElseThrow(() -> row.reject("'" + label + "' is not one of " + Arrays.toString(values)));
    }

    private static void validate(HtmlKnowledgeCorpus.Row row, Gold gold, HtmlDecisionCases.DecisionCase decisionCase,
            HtmlKnowledgeCorpus corpus) {
        var candidates = Set.copyOf(decisionCase.candidates());
        var named = new HashSet<String>(gold.acceptable());
        named.addAll(gold.ineligible());
        gold.options().forEach(option -> named.add(option.choice()));
        if (!candidates.containsAll(named)) {
            throw row.reject("every gold choice must be a candidate of " + gold.caseId());
        }
        if (gold.acceptable().stream().anyMatch(gold.ineligible()::contains)) {
            throw row.reject("acceptable and ineligible choices must be disjoint");
        }
        switch (gold.expected()) {
            case DECIDE -> {
                if (gold.acceptable().isEmpty() || !gold.options().isEmpty()) {
                    throw row.reject("DECIDE needs acceptable choices and no conditional options");
                }
            }
            case CONDITIONAL -> {
                var optionChoices = gold.options().stream().map(Option::choice).toList();
                if (gold.options().isEmpty() || !Set.copyOf(optionChoices).equals(gold.acceptable())
                        || Set.copyOf(gold.options()).size() != gold.options().size()) {
                    throw row.reject("CONDITIONAL needs distinct choice@assumption options whose choices are exactly"
                            + " the acceptable choices");
                }
            }
            case ABSTAIN -> {
                if (!gold.acceptable().isEmpty() || !gold.options().isEmpty()) {
                    throw row.reject("ABSTAIN has no acceptable choice and no option");
                }
            }
        }
        if (gold.evidence().isEmpty()) {
            throw row.reject("every gold label needs at least one supporting span");
        }
        var order = Comparator.comparing(EvidenceSpan::chunkId).thenComparingInt(EvidenceSpan::start)
                .thenComparingInt(EvidenceSpan::end);
        EvidenceSpan previous = null;
        for (var span : gold.evidence()) {
            if (previous != null && order.compare(previous, span) >= 0) {
                throw row.reject("spans must be sorted by (chunkId, start, end) without repeats");
            }
            previous = span;
            HtmlKnowledgeQuerySet.verifySpan(row.origin(), corpus, span);
        }
    }
}
