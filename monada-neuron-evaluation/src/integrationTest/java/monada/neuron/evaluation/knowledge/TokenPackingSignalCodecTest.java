package monada.neuron.evaluation.knowledge;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenPackingSignalCodecTest {

    private final TokenPackingSignalCodec codec = new TokenPackingSignalCodec();

    @Test
    void everyDistinctTokenRoundTripsInFirstOccurrenceOrder() {
        var signals = codec.querySignals("What does the <fieldset> element do? The fieldset groups 0123456789abcdefghij");

        assertEquals(List.of("what", "does", "the", "fieldset", "element", "do", "groups", "0123456789abcdefghij"),
                signals.stream().map(codec::encode).toList());
        assertEquals(signals.size(), new HashSet<>(signals).size());
        signals.forEach(signal -> assertEquals(SignalKind.OBSERVATION, signal.kind()));
    }

    @Test
    void packingIsAFunctionOfTheTextOnly() {
        assertEquals(codec.querySignals("Alt text?"), new TokenPackingSignalCodec().querySignals("alt  TEXT"));
        assertEquals(List.of("a"), codec.querySignals("a").stream().map(codec::encode).toList());
        assertEquals(List.of("0"), codec.querySignals("0").stream().map(codec::encode).toList());
        assertEquals(List.of("aaaaaaaaaa", "aaaaaaaaaab"),
                codec.querySignals("aaaaaaaaaa aaaaaaaaaab").stream().map(codec::encode).toList());
    }

    @Test
    void textWithoutTokensBecomesTheEmptyQuery() {
        for (var blank : List.of("", "   ", "?!")) {
            var signals = codec.querySignals(blank);
            assertEquals(1, signals.size(), blank);
            assertEquals("", codec.encode(signals.getFirst()));
        }
    }

    @Test
    void unpackableTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.querySignals("élément"));
        assertThrows(IllegalArgumentException.class, () -> codec.querySignals("a".repeat(21)));
        var tooMany = new StringBuilder();
        for (var i = 0; i <= TokenPackingSignalCodec.MAX_QUERY_TOKENS; i++) {
            tooMany.append("t").append(i).append(' ');
        }
        assertThrows(IllegalArgumentException.class, () -> codec.querySignals(tooMany.toString()));
    }

    @Test
    void foreignSignalsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 2.0, 0.0))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new Signal(SignalKind.OBSERVATION, new FrequencyState(0.5, 2.0, 0.0))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 2.5, 0.0))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 37.0, 0.0))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 1.0, 1.0))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 1e300, 0.0))));
    }
}
