package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.DecisionMetrics.ArmAggregate;
import monada.neuron.evaluation.knowledge.DecisionMetrics.PairScore;
import monada.neuron.evaluation.knowledge.DecisionMetrics.Scored;
import monada.neuron.evaluation.knowledge.DecisionMetrics.Scores;
import monada.neuron.evaluation.knowledge.DecisionMetrics.Verdict;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Abstained;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Conditional;
import monada.neuron.evaluation.knowledge.DecisionRecommendation.Decided;
import monada.neuron.evaluation.knowledge.DecisionTrace.Arm;
import monada.neuron.evaluation.knowledge.DecisionTrace.Hit;
import monada.neuron.evaluation.knowledge.DecisionTrace.NeuronTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.format;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.number;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.strings;

/**
 * Renders the scored decision benchmark (Issue #112) as the {@code decisions} payload of the JSON report and as
 * Markdown. Both keep four layers apart: knowledge retrieval (Store), Neuron evidence use, the host-owned decision
 * rules (printed verbatim) and the optional generator. Every row carries its evidence, trace, decision, exclusions,
 * citations, rule and failure diagnostics. Undefined values are JSON {@code null}; nothing here depends on the clock.
 */
final class HtmlDecisionReport {

    /**
     * What this benchmark does not show over {@code cases} with {@code gold}; rendered in both formats. Without an
     * adjudication the labels are preliminary; with one, the limit states who adjudicated them and what stays open.
     */
    static List<String> limits(HtmlDecisionCases cases, HtmlDecisionGold gold,
            Optional<HtmlDecisionAdjudication> adjudication) {
        return List.of(
            "The decisions come from " + DecisionRuleBaseline.VERSION + ", deterministic host rules written by hand for"
                    + " this benchmark. Neuron did not infer them, and a correct decision is not evidence of a learned"
                    + " or general reasoning capability.",
            "The rules read the typed constraints the case author stated; they do not read the requirement text. A"
                    + " rule table written next to the cases can mirror them, so correctness here measures whether the"
                    + " evidence path delivers the cited facts the rules need, not decision intelligence.",
            "Evidence cues are lexical: a cue matches a segment that contains its tokens, which is not understanding"
                    + " the sentence.",
            "A+R is the matched reference for B+R: same rules, same cases, raw Store evidence. Only a difference"
                    + " between them can be attributed to the Neuron path, and only for these " + cases.cases().size()
                    + " cases.",
            adjudication.map(value -> goldLimit(gold, value)).orElse("PRELIMINARY: the gold labels are not"
                    + " independently adjudicated. They were drafted by the author of the rules (Claude) before any"
                    + " system output was inspected and reviewed by the reviewer of record; independent adjudication"
                    + " is issue #152. No result here is independent validation. The gold file is frozen by its hash."),
            "Choice and rationale are judged separately: a decision is 'grounded' only when every claim quotes an"
                    + " eligible span exactly and a citation overlaps a gold span. The host reason text is a fixed"
                    + " per-rule template and is reported, not judged.",
            "Arm C runs only when a generator is configured; no model, provider or network is used by default, and a"
                    + " TEST_DOUBLE is never scored for decision quality.",
            "The corpus is small and authored (html-kc-v1 with the html-kd-v1 supplement); results do not transfer to"
                    + " other corpora, and no accuracy threshold gates CI.");
    }

    private static String goldLimit(HtmlDecisionGold gold, HtmlDecisionAdjudication adjudication) {
        return "Gold " + gold.version() + ": every item of every label was adjudicated (" + adjudication.agreements()
                + " agree, " + adjudication.changes() + " change) by " + String.join(", ", adjudication.reviewers())
                + ", who did not write " + DecisionRuleBaseline.VERSION + " or draft the labels, from the cases and"
                + " the corpus, without the rules or any system output; " + HtmlDecisionAdjudication.VERSION
                + " and the gold are frozen by their hashes. Independence is by authorship: the labels were drafted"
                + " by the rule author and the adjudicator judged that draft. Results are comparable only within"
                + " one gold version.";
    }

