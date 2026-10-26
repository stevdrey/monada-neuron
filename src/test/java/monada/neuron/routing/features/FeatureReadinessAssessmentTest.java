package monada.neuron.routing.features;

import monada.neuron.routing.features.FeatureReadinessAssessment.Assessed;
import monada.neuron.routing.features.FeatureReadinessAssessment.SchemaMismatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeatureReadinessAssessmentTest {

    private static TaskFeatures mixed() {
        return TaskFeatures.builder().stageKind("implement").languages(List.of("java")).build();
    }

    @Test
    void handBuiltAssessedEqualsTheOneAssessReturns() {
        TaskFeatures features = mixed();
        Set<FeatureDimension> required = new LinkedHashSet<>(List.of(FeatureDimension.TESTS,
                FeatureDimension.CATEGORY));

        assertEquals(new TaskFeatureReadiness().assess(features, "implement", Set.copyOf(required)),
                new Assessed(features, "implement", required));
    }

    @Test
    void assessedRejectsAnotherSchema() {
        TaskFeatures newer = TaskFeatures.builder().schemaVersion("task-features/2").build();

        assertThrows(IllegalArgumentException.class, () -> new Assessed(newer, "implement", Set.of()));
    }

    @Test
    void assessedChecksArgumentsInOrder() {
        TaskFeatures newer = TaskFeatures.builder().schemaVersion("task-features/2").build();
        Set<FeatureDimension> withNull = new HashSet<>(Arrays.asList((FeatureDimension) null));

        assertNullMessage("features must not be null", () -> new Assessed(null, "implement", Set.of()));
        assertNullMessage("expectedStageKind must not be null", () -> new Assessed(newer, null, null));
        assertNullMessage("required must not be null", () -> new Assessed(newer, "implement", null));
        assertThrows(IllegalArgumentException.class, () -> new Assessed(newer, "", withNull));
        assertNullMessage("required must not contain null", () -> new Assessed(newer, "implement", withNull));
    }

    @Test
    void schemaMismatchRequiresTwoDifferentValidTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaMismatch(TaskFeatures.SCHEMA_VERSION, TaskFeatures.SCHEMA_VERSION));
        assertThrows(IllegalArgumentException.class, () -> new SchemaMismatch("", "task-features/2"));
        assertThrows(IllegalArgumentException.class, () -> new SchemaMismatch(TaskFeatures.SCHEMA_VERSION, " v2"));
        assertNullMessage("expected must not be null", () -> new SchemaMismatch(null, "task-features/2"));
        assertNullMessage("actual must not be null", () -> new SchemaMismatch(TaskFeatures.SCHEMA_VERSION, null));
    }

    private static void assertNullMessage(String message, Runnable call) {
        NullPointerException thrown = assertThrows(NullPointerException.class, call::run);
        assertEquals(message, thrown.getMessage());
    }
}
