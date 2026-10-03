package monada.neuron.evaluation;

import monada.neuron.reasoning.Evidence;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.MemoryReferenceEvidence;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.SignalEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceHypothesisEvaluationPolicyTest {

    private static final double TOLERANCE = 1e-12;

    private final ReferenceHypothesisEvaluationPolicy policy = new ReferenceHypothesisEvaluationPolicy();

    @Test
    void supportingEvidenceOnlyScoresSmoothedRatio() {
        var set = set(candidate(support(1.0)));
        var breakdown = policy.evaluate(set, 1).selected().getFirst().breakdown();
        assertAll(
                () -> assertEquals(1.0, breakdown.supportMass(), TOLERANCE),
                () -> assertEquals(0.0, breakdown.contradictionMass(), TOLERANCE),
                () -> assertEquals(1, breakdown.supportCount()),
                () -> assertEquals(0.5, breakdown.score(), TOLERANCE));
    }

    @Test
    void contradictingEvidenceLowersTheScore() {
        var supportOnly = policy.evaluate(set(candidate(support(0.5))), 1).selected().getFirst();
        var contradicted = policy.evaluate(set(candidate(support(0.5), contradict(0.25))), 1)
                .selected().getFirst();
        assertAll(
                () -> assertEquals(0.25, contradicted.breakdown().contradictionMass(), TOLERANCE),
                () -> assertEquals(0.5 / (0.5 + 0.25 + 1.0), contradicted.breakdown().score(), TOLERANCE),
                () -> assertTrue(contradicted.breakdown().score() < supportOnly.breakdown().score()));
    }

    @Test
    void contradictionOnlyScoresZero() {
        var breakdown = policy.evaluate(set(candidate(contradict(1.0))), 1).selected().getFirst().breakdown();
        assertAll(
                () -> assertEquals(0.0, breakdown.score()),
                () -> assertEquals(1, breakdown.contradictionCount()));
    }

    @Test
    void neutralAndMissingEvidenceScoreZeroButNeutralIsReported() {
        var neutralOnly = policy.evaluate(set(candidate(neutral(0.9), neutral(0.1))), 2);
        var none = policy.evaluate(set(candidate()), 1);
        var neutral = neutralOnly.selected().getFirst().breakdown();
        assertAll(
                () -> assertEquals(0.0, neutral.score()),
                () -> assertEquals(2, neutral.neutralCount()),
                () -> assertEquals(1.0, neutral.neutralMass(), TOLERANCE),
                () -> assertEquals(0.0, none.selected().getFirst().breakdown().score()),
                () -> assertEquals(0, none.selected().getFirst().breakdown().supportCount()));
    }

    @Test
    void neutralEvidenceDoesNotChangeTheScore() {
        var plain = policy.evaluate(set(candidate(support(0.7))), 1).selected().getFirst();
        var withNeutral = policy.evaluate(set(candidate(support(0.7), neutral(1.0))), 1).selected().getFirst();
        assertEquals(plain.breakdown().score(), withNeutral.breakdown().score());
    }

    @Test
    void memoryReferenceEvidenceCountsLikeSignalEvidence() {
        var set = set(candidate(new MemoryReferenceEvidence("mem-1", EvidenceRelation.SUPPORTS, 0.6)));
        assertEquals(0.6 / 1.6, policy.evaluate(set, 1).selected().getFirst().breakdown().score(), TOLERANCE);
    }

    @Test
    void scoreStaysInHalfOpenUnitInterval() {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(1, 16));
        var seq = builder.propose(new Proposition(0, 1)).getAsInt();
        for (var i = 0; i < 16; i++) {
            builder.addEvidence(seq, support(1.0));
        }
        var score = policy.evaluate(builder.build(), 1).selected().getFirst().breakdown().score();
        assertAll(() -> assertTrue(score >= 0.0), () -> assertTrue(score < 1.0));
    }

    @Test
    void ranksByScoreThenLowerSequenceWins() {
        var set = set(
                candidate(support(0.5)),
                candidate(support(0.9)),
                candidate(support(0.5)),
                candidate(support(0.9)));
        var selected = policy.evaluate(set, 4).selected();
        assertEquals(List.of(1, 3, 0, 2), selected.stream().map(EvaluatedHypothesis::sequence).toList());
    }

    @Test
    void resonanceContributesOnlyToSupportScaledByWeight() {
        var set = set(candidate(support(0.5)));
        var config = new HypothesisScoringConfig(hypothesis -> 0.8, 0.5);
        var evaluated = new ReferenceHypothesisEvaluationPolicy(config, new BoundedHeapSelector())
                .evaluate(set, 1).selected().getFirst().breakdown();
        var supportMass = 0.5 + 0.5 * 0.8;
        assertAll(
                () -> assertEquals(0.4, evaluated.resonanceContribution(), TOLERANCE),
                () -> assertEquals(0.5, evaluated.supportMass(), TOLERANCE),
                () -> assertEquals(supportMass / (supportMass + 0.0 + 1.0), evaluated.score(), TOLERANCE));
    }

    @Test
    void resonanceCanLiftACandidateWithoutEvidence() {
        var set = set(candidate(), candidate(support(0.1)));
        var config = new HypothesisScoringConfig(hypothesis -> hypothesis.sequence() == 0 ? 1.0 : 0.0, 1.0);
        var selected = new ReferenceHypothesisEvaluationPolicy(config, new BoundedHeapSelector())
                .evaluate(set, 2).selected();
        assertEquals(List.of(0, 1), selected.stream().map(EvaluatedHypothesis::sequence).toList());
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.0001})
    void rejectsInvalidResonanceOutput(double invalid) {
        var failing = new ReferenceHypothesisEvaluationPolicy(
                new HypothesisScoringConfig(hypothesis -> invalid, 1.0),
                new BoundedHeapSelector());
        assertThrows(IllegalArgumentException.class, () -> failing.evaluate(set(candidate(support(1.0))), 1));
    }

    @Test
    void zeroWeightConfigNeverInvokesTheComponent() {
        var config = new HypothesisScoringConfig(hypothesis -> {
            throw new AssertionError("component must not be called");
        }, 0.0);
        var evaluation = new ReferenceHypothesisEvaluationPolicy(config, new BoundedHeapSelector())
                .evaluate(set(candidate(support(1.0))), 1);
        assertEquals(0.5, evaluation.selected().getFirst().breakdown().score(), TOLERANCE);
    }

    @Test
    void emptyCandidateSetProducesEmptyEvaluation() {
        var evaluation = policy.evaluate(HypothesisSet.EMPTY, 5);
        assertAll(
                () -> assertTrue(evaluation.selected().isEmpty()),
                () -> assertEquals(0, evaluation.evaluatedCount()),
                () -> assertEquals(5, evaluation.requestedMaxSelected()));
    }

    @Test
    void boundaryValuesOfMaxSelected() {
        var set = set(candidate(support(0.1)), candidate(support(0.2)), candidate(support(0.3)));
        assertAll(
                () -> assertEquals(0, policy.evaluate(set, 0).selected().size()),
                () -> assertEquals(1, policy.evaluate(set, 1).selected().size()),
                () -> assertEquals(2, policy.evaluate(set, 2).selected().size()),
                () -> assertEquals(3, policy.evaluate(set, 3).selected().size()),
                () -> assertEquals(3, policy.evaluate(set, 4).selected().size()),
                () -> assertEquals(3, policy.evaluate(set, Integer.MAX_VALUE).selected().size()),
                () -> assertEquals(3, policy.evaluate(set, 1).evaluatedCount()),
                () -> assertEquals(2, policy.evaluate(set, 1).selected().getFirst().sequence()));
    }

    @Test
    void rejectsNullAndNegativeArguments() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> policy.evaluate(null, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> policy.evaluate(HypothesisSet.EMPTY, -1)),
                () -> assertThrows(NullPointerException.class,
                        () -> new ReferenceHypothesisEvaluationPolicy(null, new BoundedHeapSelector())),
                () -> assertThrows(NullPointerException.class,
                        () -> new ReferenceHypothesisEvaluationPolicy(HypothesisScoringConfig.NONE, null)));
    }

    @Test
    void heapAndFullSortStrategiesProduceEqualEvaluations() {
        var set = randomSet(7, 200);
        var heap = new ReferenceHypothesisEvaluationPolicy(HypothesisScoringConfig.NONE, new BoundedHeapSelector());
        var sort = new ReferenceHypothesisEvaluationPolicy(HypothesisScoringConfig.NONE, new FullSortSelector());
        assertEquals(sort.evaluate(set, 25), heap.evaluate(set, 25));
    }

    @Test
    void everyBreakdownMatchesAnIndependentRecomputation() {
        var set = randomSet(11, 120);
        var configs = List.of(
                HypothesisScoringConfig.NONE,
                new HypothesisScoringConfig(hypothesis -> (hypothesis.sequence() % 5) / 4.0, 0.5));
        for (var config : configs) {
            var evaluation = new ReferenceHypothesisEvaluationPolicy(config, new BoundedHeapSelector())
                    .evaluate(set, set.size());
            assertEquals(set.size(), evaluation.selected().size());
            for (var evaluated : evaluation.selected()) {
                var hypothesis = set.get(evaluated.sequence());
                var support = 0.0;
                var contradiction = 0.0;
                var neutral = 0.0;
                var counts = new int[3];
                for (var item : hypothesis.evidence()) {
                    switch (item.relation()) {
                        case SUPPORTS -> {
                            support += item.weight();
                            counts[0]++;
                        }
                        case CONTRADICTS -> {
                            contradiction += item.weight();
                            counts[1]++;
                        }
                        case NEUTRAL -> {
                            neutral += item.weight();
                            counts[2]++;
                        }
                    }
                }
                var resonance = config.usesResonance()
                        ? config.resonanceWeight() * config.resonance().resonance(hypothesis)
                        : 0.0;
                final var expectedSupport = support;
                final var expectedContradiction = contradiction;
                final var expectedNeutral = neutral;
                var supportWithResonance = expectedSupport + resonance;
                var breakdown = evaluated.breakdown();
                assertAll(
                        () -> assertEquals(expectedSupport, breakdown.supportMass()),
                        () -> assertEquals(expectedContradiction, breakdown.contradictionMass()),
                        () -> assertEquals(expectedNeutral, breakdown.neutralMass()),
                        () -> assertEquals(counts[0], breakdown.supportCount()),
                        () -> assertEquals(counts[1], breakdown.contradictionCount()),
                        () -> assertEquals(counts[2], breakdown.neutralCount()),
                        () -> assertEquals(resonance, breakdown.resonanceContribution()),
                        () -> assertEquals(
                                supportWithResonance / (supportWithResonance + expectedContradiction + 1.0),
                                breakdown.score()));
            }
        }
    }

    @Test
    void identicalInputsProduceEqualEvaluations() {
        assertEquals(policy.evaluate(randomSet(42, 100), 10), policy.evaluate(randomSet(42, 100), 10));
    }

    @Test
    void evaluationLeavesTheInputSetUntouched() {
        var set = randomSet(3, 50);
        var snapshot = randomSet(3, 50);
        policy.evaluate(set, 5);
        assertEquals(snapshot, set);
    }

    // helpers ---------------------------------------------------------------------------------

    private static SignalEvidence support(double weight) {
        return new SignalEvidence(0, EvidenceRelation.SUPPORTS, weight);
    }

    private static SignalEvidence contradict(double weight) {
        return new SignalEvidence(0, EvidenceRelation.CONTRADICTS, weight);
    }

    private static SignalEvidence neutral(double weight) {
        return new SignalEvidence(0, EvidenceRelation.NEUTRAL, weight);
    }

    private static List<Evidence> candidate(Evidence... evidence) {
        return List.of(evidence);
    }

    @SafeVarargs
    private static HypothesisSet set(List<Evidence>... candidates) {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(Math.max(1, candidates.length), 16));
        for (var i = 0; i < candidates.length; i++) {
            var seq = builder.propose(new Proposition(0, i)).getAsInt();
            for (var evidence : candidates[i]) {
                builder.addEvidence(seq, evidence);
            }
        }
        return builder.build();
    }

    private static HypothesisSet randomSet(long seed, int candidates) {
        var random = new Random(seed);
        var builder = new HypothesisSetBuilder(new HypothesisLimits(candidates, 6));
        for (var i = 0; i < candidates; i++) {
            var seq = builder.propose(new Proposition(0, i)).getAsInt();
            var count = random.nextInt(5);
            for (var e = 0; e < count; e++) {
                var relation = EvidenceRelation.values()[random.nextInt(3)];
                // Coarse weights create many exact ties.
                builder.addEvidence(seq, new SignalEvidence(0, relation, (1 + random.nextInt(4)) / 4.0));
            }
        }
        return builder.build();
    }
}
