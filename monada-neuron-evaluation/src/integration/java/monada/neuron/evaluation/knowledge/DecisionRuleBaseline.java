package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerComponent.EvidenceChunk;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Exclusion;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Option;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Citation;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Claim;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.DecisionCase;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Family;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Tri;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.EXTERNAL_FILE_ALLOWED;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.INTEGRATE_WITH_PAGE;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.MODULE_CODE;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.ORDER_MATTERS;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.POWERFUL_FEATURES;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.RESTRICTIVE_CSP;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.REUSED_ACROSS_PAGES;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.RUN_AFTER_PARSE;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint.SEPARATE_DOCUMENT;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Tri.NO;
import static monada.neuron.evaluation.knowledge.HtmlDecisionCases.Tri.YES;

/**
 * The deterministic, evaluation-only <b>host rule baseline</b> {@value #VERSION} of the decision benchmark
 * (Issue #112). It is test-harness code, not Neuron cognition: the rules below were written by hand from the HTML
 * references, and nothing in Neuron inferred them. Reports print {@link #RULES} verbatim.
 *
 * <p>Algorithm, per case:
 * <ol>
 *   <li>Walk the rules of the case's family in table order. A rule <em>matches</em> when every constraint it lists has
 *       exactly the listed value. A rule is <em>undecided</em> when its listed constraints that are known match but at
 *       least one is {@code unknown}: the case might fall under it, so the baseline abstains and asks that constraint's
 *       question instead of guessing. No match and nothing undecided also abstains.</li>
 *   <li>Every choice a matched rule names must be a candidate of the case, or it abstains.</li>
 *   <li>Each required evidence cue must be found in the evidence: the first segment ({@link EvidenceSegmenter}), in
 *       rank then segment order, whose distinct tokens ({@link HtmlTokens}) contain all the cue's tokens. Its exact
 *       text becomes a claim citing that span. A missing required cue abstains with {@code insufficient evidence}; an
 *       optional cue (an exclusion's) is cited when found and otherwise left uncited.</li>
 * </ol>
 * The baseline never reads a case id, a gold label, the corpus or the Store; its only inputs are the case's constraints
 * and candidates and the evidence. Cue matching is lexical: finding the tokens is not understanding the sentence, and a
 * rule that mirrors the cases it was written for can look more capable than it is. Cost: the rules of one family
 * ({@code <= 8}) times one pass over the segments of at most five chunks.
 */
final class DecisionRuleBaseline implements DecisionSelector {

    /** Version of the rule table; any change to a rule, cue or reason is a new version. */
    static final String VERSION = "host-rules-v1";

    /** The report name of the baseline. */
    static final String NAME = "R";

    /**
     * A lexical evidence cue.
     *
     * @param id the cue name
     * @param tokens the lowercase tokens a segment must all contain
     */
    record Cue(String id, List<String> tokens) {

        Cue {
            Objects.requireNonNull(id, "id must not be null");
            tokens = List.copyOf(tokens);
        }

        String render() {
            return id + "{" + String.join(" ", tokens) + "}";
        }
    }

    /**
     * A rejected alternative with the host reason and an optional cue.
     *
     * @param choice the rejected choice
     * @param reason the host-authored reason
     * @param cue the evidence cue cited when found
     */
    record Excludes(String choice, String reason, Optional<Cue> cue) {
    }

    /**
     * A conditional option with the cue that must support it.
     *
     * @param choice the choice
     * @param assumption the assumption key
     * @param statement the host-authored statement
     * @param cue the required cue
     */
    record Conditionally(String choice, String assumption, String statement, Cue cue) {
    }

