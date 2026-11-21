package io.github.rkaushikdev.hitchline.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The order names are listed in: the Unicode root collation, compared at three levels. First the
 * base characters, where white space sorts before punctuation, punctuation before symbols and
 * digits, and digits before letters, with case and accents ignored; then the accents, an
 * unaccented letter first; then the case, lower before upper. Spaces and punctuation count, so
 * "Call home" sorts before "Callhome". {@link java.text.Collator} is not used because it ignores
 * spaces and hyphens, which would reorder names that differ only there.
 */
public final class Collation {

    /** Names in root collation order. */
    public static final Comparator<String> ORDER = Collation::compare;

    /** Punctuation and symbols, in root collation order. */
    private static final String MARKS = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";

    /** Letters that sort as another letter, or two, with an accent of their own. */
    private static final Map<Character, String> FOLDED = Map.of(
            'ø', "o",
            'æ', "ae",
            'œ', "oe",
            'ß', "ss",
            'đ', "d",
            'ł', "l",
            'ı', "i",
            'ð', "d");

    private static final int SPACE = 1;
    private static final int MARK = 2;
    private static final int OTHER = 3;
    private static final int DIGIT = 4;
    private static final int LETTER = 5;
    private static final int SCRIPT = 6;

    private Collation() {}

    /** The three levels of weights for one string. */
    private record Weights(List<Long> primary, List<Integer> secondary, List<Integer> tertiary) {}

    private static Weights weigh(String text) {
        List<Long> primary = new ArrayList<>();
        List<Integer> secondary = new ArrayList<>();
        List<Integer> tertiary = new ArrayList<>();
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        int index = 0;
        while (index < decomposed.length()) {
            int codePoint = decomposed.codePointAt(index);
            index += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                if (!secondary.isEmpty()) {
                    int last = secondary.size() - 1;
                    secondary.set(last, secondary.get(last) * 31 + codePoint);
                }
                continue;
            }
            int lower = Character.toLowerCase(codePoint);
            int caseWeight = lower == codePoint ? 0 : 1;
            String folded = lower <= 0xFFFF ? FOLDED.get((char) lower) : null;
            if (folded != null) {
                for (int at = 0; at < folded.length(); at += 1) {
                    primary.add(weight(folded.charAt(at)));
                    secondary.add(at == 0 ? 1 : 0);
                    tertiary.add(caseWeight);
                }
                continue;
            }
            primary.add(weight(lower));
            secondary.add(0);
            tertiary.add(caseWeight);
        }
        return new Weights(primary, secondary, tertiary);
    }

    private static long weight(int codePoint) {
        if (Texts.isSpace(codePoint)) {
            return group(SPACE, codePoint);
        }
        int mark = codePoint <= 0xFFFF ? MARKS.indexOf(codePoint) : -1;
        if (mark >= 0) {
            return group(MARK, mark);
        }
        if (codePoint >= '0' && codePoint <= '9') {
            return group(DIGIT, codePoint - '0');
        }
        if (codePoint >= 'a' && codePoint <= 'z') {
            return group(LETTER, codePoint - 'a');
        }
        if (Character.isLetter(codePoint)) {
            return group(SCRIPT, codePoint);
        }
        if (Character.isDigit(codePoint)) {
            return group(DIGIT, Character.digit(codePoint, 10));
        }
        return group(OTHER, codePoint);
    }

    private static long group(int level, long position) {
        return ((long) level << 32) | position;
    }

    private static <T extends Comparable<T>> int compareLists(List<T> left, List<T> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index += 1) {
            int order = left.get(index).compareTo(right.get(index));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    /** Orders two strings as {@link Collation} describes, answering a negative, zero or positive number. */
    public static int compare(String left, String right) {
        Weights first = weigh(left);
        Weights second = weigh(right);
        int order = compareLists(first.primary(), second.primary());
        if (order != 0) {
            return order;
        }
        order = compareLists(first.secondary(), second.secondary());
        if (order != 0) {
            return order;
        }
        return compareLists(first.tertiary(), second.tertiary());
    }

    /** The case folded form used to find text inside a name, independent of the machine's locale. */
    public static String fold(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
