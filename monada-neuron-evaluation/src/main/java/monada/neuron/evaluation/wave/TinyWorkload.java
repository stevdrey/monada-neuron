package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.FeatureConceptScorer.FeatureSequence;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Check;
import monada.neuron.reasoning.concept.ConceptHierarchy;
import monada.neuron.reasoning.concept.ConceptModel;
import monada.neuron.reasoning.concept.ConceptModelTrainer;
import monada.neuron.reasoning.concept.ConceptNode;
import monada.neuron.reasoning.concept.ConceptQuery;
import monada.neuron.reasoning.concept.LabeledSequence;
import monada.neuron.reasoning.concept.NextConceptPredictor;
import monada.neuron.reasoning.concept.PredictionBudget;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import monada.neuron.spectral.WaveDecomposer;
import monada.neuron.spectral.WaveProvenance;

import java.util.ArrayList;
import java.util.List;

/**
 * The tiny workload of contract section 14.1 (issue #130): windows of {@value #N} samples at {@value #N} Hz, two
 * concepts at depth 1, one window per query. Concept {@code TONE_1} is {@code cos(2π·1·t)} (DFT bin 1), concept
 * {@code TONE_3} is {@code cos(2π·3·t)} (bin 3); the training sequences alternate them, so the next concept of each is
 * the other. Exact and deterministic: control C (core predictor) and control D (reference scorer over a Haar pattern
 * of depth 3) must predict the other concept for each clean window, the reference scorer over DFT features must
 * reproduce C bit for bit, and a second pass must give bit-identical predictions.
 */
final class TinyWorkload {

    /** Samples per window and sample rate in hertz. */
    static final int N = 8;

    /** Fixed softmax temperature; the tiny workload selects nothing. */
    static final double TEMPERATURE = 0.05;

    /** Haar depth of control D on {@value #N} samples. */
    static final int HAAR_LEVELS = 3;

    private static final int DOMAIN = 131;
    private static final int[] BINS = {1, 3};
    private static final SpectralFrame FRAME = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, N, N,
            SampleUnit.HERTZ);

    private final ConceptModelTrainer trainer = new ConceptModelTrainer();
    private final NextConceptPredictor predictor = new NextConceptPredictor();
    private final FeatureConceptScorer scorer = new FeatureConceptScorer();
    private final SpectralResonanceDynamics dynamics = new SpectralResonanceDynamics();
    private final ConceptHierarchy hierarchy = ConceptHierarchy.of(DOMAIN, List.of(
            new ConceptNode(0, 1, ConceptNode.ROOT, 0), new ConceptNode(1, 3, ConceptNode.ROOT, 0)));

    /** Returns the clean window of concept {@code ordinal}. */
    SampledWave window(int ordinal, int position) {
        var samples = new double[N];
        for (int n = 0; n < N; n++) {
            samples[n] = StrictMath.cos(2.0 * StrictMath.PI * BINS[ordinal] * n / N);
        }
        return new SampledWave(samples, N, SampleUnit.HERTZ, new WaveProvenance("ncm-tiny", 1,
                "concept-" + ordinal, (long) position * N));
    }

    /** Returns the core model trained on two alternating sequences of four windows. */
    ConceptModel model() {
        var decomposer = ConceptFeatureMap.dft().decomposer();
        var sequences = new ArrayList<LabeledSequence>();
        for (int start = 0; start < 2; start++) {
            var windows = new ArrayList<LabeledSequence.Window>();
            for (int p = 0; p < 4; p++) {
                int ordinal = (start + p) % 2;
                windows.add(new LabeledSequence.Window(ConceptFeatureMap.dft().decompose(decomposer,
                        window(ordinal, p)), hierarchy.node(ordinal).conceptCode()));
            }
            sequences.add(new LabeledSequence(windows));
        }
        return trainer.train(hierarchy, FRAME, sequences, TEMPERATURE);
    }

    /** Runs the tiny workload and returns its check. */
    Check check() {
        ConceptModel model = model();
        FeatureConceptModel dft = train(ConceptFeatureMap.dft());
        FeatureConceptModel haar = train(new ConceptFeatureMap(SpectralBasis.HAAR, HAAR_LEVELS, 0.0));
        int cRight = 0;
        int dRight = 0;
        boolean equivalent = true;
        boolean deterministic = true;
        var outcomes = new ArrayList<String>();
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            int expected = 1 - ordinal;
            ScoredPrediction c = predictC(model, ordinal);
            ScoredPrediction reference = predictFeature(dft, ordinal);
            ScoredPrediction d = predictFeature(haar, ordinal);
            cRight += c.answeredLeaf() && c.selected() == expected ? 1 : 0;
            dRight += d.answeredLeaf() && d.selected() == expected ? 1 : 0;
            equivalent &= c.sameAs(reference);
            deterministic &= c.sameAs(predictC(model, ordinal)) && d.sameAs(predictFeature(haar, ordinal));
            outcomes.add("bin " + BINS[ordinal] + " -> C " + c.selected() + ", D " + d.selected());
        }
        boolean passed = cRight == 2 && dRight == 2 && equivalent && deterministic;
        return new Check("workload.tiny", passed, "N=" + N + ", 2 concepts, depth 1: C " + cRight + "/2 and D (Haar L="
                + HAAR_LEVELS + ") " + dRight + "/2 predict the alternating next concept " + outcomes
                + "; DFT reference scorer " + (equivalent ? "bit-identical to C" : "DIFFERENT from C")
                + "; second pass " + (deterministic ? "bit-identical" : "DIFFERENT"));
    }

    ScoredPrediction predictC(ConceptModel model, int ordinal) {
        var decomposer = ConceptFeatureMap.dft().decomposer();
        SpectralPattern pattern = ConceptFeatureMap.dft().decompose(decomposer, window(ordinal, 0));
        return ScoredPrediction.of(predictor.predict(ConceptQuery.of(pattern), model, PredictionBudget.DEFAULT));
    }

    private ScoredPrediction predictFeature(FeatureConceptModel model, int ordinal) {
        ConceptFeatureMap map = model.features();
        return scorer.predict(feature(map, map.decomposer(), window(ordinal, 0)), -1, model, PredictionBudget.DEFAULT);
    }

    private FeatureConceptModel train(ConceptFeatureMap map) {
        WaveDecomposer decomposer = map.decomposer();
        var sequences = new ArrayList<FeatureSequence>();
        for (int start = 0; start < 2; start++) {
            var features = new double[4][];
            var leaves = new int[4];
            for (int p = 0; p < 4; p++) {
                leaves[p] = (start + p) % 2;
                features[p] = feature(map, decomposer, window(leaves[p], p));
            }
            sequences.add(new FeatureSequence(features, leaves));
        }
        return scorer.train(hierarchy, map, map.dimension(N), sequences, TEMPERATURE);
    }

    private double[] feature(ConceptFeatureMap map, WaveDecomposer decomposer, SampledWave wave) {
        var feature = new double[map.dimension(N)];
        return map.extract(map.decompose(decomposer, wave), null, dynamics, feature) ? feature : null;
    }
}
