package monada.neuron.evaluation.integration;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.HashSet;
import java.util.List;

/**
 * Versioned deterministic knowledge corpus and query set for the Neuron + Resonance Store evaluation.
 *
 * <p>Documents form small lexically separated topics so expected recall is stable without depending
 * on physical store layout. Any change to documents or expectations must bump {@link #VERSION}.
 */
public final class ResonanceStoreFixtureCorpus {

    /** Identifies this exact corpus and query set in reports. */
    public static final String VERSION = "rs-integration-v1";

    /** One stored knowledge text with a stable, human-readable label. */
    public record Document(String label, String text) {
    }

    /**
     * One recall probe.
     *
     * @param id stable query id
     * @param signal Neuron query Signal; its frequency selects the query text in {@link FixtureSignalCodec}
     * @param text store query text the codec maps the Signal to
     * @param maxResults requested result limit
     * @param expectedTopLabels labels that must be recalled, in required order when {@code ordered}
     * @param ordered whether {@code expectedTopLabels} must appear exactly in this order
     * @param exact whether the recalled label set must equal {@code expectedTopLabels}
     */
    public record Query(
            String id,
            Signal signal,
            String text,
            int maxResults,
            List<String> expectedTopLabels,
            boolean ordered,
            boolean exact) {
        public Query {
            expectedTopLabels = List.copyOf(expectedTopLabels);
        }
    }

    private static final List<Document> DOCUMENTS = List.of(
            new Document("solar-panels", "solar panels convert sunlight into energy"),
            new Document("solar-storage", "solar energy storage uses batteries"),
            new Document("solar-farm", "solar farms generate clean energy"),
            new Document("solar-wind", "solar wind storms disturb satellites"),
            new Document("ocean-tide", "ocean tide follows the moon"),
            new Document("ocean-current", "ocean currents move warm water"),
            new Document("ocean-wave", "ocean waves erode the coastline"),
            new Document("garden-soil", "garden soil needs compost and water"),
            new Document("garden-seed", "garden seeds sprout in spring"),
            new Document("chess-opening", "chess openings control the center"),
            new Document("chess-endgame", "chess endgames reward precise king activity"));

    private static final List<Query> QUERIES = List.of(
            query("solar-energy", 1.0, "solar energy", 3, List.of("solar-panels", "solar-storage", "solar-farm"), false, true),
            query("ocean-tide", 2.0, "ocean tide", 2, List.of("ocean-tide"), true, false),
            query("garden-compost", 3.0, "garden compost", 3, List.of("garden-soil"), true, false),
            query("chess-endgame", 4.0, "chess endgames", 1, List.of("chess-endgame"), true, false),
            query("no-match", 5.0, "quantum chromodynamics lattice", 3, List.of(), true, true),
            query("blank", 6.0, "   ", 3, List.of(), true, true));

    private ResonanceStoreFixtureCorpus() {
    }

    /** Returns the immutable corpus in seeding order. */
    public static List<Document> documents() {
        return DOCUMENTS;
    }

    /** Returns the immutable query set. */
    public static List<Query> queries() {
        return QUERIES;
    }

    /** Returns the query with the id, failing loudly for an unknown fixture id. */
    public static Query query(String id) {
        return QUERIES.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown fixture query: " + id));
    }

    /** Returns the Neuron query Signal for a probe frequency. */
    static Signal querySignal(double frequency) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, frequency, 0.0));
    }

    /** Verifies internal consistency of labels and expectations. */
    static void validate() {
        var labels = new HashSet<String>();
        for (var document : DOCUMENTS) {
            if (!labels.add(document.label())) {
                throw new IllegalStateException("duplicate fixture label: " + document.label());
            }
        }
        var ids = new HashSet<String>();
        for (var query : QUERIES) {
            if (!ids.add(query.id())) {
                throw new IllegalStateException("duplicate fixture query id: " + query.id());
            }
            for (var expected : query.expectedTopLabels()) {
                if (!labels.contains(expected)) {
                    throw new IllegalStateException("query " + query.id() + " expects unknown label " + expected);
                }
            }
        }
    }

    private static Query query(
            String id,
            double frequency,
            String text,
            int maxResults,
            List<String> expected,
            boolean ordered,
            boolean exact) {
        return new Query(id, querySignal(frequency), text, maxResults, expected, ordered, exact);
    }
}
