package io.github.rkaushikdev.hitchline.domain.time;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zones and instants. A zone is named by its tz database id, such as {@code Europe/Oslo}, and
 * kept as that string. The arithmetic lives here rather than in the modules that need it,
 * because a crew member changes zone mid rotation and every reading of "what day is it for them"
 * has to agree. Instants are {@link Instant}s at millisecond precision.
 */
public final class Zones {

    private static final long MINUTE_MS = 60_000;
    private static final long DAY_MS = 24 * 60 * MINUTE_MS;
    private static final Map<String, ZoneId> ZONES = new ConcurrentHashMap<>();
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * An ISO 8601 timestamp: a date, optionally followed by a time of minute, second or fraction
     * precision and an offset. A date alone is midnight UTC, and a time with no offset is read on
     * this machine's clock.
     */
    private static final Pattern TIMESTAMP = Pattern.compile("([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?"
            + "(?:T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?(Z|[+-]\\d{2}:\\d{2})?)?");

    private Zones() {}

    private static ZoneId resolve(String zone) {
        ZoneId cached = ZONES.get(zone);
        if (cached != null) {
            return cached;
        }
        ZoneId created;
        try {
            created = ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw ValidationException.field("zone", "is not a timezone this platform knows: " + zone);
        }
        ZONES.put(zone, created);
        return created;
    }

    public static boolean isKnownZone(String zone) {
        try {
            resolve(zone);
            return true;
        } catch (ValidationException unknown) {
            return false;
        }
    }

    /** Checks that {@code raw} names a zone and hands it back. */
    public static String zoneId(String raw) {
        resolve(raw);
        return raw;
    }

    /** The instant {@code epochMs} milliseconds after the epoch, any fraction dropped. */
    public static Instant instant(double epochMs) {
        if (Double.isNaN(epochMs) || Double.isInfinite(epochMs)) {
            throw ValidationException.field("instant", "must be a finite number of milliseconds");
        }
        return Instant.ofEpochMilli((long) epochMs);
    }

    public static Instant ofEpochMilli(long epochMs) {
        return Instant.ofEpochMilli(epochMs);
    }

    /** Reads an ISO 8601 timestamp, such as {@code 2025-01-20T09:00:00Z}. */
    public static Instant parseInstant(String value) {
        Matcher match = TIMESTAMP.matcher(value);
        if (!match.matches()) {
            throw unreadable(value);
        }
        try {
            int year = Integer.parseInt(match.group(1));
            int month = match.group(2) == null ? 1 : Integer.parseInt(match.group(2));
            int day = match.group(3) == null ? 1 : Integer.parseInt(match.group(3));
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                throw unreadable(value);
            }
            LocalDate date = LocalDate.of(year, month, 1).plusDays(day - 1L);
            if (match.group(4) == null) {
                return date.atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            int hour = Integer.parseInt(match.group(4));
            int minute = Integer.parseInt(match.group(5));
            int second = match.group(6) == null ? 0 : Integer.parseInt(match.group(6));
            int millis = match.group(7) == null
                    ? 0
                    : Integer.parseInt(Texts.padEnd(match.group(7), 3, '0').substring(0, 3));
            boolean endOfDay = hour == 24 && minute == 0 && second == 0 && millis == 0;
            if ((hour > 23 && !endOfDay) || minute > 59 || second > 59) {
                throw unreadable(value);
            }
            LocalDateTime wall = date.atTime(endOfDay ? 0 : hour, minute, second, millis * 1_000_000)
                    .plusDays(endOfDay ? 1 : 0);
            String offset = match.group(8);
            if (offset == null) {
                return wall.atZone(ZoneId.systemDefault()).toInstant();
            }
            return wall.toInstant(offset.equals("Z") ? ZoneOffset.UTC : ZoneOffset.of(offset));
        } catch (DateTimeException outOfRange) {
            throw unreadable(value);
        }
    }

    private static ValidationException unreadable(String value) {
        return ValidationException.field("instant", "must be an iso timestamp, got " + value);
    }

    /** The instant in UTC with milliseconds, such as {@code 2025-01-20T09:00:00.000Z}. */
    public static String formatInstant(Instant at) {
        return ISO_MILLIS.format(at);
    }

    private static LocalDateTime wallIn(String zone, Instant at) {
        return LocalDateTime.ofInstant(at, resolve(zone));
    }

