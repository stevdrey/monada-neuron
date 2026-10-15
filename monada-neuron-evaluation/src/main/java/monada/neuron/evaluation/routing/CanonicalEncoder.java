package monada.neuron.evaluation.routing;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * A deterministic, injective byte encoding of a value tree for digests: every value is a one-byte type tag followed by
 * its content, and every text is prefixed by its UTF-8 byte length, so no opaque token can run into its neighbour
 * whatever separators it contains.
 *
 * <p>Records are walked through their components (class name, component count, then each component's name and
 * value), never through {@code toString}. Lists and arrays keep their order. Sets and maps are encoded with their
 * elements (or entries) sorted by the elements' own encodings, so a collection's iteration order can never change a
 * digest. Doubles and floats are encoded as their exact bit patterns, and {@code BigDecimal} keeps its scale. Any other
 * type is rejected rather than rendered as text.
 *
 * <p>Time and space are linear in the size of the encoding, plus {@code O(n log n)} per set or map of {@code n}
 * elements. Stateless; used only outside measured windows.
 */
final class CanonicalEncoder {

    /** The encoding of {@code value}. */
    byte[] encode(Object value) {
        var out = new ByteArrayOutputStream();
        write(value, out);
        return out.toByteArray();
    }

    private void write(Object value, ByteArrayOutputStream out) {
        switch (value) {
            case null -> out.write('Z');
            case String text -> leaf('S', text, out);
            case Long number -> leaf('l', number.toString(), out);
            case Integer number -> leaf('i', number.toString(), out);
            case Short number -> leaf('s', number.toString(), out);
            case Byte number -> leaf('b', number.toString(), out);
            case Character character -> leaf('c', character.toString(), out);
            case Boolean flag -> leaf('B', flag.toString(), out);
            case Double number -> leaf('F', Long.toHexString(Double.doubleToRawLongBits(number)), out);
            case Float number -> leaf('f', Integer.toHexString(Float.floatToRawIntBits(number)), out);
            case BigDecimal decimal -> leaf('D', decimal.toString(), out);
            case BigInteger integer -> leaf('G', integer.toString(), out);
            case UUID uuid -> leaf('U', uuid.toString(), out);
            case Enum<?> constant -> {
                out.write('E');
                text(constant.getDeclaringClass().getName(), out);
                text(constant.name(), out);
            }
            case Optional<?> optional -> {
                out.write('O');
                out.write(optional.isPresent() ? '1' : '0');
                if (optional.isPresent()) {
                    write(optional.get(), out);
                }
            }
            case OptionalInt optional -> {
                out.write('o');
                out.write(optional.isPresent() ? '1' : '0');
                if (optional.isPresent()) {
                    write(optional.getAsInt(), out);
                }
            }
            case OptionalLong optional -> {
                out.write('q');
                out.write(optional.isPresent() ? '1' : '0');
                if (optional.isPresent()) {
                    write(optional.getAsLong(), out);
                }
            }
            case OptionalDouble optional -> {
                out.write('d');
                out.write(optional.isPresent() ? '1' : '0');
                if (optional.isPresent()) {
                    write(optional.getAsDouble(), out);
                }
            }
            case Record record -> record(record, out);
            case List<?> list -> {
                out.write('L');
                text(Integer.toString(list.size()), out);
                list.forEach(element -> write(element, out));
            }
            case Set<?> set -> sorted('T', set, out);
            case Map<?, ?> map -> {
                var entries = new ArrayList<byte[]>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    var pair = new ByteArrayOutputStream();
                    write(entry.getKey(), pair);
                    write(entry.getValue(), pair);
                    entries.add(pair.toByteArray());
                }
                sortedBytes('M', entries, out);
            }
            case Object[] array -> {
                out.write('A');
                text(Integer.toString(array.length), out);
                for (Object element : array) {
                    write(element, out);
                }
            }
            case int[] array -> {
                out.write('a');
                text(Integer.toString(array.length), out);
                for (int element : array) {
                    write(element, out);
                }
            }
            case long[] array -> {
                out.write('g');
                text(Integer.toString(array.length), out);
                for (long element : array) {
                    write(element, out);
                }
            }
            default -> throw new IllegalArgumentException("no canonical encoding for " + value.getClass().getName());
        }
    }

    private void record(Record record, ByteArrayOutputStream out) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        out.write('R');
        text(record.getClass().getName(), out);
        text(Integer.toString(components.length), out);
        for (RecordComponent component : components) {
            text(component.getName(), out);
            write(read(record, component.getAccessor()), out);
        }
    }

    private Object read(Record record, Method accessor) {
        try {
            if (!accessor.canAccess(record)) {
                accessor.setAccessible(true);
            }
            return accessor.invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + accessor, e);
        }
    }

    private void sorted(int tag, Collection<?> elements, ByteArrayOutputStream out) {
        var encoded = new ArrayList<byte[]>(elements.size());
        for (Object element : elements) {
            encoded.add(encode(element));
        }
        sortedBytes(tag, encoded, out);
    }

    private void sortedBytes(int tag, List<byte[]> encoded, ByteArrayOutputStream out) {
        encoded.sort(Arrays::compareUnsigned);
        out.write(tag);
        text(Integer.toString(encoded.size()), out);
        encoded.forEach(out::writeBytes);
    }

    private void leaf(int tag, String content, ByteArrayOutputStream out) {
        out.write(tag);
        text(content, out);
    }

    /** {@code <utf8-byte-length>:<bytes>}. */
    private void text(String content, ByteArrayOutputStream out) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        out.write(':');
        out.writeBytes(bytes);
    }
}
