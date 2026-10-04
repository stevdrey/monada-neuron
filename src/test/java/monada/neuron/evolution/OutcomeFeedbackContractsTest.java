package monada.neuron.evolution;

import monada.neuron.action.ActionStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.reasoning.Proposition;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeFeedbackContractsTest {

    private static final UUID MONAD = uuid(100);

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static FeedbackEntry entry(long node, double score) {
        return FeedbackEntry.of(uuid(node), score);
    }

    private static OutcomeFeedback reinforce(List<FeedbackEntry> entries, List<HypothesisAttribution> attributions) {
        return new OutcomeFeedback(
                MONAD, 3L, ActionStatus.SUCCEEDED, 2, FeedbackDisposition.REINFORCE, entries, attributions);
    }

    @Test
    void feedbackEntryValidatesAndConvertsToFeedbackInput() {
        var target = new Signal(SignalKind.FEEDBACK, new FrequencyState(2.0, 20.0, 0.5));
        var withSignal = new FeedbackEntry(uuid(1), target, 0.5);
        var scoreOnly = FeedbackEntry.of(uuid(2), -0.25);

        assertAll(
                () -> assertEquals(FeedbackInput.ofTarget(uuid(1), target, 0.5), withSignal.toFeedbackInput()),
                () -> assertEquals(FeedbackInput.ofScore(uuid(2), -0.25), scoreOnly.toFeedbackInput()),
                () -> assertNull(scoreOnly.targetSignal()),
                () -> assertThrows(NullPointerException.class, () -> FeedbackEntry.of(null, 0.5)),
                () -> assertThrows(IllegalArgumentException.class, () -> FeedbackEntry.of(uuid(1), Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FeedbackEntry.of(uuid(1), Double.POSITIVE_INFINITY)),
                () -> assertThrows(IllegalArgumentException.class, () -> FeedbackEntry.of(uuid(1), 1.0001)),
                () -> assertThrows(IllegalArgumentException.class, () -> FeedbackEntry.of(uuid(1), -1.0001)),
                () -> assertThrows(IllegalArgumentException.class, () -> FeedbackEntry.of(uuid(1), 0.0)));
    }

    @Test
    void hypothesisAttributionValidatesPropositionAndScore() {
        var proposition = new Proposition(1, 42L);

        assertAll(
                () -> assertEquals(0.75, new HypothesisAttribution(proposition, 0.75).evaluationScore()),
                () -> assertThrows(NullPointerException.class, () -> new HypothesisAttribution(null, 0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisAttribution(proposition, Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisAttribution(proposition, -0.01)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HypothesisAttribution(proposition, 1.01)));
    }

    @Test
    void outcomeFeedbackSnapshotsItsListsAndExposesOrder() {
        var entries = new ArrayList<>(List.of(entry(1, 1.0), entry(2, 1.0)));
        var attributions = new ArrayList<>(List.of(new HypothesisAttribution(new Proposition(0, 7L), 0.5)));

        var feedback = reinforce(entries, attributions);
        entries.clear();
        attributions.clear();

        assertAll(
                () -> assertEquals(List.of(entry(1, 1.0), entry(2, 1.0)), feedback.entries()),
                () -> assertEquals(1, feedback.attributions().size()),
                () -> assertThrows(UnsupportedOperationException.class, () -> feedback.entries().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> feedback.attributions().clear()),
                () -> assertEquals(MONAD, feedback.monadId()),
                () -> assertEquals(3L, feedback.originCycleOrdinal()),
                () -> assertEquals(ActionStatus.SUCCEEDED, feedback.sourceStatus()),
                () -> assertEquals(2, feedback.admittedObservationCount()));
    }

    @Test
    void outcomeFeedbackRejectsInvalidCounters() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new OutcomeFeedback(
                        null, 0L, ActionStatus.SUCCEEDED, 0, FeedbackDisposition.NEUTRAL, List.of(), List.of())),
                () -> assertThrows(NullPointerException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, null, 0, FeedbackDisposition.NEUTRAL, List.of(), List.of())),
                () -> assertThrows(NullPointerException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.SUCCEEDED, 0, null, List.of(), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, -1L, ActionStatus.TIMED_OUT, 0, FeedbackDisposition.NEUTRAL, List.of(), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.TIMED_OUT, -1, FeedbackDisposition.NEUTRAL, List.of(), List.of())));
    }

    @Test
    void outcomeFeedbackBoundsEntriesAndAttributions() {
        var maxEntries = new ArrayList<FeedbackEntry>();
        for (int i = 0; i < OutcomeFeedback.MAX_ENTRIES + 1; i++) {
            maxEntries.add(entry(i + 1, 0.5));
        }
        var maxAttributions = new ArrayList<HypothesisAttribution>();
        for (int i = 0; i < OutcomeFeedback.MAX_ATTRIBUTIONS + 1; i++) {
            maxAttributions.add(new HypothesisAttribution(new Proposition(0, i), 0.5));
        }

        assertAll(
                () -> assertEquals(OutcomeFeedback.MAX_ENTRIES,
                        reinforce(maxEntries.subList(0, OutcomeFeedback.MAX_ENTRIES), List.of()).entries().size()),
                () -> assertThrows(IllegalArgumentException.class, () -> reinforce(maxEntries, List.of())),
                () -> assertEquals(OutcomeFeedback.MAX_ATTRIBUTIONS,
                        reinforce(List.of(entry(1, 0.5)), maxAttributions.subList(0, OutcomeFeedback.MAX_ATTRIBUTIONS))
                                .attributions().size()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> reinforce(List.of(entry(1, 0.5)), maxAttributions)));
    }

    @Test
    void outcomeFeedbackRequiresDispositionToMatchEntrySigns() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.SUCCEEDED, 1, FeedbackDisposition.REINFORCE,
                        List.of(entry(1, -0.5)), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.FAILED, 0, FeedbackDisposition.PENALIZE,
                        List.of(entry(1, 0.5)), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.TIMED_OUT, 0, FeedbackDisposition.NEUTRAL,
                        List.of(entry(1, 0.5)), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.FAILED, 0, FeedbackDisposition.PENALIZE,
                        List.of(entry(1, -0.5), entry(2, 0.5)), List.of())),
                () -> assertEquals(FeedbackDisposition.PENALIZE, new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.FAILED, 0, FeedbackDisposition.PENALIZE,
                        List.of(entry(1, -0.5), entry(2, -1.0)), List.of()).disposition()));
    }

    @Test
    void outcomeFeedbackRequiresEntriesForNonNeutralDispositions() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> reinforce(List.of(), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.FAILED, 0, FeedbackDisposition.PENALIZE, List.of(), List.of())));
    }

    private static HypothesisAttribution attribution(long code, double score) {
        return new HypothesisAttribution(new Proposition(0, code), score);
    }

    @Test
    void outcomeFeedbackRequiresAttributionsToBeUniqueAndInRankOrder() {
        var entries = List.of(entry(1, 0.5));

        assertAll(
                () -> assertEquals(0, reinforce(entries, List.of()).attributions().size()),
                () -> assertEquals(1, reinforce(entries, List.of(attribution(1, 0.4))).attributions().size()),
                () -> assertEquals(3, reinforce(entries,
                        List.of(attribution(1, 0.8), attribution(2, 0.5), attribution(3, 0.5))).attributions().size()),
                // a repeated proposition would double-credit one hypothesis
                () -> assertThrows(IllegalArgumentException.class,
                        () -> reinforce(entries, List.of(attribution(1, 0.8), attribution(1, 0.5)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> reinforce(entries, List.of(attribution(1, 0.9), attribution(2, 0.8), attribution(1, 0.1)))),
                // a later attribution must not outrank an earlier one
                () -> assertThrows(IllegalArgumentException.class,
                        () -> reinforce(entries, List.of(attribution(1, 0.5), attribution(2, 0.6)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> reinforce(entries, List.of(attribution(1, 0.9), attribution(2, 0.5), attribution(3, 0.7)))));
    }

    @Test
    void outcomeFeedbackRejectsObservationCountsNoValidActionOutcomeCouldProduce() {
        for (var status : List.of(ActionStatus.REJECTED, ActionStatus.FAILED)) {
            assertAll(
                    status.name(),
                    () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                            MONAD, 0L, status, 1, FeedbackDisposition.PENALIZE, List.of(entry(1, -0.5)), List.of())),
                    () -> assertEquals(0, new OutcomeFeedback(
                            MONAD, 0L, status, 0, FeedbackDisposition.PENALIZE, List.of(entry(1, -0.5)), List.of())
                            .admittedObservationCount()));
        }
        for (var status : List.of(ActionStatus.UNAVAILABLE, ActionStatus.TIMED_OUT)) {
            assertAll(
                    status.name(),
                    () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                            MONAD, 0L, status, 2, FeedbackDisposition.NEUTRAL, List.of(), List.of())),
                    () -> assertEquals(0, OutcomeFeedback.neutral(MONAD, 0L, status, List.of()).admittedObservationCount()));
        }
        for (var status : List.of(ActionStatus.SUCCEEDED, ActionStatus.PARTIALLY_COMPLETED)) {
            assertAll(
                    status.name(),
                    () -> assertEquals(3, new OutcomeFeedback(
                            MONAD, 0L, status, 3, FeedbackDisposition.REINFORCE, List.of(entry(1, 0.5)), List.of())
                            .admittedObservationCount()),
                    () -> assertEquals(0, new OutcomeFeedback(
                            MONAD, 0L, status, 0, FeedbackDisposition.REINFORCE, List.of(entry(1, 0.5)), List.of())
                            .admittedObservationCount()));
        }
    }

    @Test
    void outcomeFeedbackKeepsProducedAndAdmittedObservationCountsApart() {
        var truncated = new OutcomeFeedback(
                MONAD, 0L, ActionStatus.SUCCEEDED, 5, 3, FeedbackDisposition.REINFORCE,
                List.of(entry(1, 1.0)), List.of());
        var convenience = new OutcomeFeedback(
                MONAD, 0L, ActionStatus.SUCCEEDED, 4, FeedbackDisposition.REINFORCE,
                List.of(entry(1, 1.0)), List.of());

        assertAll(
                () -> assertEquals(5, truncated.producedObservationCount()),
                () -> assertEquals(3, truncated.admittedObservationCount()),
                // the single-count constructor describes a complete admission
                () -> assertEquals(4, convenience.producedObservationCount()),
                () -> assertEquals(4, convenience.admittedObservationCount()),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.SUCCEEDED, 2, 3, FeedbackDisposition.REINFORCE,
                        List.of(entry(1, 1.0)), List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.SUCCEEDED, -1, -1, FeedbackDisposition.REINFORCE,
                        List.of(entry(1, 1.0)), List.of())),
                // a failed outcome produced nothing, so nothing could have been truncated
                () -> assertThrows(IllegalArgumentException.class, () -> new OutcomeFeedback(
                        MONAD, 0L, ActionStatus.FAILED, 2, 0, FeedbackDisposition.PENALIZE,
                        List.of(entry(1, -1.0)), List.of())));
    }

    @Test
    void outcomeFeedbackRejectsDuplicateTargetsAndNullElements() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> reinforce(List.of(entry(1, 0.5), entry(1, 1.0)), List.of())),
                () -> assertThrows(NullPointerException.class,
                        () -> reinforce(java.util.Arrays.asList(entry(1, 0.5), null), List.of())),
                () -> assertThrows(NullPointerException.class, () -> reinforce(null, List.of())),
                () -> assertThrows(NullPointerException.class, () -> reinforce(List.of(entry(1, 0.5)), null)));
    }

    @Test
    void neutralFactoryProducesExplicitEmptyFeedback() {
        var neutral = OutcomeFeedback.neutral(MONAD, 9L, ActionStatus.UNAVAILABLE, List.of());

        assertAll(
                () -> assertEquals(FeedbackDisposition.NEUTRAL, neutral.disposition()),
                () -> assertTrue(neutral.entries().isEmpty()),
                () -> assertEquals(0, neutral.admittedObservationCount()),
                () -> assertEquals(ActionStatus.UNAVAILABLE, neutral.sourceStatus()));
    }

    @Test
    void configDefaultFollowsTheDocumentedSemantics() {
        var config = OutcomeFeedbackConfig.DEFAULT;

        assertAll(
                () -> assertEquals(1.0, config.successScore()),
                () -> assertEquals(0.5, config.partialScore()),
                () -> assertEquals(-0.25, config.rejectedScore()),
                () -> assertEquals(-1.0, config.failedScore()),
                () -> assertEquals(OutcomeFeedback.MAX_ENTRIES, config.maxEntries()),
                () -> assertEquals(4, config.maxAttributions()));
    }

    @Test
    void configValidatesFinitenessSignsAndBounds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(Double.NaN, 0.5, -0.25, -1.0, 8, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(1.0, Double.POSITIVE_INFINITY, -0.25, -1.0, 8, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(0.0, 0.5, -0.25, -1.0, 8, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(1.5, 0.5, -0.25, -1.0, 8, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(1.0, -0.5, -0.25, -1.0, 8, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(1.0, 0.5, 0.25, -1.0, 8, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(1.0, 0.5, -0.25, 0.0, 8, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 0, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(
                                1.0, 0.5, -0.25, -1.0, OutcomeFeedback.MAX_ENTRIES + 1, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 8, -1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutcomeFeedbackConfig(
                                1.0, 0.5, -0.25, -1.0, 8, OutcomeFeedback.MAX_ATTRIBUTIONS + 1)),
                () -> assertEquals(0, new OutcomeFeedbackConfig(1.0, 0.5, -0.25, -1.0, 8, 0).maxAttributions()));
    }
}
