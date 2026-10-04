package monada.neuron.evaluation.metrics;

/** One named, deterministic semantic verdict of an evaluation, rendered by {@link CheckedEvaluationReport}. */
public interface EvaluationCheck {

    /** Returns the stable check identifier. */
    String name();

    /** Returns whether the check passed. */
    boolean passed();

    /** Returns a human-readable explanation of what was verified and observed. */
    String detail();
}
