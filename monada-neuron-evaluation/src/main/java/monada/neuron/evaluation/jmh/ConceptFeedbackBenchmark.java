package monada.neuron.evaluation.jmh;

import monada.neuron.reasoning.concept.ConceptFeedbackLimits;
import monada.neuron.reasoning.concept.ConceptFeedbackPolicy;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.ConceptModelTrainer;
import monada.neuron.reasoning.concept.ConceptNode;
import monada.neuron.reasoning.concept.ConceptOutcome;
import monada.neuron.reasoning.concept.ConceptPredictionReceipt;
import monada.neuron.reasoning.concept.ConceptThresholds;
import monada.neuron.reasoning.concept.LabeledSequence;
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
 * Scalar reference cost of {@link ConceptFeedbackPolicy#apply} (issue #129, ADR 0045), end to end: the gate, the
 * centroid sums of the verified leaf and its ancestors, the copy of the learned arrays and the new snapshot's
 * fingerprint, which the next update's gate needs. See {@code docs/benchmarks/next-concept-g5.md}.
 *
 * <ul>
 *   <li>{@code applyOne}: one verified outcome, the streaming caller of the G5 harness;</li>
 *   <li>{@code applyBatch}: 64 verified outcomes in one update, so the copy and fingerprint are amortized;</li>
 *   <li>{@code fingerprint}: the SHA-256 of one snapshot alone.</li>
 * </ul>
 *
 * <p>Workloads and the trained model are those of {@code NextConceptPredictorBenchmark}; the feedback share is the
 * maximum (64) so no outcome reaches the cap.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ConceptFeedbackBenchmark {

    private static final String VERIFIER = "benchmark.verifier";
    private static final int BATCH = 64;

    @Param({"MEDIUM", "LARGE"})
    private String workload;

    private ConceptFeedbackPolicy policy;
    private ConceptModel model;
    private List<ConceptOutcome> one;
    private List<ConceptOutcome> batch;

    @Setup(Level.Trial)
    public void setup() {
        boolean large = workload.equals("LARGE");
        int sampleCount = large ? 4096 : 256;
        ConceptHierarchy hierarchy = large ? large() : medium();
        var random = new SplittableRandom(0x5EED_0129L);
        var windows = new ArrayList<LabeledSequence.Window>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < hierarchy.leafCount(); i++) {
                windows.add(new LabeledSequence.Window(decompose(random, sampleCount, 0),
                        hierarchy.node(hierarchy.leaf(i)).conceptCode()));
            }
        }
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, sampleCount, sampleCount,
                SampleUnit.HERTZ);
        model = new ConceptModelTrainer().train(hierarchy, frame, List.of(new LabeledSequence(windows)), 0.05)
                .withThresholds(ConceptThresholds.of(new double[] {0.5}, new double[] {0.05}));
        policy = new ConceptFeedbackPolicy(new ConceptFeedbackLimits(BATCH, 64, 64.0, List.of(VERIFIER)));
        batch = new ArrayList<>(BATCH);
        for (int k = 0; k < BATCH; k++) {
            long leaf = hierarchy.node(hierarchy.leaf(k % hierarchy.leafCount())).conceptCode();
            long previous = hierarchy.node(hierarchy.leaf((k + 1) % hierarchy.leafCount())).conceptCode();
            var receipt = new ConceptPredictionReceipt(model.fingerprint(), "window", k, OptionalLong.empty(),
                    previous, hierarchy.levels() - 1);
            batch.add(ConceptOutcome.verified(receipt, leaf, decompose(random, sampleCount, k),
                    OptionalLong.of(previous), 0, VERIFIER));
        }
        one = List.of(batch.getFirst());
    }

    @Benchmark
    public String applyOne() {
        return policy.apply(model, one, 1).current().fingerprint();
    }

    @Benchmark
    public String applyBatch() {
        return policy.apply(model, batch, 1).current().fingerprint();
    }

    @Benchmark
    public String fingerprint() {
        return model.withThresholds(model.thresholds()).fingerprint();
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

    private SpectralPattern decompose(SplittableRandom random, int sampleCount, long offset) {
        var samples = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = random.nextDouble(-10.0, 10.0);
        }
        var wave = new SampledWave(samples, sampleCount, SampleUnit.HERTZ,
                new WaveProvenance("benchmark.seeded", 1, "window", offset));
        return ((DecompositionResult.Decomposed) new DftWaveDecomposer().decompose(wave)).pattern();
    }
}