    /**
     * One rule.
     *
     * @param id the rule id
     * @param family the family it decides
     * @param when the constraint values it requires; unlisted constraints are not consulted
     * @param choice the decided choice, or empty for a conditional rule
     * @param alsoValid further equally valid choices of a decided rule
     * @param options the options of a conditional rule
     * @param reason the host-authored reason of a decided rule
     * @param required the cues a decided rule needs
     * @param optional further cues cited when found
     * @param excludes the rejected alternatives
     * @param permissions the permissions a decided rule grants
     */
    record Rule(String id, Family family, Map<Constraint, Tri> when, Optional<String> choice, List<String> alsoValid,
            List<Conditionally> options, String reason, List<Cue> required, List<Cue> optional,
            List<Excludes> excludes, List<String> permissions) {

        Rule {
            when = Map.copyOf(when);
            alsoValid = List.copyOf(alsoValid);
            options = List.copyOf(options);
            required = List.copyOf(required);
            optional = List.copyOf(optional);
            excludes = List.copyOf(excludes);
            permissions = List.copyOf(permissions);
            if (choice.isPresent() == !options.isEmpty()) {
                throw new IllegalArgumentException(id + ": a rule decides a choice or lists options, not both");
            }
        }

        /** Renders the rule for reports. */
        String render() {
            var condition = family.constraints().stream().filter(when::containsKey)
                    .map(constraint -> constraint.label() + "=" + when.get(constraint).label())
                    .collect(Collectors.joining(" and "));
            var outcome = choice.map(value -> "DECIDE " + value + (alsoValid.isEmpty() ? ""
                    : " (also valid: " + String.join(", ", alsoValid) + ")"))
                    .orElseGet(() -> "CONDITIONAL " + options.stream().map(option -> option.choice() + " if "
                            + option.assumption() + " [cue " + option.cue().render() + "]")
                            .collect(Collectors.joining("; ")));
            return id + ": if " + condition + " then " + outcome
                    + (required.isEmpty() ? "" : "; requires " + required.stream().map(Cue::render)
                            .collect(Collectors.joining(", ")))
                    + (excludes.isEmpty() ? "" : "; excludes " + excludes.stream().map(excluded -> excluded.choice()
                            + " (" + excluded.reason() + ")").collect(Collectors.joining(", ")))
                    + (permissions.isEmpty() ? "" : "; grants " + String.join(", ", permissions));
        }
    }

    static final Cue DIV = cue("div-groups-current-document", "div generic container groups current document");
    static final Cue IFRAME = cue("iframe-embeds-separate-document",
            "iframe embeds separate document browsing context");
    static final Cue ISOLATED = cue("iframe-isolates-styles", "iframe isolated styles");
    static final Cue INTEGRATED = cue("current-document-shares-styles", "share styles accessibility current document");
    static final Cue NO_PERMISSION = cue("list-no-unneeded-permission", "needs feature list none permission");
    static final Cue ALLOW = cue("allow-grants-listed-features", "allow permissions policy granting listed");
    static final Cue ORDER = cue("ol-ul-order-matters", "ol ul order matters");
    static final Cue MODULE_DEFERRED = cue("module-deferred-by-default", "module deferred default defer effect");
    static final Cue MODULE_STRICT = cue("module-strict-scope", "module strict classic");
    static final Cue DEFER_EXTERNAL = cue("defer-runs-external-after-parse", "defer external parsed order");
    static final Cue DEFER_INLINE_IGNORED = cue("defer-ignored-inline", "inline classic src ignored");
    static final Cue DEFER_INLINE_MOVE = cue("delay-inline-move-or-module", "delay inline external defer module");
    static final Cue CSP_BLOCKS = cue("csp-blocks-inline", "unsafe inline blocks external");
    static final Cue NONCE = cue("nonce-or-hash-allows-inline", "nonce hash inline script");
    static final Cue EXTERNAL_REUSE = cue("external-cached-reused", "external cached reused inline");
    static final Cue INLINE_OR_SRC = cue("script-inline-or-src", "script inline src file");

