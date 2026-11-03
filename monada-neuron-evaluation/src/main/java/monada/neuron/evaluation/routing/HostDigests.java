package monada.neuron.evaluation.routing;

import monada.neuron.routing.adaptation.recovery.RecoveryAssessment;
import monada.neuron.routing.adaptation.recovery.RecoveryBlocker;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Digests the host scenario suite adds beside {@link ReplayDigests}, which stays a #67 file: the receipt chain of the
 * host's records and the outcome of its restart cases. Both are SHA-256 over {@link CanonicalEncoder} lists, so they
 * depend only on what was decided and recovered, never on the clock or the environment.
 */
final class HostDigests {

    private final CanonicalEncoder encoder = new CanonicalEncoder();

    /** SHA-256 over the ordered receipt SHA-256 of every record. */
    String receiptChain(List<HostDecisionRecord> records) {
        return sha256(encoder.encode(records.stream().map(HostDecisionRecord::receiptSha256).toList()));
    }

    /** SHA-256 over each probe's case id, checkpoint SHA-256, assessment kind and sorted blocker kinds, in order. */
    String recovery(List<RecoveryProbe> probes) {
        var lines = new ArrayList<List<String>>(probes.size());
        for (RecoveryProbe probe : probes) {
            List<String> blockers = switch (probe.assessment()) {
                case RecoveryAssessment.Compatible ignored -> List.of();
                case RecoveryAssessment.Blocked blocked -> blocked.blockers().stream().map(RecoveryBlocker::kind)
                        .map(Enum::name).sorted().toList();
            };
            var line = new ArrayList<String>();
            line.add(probe.caseId());
            line.add(probe.checkpointSha256().orElse("-"));
            line.add(probe.assessment().getClass().getSimpleName());
            line.addAll(blockers);
            lines.add(line);
        }
        return sha256(encoder.encode(lines));
    }

    /** SHA-256 of bytes, as lowercase hex. */
    String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
