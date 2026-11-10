package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.GridSelection.Selection;
import monada.neuron.evaluation.bilingual.SenseArm.Prediction;
import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import monada.neuron.evaluation.bilingual.SenseDataset.ScoredSet;
import monada.neuron.evaluation.bilingual.SenseMetrics.Labels;
import monada.neuron.evaluation.bilingual.SensePartitions.Split;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenseGatesTest {

    private final SenseGates gates = new SenseGates();

    @Test
    void thresholdsAreInclusiveAtTheirRegisteredValues() {
        assertTrue(gates.minimumPerSense(30));
        assertFalse(gates.minimumPerSense(29));
        assertTrue(gates.trainingWeight(60, 6));
        assertFalse(gates.trainingWeight(59, 6));
        assertTrue(gates.validationPerLemma(10));
        assertTrue(gates.validationPerDirection(60));
        assertFalse(gates.validationPerDirection(59));
        assertTrue(gates.minimumPerDirection(4));
        assertFalse(gates.minimumPerDirection(3));
        assertTrue(gates.abstentionItems(30));
        assertTrue(gates.lowFrequency(19, 100));
        assertFalse(gates.lowFrequency(20, 100));
    }

    /** Four three-cluster lemmas: {@code thirds} items of gold {0,1,2} plus 4 singletons per cluster in training. */
    private ScoredSet scoredSetWithThirds(int thirds) {
        return scoredSetWithThirds(thirds, Set.of(), Set.of());
    }

    private ScoredSet scoredSetWithThirds(int thirds, Set<String> dropped, Set<String> removedClusters) {
        var training = new ArrayList<DatasetItem>();
        var trainingGold = new ArrayList<SenseGold>();
        var validation = new ArrayList<DatasetItem>();
        var validationGold = new ArrayList<SenseGold>();
        var heldOut = new ArrayList<DatasetItem>();
        var heldOutGold = new ArrayList<SenseGold>();
        for (String lemma : List.of("a", "b", "c", "d")) {
            var inventory = new SenseInventory(Direction.EN_ES, lemma, List.of(List.of("x"), List.of("y"), List.of("z")));
            var input = new SenseItem(Direction.EN_ES, lemma, inventory, lemma + " word", 0, 1);
            int family = 0;
            for (int i = 0; i < thirds; i++) {
                training.add(new DatasetItem(lemma + family, lemma + family++, Split.TRAINING, input, "s", null, false));
                trainingGold.add(SenseGold.answerable(0b111));
            }
            for (int cluster = 0; cluster < 3; cluster++) {
                for (int i = 0; i < 4; i++) {
                    training.add(new DatasetItem(lemma + family, lemma + family++, Split.TRAINING, input, "s", null,
                            false));
                    trainingGold.add(SenseGold.answerable(1 << cluster));
                }
                for (int i = 0; i < 30; i++) {
                    heldOut.add(new DatasetItem(lemma + family, lemma + family++, Split.HELD_OUT, input, "s", null,
                            false));
                    heldOutGold.add(SenseGold.answerable(1 << cluster));
                }
            }
            for (int i = 0; i < 20; i++) {
                validation.add(new DatasetItem(lemma + family, lemma + family++, Split.VALIDATION, input, "s", null,
                        false));
                validationGold.add(SenseGold.answerable(1));
            }
        }
        return gates.scoredSet(training, trainingGold, validation, validationGold, heldOut, heldOutGold, dropped,
                removedClusters);
    }

    @Test
    void trainingWeightGateIsExactAtTenWithFractionalGold() {
        // 18 thirds + 4 is exactly 10 per cluster; as doubles it accumulates to 9.999999999999998.
        ScoredSet exact = scoredSetWithThirds(18);
        assertEquals(List.of(0, 1, 2), exact.clusters().get("EN>ES|a"));
        assertEquals(4, exact.clusters().size());
        assertTrue(exact.testableDirections().contains(Direction.EN_ES));
        // 17 thirds + 4 is 9.67: no cluster is scored.
        assertTrue(scoredSetWithThirds(17).clusters().isEmpty());
    }

    @Test
    void aClusterRemovedByTheProjectionGateIsNeverScored() {
        ScoredSet scored = scoredSetWithThirds(18, Set.of(), Set.of("EN>ES|a#2"));
        assertEquals(List.of(0, 1), scored.clusters().get("EN>ES|a"));
        assertEquals(List.of(0, 1, 2), scored.clusters().get("EN>ES|b"));
        assertEquals(Set.of("EN>ES|a#2"), scored.removedClusters());
    }

    @Test
    void aLemmaDroppedOfflineIsNeverScored() {
        ScoredSet scored = scoredSetWithThirds(18, Set.of("EN>ES|a"), Set.of());
        assertEquals(Set.of("EN>ES|b", "EN>ES|c", "EN>ES|d"), scored.clusters().keySet());
        assertEquals(Set.of("EN>ES|a"), scored.droppedLemmas());
    }

    @Test
    void selectionPrefersLeastRiskThenHigherCoverageThenGridOrder() {
        var arm = new FixedArm();
        var items = List.of(
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x a", 0, 1),
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x b", 0, 1),
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x c", 0, 1),
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x d", 0, 1));
        // Items 0-2 are ANS {0}; item 3 is UNA. The arm answers 0 with s = 0.9, 0.8, 0.3, 0.5.
        var labels = new Labels(new int[4], new byte[] {0, 0, 0, SenseMetrics.UNA}, new int[] {1, 1, 1, 0});
        Selection selected = new GridSelection().select(arm, items, labels);
        // tau 0: coverage 1, risk 1/4. tau 0.4: coverage 2/3 < 0.7. tau 0.2 = tau 0: tie -> grid order (tau 0).
        assertEquals(0, selected.threshold());
        assertEquals(0.25, selected.risk());
        assertTrue(selected.reachedCoverage());
    }

    @Test
    void selectionBreaksARiskTieTowardHigherCoverage() {
        var arm = new TwoConfigurationArm();
        var items = List.of(
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x a", 0, 1),
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x b", 0, 1),
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x c", 0, 1),
                new SenseItem(Direction.EN_ES, "x", FixedArm.INVENTORY, "x d", 0, 1));
        // Every item is ANS {0} and answered correctly: risk 0 everywhere. Configuration 0 covers 3/4 at tau 0.5,
        // configuration 1 covers 4/4, so the later configuration wins on coverage.
        var labels = new Labels(new int[4], new byte[4], new int[] {1, 1, 1, 1});
        Selection selected = new GridSelection().select(arm, items, labels);
        assertEquals(1, selected.configuration());
        assertEquals(1.0, selected.coverage());
        assertEquals(0.0, selected.risk());
    }

    /** Two configurations: the first gives item d a low confidence, the second does not. */
    private static final class TwoConfigurationArm implements SenseArm {

        @Override
        public String name() {
            return "T";
        }

        @Override
        public int configurations() {
            return 2;
        }

        @Override
        public String describe(int configuration) {
            return "c" + configuration;
        }

        @Override
        public double[] thresholds() {
            return new double[] {0.5};
        }

        @Override
        public void fit(List<TrainingExample> training) {
        }

        @Override
        public Prediction predict(SenseItem item, int configuration) {
            return new Prediction(0, configuration == 0 && item.sentence().equals("x d") ? 0.1 : 0.9);
        }

        @Override
        public long retainedBytes() {
            return 0;
        }
    }

    /** One configuration whose confidences are fixed per sentence. */
    private static final class FixedArm implements SenseArm {

        static final SenseInventory INVENTORY = new SenseInventory(Direction.EN_ES, "x",
                List.of(List.of("a"), List.of("b")));

        @Override
        public String name() {
            return "F";
        }

        @Override
        public int configurations() {
            return 1;
        }

        @Override
        public String describe(int configuration) {
            return "fixed";
        }

        @Override
        public double[] thresholds() {
            return new double[] {0.0, 0.2, 0.4};
        }

        @Override
        public void fit(List<TrainingExample> training) {
        }

        @Override
        public Prediction predict(SenseItem item, int configuration) {
            return new Prediction(0, switch (item.sentence()) {
                case "x a" -> 0.9;
                case "x b" -> 0.8;
                case "x c" -> 0.3;
                default -> 0.5;
            });
        }

        @Override
        public long retainedBytes() {
            return 0;
        }
    }
}
