package io.github.rkaushikdev.hitchline.domain.time;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A time on a wall clock, with no date and no zone. Quiet hours, muster times and reminder
 * windows are all quoted like this by the people who set them.
 *
 * @param hour the hour, 0 to 23
 * @param minute the minute, 0 to 59
 */
public record ClockTime(int hour, int minute) implements Comparable<ClockTime> {

    public static final int MINUTES_IN_DAY = 24 * 60;
    public static final ClockTime MIDNIGHT = new ClockTime(0, 0);

    private static final Pattern PATTERN = Pattern.compile("([01]\\d|2[0-3]):([0-5]\\d)");

    public ClockTime {
        if (hour < 0 || hour > 23) {
            throw ValidationException.field("hour", "must be between 0 and 23, got " + hour);
        }
        if (minute < 0 || minute > 59) {
            throw ValidationException.field("minute", "must be between 0 and 59, got " + minute);
        }
    }

    public static ClockTime of(int hour, int minute) {
        return new ClockTime(hour, minute);
    }

    /** Reads {@code HH:MM} on a 24 hour clock, both parts zero padded. */
    public static ClockTime parse(String value) {
        Matcher match = PATTERN.matcher(value);
        if (!match.matches()) {
            throw ValidationException.field("time", "must be written as HH:MM, got " + value);
        }
        return new ClockTime(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
    }

    /** The time as {@code HH:MM}. */
    @Override
    public String toString() {
        return Texts.twoDigits(hour) + ":" + Texts.twoDigits(minute);
    }

    public int minutesSinceMidnight() {
        return hour * 60 + minute;
    }

    /** The time {@code minutes} after midnight, wrapping in either direction. */
    public static ClockTime fromMinutesSinceMidnight(int minutes) {
        int wrapped = Math.floorMod(minutes, MINUTES_IN_DAY);
        return new ClockTime(wrapped / 60, wrapped % 60);
    }

    /** Wraps at midnight. The caller finds out how far it wrapped from {@link #dayShift}. */
    public ClockTime plusMinutes(int minutes) {
        return fromMinutesSinceMidnight(minutesSinceMidnight() + minutes);
    }

    /** How many days adding {@code minutes} moves across, negative going backwards. */
    public int dayShift(int minutes) {
        return Math.floorDiv(minutesSinceMidnight() + minutes, MINUTES_IN_DAY);
    }

    /** Orders two times, answering exactly -1, 0 or 1. */
    public static int compare(ClockTime left, ClockTime right) {
        return Integer.signum(left.minutesSinceMidnight() - right.minutesSinceMidnight());
    }

    @Override
    public int compareTo(ClockTime other) {
        return compare(this, other);
    }
}
