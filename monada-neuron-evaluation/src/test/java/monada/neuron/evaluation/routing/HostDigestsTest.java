package monada.neuron.evaluation.routing;

import monada.neuron.routing.adaptation.recovery.RecoveryAssessment;
import monada.neuron.routing.adaptation.recovery.RecoveryBlocker;
import monada.neuron.routing.adaptation.recovery.RecoveryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HostDigestsTest {

    private final HostDigests digests = new HostDigests();

    private static HostDecisionRecord record(int index, String receiptSha256) {
        return new HostDecisionRecord(index, "implement", ReplaySample.DecisionKind.SELECTED, Optional.empty(),
                receiptSha256, 100, List.of(), HostAction.FOLLOW, Optional.empty(), new HostExecution.Advised());
    }

    private static RecoveryProbe blocked(String caseId, String sha) {
        var assessment = new RecoveryAssessment.Blocked(List.of(new RecoveryBlocker.ExportUnavailable()));
        return new RecoveryProbe(caseId, assessment, Optional.empty(), RecoveryPlan.of(assessment), Optional.of(sha),
                10, RecoveryProbe.RecoveryOutcome.BLOCKED, false);
    }

    @Test
    void theReceiptChainDependsOnEveryReceiptAndItsOrder() {
        String chain = digests.receiptChain(List.of(record(0, "a"), record(1, "b")));

        assertEquals(chain, digests.receiptChain(List.of(record(0, "a"), record(1, "b"))));
        assertNotEquals(chain, digests.receiptChain(List.of(record(0, "b"), record(1, "a"))));
        assertNotEquals(chain, digests.receiptChain(List.of(record(0, "a"), record(1, "c"))));
    }

    @Test
    void theRecoveryDigestDependsOnTheCaseTheCheckpointAndTheBlockers() {
        String recovery = digests.recovery(List.of(blocked("one", "x")));

        assertEquals(recovery, digests.recovery(List.of(blocked("one", "x"))));
        assertNotEquals(recovery, digests.recovery(List.of(blocked("two", "x"))));
        assertNotEquals(recovery, digests.recovery(List.of(blocked("one", "y"))));
    }
}
