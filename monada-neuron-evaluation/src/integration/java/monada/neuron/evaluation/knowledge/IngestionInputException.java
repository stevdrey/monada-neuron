package monada.neuron.evaluation.knowledge;

/**
 * Rejection of a run's locations before any import: an occupied or invalid Store root, or an output location that
 * cannot be written safely. The CLI reports it as rejected input (exit status 2), unlike a failure of the Store or of
 * the ingestion itself.
 */
public final class IngestionInputException extends IllegalStateException {

    /** Creates a rejection with a message naming the location and the rule. */
    public IngestionInputException(String message) {
        super(message);
    }

    /** Creates a rejection caused by an underlying failure such as an I/O error. */
    public IngestionInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
