package monada.neuron.reasoning.concept;

import monada.neuron.reasoning.concept.ConceptPrediction.ConceptAbstention;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptCandidates;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Validation of the result values. */
class ConceptPredictionTest {

    private static final ConceptScores SCORES = new ConceptScores(new double[2], new double[2], new double[2]);

    @Test
    void valuesRejectInconsistentContent() {
        ConceptCandidate top = new ConceptCandidate(0, 0, 0.6, 0.2, List.of());
        ConceptCandidate second = new ConceptCandidate(1, 0, 0.4, -0.2, List.of());
        ConceptLevel level = new ConceptLevel(0, List.of(top, second));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptCandidate(0, 0, 1.5, 0.0, List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptCandidate(0, 0, Double.NaN, 0.0, List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptCandidate(0, 0, 0.5, 0.0, List.of(-1))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptCandidate(0, 0, 0.5, 0.0, List.of(0, 1, 2, 3, 4, 5, 6, 7, 8))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptLevel(0, List.of(second, top)), "rank order"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptLevel(1, List.of(top)), "depth mismatch"),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptCandidates(List.of(),
                        Optional.empty(), Optional.empty(), SCORES, PredictionWork.NONE)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptCandidates(List.of(level),
                        Optional.of(AbstentionReason.UNKNOWN_CONCEPT), Optional.empty(), SCORES, PredictionWork.NONE)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptCandidates(List.of(level),
                        Optional.empty(), Optional.of(level), SCORES, PredictionWork.NONE)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptAbstention(
                        AbstentionReason.LOW_MARGIN, List.of(), Optional.empty(), PredictionWork.NONE, "x".repeat(257))),
                () -> assertThrows(IllegalArgumentException.class, () -> new PredictionBudget(17, 1, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new PredictionBudget(1, 1, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ConceptThresholds.of(new double[] {1.1}, new double[] {0.0})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ConceptThresholds.of(new double[] {0.1, 0.1}, new double[] {0.0})));
    }
}
