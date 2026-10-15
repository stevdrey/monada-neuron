package monada.neuron.evaluation.routing;

import com.monada.evaluation.execution.CostBasis;
import com.monada.evaluation.execution.CostReason;
import com.monada.evaluation.execution.CostToAcceptedOutcome;
import com.monada.evaluation.execution.CurrencyCost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the routing replay over a real, isolated temporary Resonance Store export. */
class StoreReplayEvaluationTest {

    private static RoutingReplayReport report;

    @BeforeAll
    static void runOnce() {
        report = RoutingStoreReplayRunner.run(true);
    }

    @Test
    void everySemanticCheckPasses() {
        var failures = report.checks().stream().filter(c -> !c.passed()).map(c -> c.name() + ": " + c.detail())
                .toList();
        assertEquals(List.of(), failures);
    }

    @Test
    void coversRequiredScenarios() {
        var names = report.checks().stream().map(c -> c.name()).toList();
        assertTrue(names.containsAll(List.of("store.temporary-store-cleanup", "store.import-complete-and-explained",
                "store.export-reproducible", "store.replay-deterministic", "store.derived-not-consumed-equals-static",
                "store.eligibility-violations-zero", "store.snapshot-watermark", "store.missing-counterfactual",
                "store.cost-from-store-tooling", "store.incremental-equals-rebuild")), names.toString());
    }

    @Test
    void semanticOutputIsStableAcrossRuns() {
        var again = RoutingStoreReplayRunner.run(true);
        assertAll(
                () -> assertEquals(report.checks(), again.checks()),
                () -> assertEquals(report.metadata().get("store.fixtureDigest"),
                        again.metadata().get("store.fixtureDigest")),
                () -> assertEquals(report.metadata().get("store.reportDigest"),
                        again.metadata().get("store.reportDigest")));
    }

    @Test
    void theExportHorizonIsCarriedIntoTheFinalAdaptiveState() {
        StoreReplaySource.Export export;
        try (StoreReplaySource source = StoreReplaySource.open()) {
            export = source.recordAndExport(StoreReplayEvaluation.TASKS);
        }
        long lastImported = export.imported().observations().stream()
                .mapToLong(o -> o.ledgerSequence()).max().orElseThrow();
        var adaptive = new TemporalReplay(export.fixture()).run(ReplayArm.ADAPTIVE);

        assertAll(
                () -> assertEquals(export.cutoff(), export.fixture().horizon()),
                // the unfinished execution appends events after the last finished observation
                () -> assertTrue(export.cutoff() > lastImported),
                () -> assertEquals(export.cutoff(), adaptive.state().orElseThrow().processedCutoff()));
    }

    @Test
    void theStoreCostIsMirroredWithoutChange() {
        var usd = Currency.getInstance("USD");
        var partial = new CostToAcceptedOutcome(CostToAcceptedOutcome.Status.PARTIAL,
                CostBasis.HYPOTHETICAL_API_EQUIVALENT,
                List.of(new CurrencyCost(usd, new BigDecimal("0.0100"), BigDecimal.ZERO)),
                List.of(new CostReason(CostReason.Code.MISSING_PRICE, Optional.empty(), Optional.of("stage"),
                        Optional.empty(), "no snapshot")));
        var mirrored = StoreReplaySource.replayCost(partial);

        assertAll(
                () -> assertEquals(ReplayCost.Status.PARTIAL, mirrored.status()),
                () -> assertEquals(0, mirrored.amounts().getFirst().amount().compareTo(new BigDecimal("0.0100"))),
                () -> assertEquals("USD", mirrored.amounts().getFirst().currency()),
                () -> assertEquals(List.of("MISSING_PRICE"), mirrored.reasons()));
    }
}
