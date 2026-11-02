package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.FeatureConceptScorer.FeatureSequence;
import monada.neuron.reasoning.concept.AbstentionReason;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptNode;
import monada.neuron.reasoning.concept.ConceptThresholds;
import monada.neuron.reasoning.concept.PredictionBudget;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.WaveProvenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reference scorer of the ablation and its feature maps. */
class FeatureConceptScorerTest {

    private final FeatureConceptScorer scorer = new FeatureConceptScorer();
    private final SpectralResonanceDynamics dynamics = new SpectralResonanceDynamics();
    private final ConceptHierarchy flat = ConceptHierarchy.of(1, List.of(new ConceptNode(0, 10, ConceptNode.ROOT, 0),
            new ConceptNode(1, 20, ConceptNode.ROOT, 0)));

    @Test
    void theTinyWorkloadPassesForTheCoreAndTheReferenceScorer() {
        var check = new TinyWorkload().check();
        assertTrue(check.passed(), check.detail());
    }

    @Test
    void equalScoresRankTheLowerOrdinalFirstAndAnExactTieFailsTheMarginFirst() {
        // Two concepts trained on the same feature: equal similarity, equal transitions, so equal next scores.
        double[] feature = {1.0, 0.0};
        var model = scorer.train(flat, ConceptFeatureMap.dft(), 2, List.of(new FeatureSequence(
                new double[][] {feature, feature}, new int[] {0, 1}), new FeatureSequence(
                new double[][] {feature, feature}, new int[] {1, 0})), 0.1);

        ScoredPrediction open = scorer.predict(feature, ArmDecisions.NONE, model, PredictionBudget.DEFAULT);
        assertEquals(0, open.selected(), "P16: lower ordinal first");
        assertEquals(open.next()[0], open.next()[1]);

        var strict = model.withThresholds(ConceptThresholds.of(new double[] {0.9}, new double[] {0.01}));
        ScoredPrediction tie = scorer.predict(feature, ArmDecisions.NONE, strict, PredictionBudget.DEFAULT);
        assertEquals(Optional.of(AbstentionReason.LOW_MARGIN), tie.abstention(), "margin is tested before score");
        assertTrue(tie.scored(), "a depth-0 abstention keeps its scores");
    }

    @Test
    void earlyAbstentionsCarryNoScores() {
        var model = scorer.train(flat, ConceptFeatureMap.dft(), 2, List.of(), 0.1);
        ScoredPrediction empty = scorer.predict(new double[] {1.0, 0.0}, ArmDecisions.NONE, model,
                PredictionBudget.DEFAULT);
        assertEquals(Optional.of(AbstentionReason.LOW_CONFIDENCE), empty.abstention());
        assertNull(empty.next());

        var trained = scorer.train(flat, ConceptFeatureMap.dft(), 2, List.of(new FeatureSequence(
                new double[][] {{1.0, 0.0}, {0.0, 1.0}}, new int[] {0, 1})), 0.1);
        ScoredPrediction silent = scorer.predict(null, ArmDecisions.NONE, trained, PredictionBudget.DEFAULT);
        assertEquals(Optional.of(AbstentionReason.LOW_CONFIDENCE), silent.abstention());
        ScoredPrediction exhausted = scorer.predict(new double[] {1.0, 0.0}, ArmDecisions.NONE, trained,
                new PredictionBudget(16, 1, 3));
        assertEquals(Optional.of(AbstentionReason.BUDGET_EXHAUSTED), exhausted.abstention());
    }

    @Test
    void haarFeaturesUseEveryCoefficientSlotAndCoherenceAddsOneUnitScaledDimension() {
        var haar = new ConceptFeatureMap(SpectralBasis.HAAR, 3, 0.0);
        var withCoherence = new ConceptFeatureMap(SpectralBasis.HAAR, 3, 1.0);
        assertEquals(8, haar.dimension(8));
        assertEquals(9, withCoherence.dimension(8));
        assertEquals(5, ConceptFeatureMap.dft().dimension(8));
        assertEquals("HAAR(L=3)+coh(1.00)", withCoherence.label());

        SpectralPattern first = haar.decompose(haar.decomposer(), wave(0.0));
        SpectralPattern second = haar.decompose(haar.decomposer(), wave(0.0));
        var feature = new double[9];
        assertTrue(withCoherence.extract(second, first, dynamics, feature));
        double norm = 0.0;
        for (double value : feature) {
            norm += value * value;
        }
        assertEquals(1.0, norm, 1e-12);
        assertTrue(feature[8] > 0.0, "identical windows are fully coherent");

        var start = new double[9];
        assertTrue(withCoherence.extract(second, null, dynamics, start));
        assertEquals(0.0, start[8], "no previous window: no coherence");
        assertFalse(haar.extract(haar.decompose(haar.decomposer(), new SampledWave(new double[8], 8, SampleUnit.HERTZ,
                new WaveProvenance("test", 1, "silent", 0))), null, dynamics, new double[8]));
    }

    @Test
    void invalidMapsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConceptFeatureMap(SpectralBasis.DFT, 2, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ConceptFeatureMap(SpectralBasis.HAAR, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ConceptFeatureMap(SpectralBasis.HAAR, 2, -1.0));
    }

    @Test
    void trainingIsDeterministic() {
        var sequences = List.of(new FeatureSequence(new double[][] {{0.6, 0.8}, {1.0, 0.0}}, new int[] {0, 1}));
        var first = scorer.train(flat, ConceptFeatureMap.dft(), 2, sequences, 0.1);
        var second = scorer.train(flat, ConceptFeatureMap.dft(), 2, sequences, 0.1);
        assertArrayEquals(scorer.predict(new double[] {0.6, 0.8}, 0, first, PredictionBudget.DEFAULT).next(),
                scorer.predict(new double[] {0.6, 0.8}, 0, second, PredictionBudget.DEFAULT).next());
        assertEquals(1, first.transitionCount(0, 1));
    }

    private SampledWave wave(double phase) {
        var samples = new double[8];
        for (int n = 0; n < 8; n++) {
            samples[n] = StrictMath.cos(2.0 * StrictMath.PI * n / 8 + phase);
        }
        return new SampledWave(samples, 8, SampleUnit.HERTZ, new WaveProvenance("test", 1, "w", 0));
    }
}