    /** The rule table, in evaluation order per family. */
    static final List<Rule> RULES = List.of(
            decided("E1", Family.EMBED, Map.of(SEPARATE_DOCUMENT, NO), "div", List.of(),
                    "the content belongs to the current document, which div groups", List.of(DIV), List.of(),
                    List.of(new Excludes("iframe", "iframe embeds a separate document with its own browsing context",
                            Optional.of(IFRAME))), List.of()),
            conditional("E2", Family.EMBED, Map.of(SEPARATE_DOCUMENT, YES, INTEGRATE_WITH_PAGE, YES), List.of(
                    new Conditionally("div", "integration-preferred", "copy the document's markup into a div of this"
                            + " page if sharing styles and the accessibility tree matters more", INTEGRATED),
                    new Conditionally("iframe", "isolation-preferred", "embed it with an iframe if keeping it an"
                            + " isolated document matters more", ISOLATED)), List.of()),
            decided("E3", Family.EMBED, Map.of(SEPARATE_DOCUMENT, YES, INTEGRATE_WITH_PAGE, NO, POWERFUL_FEATURES, NO),
                    "iframe", List.of(), "the content is a separate, isolated document, which iframe embeds; no"
                            + " feature is requested, so allow grants nothing", List.of(IFRAME),
                    List.of(NO_PERMISSION), List.of(new Excludes("div", "div only groups elements of the current"
                            + " document and cannot hold another document", Optional.of(DIV))), List.of()),
            decided("E4", Family.EMBED, Map.of(SEPARATE_DOCUMENT, YES, INTEGRATE_WITH_PAGE, NO, POWERFUL_FEATURES, YES),
                    "iframe", List.of(), "the content is a separate, isolated document, which iframe embeds; allow"
                            + " lists exactly the requested features", List.of(IFRAME, ALLOW), List.of(),
                    List.of(new Excludes("div", "div only groups elements of the current document and cannot hold"
                            + " another document", Optional.of(DIV))), List.of("allow=requested-features-only")),
            decided("L1", Family.LIST, Map.of(ORDER_MATTERS, YES), "ol", List.of(),
                    "the order of the items matters, which ol expresses", List.of(ORDER), List.of(),
                    List.of(new Excludes("ul", "ul is for items whose order does not matter", Optional.of(ORDER))),
                    List.of()),
            decided("L2", Family.LIST, Map.of(ORDER_MATTERS, NO), "ul", List.of(),
                    "the order of the items does not matter, which ul expresses", List.of(ORDER), List.of(),
                    List.of(new Excludes("ol", "ol states that the order matters", Optional.of(ORDER))), List.of()),
            decided("S1", Family.SCRIPT, Map.of(MODULE_CODE, YES), "module", List.of(),
                    "module code is loaded with type=\"module\", which is deferred by default",
                    List.of(MODULE_DEFERRED), List.of(), List.of(
                            new Excludes("external+defer", "defer has no effect on modules, and a classic script"
                                    + " cannot import modules", Optional.of(MODULE_DEFERRED)),
                            new Excludes("inline+defer", "defer has no effect on modules and is ignored on inline"
                                    + " classic scripts", Optional.of(DEFER_INLINE_IGNORED))), List.of()),
            decided("S2", Family.SCRIPT, Map.of(MODULE_CODE, NO, RUN_AFTER_PARSE, YES, EXTERNAL_FILE_ALLOWED, YES),
                    "external+defer", List.of(), "defer runs an external classic script after parsing, in document"
                            + " order", List.of(DEFER_EXTERNAL), List.of(), List.of(
                            new Excludes("inline+defer", "defer is ignored on an inline classic script",
                                    Optional.of(DEFER_INLINE_IGNORED)),
                            new Excludes("module", "the code is classic, and module code has its own scope and"
                                    + " strict mode", Optional.of(MODULE_STRICT))), List.of()),
            conditional("S3", Family.SCRIPT, Map.of(MODULE_CODE, NO, RUN_AFTER_PARSE, YES, EXTERNAL_FILE_ALLOWED, NO),
                    List.of(new Conditionally("module", "module-compatible", "make it an inline module, which runs"
                            + " after parsing, only if the classic code works unchanged as a module",
                            DEFER_INLINE_MOVE)), List.of(
                            new Excludes("inline+defer", "defer is ignored on an inline classic script",
                                    Optional.of(DEFER_INLINE_IGNORED)),
                            new Excludes("external+defer", "no file can be added", Optional.empty()))),
            decided("S4", Family.SCRIPT, Map.of(MODULE_CODE, NO, RUN_AFTER_PARSE, NO, RESTRICTIVE_CSP, YES,
                            EXTERNAL_FILE_ALLOWED, YES), "external", List.of(),
                    "a script-src without 'unsafe-inline' blocks inline code while allowed external scripts run",
                    List.of(CSP_BLOCKS), List.of(), List.of(new Excludes("inline", "the policy blocks inline code",
                            Optional.of(CSP_BLOCKS))), List.of()),
            conditional("S5", Family.SCRIPT, Map.of(MODULE_CODE, NO, RUN_AFTER_PARSE, NO, RESTRICTIVE_CSP, YES,
                            EXTERNAL_FILE_ALLOWED, NO),
                    List.of(new Conditionally("inline+nonce", "nonce-or-hash-available", "keep it inline with a"
                            + " nonce or hash only if the policy can carry that nonce or hash", NONCE)), List.of(
                            new Excludes("inline", "the policy blocks plain inline code", Optional.of(CSP_BLOCKS)),
                            new Excludes("external", "no file can be added", Optional.empty()))),
            decided("S6", Family.SCRIPT, Map.of(MODULE_CODE, NO, RUN_AFTER_PARSE, NO, RESTRICTIVE_CSP, NO,
                            REUSED_ACROSS_PAGES, YES, EXTERNAL_FILE_ALLOWED, YES), "external", List.of(),
                    "an external file can be cached and reused by every page", List.of(EXTERNAL_REUSE), List.of(),
                    List.of(new Excludes("inline", "inline code is repeated in every page",
                            Optional.of(EXTERNAL_REUSE))), List.of()),
            decided("S7", Family.SCRIPT, Map.of(MODULE_CODE, NO, RUN_AFTER_PARSE, NO, RESTRICTIVE_CSP, NO,
                            REUSED_ACROSS_PAGES, NO, EXTERNAL_FILE_ALLOWED, YES), "inline", List.of("external"),
                    "one page, no policy and no ordering need: inline code or an external file both work",
                    List.of(INLINE_OR_SRC), List.of(), List.of(), List.of()),
            decided("S8", Family.SCRIPT, Map.of(MODULE_CODE, NO, RUN_AFTER_PARSE, NO, RESTRICTIVE_CSP, NO,
                            REUSED_ACROSS_PAGES, NO, EXTERNAL_FILE_ALLOWED, NO), "inline", List.of(),
                    "no file can be added and nothing blocks inline code", List.of(INLINE_OR_SRC), List.of(),
                    List.of(new Excludes("external", "no file can be added", Optional.empty())), List.of()));

