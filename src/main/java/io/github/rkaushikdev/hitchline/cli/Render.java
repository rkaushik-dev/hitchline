package io.github.rkaushikdev.hitchline.cli;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Terminal output. Everything the command line prints goes through here so a report on a narrow
 * ssh window lines up the same way it does on a wide one, and so the tests can assert on a string
 * rather than on a stream. Widths are counted in code points.
 */
public final class Render {

    private static final String PAD = "  ";
    private static final int BAR_WIDTH = 10;

    private Render() {}

    private static int widthOf(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String pad(String text, int width, Column.Align align) {
        String gap = " ".repeat(Math.max(0, width - widthOf(text)));
        return align == Column.Align.RIGHT ? gap + text : text + gap;
    }

    /**
     * A table: the headers, a rule of dashes under each column, then one line per row. Each column
     * is as wide as its widest cell or its header, columns are two spaces apart, and no line ends
     * in white space.
     */
    public static <T> String renderTable(List<? extends T> rows, List<Column<T>> columns) {
        if (columns.isEmpty()) {
            throw ValidationException.field("columns", "must name at least one column");
        }
        List<List<String>> cells = new ArrayList<>();
        for (T row : rows) {
            cells.add(columns.stream().map(column -> column.cellOf(row)).toList());
        }
        int[] widths = new int[columns.size()];
        for (int index = 0; index < columns.size(); index += 1) {
            int widest = widthOf(columns.get(index).header());
            for (List<String> line : cells) {
                widest = Math.max(widest, widthOf(line.get(index)));
            }
            widths[index] = widest;
        }

        List<String> out = new ArrayList<>();
        List<String> header = new ArrayList<>();
        List<String> rule = new ArrayList<>();
        for (int index = 0; index < columns.size(); index += 1) {
            Column<T> column = columns.get(index);
            header.add(pad(column.header(), widths[index], column.align()));
            rule.add("-".repeat(widths[index]));
        }
        out.add(Texts.trimEnd(String.join(PAD, header)));
        out.add(Texts.trimEnd(String.join(PAD, rule)));
        for (List<String> line : cells) {
            List<String> padded = new ArrayList<>();
            for (int index = 0; index < columns.size(); index += 1) {
                padded.add(
                        pad(line.get(index), widths[index], columns.get(index).align()));
            }
            out.add(Texts.trimEnd(String.join(PAD, padded)));
        }
        return String.join("\n", out);
    }

    /** A short bar for a rate between zero and one, ten characters wide. */
    public static String bar(double fraction) {
        return bar(fraction, BAR_WIDTH);
    }

    /**
     * A bar {@code width} characters wide, filled with {@code #} in proportion to {@code fraction}
     * clamped to zero and one, the rest {@code .}. A value that is not a finite number draws a blank
     * bar.
     */
    public static String bar(double fraction, int width) {
        if (!Double.isFinite(fraction)) {
            return " ".repeat(width);
        }
        double clamped = Math.min(1, Math.max(0, fraction));
        int filled = (int) Numbers.round(clamped * width);
        return "#".repeat(filled) + ".".repeat(width - filled);
    }

    /** A rate as a whole percentage, right aligned in four characters; {@code    -} when it is not a number. */
    public static String percent(double fraction) {
        if (!Double.isFinite(fraction)) {
            return "   -";
        }
        return Texts.padStart(Numbers.format(Numbers.round(fraction * 100)), 3, ' ') + "%";
    }

    /** A heading underlined to its own width. */
    public static String heading(String text) {
        return text + "\n" + "=".repeat(widthOf(text));
    }

    /** One line per pair, the values lined up two spaces after the longest key. */
    public static String keyValues(List<Map.Entry<String, String>> pairs) {
        int width = 0;
        for (Map.Entry<String, String> pair : pairs) {
            width = Math.max(width, widthOf(pair.getKey()));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> pair : pairs) {
            out.add(pad(pair.getKey(), width, Column.Align.LEFT) + PAD + pair.getValue());
        }
        return String.join("\n", out);
    }

    /** A count and its noun, such as {@code 1 check in} or {@code 2 check ins}. */
    public static String pluralise(int count, String one) {
        return pluralise(count, one, one + "s");
    }

    /** A count and its noun, {@code one} for exactly one and {@code many} otherwise. */
    public static String pluralise(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
