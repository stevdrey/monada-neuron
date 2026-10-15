package monada.neuron.evaluation.routing;

import monada.neuron.routing.adaptation.CohortState;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.feedback.AppliedRevision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * SHA-256 digests over canonical UTF-8 lines, so equal digests mean identical fixtures, decision sequences, learned
 * state or semantic reports.
 *
 * <p>Every line is {@code name=<utf8-byte-length>:<value>}, and every identity (fixture id and version, policy id and
 * version) is its own line, so opaque tokens containing a separator cannot make two inputs hash alike. Every rendered
 * value is canonical before it is hashed: records render their components in declaration order,
 * every list and map in the routing API and in this package is kept in a defined order, and Node doubles are rendered
 * as their exact bit patterns. Timings, timestamps and environment data never enter a digest.
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
        for (int i = 0; i < trace.size(); i++) {
            lines.add("decision", trace.line(i));
        }
        return lines.digest();
    }

    /** Every feedback event of a recorded run in delivery order. */
    public String feedback(ReplayRun run) {
        var lines = new Lines();
        lines.add("arm", run.arm());
        run.feedback().forEach(event -> lines.add("event", event));
        return lines.digest();
    }

    /** Learned state: cohort Nodes, support, ledger and watermark. */
    public String state(RoutingStateStore state) {
        var lines = new Lines();
        cohortLines(lines, state);
        for (AppliedRevision entry : state.ledger()) {
            lines.add("ledger", entry + " score=" + Long.toHexString(Double.doubleToLongBits(entry.score())));
        }
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
        for (CohortState cohort : state.cohorts(NO_ROUTES)) {
            lines.add("cohort", cohort.key() + " node=" + cohort.nodeId()
                    + " amplitude=" + bits(cohort.amplitude()) + " frequency=" + bits(cohort.frequency())
                    + " phase=" + bits(cohort.phase()) + " energy=" + bits(cohort.energy())
                    + " support=" + cohort.supportingObservations());
        }
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToLongBits(value));
    }

    /** Line-oriented SHA-256 accumulator. */
    private static final class Lines {

        private final MessageDigest sha;

        Lines() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required of every Java platform", e);
            }
        }

        /** {@code name=<utf8-byte-length>:<value>\n}: the length prefix keeps value boundaries unambiguous. */
        void add(String name, Object value) {
            byte[] rendered = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            sha.update((name + "=" + rendered.length + ":").getBytes(StandardCharsets.UTF_8));
            sha.update(rendered);
            sha.update((byte) '\n');
        }

        String digest() {
            return HexFormat.of().formatHex(sha.digest());
        }
    }
}