    /** Minutes east of UTC at that instant, so Oslo in summer is 120. */
    public static int offsetMinutesAt(String zone, Instant at) {
        int seconds = resolve(zone).getRules().getOffset(at).getTotalSeconds();
        return (int) Math.floor(seconds / 60.0 + 0.5);
    }

    public static CivilDate civilDateIn(String zone, Instant at) {
        LocalDateTime wall = wallIn(zone, at);
        return new CivilDate(wall.getYear(), wall.getMonthValue(), wall.getDayOfMonth());
    }

    public static ClockTime clockTimeIn(String zone, Instant at) {
        LocalDateTime wall = wallIn(zone, at);
        return new ClockTime(wall.getHour(), wall.getMinute());
    }

    private static long wallAsUtc(CivilDate date, ClockTime time) {
        return LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute())
                        .toEpochSecond(ZoneOffset.UTC)
                * 1000;
    }

    /**
     * Pushes a wall clock reading back onto the timeline. A time inside a spring forward gap is
     * reported as {@link ZonedOutcome#SKIPPED} and answered with the instant the offset in force
     * before the change puts it at, which is past the gap: what a person tapping the button at
     * that moment actually means. A time inside an autumn overlap takes the first pass through.
     */
    public static ZonedResolution resolveZoned(String zone, CivilDate date, ClockTime time) {
        long asUtc = wallAsUtc(date, time);
        int before = offsetMinutesAt(zone, Instant.ofEpochMilli(asUtc - DAY_MS));
        int after = offsetMinutesAt(zone, Instant.ofEpochMilli(asUtc + DAY_MS));
        long withBefore = asUtc - before * MINUTE_MS;
        long withAfter = asUtc - after * MINUTE_MS;

        List<Long> valid = new ArrayList<>();
        if (holds(zone, asUtc, withBefore)) {
            valid.add(withBefore);
        }
        if (withAfter != withBefore && holds(zone, asUtc, withAfter)) {
            valid.add(withAfter);
        }
        if (valid.size() == 1) {
            return resolution(zone, valid.get(0), ZonedOutcome.EXACT);
        }
        if (valid.size() > 1) {
            return resolution(zone, Math.min(valid.get(0), valid.get(1)), ZonedOutcome.AMBIGUOUS);
        }
        return resolution(zone, withBefore, ZonedOutcome.SKIPPED);
    }

    private static boolean holds(String zone, long asUtc, long candidate) {
        return offsetMinutesAt(zone, Instant.ofEpochMilli(candidate)) * MINUTE_MS == asUtc - candidate;
    }

    private static ZonedResolution resolution(String zone, long epochMs, ZonedOutcome outcome) {
        Instant at = Instant.ofEpochMilli(epochMs);
        return new ZonedResolution(at, outcome, offsetMinutesAt(zone, at));
    }

    public static Instant zonedInstant(String zone, CivilDate date, ClockTime time) {
        return resolveZoned(zone, date, time).instant();
    }

    public static Instant startOfDayIn(String zone, CivilDate date) {
        return zonedInstant(zone, date, ClockTime.MIDNIGHT);
    }

    /** The local date, time and offset, such as {@code 2025-01-15 17:30+05:30}. */
    public static String describeZoned(String zone, Instant at) {
        int offset = offsetMinutesAt(zone, at);
        String sign = offset < 0 ? "-" : "+";
        int absolute = Math.abs(offset);
        return civilDateIn(zone, at) + " " + clockTimeIn(zone, at) + sign + Texts.twoDigits(absolute / 60) + ":"
                + Texts.twoDigits(absolute % 60);
    }

    /**
     * The instants a local day covers. A day is not always twenty four hours: it is twenty three
     * when the clocks go forward and twenty five when they go back, and a report that assumes
     * otherwise drops or double counts an hour of check ins twice a year.
     */
    public static DayBounds dayBoundsIn(String zone, CivilDate date) {
        return new DayBounds(startOfDayIn(zone, date), startOfDayIn(zone, date.plusDays(1)));
    }

    public static int dayLengthMinutes(String zone, CivilDate date) {
        DayBounds bounds = dayBoundsIn(zone, date);
        double minutes = (bounds.to().toEpochMilli() - bounds.from().toEpochMilli()) / (double) MINUTE_MS;
        return (int) Math.floor(minutes + 0.5);
    }

    /** True when the offset changes at some point during the local day. */
    public static boolean hasTransition(String zone, CivilDate date) {
        return dayLengthMinutes(zone, date) != ClockTime.MINUTES_IN_DAY;
    }
}
