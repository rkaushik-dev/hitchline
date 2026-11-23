package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many misses a run may carry. A shield is granted for a reason somebody signs off; a freeze
 * is spent by the person themselves and there are only so many. The difference matters: a shield
 * says nothing was owed, a freeze says something was owed and missed but the run survives it.
 *
 * @param allowance how many misses a run may absorb inside the window, from 0 to 10
 * @param windowDays days the allowance is counted over, ending on the day being scored, 1 to 365
 */
public record FreezeBudget(int allowance, int windowDays) {

    /** One miss in any 28 days. */
    public static final FreezeBudget DEFAULT = new FreezeBudget(1, 28);

    private static final Pattern WRITTEN = Pattern.compile("(\\d{1,6})/(\\d{1,6})");

    public FreezeBudget {
        if (allowance < 0 || allowance > 10) {
            throw ValidationException.field("allowance", "must be a whole number from 0 to 10");
        }
        if (windowDays < 1 || windowDays > 365) {
            throw ValidationException.field("windowDays", "must be a whole number of days from 1 to 365");
        }
    }

    /** Reads a budget written the way a crew says it, such as {@code 1/28} for one miss in 28 days. */
    public static FreezeBudget parse(String text) {
        Matcher match = WRITTEN.matcher(Texts.trim(text));
        if (!match.matches()) {
            throw ValidationException.field("freezes", "must look like 1/28, got " + text);
        }
        return new FreezeBudget(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
    }

    /** The budget as it is written, such as {@code 1/28}. */
    public String format() {
        return allowance + "/" + windowDays;
    }

    public static FreezeBudget of(int allowance, int windowDays) {
        return new FreezeBudget(allowance, windowDays);
    }

    public FreezeBudget withAllowance(int value) {
        return new FreezeBudget(value, windowDays);
    }

    public FreezeBudget withWindowDays(int value) {
        return new FreezeBudget(allowance, value);
    }
}
