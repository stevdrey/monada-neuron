package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.FeedbackKeyVariants.Transform;
import monada.neuron.evaluation.knowledge.FeedbackKeyVariants.Variant;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.RecallMetrics.Aggregate;
import monada.neuron.evaluation.knowledge.RecallMetrics.Outcome;
import monada.neuron.evaluation.knowledge.RecallMetrics.QueryScore;
import monada.neuron.evaluation.knowledge.RecallMetrics.Ranked;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Paired control-versus-treatment comparison of the relevance-feedback experiment (Issue #110, protocol extension
 * {@code feedback-v1}, "Scoring"). For each treatment arm, path and population it pairs every query's control result
 * with its treatment result, both read from the restarted process, and reports outcome transitions, deltas of
 * {@code Recall@5}, {@code Precision@5} and {@code MRR@5}, top-5 churn {@code |Ret_5^T Δ Ret_5^C|}, abstention changes,
 * and every regression and improvement. It describes this synthetic dataset only; nothing here is a generalization.
 *
 * <p>The direct Store path (B1) sends the raw query text, whose key either received events or did not. The Neuron
 * adapter path (B2) sends one Store query per token emitted by the L2 codec, so a B2 row counts how many of those
 * texts have a key that received events under the arm's strategy: a query in an event population with none is
 * {@code not exercised (key mismatch)} and excluded from deltas, and a query in a population without events with some
 * is a {@code keyCollision} and non-comparable, so a zero or a non-zero B2 delta is never misread.
 */
final class FeedbackEffectMetrics {

    /** The evaluated cutoff. */
    static final int K = HtmlKnowledgeRecall.MAX_LIMIT;

    /** Metrics compared per answerable query, in report order. */
    static final List<String> METRICS = List.of("Recall@" + K, "Precision@" + K, "MRR@" + K);

    /** Rank recorded for a relevant chunk that is not in a limit-5 list. */
    static final int ABSENT_RANK = K + 1;

    private FeedbackEffectMetrics() {
    }

    /** A separately labeled population of the comparison. */
    enum Population {
        CALIBRATION_WITH_EVENTS("calibration-with-events", "in-sample (tuned)"),
        CALIBRATION_WITHOUT_EVENTS("calibration-without-events", "leak check"),
        HELD_OUT("held-out", "transfer check"),
        VARIANT_CASE("key-variant/case", "in-sample (tuned)"),
        VARIANT_SPACING("key-variant/spacing", "in-sample (tuned)"),
        VARIANT_PUNCTUATION("key-variant/punctuation", "in-sample (tuned)");

        private final String label;
        private final String role;

        Population(String label, String role) {
            this.label = label;
            this.role = role;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }

        /** Returns how the population may be read. */
        String role() {
            return role;
        }

        /** Returns whether the population's keys, or its sources' keys, received events. */
        boolean withEvents() {
            return this != CALIBRATION_WITHOUT_EVENTS && this != HELD_OUT;
        }

        static Population of(Transform transform) {
            return switch (transform) {
                case CASE -> VARIANT_CASE;
                case SPACING -> VARIANT_SPACING;
                case PUNCTUATION -> VARIANT_PUNCTUATION;
            };
        }
    }

    /** The two reported paths, labeled separately. */
    enum RecallPath {
        DIRECT(Layer.B1, "direct Store (B1)"),
        NEURON(Layer.B2, "Neuron adapter (B2)");

        private final Layer layer;
        private final String label;

        RecallPath(Layer layer, String label) {
            this.layer = layer;
            this.label = label;
        }

        /** Returns the recorded layer of this path. */
        Layer layer() {
            return layer;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }
    }

    /** Whether a paired query enters the deltas. */
    enum Comparability {
        COMPARABLE("comparable"),
        NOT_EXERCISED("not exercised (key mismatch)"),
        KEY_COLLISION("keyCollision (non-comparable)"),
        FAILED("failed in an arm (non-comparable)");

        private final String label;

        Comparability(String label) {
            this.label = label;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }
    }

