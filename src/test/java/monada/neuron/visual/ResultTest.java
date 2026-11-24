package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract Section 7: the typed result and rejection values; null is malformed and throws. */
class ResultTest {

    @Test
    void okAndRejectedAreValues() {
        var rejection = new VisualRejection(VisualRejectionReason.CAP_EXCEEDED);

        assertAll(
                () -> assertEquals(new Result.Ok<>("value"), new Result.Ok<>("value")),
                () -> assertEquals("value", new Result.Ok<>("value").value()),
                () -> assertEquals(new Result.Rejected<String>(rejection), new Result.Rejected<String>(rejection)),
                () -> assertEquals(VisualRejectionReason.CAP_EXCEEDED,
                        new Result.Rejected<String>(rejection).rejection().reason()));
    }

    @Test
    void nullComponentsThrowIllegalArgumentException() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Result.Ok<>(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Result.Rejected<>(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new VisualRejection(null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Canonicalized(null, VisualFixtures.IDENTITY)));
    }

    @Test
    void definesTheSixContractReasonsThenTheGridResolutionReason() {
        assertEquals("[SIZE_UNSUPPORTED, NOT_POWER_OF_TWO, LEVELS_UNSUPPORTED, CAP_EXCEEDED, COLOR_SPACE_MISMATCH, "
                + "TRANSFORM_OUT_OF_RANGE, BELOW_GRID_RESOLUTION]",
                Arrays.toString(VisualRejectionReason.values()));
    }
}
