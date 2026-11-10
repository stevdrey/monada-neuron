package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Check;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.DirectionResult;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Outcome;
import monada.neuron.evaluation.bilingual.SenseDataset.CommittedHashes;
import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "nc.label-permutation", "budget", "determinism"), checks.keySet());
        checks.values().stream().filter(check -> !check.name().equals("budget"))
                .forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
        assertFalse(checks.get("budget").passed(), "an unmeasured inference cost never meets its budget");
        assertTrue(checks.get("budget").detail().contains("inference not measured"));
        for (DirectionResult direction : outcome.directions()) {
            assertTrue(direction.verdict().startsWith("UNTRUSTWORTHY"), direction.verdict());
            assertTrue(direction.verdict().contains("synthetic"), "the label status is carried");
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

    @Test
    void aHeldOutGoldHashMismatchFailsTheSealedCheck() {
        SenseDataset base = workload.generate();
        CommittedHashes hashes = base.hashes();
        var tampered = new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), base.scored(),
                new CommittedHashes(hashes.inventorySha256(), hashes.inventoryBytes(), "0".repeat(64),
                        hashes.guidelineBytes(), "0".repeat(64)));
        Map<String, Check> checks = checks(new BilingualSenseEvaluation(RESAMPLES, false).once(tampered));
        assertFalse(checks.get("leakage.sealed-hash").passed());
        assertFalse(checks.get("leakage.inventory-hash").passed(), "the guideline hash differs");
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
                base.scored().abstentionTestable());
        var stopped = new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
        DirectionResult esEn = new BilingualSenseEvaluation(RESAMPLES, false).once(stopped).directions().get(1);
        assertEquals(Direction.ES_EN, esEn.direction());
        assertTrue(esEn.verdict().startsWith("NOT TESTABLE"));
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
            assertTrue(direction.verdict().startsWith("UNTRUSTWORTHY"), direction.verdict());
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
            assertTrue(direction.verdict().startsWith("NOT TESTABLE"), direction.verdict());
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
            assertTrue(direction.verdict().startsWith("UNTRUSTWORTHY"), direction.verdict());
        }
    }

    @Test
    void anUndefinedPermutationBoundFailsTheCheck() {
        SenseDataset base = workload.generate();
        // Cluster 30 is in no gold set, so Macro is undefined in every resample.
        var clusters = new LinkedHashMap<String, List<Integer>>();
        base.scored().clusters().keySet().forEach(lemma -> clusters.put(lemma, List.of(30)));
        var scored = new SenseDataset.ScoredSet(clusters, base.scored().testableDirections(),
                base.scored().abstentionTestable());
        var undefined = new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
        Check check = checks(new BilingualSenseEvaluation(RESAMPLES, false).once(undefined))
                .get("nc.label-permutation");
        assertFalse(check.passed(), check.detail());
        assertTrue(check.detail().contains("n/a not <= 0 (FAIL)"), check.detail());
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
        var scored = new SenseDataset.ScoredSet(base.scored().clusters(), base.scored().testableDirections(), Set.of());
        return new SenseDataset(base.provenance(), base.labelStatus(), base.training(), base.trainingGold(),
                base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
    }

    @Test
    void untestableH2StaysNotTestableWhenACheckFails() {
        SenseDataset base = workload.generate();
        var scored = new SenseDataset.ScoredSet(base.scored().clusters(), base.scored().testableDirections(), Set.of());
        var noAbstention = new SenseDataset(base.provenance(), base.labelStatus(), base.training(),
                base.trainingGold(), base.validation(), base.validationGold(), base.heldOut(), base.heldOutGold(),
                base.calibrationFamilies(), base.pilotFamilies(), scored, base.hashes());
        // measureCost = false fails the budget check; H2's NOT TESTABLE still takes precedence.
        for (DirectionResult direction : new BilingualSenseEvaluation(RESAMPLES, false).once(noAbstention)
                .directions()) {
            assertTrue(direction.verdict().startsWith("NOT TESTABLE"), direction.verdict());
        }
    }

    /** Returns the dataset with only {@code testable} directions passing step 7. */
    private SenseDataset withTestable(SenseDataset base, Set<Direction> testable) {
        var scored = new SenseDataset.ScoredSet(base.scored().clusters(), testable, base.scored().abstentionTestable());
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
            assertTrue(direction.verdict().startsWith("NOT TESTABLE"), direction.verdict());
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
