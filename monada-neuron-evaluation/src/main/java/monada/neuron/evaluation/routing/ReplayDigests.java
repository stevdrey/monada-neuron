package monada.neuron.evaluation.routing;

import monada.neuron.routing.adaptation.CohortState;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.catalog.RouteCatalog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * SHA-256 digests over canonical entries, so equal digests mean identical fixtures, decision sequences, learned state
 * or semantic reports.
 *
 * <p>Every entry is a length-prefixed name followed by the {@link CanonicalEncoder} encoding of its value: typed,
 * length-prefixed and walked through record components, never through {@code toString}, so opaque tokens containing
 * any separator cannot make two inputs hash alike. Identities (fixture id and version, policy id and version) are
 * separate entries, sets and maps are order-independent, and doubles are encoded as their exact bit patterns. Timings,
 * timestamps and environment data never enter a digest.
 */
public final class ReplayDigests {

    private static final RouteCatalog NO_ROUTES = RouteCatalog.of("replay-digest", List.of());

    /** Fixture identity, definitions, policy and every task with its outcome oracle. */
    public String fixture(ReplayFixture fixture) {
        var lines = new Lines();
        lines.add("fixtureId", fixture.fixtureId());
        lines.add("version", fixture.version());
        lines.add("definition", fixture.definition());
        lines.add("evidencePolicy", fixture.evidencePolicy());
        lines.add("policyId", fixture.policy().policyId());
        lines.add("policyVersion", fixture.policy().policyVersion());
        lines.add("policyParameters", fixture.policy().parameters());
        lines.add("partition", fixture.partition());
        lines.add("horizon", fixture.horizon());
        for (ReplayTask task : fixture.tasks()) {
            lines.add("task", task);
        }
        return lines.digest();
    }

    /** The decision sequence: every sample of a recorded run in replay order. */
    public String decisions(ReplayRun run) {
        var lines = new Lines();
        lines.add("arm", run.arm());
        run.samples().forEach(sample -> lines.add("sample", sample));
        return lines.digest();
    }

    /** A decision trace, recorded or not: every decision's route, codes and snapshot watermark in replay order. */
    public String decisions(DecisionTrace trace) {
        var lines = new Lines();
        lines.add("trace", trace);
        return lines.digest();
    }

    /** Every feedback event of a recorded run in delivery order. */
    public String feedback(ReplayRun run) {
        var lines = new Lines();
        lines.add("arm", run.arm());
        run.feedback().forEach(event -> lines.add("event", event));
        return lines.digest();
    }

    /**
     * Learned state: cohort Nodes, support, the core {@code routing-ledger-digest/1} of every entry the state has
     * accounted for, and the watermark. The core ledger digest does not change when the ledger is compacted, so a
     * checkpointed run and a from-scratch rebuild compare digest for digest.
     */
    public String state(RoutingStateStore state) {
        var lines = new Lines();
        cohortLines(lines, state);
        lines.add("ledgerDigest", state.ledgerDigest());
        lines.add("processedCutoff", state.processedCutoff());
        return lines.digest();
    }

    /** Cohort Nodes and support only; a neutral-only ledger changes the ledger but must leave these untouched. */
    public String cohorts(RoutingStateStore state) {
        var lines = new Lines();
        cohortLines(lines, state);
        return lines.digest();
    }

    /**
     * The semantic report: the summaries in {@link ReplayArm} order, each summary's lines in name order, whatever the
     * collection's own iteration order.
     *
     * @throws IllegalArgumentException if two summaries are of the same arm: their lines would collide
     */
    public String report(Collection<ReplaySummary> summaries) {
        var byArm = new EnumMap<ReplayArm, ReplaySummary>(ReplayArm.class);
        for (ReplaySummary summary : summaries) {
            if (byArm.putIfAbsent(summary.arm(), summary) != null) {
                throw new IllegalArgumentException("two summaries of the " + summary.arm() + " arm");
            }
        }
        var lines = new Lines();
        for (ReplaySummary summary : byArm.values()) {
            for (Map.Entry<String, String> line : summary.lines().entrySet()) {
                lines.add(line.getKey(), line.getValue());
            }
        }
        return lines.digest();
    }

    private static void cohortLines(Lines lines, RoutingStateStore state) {
        // every cohort is DORMANT against the empty catalog, so the status adds nothing beyond the key
        for (CohortState cohort : state.cohorts(NO_ROUTES)) {
            lines.add("cohort", cohort);
        }
    }

    /** Entry-oriented SHA-256 accumulator. */
    private static final class Lines {

        private final CanonicalEncoder encoder = new CanonicalEncoder();
        private final MessageDigest sha;

        Lines() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required of every Java platform", e);
            }
        }

        /** The length-prefixed name, then the canonical encoding of the value. */
        void add(String name, Object value) {
            sha.update(encoder.encode(name));
            sha.update(encoder.encode(value));
        }

        String digest() {
            return HexFormat.of().formatHex(sha.digest());
        }
    }
}
