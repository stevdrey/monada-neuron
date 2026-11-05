package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.GridSelection.Selection;
import monada.neuron.evaluation.bilingual.SenseArm.Prediction;
import monada.neuron.evaluation.bilingual.SenseMetrics.Labels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenseGatesTest {

    private final SenseGates gates = new SenseGates();

    @Test
    void thresholdsAreInclusiveAtTheirRegisteredValues() {
        assertTrue(gates.text(80, 40, 60));
        assertFalse(gates.text(79, 40, 60));
        assertTrue(gates.minimumPerSense(30));
        assertFalse(gates.minimumPerSense(29));
        assertTrue(gates.trainingWeight(10.0));
        assertFalse(gates.trainingWeight(9.5));
        assertTrue(gates.validationPerLemma(10));
        assertTrue(gates.validationPerDirection(60));
        assertFalse(gates.validationPerDirection(59));
        assertTrue(gates.minimumPerDirection(4));
        assertFalse(gates.minimumPerDirection(3));
        assertTrue(gates.abstentionItems(30));
        assertTrue(gates.lowFrequency(19.0, 100.0));
        assertFalse(gates.lowFrequency(20.0, 100.0));
        assertTrue(gates.unlabeled(2, 100));
        assertFalse(gates.unlabeled(3, 100));
    }

    @Test
    void agreementDropsAnUndefinedAlpha() {
        assertTrue(gates.agreement(OptionalDouble.of(0.67)));
        assertFalse(gates.agreement(OptionalDouble.of(0.669)));
        assertFalse(gates.agreement(OptionalDouble.empty()));
    }

    @Test
    void projectionUsesTheCappedHeldOutCountAndTheWilsonUpperBound() {
        // p = 0.2 over n = 20: Wilson 80% upper bound.
        double z = SenseGates.Z80;
        double p = 0.2;
        int n = 20;
        double expected = (p + z * z / (2 * n) + z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)))
                / (1 + z * z / n);
        assertEquals(expected, gates.wilsonUpper80(4.0, 20).getAsDouble(), 1e-15);
        assertTrue(gates.wilsonUpper80(4.0, 0).isEmpty());
        // The bound is about 0.335: 120 x 0.335 = 40.2 >= 30 passes; the cap applies to 500 families as to 120.
        assertTrue(gates.projection(500, 4.0, 20));
        assertEquals(gates.projection(500, 4.0, 20), gates.projection(120, 4.0, 20));
        assertFalse(gates.projection(80, 4.0, 20), "80 x 0.335 = 26.8 < 30");
        assertFalse(gates.projection(500, 0.0, 0), "no pilot ANS item: no defined share");
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
