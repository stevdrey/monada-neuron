package monada.neuron.resonance.adapter;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Deterministic interim decoder mapping recalled content to an {@link SignalKind#INTERMEDIATE}
 * Signal through a SHA-256 digest of its UTF-8 bytes.
 *
 * <p>Amplitude is in {@code [0, 1]}, frequency in {@code [0, 100]}, and phase in {@code [0, 2π)}.
 * This is a reproducible placeholder, not a semantic reconstruction; supply a domain decoder through
 * {@link ResonanceStoreAdapterConfig}.
 */
public final class HashedSignalDecoder implements RecalledSignalDecoder {

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double UNSIGNED_INT_RANGE = 4_294_967_296.0;

    /** Derives a finite frequency state from the digest of the content. */
    @Override
    public Signal decode(String content) {
        Objects.requireNonNull(content, "content must not be null");
        var digest = sha256(content.getBytes(StandardCharsets.UTF_8));
        var amplitude = unitInterval(digest, 0);
        var frequency = unitInterval(digest, 4) * 100.0;
        var phase = unitInterval(digest, 8) * TWO_PI;
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, frequency, phase));
    }

    private double unitInterval(byte[] digest, int offset) {
        var bits = 0L;
        for (var index = 0; index < 4; index++) {
            bits = (bits << 8) | (digest[offset + index] & 0xFFL);
        }
        return bits / UNSIGNED_INT_RANGE;
    }

    private byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
