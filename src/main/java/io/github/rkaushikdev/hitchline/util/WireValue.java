package io.github.rkaushikdev.hitchline.util;

import java.util.Arrays;
import java.util.Optional;

/**
 * A closed set of values each written as one fixed word, such as a day role or a visibility.
 * The word is what appears in stored documents, responses and command output.
 */
public interface WireValue {

    /** The value as it is written. */
    String wire();

    /** The value of {@code type} written as {@code wire}, if there is one. */
    static <E extends Enum<E> & WireValue> Optional<E> fromWire(Class<E> type, String wire) {
        return Arrays.stream(type.getEnumConstants())
                .filter(value -> value.wire().equals(wire))
                .findFirst();
    }

    /** Every word of {@code type}, in declaration order, joined with {@code separator}. */
    static <E extends Enum<E> & WireValue> String join(Class<E> type, String separator) {
        return String.join(
                separator,
                Arrays.stream(type.getEnumConstants()).map(WireValue::wire).toList());
    }
}
