package io.github.rkaushikdev.hitchline.cli;

import java.util.Objects;
import java.util.function.Function;

/**
 * One column of a printed table: its header, how a row's cell is written, and which side the cell
 * lines up on.
 *
 * @param header the text over the column
 * @param value the cell text for a row
 * @param align the side cells are lined up on
 * @param <T> the row type
 */
public record Column<T>(String header, Function<? super T, String> value, Align align) {

    /** Which side of the column a cell is lined up on. */
    public enum Align {
        LEFT,
        RIGHT
    }

    public Column {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(align, "align");
    }

    /** A column of text, lined up on the left. */
    public static <T> Column<T> left(String header, Function<? super T, String> value) {
        return new Column<>(header, value, Align.LEFT);
    }

    /** A column of numbers, lined up on the right. */
    public static <T> Column<T> right(String header, Function<? super T, String> value) {
        return new Column<>(header, value, Align.RIGHT);
    }

    String cellOf(T row) {
        return value.apply(row);
    }
}
