package monada.neuron.routing.catalog;

import monada.neuron.routing.features.Feature;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Versioned behavioral description of one route, as observed by the host.
 *
 * <p>A descriptor names worker, provider, model and effort as opaque tokens and carries the typed route-side values
 * of every hard constraint. It is not proof of capability or authorization. Token sets are validated, stored in
 * code point order and defensively copied; an absent set is the empty set. Availability and fallback priority are
 * not descriptor fields (see {@link CatalogEntry}).
 *
 * @param key route identity
 * @param worker worker token, or unknown
 * @param provider provider token, or unknown
 * @param model model token, or unknown
 * @param effort effort token, or unknown
 * @param billingMode host-declared billing mode
 * @param overflowClass host-set overflow class
 * @param tier host tier, at least 1
 * @param stages stage kinds the route is compatible with (no wildcard)
 * @param capabilities capability tokens
 * @param tools tool tokens
 * @param executionModes execution-mode tokens
 * @param locality the route's single locality token, or unknown
 * @param contextCeiling known context ceiling in host units, or unknown (a known 0 is distinct from unknown)
 */
public record RouteDescriptor(
        RouteKey key,
        Feature<String> worker,
        Feature<String> provider,
        Feature<String> model,
        Feature<String> effort,
        BillingMode billingMode,
        OverflowClass overflowClass,
        int tier,
        List<String> stages,
        List<String> capabilities,
        List<String> tools,
        List<String> executionModes,
        Feature<String> locality,
        Feature<Long> contextCeiling) {

    /** Validates bounds and canonicalizes the token sets. */
    public RouteDescriptor {
        Objects.requireNonNull(key, "key must not be null");
        worker = token(worker, "worker");
        provider = token(provider, "provider");
        model = token(model, "model");
        effort = token(effort, "effort");
        Objects.requireNonNull(billingMode, "billingMode must not be null");
        Objects.requireNonNull(overflowClass, "overflowClass must not be null");
        if (tier < 1) {
            throw new IllegalArgumentException("tier must be at least 1, got: " + tier);
        }
        stages = RouteTokens.canonicalSet(stages, "stages");
        capabilities = RouteTokens.canonicalSet(capabilities, "capabilities");
        tools = RouteTokens.canonicalSet(tools, "tools");
        executionModes = RouteTokens.canonicalSet(executionModes, "executionModes");
        locality = token(locality, "locality");
        Objects.requireNonNull(contextCeiling, "contextCeiling must not be null");
        if (contextCeiling instanceof Feature.Known<Long> known && known.value() < 0) {
            throw new IllegalArgumentException("contextCeiling must be non-negative, got: " + known.value());
        }
    }

    /** Returns a builder for the given identity; every optional value starts absent or unknown. */
    public static Builder builder(String routeId, long routeVersion) {
        return new Builder(new RouteKey(routeId, routeVersion));
    }

    private static Feature<String> token(Feature<String> feature, String name) {
        Objects.requireNonNull(feature, name + " must not be null");
        if (feature instanceof Feature.Known<String> known) {
            RouteTokens.require(known.value(), name);
        }
        return feature;
    }

    /** Mutable convenience builder; {@link #build()} produces an independent immutable descriptor. */
    public static final class Builder {

        private final RouteKey key;
        private Feature<String> worker = Feature.unknown();
        private Feature<String> provider = Feature.unknown();
        private Feature<String> model = Feature.unknown();
        private Feature<String> effort = Feature.unknown();
        private BillingMode billingMode = BillingMode.UNKNOWN;
        private OverflowClass overflowClass = OverflowClass.STANDARD;
        private int tier = 1;
        private List<String> stages = List.of();
        private List<String> capabilities = List.of();
        private List<String> tools = List.of();
        private List<String> executionModes = List.of();
        private Feature<String> locality = Feature.unknown();
        private Feature<Long> contextCeiling = Feature.unknown();

        private Builder(RouteKey key) {
            this.key = key;
        }

        /** Sets the worker token. */
        public Builder worker(String value) {
            this.worker = Feature.known(value);
            return this;
        }

        /** Sets the provider token. */
        public Builder provider(String value) {
            this.provider = Feature.known(value);
            return this;
        }

        /** Sets the model token. */
        public Builder model(String value) {
            this.model = Feature.known(value);
            return this;
        }

        /** Sets the effort token. */
        public Builder effort(String value) {
            this.effort = Feature.known(value);
            return this;
        }

        /** Sets the billing mode. */
        public Builder billingMode(BillingMode value) {
            this.billingMode = value;
            return this;
        }

        /** Sets the overflow class. */
        public Builder overflowClass(OverflowClass value) {
            this.overflowClass = value;
            return this;
        }

        /** Sets the host tier. */
        public Builder tier(int value) {
            this.tier = value;
            return this;
        }

        /** Sets the compatible stage kinds; validated and copied immediately. */
        public Builder stages(Collection<String> values) {
            this.stages = RouteTokens.canonicalSet(values, "stages");
            return this;
        }

        /** Sets the capabilities; validated and copied immediately. */
        public Builder capabilities(Collection<String> values) {
            this.capabilities = RouteTokens.canonicalSet(values, "capabilities");
            return this;
        }

        /** Sets the tools; validated and copied immediately. */
        public Builder tools(Collection<String> values) {
            this.tools = RouteTokens.canonicalSet(values, "tools");
            return this;
        }

        /** Sets the execution modes; validated and copied immediately. */
        public Builder executionModes(Collection<String> values) {
            this.executionModes = RouteTokens.canonicalSet(values, "executionModes");
            return this;
        }

        /** Sets the locality token. */
        public Builder locality(String value) {
            this.locality = Feature.known(value);
            return this;
        }

        /** Sets a known context ceiling. */
        public Builder contextCeiling(long value) {
            this.contextCeiling = Feature.known(value);
            return this;
        }

        /** Builds a validated, canonical descriptor. */
        public RouteDescriptor build() {
            return new RouteDescriptor(key, worker, provider, model, effort, billingMode, overflowClass, tier,
                    stages, capabilities, tools, executionModes, locality, contextCeiling);
        }
    }
}
