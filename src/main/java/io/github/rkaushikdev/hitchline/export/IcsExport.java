package io.github.rkaushikdev.hitchline.export;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Calendar export in iCalendar form (RFC 5545). A crew member who lives in their phone calendar
 * wants the hitch and what it asks for to show up there, so each obligation becomes an all day
 * event on the day it falls due.
 */
public final class IcsExport {

    private static final String CRLF = "\r\n";
    private static final int MAX_OCTETS = 75;
    private static final Pattern LINE_BREAK = Pattern.compile("\r?\n");
    private static final Pattern FRACTION = Pattern.compile("\\.\\d{3}");

    private IcsExport() {}

    /**
     * What a calendar is called and stamped with.
     *
     * @param name the calendar's name
     * @param stampedAt when it was made
     * @param domain the domain event ids are made unique in; {@code hitchline.local} when absent
     */
    public record CalendarOptions(String name, Instant stampedAt, Optional<String> domain) {

        public static CalendarOptions of(String name, Instant stampedAt) {
            return new CalendarOptions(name, stampedAt, Optional.empty());
        }
    }

    /**
     * One all day event.
     *
     * @param uid the event's id
     * @param summary its title
     * @param description its text
     * @param from its day
     * @param to the day after it, which is how an all day event ends in the format
     */
    public record CalendarEvent(String uid, String summary, String description, CivilDate from, CivilDate to) {}

    /** Escapes the four characters the format reserves inside a text value. */
    public static String icsText(String value) {
        String escaped = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
        return LINE_BREAK.matcher(escaped).replaceAll("\\\\n");
    }

    /** A date as {@code YYYYMMDD}. */
    public static String icsDate(CivilDate date) {
        return date.toString().replace("-", "");
    }

    /** An instant as {@code YYYYMMDDTHHMMSSZ}. */
    public static String icsStamp(Instant at) {
        String iso = Zones.formatInstant(at).replace("-", "").replace(":", "");
        return FRACTION.matcher(iso).replaceFirst("");
    }

    /** The UTF-8 length of one code point; a lone surrogate counts as the three bytes U+FFFD takes. */
    private static int octets(int codePoint) {
        if (Character.isSurrogate((char) codePoint) && Character.charCount(codePoint) == 1) {
            return 3;
        }
        return new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Folds a line to 75 octets, continuing with a leading space. Counting is in octets rather
     * than characters because a name with an accent in it is two bytes and calendars that count
     * wrong drop the last character.
     */
    public static String foldLine(String line) {
        int total = line.codePoints().map(IcsExport::octets).sum();
        if (total <= MAX_OCTETS) {
            return line;
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int width = 0;
        int index = 0;
        while (index < line.length()) {
            int codePoint = line.codePointAt(index);
            index += Character.charCount(codePoint);
            int size = octets(codePoint);
            int limit = parts.isEmpty() ? MAX_OCTETS : MAX_OCTETS - 1;
            if (width + size > limit) {
                parts.add(current.toString());
                current.setLength(0);
                width = 0;
            }
            current.appendCodePoint(codePoint);
            width += size;
        }
        parts.add(current.toString());
        return String.join(CRLF + " ", parts);
    }

    /** The event for one obligation, on the day it falls due. */
    public static CalendarEvent eventFor(Habit habit, Obligation obligation) {
        String times = obligation.times() == 1 ? "" : " (" + obligation.times() + " times)";
        return new CalendarEvent(
                obligation.key(),
                habit.name() + times,
                "owed per " + obligation.scope() + ", window "
                        + obligation.window().from() + " to "
                        + obligation.window().to(),
                obligation.window().to(),
                obligation.window().to().plusDays(1));
    }

    /** A whole calendar, every line folded and ending in CRLF. */
    public static String toCalendar(List<CalendarEvent> events, CalendarOptions options) {
        if (Texts.trim(options.name()).isEmpty()) {
            throw ValidationException.field("name", "must not be empty");
        }
        String domain = options.domain().orElse("hitchline.local");
        String stamp = icsStamp(options.stampedAt());
        List<String> lines = new ArrayList<>(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//hitchline//rotation adherence//EN",
                "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:" + icsText(options.name())));
        for (CalendarEvent event : events) {
            lines.addAll(List.of(
                    "BEGIN:VEVENT",
                    "UID:" + icsText(event.uid()) + "@" + domain,
                    "DTSTAMP:" + stamp,
                    "DTSTART;VALUE=DATE:" + icsDate(event.from()),
                    "DTEND;VALUE=DATE:" + icsDate(event.to()),
                    "SUMMARY:" + icsText(event.summary()),
                    "DESCRIPTION:" + icsText(event.description()),
                    "TRANSP:TRANSPARENT",
                    "END:VEVENT"));
        }
        lines.add("END:VCALENDAR");
        return String.join(CRLF, lines.stream().map(IcsExport::foldLine).toList()) + CRLF;
    }

    /** A calendar of the obligations whose habit is among {@code habits}. */
    public static String obligationsToCalendar(
            List<Habit> habits, List<Obligation> obligations, CalendarOptions options) {
        Map<String, Habit> byId = new LinkedHashMap<>();
        habits.forEach(habit -> byId.put(habit.id(), habit));
        List<CalendarEvent> events = new ArrayList<>();
        for (Obligation obligation : obligations) {
            Habit habit = byId.get(obligation.habitId());
            if (habit != null) {
                events.add(eventFor(habit, obligation));
            }
        }
        return toCalendar(events, options);
    }
}
