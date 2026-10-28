package monada.neuron.routing.adaptation.codec;

import monada.neuron.routing.adaptation.CheckpointCohort;
import monada.neuron.routing.adaptation.LedgerDigest;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.NoUpdateReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.DIGEST_SUM;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.KEY_A;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.KEY_B;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.checkpoint;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.cohorts;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.empty;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.i64;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.marker;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.markers;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.populated;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.rejected;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.splice;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.str;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.u8;
import static monada.neuron.routing.adaptation.codec.CheckpointFixtures.concat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckpointCodecTest {

    private static RoutingStateCheckpoint roundTrip(RoutingStateCheckpoint checkpoint) {
        return assertInstanceOf(CheckpointDecodeResult.Decoded.class,
                CheckpointCodec.decode(CheckpointCodec.encode(checkpoint))).checkpoint();
    }

    /** Record equality plus each marker's audit cutoff, which {@code AppliedRevision.equals} ignores. */
    private static void assertSameComponents(RoutingStateCheckpoint expected, RoutingStateCheckpoint actual) {
        assertEquals(expected, actual);
        assertEquals(expected.retainedMarkers().stream().map(AppliedRevision::cutoff).toList(),
                actual.retainedMarkers().stream().map(AppliedRevision::cutoff).toList());
    }

    @Test
    void anEmptyCheckpointRoundTrips() {
        assertSameComponents(empty(), roundTrip(empty()));
    }

    @Test
    void aPopulatedCheckpointRoundTripsInEveryComponent() {
        var original = populated();
        var decoded = roundTrip(original);
        assertSameComponents(original, decoded);
        assertEquals(original.processedCutoff(), decoded.processedCutoff());
        assertEquals(original.ledgerDigest(), decoded.ledgerDigest());
    }

    @Test
    void negativeZeroStaysNegativeZero() {
        var decoded = roundTrip(populated());
        assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(decoded.preferenceSnapshot().getFirst().frequency()));
        AppliedRevision emojiMarker = decoded.retainedMarkers().getLast();
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(emojiMarker.score()));
        assertEquals(Feature.known(4L), emojiMarker.supersedesSequence());
    }

    @Test
    void constructorOrderDoesNotChangeTheBytes() {
        var canonical = CheckpointCodec.encode(populated());
        var permuted = checkpoint(20, cohorts().reversed(), new LedgerDigest(10, DIGEST_SUM), markers().reversed());
        assertArrayEquals(canonical, CheckpointCodec.encode(permuted));
    }

    @Test
    void equalCheckpointsGiveEqualBytesAndEncodingIsAFreshArray() {
        byte[] first = CheckpointCodec.encode(populated());
        byte[] second = CheckpointCodec.encode(populated());
        assertArrayEquals(first, second);
        assertNotSame(first, second);
    }

    @Test
    void aMarkerCutoffAloneChangesTheBytes() {
        var later = new ArrayList<>(markers());
        AppliedRevision first = later.getFirst();
        later.set(0, marker(first.experienceRef(), 0, 5, Feature.unknown(), NoUpdateReason.NO_TARGET_BINDING, 6, 0.0));
        var other = checkpoint(20, cohorts(), new LedgerDigest(10, DIGEST_SUM), later);
        // equal as records, because AppliedRevision.equals ignores the audit cutoff, yet different bytes
        assertEquals(populated(), other);
        assertFalse(Arrays.equals(CheckpointCodec.encode(populated()), CheckpointCodec.encode(other)));
        assertSameComponents(other, roundTrip(other));
    }

    @Test
    void decodeDoesNotDependOnTheCallersArrayAfterTheCall() {
        byte[] bytes = CheckpointCodec.encode(populated());
        var decoded = assertInstanceOf(CheckpointDecodeResult.Decoded.class, CheckpointCodec.decode(bytes));
        Arrays.fill(bytes, (byte) 0);
        assertSameComponents(populated(), decoded.checkpoint());
    }

    @Test
    void nullInputsThrow() {
        assertThrows(NullPointerException.class, () -> CheckpointCodec.encode(null));
        assertThrows(NullPointerException.class, () -> CheckpointCodec.decode(null));
    }

    @Test
    void cohortsOutOfCanonicalOrderAreNonCanonical() {
        byte[] encoded = CheckpointCodec.encode(checkpoint(20, cohorts(), new LedgerDigest(10, DIGEST_SUM), List.of()));
        byte[] a = cohortBytes(KEY_A.cohortBucket(), "sub-a", 1, 0.75, -0.0, 1.5, 2.0, 3);
        byte[] b = cohortBytes(KEY_B.cohortBucket(), "sub-b", 2, -0.25, 0.5, 0.0, 1.0, 0);
        byte[] swapped = splice(encoded, concat(a, b), concat(b, a));
        assertEquals(CheckpointRejectReason.NON_CANONICAL, rejected(swapped));
    }

    @Test
    void markersOutOfCanonicalOrderAreNonCanonical() {
        var markers = markers();
        byte[] encoded = CheckpointCodec.encode(populated());
        byte[] first = markerBytes(encoded, markers.getFirst(), markers.getLast());
        byte[] second = markerBytes(encoded, markers.getLast(), null);
        assertEquals(CheckpointRejectReason.NON_CANONICAL, rejected(splice(encoded, concat(first, second),
                concat(second, first))));
    }

    @Test
    void valuesTheRecordsRefuseAreInvalid() {
        byte[] encoded = CheckpointCodec.encode(populated());
        // a support count the checkpoint cannot account for in its digest
        byte[] support = concat(i64(Double.doubleToRawLongBits(2.0)), CheckpointFixtures.i32(3));
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(encoded, support,
                concat(i64(Double.doubleToRawLongBits(2.0)), CheckpointFixtures.i32(30)))));
        // a non finite channel
        byte[] amplitude = i64(Double.doubleToRawLongBits(0.75));
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(encoded, amplitude,
                i64(Double.doubleToRawLongBits(Double.NaN)))));
        // a recordable reason that is not retryable, so it is never retained
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(encoded, str("NO_TARGET_BINDING"),
                str("STALE_ARTIFACT_REVISION"))));
        // a reason that is never recorded at all
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(encoded, str("PROVENANCE_MISMATCH"),
                str("MAPPING_MISMATCH"))));
        byte[] bare = CheckpointCodec.encode(empty());
        // a control character inside a token of the same byte length
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(bare, str("policy-x"),
                str("policy\u0007x"))));
        // a token with trailing whitespace
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(bare, str("features/1"),
                str("features/1 "))));
    }

    // covers: AC-3, the reader parses each optional marker field, and the record refusal makes it INVALID, not MALFORMED
    @Test
    void markerShapesTheFormatCarriesButACheckpointNeverRetainsAreInvalid() {
        byte[] encoded = CheckpointCodec.encode(populated());
        // the first marker from its cutoff: no target Node, no cost peer, the no update tag, its reason
        byte[] tail = concat(i64(5), u8(0, 0, 2), str("NO_TARGET_BINDING"));
        // a target Node is recorded only for an entry that moved a Node, never for a neutral marker
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(encoded, tail,
                concat(i64(5), u8(1), i64(7), i64(9), u8(0, 2), str("NO_TARGET_BINDING")))));
        // a cost peer belongs only to a cost refined reward
        assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(encoded, tail,
                concat(i64(5), u8(0, 1), str("exp-0"), i64(0), i64(1), u8(2), str("NO_TARGET_BINDING")))));
        // only a neutral entry can be a no update marker
        byte[] neutral = concat(str("NEUTRAL"), i64(Double.doubleToRawLongBits(-0.0)));
        for (String disposition : new String[] {"REINFORCE", "PENALIZE"}) {
            assertEquals(CheckpointRejectReason.INVALID_CHECKPOINT, rejected(splice(encoded, neutral,
                    concat(str(disposition), i64(Double.doubleToRawLongBits(0.5))))), disposition);
        }
    }

    @Test
    void theResultRecordsRequireTheirValue() {
        assertThrows(NullPointerException.class, () -> new CheckpointDecodeResult.Decoded(null));
        assertThrows(NullPointerException.class, () -> new CheckpointDecodeResult.Rejected(null));
    }

    private static byte[] cohortBytes(String bucket, String routeId, long routeVersion, double amplitude,
            double frequency, double phase, double energy, int support) {
        return concat(str(CheckpointFixtures.SCOPE), str("implement"), str(bucket), str(routeId), i64(routeVersion),
                i64(Double.doubleToRawLongBits(amplitude)), i64(Double.doubleToRawLongBits(frequency)),
                i64(Double.doubleToRawLongBits(phase)), i64(Double.doubleToRawLongBits(energy)),
                CheckpointFixtures.i32(support));
    }

    /** The bytes of {@code marker} inside {@code encoded}: from its experienceRef to the next marker or the end. */
    private static byte[] markerBytes(byte[] encoded, AppliedRevision marker, AppliedRevision next) {
        byte[] body = CheckpointFixtures.body(encoded);
        int from = CheckpointFixtures.indexOf(body, str(marker.experienceRef()), 0);
        int to = next == null ? body.length : CheckpointFixtures.indexOf(body, str(next.experienceRef()), from);
        return Arrays.copyOfRange(body, from, to);
    }

    @Test
    void cohortsKeepTheirSupportCounts() {
        var decoded = roundTrip(populated());
        assertEquals(List.of(3, 0),
                decoded.preferenceSnapshot().stream().map(CheckpointCohort::supportingObservations).toList());
    }
}