    /**
     * One query's paired result.
     *
     * @param queryId the query or variant id
     * @param source the judged query whose labels score it (the query itself, or a variant's source)
     * @param population its population
     * @param path the path
     * @param exercisedKeys how many Store query texts of this path have a key that received events in this arm
     * @param comparability whether it enters the deltas
     * @param control the control's limit-5 chunk ids, empty when that arm failed
     * @param treatment the treatment's limit-5 chunk ids, empty when that arm failed
     * @param controlOutcome the control outcome at k = 5, or {@code null} when that arm failed
     * @param treatmentOutcome the treatment outcome at k = 5, or {@code null} when that arm failed
     * @param controlMetrics the control's per-query metrics (answerable queries only)
     * @param treatmentMetrics the treatment's per-query metrics (answerable queries only)
     * @param churn {@code |Ret_5^T Δ Ret_5^C|}
     * @param changed whether the chunk lists or any score bit differs
     * @param orderChangedOnly same chunk set, different order
     * @param scoreChangedOnly same chunks in the same order, some score bit differs
     * @param relevantRanks per relevant chunk, {@code control→treatment} 1-based ranks ({@value #ABSENT_RANK} = absent)
     * @param regression why the pair is a regression, empty when it is not
     * @param improvement why the pair is an improvement, empty when it is not
     */
    record QueryEffect(String queryId, JudgedQuery source, Population population, RecallPath path, int exercisedKeys,
            Comparability comparability, List<String> control, List<String> treatment, Outcome controlOutcome,
            Outcome treatmentOutcome, Map<String, OptionalDouble> controlMetrics,
            Map<String, OptionalDouble> treatmentMetrics, int churn, boolean changed, boolean orderChangedOnly,
            boolean scoreChangedOnly, Map<String, String> relevantRanks, List<String> regression,
            List<String> improvement) {

        QueryEffect {
            control = List.copyOf(control);
            treatment = List.copyOf(treatment);
            controlMetrics = Map.copyOf(controlMetrics);
            treatmentMetrics = Map.copyOf(treatmentMetrics);
            relevantRanks = Map.copyOf(relevantRanks);
            regression = List.copyOf(regression);
            improvement = List.copyOf(improvement);
        }

        /** Returns {@code treatment − control} of {@code metric}, undefined when either is undefined. */
        OptionalDouble delta(String metric) {
            var c = controlMetrics.getOrDefault(metric, OptionalDouble.empty());
            var t = treatmentMetrics.getOrDefault(metric, OptionalDouble.empty());
            return c.isPresent() && t.isPresent() ? OptionalDouble.of(t.getAsDouble() - c.getAsDouble())
                    : OptionalDouble.empty();
        }

        /** Returns the Jaccard distance of the two limit-5 sets, undefined when both are empty. */
        OptionalDouble jaccardDistance() {
            var union = new LinkedHashSet<>(control);
            union.addAll(treatment);
            if (union.isEmpty()) {
                return OptionalDouble.empty();
            }
            var intersection = new HashSet<>(control);
            intersection.retainAll(new HashSet<>(treatment));
            return OptionalDouble.of(1.0 - (double) intersection.size() / union.size());
        }

        /** Returns {@code control → treatment}, or {@code n/a} when an arm failed. */
        String transition() {
            return controlOutcome == null || treatmentOutcome == null ? "n/a"
                    : controlOutcome.name() + " -> " + treatmentOutcome.name();
        }
    }

