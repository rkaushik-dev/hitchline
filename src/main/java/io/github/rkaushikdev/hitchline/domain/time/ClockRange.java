package io.github.rkaushikdev.hitchline.domain.time;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;

/**
 * A stretch of the wall clock, closed at the start and open at the end. A range may wrap past
 * midnight, and most rest windows do.
 *
 * @param from the first minute inside the range
 * @param to the first minute after it
 */
public record ClockRange(ClockTime from, ClockTime to) {

    public ClockRange {
        if (ClockTime.compare(from, to) == 0) {
            throw ValidationException.field("range", "must not start and end at the same minute");
        }
    }

    public static ClockRange of(ClockTime from, ClockTime to) {
        return new ClockRange(from, to);
    }

    /** Reads {@code HH:MM-HH:MM}. */
    public static ClockRange parse(String value) {
        List<String> parts = Texts.split(value, "-");
        if (parts.size() != 2) {
            throw ValidationException.field("range", "must be written as HH:MM-HH:MM, got " + value);
        }
        return new ClockRange(ClockTime.parse(parts.get(0)), ClockTime.parse(parts.get(1)));
    }

    /** The range as {@code HH:MM-HH:MM}. */
    @Override
    public String toString() {
        return from + "-" + to;
    }

    public boolean wrapsMidnight() {
        return ClockTime.compare(from, to) > 0;
    }

    public int lengthMinutes() {
        int start = from.minutesSinceMidnight();
        int end = to.minutesSinceMidnight();
        return end > start ? end - start : ClockTime.MINUTES_IN_DAY - start + end;
    }

    public boolean contains(ClockTime time) {
        int at = time.minutesSinceMidnight();
        int start = from.minutesSinceMidnight();
        int end = to.minutesSinceMidnight();
        if (wrapsMidnight()) {
            return at >= start || at < end;
        }
        return at >= start && at < end;
    }

    public boolean overlaps(ClockRange other) {
        return contains(other.from) || other.contains(from) || equals(other);
    }
}
