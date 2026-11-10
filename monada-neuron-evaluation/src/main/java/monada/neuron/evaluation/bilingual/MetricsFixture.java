package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Check;
import monada.neuron.evaluation.bilingual.SenseMetrics.Labels;
import monada.neuron.evaluation.bilingual.SenseMetrics.Outputs;
import monada.neuron.evaluation.bilingual.SenseMetrics.Sense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Check {@code metrics.fixture}: a small synthetic fixture whose metric values were computed by hand. The harness
 * must reproduce every value, including a gold flip that changes one item's correctness by a known amount and zero
 * denominators reported as {@code n/a}.
 *
 * <p>Items (lemma, gold, {@code k*}, {@code s}, answered); lemma 0 has 2 clusters, lemma 1 has 3; scored senses
 * {@code (0,0) (0,1) (1,0) (1,2)}:
 *
 * <pre>
 * i0  0  {0}    0  0.9  yes      i4  1  {2}    2  0.6  yes
 * i1  0  {0}    1  0.8  yes      i5  1  {0}    2  0.6  yes
 * i2  0  {1}    1  0.8  yes      i6  0  UNA    0  0.2  no
 * i3  0  {0,1}  0  0.4  no       i7  1  OOI    1  0.6  yes
 * </pre>
 *
 * <p>Hand values: forced accuracy 4/6; Macro (0.6 + 1 + 0 + 1) / 4 = 0.65; Macro_const (4/3 + 1) / 4 = 7/12; coverage
 * 5/6; selective accuracy 3/5; selection risk 3/6; abstention precision and recall 1/2 and 1/2 ({@code UNA}: 1/2 and
 * 1, {@code OOI}: 0 and 0); tie-aware AURC 1/12 + 3/16 + 3/56 + 1/16 = 65/168; AUROC (6 + 4) / 12 = 5/6; ECE (each of
 * the 6 {@code ANS} items alone in its equal-mass bin) 2.7 / 6 = 0.45; Brier 1.57 / 6. Flipping i5's gold to {2}
 * raises forced accuracy by exactly 1/6 to 5/6 and leaves sense (1,0) without weight, so Macro is the mean of the
 * three remaining senses, 2.6 / 3. With every item answered, abstention precision is {@code n/a}; with no item, forced
 * accuracy and AUROC are {@code n/a}.
 */
final class MetricsFixture {

    private static final double TOLERANCE = 1e-12;

    private final SenseMetrics metrics = new SenseMetrics();

    /** Returns the fixture's computed values, by name, alongside the hand-computed ones. */
    Map<String, double[]> comparisons() {
        var labels = new Labels(new int[] {0, 0, 0, 0, 1, 1, 0, 1},
                new byte[] {0, 0, 0, 0, 0, 0, SenseMetrics.UNA, SenseMetrics.OOI},
                new int[] {0b1, 0b1, 0b10, 0b11, 0b100, 0b1, 0, 0});
        var outputs = new Outputs(new int[] {0, 1, 1, 0, 2, 2, 0, 1},
                new double[] {0.9, 0.8, 0.8, 0.4, 0.6, 0.6, 0.2, 0.6},
                new boolean[] {true, true, true, false, true, true, false, true});
        List<Sense> senses = List.of(new Sense(0, 0), new Sense(0, 1), new Sense(1, 0), new Sense(1, 2));
        int[] all = metrics.all(8);
        int una = 1 << SenseMetrics.UNA;
        int ooi = 1 << SenseMetrics.OOI;
        var values = new LinkedHashMap<String, double[]>();
        put(values, "forcedAccuracy", metrics.forcedAccuracy(labels, outputs, all), 4.0 / 6);
        put(values, "macro", metrics.macro(labels, outputs, all, senses), 0.65);
        put(values, "macroConstant", metrics.macroConstant(labels, all, senses, new int[] {2, 3}), 7.0 / 12);
        put(values, "coverage", metrics.coverage(labels, outputs, all), 5.0 / 6);
        put(values, "selectiveAccuracy", metrics.selectiveAccuracy(labels, outputs, all), 3.0 / 5);
        put(values, "selectionRisk", OptionalDouble.of(metrics.selectionRisk(labels, outputs, all)), 0.5);
        put(values, "abstentionPrecision", metrics.abstentionPrecision(labels, outputs, all, una | ooi), 0.5);
        put(values, "abstentionRecall", metrics.abstentionRecall(labels, outputs, all, una | ooi), 0.5);
        put(values, "abstentionPrecisionUNA", metrics.abstentionPrecision(labels, outputs, all, una), 0.5);
        put(values, "abstentionRecallUNA", metrics.abstentionRecall(labels, outputs, all, una), 1.0);
        put(values, "abstentionPrecisionOOI", metrics.abstentionPrecision(labels, outputs, all, ooi), 0.0);
        put(values, "abstentionRecallOOI", metrics.abstentionRecall(labels, outputs, all, ooi), 0.0);
        put(values, "aurc", metrics.aurc(labels, outputs, all), 65.0 / 168);
        put(values, "auroc", metrics.auroc(labels, outputs, all), 5.0 / 6);
        put(values, "ece", metrics.ece(labels, outputs, all), 0.45);
        put(values, "brier", metrics.brier(labels, outputs, all), 1.57 / 6);

        var flipped = new Labels(labels.lemma(), labels.kind(), new int[] {0b1, 0b1, 0b10, 0b11, 0b100, 0b100, 0, 0});
        put(values, "flip.forcedAccuracy", metrics.forcedAccuracy(flipped, outputs, all), 5.0 / 6);
        put(values, "flip.macro", metrics.macro(flipped, outputs, all, senses), 2.6 / 3);

        var answeredAll = new Outputs(outputs.choice(), outputs.confidence(), new boolean[8]);
        Arrays.fill(answeredAll.answered(), true);
        put(values, "zero.abstentionPrecision", metrics.abstentionPrecision(labels, answeredAll, all, una | ooi),
                Double.NaN);
        put(values, "zero.forcedAccuracy", metrics.forcedAccuracy(labels, outputs, new int[0]), Double.NaN);
        put(values, "zero.auroc", metrics.auroc(labels, outputs, new int[] {0, 1, 2}), Double.NaN);
        return values;
    }

    /** Returns the check: every computed value equals its hand value within {@code 1e-12}, {@code n/a} exactly. */
    Check check() {
        var failures = new ArrayList<String>();
        for (var entry : comparisons().entrySet()) {
            double computed = entry.getValue()[0];
            double expected = entry.getValue()[1];
            boolean same = Double.isNaN(expected) ? Double.isNaN(computed) : Math.abs(computed - expected) <= TOLERANCE;
            if (!same) {
                failures.add(entry.getKey() + " computed " + computed + ", expected " + expected);
            }
        }
        return new Check("metrics.fixture", failures.isEmpty(), failures.isEmpty()
                ? comparisons().size() + " hand-computed values reproduced (Macro, Macro_const, coverage, selective "
                + "risk, tie-aware AURC and AUROC, ECE, Brier, a gold flip and n/a denominators)"
                : String.join("; ", failures));
    }

    private void put(Map<String, double[]> values, String name, OptionalDouble computed, double expected) {
        values.put(name, new double[] {computed.isPresent() ? computed.getAsDouble() : Double.NaN, expected});
    }
}
