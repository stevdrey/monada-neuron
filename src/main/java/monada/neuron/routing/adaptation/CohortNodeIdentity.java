package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.CohortKey;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic identity of a cohort Node (contract section 7): the name-based UUID of a collision-free byte string,
 * UTF-8 of the tag {@value #TAG}, then {@code scopeId}, evaluation policy id and version, {@code stageKind},
 * {@code cohortBucket}, {@code RouteId} and {@code mappingVersion}, each as a 4-byte big-endian length followed by
 * its UTF-8 bytes, and finally {@code RouteVersion} as an 8-byte big-endian {@code long}.
 *
 * <p>The identity is a pure function of its inputs, so it is class-level rather than behavior of an instance; a reset
 * or restore recreates the same identity and no randomness is used.
 */
final class CohortNodeIdentity {

    /** Fixed tag that versions the encoding itself. */
    static final String TAG = "forge-routing/1/node";

    private CohortNodeIdentity() {
    }

    static UUID of(CohortKey key, String evaluationPolicyId, String evaluationPolicyVersion, String mappingVersion) {
        var bytes = new ByteArrayOutputStream(160);
        bytes.writeBytes(TAG.getBytes(StandardCharsets.UTF_8));
        for (String part : new String[] {key.scopeId(), evaluationPolicyId, evaluationPolicyVersion,
                key.stageKind(), key.cohortBucket(), key.route().routeId(), mappingVersion}) {
            byte[] utf8 = part.getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
            bytes.writeBytes(utf8);
        }
        bytes.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(key.route().routeVersion()).array());
        return UUID.nameUUIDFromBytes(bytes.toByteArray());
    }
}
