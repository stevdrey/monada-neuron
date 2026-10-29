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
import monada.neuron.routing.feedback.PeerRevision;
import monada.neuron.routing.token.OpaqueTokens;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The {@value #FORMAT} writer and its strict reader: every component of a {@link RoutingStateCheckpoint} as bytes,
 * ending with the SHA-256 of everything before it, so a host can keep learned routing state in storage it owns. Both
 * operations are pure functions of their input, so class-level. The byte format is normative in
 * {@code docs/specs/routing-checkpoint-v1.md}.
 *
 * <p>The checksum is integrity, not authenticity: it proves the bytes are unchanged, not who wrote them, because anyone
 * holding the bytes can compute a valid checksum for any checkpoint. A host that must know the writer adds its own
 * signature or authenticated storage outside Neuron. Checkpoint bytes hold host identifiers and learned state, so
 * Neuron never logs, persists or transports them.
 *
 * <p>Decoding fails closed in the order the format fixes: size, tag, checksum, structure (every field is parsed into raw
 * values and no record is built), then the records' own validation, then canonical form (the built checkpoint must
 * encode to the input byte for byte). Only {@code IllegalArgumentException} and {@code NullPointerException}, the
 * exceptions the records throw for bad values, are caught while building; anything else is a codec bug and surfaces.
 */
public final class CheckpointCodec {

    /** Version of the byte format. */
    public static final String FORMAT = "routing-checkpoint/1";

    /**
     * The encoded size of the largest valid checkpoint: {@value RoutingStateStore#MAX_COHORTS} cohorts and
     * {@value RoutingStateStore#MAX_LEDGER} markers with every token and text at its byte bound. Decode refuses longer
     * input before reading a byte. At this size decoding holds up to about six times the input on the heap at its peak,
     * counting the caller's array, as {@code docs/specs/routing-checkpoint-v1.md} Section 3 itemizes.
     */
    public static final int MAX_ENCODED_BYTES = 43_149_964;

    private static final String TAG_PREFIX = "routing-checkpoint/";
    private static final String SUPPORTED_MAJOR = "1";
    private static final int MAX_TAG_BYTES = 64;
    // a token is at most 128 code points and a text at most 512, each of at most 4 UTF-8 bytes
    private static final int MAX_TOKEN_BYTES = OpaqueTokens.MAX_CODE_POINTS * 4;
    private static final int MAX_TEXT_BYTES = OpaqueTokens.MAX_TEXT_CODE_POINTS * 4;
    private static final int CHECKSUM_BYTES = 32;
    private static final int DIGEST_SUM_BYTES = 32;
    private static final int NO_UPDATE_MARKER = 2;

    private static final HexFormat HEX = HexFormat.of();
    private static final NoUpdateReason[] REASONS = NoUpdateReason.values();
    private static final FeedbackDisposition[] DISPOSITIONS = FeedbackDisposition.values();

    private CheckpointCodec() {
    }