    /**
     * The aggregates of one population through one path in one treatment arm.
     *
     * @param arm the treatment arm
     * @param path the path
     * @param population the population
     * @param queries every paired query of the population
     * @param comparable the queries that enter the deltas
     * @param control means over comparable answerable queries, by metric
     * @param treatment means over comparable answerable queries, by metric
     * @param delta means of the paired deltas, by metric
     * @param transitions outcome transitions of comparable queries, {@code control -> treatment}
     * @param changed comparable queries whose results differ at all
     * @param meanChurn mean churn over comparable queries
     * @param controlNoAnswerFalsePositiveRate fraction of comparable {@code U} queries with a non-empty control list
     * @param treatmentNoAnswerFalsePositiveRate the same for the treatment
     * @param controlAbstentionRecall {@code |{q ∈ U : Ret(q) = ∅}| / |U|} in the control
     * @param treatmentAbstentionRecall the same in the treatment
     * @param regressions query ids with reasons
     * @param improvements query ids with reasons
     */
    record PopulationEffect(FeedbackArm arm, RecallPath path, Population population, int queries, int comparable,
            Map<String, Aggregate> control, Map<String, Aggregate> treatment, Map<String, Aggregate> delta,
            Map<String, Integer> transitions, int changed, OptionalDouble meanChurn,
            OptionalDouble controlNoAnswerFalsePositiveRate, OptionalDouble treatmentNoAnswerFalsePositiveRate,
            OptionalDouble controlAbstentionRecall, OptionalDouble treatmentAbstentionRecall,
            List<String> regressions, List<String> improvements) {

        PopulationEffect {
            control = Map.copyOf(control);
            treatment = Map.copyOf(treatment);
            delta = Map.copyOf(delta);
            transitions = Map.copyOf(transitions);
            regressions = List.copyOf(regressions);
            improvements = List.copyOf(improvements);
        }
    }

    /** Everything compared for one treatment arm. */
    record ArmEffect(FeedbackArm arm, List<QueryEffect> queries, List<PopulationEffect> populations) {

        ArmEffect {
            queries = List.copyOf(queries);
            populations = List.copyOf(populations);
        }
    }

    /**
     * Compares one treatment arm with the control.
     *
     * @param querySet the labels
     * @param split the calibration / held-out split
     * @param variants every surface variant in the workload
     * @param plan the events every treatment arm received
     * @param arm the treatment arm
     * @param control the control snapshot from the restarted process
     * @param treatment the treatment snapshot from the restarted process
     */
    static ArmEffect compare(HtmlKnowledgeQuerySet querySet, FeedbackQuerySplit split, List<Variant> variants,
            FeedbackEvents plan, FeedbackArm arm, Snapshot control, Snapshot treatment) {
        Objects.requireNonNull(arm, "arm must not be null");
        if (!arm.treatment()) {
            throw new IllegalArgumentException("compare a treatment arm with the control");
        }
        var eventKeys = eventKeys(plan, arm);
        var codec = new TokenPackingSignalCodec();
        var byId = new LinkedHashMap<String, JudgedQuery>();
        querySet.queries().forEach(query -> byId.put(query.queryId(), query));
        var members = new ArrayList<Member>();
        for (var query : querySet.queries()) {
            var population = switch (split.side(query.queryId())) {
                case HELD_OUT -> Population.HELD_OUT;
                case CALIBRATION -> plan.queryIds().contains(query.queryId()) ? Population.CALIBRATION_WITH_EVENTS
                        : Population.CALIBRATION_WITHOUT_EVENTS;
            };
            members.add(new Member(query.queryId(), query.text(), query, population));
        }
        for (var variant : variants) {
            if (plan.queryIds().contains(variant.sourceId())) {
                members.add(new Member(variant.variantId(), variant.text(), byId.get(variant.sourceId()),
                        Population.of(variant.transform())));
            }
        }
        var queries = new ArrayList<QueryEffect>();
        for (var path : RecallPath.values()) {
            for (var member : members) {
                var texts = path == RecallPath.DIRECT ? List.of(member.text()) : codecTexts(codec, member.text());
                var exercised = (int) texts.stream().filter(text -> !text.isBlank())
                        .filter(text -> eventKeys.contains(arm.strategy().effectiveKey(text))).count();
                queries.add(pair(member, path, exercised, control, treatment));
            }
        }
        var populations = new ArrayList<PopulationEffect>();
        for (var path : RecallPath.values()) {
            for (var population : Population.values()) {
                populations.add(aggregate(arm, path, population, queries.stream()
                        .filter(query -> query.path() == path && query.population() == population).toList()));
            }
        }
        return new ArmEffect(arm, queries, populations);
    }

    /** Returns the keys that received events in {@code arm}: each event text's effective key under its strategy. */
    static Set<String> eventKeys(FeedbackEvents plan, FeedbackArm arm) {
        var keys = new HashSet<String>();
        plan.events().forEach(event -> keys.add(arm.strategy().effectiveKey(event.text())));
        return keys;
    }

