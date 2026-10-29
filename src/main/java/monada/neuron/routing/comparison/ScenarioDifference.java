package monada.neuron.routing.comparison;

/**
 * An input that differs between the two scenarios of a preview. Declaration order is report order: the six that break
 * comparability come first.
 *
 * <p>A scenario that differs in scope, task, stage kind, feature schema, evaluation policy or cutoff answers another
 * question, so its decision says nothing about the effect of a changed catalog, requirement or preference.
 */
public enum ScenarioDifference {
    /** The request's {@code scopeId}. Breaks comparability. */
    SCOPE(true),
    /** The request's {@code taskId}. Breaks comparability. */
    TASK(true),
    /** The request's {@code stageKind}. Breaks comparability. */
    STAGE_KIND(true),
    /** The task features' schema version. Breaks comparability. */
    FEATURE_SCHEMA(true),
    /** The request's evaluation policy id or version. Breaks comparability. */
    EVALUATION_POLICY(true),
    /** The request's cutoff. Breaks comparability. */
    CUTOFF(true),
    /** Any catalog content, its version included. */
    CATALOG(false),
    /** The request's hard requirements. */
    REQUIREMENTS(false),
    /** Whether the request admits overflow routes to eligibility. */
    OVERFLOW_ADMISSION(false),
    /** Any task feature; listed beside {@link #FEATURE_SCHEMA} when the schema version differs too. */
    FEATURES(false),
    /** The source, context or constraints fingerprint. */
    REQUEST_CONTEXT(false),
    /** The execution, attempt or stage instance token, or the request ordinal. */
    REQUEST_IDENTITY(false),
    /** Any field of the preference snapshot. */
    PREFERENCE(false),
    /** The routing policy id or version. */
    POLICY_IDENTITY(false),
    /** The policy parameters the decisions record. */
    POLICY_PARAMETERS(false);

    private final boolean breaksComparability;

    ScenarioDifference(boolean breaksComparability) {
        this.breaksComparability = breaksComparability;
    }

    /** Whether a scenario pair that differs here is {@link ScenarioComparison.Incomparable}. */
    public boolean breaksComparability() {
        return breaksComparability;
    }
}