    private final EvidenceSegmenter segmenter = new EvidenceSegmenter();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return VERSION + ": deterministic, evaluation-only host rules over the case's typed constraints, with"
                + " lexical evidence cues; first matching rule per family, abstains on an undecided rule, a missing"
                + " required cue or a non-candidate choice; " + RULES.size() + " rules";
    }

    @Override
    public Kind kind() {
        return Kind.HOST_RULE_BASELINE;
    }

    @Override
    public DecisionRecommendation decide(DecisionCase decisionCase, List<EvidenceChunk> evidence) {
        Objects.requireNonNull(decisionCase, "decisionCase must not be null");
        var segments = segments(evidence);
        for (var rule : RULES) {
            if (rule.family() != decisionCase.family()) {
                continue;
            }
            var unknown = rule.family().constraints().stream().filter(rule.when()::containsKey)
                    .filter(constraint -> decisionCase.value(constraint) == Tri.UNKNOWN).findFirst();
            var knownMatch = rule.when().entrySet().stream()
                    .allMatch(entry -> decisionCase.value(entry.getKey()) == Tri.UNKNOWN
                            || decisionCase.value(entry.getKey()) == entry.getValue());
            if (!knownMatch) {
                continue;
            }
            if (unknown.isPresent()) {
                return new Abstained("rule " + rule.id() + " may apply but " + unknown.get().label() + " is unknown",
                        unknown.get().question(), rule.id());
            }
            return apply(rule, decisionCase, segments);
        }
        return new Abstained("no " + VERSION + " rule covers these constraints", "-", "none");
    }

    private DecisionRecommendation apply(Rule rule, DecisionCase decisionCase, List<Located> segments) {
        var named = new ArrayList<String>();
        rule.choice().ifPresent(named::add);
        named.addAll(rule.alsoValid());
        rule.options().forEach(option -> named.add(option.choice()));
        var missingCandidate = named.stream().filter(choice -> !decisionCase.candidates().contains(choice)).findFirst();
        if (missingCandidate.isPresent()) {
            return new Abstained("rule " + rule.id() + " names " + missingCandidate.get()
                    + ", which is not a candidate", "-", rule.id());
        }
        var claims = new ClaimSet();
        var required = new ArrayList<>(rule.required());
        rule.options().forEach(option -> required.add(option.cue()));
        for (var cue : required) {
            if (claims.cite(cue, segments).isEmpty()) {
                return new Abstained("insufficient evidence: no evidence segment matches cue " + cue.render(), "-",
                        rule.id());
            }
        }
        rule.optional().forEach(cue -> claims.cite(cue, segments));
        var excluded = rule.excludes().stream()
                .filter(excludes -> decisionCase.candidates().contains(excludes.choice()))
                .map(excludes -> new Exclusion(excludes.choice(), excludes.reason(),
                        excludes.cue().flatMap(cue -> claims.cite(cue, segments)).stream().toList()))
                .toList();
        var used = rule.family().constraints().stream().filter(rule.when()::containsKey)
                .map(constraint -> constraint.label() + "=" + decisionCase.value(constraint).label()).toList();
        if (rule.choice().isPresent()) {
            return new Decided(rule.choice().get(), rule.alsoValid(), excluded, used, rule.permissions(), rule.reason(),
                    claims.claims(), rule.id());
        }
        return new Conditional(rule.options().stream()
                .map(option -> new Option(option.choice(), option.assumption(), option.statement())).toList(),
                excluded, used, claims.claims(), rule.id());
    }

    /** One segment of one evidence chunk, with its distinct tokens. */
    private record Located(String chunkId, EvidenceSegmenter.Segment segment, LinkedHashSet<String> tokens) {
    }

    private List<Located> segments(List<EvidenceChunk> evidence) {
        var located = new ArrayList<Located>();
        for (var chunk : evidence) {
            for (var segment : segmenter.segments(chunk.text())) {
                located.add(new Located(chunk.chunkId(), segment, new LinkedHashSet<>(HtmlTokens.distinct(
                        segment.text()))));
            }
        }
        return located;
    }

    /** The claims of one recommendation: one per cited segment, in first-citation order. */
    private static final class ClaimSet {

        private final Map<Citation, Claim> claims = new LinkedHashMap<>();

        /** Cites the first segment matching {@code cue}, adding its claim once; empty when none matches. */
        Optional<Citation> cite(Cue cue, List<Located> segments) {
            for (var located : segments) {
                if (located.tokens().containsAll(cue.tokens())) {
                    var citation = new Citation(located.chunkId(), located.segment().start(), located.segment().end());
                    claims.computeIfAbsent(citation, key -> new Claim(located.segment().text(), List.of(key)));
                    return Optional.of(citation);
                }
            }
            return Optional.empty();
        }

        List<Claim> claims() {
            return List.copyOf(claims.values());
        }
    }

    private static Cue cue(String id, String tokens) {
        return new Cue(id, List.of(tokens.split(" ")));
    }

    private static Rule decided(String id, Family family, Map<Constraint, Tri> when, String choice,
            List<String> alsoValid, String reason, List<Cue> required, List<Cue> optional, List<Excludes> excludes,
            List<String> permissions) {
        return new Rule(id, family, new EnumMap<>(when), Optional.of(choice), alsoValid, List.of(), reason, required,
                optional, excludes, permissions);
    }

    private static Rule conditional(String id, Family family, Map<Constraint, Tri> when, List<Conditionally> options,
            List<Excludes> excludes) {
        return new Rule(id, family, new EnumMap<>(when), Optional.empty(), List.of(), options, "conditional",
                List.of(), List.of(), excludes, List.of());
    }
}
