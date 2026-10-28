package monada.neuron.reasoning.concept;

import monada.neuron.reasoning.concept.ConceptPrediction.ConceptAbstention;
import monada.neuron.reasoning.concept.ConceptPrediction.ConceptCandidates;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;

import static monada.neuron.reasoning.concept.ConceptFixtures.FRAME;
import static monada.neuron.reasoning.concept.ConceptFixtures.HIERARCHY;
import static monada.neuron.reasoning.concept.ConceptFixtures.HIGH_CHORD;
import static monada.neuron.reasoning.concept.ConceptFixtures.LEAVES;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_CHORD;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.N;
import static monada.neuron.reasoning.concept.ConceptFixtures.PROVENANCE;
import static monada.neuron.reasoning.concept.ConceptFixtures.decompose;
import static monada.neuron.reasoning.concept.ConceptFixtures.plus;
import static monada.neuron.reasoning.concept.ConceptFixtures.tone;
import static monada.neuron.reasoning.concept.ConceptFixtures.window;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract section 8 rules and fixtures P15, P16, N9 and N10, plus the abstention and budget cases of #127. */
class NextConceptPredictorTest {

    private final NextConceptPredictor predictor = new NextConceptPredictor();
    private final ConceptModel model = ConceptFixtures.trainedModel();

    @Test
    void p15CleanEvidencePredictsTheCoarseThenTheFineConcept() {
        SplittableRandom random = new SplittableRandom(0x15);
        for (int leaf = 0; leaf < LEAVES.length; leaf++) {
            int expectedLeaf = HIERARCHY.leaf(leaf);
            int expectedRoot = HIERARCHY.node(expectedLeaf).parentOrdinal();
            ConceptQuery query = new ConceptQuery(window(LEAVES[leaf], random), OptionalLong.of(LEAVES[leaf]));
            ConceptCandidates candidates = assertInstanceOf(ConceptCandidates.class,
                    predictor.predict(query, model, PredictionBudget.DEFAULT));
            assertAll(
                    () -> assertEquals(2, candidates.levels().size()),
                    () -> assertEquals(expectedRoot, candidates.levels().get(0).top().conceptOrdinal()),
                    () -> assertEquals(expectedLeaf, candidates.selected().conceptOrdinal()),
                    () -> assertFalse(candidates.coarseOnly()),
                    () -> assertFalse(candidates.selected().supportingComponents().isEmpty()),
                    () -> assertEquals(new PredictionWork(6, 4, 2), candidates.work()));
        }
    }

    @Test
    void candidatesAreRankedBoundedAndExplainable() {
        ConceptQuery query = ConceptQuery.of(window(LOW_CHORD, new SplittableRandom(3)));
        ConceptCandidates candidates = assertInstanceOf(ConceptCandidates.class,
                predictor.predict(query, model, PredictionBudget.DEFAULT));
        ConceptScores scores = candidates.scoreTable();
        ConceptLevel roots = candidates.levels().get(0);
        ConceptCandidate top = roots.top();
        ConceptCandidate runnerUp = roots.ranked().get(1);
        assertAll(
                () -> assertEquals(1.0, scores.nextScore(0) + scores.nextScore(1), 1e-12),
                () -> assertEquals(1.0, scores.currentScore(0) + scores.currentScore(1), 1e-12),
                () -> assertEquals(top.score() - runnerUp.score(), top.margin()),
                () -> assertEquals(runnerUp.score() - top.score(), runnerUp.margin()),
                () -> assertTrue(top.supportingComponents().size() <= NextConceptPredictor.MAX_SUPPORTING_COMPONENTS),
                () -> {
                    for (int a = 0; a < scores.size(); a++) {
                        assertTrue(scores.similarity(a) >= 0.0 && scores.similarity(a) <= 1.0);
                        assertTrue(scores.nextScore(a) >= 0.0 && scores.nextScore(a) <= 1.0);
                    }
                });
        ConceptCandidates single = assertInstanceOf(ConceptCandidates.class, predictor.predict(query, model,
                new PredictionBudget(1, 256, 3)));
        assertAll(
                () -> assertEquals(1, single.levels().get(0).ranked().size()),
                () -> assertEquals(top.margin(), single.levels().get(0).top().margin(),
                        "the runner-up still sets the margin when one candidate is reported"));
    }

