package monada.neuron.routing.adaptation.recovery;

/**
 * What the host knows about the Store export it intends to replay after a checkpoint. A missing export is a value of
 * its own, never an empty export or a zero watermark, so a preflight cannot mistake "I have nothing" for "nothing
 * happened" and report a cold start.
 */
public sealed interface ExportState {

    /**
     * The host holds an export and knows its high watermark. The watermark says how far the export reaches, not that
     * every observation below it is present; a cut off export with a high enough watermark is not detectable here.
     *
     * @param highWatermark the export's high watermark (the ledger sequence the rebuilt state would examine up to)
     */
    record Observed(long highWatermark) implements ExportState {

        /** Requires a non-negative watermark. */
        public Observed {
            if (highWatermark < 0) {
                throw new IllegalArgumentException("highWatermark must be non-negative, got: " + highWatermark);
            }
        }
    }

    /** The host could not obtain an export. Replay cannot start; nothing is assumed about what the export holds. */
    record Unavailable() implements ExportState {
    }
}
