package monada.neuron.routing.features;

/** Whether the host requires a quality dimension such as tests or security review for a task. */
public enum Requirement {

    /** The host does not require the dimension. */
    NOT_REQUIRED,

    /** The host requires the dimension. */
    REQUIRED
}
