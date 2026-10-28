package monada.neuron.routing.adaptation.codec;

import monada.neuron.routing.adaptation.CheckpointCohort;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.EntryBasis;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A second {@code routing-checkpoint/1} writer, written from the layout table of
 * {@code docs/specs/routing-checkpoint-v1.md} with a {@link DataOutputStream} and sharing no code with the codec, so
 * the golden vectors check the codec against the spec and not against itself. Public so the store based tests in the
 * parent package can use it.
 */
public final class ReferenceCheckpointWriter {

    private ReferenceCheckpointWriter() {
    }

    /** Fields 1 to 6 and the SHA-256 checksum. */
    public static byte[] write(RoutingStateCheckpoint checkpoint) {
        try {
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            str(out, "routing-checkpoint/1");
            str(out, checkpoint.scopeId());
            str(out, checkpoint.featureSchemaVersion());
            str(out, checkpoint.evaluationPolicyId());
            str(out, checkpoint.evaluationPolicyVersion());
            str(out, checkpoint.mappingVersion());
            str(out, checkpoint.policyId());
            str(out, checkpoint.policyVersion());
            out.writeLong(checkpoint.processedCutoff());
            out.writeInt(checkpoint.preferenceSnapshot().size());
            for (CheckpointCohort cohort : checkpoint.preferenceSnapshot()) {
                str(out, cohort.key().scopeId());
                str(out, cohort.key().stageKind());
                str(out, cohort.key().cohortBucket());
                str(out, cohort.key().route().routeId());
                out.writeLong(cohort.key().route().routeVersion());
                out.writeLong(Double.doubleToRawLongBits(cohort.amplitude()));
                out.writeLong(Double.doubleToRawLongBits(cohort.frequency()));
                out.writeLong(Double.doubleToRawLongBits(cohort.phase()));
                out.writeLong(Double.doubleToRawLongBits(cohort.energy()));
                out.writeInt(cohort.supportingObservations());
            }
            out.writeLong(checkpoint.ledgerDigest().entries());
            out.write(HexFormat.of().parseHex(checkpoint.ledgerDigest().sum()));
            out.writeInt(checkpoint.retainedMarkers().size());
            for (AppliedRevision marker : checkpoint.retainedMarkers()) {
                marker(out, marker);
            }
            out.flush();
            byte[] body = buffer.toByteArray();
            out.write(MessageDigest.getInstance("SHA-256").digest(body));
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void marker(DataOutputStream out, AppliedRevision marker) throws IOException {
        var identity = marker.identity();
        str(out, identity.experienceRef());
        out.writeLong(identity.revision());
        out.writeLong(identity.ledgerSequence());
        var decision = identity.decisionRef();
        str(out, decision.scopeId());
        str(out, decision.taskId());
        str(out, decision.executionId());
        str(out, decision.attemptId());
        str(out, decision.stageId());
        out.writeLong(decision.requestOrdinal());
        str(out, identity.route().routeId());
        out.writeLong(identity.route().routeVersion());
        str(out, identity.mappingVersion());
        str(out, identity.evaluationPolicyId());
        str(out, identity.evaluationPolicyVersion());
        str(out, marker.routingPolicyId());
        str(out, marker.routingPolicyVersion());
        if (marker.supersedesSequence() instanceof Feature.Known<Long> known) {
            out.writeByte(1);
            out.writeLong(known.value());
        } else {
            out.writeByte(0);
        }
        str(out, marker.catalogVersion());
        out.writeLong(marker.cutoff());
        out.writeByte(marker.targetNodeId().isPresent() ? 1 : 0);
        if (marker.targetNodeId().isPresent()) {
            out.writeLong(marker.targetNodeId().get().getMostSignificantBits());
            out.writeLong(marker.targetNodeId().get().getLeastSignificantBits());
        }
        out.writeByte(marker.costPeer().isPresent() ? 1 : 0);
        if (marker.costPeer().isPresent()) {
            str(out, marker.costPeer().get().experienceRef());
            out.writeLong(marker.costPeer().get().revision());
            out.writeLong(marker.costPeer().get().ledgerSequence());
        }
        var basis = (EntryBasis.NoUpdateMarker) marker.basis();
        out.writeByte(2);
        str(out, basis.reason().name());
        str(out, basis.cohortBinding().cohortBucket());
        str(out, basis.cohortBinding().bucketMappingVersion());
        str(out, basis.cohortBinding().mappingVersion());
        str(out, basis.cohortBinding().featureSchemaVersion());
        str(out, marker.disposition().name());
        out.writeLong(Double.doubleToRawLongBits(marker.score()));
    }

    private static void str(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }
}