    @Test
    void p16EqualScoresRankTheLowerOrdinalFirstAndN10AbstainsWithLowMargin() {
        ConceptModel symmetric = symmetricModel();
        SpectralPattern equidistant = decompose(plus(tone(3, 1.0), tone(9, 1.0)));
        ConceptCandidates candidates = assertInstanceOf(ConceptCandidates.class,
                predictor.predict(ConceptQuery.of(equidistant), symmetric, PredictionBudget.DEFAULT));
        ConceptLevel level = candidates.levels().get(0);
        assertAll(
                () -> assertEquals(level.ranked().get(0).score(), level.ranked().get(1).score()),
                () -> assertEquals(0, level.top().conceptOrdinal()),
                () -> assertEquals(1, level.ranked().get(1).conceptOrdinal()));
        ConceptModel strict = symmetric.withThresholds(ConceptThresholds.of(new double[] {0.0},
                new double[] {0.01}));
        ConceptAbstention abstention = assertInstanceOf(ConceptAbstention.class,
                predictor.predict(ConceptQuery.of(equidistant), strict, PredictionBudget.DEFAULT));
        assertAll(
                () -> assertEquals(AbstentionReason.LOW_MARGIN, abstention.reason()),
                () -> assertEquals(0, abstention.diagnostics().getFirst().depth()),
                () -> assertTrue(abstention.scores().isPresent()));
    }

    @Test
    void n9UnknownPreviousConceptAbstains() {
        ConceptQuery query = new ConceptQuery(window(LOW_PURE, new SplittableRandom(9)), OptionalLong.of(99));
        ConceptAbstention abstention = assertInstanceOf(ConceptAbstention.class,
                predictor.predict(query, model, PredictionBudget.DEFAULT));
        assertAll(
                () -> assertEquals(AbstentionReason.UNKNOWN_CONCEPT, abstention.reason()),
                () -> assertEquals(PredictionWork.NONE, abstention.work()),
                () -> assertEquals(Optional.empty(), abstention.scores()));
    }

    @Test
    void contradictoryEvidenceAbstainsAtTheCoarseLevel() {
        SpectralPattern contradictory = decompose(plus(tone(6, 1.0), tone(18, 1.0)));
        ConceptModel strict = model.withThresholds(ConceptThresholds.of(new double[] {0.0, 0.0},
                new double[] {0.3, 0.0}));
        ConceptAbstention abstention = assertInstanceOf(ConceptAbstention.class,
                predictor.predict(ConceptQuery.of(contradictory), strict, PredictionBudget.DEFAULT));
        assertEquals(AbstentionReason.LOW_MARGIN, abstention.reason());
    }

    @Test
    void lowConfidenceAtTheFineLevelGivesACoarseOnlyPrediction() {
        ConceptModel strict = model.withThresholds(ConceptThresholds.of(new double[] {0.0, 0.99},
                new double[] {0.0, 0.0}));
        ConceptQuery query = ConceptQuery.of(window(HIGH_CHORD, new SplittableRandom(4)));
        ConceptCandidates candidates = assertInstanceOf(ConceptCandidates.class,
                predictor.predict(query, strict, PredictionBudget.DEFAULT));
        assertAll(
                () -> assertTrue(candidates.coarseOnly()),
                () -> assertEquals(Optional.of(AbstentionReason.LOW_CONFIDENCE), candidates.refinementStop()),
                () -> assertEquals(1, candidates.levels().size()),
                () -> assertEquals(1, candidates.selected().conceptOrdinal()),
                () -> assertEquals(1, candidates.stoppedLevel().orElseThrow().depth()));
    }

