package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.FeedbackQuerySplit.Side;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackQuerySplitTest {

    private static final HtmlKnowledgeQuerySet QUERIES = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());

    @Test
    void bundledSplitAlternatesWithinEachCategoryStartingWithCalibration() {
        var split = FeedbackQuerySplit.of(QUERIES.queries());
        assertTrue(split.validity().passed(), split.validity().detail());
        assertEquals(19, split.calibration().size());
        assertEquals(13, split.heldOut().size());
        assertTrue(split.mergedGroups().isEmpty());
        assertEquals(Side.CALIBRATION, split.side("q:exact-term-01"));
        assertEquals(Side.HELD_OUT, split.side("q:exact-term-02"));
        assertEquals(Side.CALIBRATION, split.side("q:exact-term-03"));
        assertEquals(Side.CALIBRATION, split.side("q:blank-01"));
        assertEquals(Side.HELD_OUT, split.side("q:blank-02"));
        var sides = new EnumMap<Category, Set<Side>>(Category.class);
        QUERIES.queries().forEach(query -> sides.computeIfAbsent(query.category(), c -> new HashSet<>())
                .add(split.side(query.queryId())));
        sides.values().forEach(categorySides -> assertEquals(Set.of(Side.CALIBRATION, Side.HELD_OUT), categorySides));
    }

    @Test
    void splitIsDeterministicAndIgnoresLabels() {
        var relabeled = QUERIES.queries().stream().map(query -> new JudgedQuery(query.queryId(), query.category(),
                query.text(), Set.of("chk:forms#00"), Set.of(), List.of(), List.of(), Set.of())).toList();
        var first = FeedbackQuerySplit.of(QUERIES.queries());
        assertEquals(first.splitHash(), FeedbackQuerySplit.of(QUERIES.queries()).splitHash());
        assertEquals(first.splitHash(), FeedbackQuerySplit.of(relabeled).splitHash());
        assertEquals(first.toTsv(), FeedbackQuerySplit.of(relabeled).toTsv());
    }

    @Test
    void queriesSharingAKeyUnderAnyStrategyStayInOneGroup() {
        var queries = new ArrayList<JudgedQuery>();
        queries.add(query("q:exact-term-01", Category.EXACT_TERM, "What does the fieldset element do?"));
        // Same normalized key as exact-term-01, and alphabetically the second exact-term query: alternating alone would
        // put it on the held-out side.
        queries.add(query("q:exact-term-02", Category.EXACT_TERM, "WHAT DOES THE FIELDSET ELEMENT DO"));
        queries.add(query("q:exact-term-03", Category.EXACT_TERM, "What does the defer attribute do on a script?"));
        queries.add(query("q:blank-01", Category.BLANK, ""));
        queries.add(query("q:blank-02", Category.BLANK, ""));
        var split = FeedbackQuerySplit.of(queries);
        assertEquals(1, split.mergedGroups().size());
        assertEquals(List.of("q:exact-term-01", "q:exact-term-02"), split.mergedGroups().getFirst().members());
        assertEquals(split.side("q:exact-term-01"), split.side("q:exact-term-02"));
        assertEquals(Side.HELD_OUT, split.side("q:exact-term-03"));
        // Blank queries share the raw key "" but are never grouped.
        assertEquals(Side.CALIBRATION, split.side("q:blank-01"));
        assertEquals(Side.HELD_OUT, split.side("q:blank-02"));
        assertTrue(split.validity().passed(), split.validity().detail());
    }

    @Test
    void aKeyGroupThatTakesACategoryToOneSideIsInvalid() {
        var queries = List.of(
                query("q:exact-term-01", Category.EXACT_TERM, "What does the fieldset element do?"),
                query("q:exact-term-02", Category.EXACT_TERM, "what does the fieldset element do"));
        var split = FeedbackQuerySplit.of(queries);
        assertEquals(split.side("q:exact-term-01"), split.side("q:exact-term-02"));
        var validity = split.validity();
        assertFalse(validity.passed());
        assertTrue(validity.detail().contains("exact-term has queries on one side only"), validity.detail());
    }

    private static JudgedQuery query(String id, Category category, String text) {
        return new JudgedQuery(id, category, text, Set.of(), Set.of(), List.of(), List.of(), Set.of());
    }
}
