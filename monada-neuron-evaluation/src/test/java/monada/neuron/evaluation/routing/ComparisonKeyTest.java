package monada.neuron.evaluation.routing;

import monada.neuron.routing.ResourceObjective;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ComparisonKeyTest {

    private static final ComparisonKey BASE = key("implement", "gates", "1", "route-lex", "1", Optional.empty());

    @Test
    void identicalKeysAreDirectlyComparable() {
        assertEquals(List.of(), BASE.differingFields(key("implement", "gates", "1", "route-lex", "1",
                Optional.empty())));
    }

    @Test
    void differentStagesOfOneTaskAreNeverADirectPair() {
        var review = key("review", "gates", "1", "route-lex", "1", Optional.empty());
        assertAll(
                () -> assertNotEquals(BASE, review),
                () -> assertEquals(List.of("stageKind"), BASE.differingFields(review)));
    }

    @Test
    void separatorsInsidePolicyIdentitiesCannotMergeTwoPolicies() {
        // "a@b" + "c" and "a" + "b@c" would both read "a@b@c" if concatenated
        var first = key("implement", "a@b", "c", "r@s", "t", Optional.empty());
        var second = key("implement", "a", "b@c", "r", "s@t", Optional.empty());
        assertAll(
                () -> assertNotEquals(first, second),
                () -> assertEquals(List.of("evaluationPolicy", "routingPolicy"), first.differingFields(second)));
    }

    @Test
    void aDifferentObjectiveBindingIsNotComparable() {
        var objective = key("implement", "gates", "1", "route-lex", "1",
                Optional.of(new ResourceObjective("latency", ResourceObjective.Direction.MINIMIZE)));
        assertEquals(List.of("objective"), BASE.differingFields(objective));
    }

    private static ComparisonKey key(String stage, String evaluationId, String evaluationVersion, String routingId,
            String routingVersion, Optional<ResourceObjective> objective) {
        return new ComparisonKey("scope", "task", stage, "src", "ctx", "con", evaluationId, evaluationVersion,
                routingId, routingVersion, objective);
    }
}
