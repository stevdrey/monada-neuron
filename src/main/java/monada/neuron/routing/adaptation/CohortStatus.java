package monada.neuron.routing.adaptation;

/** Whether a cohort's route is present in the catalog a snapshot is published for. */
public enum CohortStatus {

    /** The route is in the catalog; the cohort is published in snapshots. */
    ACTIVE,

    /**
     * The route is absent from the catalog. The cohort keeps its state, is never published or consulted, still
     * counts toward the cohort bound, and becomes active again, intact, when the identical route returns.
     */
    DORMANT
}
