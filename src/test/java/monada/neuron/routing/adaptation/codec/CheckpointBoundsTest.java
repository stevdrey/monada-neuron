package monada.neuron.routing.adaptation.codec;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.adaptation.CheckpointCohort;
import monada.neuron.routing.adaptation.LedgerDigest;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.EntryBasis;
import monada.neuron.routing.feedback.FeedbackIdentity;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.token.OpaqueTokens;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CheckpointBoundsTest {

    /** U+1F600, 4 UTF-8 bytes. */
    private static final String WIDE = "😀";

    /** {@code codePoints} wide code points, the last replaced by U+20000 + {@code variant} (also 4 bytes). */
    private static String wide(int codePoints, int variant) {
        return WIDE.repeat(codePoints - 1) + Character.toString(0x20000 + variant);
    }

    /**
     * The largest valid checkpoint: 256 cohorts and 4,096 markers, every token at 128 and every text at 512 four byte
     * code points, each marker carrying a supersedesSequence and the longest reason name, {@code PROVENANCE_MISMATCH}.
     */
    private static RoutingStateCheckpoint worstCase() {
        String token = wide(OpaqueTokens.MAX_CODE_POINTS, 0);
        var route = new RouteKey(token, Long.MAX_VALUE);
        List<CheckpointCohort> cohorts = new ArrayList<>(RoutingStateStore.MAX_COHORTS);
        for (int i = 0; i < RoutingStateStore.MAX_COHORTS; i++) {
            cohorts.add(new CheckpointCohort(new CohortKey(token, token, wide(OpaqueTokens.MAX_CODE_POINTS, i), route),
                    1.0, 1.0, 1.0, 1.0, 0));
        }
        var decision = new DecisionRef(token, token, token, token, token, Long.MAX_VALUE);
        var basis = new EntryBasis.NoUpdateMarker(NoUpdateReason.PROVENANCE_MISMATCH,
                new CohortBinding(token, token, token, token));
        List<AppliedRevision> markers = new ArrayList<>(RoutingStateStore.MAX_LEDGER);
        for (int i = 0; i < RoutingStateStore.MAX_LEDGER; i++) {
            markers.add(new AppliedRevision(
                    new FeedbackIdentity(wide(OpaqueTokens.MAX_TEXT_CODE_POINTS, i), 1, 1, decision, route, token,
                            token, token),
                    token, token, Feature.known(0L), token, Long.MAX_VALUE, Optional.empty(), Optional.empty(), basis,
                    FeedbackDisposition.NEUTRAL, 0.0));
        }
        return new RoutingStateCheckpoint(token, token, token, token, token, token, token, Long.MAX_VALUE, cohorts,
                new LedgerDigest(Long.MAX_VALUE, "f".repeat(64)), markers);
    }

    @Test
    void theCapIsTheMeasuredSizeOfTheLargestValidCheckpoint() {
        assertEquals(512, wide(OpaqueTokens.MAX_CODE_POINTS, 0).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(43_149_964, 3_724 + 256 * 2_108 + 4_096 * 10_402);
        var checkpoint = worstCase();
        byte[] encoded = CheckpointCodec.encode(checkpoint);
        assertEquals(CheckpointCodec.MAX_ENCODED_BYTES, encoded.length);

        var decoded = assertInstanceOf(CheckpointDecodeResult.Decoded.class, CheckpointCodec.decode(encoded));
        assertEquals(checkpoint, decoded.checkpoint());
        // one byte more is refused before any byte is read
        assertEquals(new CheckpointDecodeResult.Rejected(CheckpointRejectReason.OVERSIZE),
                CheckpointCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
