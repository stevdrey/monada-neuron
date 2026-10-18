package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Bounded probe queries of the persistence experiment (Issue #106) and the recall snapshot they produce.
 *
 * <p>Probes are fixed before any run and never derived from Store output: one readback probe per chunk, whose query
 * is the chunk's own normalized text, plus a few hand-written paraphrase probes. They carry no expected answer. They
 * exist to compare what the same root returns in two processes and through two paths (direct {@code MonadaMemory}
 * and the Neuron adapter), not to score retrieval quality, which is #107's job.
 */
final class PersistenceProbes {

    /** Version of the probe set; bump with any change to the probe ids, the paraphrases or the readback probe rule. */
    static final String VERSION = "html-kc-probes-v1";

    /** Upper bound on the probe count, so the probe codec and every snapshot stay bounded. */
    static final int MAX_PROBES = 256;

    /** Hand-written paraphrase queries; they name a topic without copying any chunk sentence. */
    private static final List<Probe> PARAPHRASES = List.of(
            new Probe("paraphrase:hyperlink", "which tag makes a clickable link to another page"),
            new Probe("paraphrase:image-description", "describe a picture for people who cannot see it"),
            new Probe("paraphrase:list-order", "list where the sequence of items matters"),
            new Probe("paraphrase:form-label", "connect a text caption to a form field"),
            new Probe("paraphrase:external-script", "load javascript from a separate file"),
            new Probe("paraphrase:page-title-heading", "top level title heading of a page"));

    /**
     * One probe.
     *
     * @param probeId stable label, {@code readback:<chunkId>} or {@code paraphrase:<name>}
     * @param query query text sent to the Store
     */
    record Probe(String probeId, String query) {

        Probe {
            Objects.requireNonNull(probeId, "probeId must not be null");
            Objects.requireNonNull(query, "query must not be null");
            if (probeId.isBlank() || query.isBlank() || probeId.contains("\t") || probeId.contains("\n")) {
                throw new IllegalArgumentException("probe id and query must be non-blank, single-field values");
            }
        }
    }

    private PersistenceProbes() {
    }

    /** Returns the readback probes of {@code corpus}, in chunk order, followed by the paraphrase probes. */
    static List<Probe> of(HtmlKnowledgeCorpus corpus) {
        var probes = new ArrayList<Probe>(corpus.chunks().size() + PARAPHRASES.size());
        for (var chunk : corpus.chunks()) {
            probes.add(new Probe("readback:" + chunk.chunkId(), chunk.text()));
        }
        probes.addAll(PARAPHRASES);
        if (probes.size() > MAX_PROBES) {
            throw new IllegalArgumentException(probes.size() + " probes exceed the bound " + MAX_PROBES);
        }
        var ids = new HashSet<String>();
        for (var probe : probes) {
            if (!ids.add(probe.probeId())) {
                throw new IllegalArgumentException("duplicate probe id " + probe.probeId());
            }
        }
        return List.copyOf(probes);
    }

    /**
     * One recalled result of one probe on one path.
     *
     * @param probeId probe that produced it
     * @param path {@code direct} ({@code MonadaMemory.resonate}) or {@code neuron} (adapter {@code recall})
     * @param rank zero-based position in the returned order
     * @param chunkId chunk the result maps to through the manifest, or {@code <unmapped>} when it maps to none
     * @param score exact score, rendered with {@link Double#toHexString(double)} so equality is bit-exact
     */
    record Row(String probeId, String path, int rank, String chunkId, double score) {

        Row {
            Objects.requireNonNull(probeId, "probeId must not be null");
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(chunkId, "chunkId must not be null");
        }

        String toTsv() {
            return probeId + '\t' + path + '\t' + rank + '\t' + chunkId + '\t' + Double.toHexString(score);
        }
    }

    /** Header row of a snapshot TSV. */
    static final String SNAPSHOT_HEADER = "probeId\tpath\trank\tchunkId\tscore";

    /**
     * Recall observed on one root in one process.
     *
     * @param profile persisted encoding and vector-format profile, rendered from the Store's own records
     * @param probeHash {@code probeHash} of the probe set that produced the rows
     * @param rows every recalled result, ordered by probe, then path, then rank
     */
    record Snapshot(String profile, String probeHash, List<Row> rows) {

        Snapshot {
            Objects.requireNonNull(profile, "profile must not be null");
            Objects.requireNonNull(probeHash, "probeHash must not be null");
            if (profile.contains("\n") || probeHash.contains("\n")) {
                throw new IllegalArgumentException("profile and probeHash must be single lines");
            }
            rows = List.copyOf(rows);
        }

        /** Returns the rows of one path. */
        List<Row> rows(String path) {
            return rows.stream().filter(row -> row.path().equals(path)).toList();
        }

        String toTsv() {
            var sb = new StringBuilder();
            sb.append("# profile: ").append(profile).append('\n');
            sb.append("# probeHash: ").append(probeHash).append('\n');
            sb.append(SNAPSHOT_HEADER).append('\n');
            for (var row : rows) {
                sb.append(row.toTsv()).append('\n');
            }
            return sb.toString();
        }

        /** Parses {@link #toTsv()} strictly. */
        static Snapshot parseTsv(String tsv) {
            var lines = tsv.lines().toList();
            if (lines.size() < 3 || !lines.get(0).startsWith("# profile: ")
                    || !lines.get(1).startsWith("# probeHash: ") || !lines.get(2).equals(SNAPSHOT_HEADER)) {
                throw new IllegalArgumentException("not a recall snapshot: profile, probeHash or header line missing");
            }
            var rows = new ArrayList<Row>(lines.size() - 3);
            for (var index = 3; index < lines.size(); index++) {
                var columns = lines.get(index).split("\t", -1);
                if (columns.length != 5) {
                    throw new IllegalArgumentException("malformed snapshot row " + (index + 1));
                }
                rows.add(new Row(columns[0], columns[1], Integer.parseInt(columns[2]), columns[3],
                        Double.parseDouble(columns[4])));
            }
            return new Snapshot(lines.get(0).substring("# profile: ".length()),
                    lines.get(1).substring("# probeHash: ".length()), rows);
        }
    }
}