    /** Returns the Store query texts the L2 codec emits for {@code text}, one per token, as B2 and B2c send them. */
    static List<String> codecTexts(TokenPackingSignalCodec codec, String text) {
        return codec.querySignals(text).stream().map(codec::encode).toList();
    }

    private record Member(String id, String text, JudgedQuery source, Population population) {
    }

    private static QueryEffect pair(Member member, RecallPath path, int exercised, Snapshot control, Snapshot treatment) {
        var controlScore = score(member, path.layer(), control);
        var treatmentScore = score(member, path.layer(), treatment);
        var controlRows = control.rows(member.id(), path.layer(), K);
        var treatmentRows = treatment.rows(member.id(), path.layer(), K);
        Comparability comparability;
        if (!controlScore.completed() || !treatmentScore.completed()) {
            comparability = Comparability.FAILED;
        } else if (path == RecallPath.NEURON && member.population().withEvents() && exercised == 0) {
            comparability = Comparability.NOT_EXERCISED;
        } else if (!member.population().withEvents() && exercised > 0) {
            comparability = Comparability.KEY_COLLISION;
        } else {
            comparability = Comparability.COMPARABLE;
        }
        var c = ids(controlScore);
        var t = ids(treatmentScore);
        var cSet = new HashSet<>(c);
        var tSet = new HashSet<>(t);
        var churn = (int) (c.stream().filter(id -> !tSet.contains(id)).count()
                + t.stream().filter(id -> !cSet.contains(id)).count());
        var changed = !sameRows(controlRows, treatmentRows);
        var relevantRanks = new TreeMap<String, String>();
        for (var chunk : new TreeSet<>(member.source().relevant())) {
            relevantRanks.put(chunk, rank(c, chunk) + "->" + rank(t, chunk));
        }
        var controlOutcome = controlScore.completed() ? controlScore.byK().get(K).outcome() : null;
        var treatmentOutcome = treatmentScore.completed() ? treatmentScore.byK().get(K).outcome() : null;
        var controlMetrics = metrics(controlScore);
        var treatmentMetrics = metrics(treatmentScore);
        var comparable = comparability == Comparability.COMPARABLE;
        return new QueryEffect(member.id(), member.source(), member.population(), path, exercised, comparability, c,
                t, controlOutcome, treatmentOutcome, controlMetrics, treatmentMetrics, churn, changed,
                !c.equals(t) && cSet.equals(tSet), c.equals(t) && changed, relevantRanks,
                comparable ? changes(controlOutcome, treatmentOutcome, controlMetrics, treatmentMetrics, true)
                        : List.of(),
                comparable ? changes(controlOutcome, treatmentOutcome, controlMetrics, treatmentMetrics, false)
                        : List.of());
    }

    /** Whether two row lists hold the same chunks in the same order with bit-exactly equal scores. */
    private static boolean sameRows(List<Row> first, List<Row> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (var i = 0; i < first.size(); i++) {
            if (!first.get(i).chunkId().equals(second.get(i).chunkId())
                    || !HtmlKnowledgeRecall.sameScore(first.get(i).score(), second.get(i).score())) {
                return false;
            }
        }
        return true;
    }

    private static QueryScore score(Member member, Layer layer, Snapshot snapshot) {
        return RecallMetrics.layerScore(member.source(), layer, snapshot.rows(member.id(), layer, K),
                snapshot.rows(member.id(), layer, HtmlKnowledgeRecall.LOOKAHEAD_LIMIT));
    }

    private static List<String> ids(QueryScore score) {
        return score.returned().map(list -> list.stream().map(Ranked::chunkId).toList()).orElse(List.of());
    }

    private static int rank(List<String> ids, String chunk) {
        var index = ids.indexOf(chunk);
        return index < 0 ? ABSENT_RANK : index + 1;
    }

    private static Map<String, OptionalDouble> metrics(QueryScore score) {
        var metrics = new LinkedHashMap<String, OptionalDouble>();
        if (score.completed()) {
            var byName = score.byK().get(K).metrics();
            METRICS.stream().filter(byName::containsKey).forEach(name -> metrics.put(name, byName.get(name)));
        }
        return metrics;
    }