    private final Scores scores;
    private final HtmlDecisionCases cases;
    private final HtmlDecisionGold gold;
    private final Optional<HtmlDecisionAdjudication> adjudication;
    private final DecisionSelector selector;
    private final Optional<GroundedAnswerGenerator> generator;

    HtmlDecisionReport(Scores scores, HtmlDecisionCases cases, HtmlDecisionGold gold,
            Optional<HtmlDecisionAdjudication> adjudication, DecisionSelector selector,
            Optional<GroundedAnswerGenerator> generator) {
        this.scores = Objects.requireNonNull(scores, "scores must not be null");
        this.cases = Objects.requireNonNull(cases, "cases must not be null");
        this.gold = Objects.requireNonNull(gold, "gold must not be null");
        this.adjudication = Objects.requireNonNull(adjudication, "adjudication must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
    }

    String toJson() {
        var json = new StringBuilder("{ \"extension\": ").append(string(HtmlDecisionExperiment.EXTENSION));
        json.append(", \"limits\": ").append(strings(limits(cases, gold, adjudication)));
        json.append(", \"layers\": { \"knowledgeRetrieval\": ").append(string(Arm.STORE.description()))
                .append(", \"neuronEvidenceUse\": ").append(string(Arm.NEURON_RULES.description()))
                .append(", \"hostRules\": ").append(string(selector.kind() + " " + selector.name() + ": "
                        + selector.description()))
                .append(", \"generator\": ").append(generator.map(value -> string(value.kind() + " " + value.name()
                        + ": " + value.description())).orElse("null")).append(" }");
        json.append(", \"rules\": ").append(selector instanceof DecisionRuleBaseline
                ? strings(DecisionRuleBaseline.RULES.stream().map(DecisionRuleBaseline.Rule::render).toList())
                : "[]");
        json.append(", \"cases\": { \"version\": ").append(string(cases.version())).append(", \"hash\": ")
                .append(string(cases.caseHash())).append(", \"count\": ").append(cases.cases().size())
                .append(", \"pairs\": ").append(cases.pairs().size()).append(" }");
        json.append(", \"gold\": { \"version\": ").append(string(gold.version())).append(", \"hash\": ")
                .append(string(gold.hash())).append(" }");
        json.append(", \"adjudication\": ").append(adjudication.map(value -> "{ \"version\": "
                + string(HtmlDecisionAdjudication.VERSION) + ", \"hash\": " + string(value.hash())
                + ", \"reviewers\": " + strings(List.copyOf(value.reviewers())) + ", \"agree\": "
                + value.agreements() + ", \"change\": " + value.changes() + " }").orElse("null"));
        json.append(", \"aggregates\": [ ").append(scores.aggregates().stream().map(this::aggregateJson)
                .collect(Collectors.joining(", "))).append(" ]");
        json.append(", \"pairs\": [ ").append(scores.pairs().stream().map(this::pairJson)
                .collect(Collectors.joining(", "))).append(" ]");
        json.append(", \"rows\": [ ").append(scores.scored().stream().map(this::rowJson)
                .collect(Collectors.joining(", "))).append(" ] }");
        return json.toString();
    }

    private String aggregateJson(ArmAggregate aggregate) {
        return "{ \"arm\": " + string(aggregate.arm().label()) + ", \"rows\": " + aggregate.rows() + ", \"judged\": "
                + aggregate.judged() + ", \"correct\": " + aggregate.correct() + ", \"correctGrounded\": "
                + aggregate.correctGrounded() + ", \"verdicts\": { "
                + aggregate.verdicts().entrySet().stream().map(entry -> string(entry.getKey().name()) + ": "
                        + entry.getValue()).collect(Collectors.joining(", ")) + " }, \"metrics\": { "
                + aggregate.metrics().entrySet().stream().map(entry -> string(entry.getKey()) + ": "
                        + number(entry.getValue())).collect(Collectors.joining(", ")) + " }, \"flagged\": { "
                + aggregate.flagged().entrySet().stream().map(entry -> string(entry.getKey()) + ": "
                        + entry.getValue()).collect(Collectors.joining(", ")) + " }, \"pairsConsistent\": "
                + aggregate.pairsConsistent() + ", \"pairsScored\": " + aggregate.pairsScored() + " }";
    }

    private String pairJson(PairScore pair) {
        return "{ \"pair\": " + string(pair.pair().pairId()) + ", \"arm\": " + string(pair.arm().label())
                + ", \"cases\": " + strings(List.of(pair.pair().first(), pair.pair().second()))
                + ", \"varied\": " + string(pair.pair().varied().label()) + ", \"expectedChange\": "
                + pair.expectedChange() + ", \"observedChange\": " + pair.observedChange() + ", \"scored\": "
                + pair.scored() + ", \"consistent\": " + pair.consistent() + " }";
    }

    private String rowJson(Scored scored) {
        var row = scored.row();
        var expected = scored.gold();
        return "{ \"caseId\": " + string(row.caseId()) + ", \"arm\": " + string(row.arm().label())
                + ", \"selector\": " + string(row.selector()) + ", \"kind\": " + string(row.kind())
                + ", \"constraints\": " + string(cases.byId(row.caseId()).renderConstraints())
                + ", \"evidence\": [ " + row.evidence().stream().map(hit -> "{ \"chunkId\": " + string(hit.chunkId())
                        + ", \"rank\": " + hit.rank() + ", \"score\": " + number(hit.score()) + " }")
                        .collect(Collectors.joining(", ")) + " ]"
                + ", \"neuron\": " + row.neuron().map(this::neuronJson).orElse("null")
                + ", \"outcome\": " + string(row.outcome().name())
                + ", \"recommendation\": " + row.recommendation().map(this::recommendationJson).orElse("null")
                + ", \"claims\": [ " + row.claims().stream().map(claim -> "{ \"text\": " + string(claim.text())
                        + ", \"citations\": [ " + claim.citations().stream().map(this::citedJson)
                        .collect(Collectors.joining(", ")) + " ] }").collect(Collectors.joining(", ")) + " ]"
                + ", \"exclusionCitations\": [ " + row.exclusionCitations().stream().map(this::citedJson)
                        .collect(Collectors.joining(", ")) + " ]"
                + ", \"detail\": " + string(row.detail())
                + ", \"gold\": { \"expected\": " + string(expected.expected().name()) + ", \"acceptable\": "
                        + strings(expected.acceptable().stream().sorted().toList()) + ", \"options\": "
                        + strings(expected.options().stream().map(HtmlDecisionGold.Option::render).toList())
                        + ", \"permissions\": " + string(expected.permissions().name()) + ", \"rationale\": "
                        + string(expected.rationale()) + " }"
                + ", \"verdict\": " + string(scored.verdict().name())
                + ", \"metrics\": { " + scored.metrics().entrySet().stream().map(entry -> string(entry.getKey()) + ": "
                        + number(entry.getValue())).collect(Collectors.joining(", ")) + " }"
                + ", \"flags\": { " + scored.flags().entrySet().stream().map(entry -> string(entry.getKey()) + ": "
                        + entry.getValue()).collect(Collectors.joining(", ")) + " }"
                + ", \"diagnostics\": " + strings(scored.diagnostics()) + " }";
    }

    private String neuronJson(NeuronTrace trace) {
        return "{ \"decision\": " + string(trace.decision().name()) + ", \"querySignals\": " + trace.querySignals()
                + ", \"admitted\": " + strings(trace.admitted()) + ", \"selected\": " + strings(trace.selected())
                + " }";
    }

    private String recommendationJson(DecisionRecommendation recommendation) {
        return switch (recommendation) {
            case Decided decided -> "{ \"type\": \"DECIDED\", \"ruleId\": " + string(decided.ruleId())
                    + ", \"choice\": " + string(decided.choice()) + ", \"alsoValid\": " + strings(decided.alsoValid())
                    + ", \"constraintsUsed\": " + strings(decided.constraintsUsed()) + ", \"permissions\": "
                    + strings(decided.permissions()) + ", \"reason\": " + string(decided.reason())
                    + ", \"excluded\": " + exclusionsJson(decided.excluded()) + " }";
            case Conditional conditional -> "{ \"type\": \"CONDITIONAL\", \"ruleId\": "
                    + string(conditional.ruleId()) + ", \"options\": [ " + conditional.options().stream()
                    .map(option -> "{ \"choice\": " + string(option.choice()) + ", \"assumption\": "
                            + string(option.assumption()) + ", \"statement\": " + string(option.statement()) + " }")
                    .collect(Collectors.joining(", ")) + " ], \"constraintsUsed\": "
                    + strings(conditional.constraintsUsed()) + ", \"excluded\": "
                    + exclusionsJson(conditional.excluded()) + " }";
            case Abstained abstained -> "{ \"type\": \"ABSTAINED\", \"ruleId\": " + string(abstained.ruleId())
                    + ", \"reason\": " + string(abstained.reason()) + ", \"clarifyingQuestion\": "
                    + (abstained.clarifyingQuestion().equals("-") ? "null" : string(abstained.clarifyingQuestion()))
                    + " }";
        };
    }

    private String exclusionsJson(List<DecisionRecommendation.Exclusion> exclusions) {
        return "[ " + exclusions.stream().map(exclusion -> "{ \"choice\": " + string(exclusion.choice())
                + ", \"reason\": " + string(exclusion.reason()) + ", \"citations\": " + strings(exclusion.citations()
                        .stream().map(GroundedAnswer.Citation::render).toList()) + " }")
                .collect(Collectors.joining(", ")) + " ]";
    }

    private String citedJson(AnswerTrace.CitedSpan span) {
        return "{ \"citation\": " + string(span.citation().render()) + ", \"eligible\": " + span.eligible()
                + ", \"exactQuote\": " + span.exactQuote() + " }";
    }

    String toMarkdown() {
        var md = new StringBuilder("## HTML design decisions (" + HtmlDecisionExperiment.EXTENSION + ")\n\n");
        md.append("Cases `").append(cases.version()).append("` (").append(cases.cases().size()).append(" cases, ")
                .append(cases.pairs().size()).append(" contrastive pairs, hash `").append(cases.caseHash())
                .append("`); gold `").append(gold.version()).append("` (hash `").append(gold.hash())
                .append("`); ").append(adjudication.map(value -> "adjudicated by " + String.join(", ",
                        value.reviewers()) + " (" + value.agreements() + " agree, " + value.changes()
                        + " change, hash `" + value.hash() + "`)").orElse("not independently adjudicated"))
                .append(".\n\n");

        md.append("### Knowledge retrieval (arm A, Store only)\n\n").append(Arm.STORE.description()).append(".\n\n");
        md.append("| Case | Store top-").append(HtmlDecisionExperiment.K).append(" | Gold chunks retrieved |\n")
                .append("| :--- | :--- | ---: |\n");
        rows(Arm.STORE).forEach(scored -> md.append("| ").append(scored.row().caseId()).append(" | ")
                .append(hits(scored.row().evidence())).append(" | ")
                .append(format(scored.metrics().get("evidenceCoverage"))).append(" |\n"));

        md.append("\n### Neuron evidence use (arm B+R)\n\n").append(Arm.NEURON_RULES.description()).append(".\n\n");
        md.append("| Case | Cycle decision | Admitted | Selected for the rules | Gold chunks seen |\n")
                .append("| :--- | :--- | :--- | :--- | ---: |\n");
        rows(Arm.NEURON_RULES).forEach(scored -> {
            var trace = scored.row().neuron();
            md.append("| ").append(scored.row().caseId()).append(" | ")
                    .append(trace.map(value -> value.decision().name()).orElse("n/a")).append(" | ")
                    .append(trace.map(value -> String.join(", ", value.admitted())).orElse("")).append(" | ")
                    .append(trace.map(value -> String.join(", ", value.selected())).orElse("")).append(" | ")
                    .append(format(scored.metrics().get("evidenceCoverage"))).append(" |\n");
        });

        md.append("\n### Host-owned decision rules (evaluation-only)\n\n");
        md.append("`").append(selector.name()).append("` is ").append(selector.kind()).append(": ")
                .append(selector.description()).append(". These rules were written by hand; Neuron did not infer"
                        + " them.\n\n");
        if (selector instanceof DecisionRuleBaseline) {
            DecisionRuleBaseline.RULES.forEach(rule -> md.append("- `").append(rule.render().replace("`", "'"))
                    .append("`\n"));
        }

        md.append("\n### Results per arm\n\n");
        md.append("| Arm | Judged | Correct | Correct, grounded rationale | Evidence-miss abstentions |"
                + " Wrong abstentions | Wrong choice |"
                + " Overconfident | Hedged/incomplete/overbroad | Contradictions | No-evidence certainty | Unrequested"
                + " permission | Citation fidelity | Unsupported claims | Pairs consistent |\n");
        md.append("| :--- |").append(" ---: |".repeat(14)).append('\n');
        for (var aggregate : scores.aggregates()) {
            if (aggregate.arm() == Arm.STORE || aggregate.arm() == Arm.GENERATOR) {
                continue;
            }
            var v = aggregate.verdicts();
            md.append("| ").append(aggregate.arm().label()).append(" | ").append(aggregate.judged()).append(" | ")
                    .append(aggregate.correct()).append(" | ").append(aggregate.correctGrounded()).append(" | ")
                    .append(count(v, Verdict.EVIDENCE_MISS_ABSTENTION)).append(" | ")
                    .append(count(v, Verdict.WRONG_ABSTENTION)).append(" | ")
                    .append(count(v, Verdict.WRONG_CHOICE, Verdict.UNLISTED_CHOICE)).append(" | ")
                    .append(count(v, Verdict.OVERCONFIDENT)).append(" | ")
                    .append(count(v, Verdict.HEDGED, Verdict.INCOMPLETE_CONDITIONAL, Verdict.OVERBROAD_CONDITIONAL))
                    .append(" | ")
                    .append(aggregate.flagged().get("contradiction")).append(" | ")
                    .append(aggregate.flagged().get("noEvidenceCertainty")).append(" | ")
                    .append(aggregate.flagged().get("unrequestedPermission")).append(" | ")
                    .append(format(aggregate.metrics().get("citationFidelity"))).append(" | ")
                    .append(format(aggregate.metrics().get("unsupportedClaimRate"))).append(" | ")
                    .append(DecisionMetrics.PAIRED_ARMS.contains(aggregate.arm())
                            ? aggregate.pairsConsistent() + "/" + aggregate.pairsScored() : "n/a").append(" |\n");
        }

        md.append("\n### Decisions per case\n\n");
        md.append("| Case | Gold | A+R | B+R | R0 | B+R answer | B+R diagnostics |\n")
                .append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
        for (var decisionCase : cases.cases()) {
            var b = find(decisionCase.caseId(), Arm.NEURON_RULES);
            md.append("| ").append(decisionCase.caseId()).append(" | ").append(b.gold().expected())
                    .append(b.gold().acceptable().isEmpty() ? "" : " " + String.join("/",
                            b.gold().acceptable().stream().sorted().toList())).append(" | ")
                    .append(find(decisionCase.caseId(), Arm.STORE_RULES).verdict()).append(" | ")
                    .append(b.verdict()).append(" | ")
                    .append(find(decisionCase.caseId(), Arm.NO_EVIDENCE_RULES).verdict()).append(" | ")
                    .append(b.row().recommendation().map(HtmlDecisionReport::answer).orElse(b.row().outcome().name()))
                    .append(" | ").append(b.diagnostics().isEmpty() ? "-" : String.join("; ", b.diagnostics())
                            .replace("|", "\\|")).append(" |\n");
        }

        md.append("\n### Contrastive pairs\n\nR0 abstains by design and is not paired.\n\n"
                + "| Pair | Varied | Expected change | A+R | B+R |\n")
                .append("| :--- | :--- | :--- | :--- | :--- |\n");
        for (var pair : cases.pairs()) {
            var byArm = scores.pairs().stream().filter(score -> score.pair().equals(pair)).toList();
            md.append("| ").append(pair.pairId()).append(" | ").append(pair.varied().label()).append(" | ")
                    .append(byArm.getFirst().expectedChange()).append(" | ")
                    .append(pairCell(byArm, Arm.STORE_RULES)).append(" | ").append(pairCell(byArm, Arm.NEURON_RULES))
                    .append(" |\n");
        }

        md.append("\n### Optional generator (arm C)\n\n");
        if (generator.isEmpty()) {
            md.append("Not configured: arm C did not run, and no generator result is reported.\n");
        } else {
            var aggregate = scores.aggregates().stream().filter(value -> value.arm() == Arm.GENERATOR).findFirst();
            md.append("`").append(generator.get().name()).append("` (").append(generator.get().kind()).append("): ")
                    .append(generator.get().description()).append(". Free text, never scored for decision quality;"
                            + " citation fidelity ")
                    .append(aggregate.map(value -> format(value.metrics().get("citationFidelity"))).orElse("n/a"))
                    .append(".\n");
        }

        md.append("\n### Limits\n\n");
        limits(cases, gold, adjudication).forEach(limit -> md.append("- ").append(limit).append('\n'));
        return md.toString();
    }

    private static String answer(DecisionRecommendation recommendation) {
        return switch (recommendation) {
            case Decided decided -> decided.ruleId() + ": " + decided.choice()
                    + (decided.alsoValid().isEmpty() ? "" : " (or " + String.join(", ", decided.alsoValid()) + ")")
                    + (decided.permissions().isEmpty() ? "" : " grants " + String.join(", ", decided.permissions()));
            case Conditional conditional -> conditional.ruleId() + ": " + conditional.options().stream()
                    .map(option -> option.choice() + " if " + option.assumption()).collect(Collectors.joining("; "));
            case Abstained abstained -> abstained.ruleId() + ": abstain (" + abstained.reason()
                    + (abstained.clarifyingQuestion().equals("-") ? "" : "; ask: " + abstained.clarifyingQuestion())
                    + ")";
        };
    }

    private static String pairCell(List<PairScore> byArm, Arm arm) {
        return byArm.stream().filter(score -> score.arm() == arm).findFirst()
                .map(score -> !score.scored() ? "not scored" : (score.consistent() ? "consistent" : "inconsistent")
                        + " (changed " + score.observedChange() + ")").orElse("n/a");
    }

    private List<Scored> rows(Arm arm) {
        return scores.scored().stream().filter(scored -> scored.row().arm() == arm).toList();
    }

    private Scored find(String caseId, Arm arm) {
        return scores.scored().stream().filter(scored -> scored.row().caseId().equals(caseId)
                && scored.row().arm() == arm).findFirst().orElseThrow();
    }

    private static String hits(List<Hit> hits) {
        return hits.isEmpty() ? "none" : hits.stream().map(Hit::chunkId).collect(Collectors.joining(", "));
    }

    private static int count(Map<Verdict, Integer> verdicts, Verdict... wanted) {
        var total = 0;
        for (var verdict : wanted) {
            total += verdicts.getOrDefault(verdict, 0);
        }
        return total;
    }
}
