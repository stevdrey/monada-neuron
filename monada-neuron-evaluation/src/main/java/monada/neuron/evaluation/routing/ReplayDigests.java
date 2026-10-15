package monada.neuron.evaluation.routing;

import monada.neuron.routing.adaptation.CohortState;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.feedback.AppliedRevision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * SHA-256 digests over canonical UTF-8 lines, so equal digests mean identical fixtures, decision sequences, learned
 * state or semantic reports.
 *
 * <p>Every rendered value is canonical before it is hashed: records render their components in declaration order,
 * every list and map in the routing API and in this package is kept in a defined order, and Node doubles are rendered
 * as their exact bit patterns. Timings, timestamps and environment data never enter a digest.
 */
public final class ReplayDigests {

    private static final RouteCatalog NO_ROUTES = RouteCatalog.of("replay-digest", List.of());

    /** Fixture identity, definitions, policy and every task with its outcome oracle. */
    public String fixture(ReplayFixture fixture) {
        var lines = new Lines();
        lines.add("fixture", fixture.fixtureId() + "@" + fixture.version());
        lines.add("definition", fixture.definition());
        lines.add("evidencePolicy", fixture.evidencePolicy());
        lines.add("policy", fixture.policy().policyId() + "@" + fixture.policy().policyVersion() + " "
                + fixture.policy().parameters());
        lines.add("partition", fixture.partition());
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

    /** The semantic report: every summary line of every arm, in name order. */
    public String report(Collection<ReplaySummary> summaries) {
        var lines = new Lines();
        for (ReplaySummary summary : summaries) {
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

        void add(String name, Object value) {
            sha.update((name + "=" + value + "\n").getBytes(StandardCharsets.UTF_8));
        }

        String digest() {
            return HexFormat.of().formatHex(sha.digest());
        }
    }
}
