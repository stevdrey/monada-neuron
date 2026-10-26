package monada.neuron.routing.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldDiagnosticTest {

    @Test
    void factoryAppliesTheSeverityTable() {
        assertEquals(DiagnosticSeverity.INFORMATION,
                FieldDiagnostic.of(FeatureDimension.TESTS, FieldStatus.KNOWN, false).severity());
        assertEquals(DiagnosticSeverity.INFORMATION,
                FieldDiagnostic.of(FeatureDimension.TESTS, FieldStatus.KNOWN, true).severity());
        assertEquals(DiagnosticSeverity.INFORMATION,
                FieldDiagnostic.of(FeatureDimension.TESTS, FieldStatus.UNKNOWN, false).severity());
        assertEquals(DiagnosticSeverity.MISSING_FOR_HOST_PREVIEW,
                FieldDiagnostic.of(FeatureDimension.TESTS, FieldStatus.UNKNOWN, true).severity());
        assertEquals(DiagnosticSeverity.INCONSISTENT_WITH_REQUEST,
                FieldDiagnostic.of(FeatureDimension.STAGE_KIND, FieldStatus.MISMATCH, false).severity());
        assertEquals(DiagnosticSeverity.INCONSISTENT_WITH_REQUEST,
                FieldDiagnostic.of(FeatureDimension.STAGE_KIND, FieldStatus.MISMATCH, true).severity());
    }

    @Test
    void mismatchIsRejectedOffStageKind() {
        assertThrows(IllegalArgumentException.class,
                () -> FieldDiagnostic.of(FeatureDimension.TESTS, FieldStatus.MISMATCH, true));
        assertThrows(IllegalArgumentException.class, () -> new FieldDiagnostic(FeatureDimension.CATEGORY,
                FieldStatus.MISMATCH, false, DiagnosticSeverity.INCONSISTENT_WITH_REQUEST));
    }

    @Test
    void severityThatBreaksTheTableIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FieldDiagnostic(FeatureDimension.CATEGORY,
                FieldStatus.UNKNOWN, true, DiagnosticSeverity.INFORMATION));
        assertThrows(IllegalArgumentException.class, () -> new FieldDiagnostic(FeatureDimension.CATEGORY,
                FieldStatus.KNOWN, true, DiagnosticSeverity.MISSING_FOR_HOST_PREVIEW));
        assertThrows(IllegalArgumentException.class, () -> new FieldDiagnostic(FeatureDimension.STAGE_KIND,
                FieldStatus.MISMATCH, false, DiagnosticSeverity.INFORMATION));
    }

    @Test
    void nullComponentsThrowWithTheirName() {
        assertEquals("dimension must not be null", assertThrows(NullPointerException.class,
                () -> FieldDiagnostic.of(null, FieldStatus.KNOWN, false)).getMessage());
        assertEquals("status must not be null", assertThrows(NullPointerException.class,
                () -> FieldDiagnostic.of(FeatureDimension.TESTS, null, false)).getMessage());
        assertEquals("severity must not be null", assertThrows(NullPointerException.class,
                () -> new FieldDiagnostic(FeatureDimension.TESTS, FieldStatus.KNOWN, false, null)).getMessage());
    }
}
