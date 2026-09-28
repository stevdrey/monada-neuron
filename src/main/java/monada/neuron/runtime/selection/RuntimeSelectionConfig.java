package monada.neuron.runtime.selection;

import java.util.Objects;

/**
 * Immutable master configuration governing optimized backend selection, crossover thresholds,
 * and fallback semantics across all runtime cognitive components.
 *
 * @param overallPreference global execution preference fallback
 * @param fallbackPolicy fallback strategy when an optimized or requested backend is unavailable/ineligible
 * @param resonance resonance scoring backend configuration
 * @param graph signal propagation graph backend configuration
 * @param aeon Aeon coordination backend configuration
 */
public record RuntimeSelectionConfig(
        ExecutionPreference overallPreference,
        FallbackPolicy fallbackPolicy,
        ResonanceSelectionConfig resonance,
        GraphSelectionConfig graph,
        AeonSelectionConfig aeon) {

    public RuntimeSelectionConfig {
        Objects.requireNonNull(overallPreference, "overallPreference must not be null");
        Objects.requireNonNull(fallbackPolicy, "fallbackPolicy must not be null");
        Objects.requireNonNull(resonance, "resonance must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(aeon, "aeon must not be null");
    }

    /**
     * Safe reference default configuration using reference implementations everywhere.
     */
    public static RuntimeSelectionConfig referenceDefault() {
        return new RuntimeSelectionConfig(
                ExecutionPreference.REFERENCE,
                FallbackPolicy.FALLBACK_TO_REFERENCE,
                ResonanceSelectionConfig.reference(),
                GraphSelectionConfig.reference(),
                AeonSelectionConfig.reference());
    }

    /**
     * Benchmark-driven automatic selection configuration applying evidence-derived thresholds.
     */
    public static RuntimeSelectionConfig autoDefault() {
        return new RuntimeSelectionConfig(
                ExecutionPreference.AUTO,
                FallbackPolicy.FALLBACK_TO_REFERENCE,
                ResonanceSelectionConfig.auto(),
                GraphSelectionConfig.auto(),
                AeonSelectionConfig.auto());
    }

    /**
     * Forced reference configuration for debugging and numerical/semantic equivalence testing.
     */
    public static RuntimeSelectionConfig forcedReference() {
        return referenceDefault();
    }

    /** Creates a new fluent builder initialized with reference defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a new fluent builder initialized with automatic defaults. */
    public static Builder autoBuilder() {
        return new Builder()
                .overallPreference(ExecutionPreference.AUTO)
                .resonance(ResonanceSelectionConfig.auto())
                .graph(GraphSelectionConfig.auto())
                .aeon(AeonSelectionConfig.auto());
    }

    /** Fluent builder for {@link RuntimeSelectionConfig}. */
    public static final class Builder {
        private ExecutionPreference overallPreference = ExecutionPreference.REFERENCE;
        private FallbackPolicy fallbackPolicy = FallbackPolicy.FALLBACK_TO_REFERENCE;
        private ResonanceSelectionConfig resonance = ResonanceSelectionConfig.reference();
        private GraphSelectionConfig graph = GraphSelectionConfig.reference();
        private AeonSelectionConfig aeon = AeonSelectionConfig.reference();

        public Builder overallPreference(ExecutionPreference preference) {
            this.overallPreference = Objects.requireNonNull(preference, "preference must not be null");
            return this;
        }

        public Builder fallbackPolicy(FallbackPolicy fallbackPolicy) {
            this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy must not be null");
            return this;
        }

        public Builder resonance(ResonanceSelectionConfig resonance) {
            this.resonance = Objects.requireNonNull(resonance, "resonance must not be null");
            return this;
        }

        public Builder graph(GraphSelectionConfig graph) {
            this.graph = Objects.requireNonNull(graph, "graph must not be null");
            return this;
        }

        public Builder aeon(AeonSelectionConfig aeon) {
            this.aeon = Objects.requireNonNull(aeon, "aeon must not be null");
            return this;
        }

        public RuntimeSelectionConfig build() {
            return new RuntimeSelectionConfig(
                    overallPreference,
                    fallbackPolicy,
                    resonance,
                    graph,
                    aeon);
        }
    }
}
