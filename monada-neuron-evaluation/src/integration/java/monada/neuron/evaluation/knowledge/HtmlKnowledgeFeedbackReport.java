package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.ArmEffect;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.RecallPath;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.PopulationEffect;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.QueryEffect;
import monada.neuron.evaluation.knowledge.FeedbackKeyVariants.Variant;
import monada.neuron.evaluation.knowledge.RecallMetrics.Aggregate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.aggregateJson;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.format;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.number;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.string;
import static monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallReport.strings;

/**
 * Renders the relevance-feedback experiment (Issue #110) as machine-readable JSON and a Markdown summary: run identity
 * and hashes, the arms and their persisted logs, the split, every event, the paired comparisons per arm, path and
 * population, every per-query pair, diagnostics, follow-up candidates and the scope statement. The direct Store path
 * and the Neuron adapter path are always rendered as separate sections. Undefined values are JSON {@code null} and
 * {@code n/a} in Markdown. Store-owned atom ids are never rendered; events carry chunk ids and opaque references.
 */
final class HtmlKnowledgeFeedbackReport {

    /** The protocol extension this run implements, with the refinements documented by Issue #110. */
    static final String EXTENSION = "feedback-v1";

    /** What this experiment measures, and what it is not; part of every report. */
    static final String SCOPE = "Retrieval-rank feedback inside Monada Resonance Store: query-scoped score adjustments"
            + " recorded through the Store's public MonadaMemory.feedback and persisted in each treatment root. It is"
            + " not fact training, not model-weight training, not Neuron cognitive adaptation"
            + " (FeedbackAdaptationCognitiveStage), not evaluation adaptation and not Forge worker-route adaptation"
            + " (RoutingStateStore), none of which this run touches. Events were chosen from calibration labels only;"
            + " in-sample effects measure that the mechanism acts and persists, not that it generalizes. The deltas"
            + " describe one small synthetic dataset and are not generalized.";

    /**
     * Everything the report renders.
     *
     * @param querySet the judged query set
     * @param split the calibration / held-out split
     * @param variants the surface variants in the workload
     * @param plan the events every treatment arm received
     * @param logs every arm's feedback log as read back in the restarted process
     * @param effects one comparison per treatment arm
     * @param diagnostics named observations that are never checks
     * @param followUps follow-up candidates suggested by what was observed
     */
    record Scored(HtmlKnowledgeQuerySet querySet, FeedbackQuerySplit split, List<Variant> variants,
            FeedbackEvents plan, Map<FeedbackArm, FeedbackEventLog> logs, List<ArmEffect> effects,
            Map<String, String> diagnostics, List<String> followUps) {

        Scored {
            Objects.requireNonNull(querySet, "querySet must not be null");
            Objects.requireNonNull(split, "split must not be null");
            variants = List.copyOf(variants);
            Objects.requireNonNull(plan, "plan must not be null");
            logs = Map.copyOf(logs);
            effects = List.copyOf(effects);
            diagnostics = Map.copyOf(diagnostics);
            followUps = List.copyOf(followUps);
        }
    }

    private final HtmlKnowledgeCorpus corpus;
    private final Scored scored;

    HtmlKnowledgeFeedbackReport(HtmlKnowledgeCorpus corpus, Scored scored) {
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.scored = Objects.requireNonNull(scored, "scored must not be null");
    }

