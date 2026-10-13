package monada.neuron.resonance.adapter.experience;

import monada.neuron.routing.outcome.RoutingObservation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of one import: the observations, what was refused and why, and the snapshot they came from. Observations are in
 * the applied order {@code (ledgerSequence ascending, experienceRef ascending by code point)}. The record is a value; it
 * triggers no learning and holds no Store type.
 *
 * @param observations translated observations
 * @param rejections refused samples in Store order
 * @param diagnostics import-level findings
 * @param snapshot the pinned snapshot, or empty when nothing was read
 */
public record ExperienceImport(
        List<RoutingObservation> observations, List<ExperienceRejection> rejections,
        List<ImportDiagnostic> diagnostics, Optional<Snapshot> snapshot) {

    /**
     * The pinned snapshot an import read.
     *
     * @param schema schema of the exported pages
     * @param cutoffSequence ledger sequence that bounded every page
     * @param ledgerPrefixDigest Store digest of the ledger prefix up to the cutoff
     * @param pages pages read
     * @param samplesExamined samples translated or refused
     */
    public record Snapshot(
            String schema, long cutoffSequence, String ledgerPrefixDigest, int pages, int samplesExamined) {

        /** Validates the fields. */
        public Snapshot {
            Objects.requireNonNull(schema, "schema must not be null");
            Objects.requireNonNull(ledgerPrefixDigest, "ledgerPrefixDigest must not be null");
        }
    }

    /** Copies the lists. */
    public ExperienceImport {
        observations = List.copyOf(observations);
        rejections = List.copyOf(rejections);
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
