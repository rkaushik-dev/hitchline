package io.github.rkaushikdev.hitchline.domain.time;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A date with no zone attached. Rotations are planned on these: a hitch starts on a date, not
 * at an instant, and the instant it starts at depends on where the crew member happens to be
 * standing. Only years 1900 to 2999 are supported, and every date that exists is a real one.
 *
 * @param year the year, 1900 to 2999
 * @param month the month, 1 to 12
 * @param day the day of the month
 */
public record CivilDate(int year, int month, int day) implements Comparable<CivilDate> {

    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    public CivilDate {
        if (year < 1900 || year > 2999) {
            throw ValidationException.field("year", "must be between 1900 and 2999, got " + year);
        }
        int limit = daysInMonth(year, month);
        if (day < 1 || day > limit) {
            throw ValidationException.field("day", "must be between 1 and " + limit + " for that month, got " + day);
        }
    }

    public static CivilDate of(int year, int month, int day) {
        return new CivilDate(year, month, day);
    }

    public static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    public static int daysInMonth(int year, int month) {
        if (month < 1 || month > 12) {
            throw ValidationException.field("month", "must be between 1 and 12, got " + month);
        }
        if (month == 2 && isLeapYear(year)) {
            return 29;
        }
        return DAYS_IN_MONTH[month - 1];
    }

    /** Reads {@code YYYY-MM-DD} and nothing looser. */
    public static CivilDate parse(String value) {
        Matcher match = ISO.matcher(value);
        if (!match.matches()) {
            throw ValidationException.field("date", "must be written as YYYY-MM-DD, got " + value);
        }
        return new CivilDate(
                Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
    }

    /** The date as {@code YYYY-MM-DD}. */
    @Override
    public String toString() {
        return year + "-" + Texts.twoDigits(month) + "-" + Texts.twoDigits(day);
    }

    /** Days since 1970-01-01, negative before it. */
    public int epochDay() {
        return Math.toIntExact(LocalDate.of(year, month, day).toEpochDay());
    }

    public static CivilDate ofEpochDay(long epochDay) {
        LocalDate moment = LocalDate.ofEpochDay(epochDay);
        return new CivilDate(moment.getYear(), moment.getMonthValue(), moment.getDayOfMonth());
    }

    public CivilDate plusDays(long days) {
        return ofEpochDay((long) epochDay() + days);
    }

    /** Moves by whole months, clamping the day when it lands on a shorter month. */
    public CivilDate plusMonths(int months) {
        int zeroBased = year * 12 + (month - 1) + months;
        int newYear = Math.floorDiv(zeroBased, 12);
        int newMonth = Math.floorMod(zeroBased, 12) + 1;
        return new CivilDate(newYear, newMonth, Math.min(day, daysInMonth(newYear, newMonth)));
    }

    /** Days from {@code from} to {@code to}, negative when {@code to} comes first. */
    public static int daysBetween(CivilDate from, CivilDate to) {
        return to.epochDay() - from.epochDay();
    }

    /** Orders two dates, answering exactly -1, 0 or 1. */
    public static int compare(CivilDate left, CivilDate right) {
        return Integer.signum(left.epochDay() - right.epochDay());
    }

    @Override
    public int compareTo(CivilDate other) {
        return compare(this, other);
    }

    public boolean isSameDay(CivilDate other) {
        return compare(this, other) == 0;
    }

    public boolean isBefore(CivilDate other) {
        return compare(this, other) < 0;
    }

    public boolean isAfter(CivilDate other) {
        return compare(this, other) > 0;
    }

    /** Inclusive on both ends, because rosters are quoted that way. */
    public boolean isWithin(CivilDate from, CivilDate to) {
        return !isBefore(from) && !isAfter(to);
    }

    public static CivilDate min(CivilDate left, CivilDate right) {
        return left.isBefore(right) ? left : right;
    }

    public static CivilDate max(CivilDate left, CivilDate right) {
        return left.isAfter(right) ? left : right;
    }

    /** The ISO weekday: Monday is 1 and Sunday is 7. */
    public int dayOfWeek() {
        return LocalDate.of(year, month, day).getDayOfWeek().getValue();
    }

    public boolean isWeekend() {
        return dayOfWeek() >= 6;
    }

    public CivilDate startOfIsoWeek() {
        return plusDays(1L - dayOfWeek());
    }

    public CivilDate endOfIsoWeek() {
        return startOfIsoWeek().plusDays(6);
    }

    public CivilDate startOfMonth() {
        return new CivilDate(year, month, 1);
    }

    public CivilDate endOfMonth() {
        return new CivilDate(year, month, daysInMonth(year, month));
    }

    /** Every day from {@code from} to {@code to} inclusive; nothing when the pair is reversed. */
    public static List<CivilDate> eachDay(CivilDate from, CivilDate to) {
        List<CivilDate> days = new ArrayList<>();
        int last = to.epochDay();
        for (int cursor = from.epochDay(); cursor <= last; cursor += 1) {
            days.add(ofEpochDay(cursor));
        }
        return days;
    }

    /** The number of days an inclusive span covers, zero when it is reversed. */
    public static int dayCount(CivilDate from, CivilDate to) {
        return Math.max(0, daysBetween(from, to) + 1);
    }
}
