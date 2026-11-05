package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Check;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.DirectionResult;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Outcome;
import monada.neuron.evaluation.bilingual.SenseDataset.CommittedHashes;
import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
