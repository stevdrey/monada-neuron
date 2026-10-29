package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SpectralFrame;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable, caller-owned model snapshot of the next-concept predictor (contract {@code ncm-wave-cognition/2}
 * sections 3.3, 8.2 and 10.2, ADR 0043 and ADR 0045). It is built by {@link ConceptModelTrainer} from host-labeled
 * sequences and is never updated in place: Neuron keeps nothing after a call, and {@link ConceptFeedbackPolicy}
 * derives a new snapshot with the next {@link #revision()} from verified outcomes, leaving this one untouched.
 *
 * <p><b>Content.</b>
 * <ul>
 *   <li>the {@link ConceptHierarchy} and the expected DFT {@link SpectralFrame}: evidence with any other frame or
 *       pattern version is {@link AbstentionReason#INCOMPATIBLE_INPUT};</li>
 *   <li>one spectral centroid per concept (an inner concept pools every exemplar of its leaves), unit-normalized, in
 *       one contiguous {@code double[concepts × bins]} array, the norm of each centroid's exemplar sum, and the number
 *       of exemplars behind each; a concept with no exemplar has a zero centroid and is never scored;</li>
 *   <li>leaf-to-leaf transition counts ({@code long[leaves × leaves]}, row = current leaf) and the leaf marginal
 *       counts, from consecutive labeled windows;</li>
 *   <li>the number of verified feedback outcomes absorbed per leaf, which bounds further feedback;</li>
 *   <li>the softmax temperature over centroid similarities, the per-depth {@link ConceptThresholds} and the
 *       revision ({@code 0} when trained, one more per accepted feedback batch).</li>
 * </ul>
 *
 * <p>Memory is {@code 8 · concepts · bins} bytes for the centroids plus {@code 8 · leaves²} for the transitions:
 * about 4.2 MB at the largest bounds (256 concepts, 2,049 bins), 6.5 kB for the medium workload. {@link
 * #retainedBytes()} reports the payload. {@link #fingerprint()} names the exact snapshot a prediction came from.
 */
public final class ConceptModel {

    /** Format of the canonical encoding behind {@link #fingerprint()}. */
    public static final String FINGERPRINT_FORMAT = "ncm-concept-model/1";

    private static final HexFormat HEX = HexFormat.of();

    private final ConceptHierarchy hierarchy;
    private final SpectralFrame frame;
    private final int bins;
    private final double[] centroids;
    private final double[] centroidNorms;
    private final long[] exemplarCounts;
    private final long[] transitions;
    private final long[] leafCounts;
    private final long[] feedbackCounts;
    private final int scorableConcepts;
    private final double temperature;
    private final ConceptThresholds thresholds;
    private final long revision;
    private volatile String fingerprint;

    ConceptModel(ConceptHierarchy hierarchy, SpectralFrame frame, double[] centroids, double[] centroidNorms,
                 long[] exemplarCounts, long[] transitions, long[] leafCounts, long[] feedbackCounts,
                 double temperature, ConceptThresholds thresholds, long revision) {
        this.hierarchy = hierarchy;
        this.frame = frame;
        this.bins = frame.componentCapacity();
        this.centroids = centroids;
        this.centroidNorms = centroidNorms;
        this.exemplarCounts = exemplarCounts;
        this.transitions = transitions;
        this.leafCounts = leafCounts;
        this.feedbackCounts = feedbackCounts;
        int scorable = 0;
        for (long count : exemplarCounts) {
            if (count > 0) {
                scorable++;
            }
        }
        this.scorableConcepts = scorable;
        this.temperature = temperature;
        this.thresholds = thresholds;
        this.revision = revision;
    }

    /**
     * Returns this snapshot with other thresholds and the same revision; the trained data is shared, not copied. The
     * fingerprint differs, because thresholds change what the snapshot predicts.
     *
     * @param thresholds thresholds chosen on non-test data
     * @return the new snapshot
     */
    public ConceptModel withThresholds(ConceptThresholds thresholds) {
        Objects.requireNonNull(thresholds, "thresholds must not be null");
        return new ConceptModel(hierarchy, frame, centroids, centroidNorms, exemplarCounts, transitions, leafCounts,
                feedbackCounts, temperature, thresholds, revision);
    }

    /** Returns the hierarchy. */
    public ConceptHierarchy hierarchy() {
        return hierarchy;
    }

    /** Returns the expected spectral frame of the evidence. */
    public SpectralFrame frame() {
        return frame;
    }

    /** Returns the softmax temperature over centroid similarities. */
    public double temperature() {
        return temperature;
    }

    /** Returns the per-depth thresholds. */
    public ConceptThresholds thresholds() {
        return thresholds;
    }

    /** Returns the revision: {@code 0} when trained, one more per accepted feedback batch. */
    public long revision() {
        return revision;
    }

    /** Returns the number of non-silent exemplars behind a concept's centroid. */
    public long exemplarCount(int ordinal) {
        return exemplarCounts[Objects.checkIndex(ordinal, exemplarCounts.length)];
    }

    /** Returns the number of concepts with at least one exemplar: the comparisons of one prediction. */
    public int scorableConcepts() {
        return scorableConcepts;
    }

    /** Returns the number of labeled windows of a leaf, including silent ones and verified feedback. */
    public long leafCount(int leafOrdinal) {
        return leafCounts[requireLeaf(leafOrdinal)];
    }

    /** Returns the number of verified feedback outcomes absorbed by a leaf. */
    public long feedbackCount(int leafOrdinal) {
        return feedbackCounts[requireLeaf(leafOrdinal)];
    }

    /** Returns how often leaf {@code to} followed leaf {@code from} in the training sequences and verified feedback. */
    public long transitionCount(int from, int to) {
        return transitions[requireLeaf(from) * hierarchy.leafCount() + requireLeaf(to)];
    }

    /** Returns a copy of a concept's unit centroid, zero for a concept without exemplars. */
    public double[] centroid(int ordinal) {
        int offset = Objects.checkIndex(ordinal, hierarchy.size()) * bins;
        return Arrays.copyOfRange(centroids, offset, offset + bins);
    }

    /** Returns the bytes of the learned arrays (centroids and their norms, exemplar, transition, leaf and feedback counts). */
    public long retainedBytes() {
        return 8L * (centroids.length + centroidNorms.length + exemplarCounts.length + transitions.length
                + leafCounts.length + feedbackCounts.length);
    }

    /**
     * Returns the lower-case hex SHA-256 of the snapshot's canonical encoding ({@link #FINGERPRINT_FORMAT}): format,
     * hierarchy, frame, every learned value by its exact bits, temperature, the thresholds at every depth and the
     * revision. Equal fingerprints mean the same predictions; it is an identity and equivalence check, not an
     * authentication boundary. Computed once, on first use.
     */
    public String fingerprint() {
        String value = fingerprint;
        if (value == null) {
            value = HEX.formatHex(encodeDigest());
            fingerprint = value;
        }
        return value;
    }

    int bins() {
        return bins;
    }

    double[] centroidsView() {
        return centroids;
    }

    double[] centroidNormsView() {
        return centroidNorms;
    }

    long[] exemplarCountsView() {
        return exemplarCounts;
    }

    long[] transitionsView() {
        return transitions;
    }

    long[] leafCountsView() {
        return leafCounts;
    }

    long[] feedbackCountsView() {
        return feedbackCounts;
    }

    private int requireLeaf(int ordinal) {
        int position = hierarchy.leafPosition(ordinal);
        if (position < 0) {
            throw new IllegalArgumentException("concept " + ordinal + " is not a leaf");
        }
        return position;
    }

    private byte[] encodeDigest() {
        MessageDigest digest = newDigest();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        putText(buffer, FINGERPRINT_FORMAT);
        buffer.putInt(hierarchy.domain()).putInt(hierarchy.size());
        for (ConceptNode node : hierarchy.nodes()) {
            buffer = room(digest, buffer, 16);
            buffer.putLong(node.conceptCode()).putInt(node.parentOrdinal()).putInt(node.depth());
        }
        buffer = room(digest, buffer, 256);
        putText(buffer, frame.basis().name());
        putText(buffer, frame.unit().name());
        buffer.putInt(frame.scale().levels()).putInt(frame.sampleCount())
                .putLong(Double.doubleToLongBits(frame.sampleRate())).putInt(bins);
        for (double value : centroids) {
            buffer = room(digest, buffer, 8);
            buffer.putLong(Double.doubleToLongBits(value));
        }
        for (double value : centroidNorms) {
            buffer = room(digest, buffer, 8);
            buffer.putLong(Double.doubleToLongBits(value));
        }
        for (long[] counts : new long[][] {exemplarCounts, transitions, leafCounts, feedbackCounts}) {
            for (long value : counts) {
                buffer = room(digest, buffer, 8);
                buffer.putLong(value);
            }
        }
        buffer = room(digest, buffer, 8 + 16 * ConceptHierarchy.MAX_DEPTH + 8);
        buffer.putLong(Double.doubleToLongBits(temperature));
        for (int depth = 0; depth < ConceptHierarchy.MAX_DEPTH; depth++) {
            buffer.putLong(Double.doubleToLongBits(thresholds.confidence(depth)))
                    .putLong(Double.doubleToLongBits(thresholds.margin(depth)));
        }
        buffer.putLong(revision);
        digest.update(buffer.flip());
        return digest.digest();
    }

    private void putText(ByteBuffer buffer, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length).put(bytes);
    }

    private ByteBuffer room(MessageDigest digest, ByteBuffer buffer, int bytes) {
        if (buffer.remaining() < bytes) {
            digest.update(buffer.flip());
            buffer.clear();
        }
        return buffer;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConceptModel that && revision == that.revision && hierarchy.equals(that.hierarchy)
                && frame.equals(that.frame) && Arrays.equals(centroids, that.centroids)
                && Arrays.equals(centroidNorms, that.centroidNorms)
                && Arrays.equals(exemplarCounts, that.exemplarCounts)
                && Arrays.equals(transitions, that.transitions) && Arrays.equals(leafCounts, that.leafCounts)
                && Arrays.equals(feedbackCounts, that.feedbackCounts)
                && Double.compare(temperature, that.temperature) == 0 && thresholds.equals(that.thresholds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hierarchy, frame, Arrays.hashCode(centroids), Arrays.hashCode(transitions), temperature,
                thresholds, revision);
    }

    @Override
    public String toString() {
        return "ConceptModel[" + hierarchy + ", frame=" + frame + ", temperature=" + temperature + ", "
                + thresholds + ", revision=" + revision + "]";
    }
}
