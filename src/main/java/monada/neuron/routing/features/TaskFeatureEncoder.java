package monada.neuron.routing.features;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic encoder from {@link TaskFeatures} to {@code OBSERVATION} signals under an {@link EncodingPolicy}.
 *
 * <p>Encoding, per dimension {@code d} with index {@code i = d.ordinal()}:
 *
 * <ul>
 *   <li>frequency {@code = 3^i * (1 + ratio)} with {@code ratio} in {@code [0, 1]}, so dimensions occupy the closed,
 *       disjoint bands {@code [3^i, 2*3^i]} and every dimension has the same within-band resonance range
 *       ({@code F = min/max} in {@code [0.5, 1]}), which keeps the dimensions equally weighted under
 *       {@code ScalarResonanceMetric};
 *   <li>a known value has amplitude {@code 1.0} and phase {@code 0.0};
 *   <li>a categorical token at vocabulary index {@code k} of {@code n} has {@code ratio = (k+1)/(n+1)}; a token
 *       outside the vocabulary shares {@code ratio = 1.0} (the {@code OTHER} position, a documented collision);
 *   <li>a number {@code v} has {@code ratio = min(bitLength(v), L) / L} with {@code L = bitLength(ceiling)}, using
 *       integer arithmetic only; {@code v > ceiling} saturates (exactly when {@code bitLength(v) > L}) and is reported;
 *   <li>an unknown value is {@code FrequencyState(1.0, 2.5 * 3^i, PI)}: non-silent, so unknown matches only unknown
 *       of the same dimension (resonance 1) and never a known value (phase opposition, resonance 0); it sits in the
 *       gap above its band, so it still names its dimension;
 *   <li>a tag set emits one signal per tag in canonical order, nothing for a known empty set, and one unknown
 *       marker for an unknown set.
 * </ul>
 *
 * <p>Every signal's frequency identifies its dimension, so consumers must match signals by band, never by list
 * position (tag sets have variable length). This is a coarse ordinal layout, not a semantic embedding: no
 * similarity quality is claimed. Time is {@code O(F)} in the number of features and extra space is at most
 * {@value #MAX_SIGNALS} signals. The encoder is stateless apart from its immutable vocabulary indexes, performs no
 * I/O and reads only the argument.
 */
public final class TaskFeatureEncoder {

    /** Upper bound on signals per encoding: six scalar dimensions plus two full tag sets. */
    public static final int MAX_SIGNALS = 6 + 2 * TaskFeatures.MAX_TAGS;

    private static final double[] BAND_BASE = bandBases();

    private final EncodingPolicy policy;
    private final Map<String, Integer> stageKinds;
    private final Map<String, Integer> categories;
    private final Map<String, Integer> languages;
    private final Map<String, Integer> domains;
    private final int changeSizeBits;
    private final int contextSizeBits;

    /** Creates an encoder for the given policy. */
    public TaskFeatureEncoder(EncodingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.stageKinds = index(policy.stageKinds());
        this.categories = index(policy.categories());
        this.languages = index(policy.languages());
        this.domains = index(policy.domains());
        this.changeSizeBits = bitLength(policy.changeSizeCeiling());
        this.contextSizeBits = bitLength(policy.contextSizeCeiling());
    }

    /** Returns the policy this encoder applies. */
    public EncodingPolicy policy() {
        return policy;
    }

    /**
     * Encodes the features, or reports a schema mismatch without throwing.
     *
     * @param features validated typed features
     * @return {@link EncodingResult.Encoded} or {@link EncodingResult.SchemaMismatch}
     */
    public EncodingResult encode(TaskFeatures features) {
        Objects.requireNonNull(features, "features must not be null");
        if (!TaskFeatures.SCHEMA_VERSION.equals(features.schemaVersion())) {
            return new EncodingResult.SchemaMismatch(TaskFeatures.SCHEMA_VERSION, features.schemaVersion());
        }
        Run run = new Run();
        run.categorical(FeatureDimension.STAGE_KIND, features.stageKind(), stageKinds, policy.stageKinds().size());
        run.categorical(FeatureDimension.CATEGORY, features.category(), categories, policy.categories().size());
        run.tags(FeatureDimension.LANGUAGES, features.languages(), languages, policy.languages().size());
        run.tags(FeatureDimension.DOMAINS, features.domains(), domains, policy.domains().size());
        run.numeric(FeatureDimension.CHANGE_SIZE, features.changeSize(), policy.changeSizeCeiling(), changeSizeBits);
        run.numeric(FeatureDimension.CONTEXT_SIZE, features.contextSize(), policy.contextSizeCeiling(),
                contextSizeBits);
        run.requirement(FeatureDimension.TESTS, features.tests());
        run.requirement(FeatureDimension.SECURITY, features.security());
        return new EncodingResult.Encoded(features, run.signals, policy.id(), policy.version(),
                List.copyOf(run.saturated), List.copyOf(run.otherBucket));
    }

    private static Map<String, Integer> index(List<String> vocabulary) {
        Map<String, Integer> map = HashMap.newHashMap(vocabulary.size());
        for (int k = 0; k < vocabulary.size(); k++) {
            map.put(vocabulary.get(k), k);
        }
        return Map.copyOf(map);
    }

    private static double[] bandBases() {
        double[] bases = new double[FeatureDimension.values().length];
        double base = 1.0;
        for (int i = 0; i < bases.length; i++) {
            bases[i] = base;
            base *= 3.0;
        }
        return bases;
    }

    private static int bitLength(long value) {
        return Long.SIZE - Long.numberOfLeadingZeros(value);
    }

    /** Per-call accumulator; confined to one {@link #encode} invocation. */
    private static final class Run {

        private final List<Signal> signals = new ArrayList<>(MAX_SIGNALS);
        private final EnumSet<FeatureDimension> saturated = EnumSet.noneOf(FeatureDimension.class);
        private final EnumSet<FeatureDimension> otherBucket = EnumSet.noneOf(FeatureDimension.class);

        void categorical(FeatureDimension dimension, Feature<String> feature, Map<String, Integer> vocabulary,
                int size) {
            if (feature instanceof Feature.Known<String>(var value)) {
                token(dimension, value, vocabulary, size);
            } else {
                unknown(dimension);
            }
        }

        void tags(FeatureDimension dimension, Feature<List<String>> feature, Map<String, Integer> vocabulary,
                int size) {
            if (feature instanceof Feature.Known<List<String>>(var values)) {
                for (String tag : values) {
                    token(dimension, tag, vocabulary, size);
                }
            } else {
                unknown(dimension);
            }
        }

        void numeric(FeatureDimension dimension, Feature<Long> feature, long ceiling, int ceilingBits) {
            if (feature instanceof Feature.Known<Long>(var value)) {
                if (value > ceiling) {
                    saturated.add(dimension);
                }
                known(dimension, Math.min(bitLength(value), ceilingBits), ceilingBits);
            } else {
                unknown(dimension);
            }
        }

        void requirement(FeatureDimension dimension, Feature<Requirement> feature) {
            if (feature instanceof Feature.Known<Requirement>(var value)) {
                known(dimension, value.ordinal() + 1, Requirement.values().length + 1);
            } else {
                unknown(dimension);
            }
        }

        private void token(FeatureDimension dimension, String token, Map<String, Integer> vocabulary, int size) {
            Integer position = vocabulary.get(token);
            if (position == null) {
                otherBucket.add(dimension);
                known(dimension, size + 1, size + 1);
            } else {
                known(dimension, position + 1, size + 1);
            }
        }

        private void known(FeatureDimension dimension, int numerator, int denominator) {
            double frequency = BAND_BASE[dimension.ordinal()] * (denominator + numerator) / denominator;
            signals.add(new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, frequency, 0.0)));
        }

        private void unknown(FeatureDimension dimension) {
            double frequency = BAND_BASE[dimension.ordinal()] * 2.5;
            signals.add(new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, frequency, Math.PI)));
        }
    }
}