    /**
     * The protocol's regressions from the control to the treatment: a {@code HIT} lost, a {@code CORRECT_ABSTENTION}
     * turned into {@code FALSE_EVIDENCE}, or a lower {@code Recall@5} or {@code MRR@5}. With {@code regressions} false it
     * returns the mirror image, the improvements.
     */
    static List<String> changes(Outcome control, Outcome treatment, Map<String, OptionalDouble> controlMetrics,
            Map<String, OptionalDouble> treatmentMetrics, boolean regressions) {
        var reasons = new ArrayList<String>();
        var better = regressions ? control : treatment;
        var worse = regressions ? treatment : control;
        if (better == Outcome.HIT && worse != Outcome.HIT) {
            reasons.add(control + " -> " + treatment);
        }
        if (better == Outcome.CORRECT_ABSTENTION && worse == Outcome.FALSE_EVIDENCE) {
            reasons.add(control + " -> " + treatment);
        }
        for (var metric : List.of("Recall@" + K, "MRR@" + K)) {
            var c = controlMetrics.getOrDefault(metric, OptionalDouble.empty());
            var t = treatmentMetrics.getOrDefault(metric, OptionalDouble.empty());
            if (c.isPresent() && t.isPresent() && (regressions ? t.getAsDouble() < c.getAsDouble()
                    : t.getAsDouble() > c.getAsDouble())) {
                reasons.add(metric + " " + format(c.getAsDouble()) + " -> " + format(t.getAsDouble()));
            }
        }
        return reasons;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Formats a signed value with three decimals. */
    static String signed(double value) {
        return String.format(Locale.ROOT, "%+.3f", value);
    }

    private static PopulationEffect aggregate(FeedbackArm arm, RecallPath path, Population population,
            List<QueryEffect> queries) {
        var comparable = queries.stream().filter(query -> query.comparability() == Comparability.COMPARABLE).toList();
        var answerable = comparable.stream().filter(query -> !query.source().relevant().isEmpty()).toList();
        var unanswerable = comparable.stream().filter(query -> query.source().relevant().isEmpty()).toList();
        var control = new LinkedHashMap<String, Aggregate>();
        var treatment = new LinkedHashMap<String, Aggregate>();
        var delta = new LinkedHashMap<String, Aggregate>();
        for (var metric : METRICS) {
            control.put(metric, RecallMetrics.mean(answerable.stream()
                    .map(query -> query.controlMetrics().getOrDefault(metric, OptionalDouble.empty())).toList()));
            treatment.put(metric, RecallMetrics.mean(answerable.stream()
                    .map(query -> query.treatmentMetrics().getOrDefault(metric, OptionalDouble.empty())).toList()));
            delta.put(metric, RecallMetrics.mean(answerable.stream().map(query -> query.delta(metric)).toList()));
        }
        var transitions = new TreeMap<String, Integer>();
        comparable.forEach(query -> transitions.merge(query.transition(), 1, Integer::sum));
        var churn = comparable.stream().mapToInt(QueryEffect::churn).average();
        return new PopulationEffect(arm, path, population, queries.size(), comparable.size(), control, treatment,
                delta, transitions, (int) comparable.stream().filter(QueryEffect::changed).count(),
                churn.isPresent() ? OptionalDouble.of(churn.getAsDouble()) : OptionalDouble.empty(),
                RecallMetrics.ratio((int) unanswerable.stream().filter(query -> !query.control().isEmpty()).count(),
                        unanswerable.size()),
                RecallMetrics.ratio((int) unanswerable.stream().filter(query -> !query.treatment().isEmpty()).count(),
                        unanswerable.size()),
                RecallMetrics.ratio((int) unanswerable.stream().filter(query -> query.control().isEmpty()).count(),
                        unanswerable.size()),
                RecallMetrics.ratio((int) unanswerable.stream().filter(query -> query.treatment().isEmpty()).count(),
                        unanswerable.size()),
                comparable.stream().filter(query -> !query.regression().isEmpty())
                        .map(query -> query.queryId() + ": " + String.join(", ", query.regression())).toList(),
                comparable.stream().filter(query -> !query.improvement().isEmpty())
                        .map(query -> query.queryId() + ": " + String.join(", ", query.improvement())).toList());
    }
}
