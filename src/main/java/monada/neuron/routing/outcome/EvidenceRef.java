package monada.neuron.routing.outcome;

import monada.neuron.routing.features.Feature;

import java.util.Objects;

/**
 * Opaque pointer to caller-held evidence. Neuron never dereferences it.
 *
 * @param kind opaque kind token
 * @param reference opaque reference, up to 512 code points
 * @param digest optional opaque digest token
 */
public record EvidenceRef(String kind, String reference, Feature<String> digest)
        implements Comparable<EvidenceRef> {

    /** Validates tokens. */
    public EvidenceRef {
        OutcomeTokens.token(kind, "kind");
        OutcomeTokens.text(reference, "reference");
        Objects.requireNonNull(digest, "digest must not be null");
        if (digest instanceof Feature.Known<String> known) {
            OutcomeTokens.text(known.value(), "digest");
        }
    }

    @Override
    public int compareTo(EvidenceRef other) {
        int byKind = OutcomeTokens.compare(kind, other.kind);
        return byKind != 0 ? byKind : OutcomeTokens.compare(reference, other.reference);
    }
}
