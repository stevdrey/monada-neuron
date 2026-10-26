package monada.neuron.routing.features;

import monada.neuron.routing.features.FeatureReadinessAssessment.Assessed;
import monada.neuron.routing.features.FeatureReadinessAssessment.SchemaMismatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFeatureReadinessTest {

    private final TaskFeatureReadiness readiness = new TaskFeatureReadiness();

    private static TaskFeatures full() {
        return TaskFeatures.builder()
                .stageKind("implement")
                .category("bugfix")
                .languages(List.of("java"))
                .domains(List.of("backend"))
                .changeSize(10)
                .contextSize(100)
                .tests(Requirement.REQUIRED)
                .security(Requirement.NOT_REQUIRED)
                .build();
    }

    private Assessed assess(TaskFeatures features, Set<FeatureDimension> required) {
        return assertInstanceOf(Assessed.class, readiness.assess(features, "implement", required));
    }

    // covers: AC-1, AC-2
    @Test
    void fullSnapshotGivesEightKnownInformationRowsInDeclarationOrder() {
        Assessed assessed = assess(full(), Set.of());

        List<FieldDiagnostic> rows = assessed.diagnostics();
        assertEquals(8, rows.size());
        for (FeatureDimension dimension : FeatureDimension.values()) {
            FieldDiagnostic row = rows.get(dimension.ordinal());
            assertEquals(dimension, row.dimension());
            assertEquals(FieldStatus.KNOWN, row.status());
            assertEquals(DiagnosticSeverity.INFORMATION, row.severity());
        }
        assertTrue(assessed.missingForHostPreview().isEmpty());
        assertTrue(assessed.inconsistentWithRequest().isEmpty());
    }

    // covers: AC-1
    @Test
    void emptySnapshotGivesEightUnknownRows() {
        Assessed assessed = assess(TaskFeatures.builder().build(), Set.of());

        for (FieldDiagnostic row : assessed.diagnostics()) {
            assertEquals(FieldStatus.UNKNOWN, row.status());
            assertEquals(DiagnosticSeverity.INFORMATION, row.severity());
        }
    }

    // covers: AC-1, AC-2
    @Test
    void emptySnapshotWithEverythingRequiredIsMissingEverywhere() {
        Assessed assessed = assess(TaskFeatures.builder().build(), EnumSet.allOf(FeatureDimension.class));

        for (FieldDiagnostic row : assessed.diagnostics()) {
            assertEquals(FieldStatus.UNKNOWN, row.status());
            assertTrue(row.required());
            assertEquals(DiagnosticSeverity.MISSING_FOR_HOST_PREVIEW, row.severity());
        }
        assertEquals(List.of(FeatureDimension.values()), assessed.missingForHostPreview());
    }

    // covers: AC-1
    @Test
    void equalInputsGiveEqualAssessments() {
        TaskFeatures features = full();

        assertEquals(readiness.assess(features, "implement", Set.of(FeatureDimension.TESTS)),
                readiness.assess(features, "implement", Set.of(FeatureDimension.TESTS)));
        assertEquals(assess(features, Set.of()).diagnostics(), assess(features, Set.of()).diagnostics());
    }

    private static TaskFeatures mixed() {
        return TaskFeatures.builder().stageKind("implement").languages(List.of("java")).build();
    }

    // covers: AC-1, AC-2, AC-4
    @Test
    void mixedSnapshotListsOnlyRequiredUnknownDimensionsAsMissing() {
        Assessed assessed = assess(mixed(), Set.of(FeatureDimension.CATEGORY, FeatureDimension.TESTS,
                FeatureDimension.LANGUAGES));

        List<FieldDiagnostic> rows = assessed.diagnostics();
        assertEquals(FieldDiagnostic.of(FeatureDimension.STAGE_KIND, FieldStatus.KNOWN, false), rows.get(0));
        assertEquals(FieldDiagnostic.of(FeatureDimension.CATEGORY, FieldStatus.UNKNOWN, true), rows.get(1));
        assertEquals(FieldDiagnostic.of(FeatureDimension.LANGUAGES, FieldStatus.KNOWN, true), rows.get(2));
        assertEquals(FieldDiagnostic.of(FeatureDimension.DOMAINS, FieldStatus.UNKNOWN, false), rows.get(3));
        assertEquals(DiagnosticSeverity.INFORMATION, rows.get(2).severity());
        assertEquals(DiagnosticSeverity.INFORMATION, rows.get(3).severity());
        assertEquals(List.of(FeatureDimension.CATEGORY, FeatureDimension.TESTS), assessed.missingForHostPreview());
        assertTrue(assessed.inconsistentWithRequest().isEmpty());
        assertEquals(assessed, assess(mixed(), Set.of(FeatureDimension.TESTS, FeatureDimension.LANGUAGES,
                FeatureDimension.CATEGORY)));
    }

    // covers: AC-4
    @Test
    void emptyRequiredSetMeansNothingIsRequired() {
        Assessed assessed = assess(TaskFeatures.builder().build(), Set.of());

        assertTrue(assessed.required().isEmpty());
        assertTrue(assessed.missingForHostPreview().isEmpty());
        assertTrue(assessed.diagnostics().stream().noneMatch(FieldDiagnostic::required));
    }

    // covers: AC-4
    @Test
    void requiredSetOrderAndImplementationDoNotChangeTheAssessment() {
        Assessed fromEnumSet = assess(mixed(), EnumSet.of(FeatureDimension.SECURITY, FeatureDimension.CATEGORY));
        Assessed fromSetOf = assess(mixed(), Set.of(FeatureDimension.CATEGORY, FeatureDimension.SECURITY));
        Assessed forward = assess(mixed(), new LinkedHashSet<>(List.of(FeatureDimension.CATEGORY,
                FeatureDimension.SECURITY)));
        Assessed backward = assess(mixed(), new LinkedHashSet<>(List.of(FeatureDimension.SECURITY,
                FeatureDimension.CATEGORY)));

        assertEquals(fromEnumSet, fromSetOf);
        assertEquals(fromEnumSet, forward);
        assertEquals(fromEnumSet, backward);
        assertEquals(List.of(FeatureDimension.CATEGORY, FeatureDimension.SECURITY),
                List.copyOf(backward.required()));
        assertThrows(UnsupportedOperationException.class, () -> backward.required().add(FeatureDimension.TESTS));
        assertThrows(UnsupportedOperationException.class, () -> backward.diagnostics().clear());
        assertThrows(UnsupportedOperationException.class, () -> backward.missingForHostPreview().clear());
        assertThrows(UnsupportedOperationException.class, () -> backward.inconsistentWithRequest().clear());
    }

    // covers: AC-4
    @Test
    void requiredSetIsCopiedNotShared() {
        Set<FeatureDimension> required = new HashSet<>(Set.of(FeatureDimension.CATEGORY));
        Assessed assessed = assess(mixed(), required);

        required.add(FeatureDimension.TESTS);

        assertEquals(Set.of(FeatureDimension.CATEGORY), assessed.required());
    }

    // covers: AC-2, AC-3
    @Test
    void differentStageKindIsInconsistentWithTheRequestWhetherRequiredOrNot() {
        TaskFeatures features = TaskFeatures.builder().stageKind("review").build();

        for (Set<FeatureDimension> required : List.of(Set.<FeatureDimension>of(),
                Set.of(FeatureDimension.STAGE_KIND))) {
            Assessed assessed = assertInstanceOf(Assessed.class, readiness.assess(features, "plan", required));

            FieldDiagnostic stage = assessed.diagnostics().getFirst();
            assertEquals(FieldStatus.MISMATCH, stage.status());
            assertEquals(DiagnosticSeverity.INCONSISTENT_WITH_REQUEST, stage.severity());
            assertEquals(List.of(FeatureDimension.STAGE_KIND), assessed.inconsistentWithRequest());
            assertFalse(assessed.missingForHostPreview().contains(FeatureDimension.STAGE_KIND));
            assertSame(features, assessed.features());
        }
    }

    // covers: AC-3
    @Test
    void equalStageKindIsKnownAndUnknownStageKindStaysUnknown() {
        FieldDiagnostic equal = assess(TaskFeatures.builder().stageKind("implement").build(), Set.of())
                .diagnostics().getFirst();
        FieldDiagnostic unknown = assess(TaskFeatures.builder().build(), Set.of(FeatureDimension.STAGE_KIND))
                .diagnostics().getFirst();

        assertEquals(FieldStatus.KNOWN, equal.status());
        assertEquals(FieldStatus.UNKNOWN, unknown.status());
        assertEquals(DiagnosticSeverity.MISSING_FOR_HOST_PREVIEW, unknown.severity());
    }

    // covers: AC-5
    @Test
    void newerSchemaIsReportedWithoutRows() {
        TaskFeatures features = TaskFeatures.builder().schemaVersion("task-features/2").stageKind("review").build();

        FeatureReadinessAssessment result = readiness.assess(features, "plan", Set.of(FeatureDimension.CATEGORY));

        assertEquals(new SchemaMismatch(TaskFeatures.SCHEMA_VERSION, "task-features/2"), result);
    }

    // covers: AC-6
    @Test
    void nullArgumentsThrowWithTheirName() {
        assertNullMessage("features must not be null", () -> readiness.assess(null, "implement", Set.of()));
        assertNullMessage("expectedStageKind must not be null", () -> readiness.assess(mixed(), null, Set.of()));
        assertNullMessage("required must not be null", () -> readiness.assess(mixed(), "implement", null));
        assertNullMessage("required must not contain null",
                () -> readiness.assess(mixed(), "implement", new HashSet<>(Arrays.asList(FeatureDimension.TESTS,
                        null))));
    }

    // covers: AC-6
    @Test
    void argumentChecksRunBeforeTheSchemaCheck() {
        TaskFeatures newer = TaskFeatures.builder().schemaVersion("task-features/2").build();

        assertNullMessage("required must not be null", () -> readiness.assess(newer, "implement", null));
        assertNullMessage("required must not contain null",
                () -> readiness.assess(newer, "implement", new HashSet<>(Arrays.asList((FeatureDimension) null))));
        assertThrows(IllegalArgumentException.class, () -> readiness.assess(newer, "", Set.of()));
    }

    // covers: AC-6
    @Test
    void invalidExpectedStageKindIsRejectedBeforeNullElements() {
        Set<FeatureDimension> withNull = new HashSet<>(Arrays.asList((FeatureDimension) null));

        for (String token : List.of("", "a".repeat(129), "bad\ntoken", " padded")) {
            assertThrows(IllegalArgumentException.class, () -> readiness.assess(mixed(), token, withNull), token);
        }
    }

    private static TaskFeatures onlyKnown(FeatureDimension dimension) {
        TaskFeatures.Builder builder = TaskFeatures.builder();
        switch (dimension) {
            case STAGE_KIND -> builder.stageKind("implement");
            case CATEGORY -> builder.category("bugfix");
            case LANGUAGES -> builder.languages(List.of("java"));
            case DOMAINS -> builder.domains(List.of("backend"));
            case CHANGE_SIZE -> builder.changeSize(1);
            case CONTEXT_SIZE -> builder.contextSize(1);
            case TESTS -> builder.tests(Requirement.REQUIRED);
            case SECURITY -> builder.security(Requirement.REQUIRED);
        }
        return builder.build();
    }

    // covers: AC-1
    @ParameterizedTest
    @EnumSource(FeatureDimension.class)
    void eachRowReadsOnlyItsOwnFeatureComponent(FeatureDimension known) {
        Assessed assessed = assess(onlyKnown(known), Set.of());

        for (FieldDiagnostic row : assessed.diagnostics()) {
            FieldStatus expected = row.dimension() == known ? FieldStatus.KNOWN : FieldStatus.UNKNOWN;
            assertEquals(expected, row.status(), row.dimension().name());
        }
    }

    // covers: AC-2
    @ParameterizedTest
    @EnumSource(FeatureDimension.class)
    void requiringOneUnknownDimensionMarksOnlyThatRowMissing(FeatureDimension required) {
        Assessed assessed = assess(TaskFeatures.builder().build(), Set.of(required));

        assertEquals(List.of(required), assessed.missingForHostPreview());
        for (FieldDiagnostic row : assessed.diagnostics()) {
            assertEquals(row.dimension() == required, row.required(), row.dimension().name());
        }
    }

    // covers: AC-1, AC-2
    @Test
    void zeroSizesEmptyTagsAndNotRequiredAreKnownValuesNotUnknown() {
        TaskFeatures features = TaskFeatures.builder()
                .languages(List.of())
                .domains(List.of())
                .changeSize(0)
                .contextSize(0)
                .tests(Requirement.NOT_REQUIRED)
                .security(Requirement.NOT_REQUIRED)
                .build();

        Assessed assessed = assess(features, EnumSet.range(FeatureDimension.LANGUAGES, FeatureDimension.SECURITY));

        assertEquals(List.of(), assessed.missingForHostPreview());
        for (FieldDiagnostic row : assessed.diagnostics().subList(2, 8)) {
            assertEquals(FieldStatus.KNOWN, row.status(), row.dimension().name());
        }
    }

    // covers: AC-3
    @Test
    void stageKindIsComparedExactlyWithoutCaseOrUnicodeNormalization() {
        assertStageStatus(FieldStatus.MISMATCH, "Plan", "plan");
        assertStageStatus(FieldStatus.MISMATCH, "\u00e9tape", "e\u0301tape");
        assertStageStatus(FieldStatus.KNOWN, "\uD83D\uDE80 launch", "\uD83D\uDE80 launch");
    }

    private void assertStageStatus(FieldStatus expected, String featureStage, String expectedStage) {
        TaskFeatures features = TaskFeatures.builder().stageKind(featureStage).build();
        Assessed assessed = assertInstanceOf(Assessed.class, readiness.assess(features, expectedStage, Set.of()));

        assertEquals(expected, assessed.diagnostics().getFirst().status(), featureStage + " vs " + expectedStage);
    }

    // covers: AC-2, AC-3
    @Test
    void mismatchAndMissingAreReportedSideBySide() {
        TaskFeatures features = TaskFeatures.builder().stageKind("review").build();

        Assessed assessed = assertInstanceOf(Assessed.class, readiness.assess(features, "plan",
                Set.of(FeatureDimension.STAGE_KIND, FeatureDimension.CATEGORY)));

        assertEquals(List.of(FeatureDimension.STAGE_KIND), assessed.inconsistentWithRequest());
        assertEquals(List.of(FeatureDimension.CATEGORY), assessed.missingForHostPreview());
    }

    // covers: AC-6
    @Test
    void expectedStageKindAcceptsExactly128CodePointsIncludingSupplementaryCharacters() {
        String rocket = "\uD83D\uDE80";

        assertInstanceOf(Assessed.class, readiness.assess(mixed(), rocket.repeat(128), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> readiness.assess(mixed(), rocket.repeat(129), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> readiness.assess(mixed(), "plan ", Set.of()));
    }

    // covers: AC-6
    @Test
    void nullChecksRunBeforeTheTokenCheck() {
        assertNullMessage("required must not be null", () -> readiness.assess(mixed(), "", null));
        assertNullMessage("features must not be null", () -> readiness.assess(null, "", null));
    }

    // covers: AC-4
    @Test
    void requiredSetVariantsGiveEqualHashCodes() {
        Assessed fromSetOf = assess(mixed(), Set.of(FeatureDimension.TESTS, FeatureDimension.DOMAINS));
        Assessed fromLinked = assess(mixed(), new LinkedHashSet<>(List.of(FeatureDimension.TESTS,
                FeatureDimension.DOMAINS)));

        assertEquals(fromSetOf.hashCode(), fromLinked.hashCode());
        assertNotEquals(fromSetOf, assess(mixed(), Set.of(FeatureDimension.TESTS)));
    }

    // covers: AC-5
    @Test
    void anyOtherSchemaIsAMismatchNamingTheSupportedVersion() {
        TaskFeatures features = TaskFeatures.builder().schemaVersion("other-schema/1").build();

        assertEquals(new SchemaMismatch(TaskFeatures.SCHEMA_VERSION, "other-schema/1"),
                readiness.assess(features, "plan", Set.of()));
    }

    private static void assertNullMessage(String message, Runnable call) {
        NullPointerException thrown = assertThrows(NullPointerException.class, call::run);
        assertEquals(message, thrown.getMessage());
    }
}
