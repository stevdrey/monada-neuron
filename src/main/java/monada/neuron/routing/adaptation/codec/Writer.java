package monada.neuron.routing.adaptation.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Big endian, length prefixed byte writer with the primitive rules of {@code routing-advice-receipt/1}. An own copy:
 * the receipt codec keeps its private one until a third format justifies a shared home.
 */
final class Writer {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
    private final ByteBuffer scratch = ByteBuffer.allocate(Long.BYTES);

    void u8(int value) {
        bytes.write(value);
    }

    void present(boolean value) {
        u8(value ? 1 : 0);
    }

    void i32(int value) {
        bytes.write(scratch.clear().putInt(value).array(), 0, Integer.BYTES);
    }

    void i64(long value) {
        bytes.write(scratch.clear().putLong(value).array(), 0, Long.BYTES);
    }

    // raw bits, so -0.0 stays -0.0; every value written is finite, so raw and canonical bits agree
    void f64(double value) {
        i64(Double.doubleToRawLongBits(value));
    }

    void str(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        i32(utf8.length);
        bytes.writeBytes(utf8);
    }

    void name(Enum<?> value) {
        str(value.name());
    }

    void raw(byte[] value) {
        bytes.writeBytes(value);
    }

    byte[] bytes() {
        return bytes.toByteArray();
    }
}
