package monada.neuron.resonance.adapter.experience;

import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, bounded description of one import. Scope, snapshot cutoff, page size and total sample cap are always
 * stated by the caller; nothing is defaulted from a clock or a hidden cursor.
 *
 * @param scopeId scope to read; any sample of another scope is refused before admission
 * @param cutoffSequence inclusive ledger sequence that pins the snapshot; later appends are never read
 * @param pageSize samples per Store page, 1 to {@link #MAX_PAGE_SIZE}
 * @param maxTotalSamples cap on samples examined (translated or rejected), 1 to {@link #MAX_TOTAL_SAMPLES}
 * @param additionalGates dimensions required to pass besides the execution's own evaluation policy
 */
public record ExperienceExportRequest(
        String scopeId, long cutoffSequence, int pageSize, int maxTotalSamples, Set<QualityDimension> additionalGates) {

    /** Largest page the Store serves. */
    public static final int MAX_PAGE_SIZE = 500;

    /** Largest cap on samples examined by one import. */
    public static final int MAX_TOTAL_SAMPLES = 10_000;

    /** Validates bounds and copies the gates. */
    public ExperienceExportRequest {
        OpaqueTokens.require(scopeId, "scopeId");
        if (cutoffSequence < 0) {
            throw new IllegalArgumentException("cutoffSequence must be non-negative, got: " + cutoffSequence);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be in [1, " + MAX_PAGE_SIZE + "], got: " + pageSize);
        }
        if (maxTotalSamples < 1 || maxTotalSamples > MAX_TOTAL_SAMPLES) {
            throw new IllegalArgumentException(
                    "maxTotalSamples must be in [1, " + MAX_TOTAL_SAMPLES + "], got: " + maxTotalSamples);
        }
        Objects.requireNonNull(additionalGates, "additionalGates must not be null");
        additionalGates = additionalGates.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(additionalGates));
    }

    /**
     * A request with no additional gates.
     *
     * @param scopeId scope
     * @param cutoffSequence snapshot cutoff
     * @param pageSize page size
     * @param maxTotalSamples sample cap
     * @return the request
     */
    public static ExperienceExportRequest of(String scopeId, long cutoffSequence, int pageSize, int maxTotalSamples) {
        return new ExperienceExportRequest(scopeId, cutoffSequence, pageSize, maxTotalSamples, Set.of());
    }
}
