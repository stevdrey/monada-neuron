package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Check;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.DirectionResult;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Outcome;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Verdict;
import monada.neuron.evaluation.bilingual.SenseDataset.CommittedHashes;
import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilingualSenseEvaluationTest {

    private static final int RESAMPLES = 100;

    private final SyntheticSenseWorkload workload = new SyntheticSenseWorkload(SyntheticSenseWorkload.SEED);

    private Map<String, Check> checks(Outcome outcome) {
        return outcome.checks().stream().collect(Collectors.toMap(Check::name, check -> check));
    }

    @Test
    void syntheticSelfTestPassesEveryCheckButTheUnmeasuredBudget() {
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, false).run(workload::generate);
        Map<String, Check> checks = checks(outcome);
        assertEquals(Set.of("leakage.input-surface", "leakage.family-disjoint", "leakage.near-duplicate",
                "leakage.inventory-hash", "nc.heldout-counterfactual", "unseen-lemma.abstain", "nc.context-mask",
                "n.policy-equivalence", "leakage.test-labels", "leakage.sealed-hash", "metrics.fixture",
                "gates.step7-consistency", "nc.label-permutation", "budget", "determinism"), checks.keySet());
        checks.values().stream().filter(check -> !check.name().equals("budget"))
                .forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
        assertFalse(checks.get("budget").passed(), "an unmeasured inference cost never meets its budget");
        assertTrue(checks.get("budget").detail().contains("inference not measured"));
        for (DirectionResult direction : outcome.directions()) {
            assertEquals(Verdict.UNTRUSTWORTHY, direction.verdict());
            assertTrue(direction.verdictLabel().contains("synthetic"), "the label status is carried");
            assertEquals(4, direction.arms().size());
        }
        assertEquals("omitted: optional under the protocol; no wave-token result is produced",
                outcome.metadata().get("armW"));
    }

    @Test
    void readingHeldOutGoldBeforeScoringFailsTheLeakCheck() {
        SenseDataset dataset = workload.generate();
        dataset.heldOutGold().get(0);
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, false).once(dataset);
        Check leak = checks(outcome).get("leakage.test-labels");
        assertFalse(leak.passed());
        assertTrue(leak.detail().startsWith("1 held-out gold reads"));
    }

    private SenseDataset withHashes(SenseDataset base, CommittedHashes hashes) {
        return new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), base.scored(), hashes);
    }

    @Test
    void aHeldOutGoldHashMismatchFailsTheSealedCheck() {
        SenseDataset base = workload.generate();
        CommittedHashes hashes = base.hashes();
        Check sealed = checks(new BilingualSenseEvaluation(RESAMPLES, false).once(withHashes(base, new CommittedHashes(
                hashes.inventorySha256(), hashes.inventoryBytes(), hashes.guidelineSha256(), hashes.guidelineBytes(),
                "0".repeat(64), hashes.sealedLogSha256(), hashes.sealedLogBytes())))).get("leakage.sealed-hash");
        assertFalse(sealed.passed());
    }

    @Test
    void aSealedLogHashMismatchFailsTheSealedCheck() {
        SenseDataset base = workload.generate();
        CommittedHashes hashes = base.hashes();
        Check sealed = checks(new BilingualSenseEvaluation(RESAMPLES, false).once(withHashes(base, new CommittedHashes(
                hashes.inventorySha256(), hashes.inventoryBytes(), hashes.guidelineSha256(), hashes.guidelineBytes(),
                hashes.heldOutGoldSha256(), "0".repeat(64), hashes.sealedLogBytes())))).get("leakage.sealed-hash");
        assertFalse(sealed.passed());
    }

    @Test
    void aFailedPreflightCheckRunsNoSystemAndLeavesTheGoldSealed() {
        SenseDataset base = workload.generate();
        CommittedHashes hashes = base.hashes();
        SenseDataset tampered = withHashes(base, new CommittedHashes(hashes.inventorySha256(), hashes.inventoryBytes(),
                "0".repeat(64), hashes.guidelineBytes(), hashes.heldOutGoldSha256(), hashes.sealedLogSha256(),
                hashes.sealedLogBytes()));
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, false).once(tampered);
        Map<String, Check> checks = checks(outcome);
        assertFalse(checks.get("leakage.inventory-hash").passed(), "the guideline hash differs");
        assertFalse(checks.containsKey("n.policy-equivalence"), "no arm was fitted");
        assertFalse(tampered.heldOutGold().isOpen());
        assertEquals(0, tampered.heldOutGold().earlyReads());
        for (DirectionResult direction : outcome.directions()) {
            assertEquals(Verdict.UNTRUSTWORTHY, direction.verdict());
            assertTrue(direction.reason().startsWith("a preflight check failed"), direction.reason());
            assertTrue(direction.arms().isEmpty());
        }
    }

    @Test
    void aStaleScoredSetFailsTheStepSevenConsistencyCheck() {
        SenseDataset base = workload.generate();
        var clusters = new LinkedHashMap<>(base.scored().clusters());
        clusters.remove(clusters.keySet().iterator().next());
        var stale = new SenseDataset.ScoredSet(clusters, base.scored().testableDirections(),
                base.scored().abstentionTestable(), Set.of(), Set.of());
        var dataset = new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), stale, base.hashes());
        assertFalse(checks(new BilingualSenseEvaluation(RESAMPLES, false).once(dataset))
                .get("gates.step7-consistency").passed());
    }

    @Test
    void goldAndScoredClustersOutsideTheInventoryAreRejected() {
        SenseDataset base = workload.generate();
        var gold = new ArrayList<>(base.validationGold());
        int ans = 0;
        while (!(gold.get(ans) instanceof SenseGold.Answerable)) {
            ans++;
        }
        gold.set(ans, SenseGold.answerable(1 << 30));
        assertThrows(IllegalArgumentException.class, () -> new SenseDataset(base.provenance(), base.labelStatus(),
                base.training(), base.trainingGold(), base.validation(), gold, base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), base.scored(), base.hashes()));
        var clusters = new LinkedHashMap<>(base.scored().clusters());
        clusters.replaceAll((lemma, scored) -> List.of(30));
        var outside = new SenseDataset.ScoredSet(clusters, base.scored().testableDirections(),
                base.scored().abstentionTestable(), Set.of(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> new SenseDataset(base.provenance(), base.labelStatus(),
                base.training(), base.trainingGold(), base.validation(), base.validationGold(), base.heldOut(),
                base.heldOutGold(), base.calibrationFamilies(), base.pilotFamilies(), outside, base.hashes()));
        var heldOut = new ArrayList<SenseGold>();
        for (int i = 0; i < base.heldOut().size(); i++) {
            heldOut.add(SenseGold.answerable(1 << 30));
        }
        assertThrows(IllegalArgumentException.class, () -> base.withHeldOutGold(new GuardedGold(heldOut, true)));
    }

    @Test
    void anArmOverBudgetIsReportedWithoutWithholdingAndNOverBudgetIsNoGo() {
        var evaluation = new BilingualSenseEvaluation(RESAMPLES, true);
        var overBudget = new BilingualSenseEvaluation.Cost(0, 0, BilingualSenseEvaluation.INFERENCE_BUDGET_NANOS + 1,
                0, 0.0, 0);
        var arm = new BilingualSenseEvaluation.ArmResult("B", null, "", null, null, null, Map.of(), Map.of(), List.of(),
                overBudget);
        Check budget = evaluation.budget(List.of(new DirectionResult(Direction.EN_ES, Verdict.NO_GO, "s", "r",
                List.of(arm), Map.of(), Map.of(), OptionalDouble.empty(), List.of())));
        assertTrue(budget.passed(), budget.detail());
        assertTrue(budget.detail().contains("over budget: [EN>ES B]"), budget.detail());
        assertEquals(Verdict.NO_GO, evaluation.verdict(true, true, false));
        assertEquals(Verdict.EXPLORATORY_GO, evaluation.verdict(true, true, true));
        assertEquals(Verdict.NOT_TESTABLE, evaluation.verdict(false, true, true));
    }

    @Test
    void everyLemmaSenseAndStratumReportsTheBundleWithIntervals() {
        DirectionResult direction = new BilingualSenseEvaluation(RESAMPLES, false).once(workload.generate())
                .directions().getFirst();
        var groups = direction.arms().getLast().groups();
        assertTrue(groups.containsKey("direction"));
        assertTrue(groups.containsKey("lemma EN>ES|bank"));
        assertTrue(groups.containsKey("sense EN>ES|bank#0"));
        assertTrue(groups.containsKey("stratum UNA"));
        var lemma = groups.get("lemma EN>ES|bank");
        assertEquals(120, lemma.items());
        assertEquals(new HashSet<>(SenseMetrics.BUNDLE), lemma.metrics().keySet());
        assertTrue(lemma.metrics().get("coverage").point().isPresent());
        assertFalse(Double.isNaN(lemma.metrics().get("coverage").interval().lower()));
        assertFalse(direction.arms().getLast().riskCoverage().isEmpty());
        // A lemma holds a fifth of the direction's items, so its own bootstrap interval must be wider.
        var whole = groups.get("direction").metrics().get("coverage").interval();
        var part = lemma.metrics().get("coverage").interval();
        assertTrue(part.upper() - part.lower() > whole.upper() - whole.lower());
    }

    @Test
    void aGroupSampleKeepsOnlyItsItemsWithTheirMultiplicity() {
        int[] sample = new BilingualSenseEvaluation(RESAMPLES, false)
                .groupSample(new int[] {2, 0, 0, 1, 2}, new boolean[] {true, false, true});
        assertArrayEquals(new int[] {2, 0, 0, 2}, sample);
    }

    @Test
    void jsonReportCarriesReasonNotesGroupsCurveAndCost() {
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, false).once(workload.generate());
        String json = new BilingualSenseRunner().resultsJson(outcome);
        for (String field : List.of("\"reason\"", "\"macroConstant\"", "\"notes\"", "\"groups\"", "\"lemma EN>ES|bank\"",
                "\"riskCoverage\"", "\"cost\"", "\"withinBudget\"")) {
            assertTrue(json.contains(field), field);
        }
    }

    @Test
    void aFamilyInTwoSplitsOrAPilotFamilyFailsTheDisjointCheck() {
        SenseDataset base = workload.generate();
        var validation = new ArrayList<>(base.validation());
        DatasetItem first = validation.getFirst();
        String trainingFamily = base.training().getFirst().familyId();
        validation.set(0, new DatasetItem(first.occurrenceId(), trainingFamily, first.split(), first.input(),
                first.source(), first.originalSentence(), first.translationDivergent()));
        var leaked = new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                validation, base.validationGold(), base.heldOut(), base.heldOutGold(), base.calibrationFamilies(),
                Set.of(base.heldOut().getFirst().familyId()), base.scored(), base.hashes());
        Check check = checks(new BilingualSenseEvaluation(RESAMPLES, false).once(leaked))
                .get("leakage.family-disjoint");
        assertFalse(check.passed());
        assertTrue(check.detail().startsWith("2 violations"), check.detail());
    }

    @Test
    void aNearDuplicateAcrossSplitsFailsTheNearDuplicateCheck() {
        SenseDataset base = workload.generate();
        var heldOut = new ArrayList<>(base.heldOut());
        DatasetItem training = base.training().getFirst();
        DatasetItem target = heldOut.getFirst();
        // Same masked sequence as a training item, in another family and split.
        heldOut.set(0, new DatasetItem(target.occurrenceId(), target.familyId(), target.split(), training.input(),
                target.source(), target.originalSentence(), target.translationDivergent()));
        var leaked = new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), heldOut, base.heldOutGold(), base.calibrationFamilies(),
                base.pilotFamilies(), base.scored(), base.hashes());
        assertFalse(checks(new BilingualSenseEvaluation(RESAMPLES, false).once(leaked))
                .get("leakage.near-duplicate").passed());
    }

    @Test
    void systemInputCarriesOnlyTheRegisteredFields() {
        var names = Arrays.stream(SenseItem.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("direction", "lemma", "inventory", "sentence", "start", "end"), names);
        assertEquals(SenseItem.ALLOWED_COMPONENTS, Set.copyOf(names));
    }

    @Test
    void aDirectionThatFailedStepSevenIsNotTestable() {
        SenseDataset base = workload.generate();
        var scored = new SenseDataset.ScoredSet(base.scored().clusters(), Set.of(Direction.EN_ES),
                base.scored().abstentionTestable(), Set.of(), Set.of());
        var stopped = new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
        DirectionResult esEn = new BilingualSenseEvaluation(RESAMPLES, false).once(stopped).directions().get(1);
        assertEquals(Direction.ES_EN, esEn.direction());
        assertEquals(Verdict.NOT_TESTABLE, esEn.verdict());
        assertTrue(esEn.arms().isEmpty());
    }

    /** Supplies {@code first}, then {@code second}, one per pass of a run. */
    private Supplier<SenseDataset> passes(SenseDataset first, SenseDataset second) {
        var datasets = new ArrayDeque<>(List.of(first, second));
        return datasets::removeFirst;
    }

    @Test
    void aFailedDeterminismCheckWithholdsTheVerdicts() {
        var other = new SyntheticSenseWorkload(SyntheticSenseWorkload.SEED + 1);
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, true)
                .run(passes(workload.generate(), other.generate()));
        Map<String, Check> checks = checks(outcome);
        assertFalse(checks.get("determinism").passed());
        checks.values().stream().filter(check -> !check.name().equals("determinism"))
                .forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
        for (DirectionResult direction : outcome.directions()) {
            assertEquals(Verdict.UNTRUSTWORTHY, direction.verdict());
            assertEquals("a check failed; no verdict is recorded", direction.reason());
        }
    }

    @Test
    void aFailedDeterminismCheckLeavesNotTestableInPlace() {
        var other = new SyntheticSenseWorkload(SyntheticSenseWorkload.SEED + 1);
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, true)
                .run(passes(withoutAbstention(workload.generate()), withoutAbstention(other.generate())));
        assertFalse(checks(outcome).get("determinism").passed());
        for (DirectionResult direction : outcome.directions()) {
            assertEquals(Verdict.NOT_TESTABLE, direction.verdict());
        }
    }

    @Test
    void aCheckThatFailsOnlyInTheSecondPassFailsDeterminism() {
        SenseDataset second = workload.generate();
        second.heldOutGold().get(0);
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, true).run(passes(workload.generate(), second));
        Map<String, Check> checks = checks(outcome);
        assertTrue(checks.get("leakage.test-labels").passed(), "the first pass read nothing early");
        assertFalse(checks.get("determinism").passed());
        assertTrue(checks.get("determinism").detail().contains("leakage.test-labels"), checks.get("determinism").detail());
        for (DirectionResult direction : outcome.directions()) {
            assertEquals(Verdict.UNTRUSTWORTHY, direction.verdict());
        }
    }

    @Test
    void permutationBoundFailsOnlyAboveZeroOrWhenUndefined() {
        var evaluation = new BilingualSenseEvaluation(RESAMPLES, false);
        assertFalse(evaluation.permutationFails(-0.1));
        assertFalse(evaluation.permutationFails(0.0));
        assertTrue(evaluation.permutationFails(0.001));
        assertTrue(evaluation.permutationFails(Double.NaN));
    }

    private SenseDataset withoutAbstention(SenseDataset base) {
        var scored = new SenseDataset.ScoredSet(base.scored().clusters(), base.scored().testableDirections(), Set.of(),
                Set.of(), Set.of());
        return new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
    }

    @Test
    void untestableH2StaysNotTestableWhenACheckFails() {
        SenseDataset base = workload.generate();
        var scored = new SenseDataset.ScoredSet(base.scored().clusters(), base.scored().testableDirections(), Set.of(),
                Set.of(), Set.of());
        var noAbstention = new SenseDataset(base.provenance(), base.labelStatus(), base.training(),
                base.trainingGold(), base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
        // measureCost = false fails the budget check; H2's NOT TESTABLE still takes precedence.
        for (DirectionResult direction : new BilingualSenseEvaluation(RESAMPLES, false).once(noAbstention)
                .directions()) {
            assertEquals(Verdict.NOT_TESTABLE, direction.verdict());
        }
    }

    /** Returns the dataset with only {@code testable} directions passing step 7. */
    private SenseDataset withTestable(SenseDataset base, Set<Direction> testable) {
        var scored = new SenseDataset.ScoredSet(base.scored().clusters(), testable, base.scored().abstentionTestable(),
                Set.of(), Set.of());
        return new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
    }

    @Test
    void aGlobalStopRunsNoSystemAndLeavesTheGoldSealed() {
        SenseDataset stopped = withTestable(workload.generate(), Set.of());
        Outcome outcome = new BilingualSenseEvaluation(RESAMPLES, false).once(stopped);
        assertFalse(stopped.heldOutGold().isOpen());
        assertEquals(0, stopped.heldOutGold().earlyReads());
        assertEquals("no system was run; data no-go", outcome.metadata().get("globalStop"));
        assertFalse(checks(outcome).containsKey("n.policy-equivalence"), "no arm was fitted");
        for (DirectionResult direction : outcome.directions()) {
            assertEquals(Verdict.NOT_TESTABLE, direction.verdict());
            assertTrue(direction.reason().startsWith("global stop"), direction.reason());
            assertTrue(direction.arms().isEmpty());
        }
    }

    @Test
    void aStoppedDirectionIsNeverAnswered() {
        SenseDataset dataset = withTestable(workload.generate(), Set.of(Direction.EN_ES));
        var pipeline = new BilingualSenseEvaluation(RESAMPLES, false).pipeline(dataset, List.of("A"));
        assertEquals(Set.of(Direction.EN_ES), pipeline.heldOut().keySet());
        assertEquals(Set.of(Direction.EN_ES), pipeline.selections().keySet());
        var outputs = pipeline.heldOut().get(Direction.EN_ES)[0];
        int stopped = 0;
        for (int i = 0; i < dataset.heldOut().size(); i++) {
            if (dataset.heldOut().get(i).input().direction() == Direction.ES_EN) {
                stopped++;
                assertEquals(-1, outputs.choice()[i], "A would choose a cluster for any item it was given");
                assertFalse(outputs.answered()[i]);
            }
        }
        assertTrue(stopped > 0);
        assertEquals(0, dataset.heldOutGold().earlyReads());
        var majority = (MajoritySenseArm) pipeline.arms().getFirst();
        assertEquals(-1, majority.majorityCluster("ES>EN|banco"), "a stopped direction is not fitted");
        assertTrue(majority.majorityCluster("EN>ES|bank") >= 0);
    }

    @Test
    void neuronChecksCoverOnlyTheDirectionsThatPassedStepSeven() {
        SenseDataset dataset = withTestable(workload.generate(), Set.of(Direction.EN_ES));
        long validation = dataset.validation().stream().filter(item -> item.input().hasInventory()
                && item.input().direction() == Direction.EN_ES).count();
        long heldOut = dataset.heldOut().stream().filter(item -> item.input().hasInventory()
                && item.input().direction() == Direction.EN_ES).count();
        Map<String, Check> checks = checks(new BilingualSenseEvaluation(RESAMPLES, false).once(dataset));
        String masked = checks.get("nc.context-mask").detail();
        assertTrue(masked.startsWith((validation + heldOut) * 9 + " masked"), masked);
        String policy = checks.get("n.policy-equivalence").detail();
        assertTrue(policy.startsWith(validation * 9 + " (validation item"), policy);
    }

    @Test
    void syntheticGenerationIsDeterministicAndPassesTheGates() {
        SenseDataset first = workload.generate();
        SenseDataset second = workload.generate();
        assertEquals(first.hashes().heldOutGoldSha256(), second.hashes().heldOutGoldSha256());
        assertEquals(first.training(), second.training());
        assertEquals(Set.of(Direction.EN_ES, Direction.ES_EN), first.scored().testableDirections());
        assertEquals(Set.of(Direction.EN_ES, Direction.ES_EN), first.scored().abstentionTestable());
    }
}