    /** Renders the scored payload as JSON. */
    String toJson() {
        var plan = scored.plan();
        var json = new StringBuilder("{\n");
        json.append("  \"extension\": ").append(string(EXTENSION)).append(",\n");
        json.append("  \"corpusHash\": ").append(string(corpus.corpusHash())).append(",\n");
        json.append("  \"queryVersion\": ").append(string(scored.querySet().version())).append(",\n");
        json.append("  \"queryHash\": ").append(string(scored.querySet().queryHash())).append(",\n");
        json.append("  \"splitVersion\": ").append(string(FeedbackQuerySplit.VERSION)).append(",\n");
        json.append("  \"splitHash\": ").append(string(scored.split().splitHash())).append(",\n");
        json.append("  \"variantVersion\": ").append(string(FeedbackKeyVariants.VERSION)).append(",\n");
        json.append("  \"variantHash\": ").append(string(FeedbackKeyVariants.variantHash(scored.variants())))
                .append(",\n");
        json.append("  \"feedbackVersion\": ").append(string(FeedbackEvents.VERSION)).append(",\n");
        json.append("  \"feedbackHash\": ").append(string(plan.feedbackHash())).append(",\n");
        json.append("  \"eventCount\": ").append(plan.events().size())
                .append(", \"positiveEvents\": ").append(plan.count(FeedbackSignal.POSITIVE))
                .append(", \"negativeEvents\": ").append(plan.count(FeedbackSignal.NEGATIVE))
                .append(", \"queriesWithEvents\": ").append(plan.queryIds().size()).append(",\n");
        json.append("  \"k\": ").append(FeedbackEffectMetrics.K).append(", \"threshold\": ")
                .append(number(HtmlKnowledgeRecall.THRESHOLD)).append(",\n");
        json.append("  \"scope\": ").append(string(SCOPE)).append(",\n");
        json.append("  \"arms\": [\n");
        var arms = List.of(FeedbackArm.values());
        for (var a = 0; a < arms.size(); a++) {
            var arm = arms.get(a);
            var log = scored.logs().get(arm);
            json.append("    { \"arm\": ").append(string(arm.label()))
                    .append(", \"keyStrategy\": ").append(string(arm.strategy().label()))
                    .append(", \"receivesEvents\": ").append(arm.treatment());
            if (log != null) {
                json.append(", \"storedEvents\": ").append(log.events().size())
                        .append(", \"eventLogHash\": ").append(string(log.eventLogHash()))
                        .append(", \"logSha256\": ").append(string(log.logSha256()))
                        .append(", \"logBytes\": ").append(log.logBytes());
            }
            json.append(" }").append(a < arms.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"split\": [\n");
        var queries = scored.querySet().queries();
        for (var q = 0; q < queries.size(); q++) {
            var query = queries.get(q);
            json.append("    { \"queryId\": ").append(string(query.queryId()))
                    .append(", \"category\": ").append(string(query.category().label()))
                    .append(", \"side\": ").append(string(scored.split().side(query.queryId()).label()))
                    .append(", \"groupId\": ").append(string(scored.split().groupOf(query.queryId())))
                    .append(", \"events\": ").append(plan.events().stream()
                            .filter(event -> event.queryId().equals(query.queryId())).count())
                    .append(" }").append(q < queries.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"events\": [\n");
        var events = plan.events();
        for (var e = 0; e < events.size(); e++) {
            var event = events.get(e);
            json.append("    { \"queryId\": ").append(string(event.queryId()))
                    .append(", \"chunkId\": ").append(string(event.chunkId()))
                    .append(", \"signal\": ").append(string(event.signal().name()))
                    .append(", \"queryKeys\": {");
            for (var t = 0; t < FeedbackArm.treatments().size(); t++) {
                var arm = FeedbackArm.treatments().get(t);
                json.append(t == 0 ? " " : ", ").append(string(arm.label())).append(": ")
                        .append(string(arm.strategy().effectiveKey(event.text())));
            }
            json.append(" }, \"opaqueReference\": ").append(string(opaqueReference(event))).append(" }")
                    .append(e < events.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"comparisons\": [\n");
        var populations = scored.effects().stream().flatMap(effect -> effect.populations().stream()).toList();
        for (var p = 0; p < populations.size(); p++) {
            json.append("    ").append(populationJson(populations.get(p)))
                    .append(p < populations.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"queries\": [\n");
        var pairs = scored.effects().stream()
                .flatMap(effect -> effect.queries().stream().map(query -> queryJson(effect.arm(), query))).toList();
        for (var p = 0; p < pairs.size(); p++) {
            json.append("    ").append(pairs.get(p)).append(p < pairs.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n  \"diagnostics\": {");
        var first = true;
        for (var entry : new TreeMap<>(scored.diagnostics()).entrySet()) {
            json.append(first ? "\n    " : ",\n    ").append(string(entry.getKey())).append(": ")
                    .append(string(entry.getValue()));
            first = false;
        }
        json.append(first ? "},\n" : "\n  },\n");
        json.append("  \"followUps\": ").append(strings(scored.followUps())).append("\n}");
        return json.toString();
    }

    private String opaqueReference(FeedbackEvents.Event event) {
        var log = scored.logs().get(FeedbackArm.TREATMENT_EXACT);
        if (log == null) {
            return HtmlKnowledgeRecall.UNMAPPED;
        }
        return log.events().stream().filter(stored -> stored.chunkId().equals(event.chunkId())).findFirst()
                .map(FeedbackEventLog.StoredEvent::opaqueReference).orElse(HtmlKnowledgeRecall.UNMAPPED);
    }

    private static String populationJson(PopulationEffect effect) {
        return "{ \"arm\": " + string(effect.arm().label())
                + ", \"keyStrategy\": " + string(effect.arm().strategy().label())
                + ", \"path\": " + string(effect.path().label())
                + ", \"population\": " + string(effect.population().label())
                + ", \"role\": " + string(effect.population().role())
                + ", \"queries\": " + effect.queries()
                + ", \"comparable\": " + effect.comparable()
                + ", \"changed\": " + effect.changed()
                + ", \"meanChurn\": " + number(effect.meanChurn())
                + ",\n      \"control\": " + aggregates(effect.control())
                + ",\n      \"treatment\": " + aggregates(effect.treatment())
                + ",\n      \"delta\": " + aggregates(effect.delta())
                + ",\n      \"transitions\": " + effect.transitions().entrySet().stream()
                        .map(entry -> string(entry.getKey()) + ": " + entry.getValue())
                        .collect(Collectors.joining(", ", "{ ", " }"))
                + ", \"NoAnswerFalsePositiveRate@5\": { \"control\": "
                + number(effect.controlNoAnswerFalsePositiveRate()) + ", \"treatment\": "
                + number(effect.treatmentNoAnswerFalsePositiveRate()) + " }"
                + ", \"AbstentionRecall\": { \"control\": " + number(effect.controlAbstentionRecall())
                + ", \"treatment\": " + number(effect.treatmentAbstentionRecall()) + " }"
                + ",\n      \"regressions\": " + strings(effect.regressions())
                + ", \"improvements\": " + strings(effect.improvements()) + " }";
    }

    private static String aggregates(Map<String, Aggregate> values) {
        return FeedbackEffectMetrics.METRICS.stream()
                .map(metric -> string(metric) + ": " + aggregateJson(values.get(metric)))
                .collect(Collectors.joining(", ", "{ ", " }"));
    }

    private static String queryJson(FeedbackArm arm, QueryEffect query) {
        return "{ \"arm\": " + string(arm.label())
                + ", \"path\": " + string(query.path().label())
                + ", \"queryId\": " + string(query.queryId())
                + ", \"sourceId\": " + string(query.source().queryId())
                + ", \"category\": " + string(query.source().category().label())
                + ", \"answerability\": " + string(query.source().answerability().name())
                + ", \"required\": " + strings(new TreeSet<>(query.source().required()))
                + ", \"supporting\": " + strings(new TreeSet<>(query.source().supporting()))
                + ", \"population\": " + string(query.population().label())
                + ", \"exercisedKeys\": " + query.exercisedKeys()
                + ", \"comparability\": " + string(query.comparability().label())
                + ", \"control\": " + strings(query.control())
                + ", \"treatment\": " + strings(query.treatment())
                + ", \"transition\": " + string(query.transition())
                + ", \"delta\": " + FeedbackEffectMetrics.METRICS.stream()
                        .map(metric -> string(metric) + ": " + number(query.delta(metric)))
                        .collect(Collectors.joining(", ", "{ ", " }"))
                + ", \"churn\": " + query.churn()
                + ", \"jaccardDistance\": " + number(query.jaccardDistance())
                + ", \"changed\": " + query.changed()
                + ", \"orderChangedOnly\": " + query.orderChangedOnly()
                + ", \"scoreChangedOnly\": " + query.scoreChangedOnly()
                + ", \"relevantRanks\": " + query.relevantRanks().entrySet().stream()
                        .map(entry -> string(entry.getKey()) + ": " + string(entry.getValue()))
                        .collect(Collectors.joining(", ", "{ ", " }"))
                + ", \"regression\": " + strings(query.regression())
                + ", \"improvement\": " + strings(query.improvement())
                + ",\n      \"controlResult\": " + HtmlKnowledgeRecallReport.queryLayerJson(query.controlScore())
                + ",\n      \"treatmentResult\": " + HtmlKnowledgeRecallReport.queryLayerJson(query.treatmentScore())
                + " }";
    }

    /** Renders the scored payload as Markdown. */
    String toMarkdown() {
        var plan = scored.plan();
        var md = new StringBuilder("## Relevance feedback versus a no-feedback control\n\n");
        md.append("> ").append(SCOPE).append("\n\n");
        md.append("- Extension: `").append(EXTENSION).append("`; split `").append(FeedbackQuerySplit.VERSION)
                .append("` `").append(scored.split().splitHash()).append("`; variants `")
                .append(FeedbackKeyVariants.VERSION).append("` `")
                .append(FeedbackKeyVariants.variantHash(scored.variants())).append("`\n");
        md.append("- `feedbackHash` (`").append(FeedbackEvents.VERSION).append("`): `").append(plan.feedbackHash())
                .append("`; ").append(plan.events().size()).append(" events (").append(plan.count(FeedbackSignal.POSITIVE))
                .append(" POSITIVE, ").append(plan.count(FeedbackSignal.NEGATIVE)).append(" NEGATIVE) on ")
                .append(plan.queryIds().size()).append(" calibration queries; k = ").append(FeedbackEffectMetrics.K)
                .append(", t = ").append(HtmlKnowledgeRecall.THRESHOLD).append("\n");
        md.append("- Split: ").append(scored.split().calibration().size()).append(" calibration, ")
                .append(scored.split().heldOut().size()).append(" held-out queries; ")
                .append(scored.variants().size()).append(" surface variants of calibration queries\n\n");
        md.append("| Arm | Key strategy | Stored events | eventLogHash |\n|---|---|---:|---|\n");
        for (var arm : FeedbackArm.values()) {
            var log = scored.logs().get(arm);
            md.append("| ").append(arm.label()).append(" | ").append(arm.strategy().label()).append(" | ")
                    .append(log == null ? "n/a" : Integer.toString(log.events().size())).append(" | ")
                    .append(log == null ? "n/a" : "`" + log.eventLogHash().substring(0, 16) + "…`").append(" |\n");
        }
        for (var path : RecallPath.values()) {
            md.append("\n### ").append(path == RecallPath.DIRECT ? "Direct Store (B1)" : "Neuron adapter (B2)")
                    .append("\n\n");
            if (path == RecallPath.NEURON) {
                md.append("B2 sends one Store query per codec token. A query in an event population whose token keys"
                        + " received no event is `not exercised (key mismatch)` and excluded from deltas; a query in a"
                        + " population without events whose token key did is a `keyCollision` and non-comparable."
                        + "\n\n");
            }
            md.append("| Arm | Population | Role | Comparable / n | Changed | ΔRecall@5 | ΔPrecision@5 | ΔMRR@5"
                    + " | Mean churn | No-answer FP rate C→T | Regressions | Improvements |\n");
            md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---|---:|---:|\n");
            for (var effect : scored.effects()) {
                for (var population : effect.populations()) {
                    if (population.path() != path) {
                        continue;
                    }
                    md.append("| ").append(effect.arm().label()).append(" | ")
                            .append(population.population().label()).append(" | ")
                            .append(population.population().role()).append(" | ")
                            .append(population.comparable()).append(" / ").append(population.queries()).append(" | ")
                            .append(population.changed()).append(" | ")
                            .append(signed(population.delta().get("Recall@5"))).append(" | ")
                            .append(signed(population.delta().get("Precision@5"))).append(" | ")
                            .append(signed(population.delta().get("MRR@5"))).append(" | ")
                            .append(format(population.meanChurn())).append(" | ")
                            .append(format(population.controlNoAnswerFalsePositiveRate())).append(" → ")
                            .append(format(population.treatmentNoAnswerFalsePositiveRate())).append(" | ")
                            .append(population.regressions().size()).append(" | ")
                            .append(population.improvements().size()).append(" |\n");
                }
            }
        }
        md.append("\n### Held-out transfer\n\n");
        for (var effect : scored.effects()) {
            for (var path : RecallPath.values()) {
                var heldOut = effect.populations().stream().filter(population -> population.path() == path
                        && population.population() == FeedbackEffectMetrics.Population.HELD_OUT).findFirst()
                        .orElseThrow();
                md.append("- ").append(effect.arm().label()).append(", ").append(path.label()).append(": ")
                        .append(heldOut.changed() == 0
                                ? "no effect: all " + heldOut.comparable() + " comparable held-out queries are"
                                        + " identical to the control (key-scoped feedback did not transfer)"
                                : heldOut.changed() + " of " + heldOut.comparable() + " comparable held-out queries"
                                        + " changed")
                        .append(heldOut.queries() > heldOut.comparable() ? "; " + (heldOut.queries()
                                - heldOut.comparable()) + " non-comparable" : "").append("\n");
            }
        }
        md.append("\n### Regressions\n\n");
        var any = false;
        for (var effect : scored.effects()) {
            for (var population : effect.populations()) {
                for (var regression : population.regressions()) {
                    md.append("- ").append(effect.arm().label()).append(", ").append(population.path().label())
                            .append(", ").append(population.population().label()).append(": ").append(regression)
                            .append("\n");
                    any = true;
                }
            }
        }
        if (!any) {
            md.append("None in any comparable pair.\n");
        }
        md.append("\n### Diagnostics\n\n");
        new TreeMap<>(scored.diagnostics()).forEach((key, value) -> md.append("- `").append(key)
                .append("`: ").append(value).append("\n"));
        md.append("\n### Follow-up candidates\n\n");
        if (scored.followUps().isEmpty()) {
            md.append("None observed.\n");
        }
        scored.followUps().forEach(followUp -> md.append("- ").append(followUp).append("\n"));
        return md.toString();
    }

    private static String signed(Aggregate aggregate) {
        return aggregate.value().isPresent()
                ? FeedbackEffectMetrics.signed(aggregate.value().getAsDouble()) + " (n=" + aggregate.nDefined() + ")"
                : "n/a";
    }

    /** Renders the plan with every arm's stored key, chunk, opaque reference, signal and delta; no atom id. */
    String eventsTsv() {
        var tsv = new StringBuilder("# feedbackHash ").append(scored.plan().feedbackHash()).append('\n')
                .append("arm\tquery\tqueryKey\tchunkId\topaqueReference\tsignal\tdelta\n");
        var rows = 0;
        for (var arm : FeedbackArm.values()) {
            var log = scored.logs().get(arm);
            if (log == null) {
                continue;
            }
            for (var event : log.events()) {
                tsv.append(arm.label()).append('\t').append(HtmlKnowledgeRecall.QueryText.escape(event.query()))
                        .append('\t').append(HtmlKnowledgeRecall.QueryText.escape(event.queryKey())).append('\t')
                        .append(event.chunkId()).append('\t').append(event.opaqueReference()).append('\t')
                        .append(event.signal().name()).append('\t').append(Double.toHexString(event.delta()))
                        .append('\n');
                rows++;
            }
        }
        return tsv.append("end\t").append(rows).append('\n').toString();
    }

}
