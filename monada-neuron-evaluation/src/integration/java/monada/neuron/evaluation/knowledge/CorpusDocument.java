package monada.neuron.evaluation.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * One normalized source document of a corpus with its provenance.
 *
 * @param documentId {@code doc:<slug>}
 * @param file file name the document was read from
 * @param source {@code authored-fixture}, or the source URL plus a pinned revision
 * @param license declared license (SPDX-style identifier)
 * @param attribution credit line
 * @param references original references cited for authored content (URLs only, no copied text)
 * @param documentSha256 SHA-256 of the normalized document text
 * @param text normalized document text that chunk spans refer to
 */
public record CorpusDocument(
        String documentId,
        String file,
        String source,
        String license,
        String attribution,
        List<String> references,
        String documentSha256,
        String text) {

    public CorpusDocument {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(license, "license must not be null");
        Objects.requireNonNull(attribution, "attribution must not be null");
        references = List.copyOf(Objects.requireNonNull(references, "references must not be null"));
        Objects.requireNonNull(documentSha256, "documentSha256 must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }

    /** Returns the slug of {@code doc:<slug>}. */
    public String slug() {
        return documentId.substring("doc:".length());
    }
}
