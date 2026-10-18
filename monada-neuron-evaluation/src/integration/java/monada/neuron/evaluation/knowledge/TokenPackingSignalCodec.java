package monada.neuron.evaluation.knowledge;

import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.adapter.SignalQueryEncoder;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluation-only, stateless L2 query codec ({@value #NAME} v{@value #VERSION}): a function of the query text only,
 * as the protocol's L2 codec rule requires. It holds no table and never sees query ids or gold labels.
 *
 * <p>{@link #querySignals(String)} turns every distinct token of the text (the protocol's token rule, in order of first
 * occurrence) into one {@link SignalKind#OBSERVATION} Signal of amplitude {@code 1.0} whose frequency and phase pack the
 * token losslessly: the first ten characters in the frequency and the next ten in the phase, each as the base-37
 * number {@code Σ (digit_i + 1) · 37^i} over {@code [0-9a-z]} (no zero digit, so no padding ambiguity), exact in a {@code double} because
 * {@code 37^10 < 2^53}. {@link #encode(Signal)} unpacks a Signal back to its token, so the adapter sends one Store query
 * per token and merges their candidates. A text without tokens becomes a single Signal of frequency and phase
 * {@code 0}, which encodes to the empty query, for which the Store returns no result; a blank query therefore still
 * travels through the real port.
 *
 * <p>Fail-closed: a token outside {@code [0-9a-z]}, longer than {@value #MAX_TOKEN_LENGTH} characters, or more than
 * {@value #MAX_QUERY_TOKENS} distinct tokens is rejected, and {@link #encode(Signal)} rejects any Signal this codec
 * cannot have produced; the adapter propagates that exception as an operational failure instead of querying
 * with invented text.
 */
final class TokenPackingSignalCodec implements SignalQueryEncoder {

    /** Codec name recorded in the report. */
    static final String NAME = "token-packing";

    /** Codec version recorded in the report; bump with any change to the packing below. */
    static final String VERSION = "1";

    /** Largest number of distinct tokens, and so query Signals, per query. */
    static final int MAX_QUERY_TOKENS = 32;

    /** Longest packable token. */
    static final int MAX_TOKEN_LENGTH = 20;

    private static final int CHARS_PER_FIELD = 10;
    private static final int RADIX = 37;
    private static final double AMPLITUDE = 1.0;
    private static final long MAX_FIELD = maxField();

    /** Returns a description of the codec's parameters for the report. */
    static String describe() {
        return NAME + " v" + VERSION + ": one OBSERVATION(" + AMPLITUDE + ", f, p) Signal per distinct token "
                + "(Character.isLetterOrDigit runs, Locale.ROOT lowercase, first-occurrence order, at most "
                + MAX_QUERY_TOKENS + "); token chars 1-" + CHARS_PER_FIELD + " in f and " + (CHARS_PER_FIELD + 1)
                + "-" + MAX_TOKEN_LENGTH + " in p as base-" + RADIX + " sum of (digit+1)*37^i over [0-9a-z]; no tokens -> one "
                + "OBSERVATION(" + AMPLITUDE + ", 0, 0) encoding the empty query; encode(signal) = unpacked token";
    }

    /**
     * Returns the query Signals of {@code text}.
     *
     * @throws IllegalArgumentException when the text has a token this codec cannot pack, or too many tokens
     */
    List<Signal> querySignals(String text) {
        var tokens = HtmlTokens.distinct(Objects.requireNonNull(text, "text must not be null"));
        if (tokens.size() > MAX_QUERY_TOKENS) {
            throw new IllegalArgumentException(tokens.size() + " distinct tokens exceed " + MAX_QUERY_TOKENS);
        }
        if (tokens.isEmpty()) {
            return List.of(signal(0, 0));
        }
        var signals = new ArrayList<Signal>(tokens.size());
        for (var token : tokens) {
            if (token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException("token '" + token + "' exceeds " + MAX_TOKEN_LENGTH + " characters");
            }
            var split = Math.min(token.length(), CHARS_PER_FIELD);
            signals.add(signal(pack(token.substring(0, split)), pack(token.substring(split))));
        }
        return List.copyOf(signals);
    }

    @Override
    public String encode(Signal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        var state = signal.frequencyState();
        if (signal.kind() != SignalKind.OBSERVATION || state.amplitude() != AMPLITUDE) {
            throw new IllegalArgumentException("not a " + NAME + " query Signal: " + signal);
        }
        var head = unpack(state.frequency(), signal);
        var tail = unpack(state.phase(), signal);
        if (head.length() < CHARS_PER_FIELD && !tail.isEmpty()) {
            throw new IllegalArgumentException("non-canonical " + NAME + " query Signal: " + signal);
        }
        return head + tail;
    }

    private static Signal signal(long frequency, long phase) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(AMPLITUDE, frequency, phase));
    }

    private static long pack(String chars) {
        var value = 0L;
        for (var i = chars.length() - 1; i >= 0; i--) {
            value = value * RADIX + digit(chars.charAt(i)) + 1;
        }
        return value;
    }

    private static String unpack(double field, Signal signal) {
        if (field < 0 || field > MAX_FIELD || field != Math.rint(field)) {
            throw new IllegalArgumentException("not a " + NAME + " query Signal: " + signal);
        }
        var value = (long) field;
        var chars = new StringBuilder(CHARS_PER_FIELD);
        while (value > 0) {
            chars.append(character((int) (value % RADIX)));
            value /= RADIX;
        }
        return chars.toString();
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'z') {
            return 10 + c - 'a';
        }
        throw new IllegalArgumentException("token character '" + c + "' is outside [0-9a-z]");
    }

    private static char character(int packedDigit) {
        if (packedDigit == 0) {
            throw new IllegalArgumentException("digit 0 inside a packed token");
        }
        var digit = packedDigit - 1;
        return digit < 10 ? (char) ('0' + digit) : (char) ('a' + digit - 10);
    }

    private static long maxField() {
        var value = 0L;
        for (var i = 0; i < CHARS_PER_FIELD; i++) {
            value = value * RADIX + RADIX - 1;
        }
        return value;
    }
}
