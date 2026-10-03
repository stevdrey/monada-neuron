package monada.neuron.evaluation;

import com.sun.management.ThreadMXBean;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.Proposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HypothesisEvaluationContractsTest {

    @Test
    void breakdownAcceptsValidComponents() {
        assertDoesNotThrow(() -> new HypothesisScoreBreakdown(1.0, 0.5, 0.25, 2, 1, 1, 0.0, 0.4));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.0, 1.5})
    void breakdownRejectsInvalidScore(double score) {
        assertThrows(IllegalArgumentException.class,
                () -> new HypothesisScoreBreakdown(1.0, 0.0, 0.0, 1, 0, 0, 0.0, score));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -0.5})
    void breakdownRejectsInvalidMasses(double mass) {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(mass, 0.0, 0.0, 1, 0, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, mass, 0.0, 0, 1, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, mass, 0, 0, 1, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, 0, mass, 0.1)));
    }

    @Test
    void breakdownRejectsInconsistentMassAndCount() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.5, 0.0, 0.0, 0, 0, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.5, 0.0, 0, 0, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.5, 0, 0, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 1, 0, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 1, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, 1, 0.0, 0.1)));
    }

    @Test
    void breakdownRejectsMassAboveCount() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(1.5, 0.0, 0.0, 1, 0, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 2.5, 0.0, 0, 2, 0, 0.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 1.1, 0, 0, 1, 0.0, 0.1)),
                () -> assertDoesNotThrow(() -> new HypothesisScoreBreakdown(2.0, 0.0, 0.0, 2, 0, 0, 0.0, 0.1)));
    }

    @Test
    void breakdownRejectsResonanceContributionAboveOne() {
        assertAll(
                () -> assertDoesNotThrow(() -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, 0, 1.0, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, 0, 1.0001, 0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, 0, 2.0, 0.1)));
    }

    @Test
    void breakdownRejectsNegativeCounts() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, -1, 0, 0, 0.0, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, -1, 0, 0.0, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, -1, 0.0, 0.0)));
    }

    @Test
    void evaluatedHypothesisRejectsNegativeSequenceAndNullBreakdown() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new EvaluatedHypothesis(-1, breakdown(0.5))),
                () -> assertThrows(NullPointerException.class, () -> new EvaluatedHypothesis(0, null)));
    }

    @Test
    void evaluationAcceptsRankedSelection() {
        var selected = List.of(
                new EvaluatedHypothesis(0, breakdown(0.5)),
                new EvaluatedHypothesis(2, breakdown(0.5)),
                new EvaluatedHypothesis(1, breakdown(0.25)));
        var evaluation = new HypothesisEvaluation(candidates(5), selected, 3);
        assertAll(
                () -> assertEquals(selected, evaluation.selected()),
                () -> assertEquals(5, evaluation.evaluatedCount()),
                () -> assertEquals(5, evaluation.evaluated().size()),
                () -> assertEquals(3, evaluation.requestedMaxSelected()));
    }

    @Test
    void evaluationCarriesTheScoredSetSoAPairingCannotMismatch() {
        var scored = candidates(3);
        var evaluation = new HypothesisEvaluation(
                scored, List.of(new EvaluatedHypothesis(2, breakdown(0.5))), 1);
        assertAll(
                () -> assertSame(scored, evaluation.evaluated()),
                () -> assertEquals(3, evaluation.evaluatedCount()),
                () -> assertFalse(evaluation.toString().contains("Proposition"), "toString must not dump the set"));
    }

    @Test
    void evaluationValidatesUniquenessWithStateBoundedByTheSelection() throws Exception {
        var bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof ThreadMXBean, "com.sun.management.ThreadMXBean is unavailable");
        var threads = (ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported(), "thread allocated-memory tracking is unsupported");
        if (!threads.isThreadAllocatedMemoryEnabled()) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
        var selected = new ArrayList<EvaluatedHypothesis>();
        for (var i = 0; i < 17; i++) {
            selected.add(new EvaluatedHypothesis(i, breakdown(0.9 - 0.01 * i)));
        }
        var smallSet = candidates(20);
        var hugeSet = candidates(200_000);
        for (var warm = 0; warm < 3; warm++) {
            new HypothesisEvaluation(smallSet, selected, 17);
            new HypothesisEvaluation(hugeSet, selected, 17);
        }
        var before = threads.getCurrentThreadAllocatedBytes();
        new HypothesisEvaluation(hugeSet, selected, 17);
        var allocated = threads.getCurrentThreadAllocatedBytes() - before;
        // 17 sequences need about 100 B of working state; sizing from 200,000 candidates would need 25 KB.
        assumeTrue(before >= 0, "thread allocated-memory tracking is disabled");
        assertTrue(allocated <= 1_024, "validation allocated " + allocated + " B for K = 17 and N = 200,000");
    }

    @Test
    void evaluationRejectsOutOfOrderSelection() {
        var worseFirst = List.of(
                new EvaluatedHypothesis(0, breakdown(0.25)),
                new EvaluatedHypothesis(1, breakdown(0.5)));
        var tieHigherSequenceFirst = List.of(
                new EvaluatedHypothesis(3, breakdown(0.5)),
                new EvaluatedHypothesis(1, breakdown(0.5)));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(candidates(2), worseFirst, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisEvaluation(candidates(4), tieHigherSequenceFirst, 2)));
    }

    @Test
    void evaluationRejectsDuplicateSequencesEvenWhenNotAdjacent() {
        var adjacent = List.of(
                new EvaluatedHypothesis(1, breakdown(0.5)),
                new EvaluatedHypothesis(1, breakdown(0.5)));
        var separated = List.of(
                new EvaluatedHypothesis(0, breakdown(0.9)),
                new EvaluatedHypothesis(1, breakdown(0.5)),
                new EvaluatedHypothesis(0, breakdown(0.1)));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(candidates(2), adjacent, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(candidates(3), separated, 3)));
    }

    @Test
    void evaluationRejectsDuplicatesAboveThePairwiseLimit() {
        var unique = new ArrayList<EvaluatedHypothesis>();
        for (var i = 0; i < 19; i++) {
            unique.add(new EvaluatedHypothesis(i, breakdown(0.9 - 0.01 * i)));
        }
        var withDuplicate = new ArrayList<>(unique);
        withDuplicate.set(18, new EvaluatedHypothesis(3, breakdown(0.5)));
        assertAll(
                () -> assertDoesNotThrow(() -> new HypothesisEvaluation(candidates(20), unique, 20)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisEvaluation(candidates(20), withDuplicate, 20)));
    }

    @Test
    void evaluationRankValidationOrdersPositiveZeroBeforeNegativeZero() {
        var positiveFirst = List.of(
                new EvaluatedHypothesis(1, breakdown(0.0)),
                new EvaluatedHypothesis(0, breakdown(-0.0)));
        var negativeFirst = List.of(
                new EvaluatedHypothesis(0, breakdown(-0.0)),
                new EvaluatedHypothesis(1, breakdown(0.0)));
        assertAll(
                () -> assertDoesNotThrow(() -> new HypothesisEvaluation(candidates(2), positiveFirst, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(candidates(2), negativeFirst, 2)));
    }

    @Test
    void evaluationRejectsInconsistentCounts() {
        var one = List.of(new EvaluatedHypothesis(0, breakdown(0.5)));
        var outOfRange = List.of(new EvaluatedHypothesis(5, breakdown(0.5)));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(candidates(1), one, -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(candidates(1), one, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisEvaluation(HypothesisSet.EMPTY, one, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(candidates(3), outOfRange, 1)),
                () -> assertThrows(NullPointerException.class, () -> new HypothesisEvaluation(candidates(1), null, 1)),
                () -> assertThrows(NullPointerException.class, () -> new HypothesisEvaluation(null, one, 1)));
    }

    @Test
    void evaluationSnapshotsTheSelectionList() {
        var mutable = new ArrayList<>(List.of(new EvaluatedHypothesis(0, breakdown(0.5))));
        var evaluation = new HypothesisEvaluation(candidates(1), mutable, 1);
        mutable.clear();
        assertEquals(1, evaluation.selected().size());
    }

    @Test
    void scoringConfigValidatesWeightAndComponentConsistency() {
        assertAll(
                () -> assertDoesNotThrow(() -> new HypothesisScoringConfig(h -> 0.5, 0.0)),
                () -> assertDoesNotThrow(() -> new HypothesisScoringConfig(h -> 0.5, 1.0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisScoringConfig(h -> 0.5, -0.1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisScoringConfig(h -> 0.5, 1.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisScoringConfig(h -> 0.5, Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisScoringConfig(null, 0.5)),
                () -> assertDoesNotThrow(() -> new HypothesisScoringConfig(null, 0.0)),
                () -> assertEquals(HypothesisScoringConfig.NONE, new HypothesisScoringConfig(null, 0.0)));
    }

    private static HypothesisSet candidates(int count) {
        if (count == 0) {
            return HypothesisSet.EMPTY;
        }
        var builder = new HypothesisSetBuilder(new HypothesisLimits(count, 1));
        for (var i = 0; i < count; i++) {
            builder.propose(new Proposition(0, i));
        }
        return builder.build();
    }

    private static HypothesisScoreBreakdown breakdown(double score) {
        return new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, 0, 0.0, score);
    }
}
