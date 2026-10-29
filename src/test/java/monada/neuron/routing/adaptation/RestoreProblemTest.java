package monada.neuron.routing.adaptation;

import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.CohortKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Each problem's {@code message()} is the exact text {@code rebuildFrom} threw before issue #101 moved the rules behind
 * typed problems. The parity with the store's live exceptions is {@link RecoveryParityTest}; this pins the literals so a
 * reworded message fails a named test.
 */
class RestoreProblemTest {

    private static final CohortKey KEY = new CohortKey("scope", "implement", "UNKNOWN", new RouteKey("route-a", 1));

    // covers: AC-4
    @Test
    void everyIdentityMismatchSharesTheStoresOneMessage() {
        for (PartitionField field : List.of(PartitionField.SCOPE, PartitionField.EVALUATION_POLICY,
                PartitionField.MAPPING_VERSION, PartitionField.FEATURE_SCHEMA)) {
            assertEquals("the checkpoint belongs to another partition or definition",
                    new RestoreProblem.Mismatch(field).message(), field.name());
        }
    }

    // covers: AC-4
    @Test
    void theMarkerAndCohortProblemsNameTheOffender() {
        assertEquals("marker exp-b was recorded under bucket mapping other-buckets/9, not change-size/1",
                new RestoreProblem.BucketMappingMismatch("exp-b", "other-buckets/9", "change-size/1").message());
        assertEquals("cohort " + KEY + " holds a Node channel outside the definition's adaptation bounds",
                new RestoreProblem.ChannelOutOfBounds(KEY).message());
        assertEquals("cohort " + KEY + " was bound after the checkpoint at 21, which records sample exp-b as unbound; "
                + "rebuild from an earlier checkpoint or from scratch",
                new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp-b", 20, 21).message());
    }

    // covers: AC-4
    @Test
    void theExportProblemsCarryBothCutoffs() {
        assertEquals("exportCutoff 29 is below the checkpoint's cutoff 30",
                new RestoreProblem.ExportBehindCheckpoint(29, 30).message());
        assertEquals("exportCutoff 30 is below processedCutoff 40: the rebuilt state would forget examined observations",
                new RestoreProblem.ExportBehindStore(30, 40).message());
    }

    // covers: AC-4
    @Test
    void theCapacityMessageMentionsReservationsOnlyWhenThereAreAny() {
        assertEquals("the checkpoint's cohorts and this store's together are 257, more than the "
                + RoutingStateStore.MAX_COHORTS + " a rebuilt store allows",
                new RestoreProblem.CohortCapacity(257, 0).message());
        assertEquals("the checkpoint's cohorts and this store's together are 258, 3 of them held only by this store's "
                + "checkpoints, more than the " + RoutingStateStore.MAX_COHORTS + " a rebuilt store allows",
                new RestoreProblem.CohortCapacity(258, 3).message());
    }

    @Test
    void theProblemsRefuseMissingParts() {
        assertThrows(NullPointerException.class, () -> new RestoreProblem.Mismatch(null));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.Mismatch(PartitionField.BUCKET_MAPPING));
        assertThrows(NullPointerException.class, () -> new RestoreProblem.BucketMappingMismatch(null, "a/1", "b/1"));
        assertThrows(NullPointerException.class, () -> new RestoreProblem.BucketMappingMismatch("exp", null, "b/1"));
        assertThrows(NullPointerException.class, () -> new RestoreProblem.BucketMappingMismatch("exp", "a/1", null));
        assertThrows(NullPointerException.class, () -> new RestoreProblem.ChannelOutOfBounds(null));
        assertThrows(NullPointerException.class, () -> new RestoreProblem.CohortBoundAfterCheckpoint(null, "exp", 0, 0));
        assertThrows(NullPointerException.class, () -> new RestoreProblem.CohortBoundAfterCheckpoint(KEY, null, 0, 0));
    }

    // covers: AC-6
    @Test
    void theNumericProblemsRefuseValuesTheStoreNeverReports() {
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.ExportBehindCheckpoint(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.ExportBehindCheckpoint(5, 5));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.ExportBehindCheckpoint(6, 5));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.ExportBehindStore(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.ExportBehindStore(5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", -1, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", 6, 5));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.CohortCapacity(RoutingStateStore.MAX_COHORTS, 0));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.CohortCapacity(257, -1));
        assertThrows(IllegalArgumentException.class, () -> new RestoreProblem.CohortCapacity(257, 258));
        // the bounds themselves are accepted, so every value the store reports still builds
        new RestoreProblem.ExportBehindCheckpoint(0, 1);
        new RestoreProblem.ExportBehindStore(0, 1);
        // a negative export cutoff is host input the store has always reported with the same text
        assertEquals("exportCutoff -1 is below the checkpoint's cutoff 0",
                new RestoreProblem.ExportBehindCheckpoint(-1, 0).message());
        assertEquals("exportCutoff -1 is below processedCutoff 0: the rebuilt state would forget examined observations",
                new RestoreProblem.ExportBehindStore(-1, 0).message());
        new RestoreProblem.CohortBoundAfterCheckpoint(KEY, "exp", 0, 0);
        new RestoreProblem.CohortCapacity(RoutingStateStore.MAX_COHORTS + 1, RoutingStateStore.MAX_COHORTS + 1);
    }
}
