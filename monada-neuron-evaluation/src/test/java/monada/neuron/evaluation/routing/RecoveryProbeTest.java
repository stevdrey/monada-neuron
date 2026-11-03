package monada.neuron.evaluation.routing;

import monada.neuron.routing.adaptation.recovery.RecoveryAssessment;
import monada.neuron.routing.adaptation.recovery.RecoveryBlocker;
import monada.neuron.routing.adaptation.recovery.RecoveryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryProbeTest {

    private static final RecoveryAssessment.Blocked BLOCKED =
            new RecoveryAssessment.Blocked(List.of(new RecoveryBlocker.ExportUnavailable()));

    private static RecoveryProbe probe(RecoveryProbe.RecoveryOutcome outcome) {
        return new RecoveryProbe("case", BLOCKED, Optional.empty(), RecoveryPlan.of(BLOCKED), Optional.of("sha"), 10,
                outcome, false);
    }

    @Test
    void aBlockedCaseIsRecordedWithoutARebuild() {
        RecoveryProbe blocked = probe(RecoveryProbe.RecoveryOutcome.BLOCKED);

        assertAll(
                () -> assertTrue(blocked.rebuild().isEmpty()),
                () -> assertFalse(blocked.parity()));
    }

    @Test
    void aCaseThatClaimsToHaveRebuiltMustCarryItsRebuildResult() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> probe(RecoveryProbe.RecoveryOutcome.COMPATIBLE_REBUILT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> probe(RecoveryProbe.RecoveryOutcome.REBUILD_FAILED_SCRATCH_FALLBACK)));
    }
}
