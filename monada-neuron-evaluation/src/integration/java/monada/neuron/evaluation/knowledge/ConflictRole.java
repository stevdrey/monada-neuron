package monada.neuron.evaluation.knowledge;

/** Role of a member of the corpus's documented conflicting-source pair. */
public enum ConflictRole {

    /** The member whose claim the curated gold labels treat as correct. */
    CURATED,

    /** The member that deliberately contradicts the curated claim. */
    CONTRADICTING
}
