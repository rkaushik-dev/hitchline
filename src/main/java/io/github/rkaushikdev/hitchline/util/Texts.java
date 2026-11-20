package io.github.rkaushikdev.hitchline.util;

import java.util.ArrayList;
import java.util.List;

/**
 * String operations whose exact behaviour the stored formats and printed reports depend on.
 * Every caller goes through these rather than the nearest JDK call, because the JDK versions
 * differ at the edges: {@link String#split} takes a regex and drops trailing empty parts, and
 * {@link String#trim} stops at U+0020.
 */
public final class Texts {

    private Texts() {}

    /**
     * Splits on a literal separator and keeps every part, empty ones included, so {@code "a,,b,"}
     * gives four parts. An empty separator splits into single UTF-16 units.
     */
    public static List<String> split(String value, String separator) {
        List<String> parts = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int index = 0; index < value.length(); index += 1) {
                parts.add(String.valueOf(value.charAt(index)));
            }
            return parts;
        }
        int start = 0;
        while (true) {
            int found = value.indexOf(separator, start);
            if (found < 0) {
                parts.add(value.substring(start));
                return parts;
            }
            parts.add(value.substring(start, found));
            start = found + separator.length();
        }
    }

    /**
     * True for the characters {@link #trim} removes: the Unicode space separators, the line and
     * paragraph separators, tab, line feed, vertical tab, form feed, carriage return, no-break
     * space and the byte order mark.
     */
    public static boolean isSpace(int codePoint) {
        return switch (codePoint) {
            case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF ->
                true;
            default -> codePoint >= 0x2000 && codePoint <= 0x200A;
        };
    }

    /** Removes leading and trailing white space, as {@link #isSpace} defines it. */
    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start += 1;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end -= 1;
        }
        return value.substring(start, end);
    }

    /** Pads {@code value} on the left with {@code fill} up to {@code width} UTF-16 units. */
    public static String padStart(String value, int width, char fill) {
        if (value.length() >= width) {
            return value;
        }
        return String.valueOf(fill).repeat(width - value.length()) + value;
    }

    /** Pads {@code value} on the right with {@code fill} up to {@code width} UTF-16 units. */
    public static String padEnd(String value, int width, char fill) {
        if (value.length() >= width) {
            return value;
        }
        return value + String.valueOf(fill).repeat(width - value.length());
    }

    /** Two digits, zero padded, the way dates and clock times are written. */
    public static String twoDigits(int value) {
        return padStart(Integer.toString(value), 2, '0');
    }
}
