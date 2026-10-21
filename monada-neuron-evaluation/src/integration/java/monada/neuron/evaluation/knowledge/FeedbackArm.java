package monada.neuron.evaluation.knowledge;

import com.monada.api.ExactQueryKeyStrategy;
import com.monada.api.FeedbackQueryKeyStrategy;
import com.monada.api.LexicallyEnrichedQueryKeyStrategy;
import com.monada.api.MonadaMemoryOptions;
import com.monada.api.NormalizedQueryKeyStrategy;
import com.monada.encoder.TextNormalizer;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One arm of the relevance-feedback experiment (Issue #110): an independent Store root, imported exactly like every
 * other arm, and the Store feedback key strategy it is opened with. Only treatment arms receive events, and each
 * treatment arm writes them through its own strategy, because the Store stores the key computed when an event is
 * written and looks events up by the key of the query being ranked.
 *
 * <p>Every option set derives from one {@link MonadaMemoryOptions#defaults()} and changes only ranking-side options
 * (the key strategy and feedback-aware ranking), which the Store does not persist in its encoding profile; the
 * experiment checks that every arm's root opens with each of them.
 */
enum FeedbackArm {

    /** No events; default options. */
    CONTROL("control", KeyStrategy.EXACT),
    /** Events keyed by the raw query text (the Store default). */
    TREATMENT_EXACT("treatment-exact", KeyStrategy.EXACT),
    /** Events keyed by the normalized query text. */
    TREATMENT_NORMALIZED("treatment-normalized", KeyStrategy.NORMALIZED),
    /** Events keyed by the sorted normalized and lexically expanded terms. */
    TREATMENT_ENRICHED("treatment-enriched", KeyStrategy.ENRICHED);

    /** The Store's public feedback key strategies, by the label the report uses. */
    enum KeyStrategy {
        EXACT("exact"),
        NORMALIZED("normalized"),
        ENRICHED("lexically-enriched");

        private final String label;

        KeyStrategy(String label) {
            this.label = label;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }

        /** Creates the Store strategy over {@code normalizer}, the normalizer of the options it is used with. */
        FeedbackQueryKeyStrategy create(TextNormalizer normalizer) {
            return switch (this) {
                case EXACT -> new ExactQueryKeyStrategy();
                case NORMALIZED -> new NormalizedQueryKeyStrategy(normalizer);
                case ENRICHED -> new LexicallyEnrichedQueryKeyStrategy(normalizer);
            };
        }

        /**
         * Returns the key the Store records for, and looks up with, {@code query} under this strategy with the default
         * normalizer, including the Store's fallback from a blank key to the raw query.
         */
        String effectiveKey(String query) {
            Objects.requireNonNull(query, "query must not be null");
            var key = Defaults.STRATEGIES.get(this).keyFor(query);
            return key.isBlank() ? query : key;
        }
    }

    /**
     * Every key strategy over the default normalizer, created once on first use: building the default options loads the
     * Store's lexical resources, and the strategies are stateless wrappers of an immutable normalizer.
     */
    private static final class Defaults {

        static final Map<KeyStrategy, FeedbackQueryKeyStrategy> STRATEGIES = strategies();

        private static Map<KeyStrategy, FeedbackQueryKeyStrategy> strategies() {
            var normalizer = MonadaMemoryOptions.defaults().textNormalizer();
            var strategies = new EnumMap<KeyStrategy, FeedbackQueryKeyStrategy>(KeyStrategy.class);
            for (var strategy : KeyStrategy.values()) {
                strategies.put(strategy, strategy.create(normalizer));
            }
            return Collections.unmodifiableMap(strategies);
        }
    }

    private final String directory;
    private final KeyStrategy strategy;

    FeedbackArm(String directory, KeyStrategy strategy) {
        this.directory = directory;
        this.strategy = strategy;
    }

    /** Returns the arm's directory under the arms root, which is also its report label. */
    String directory() {
        return directory;
    }

    /** Returns the arm's report label. */
    String label() {
        return directory;
    }

    /** Returns the key strategy the arm is opened with. */
    KeyStrategy strategy() {
        return strategy;
    }

    /** Returns whether the arm receives the feedback plan. */
    boolean treatment() {
        return this != CONTROL;
    }

    /** Returns the treatment arms in declaration order. */
    static List<FeedbackArm> treatments() {
        return Arrays.stream(values()).filter(FeedbackArm::treatment).toList();
    }

    /**
     * Returns the default Store options with this arm's key strategy and feedback-aware ranking on or off; the
     * normalizer and expansion options, which the encoding profile records, stay the defaults.
     */
    MonadaMemoryOptions options(boolean feedbackAware) {
        var base = MonadaMemoryOptions.defaults();
        return new MonadaMemoryOptions(base.textNormalizer(), feedbackAware, base.expansionOptions(),
                strategy.create(base.textNormalizer()));
    }

    /** Parses a report label. */
    static FeedbackArm ofLabel(String label) {
        return Arrays.stream(values()).filter(arm -> arm.directory.equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown arm " + label));
    }
}