    @Test
    void emptyExemplarsSilentAndIncompatibleEvidenceAbstainDeterministically() {
        ConceptModel empty = new ConceptModelTrainer().train(HIERARCHY, FRAME, List.of(), 0.05);
        SpectralPattern silent = decompose(new double[N]);
        SpectralFrame other = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 8.0, SampleUnit.HERTZ);
        SpectralPattern incompatible = SpectralPattern.of(other, 4.0, PROVENANCE, new int[5], new int[] {0, 1, 2, 3, 4},
                new double[] {0, 1, 0, 0, 0}, new double[5]);
        SpectralPattern evidence = window(LOW_PURE, new SplittableRandom(5));
        assertAll(
                () -> assertEquals(AbstentionReason.LOW_CONFIDENCE, reason(ConceptQuery.of(evidence), empty)),
                () -> assertEquals(AbstentionReason.LOW_CONFIDENCE, reason(ConceptQuery.of(silent), model)),
                () -> assertEquals(AbstentionReason.INCOMPATIBLE_INPUT, reason(ConceptQuery.of(incompatible), model)),
                () -> assertEquals(predictor.predict(ConceptQuery.of(silent), model, PredictionBudget.DEFAULT),
                        predictor.predict(ConceptQuery.of(silent), model, PredictionBudget.DEFAULT)));
    }

    @Test
    void budgetBoundsAbstainOrStopTheDescent() {
        ConceptQuery query = ConceptQuery.of(window(LOW_PURE, new SplittableRandom(6)));
        ConceptAbstention exhausted = assertInstanceOf(ConceptAbstention.class,
                predictor.predict(query, model, new PredictionBudget(16, 5, 3)));
        ConceptCandidates shallow = assertInstanceOf(ConceptCandidates.class,
                predictor.predict(query, model, new PredictionBudget(16, 6, 1)));
        assertAll(
                () -> assertEquals(AbstentionReason.BUDGET_EXHAUSTED, exhausted.reason()),
                () -> assertEquals(Optional.of(AbstentionReason.BUDGET_EXHAUSTED), shallow.refinementStop()),
                () -> assertEquals(Optional.empty(), shallow.stoppedLevel()),
                () -> assertEquals(1, shallow.levels().size()));
    }

    @Test
    void repeatedPredictionsAreBitIdentical() {
        SplittableRandom random = new SplittableRandom(0xD);
        for (int i = 0; i < 16; i++) {
            long leaf = LEAVES[i % LEAVES.length];
            ConceptQuery query = new ConceptQuery(window(leaf, random), OptionalLong.of(LEAVES[(i + 1) % 4]));
            ConceptModel thresholded = model.withThresholds(ConceptThresholds.of(new double[] {0.6, 0.5},
                    new double[] {0.1, 0.05}));
            assertEquals(predictor.predict(query, thresholded, PredictionBudget.DEFAULT),
                    predictor.predict(query, ConceptFixtures.trainedModel().withThresholds(thresholded.thresholds()),
                            PredictionBudget.DEFAULT));
        }
    }

    private AbstentionReason reason(ConceptQuery query, ConceptModel snapshot) {
        return assertInstanceOf(ConceptAbstention.class, predictor.predict(query, snapshot, PredictionBudget.DEFAULT))
                .reason();
    }

    /** Two root leaves with mirror-image centroids (bins 3 and 9) and symmetric transitions. */
    private static ConceptModel symmetricModel() {
        ConceptHierarchy flat = ConceptHierarchy.of(1, List.of(
                new ConceptNode(0, 100, ConceptNode.ROOT, 0),
                new ConceptNode(1, 200, ConceptNode.ROOT, 0)));
        SpectralPattern a = decompose(tone(3, 1.0));
        SpectralPattern b = decompose(tone(9, 1.0));
        LabeledSequence sequence = new LabeledSequence(List.of(
                new LabeledSequence.Window(a, 100), new LabeledSequence.Window(b, 200),
                new LabeledSequence.Window(b, 200), new LabeledSequence.Window(a, 100),
                new LabeledSequence.Window(a, 100)));
        return new ConceptModelTrainer().train(flat, FRAME, List.of(sequence, new LabeledSequence(List.of(
                new LabeledSequence.Window(b, 200)))), 0.1);
    }
}
