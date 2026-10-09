package monada.neuron.routing.features;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded, caller-approved characteristics of one task or stage.
 *
 * <p>Every value is host-supplied and is an observation, not proof of capability or authorization. A value
 * is either {@link Feature.Known} or explicitly {@link Feature.Unknown}; unknown is never a measured zero or
 * an empty set. Tag sets are validated, rejected on duplicates and stored in code point order, so equivalent
 * input in any order yields equal snapshots, and the stored lists are defensive immutable copies.
 *
 * <p>The record deliberately has no task, project, execution, route or price field and no outcome: identity
 * and provenance belong to the routing envelope, never here and never in a {@code Signal} (ADR 0005).
 *
 * @param schemaVersion version token of this schema; the encoder reports a mismatch instead of guessing
 * @param stageKind opaque workflow stage token
 * @param category opaque task category token
 * @param languages language tags, at most {@value #MAX_TAGS}, in canonical order
 * @param domains domain tags, at most {@value #MAX_TAGS}, in canonical order
 * @param changeSize non-negative change size in host-defined units
 * @param contextSize non-negative context size in host-defined units
 * @param tests whether the host requires tests
 * @param security whether the host requires a security review
 */
public record TaskFeatures(
        String schemaVersion,
        Feature<String> stageKind,
        Feature<String> category,
        Feature<List<String>> languages,
        Feature<List<String>> domains,
        Feature<Long> changeSize,
        Feature<Long> contextSize,
        Feature<Requirement> tests,
        Feature<Requirement> security) {

    /** Schema version implemented by this package. */
    public static final String SCHEMA_VERSION = "task-features/1";

    /** Maximum tags in each tag set. */
    public static final int MAX_TAGS = 8;

    /** Validates bounds and canonicalizes the tag sets. */
    public TaskFeatures {
        FeatureTokens.require(schemaVersion, "schemaVersion");
        stageKind = checkToken(stageKind, "stageKind");
        category = checkToken(category, "category");
        languages = canonicalTags(languages, "languages");
        domains = canonicalTags(domains, "domains");
        changeSize = checkQuantity(changeSize, "changeSize");
        contextSize = checkQuantity(contextSize, "contextSize");
        Objects.requireNonNull(tests, "tests must not be null");
        Objects.requireNonNull(security, "security must not be null");
    }

    /** Returns a builder in which every feature starts as explicitly unknown. */
    public static Builder builder() {
        return new Builder();
    }

    private static Feature<String> checkToken(Feature<String> feature, String name) {
        Objects.requireNonNull(feature, name + " must not be null");
        if (feature instanceof Feature.Known<String> known) {
            FeatureTokens.require(known.value(), name);
        }
        return feature;
    }

    private static Feature<Long> checkQuantity(Feature<Long> feature, String name) {
        Objects.requireNonNull(feature, name + " must not be null");
        if (feature instanceof Feature.Known<Long> known && known.value() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got: " + known.value());
        }
        return feature;
    }

    private static Feature<List<String>> canonicalTags(Feature<List<String>> feature, String name) {
        Objects.requireNonNull(feature, name + " must not be null");
        if (!(feature instanceof Feature.Known<List<String>> known)) {
            return feature;
        }
        List<String> source = known.value();
        if (source.size() > MAX_TAGS) {
            throw new IllegalArgumentException(
                    name + " allows at most " + MAX_TAGS + " tags, got: " + source.size());
        }
        List<String> sorted = new ArrayList<>(source);
        HashSet<String> seen = HashSet.newHashSet(sorted.size());
        for (String tag : sorted) {
            FeatureTokens.require(tag, name + " tag");
            if (!seen.add(tag)) {
                throw new IllegalArgumentException(name + " contains a duplicate tag: " + tag);
            }
        }
        sorted.sort(FeatureTokens.CODE_POINT_ORDER);
        return Feature.known(List.copyOf(sorted));
    }

    /** Mutable convenience builder; {@link #build()} produces an independent immutable snapshot. */
    public static final class Builder {

        private String schemaVersion = SCHEMA_VERSION;
        private Feature<String> stageKind = Feature.unknown();
        private Feature<String> category = Feature.unknown();
        private Feature<List<String>> languages = Feature.unknown();
        private Feature<List<String>> domains = Feature.unknown();
        private Feature<Long> changeSize = Feature.unknown();
        private Feature<Long> contextSize = Feature.unknown();
        private Feature<Requirement> tests = Feature.unknown();
        private Feature<Requirement> security = Feature.unknown();

        private Builder() {
        }

        /** Overrides the declared schema version, for example to represent another producer. */
        public Builder schemaVersion(String value) {
            this.schemaVersion = value;
            return this;
        }

        /** Sets the stage kind token. */
        public Builder stageKind(String value) {
            this.stageKind = Feature.known(value);
            return this;
        }

        /** Sets the task category token. */
        public Builder category(String value) {
            this.category = Feature.known(value);
            return this;
        }

        /** Sets the language tags; the collection is copied immediately. */
        public Builder languages(Collection<String> values) {
            this.languages = Feature.known(List.copyOf(values));
            return this;
        }

        /** Sets the domain tags; the collection is copied immediately. */
        public Builder domains(Collection<String> values) {
            this.domains = Feature.known(List.copyOf(values));
            return this;
        }

        /** Sets the change size. */
        public Builder changeSize(long value) {
            this.changeSize = Feature.known(value);
            return this;
        }

        /** Sets the context size. */
        public Builder contextSize(long value) {
            this.contextSize = Feature.known(value);
            return this;
        }

        /** Sets the test requirement. */
        public Builder tests(Requirement value) {
            this.tests = Feature.known(value);
            return this;
        }

        /** Sets the security requirement. */
        public Builder security(Requirement value) {
            this.security = Feature.known(value);
            return this;
        }

        /** Builds a validated, canonical snapshot. */
        public TaskFeatures build() {
            return new TaskFeatures(schemaVersion, stageKind, category, languages, domains,
                    changeSize, contextSize, tests, security);
        }
    }
}
