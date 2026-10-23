package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The validated, versioned decision case set {@value #VERSION} (Issue #112): HTML design requirements with explicit,
 * typed constraints and the candidate choices a selector may pick from.
 *
 * <p>This is the <b>selector-visible</b> half of the fixture. It holds no gold label: the expected decision, rationale
 * and evidence spans live in {@link HtmlDecisionGold}, which only the scorer reads. Cases are exposed in {@code caseId}
 * order, which is also the run order. Load-time invariants: bounded size and count, every constraint of a case's family
 * present exactly once, candidates drawn from the family's choices, and every contrastive pair made of exactly two
 * cases of one family with equal candidates that differ in exactly one constraint. A violation throws
 * {@link CorpusValidationException}.
 */
final class HtmlDecisionCases {

    /** Version of the authored case set. */
    static final String VERSION = "html-decision-v1";

    /** Header row of {@code cases.tsv}. */
    static final String HEADER = "caseId\tfamily\tpairs\trequirement\tconstraints\tcandidates";

    static final int MIN_CASES = 12;
    static final int MAX_CASES = 20;
    static final int MIN_PAIRS = 4;

    /** Longest requirement text, in code points; it is also the retrieval query. */
    static final int MAX_REQUIREMENT_CODE_POINTS = 400;

    static final String LIST_SEPARATOR = "|";

    private static final String FILE = "cases.tsv";
    private static final String RESOURCE = "/" + VERSION + "/" + FILE;
    private static final Pattern CASE_ID = Pattern.compile("case:[a-z]+(?:-[a-z]+)*-\\d{2}");
    private static final Pattern PAIR_ID = Pattern.compile("pair:[a-z]+(?:-[a-z]+)*");

    /** A three-valued constraint value: unknown is a stated fact about the requirement, never a default. */
    enum Tri {
        YES,
        NO,
        UNKNOWN;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Tri ofLabel(String where, String label) {
            return Arrays.stream(values()).filter(value -> value.label().equals(label)).findFirst()
                    .orElseThrow(() -> new CorpusValidationException(where, "constraint value must be yes, no or"
                            + " unknown, was '" + label + "'"));
        }
    }

    /** A family of related HTML choices, with the constraints and choices it is decided by. */
    enum Family {
        EMBED("embed", List.of("div", "iframe")),
        LIST("list", List.of("ol", "ul")),
        SCRIPT("script", List.of("inline", "external", "inline+nonce", "inline+defer", "external+defer", "module"));

        private final String label;
        private final List<String> choices;

        Family(String label, List<String> choices) {
            this.label = label;
            this.choices = choices;
        }

        String label() {
            return label;
        }

        /** Returns every choice of the family, in a fixed order. */
        List<String> choices() {
            return choices;
        }

        /** Returns the constraints of the family, in declaration order. */
        List<Constraint> constraints() {
            return Arrays.stream(Constraint.values()).filter(constraint -> constraint.family() == this).toList();
        }

        static Family ofLabel(String where, String label) {
            return Arrays.stream(values()).filter(family -> family.label.equals(label)).findFirst()
                    .orElseThrow(() -> new CorpusValidationException(where, "unknown family '" + label + "'"));
        }
    }

    /** A named requirement property; its value is part of the case, stated by the case author. */
    enum Constraint {
        SEPARATE_DOCUMENT(Family.EMBED, "separateDocument", "Is the content a separate HTML document?"),
        INTEGRATE_WITH_PAGE(Family.EMBED, "integrateWithPage",
                "Must the content share the page's styles, layout and accessibility tree?"),
        POWERFUL_FEATURES(Family.EMBED, "powerfulFeatures",
                "Does the embedded content need a powerful feature such as the camera or geolocation?"),
        ORDER_MATTERS(Family.LIST, "orderMatters", "Does the order of the items change their meaning?"),
        MODULE_CODE(Family.SCRIPT, "moduleCode", "Is the code a JavaScript module?"),
        EXTERNAL_FILE_ALLOWED(Family.SCRIPT, "externalFileAllowed", "Can the code be put in its own file?"),
        RUN_AFTER_PARSE(Family.SCRIPT, "runAfterParse", "Must the code run only after the document is parsed?"),
        RESTRICTIVE_CSP(Family.SCRIPT, "restrictiveCsp",
                "Does a Content Security Policy apply whose script-src allows the page's own script files"
                        + " ('self') but not 'unsafe-inline'?"),
        REUSED_ACROSS_PAGES(Family.SCRIPT, "reusedAcrossPages", "Is the same code used by several pages?");

        private final Family family;
        private final String label;
        private final String question;

        Constraint(Family family, String label, String question) {
            this.family = family;
            this.label = label;
            this.question = question;
        }

        Family family() {
            return family;
        }

        String label() {
            return label;
        }

        /** Returns the clarifying question to ask when the value is unknown. */
        String question() {
            return question;
        }

        static Constraint ofLabel(String where, String label) {
            return Arrays.stream(values()).filter(constraint -> constraint.label.equals(label)).findFirst()
                    .orElseThrow(() -> new CorpusValidationException(where, "unknown constraint '" + label + "'"));
        }
    }

    /**
     * One decision case.
     *
     * @param caseId {@code case:<slug>-NN}
     * @param family the choice family
     * @param pairs the contrastive pairs the case belongs to
     * @param requirement the requirement text, also the retrieval query
     * @param constraints every constraint of the family with its stated value, in declaration order
     * @param candidates the choices offered, in the family's order
     */
    record DecisionCase(String caseId, Family family, List<String> pairs, String requirement,
            Map<Constraint, Tri> constraints, List<String> candidates) {

        DecisionCase {
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(family, "family must not be null");
            Objects.requireNonNull(requirement, "requirement must not be null");
            pairs = List.copyOf(pairs);
            constraints = Collections.unmodifiableMap(new EnumMap<>(constraints));
            candidates = List.copyOf(candidates);
        }

        /** Returns the stated value of {@code constraint}. */
        Tri value(Constraint constraint) {
            return Objects.requireNonNull(constraints.get(constraint),
                    constraint + " is not a constraint of " + caseId);
        }

        /**
         * Returns the retrieval query of the case, the same for every arm: the distinct tokens ({@link HtmlTokens}) of
         * the candidates and then of the requirement, without {@link ExtractiveAnswerer#FUNCTION_WORDS} and tokens the
         * codec cannot pack, capped at {@link TokenPackingSignalCodec#MAX_QUERY_TOKENS} and joined by spaces. It is a
         * fixed function of the case text, never of a gold label.
         */
        String query() {
            var tokens = new LinkedHashSet<String>();
            for (var token : HtmlTokens.tokens(String.join(" ", candidates) + " " + requirement)) {
                if (tokens.size() == TokenPackingSignalCodec.MAX_QUERY_TOKENS) {
                    break;
                }
                if (!ExtractiveAnswerer.FUNCTION_WORDS.contains(token)
                        && token.length() <= TokenPackingSignalCodec.MAX_TOKEN_LENGTH
                        && token.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                    tokens.add(token);
                }
            }
            return String.join(" ", tokens);
        }

        /** Renders the constraints as {@code name=value} in declaration order. */
        String renderConstraints() {
            return String.join(LIST_SEPARATOR, constraints.entrySet().stream()
                    .map(entry -> entry.getKey().label() + "=" + entry.getValue().label()).toList());
        }
    }

    /**
     * One contrastive pair.
     *
     * @param pairId {@code pair:<slug>}
     * @param first the case with the smaller id
     * @param second the case with the larger id
     * @param varied the single constraint the two cases differ in
     */
    record Pair(String pairId, String first, String second, Constraint varied) {
    }

    private final String version;
    private final List<DecisionCase> cases;
    private final Map<String, DecisionCase> byId;
    private final List<Pair> pairs;
    private final String caseHash;

    private HtmlDecisionCases(String version, List<DecisionCase> cases, List<Pair> pairs) {
        this.version = version;
        this.cases = cases.stream().sorted(Comparator.comparing(DecisionCase::caseId)).toList();
        var index = new LinkedHashMap<String, DecisionCase>();
        this.cases.forEach(value -> index.put(value.caseId(), value));
        this.byId = Collections.unmodifiableMap(index);
        this.pairs = List.copyOf(pairs);
        this.caseHash = hash(version, this.cases);
    }

    /** Loads and validates the bundled case set. */
    static HtmlDecisionCases bundled() {
        return parse(VERSION, HtmlKnowledgeCorpus.bundledTable(VERSION, FILE), RESOURCE);
    }

    /** Parses and validates a case table, as the loader does. */
    static HtmlDecisionCases parse(String version, String text, String origin) {
        var cases = new ArrayList<DecisionCase>();
        for (var row : HtmlKnowledgeCorpus.rows(text, HEADER, origin)) {
            cases.add(caseOf(row));
        }
        return of(version, cases);
    }

    /** Validates already built cases. */
    static HtmlDecisionCases of(String version, List<DecisionCase> cases) {
        Objects.requireNonNull(version, "version must not be null");
        var origin = "case set " + version;
        if (cases.size() < MIN_CASES || cases.size() > MAX_CASES) {
            throw new CorpusValidationException(origin, cases.size() + " cases are outside " + MIN_CASES + ".."
                    + MAX_CASES);
        }
        var ids = new HashSet<String>();
        var members = new TreeMap<String, List<DecisionCase>>();
        for (var value : cases) {
            var where = origin + " " + value.caseId();
            if (!CASE_ID.matcher(value.caseId()).matches() || !ids.add(value.caseId())) {
                throw new CorpusValidationException(where, "caseId must match case:<slug>-NN and be unique");
            }
            if (value.requirement().isBlank() || value.requirement().codePointCount(0, value.requirement().length())
                    > MAX_REQUIREMENT_CODE_POINTS || value.requirement().contains("\t")
                    || value.requirement().contains("\n")) {
                throw new CorpusValidationException(where, "requirement must be one non-blank line of at most "
                        + MAX_REQUIREMENT_CODE_POINTS + " code points");
            }
            if (!value.constraints().keySet().equals(Set.copyOf(value.family().constraints()))) {
                throw new CorpusValidationException(where, "constraints must be exactly "
                        + value.family().constraints().stream().map(Constraint::label).toList());
            }
            var ordered = value.family().choices().stream().filter(value.candidates()::contains).toList();
            if (value.candidates().size() < 2 || !ordered.equals(value.candidates())) {
                throw new CorpusValidationException(where, "candidates must be at least two distinct choices of "
                        + value.family().label() + " in the order " + value.family().choices());
            }
            for (var pair : value.pairs()) {
                if (!PAIR_ID.matcher(pair).matches()) {
                    throw new CorpusValidationException(where, "pair id '" + pair + "' must match pair:<slug>");
                }
                members.computeIfAbsent(pair, key -> new ArrayList<>()).add(value);
            }
        }
        var pairs = new ArrayList<Pair>();
        for (var entry : members.entrySet()) {
            var where = origin + " " + entry.getKey();
            var both = entry.getValue().stream().sorted(Comparator.comparing(DecisionCase::caseId)).toList();
            if (both.size() != 2) {
                throw new CorpusValidationException(where, "a pair needs exactly two cases, found " + both.size());
            }
            var first = both.get(0);
            var second = both.get(1);
            if (first.family() != second.family() || !first.candidates().equals(second.candidates())) {
                throw new CorpusValidationException(where, "the cases of a pair need one family and equal candidates");
            }
            var varied = first.family().constraints().stream()
                    .filter(constraint -> first.value(constraint) != second.value(constraint)).toList();
            if (varied.size() != 1) {
                throw new CorpusValidationException(where, "the cases of a pair must differ in exactly one constraint,"
                        + " they differ in " + varied.stream().map(Constraint::label).toList());
            }
            pairs.add(new Pair(entry.getKey(), first.caseId(), second.caseId(), varied.getFirst()));
        }
        if (pairs.size() < MIN_PAIRS) {
            throw new CorpusValidationException(origin, "at least " + MIN_PAIRS + " contrastive pairs are required,"
                    + " found " + pairs.size());
        }
        return new HtmlDecisionCases(version, cases, pairs);
    }

    String version() {
        return version;
    }

    /** Returns the cases in {@code caseId} order. */
    List<DecisionCase> cases() {
        return cases;
    }

    /** Returns the case with {@code caseId}, or throws. */
    DecisionCase byId(String caseId) {
        var value = byId.get(caseId);
        if (value == null) {
            throw new IllegalArgumentException("unknown case " + caseId);
        }
        return value;
    }

    /** Returns whether {@code caseId} is a case of this set. */
    boolean contains(String caseId) {
        return byId.containsKey(caseId);
    }

    /** Returns the contrastive pairs in {@code pairId} order. */
    List<Pair> pairs() {
        return pairs;
    }

    /** Returns the canonical {@code caseHash}. */
    String caseHash() {
        return caseHash;
    }

    private static DecisionCase caseOf(HtmlKnowledgeCorpus.Row row) {
        var family = Family.ofLabel(row.origin(), row.required(1, "family"));
        var constraints = new EnumMap<Constraint, Tri>(Constraint.class);
        for (var item : row.list(4)) {
            var parts = item.split("=", -1);
            if (parts.length != 2) {
                throw row.reject("constraint '" + item + "' must be name=value");
            }
            var constraint = Constraint.ofLabel(row.origin(), parts[0]);
            if (constraints.put(constraint, Tri.ofLabel(row.origin(), parts[1])) != null) {
                throw row.reject("constraint " + parts[0] + " is repeated");
            }
        }
        var candidates = row.list(5);
        for (var candidate : candidates) {
            if (!family.choices().contains(candidate)) {
                throw row.reject("candidate '" + candidate + "' is not a " + family.label() + " choice");
            }
        }
        return new DecisionCase(row.required(0, "caseId"), family, row.list(2), row.required(3, "requirement"),
                constraints, candidates);
    }

    private static String hash(String version, List<DecisionCase> sorted) {
        var digest = new CanonicalRecordDigest().field(version).field(sorted.size());
        for (var value : sorted) {
            digest.field(value.caseId()).field(value.family().label())
                    .field(String.join(LIST_SEPARATOR, value.pairs())).field(value.requirement())
                    .field(value.renderConstraints()).field(String.join(LIST_SEPARATOR, value.candidates()));
        }
        return digest.hex();
    }
}
