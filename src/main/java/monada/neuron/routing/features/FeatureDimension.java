package monada.neuron.routing.features;

/**
 * The fixed dimensions of {@link TaskFeatures}, in canonical encoding order.
 *
 * <p>The ordinal is the dimension index used by the encoder to select a disjoint frequency band.
 */
public enum FeatureDimension {
    /** Workflow stage kind (opaque host token). */
    STAGE_KIND,
    /** Task category (opaque host token). */
    CATEGORY,
    /** Language tags. */
    LANGUAGES,
    /** Domain tags. */
    DOMAINS,
    /** Change size in host-defined units. */
    CHANGE_SIZE,
    /** Context size in host-defined units. */
    CONTEXT_SIZE,
    /** Test requirement. */
    TESTS,
    /** Security requirement. */
    SECURITY
}
