package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.routing.RoutingReplayEvaluation.Check;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostScenarioEvaluationTest {

    private static HostScenarioEvaluation.Outcome outcome;

    @BeforeAll
    static void runOnce() {
        outcome = new HostScenarioEvaluation().run();
    }

    private static Check check(String name) {
        return outcome.checks().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no check " + name + " in " + names()));
    }

    private static List<String> names() {
        return outcome.checks().stream().map(Check::name).toList();
    }

    @Test
    void everyCheckPasses() {
        Map<String, String> failed = outcome.checks().stream().filter(c -> !c.passed())
                .collect(Collectors.toMap(Check::name, Check::detail));
        assertTrue(failed.isEmpty(), failed.toString());
    }

    @Test
    void aDispositionAloneEarnsNoCreditAndAnOverrideCreditsNoRoute() {
        assertTrue(check("host.disposition.no-credit-path").passed(), check("host.disposition.no-credit-path").detail());
        assertTrue(check("host.override.executed-route-only").passed(),
                check("host.override.executed-route-only").detail());
    }

    @Test
    void theCatalogChangeIsComparedAndTheRemovedRouteIsNeverSelected() {
        Check catalog = check("host.catalog-change.compared");

        assertTrue(catalog.passed(), catalog.detail());
        assertEquals("Compared", outcome.metadata().get("host.catalog-change.probe.comparison"));
        assertEquals("Eligible->NotInCatalog", outcome.metadata().get("host.catalog-change.probe.eligibility.sub-b@1"),
                outcome.metadata().toString());
    }

    @Test
    void everyArmReportsOnlyObservedDataAndTheReadinessGapsOfEveryOtherTask() {
        assertTrue(check("host.arms.observed-only").passed(), check("host.arms.observed-only").detail());
        for (ReplayArm arm : HostScenarioEvaluation.ARMS) {
            String prefix = "host.missing-features." + arm.benchmarkName() + ".readiness.missing.";
            for (String dimension : List.of("CATEGORY", "LANGUAGES", "CHANGE_SIZE")) {
                assertEquals("8", outcome.metadata().get(prefix + dimension), prefix + dimension);
            }
        }
    }

    @Test
    void futureAndForeignEvidenceNeverReachAnEarlierDecisionOrTheState() {
        Map<String, String> metadata = outcome.metadata();

        assertTrue(check("host.leakage.future-evidence").passed(), check("host.leakage.future-evidence").detail());
        assertTrue(check("host.leakage.foreign-evidence").passed(), check("host.leakage.foreign-evidence").detail());
        assertEquals("NoUpdate(BINDING_SCOPE_MISMATCH)", metadata.get("host.leakage.foreign.scope.derivation"));
        assertEquals("NoUpdate(BINDING_PARTITION_MISMATCH)",
                metadata.get("host.leakage.foreign.evaluation-policy.derivation"));
        assertEquals("NoUpdate(FEATURE_SCHEMA_MISMATCH)",
                metadata.get("host.leakage.foreign.feature-schema.derivation"));
        for (String name : List.of("scope", "evaluation-policy", "feature-schema")) {
            assertEquals("Unchanged", metadata.get("host.leakage.foreign." + name + ".update"), name);
        }
    }

    @Test
    void aRestartMatchesTheScratchReferenceOrReportsExactlyWhyItCannot() {
        Map<String, String> metadata = outcome.metadata();
        String probe = "host.restart.probe.";

        assertTrue(check("host.recovery.parity").passed(), check("host.recovery.parity").detail());
        assertTrue(check("host.recovery.unrecoverable-reported").passed(),
                check("host.recovery.unrecoverable-reported").detail());
        assertEquals("COMPATIBLE_REBUILT", metadata.get(probe + "post-checkpoint-correction.outcome"));
        assertEquals("true", metadata.get(probe + "post-checkpoint-correction.parity"));
        assertEquals("REBUILD_FAILED_SCRATCH_FALLBACK", metadata.get(probe + "pre-checkpoint-correction.outcome"));
        assertEquals("true", metadata.get(probe + "pre-checkpoint-correction.parity"));
        // the blocked rows of spec 0010, pinned against the real CheckpointRecovery output
        Map<String, List<String>> blocked = Map.of(
                "corrupt-bytes", List.of("[UNDECODABLE]", "OBTAIN_READABLE_CHECKPOINT"),
                "foreign-scope", List.of("[FOREIGN_SCOPE]", "FIX_HOST_CONFIGURATION"),
                "foreign-policy", List.of("[FOREIGN_EVALUATION_POLICY]", "FIX_HOST_CONFIGURATION"),
                "export-behind", List.of("[EXPORT_BEHIND_CHECKPOINT]", "OBTAIN_EXPORT"));
        blocked.forEach((caseId, expected) -> {
            assertEquals("BLOCKED", metadata.get(probe + caseId + ".outcome"), caseId);
            assertEquals(expected.get(0), metadata.get(probe + caseId + ".blockers"), caseId);
            assertTrue(metadata.get(probe + caseId + ".plan").startsWith("[" + expected.get(1)), caseId);
        });
    }

    @Test
    void twoFreshRunsAgreeOnEveryLineAndAnyDifferenceFailsTheCheck() {
        assertTrue(check("host.deterministic").passed(), check("host.deterministic").detail());

        List<Check> checks = outcome.checks();
        var changed = new TreeMap<>(outcome.metadata());
        changed.put("host.digest.recovery", "different");
        Check failed = HostScenarioEvaluation.deterministicCheck(checks, outcome.metadata(), checks, changed);
        assertFalse(failed.passed());
        assertTrue(failed.detail().contains("host.digest.recovery"), failed.detail());
        assertFalse(HostScenarioEvaluation.deterministicCheck(List.of(), Map.of("a", "1"), List.of(), Map.of("a", "1"))
                .passed(), "without a receipt chain or recovery digest the check is vacuous");
    }

    @Test
    void aCheckThatFlipsOnTheSecondPassFailsTheCheckEvenWithIdenticalMetadata() {
        List<Check> first = outcome.checks();
        var second = first.stream().map(c -> c.name().equals("bounds.respected")
                ? new Check(c.name(), !c.passed(), c.detail()) : c).toList();

        Check failed = HostScenarioEvaluation.deterministicCheck(first, outcome.metadata(), second, outcome.metadata());

        assertFalse(failed.passed());
        assertTrue(failed.detail().contains("bounds.respected"), failed.detail());
    }

    @Test
    void theStaticArmCreditsNothingAndAlternativesAreReportedInTheirOwnRow() {
        Map<String, String> metadata = outcome.metadata();
        String staticArm = "host.disposition." + ReplayArm.STATIC.benchmarkName() + ".";
        String adaptive = "host.disposition." + ReplayArm.ADAPTIVE.benchmarkName() + ".";

        assertEquals("0", metadata.get(staticArm + "credits.applied"));
        assertTrue(Integer.parseInt(metadata.get(adaptive + "credits.applied")) > 0);
        assertTrue(Integer.parseInt(metadata.get(adaptive + "alternative.total.coverage.OBSERVED")) > 0,
                metadata.toString());
        assertTrue(metadata.containsKey(adaptive + "advised.total.coverage.HOST_NOT_EXECUTED"), metadata.toString());
    }

    @Test
    void theNoCreditCheckFailsWhenNoAlternativeIsBoundOverAnAbstention() {
        var evaluation = new HostScenarioEvaluation();
        var fixtures = new HostScenarioFixtures();
        var script = new TreeMap<>(fixtures.dispositionScript());
        // tasks 5 and 21 are the qa alternatives, the only ones over an abstention
        script.put(5, HostAction.DEFER);
        script.put(21, HostAction.DEFER);

        Check check = evaluation.noCreditPathCheck(evaluation.run("disposition", fixtures.disposition(), script));

        assertFalse(check.passed());
        assertTrue(check.detail().contains("bound over an abstention and ran nothing: false"), check.detail());
    }

    @Test
    void theObservedOnlyCheckIsVacuousWithoutAnAlternativeExecution() {
        var evaluation = new HostScenarioEvaluation();
        var fixtures = new HostScenarioFixtures();

        Check check = evaluation.observedOnlyCheck(
                List.of(evaluation.run("cold-start", fixtures.coldStart(), Map.of())));

        assertFalse(check.passed());
        assertTrue(check.detail().contains("alternatives=0"), check.detail());
    }
}
