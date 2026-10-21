package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.FeedbackArm.KeyStrategy;
import monada.neuron.evaluation.knowledge.FeedbackKeyVariants.Transform;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackKeyVariantsTest {

    @Test
    void transformsChangeOnlyTheSurface() {
        assertEquals("WHAT IS A DL?", Transform.CASE.apply("What is a dl?"));
        assertEquals("What  is  a  dl?", Transform.SPACING.apply("What is a dl?"));
        assertEquals("What is a dl", Transform.PUNCTUATION.apply("What is a dl?"));
        assertEquals("What is a dl?", Transform.PUNCTUATION.apply("What is a dl"));
    }

    @Test
    void everyNonBlankSourceGetsEveryTransformInOrder() {
        var variants = FeedbackKeyVariants.of(List.of(new QueryText("q:a", "What is a dl?"),
                new QueryText("q:blank", " "), new QueryText("q:b", "Which tag shows steps?")));
        assertEquals(6, variants.size());
        assertEquals("v:q:a/case", variants.getFirst().variantId());
        assertEquals("q:b", variants.getLast().sourceId());
        assertEquals(Transform.PUNCTUATION, variants.getLast().transform());
        assertEquals(FeedbackKeyVariants.variantHash(variants), FeedbackKeyVariants.variantHash(
                FeedbackKeyVariants.of(List.of(new QueryText("q:a", "What is a dl?"),
                        new QueryText("q:b", "Which tag shows steps?")))));
    }

    @Test
    void bundledCalibrationVariantsKeepNormalizedAndEnrichedKeysButNotTheExactKey() {
        var queries = HtmlKnowledgeQuerySet.bundled(HtmlKnowledgeCorpus.bundled());
        var calibration = FeedbackQuerySplit.of(queries.queries()).calibration().stream()
                .map(query -> new QueryText(query.queryId(), query.text())).toList();
        var variants = FeedbackKeyVariants.of(calibration);
        var workload = new ArrayList<>(queries.queries().stream()
                .map(query -> new QueryText(query.queryId(), query.text())).toList());
        variants.forEach(variant -> workload.add(variant.queryText()));
        var check = FeedbackKeyVariants.keyPreserving(variants, workload);
        assertTrue(check.passed(), check.detail());
        var variant = variants.getFirst();
        var source = calibration.stream().filter(query -> query.queryId().equals(variant.sourceId())).findFirst()
                .orElseThrow().text();
        assertNotEquals(KeyStrategy.EXACT.effectiveKey(source), KeyStrategy.EXACT.effectiveKey(variant.text()));
        assertEquals(KeyStrategy.NORMALIZED.effectiveKey(source), KeyStrategy.NORMALIZED.effectiveKey(variant.text()));
    }

    @Test
    void aVariantThatRepeatsAWorkloadTextFailsTheCheck() {
        var sources = List.of(new QueryText("q:a", "What is a dl?"), new QueryText("q:b", "WHAT IS A DL?"));
        var variants = FeedbackKeyVariants.of(sources.subList(0, 1));
        var workload = new ArrayList<>(sources);
        variants.forEach(variant -> workload.add(variant.queryText()));
        var check = FeedbackKeyVariants.keyPreserving(variants, workload);
        assertFalse(check.passed());
        assertTrue(check.detail().contains("repeats another workload text"), check.detail());
    }
}
