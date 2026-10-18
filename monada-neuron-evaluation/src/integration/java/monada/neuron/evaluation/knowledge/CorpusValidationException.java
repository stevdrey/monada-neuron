package monada.neuron.evaluation.knowledge;

/**
 * Rejection of corpus input that violates the {@code html-kc-v1} corpus contract: empty, malformed, oversize,
 * duplicate or encoding-invalid input, or a missing requested external corpus. The message names the origin
 * (file and row where known) and the violated rule.
 */
public final class CorpusValidationException extends IllegalArgumentException {

    /** Creates a rejection for {@code origin} that violated {@code rule}. */
    public CorpusValidationException(String origin, String rule) {
        super(origin + ": " + rule);
    }

    /** Creates a rejection caused by an underlying failure such as an I/O error. */
    public CorpusValidationException(String origin, String rule, Throwable cause) {
        super(origin + ": " + rule, cause);
    }
}
