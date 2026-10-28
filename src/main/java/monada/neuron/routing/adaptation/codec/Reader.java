package monada.neuron.routing.adaptation.codec;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Bounds checked reader with the primitive rules of {@code routing-advice-receipt/1}. Every length and count is checked
 * as an {@code int} against its bound and the bytes remaining before anything is allocated or the position moves. Any
 * structural fault is a {@link Fault} carrying {@link CheckpointRejectReason#MALFORMED}.
 *
 * <p>The readable bytes end at {@link #limit}: the whole input until the checksum is confirmed, then the input without
 * its 32 checksum bytes, so no field can read into them.
 */
final class Reader {

    private static final HexFormat HEX = HexFormat.of();

    private final byte[] bytes;
    private final ByteBuffer buffer;
    private final CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private int position;
    private int end;

    Reader(byte[] bytes) {
        this.bytes = bytes;
        this.buffer = ByteBuffer.wrap(bytes);
        this.end = bytes.length;
    }

    int remaining() {
        return end - position;
    }

    /** Stops reading {@code count} bytes before the end of the input. */
    void limit(int count) {
        end = bytes.length - count;
    }

    void end() {
        if (position != end) {
            throw malformed();
        }
    }

    int u8() {
        need(1);
        return bytes[position++] & 0xFF;
    }

    boolean present() {
        int value = u8();
        if (value > 1) {
            throw malformed();
        }
        return value == 1;
    }

    int i32() {
        need(Integer.BYTES);
        int value = buffer.getInt(position);
        position += Integer.BYTES;
        return value;
    }

    long i64() {
        need(Long.BYTES);
        long value = buffer.getLong(position);
        position += Long.BYTES;
        return value;
    }

    double f64() {
        return Double.longBitsToDouble(i64());
    }

    UUID uuid() {
        long most = i64();
        long least = i64();
        return new UUID(most, least);
    }

    /** Lowercase hexadecimal of the next {@code count} raw bytes. */
    String hex(int count) {
        need(count);
        String value = HEX.formatHex(bytes, position, position + count);
        position += count;
        return value;
    }

    int count(int max) {
        int count = i32();
        if (count < 0 || count > max) {
            throw malformed();
        }
        return count;
    }

    String str(int maxBytes) {
        int length = i32();
        if (length < 1 || length > maxBytes || length > end - position) {
            throw malformed();
        }
        String value;
        try {
            value = utf8.decode(buffer.slice(position, length)).toString();
        } catch (CharacterCodingException e) {
            throw malformed();
        }
        position += length;
        return value;
    }

    // a name table, not valueOf: a miss here is a structural fault, never an IllegalArgumentException
    <E extends Enum<E>> E constant(E[] constants, int maxBytes) {
        String name = str(maxBytes);
        for (E constant : constants) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        throw malformed();
    }

    private void need(int count) {
        if (count > end - position) {
            throw malformed();
        }
    }

    static Fault malformed() {
        return new Fault(CheckpointRejectReason.MALFORMED);
    }

    /** A structural fault; carries only the reason and records no stack trace. */
    static final class Fault extends RuntimeException {

        final CheckpointRejectReason reason;

        Fault(CheckpointRejectReason reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }
}
