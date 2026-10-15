package monada.neuron.evaluation.routing;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.ScoreRule;
import monada.neuron.routing.outcome.EligibilityVerdict;
import monada.neuron.routing.outcome.StageAttribution;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackEventTest {

    private static final RouteKey EXECUTED = new RouteKey("sub-a", 1);
    private static final DecisionRef REF = new DecisionRef("scope", "task", "exe", "att", "stage", 1L);
    private static final CohortKey OWN = new CohortKey("scope", "implement", "M", EXECUTED);

    @Test
    void aStageLocalRewardOfAPositiveCandidateIsNotAViolation() {
        assertFalse(event(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL, EligibilityVerdict.POSITIVE_CANDIDATE,
                StageAttribution.Basis.STAGE_LOCAL, Optional.of(OWN)).eligibilityViolation());
    }

    @Test
    void everyBrokenCreditRuleIsAViolation() {
        assertAll(
                // reward without a positive candidate
                () -> assertTrue(event(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                        EligibilityVerdict.NOT_ELIGIBLE, StageAttribution.Basis.STAGE_LOCAL, Optional.of(OWN))
                        .eligibilityViolation()),
                // reward for chain-only evidence
                () -> assertTrue(event(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                        EligibilityVerdict.POSITIVE_CANDIDATE, StageAttribution.Basis.CHAIN_ONLY, Optional.of(OWN))
                        .eligibilityViolation()),
                // credit for a route that did not execute
                () -> assertTrue(event(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                        EligibilityVerdict.POSITIVE_CANDIDATE, StageAttribution.Basis.STAGE_LOCAL,
                        Optional.of(new CohortKey("scope", "implement", "M", new RouteKey("sub-b", 1))))
                        .eligibilityViolation()),
                // credit for another stage kind
                () -> assertTrue(event(ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE,
                        EligibilityVerdict.NEGATIVE_CANDIDATE, StageAttribution.Basis.STAGE_LOCAL,
                        Optional.of(new CohortKey("scope", "review", "M", EXECUTED))).eligibilityViolation()),
                // penalty without a negative candidate
                () -> assertTrue(event(ScoreRule.PENALIZE_PROVEN_MANDATORY_FAILURE,
                        EligibilityVerdict.NOT_ELIGIBLE, StageAttribution.Basis.STAGE_LOCAL, Optional.of(OWN))
                        .eligibilityViolation()),
                // credit for another cohort bucket of the same route, stage and scope
                () -> assertTrue(event(ScoreRule.REINFORCE_VALIDATED_STAGE_LOCAL,
                        EligibilityVerdict.POSITIVE_CANDIDATE, StageAttribution.Basis.STAGE_LOCAL,
                        Optional.of(new CohortKey("scope", "implement", "L", EXECUTED))).eligibilityViolation()),
                // a neutral rule that moves a Node
                () -> assertTrue(event(ScoreRule.NEUTRAL_ENVIRONMENTAL, EligibilityVerdict.NOT_ELIGIBLE,
                        StageAttribution.Basis.STAGE_LOCAL, Optional.of(OWN)).eligibilityViolation()));
    }

    @Test
    void exactlyOneOfRuleAndNoUpdateIsPresent() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new FeedbackEvent("exp", 0, 1, REF,
                        "implement", EXECUTED, "M", true, EligibilityVerdict.NOT_ELIGIBLE,
                        StageAttribution.Basis.STAGE_LOCAL, Optional.empty(), Optional.empty(), Optional.empty(),
                        FeedbackEvent.Update.NOT_CONSUMED)),
                () -> assertFalse(new FeedbackEvent("exp", 0, 1, REF, "implement", EXECUTED, "M", true,
                        EligibilityVerdict.NOT_ELIGIBLE, StageAttribution.Basis.CHAIN_ONLY, Optional.empty(),
                        Optional.of(NoUpdateReason.NO_STAGE_ATTRIBUTION), Optional.empty(),
                        FeedbackEvent.Update.RECORDED).eligibilityViolation()));
    }

    private static FeedbackEvent event(ScoreRule rule, EligibilityVerdict verdict, StageAttribution.Basis basis,
            Optional<CohortKey> credited) {
        return new FeedbackEvent("exp", 0, 1, REF, "implement", EXECUTED, "M", true, verdict, basis, Optional.of(rule),
                Optional.empty(), credited, FeedbackEvent.Update.APPLIED);
    }
}