    /**
     * The canonical encoding of a checkpoint. Checkpoints equal in every component, each marker's {@code cutoff}
     * included, give identical bytes. {@code O(N)} in the encoded size.
     *
     * @return a new array of at most {@link #MAX_ENCODED_BYTES} bytes
     * @throws IllegalStateException if a marker is a derived entry or the encoding exceeds {@link #MAX_ENCODED_BYTES};
     *         a valid checkpoint can do neither
     */
    public static byte[] encode(RoutingStateCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        byte[] encoded = write(checkpoint);
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalStateException("encoding exceeds MAX_ENCODED_BYTES");
        }
        return encoded;
    }

    /**
     * Reads a checkpoint strictly, never repairing input. {@code O(N)} in the input size.
     *
     * @return {@code Decoded} with a checkpoint equal to the encoded one in every component, or {@code Rejected} with
     *         the first failure in the format's check order
     */
    public static CheckpointDecodeResult decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (bytes.length > MAX_ENCODED_BYTES) {
            return new CheckpointDecodeResult.Rejected(CheckpointRejectReason.OVERSIZE);
        }
        // parse a private copy, so a caller changing its array during the call cannot split checksum from parse
        byte[] input = bytes.clone();
        Supplier<RoutingStateCheckpoint> build;
        try {
            var in = new Reader(input);
            tag(in);
            checksum(in, input);
            build = body(in);
            in.end();
        } catch (Reader.Fault fault) {
            return new CheckpointDecodeResult.Rejected(fault.reason);
        }
        RoutingStateCheckpoint checkpoint;
        try {
            checkpoint = build.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            // the message may echo host tokens, so it is dropped
            return new CheckpointDecodeResult.Rejected(CheckpointRejectReason.INVALID_CHECKPOINT);
        }
        if (!Arrays.equals(write(checkpoint), input)) {
            return new CheckpointDecodeResult.Rejected(CheckpointRejectReason.NON_CANONICAL);
        }
        return new CheckpointDecodeResult.Decoded(checkpoint);
    }

    private static byte[] write(RoutingStateCheckpoint checkpoint) {
        var out = new Writer();
        out.str(FORMAT);
        out.str(checkpoint.scopeId());
        out.str(checkpoint.featureSchemaVersion());
        out.str(checkpoint.evaluationPolicyId());
        out.str(checkpoint.evaluationPolicyVersion());
        out.str(checkpoint.mappingVersion());
        out.str(checkpoint.policyId());
        out.str(checkpoint.policyVersion());
        out.i64(checkpoint.processedCutoff());
        out.i32(checkpoint.preferenceSnapshot().size());
        for (CheckpointCohort cohort : checkpoint.preferenceSnapshot()) {
            CohortKey key = cohort.key();
            out.str(key.scopeId());
            out.str(key.stageKind());
            out.str(key.cohortBucket());
            key(out, key.route());
            out.f64(cohort.amplitude());
            out.f64(cohort.frequency());
            out.f64(cohort.phase());
            out.f64(cohort.energy());
            out.i32(cohort.supportingObservations());
        }
        out.i64(checkpoint.ledgerDigest().entries());
        out.raw(HEX.parseHex(checkpoint.ledgerDigest().sum()));
        out.i32(checkpoint.retainedMarkers().size());
        for (AppliedRevision marker : checkpoint.retainedMarkers()) {
            marker(out, marker);
        }
        byte[] body = out.bytes();
        byte[] encoded = Arrays.copyOf(body, body.length + CHECKSUM_BYTES);
        System.arraycopy(sha256().digest(body), 0, encoded, body.length, CHECKSUM_BYTES);
        return encoded;
    }

    private static void marker(Writer out, AppliedRevision marker) {
        if (!(marker.basis() instanceof EntryBasis.NoUpdateMarker basis)) {
            throw new IllegalStateException(FORMAT + " has no derived entries");
        }
        FeedbackIdentity identity = marker.identity();
        out.str(identity.experienceRef());
        out.i64(identity.revision());
        out.i64(identity.ledgerSequence());
        DecisionRef decision = identity.decisionRef();
        out.str(decision.scopeId());
        out.str(decision.taskId());
        out.str(decision.executionId());
        out.str(decision.attemptId());
        out.str(decision.stageId());
        out.i64(decision.requestOrdinal());
        key(out, identity.route());
        out.str(identity.mappingVersion());
        out.str(identity.evaluationPolicyId());
        out.str(identity.evaluationPolicyVersion());
        out.str(marker.routingPolicyId());
        out.str(marker.routingPolicyVersion());
        out.present(marker.supersedesSequence() instanceof Feature.Known<Long>);
        if (marker.supersedesSequence() instanceof Feature.Known<Long> known) {
            out.i64(known.value());
        }
        out.str(marker.catalogVersion());
        out.i64(marker.cutoff());
        out.present(marker.targetNodeId().isPresent());
        marker.targetNodeId().ifPresent(id -> {
            out.i64(id.getMostSignificantBits());
            out.i64(id.getLeastSignificantBits());
        });
        out.present(marker.costPeer().isPresent());
        marker.costPeer().ifPresent(peer -> {
            out.str(peer.experienceRef());
            out.i64(peer.revision());
            out.i64(peer.ledgerSequence());
        });
        out.u8(NO_UPDATE_MARKER);
        out.name(basis.reason());
        CohortBinding binding = basis.cohortBinding();
        out.str(binding.cohortBucket());
        out.str(binding.bucketMappingVersion());
        out.str(binding.mappingVersion());
        out.str(binding.featureSchemaVersion());
        out.name(marker.disposition());
        out.f64(marker.score());
    }

    private static void key(Writer out, RouteKey key) {
        out.str(key.routeId());
        out.i64(key.routeVersion());
    }

    private static void tag(Reader in) {
        String tag = in.str(MAX_TAG_BYTES);
        if (!tag.startsWith(TAG_PREFIX)) {
            throw Reader.malformed();
        }
        String major = tag.substring(TAG_PREFIX.length());
        boolean digits = !major.isEmpty() && major.chars().allMatch(c -> c >= '0' && c <= '9');
        if (!digits || (major.length() > 1 && major.charAt(0) == '0')) {
            throw Reader.malformed();
        }
        // compared as a digit string, never parsed, so no major can overflow
        if (!major.equals(SUPPORTED_MAJOR)) {
            throw new Reader.Fault(CheckpointRejectReason.UNSUPPORTED_VERSION);
        }
    }

    private static void checksum(Reader in, byte[] input) {
        if (in.remaining() < CHECKSUM_BYTES) {
            throw Reader.malformed();
        }
        int bodyLength = input.length - CHECKSUM_BYTES;
        MessageDigest sha = sha256();
        sha.update(input, 0, bodyLength);
        if (!MessageDigest.isEqual(sha.digest(), Arrays.copyOfRange(input, bodyLength, input.length))) {
            throw new Reader.Fault(CheckpointRejectReason.CORRUPT);
        }
        in.limit(CHECKSUM_BYTES);
    }

    private static Supplier<RoutingStateCheckpoint> body(Reader in) {
        String scopeId = in.str(MAX_TOKEN_BYTES);
        String featureSchemaVersion = in.str(MAX_TOKEN_BYTES);
        String evaluationPolicyId = in.str(MAX_TOKEN_BYTES);
        String evaluationPolicyVersion = in.str(MAX_TOKEN_BYTES);
        String mappingVersion = in.str(MAX_TOKEN_BYTES);
        String policyId = in.str(MAX_TOKEN_BYTES);
        String policyVersion = in.str(MAX_TOKEN_BYTES);
        long processedCutoff = in.i64();
        int cohortCount = in.count(RoutingStateStore.MAX_COHORTS);
        List<Supplier<CheckpointCohort>> cohorts = new ArrayList<>(cohortCount);
        for (int i = 0; i < cohortCount; i++) {
            cohorts.add(cohort(in));
        }
        long entries = in.i64();
        String sum = in.hex(DIGEST_SUM_BYTES);
        int markerCount = in.count(RoutingStateStore.MAX_LEDGER);
        List<Supplier<AppliedRevision>> markers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            markers.add(marker(in));
        }
        return () -> new RoutingStateCheckpoint(scopeId, featureSchemaVersion, evaluationPolicyId,
                evaluationPolicyVersion, mappingVersion, policyId, policyVersion, processedCutoff, all(cohorts).get(),
                new LedgerDigest(entries, sum), all(markers).get());
    }

    private static Supplier<CheckpointCohort> cohort(Reader in) {
        String scopeId = in.str(MAX_TOKEN_BYTES);
        String stageKind = in.str(MAX_TOKEN_BYTES);
        String cohortBucket = in.str(MAX_TOKEN_BYTES);
        Supplier<RouteKey> route = key(in);
        double amplitude = in.f64();
        double frequency = in.f64();
        double phase = in.f64();
        double energy = in.f64();
        int supportingObservations = in.i32();
        return () -> new CheckpointCohort(new CohortKey(scopeId, stageKind, cohortBucket, route.get()), amplitude,
                frequency, phase, energy, supportingObservations);
    }

    private static Supplier<AppliedRevision> marker(Reader in) {
        String experienceRef = in.str(MAX_TEXT_BYTES);
        long revision = in.i64();
        long ledgerSequence = in.i64();
        String scopeId = in.str(MAX_TOKEN_BYTES);
        String taskId = in.str(MAX_TOKEN_BYTES);
        String executionId = in.str(MAX_TOKEN_BYTES);
        String attemptId = in.str(MAX_TOKEN_BYTES);
        String stageId = in.str(MAX_TOKEN_BYTES);
        long requestOrdinal = in.i64();
        Supplier<RouteKey> route = key(in);
        String mappingVersion = in.str(MAX_TOKEN_BYTES);
        String evaluationPolicyId = in.str(MAX_TOKEN_BYTES);
        String evaluationPolicyVersion = in.str(MAX_TOKEN_BYTES);
        String routingPolicyId = in.str(MAX_TOKEN_BYTES);
        String routingPolicyVersion = in.str(MAX_TOKEN_BYTES);
        Feature<Long> supersedesSequence = in.present() ? Feature.known(in.i64()) : Feature.unknown();
        String catalogVersion = in.str(MAX_TOKEN_BYTES);
        long cutoff = in.i64();
        Optional<UUID> targetNodeId = in.present() ? Optional.of(in.uuid()) : Optional.empty();
        Supplier<Optional<PeerRevision>> costPeer;
        if (in.present()) {
            String peerRef = in.str(MAX_TEXT_BYTES);
            long peerRevision = in.i64();
            long peerSequence = in.i64();
            costPeer = () -> Optional.of(new PeerRevision(peerRef, peerRevision, peerSequence));
        } else {
            costPeer = Optional::empty;
        }
        if (in.u8() != NO_UPDATE_MARKER) {
            throw Reader.malformed();
        }
        NoUpdateReason reason = in.constant(REASONS, MAX_TOKEN_BYTES);
        String cohortBucket = in.str(MAX_TOKEN_BYTES);
        String bucketMappingVersion = in.str(MAX_TOKEN_BYTES);
        String bindingMappingVersion = in.str(MAX_TOKEN_BYTES);
        String featureSchemaVersion = in.str(MAX_TOKEN_BYTES);
        FeedbackDisposition disposition = in.constant(DISPOSITIONS, MAX_TOKEN_BYTES);
        double score = in.f64();
        return () -> new AppliedRevision(
                new FeedbackIdentity(experienceRef, revision, ledgerSequence,
                        new DecisionRef(scopeId, taskId, executionId, attemptId, stageId, requestOrdinal),
                        route.get(), mappingVersion, evaluationPolicyId, evaluationPolicyVersion),
                routingPolicyId, routingPolicyVersion, supersedesSequence, catalogVersion, cutoff, targetNodeId,
                costPeer.get(),
                new EntryBasis.NoUpdateMarker(reason,
                        new CohortBinding(cohortBucket, bucketMappingVersion, bindingMappingVersion,
                                featureSchemaVersion)),
                disposition, score);
    }

    private static Supplier<RouteKey> key(Reader in) {
        String routeId = in.str(MAX_TOKEN_BYTES);
        long routeVersion = in.i64();
        return () -> new RouteKey(routeId, routeVersion);
    }

    private static <T> Supplier<List<T>> all(List<Supplier<T>> parts) {
        return () -> parts.stream().map(Supplier::get).toList();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
