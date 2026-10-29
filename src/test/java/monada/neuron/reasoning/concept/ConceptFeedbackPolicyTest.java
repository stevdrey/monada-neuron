package monada.neuron.reasoning.concept;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.WaveProvenance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.SplittableRandom;

import static monada.neuron.reasoning.concept.ConceptFixtures.FRAME;
import static monada.neuron.reasoning.concept.ConceptFixtures.HIERARCHY;
import static monada.neuron.reasoning.concept.ConceptFixtures.HIGH_CHORD;
import static monada.neuron.reasoning.concept.ConceptFixtures.HIGH_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.LEAVES;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_BAND;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_CHORD;
import static monada.neuron.reasoning.concept.ConceptFixtures.LOW_PURE;
import static monada.neuron.reasoning.concept.ConceptFixtures.window;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConceptFeedbackPolicyTest {

    private static final String VERIFIER = "verifier.host";
    private static final ConceptFeedbackPolicy POLICY =
            new ConceptFeedbackPolicy(ConceptFeedbackLimits.trusting(VERIFIER));
    private static final int LOW_PURE_ORDINAL = 2;
    private static final int LOW_BAND_ORDINAL = 0;

    private final ConceptModel model = ConceptFixtures.trainedModel();

    private static ConceptPredictionReceipt receipt(ConceptModel model, String ref, long offset, long predicted) {
        return new ConceptPredictionReceipt(model.fingerprint(), ref, offset, OptionalLong.empty(), predicted, 1);
    }

    private static ConceptOutcome verified(ConceptPredictionReceipt receipt, long verified, long seed) {
        return ConceptOutcome.verified(receipt, verified, window(verified, new SplittableRandom(seed)),
                OptionalLong.empty(), 0, VERIFIER);
    }

    private static ConceptOutcome outcome(ConceptModel model, String ref, long offset, long predicted, long verified) {
        return verified(receipt(model, ref, offset, predicted), verified, offset + 1);
    }

    private static List<ConceptFeedbackRejection> reasons(ConceptModelUpdate update) {
        return update.rejected().stream().map(ConceptModelUpdate.Rejected::reason).toList();
    }

    private static ConceptModelUpdate.Updated updated(ConceptModelUpdate update) {
        return assertInstanceOf(ConceptModelUpdate.Updated.class, update);
    }

    @Test
    void positiveAndNegativeOutcomesAddTheSameVerifiedEvidence() {
        var positive = updated(POLICY.apply(model, List.of(outcome(model, "s", 0, LOW_PURE, LOW_PURE)), 0));
        var negative = updated(POLICY.apply(model, List.of(outcome(model, "s", 0, HIGH_PURE, LOW_PURE)), 0));
        var next = positive.next();

        assertAll(
                () -> assertEquals(1, positive.work().positive()),
                () -> assertEquals(1, negative.work().negative()),
                () -> assertEquals(next, negative.next(), "a confirmation adds no more than a correction"),
                () -> assertEquals(next.fingerprint(), negative.next().fingerprint()),
                () -> assertEquals(1, next.revision()),
                () -> assertEquals(model.leafCount(LOW_PURE_ORDINAL) + 1, next.leafCount(LOW_PURE_ORDINAL)),
                () -> assertEquals(1, next.feedbackCount(LOW_PURE_ORDINAL)),
                () -> assertEquals(model.exemplarCount(LOW_PURE_ORDINAL) + 1, next.exemplarCount(LOW_PURE_ORDINAL)),
                () -> assertEquals(model.exemplarCount(LOW_BAND_ORDINAL) + 1, next.exemplarCount(LOW_BAND_ORDINAL)),
                () -> assertEquals(model.exemplarCount(1), next.exemplarCount(1)),
                () -> assertEquals(2, positive.work().exemplarsAdded()),
                () -> assertEquals(next.retainedBytes(), positive.work().copiedBytes()),
                () -> assertEquals(model.temperature(), next.temperature()),
                () -> assertEquals(model.thresholds(), next.thresholds()));
    }

    @Test
    void verifiedPreviousLeafAddsOneTransition() {
        var outcome = ConceptOutcome.verified(receipt(model, "s", 0, LOW_PURE), LOW_CHORD,
                window(LOW_CHORD, new SplittableRandom(3)), OptionalLong.of(HIGH_PURE), 0, VERIFIER);
        var next = updated(POLICY.apply(model, List.of(outcome), 0)).next();

        assertAll(
                () -> assertEquals(model.transitionCount(4, 3) + 1, next.transitionCount(4, 3)),
                () -> assertEquals(model.transitionCount(2, 3), next.transitionCount(2, 3)));
    }

    @Test
    void unknownAndContradictedOutcomesAbstain() {
        var receipt = receipt(model, "s", 0, LOW_PURE);
        var update = POLICY.apply(model, List.of(
                ConceptOutcome.unverified(receipt, ConceptOutcome.Verification.UNVERIFIED, 0, VERIFIER),
                ConceptOutcome.unverified(receipt, ConceptOutcome.Verification.CONTRADICTED, 0, VERIFIER)), 0);

        var unchanged = assertInstanceOf(ConceptModelUpdate.Unchanged.class, update);
        assertAll(
                () -> assertSame(model, unchanged.current()),
                () -> assertEquals(List.of(ConceptFeedbackRejection.UNVERIFIED, ConceptFeedbackRejection.AMBIGUOUS),
                        reasons(update)),
                () -> assertEquals(0, update.work().applied()),
                () -> assertEquals(ConceptOutcome.Polarity.UNKNOWN,
                        unchanged.rejected().getFirst().outcome().polarity(HIERARCHY)));
    }

    @Test
    void emptyBatchIsTheNoFeedbackControl() {
        var update = POLICY.apply(model, List.of(), 7);

        assertAll(
                () -> assertSame(model, assertInstanceOf(ConceptModelUpdate.Unchanged.class, update).current()),
                () -> assertEquals(new ConceptModelUpdate.Work(0, 0, 0, 0, 0, 0), update.work()));
    }

    @Test
    void repeatedIdenticalFeedbackCannotAmplify() {
        var outcome = outcome(model, "s", 0, LOW_PURE, LOW_PURE);
        var first = updated(POLICY.apply(model, List.of(outcome, outcome), 0));
        var replay = POLICY.apply(first.next(), List.of(outcome, outcome), 0);

        assertAll(
                () -> assertEquals(1, first.work().applied()),
                () -> assertEquals(List.of(ConceptFeedbackRejection.DUPLICATE), reasons(first)),
                () -> assertEquals(1, first.rejected().getFirst().index()),
                () -> assertSame(first.next(), replay.current(), "the replayed batch names the previous snapshot"),
                () -> assertEquals(List.of(ConceptFeedbackRejection.MODEL_MISMATCH,
                        ConceptFeedbackRejection.MODEL_MISMATCH), reasons(replay)));
    }

    @Test
    void differentLabelsForOneWindowAreAmbiguous() {
        var receipt = receipt(model, "s", 4, LOW_PURE);
        var update = POLICY.apply(model, List.of(verified(receipt, LOW_PURE, 1), verified(receipt, HIGH_PURE, 2),
                outcome(model, "t", 4, LOW_PURE, LOW_CHORD)), 0);
        var result = updated(update);

        assertAll(
                () -> assertEquals(List.of(ConceptFeedbackRejection.AMBIGUOUS, ConceptFeedbackRejection.AMBIGUOUS),
                        reasons(update)),
                () -> assertEquals(1, result.applied().size()),
                () -> assertEquals("t", result.applied().getFirst().receipt().sourceReference()));
    }

    @Test
    void misattributedUntrustedAndInvalidOutcomesAreRejected() {
        var otherModel = model.withThresholds(ConceptThresholds.of(new double[] {0.5}, new double[] {0.1}));
        var otherFrame = ((Decomposed) new DftWaveDecomposer().decompose(new SampledWave(new double[32], 32,
                SampleUnit.HERTZ, new WaveProvenance("encoder", 1, "other", 0)))).pattern();
        var receipt = receipt(model, "s", 0, LOW_PURE);
        SpectralPattern evidence = window(LOW_PURE, new SplittableRandom(1));
        var update = POLICY.apply(model, List.of(
                outcome(otherModel, "s", 0, LOW_PURE, LOW_PURE),
                ConceptOutcome.verified(receipt, LOW_PURE, evidence, OptionalLong.empty(), 0, "predictor.self"),
                ConceptOutcome.verified(receipt, LOW_PURE, evidence, OptionalLong.empty(), 6, VERIFIER),
                ConceptOutcome.verified(receipt, LOW_PURE, evidence, OptionalLong.empty(), 0, VERIFIER),
                verified(receipt(model, "s", 1, 99), LOW_PURE, 1),
                verified(receipt(model, "s", 2, LOW_PURE), LOW_BAND, 1),
                ConceptOutcome.verified(receipt(model, "s", 3, LOW_PURE), LOW_PURE, evidence, OptionalLong.of(99),
                        5, VERIFIER),
                ConceptOutcome.verified(receipt(model, "s", 5, LOW_PURE), LOW_PURE, otherFrame,
                        OptionalLong.empty(), 5, VERIFIER)), 5);

        assertAll(
                () -> assertEquals(List.of(ConceptFeedbackRejection.MODEL_MISMATCH,
                        ConceptFeedbackRejection.UNTRUSTED_VERIFIER, ConceptFeedbackRejection.AGE_OUT_OF_RANGE,
                        ConceptFeedbackRejection.UNKNOWN_CONCEPT, ConceptFeedbackRejection.UNKNOWN_CONCEPT,
                        ConceptFeedbackRejection.UNKNOWN_CONCEPT, ConceptFeedbackRejection.INCOMPATIBLE_EVIDENCE),
                        reasons(update).subList(0, 7)),
                () -> assertEquals(1, update.work().applied(), "only the in-range trusted outcome"),
                () -> assertNotEquals(model.fingerprint(), otherModel.fingerprint()),
                () -> assertEquals(model.revision(), otherModel.revision()));
    }

    @Test
    void evidenceOlderThanTheAgeBoundExpires() {
        var limits = new ConceptFeedbackLimits(8, 2, 1.0, List.of(VERIFIER));
        var policy = new ConceptFeedbackPolicy(limits);
        var outcome = outcome(model, "s", 0, LOW_PURE, LOW_PURE);

        assertAll(
                () -> assertInstanceOf(ConceptModelUpdate.Updated.class, policy.apply(model, List.of(outcome), 2)),
                () -> assertEquals(List.of(ConceptFeedbackRejection.AGE_OUT_OF_RANGE),
                        reasons(policy.apply(model, List.of(outcome), 3))));
    }

    @Test
    void oversizedBatchIsRejectedWhole() {
        var policy = new ConceptFeedbackPolicy(new ConceptFeedbackLimits(2, 64, 1.0, List.of(VERIFIER)));
        var batch = List.of(outcome(model, "s", 0, LOW_PURE, LOW_PURE), outcome(model, "s", 1, LOW_PURE, LOW_PURE),
                outcome(model, "s", 2, LOW_PURE, LOW_PURE));
        var update = policy.apply(model, batch, 0);

        assertAll(
                () -> assertSame(model, update.current()),
                () -> assertEquals(List.of(ConceptFeedbackRejection.BATCH_LIMIT, ConceptFeedbackRejection.BATCH_LIMIT,
                        ConceptFeedbackRejection.BATCH_LIMIT), reasons(update)));
    }

    @Test
    void feedbackShareCapsHowFarALeafMoves() {
        long training = model.leafCount(LOW_PURE_ORDINAL);
        var policy = new ConceptFeedbackPolicy(new ConceptFeedbackLimits(8, 64, 1.5 / training, List.of(VERIFIER)));
        var first = updated(policy.apply(model, List.of(outcome(model, "s", 0, LOW_PURE, LOW_PURE),
                outcome(model, "s", 1, LOW_PURE, LOW_PURE), outcome(model, "s", 2, HIGH_CHORD, LOW_PURE)), 0));
        var later = policy.apply(first.next(), List.of(outcome(first.next(), "s", 3, LOW_PURE, LOW_PURE)), 0);

        assertAll(
                () -> assertEquals(1, first.work().applied(), "one feedback window at this share"),
                () -> assertEquals(List.of(ConceptFeedbackRejection.CONCEPT_CAP, ConceptFeedbackRejection.CONCEPT_CAP),
                        reasons(first)),
                () -> assertEquals(List.of(ConceptFeedbackRejection.CONCEPT_CAP), reasons(later)),
                () -> assertSame(first.next(), later.current()));
    }

    @Test
    void updatesPreserveTheHierarchyInvariantAndBoundedGrowth() {
        var policy = new ConceptFeedbackPolicy(new ConceptFeedbackLimits(64, 64, 4.0, List.of(VERIFIER)));
        var random = new SplittableRandom(0x129L);
        ConceptModel current = model;
        long offset = 0;
        for (int round = 0; round < 10; round++) {
            List<ConceptOutcome> batch = new ArrayList<>();
            for (int k = 0; k < 16; k++) {
                long leaf = LEAVES[random.nextInt(LEAVES.length)];
                batch.add(verified(receipt(current, "s", offset++, LEAVES[random.nextInt(LEAVES.length)]), leaf,
                        random.nextLong()));
            }
            var update = updated(policy.apply(current, batch, round));
            assertEquals(model.retainedBytes(), update.next().retainedBytes(), "no growth per update");
            assertTrue(update.work().exemplarsAdded() <= 2 * batch.size());
            current = update.next();
        }
        ConceptModel last = current;
        assertEquals(10, last.revision());
        for (int inner = 0; inner < 2; inner++) {
            long sum = 0;
            for (int k = 0; k < HIERARCHY.childCount(inner); k++) {
                sum += last.exemplarCount(HIERARCHY.child(inner, k));
            }
            assertEquals(sum, last.exemplarCount(inner), "an inner concept pools its leaves");
        }
        for (int concept = 0; concept < HIERARCHY.size(); concept++) {
            double norm = 0.0;
            for (double value : last.centroid(concept)) {
                norm += value * value;
            }
            assertEquals(1.0, norm, 1e-12);
        }
    }

    @Test
    void previousSnapshotIsUntouchedAndRollbackPredictsAsBefore() {
        var fresh = ConceptFixtures.trainedModel();
        var predictor = new NextConceptPredictor();
        var query = new ConceptQuery(window(HIGH_PURE, new SplittableRandom(9)), OptionalLong.of(HIGH_PURE));
        var before = predictor.predict(query, model, PredictionBudget.DEFAULT);
        String fingerprint = model.fingerprint();
        double[] centroid = model.centroid(LOW_PURE_ORDINAL);

        var batch = new ArrayList<ConceptOutcome>();
        for (int k = 0; k < 32; k++) {
            batch.add(outcome(model, "s", k, HIGH_PURE, LOW_PURE));
        }
        var next = updated(POLICY.apply(model, batch, 0)).next();

        assertAll(
                () -> assertEquals(fresh, model),
                () -> assertEquals(fingerprint, model.fingerprint()),
                () -> assertArrayEquals(centroid, model.centroid(LOW_PURE_ORDINAL)),
                () -> assertEquals(0, model.feedbackCount(LOW_PURE_ORDINAL)),
                () -> assertEquals(before, predictor.predict(query, model, PredictionBudget.DEFAULT),
                        "rollback is keeping the previous snapshot"),
                () -> assertNotEquals(before.scores().orElseThrow().nextScore(LOW_PURE_ORDINAL),
                        predictor.predict(query, next, PredictionBudget.DEFAULT).scores().orElseThrow()
                        .nextScore(LOW_PURE_ORDINAL)));
    }

    @Test
    void replayIsDeterministicAndEqualsARetrainUpToRounding() {
        var extra = ConceptFixtures.sequences(1, 24, 0x5129L).getFirst();
        var batch = new ArrayList<ConceptOutcome>();
        long previous = -1;
        for (int w = 0; w < extra.windows().size(); w++) {
            var window = extra.windows().get(w);
            batch.add(ConceptOutcome.verified(receipt(model, "x", w, LOW_PURE), window.conceptCode(),
                    window.pattern(), previous < 0 ? OptionalLong.empty() : OptionalLong.of(previous), 0, VERIFIER));
            previous = window.conceptCode();
        }
        var once = updated(POLICY.apply(model, batch, 0)).next();
        var twice = updated(POLICY.apply(ConceptFixtures.trainedModel(), batch, 0)).next();
        var training = new ArrayList<>(ConceptFixtures.sequences(8, 32, 0x127L));
        training.add(extra);
        var retrained = new ConceptModelTrainer().train(HIERARCHY, FRAME, training, 0.05);

        assertEquals(once.fingerprint(), twice.fingerprint());
        for (int concept = 0; concept < HIERARCHY.size(); concept++) {
            assertEquals(retrained.exemplarCount(concept), once.exemplarCount(concept));
            assertArrayEquals(retrained.centroid(concept), once.centroid(concept), 1e-12);
        }
        for (int from = 0; from < HIERARCHY.leafCount(); from++) {
            assertEquals(retrained.leafCount(HIERARCHY.leaf(from)), once.leafCount(HIERARCHY.leaf(from)));
            for (int to = 0; to < HIERARCHY.leafCount(); to++) {
                assertEquals(retrained.transitionCount(HIERARCHY.leaf(from), HIERARCHY.leaf(to)),
                        once.transitionCount(HIERARCHY.leaf(from), HIERARCHY.leaf(to)));
            }
        }
    }

    @Test
    void fingerprintNamesTheExactSnapshot() {
        var same = ConceptFixtures.trainedModel();

        assertAll(
                () -> assertEquals(model.fingerprint(), same.fingerprint()),
                () -> assertEquals(64, model.fingerprint().length()),
                () -> assertEquals(0, model.revision()),
                () -> assertNotEquals(model.fingerprint(), new ConceptModelTrainer()
                        .train(HIERARCHY, FRAME, ConceptFixtures.sequences(8, 32, 0x128L), 0.05).fingerprint()));
    }

    @Test
    void contractsValidateTheirComponents() {
        var receipt = receipt(model, "s", 0, LOW_PURE);
        var evidence = window(LOW_PURE, new SplittableRandom(1));

        assertAll(
                () -> assertEquals(List.of("a", "b"), ConceptFeedbackLimits.trusting("b", "a", "b").trustedVerifiers()),
                () -> assertThrows(IllegalArgumentException.class, ConceptFeedbackLimits::trusting),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptFeedbackLimits(0, 1, 1.0, List.of(VERIFIER))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptFeedbackLimits(1, -1, 1.0, List.of(VERIFIER))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ConceptFeedbackLimits(1, 1, Double.NaN, List.of(VERIFIER))),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptOutcome(receipt,
                        ConceptOutcome.Verification.VERIFIED, OptionalLong.empty(), evidence, OptionalLong.empty(), 0,
                        VERIFIER)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptOutcome(receipt,
                        ConceptOutcome.Verification.UNVERIFIED, OptionalLong.empty(), null, OptionalLong.of(1), 0,
                        VERIFIER)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ConceptPredictionReceipt("ABC", "s", 0,
                        OptionalLong.empty(), LOW_PURE, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> POLICY.apply(model, List.of(), -1)));
    }
}
