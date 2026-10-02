package monada.neuron.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        var evaluation = new HypothesisEvaluation(selected, 5, 3);
        assertAll(
                () -> assertEquals(selected, evaluation.selected()),
                () -> assertEquals(5, evaluation.evaluatedCount()),
                () -> assertEquals(3, evaluation.requestedMaxSelected()));
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
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(worseFirst, 2, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisEvaluation(tieHigherSequenceFirst, 4, 2)));
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
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(adjacent, 2, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(separated, 3, 3)));
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
                () -> assertDoesNotThrow(() -> new HypothesisEvaluation(positiveFirst, 2, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(negativeFirst, 2, 2)));
    }

    @Test
    void evaluationRejectsInconsistentCounts() {
        var one = List.of(new EvaluatedHypothesis(0, breakdown(0.5)));
        var outOfRange = List.of(new EvaluatedHypothesis(5, breakdown(0.5)));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(one, -1, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(one, 1, -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(one, 1, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(one, 0, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HypothesisEvaluation(outOfRange, 3, 1)),
                () -> assertThrows(NullPointerException.class, () -> new HypothesisEvaluation(null, 1, 1)));
    }

    @Test
    void evaluationSnapshotsTheSelectionList() {
        var mutable = new ArrayList<>(List.of(new EvaluatedHypothesis(0, breakdown(0.5))));
        var evaluation = new HypothesisEvaluation(mutable, 1, 1);
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

    private static HypothesisScoreBreakdown breakdown(double score) {
        return new HypothesisScoreBreakdown(0.0, 0.0, 0.0, 0, 0, 0, 0.0, score);
    }
}
