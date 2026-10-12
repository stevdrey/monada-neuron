package monada.neuron.routing.outcome;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Whether the result can be attributed to the executed route's own stage. Acceptance of a multi-stage chain alone
 * cannot identify which worker deserves credit, so the host must supply stage-local evidence; Neuron never invents it.
 *
 * @param basis how the result is attributable
 * @param chainStageCount stages in the chain the acceptance covers, at least 1
 * @param evidenceRefs stage-local evidence, canonical order; required for {@code STAGE_LOCAL}, empty otherwise
 */
public record StageAttribution(Basis basis, int chainStageCount, List<EvidenceRef> evidenceRefs) {

    /** Maximum retained attribution references. */
    public static final int MAX_REFS = 8;

    /** How credit can be assigned. */
    public enum Basis {
        /** The host provides evidence about this stage alone. */
        STAGE_LOCAL,
        /** Only the chain as a whole was judged. */
        CHAIN_ONLY,
        /** No attribution evidence was recorded. */
        NONE
    }

    /** Validates the basis against the references and the chain size. */
    public StageAttribution {
        Objects.requireNonNull(basis, "basis must not be null");
        if (chainStageCount < 1) {
            throw new IllegalArgumentException("chainStageCount must be at least 1, got: " + chainStageCount);
        }
        evidenceRefs = Ordering.canonical((Collection<EvidenceRef>) evidenceRefs, Comparator.naturalOrder(), MAX_REFS,
                "evidenceRefs");
        if (basis == Basis.STAGE_LOCAL && evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("STAGE_LOCAL attribution requires evidence references");
        }
        if (basis != Basis.STAGE_LOCAL && !evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(basis + " attribution must not carry evidence references");
        }
        if (basis == Basis.CHAIN_ONLY && chainStageCount < 2) {
            throw new IllegalArgumentException("CHAIN_ONLY attribution needs at least 2 stages");
        }
    }

    /** No attribution evidence. */
    public static StageAttribution none() {
        return new StageAttribution(Basis.NONE, 1, List.of());
    }
}
