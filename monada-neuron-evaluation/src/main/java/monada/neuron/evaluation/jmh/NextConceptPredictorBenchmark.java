package monada.neuron.evaluation.jmh;

import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.ConceptModelTrainer;
import monada.neuron.reasoning.concept.ConceptNode;
import monada.neuron.reasoning.concept.ConceptPrediction;
import monada.neuron.reasoning.concept.ConceptQuery;
import monada.neuron.reasoning.concept.ConceptThresholds;
import monada.neuron.reasoning.concept.LabeledSequence;
import monada.neuron.reasoning.concept.NextConceptPredictor;
import monada.neuron.reasoning.concept.PredictionBudget;
import monada.neuron.spectral.DecompositionResult;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import monada.neuron.spectral.WaveProvenance;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Scalar reference cost of one {@link NextConceptPredictor#predict} (issue #127, ADR 0043), end to end: the
 * compatibility and previous-concept checks, the feature, one centroid comparison per concept, the transition mix,
 * the descent and the result values. See {@code docs/benchmarks/next-concept-g3.md}.
 *
 * <ul>
 *   <li>{@code MEDIUM}: the contract section 12.3 shape, 2 categories over 4 leaves (6 concepts, 2 levels),
 *       {@code N = 256} (129 bins).</li>
 *   <li>{@code LARGE}: the contract section 11.1 bounds, 16 roots, 48 inner concepts and 192 leaves (256 concepts, 3
 *       levels), {@code N = 4096} (2,049 bins).</li>
 * </ul>
 *
 * <p>The model is trained once per trial on two seeded random windows per leaf, in one labeled sequence; thresholds
 * are {@code 0.5} confidence and {@code 0.05} margin at every depth. The query carries a previous leaf.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class NextConceptPredictorBenchmark {

    @Param({"MEDIUM", "LARGE"})
    private String workload;

    private NextConceptPredictor predictor;
    private ConceptModel model;
    private ConceptQuery query;

    @Setup(Level.Trial)
    public void setup() {
        boolean large = workload.equals("LARGE");
        int sampleCount = large ? 4096 : 256;
        ConceptHierarchy hierarchy = large ? large() : medium();
        var random = new SplittableRandom(0x5EED_0127L);
        var windows = new ArrayList<LabeledSequence.Window>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < hierarchy.leafCount(); i++) {
                windows.add(new LabeledSequence.Window(decompose(random, sampleCount),
                        hierarchy.node(hierarchy.leaf(i)).conceptCode()));
            }
        }
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, sampleCount, sampleCount,
                SampleUnit.HERTZ);
        model = new ConceptModelTrainer().train(hierarchy, frame, List.of(new LabeledSequence(windows)), 0.05)
                .withThresholds(ConceptThresholds.of(new double[] {0.5}, new double[] {0.05}));
        query = new ConceptQuery(decompose(random, sampleCount),
                OptionalLong.of(hierarchy.node(hierarchy.leaf(0)).conceptCode()));
        predictor = new NextConceptPredictor();
    }

    @Benchmark
    public ConceptPrediction predict() {
        return predictor.predict(query, model, PredictionBudget.DEFAULT);
    }

    private ConceptHierarchy medium() {
        return ConceptHierarchy.of(127, List.of(
                new ConceptNode(0, 1, ConceptNode.ROOT, 0), new ConceptNode(1, 2, ConceptNode.ROOT, 0),
                new ConceptNode(2, 11, 0, 1), new ConceptNode(3, 12, 0, 1),
                new ConceptNode(4, 21, 1, 1), new ConceptNode(5, 22, 1, 1)));
    }

    /** 16 roots, 3 children each (48), 4 children each (192): 256 concepts. */
    private ConceptHierarchy large() {
        var nodes = new ArrayList<ConceptNode>();
        for (int i = 0; i < ConceptHierarchy.MAX_CONCEPTS; i++) {
            nodes.add(i < 16 ? new ConceptNode(i, i, ConceptNode.ROOT, 0)
                    : i < 64 ? new ConceptNode(i, i, (i - 16) / 3, 1)
                    : new ConceptNode(i, i, 16 + (i - 64) / 4, 2));
        }
        return ConceptHierarchy.of(127, nodes);
    }

    private SpectralPattern decompose(SplittableRandom random, int sampleCount) {
        var samples = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = random.nextDouble(-10.0, 10.0);
        }
        var wave = new SampledWave(samples, sampleCount, SampleUnit.HERTZ,
                new WaveProvenance("benchmark.seeded", 1, "window-0", 0L));
        return ((DecompositionResult.Decomposed) new DftWaveDecomposer().decompose(wave)).pattern();
    }
}
