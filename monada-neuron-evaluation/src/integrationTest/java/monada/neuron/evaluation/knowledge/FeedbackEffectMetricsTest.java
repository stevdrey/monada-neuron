package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.ArmEffect;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.Comparability;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.Population;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.QueryEffect;
import monada.neuron.evaluation.knowledge.FeedbackEffectMetrics.RecallPath;
import monada.neuron.evaluation.knowledge.FeedbackEvents.Event;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.evaluation.knowledge.RecallMetrics.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackEffectMetricsTest {

    private static final HtmlKnowledgeQuerySet QUERIES = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
    private static final FeedbackQuerySplit SPLIT = FeedbackQuerySplit.of(QUERIES.queries());
    private static final JudgedQuery CALIBRATION = query("q:exact-term-01");
    private static final String RELEVANT = CALIBRATION.required().iterator().next();
    private static final String OTHER = "chk:forms#00";
    private static final String THIRD = "chk:forms#01";

    @Test
    void anImprovedCalibrationQueryReportsDeltasChurnAndTransition() {
        var plan = plan(new Event(CALIBRATION.queryId(), CALIBRATION.text(), RELEVANT, FeedbackSignal.POSITIVE));
        var control = snapshot(rows(CALIBRATION.queryId(), Layer.B1, OTHER, THIRD));
        var treatment = snapshot(rows(CALIBRATION.queryId(), Layer.B1, RELEVANT, OTHER));
        var effect = effect(FeedbackEffectMetrics.compare(QUERIES, SPLIT, List.of(), plan,
                FeedbackArm.TREATMENT_EXACT, control, treatment), CALIBRATION.queryId(), RecallPath.DIRECT);
        assertEquals(Population.CALIBRATION_WITH_EVENTS, effect.population());
        assertEquals(Comparability.COMPARABLE, effect.comparability());
        assertEquals(1, effect.exercisedKeys());
        assertEquals(2, effect.churn());
        assertEquals(1.0 - 1.0 / 3, effect.jaccardDistance().getAsDouble(), 1e-12);
        assertEquals(Outcome.MISS_WITH_RESULTS, effect.controlOutcome());
        assertEquals(1.0, effect.delta("MRR@5").getAsDouble(), 1e-12);
        assertEquals(FeedbackEffectMetrics.ABSENT_RANK + "->1", effect.relevantRanks().get(RELEVANT));
        assertTrue(effect.regression().isEmpty());
        assertFalse(effect.improvement().isEmpty());
    }

    @Test
    void neuronRowsWhoseTokensMissEveryEventKeyAreNotExercised() {
        var plan = plan(new Event(CALIBRATION.queryId(), CALIBRATION.text(), RELEVANT, FeedbackSignal.POSITIVE));
        var rows = rows(CALIBRATION.queryId(), Layer.B2, OTHER);
        var arm = FeedbackEffectMetrics.compare(QUERIES, SPLIT, List.of(), plan, FeedbackArm.TREATMENT_EXACT,
                snapshot(rows), snapshot(rows));
        var effect = effect(arm, CALIBRATION.queryId(), RecallPath.NEURON);
        assertEquals(0, effect.exercisedKeys());
        assertEquals(Comparability.NOT_EXERCISED, effect.comparability());
        var population = arm.populations().stream().filter(value -> value.path() == RecallPath.NEURON
                && value.population() == Population.CALIBRATION_WITH_EVENTS).findFirst().orElseThrow();
        assertEquals(0, population.comparable());
    }

    @Test
    void aHeldOutQueryWhoseCodecTokenSharesAnEventKeyIsAKeyCollision() {
        var heldOut = SPLIT.heldOut().stream().filter(query -> !query.text().isBlank()).findFirst().orElseThrow();
        var token = HtmlTokens.distinct(heldOut.text()).getFirst();
        // An event keyed by one of the held-out query's tokens: under the exact strategy the B2 Store query for that
        // token has the event's key, so B2 for the held-out query is non-comparable, while B1 is not affected.
        var plan = plan(new Event(CALIBRATION.queryId(), token, RELEVANT, FeedbackSignal.POSITIVE));
        var arm = FeedbackEffectMetrics.compare(QUERIES, SPLIT, List.of(), plan, FeedbackArm.TREATMENT_EXACT,
                snapshot(List.of()), snapshot(List.of()));
        assertEquals(Comparability.KEY_COLLISION, effect(arm, heldOut.queryId(), RecallPath.NEURON).comparability());
        assertEquals(Comparability.COMPARABLE, effect(arm, heldOut.queryId(), RecallPath.DIRECT).comparability());
    }

    @Test
    void lostHitsAndLostAbstentionsAreRegressions() {
        assertEquals(List.of("HIT -> PARTIAL"), FeedbackEffectMetrics.changes(Outcome.HIT, Outcome.PARTIAL, Map.of(),
                Map.of(), true));
        assertEquals(List.of("CORRECT_ABSTENTION -> FALSE_EVIDENCE"), FeedbackEffectMetrics.changes(
                Outcome.CORRECT_ABSTENTION, Outcome.FALSE_EVIDENCE, Map.of(), Map.of(), true));
        assertEquals(List.of("MRR@5 1.000 -> 0.500"), FeedbackEffectMetrics.changes(Outcome.HIT, Outcome.HIT,
                Map.of("MRR@5", OptionalDouble.of(1.0)), Map.of("MRR@5", OptionalDouble.of(0.5)), true));
        assertEquals(List.of("PARTIAL -> HIT"), FeedbackEffectMetrics.changes(Outcome.PARTIAL, Outcome.HIT, Map.of(),
                Map.of(), false));
        assertTrue(FeedbackEffectMetrics.changes(Outcome.PARTIAL, Outcome.HIT, Map.of(), Map.of(), true).isEmpty());
    }

    @Test
    void variantsOfQueriesWithEventsAreScoredWithTheirSourceLabels() {
        var plan = plan(new Event(CALIBRATION.queryId(), CALIBRATION.text(), RELEVANT, FeedbackSignal.POSITIVE));
        var variants = FeedbackKeyVariants.of(List.of(new QueryText(CALIBRATION.queryId(), CALIBRATION.text())));
        var caseVariant = variants.getFirst().variantId();
        var arm = FeedbackEffectMetrics.compare(QUERIES, SPLIT, variants, plan, FeedbackArm.TREATMENT_EXACT,
                snapshot(rows(caseVariant, Layer.B1, OTHER)), snapshot(rows(caseVariant, Layer.B1, OTHER)));
        var effect = effect(arm, caseVariant, RecallPath.DIRECT);
        assertEquals(Population.VARIANT_CASE, effect.population());
        assertEquals(CALIBRATION.queryId(), effect.source().queryId());
        assertEquals(0, effect.exercisedKeys(), "the exact key of an upper-cased text differs");
        assertFalse(effect.changed());
    }

    private static QueryEffect effect(ArmEffect arm, String queryId, RecallPath path) {
        return arm.queries().stream().filter(query -> query.queryId().equals(queryId) && query.path() == path)
                .findFirst().orElseThrow();
    }

    private static FeedbackEvents plan(Event... events) {
        return new FeedbackEvents(List.of(events));
    }

    private static Snapshot snapshot(List<Row> rows) {
        return new Snapshot(QUERIES.queryHash(), rows);
    }

    private static List<Row> rows(String queryId, Layer layer, String... chunks) {
        var rows = new ArrayList<Row>();
        for (var i = 0; i < chunks.length; i++) {
            rows.add(new Row(queryId, layer, HtmlKnowledgeRecall.MAX_LIMIT, i + 1, chunks[i], 0.5 - i * 0.1));
        }
        return rows;
    }

    private static JudgedQuery query(String id) {
        return QUERIES.queries().stream().filter(query -> query.queryId().equals(id)).findFirst().orElseThrow();
    }
}
