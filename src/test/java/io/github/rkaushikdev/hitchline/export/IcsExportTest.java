package io.github.rkaushikdev.hitchline.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.export.IcsExport.CalendarEvent;
import io.github.rkaushikdev.hitchline.export.IcsExport.CalendarOptions;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IcsExportTest {

    private static final Instant STAMPED_AT = Zones.parseInstant("2025-03-17T09:00:00Z");
    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final Obligation DAILY =
            new Obligation(TREADMILL, ADA, ObligationScope.DAY, DateRange.parse("2025-01-10..2025-01-10"), 1);
    private static final CalendarOptions ADA_CALENDAR = CalendarOptions.of("Ada", STAMPED_AT);

    @Nested
    class Escaping {

        @Test
        void escapesTheReservedCharacters() {
            assertThat(IcsExport.icsText("a;b,c")).isEqualTo("a\\;b\\,c");
            assertThat(IcsExport.icsText("a\\b")).isEqualTo("a\\\\b");
            assertThat(IcsExport.icsText("two\nlines")).isEqualTo("two\\nlines");
        }

        @Test
        void leavesPlainTextAlone() {
            assertThat(IcsExport.icsText("Twenty minutes on the treadmill"))
                    .isEqualTo("Twenty minutes on the treadmill");
        }
    }

    @Nested
    class DatesAndStamps {

        @Test
        void writesADateWithNoSeparators() {
            assertThat(IcsExport.icsDate(CivilDate.parse("2025-01-10"))).isEqualTo("20250110");
        }

        @Test
        void writesAStampWithNoSeparatorsOrMilliseconds() {
            assertThat(IcsExport.icsStamp(STAMPED_AT)).isEqualTo("20250317T090000Z");
        }
    }

    @Nested
    class Folding {

        @Test
        void leavesAShortLineAlone() {
            assertThat(IcsExport.foldLine("SUMMARY:Row")).isEqualTo("SUMMARY:Row");
        }

        @Test
        void foldsALongLineWithALeadingSpace() {
            List<String> lines = Texts.split(IcsExport.foldLine("SUMMARY:" + "x".repeat(200)), "\r\n");
            assertThat(lines.size()).isGreaterThan(1);
            assertThat(lines.get(0)).hasSize(75);
            assertThat(lines.get(1)).startsWith(" ");
        }

        @Test
        void countsOctetsRatherThanCharacters() {
            String first = Texts.split(IcsExport.foldLine("SUMMARY:" + "é".repeat(60)), "\r\n")
                    .get(0);
            assertThat(first.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
    }

    @Nested
    class BuildingEvents {

        @Test
        void putsTheEventOnTheDayItFallsDue() {
            CalendarEvent event = IcsExport.eventFor(Fixtures.aHabit(), DAILY);
            assertThat(event.from()).isEqualTo(CivilDate.parse("2025-01-10"));
            assertThat(event.to()).isEqualTo(CivilDate.parse("2025-01-11"));
        }

        @Test
        void namesTheHabitAndHowManyTimes() {
            assertThat(IcsExport.eventFor(Fixtures.aHabit(), DAILY).summary())
                    .isEqualTo("Twenty minutes on the treadmill");
            Obligation perHitch =
                    new Obligation(TREADMILL, ADA, ObligationScope.HITCH, DateRange.parse("2025-01-06..2025-02-02"), 3);
            assertThat(IcsExport.eventFor(Fixtures.aHabit(), perHitch).summary())
                    .contains("(3 times)");
        }

        @Test
        void keysTheEventOnTheObligation() {
            assertThat(IcsExport.eventFor(Fixtures.aHabit(), DAILY).uid())
                    .isEqualTo("hab_0123456789ABCDEF:day:2025-01-10");
        }
    }

    @Nested
    class TheCalendar {

        private final List<CalendarEvent> events = List.of(IcsExport.eventFor(Fixtures.aHabit(), DAILY));

        @Test
        void opensAndClosesProperly() {
            String calendar = IcsExport.toCalendar(events, ADA_CALENDAR);
            List<String> lines = Texts.split(calendar, "\r\n");
            assertThat(lines.get(0)).isEqualTo("BEGIN:VCALENDAR");
            assertThat(lines.get(lines.size() - 2)).isEqualTo("END:VCALENDAR");
            assertThat(calendar).endsWith("\r\n");
        }

        @Test
        void writesOneEventBlock() {
            assertThat(IcsExport.toCalendar(events, ADA_CALENDAR))
                    .contains("BEGIN:VEVENT")
                    .contains("DTSTART;VALUE=DATE:20250110")
                    .contains("DTEND;VALUE=DATE:20250111")
                    .contains("DTSTAMP:20250317T090000Z");
        }

        @Test
        void takesADomainForTheUid() {
            assertThat(IcsExport.toCalendar(
                            events, new CalendarOptions("Ada", STAMPED_AT, Optional.of("grane.example"))))
                    .contains("@grane.example");
        }

        @Test
        void refusesACalendarWithNoName() {
            assertThatThrownBy(() -> IcsExport.toCalendar(events, CalendarOptions.of("  ", STAMPED_AT)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void writesAnEmptyCalendarWithNoEvents() {
            assertThat(IcsExport.toCalendar(List.of(), ADA_CALENDAR)).doesNotContain("BEGIN:VEVENT");
        }
    }

    @Nested
    class BuildingFromObligations {

        @Test
        void skipsAnObligationWhoseHabitIsMissing() {
            assertThat(IcsExport.obligationsToCalendar(List.of(), List.of(DAILY), ADA_CALENDAR))
                    .doesNotContain("BEGIN:VEVENT");
        }

        @Test
        void writesAnEventForEachObligationItCanName() {
            Obligation second =
                    new Obligation(TREADMILL, ADA, ObligationScope.DAY, DateRange.parse("2025-01-11..2025-01-11"), 1);
            String calendar =
                    IcsExport.obligationsToCalendar(List.of(Fixtures.aHabit()), List.of(DAILY, second), ADA_CALENDAR);
            assertThat(Texts.split(calendar, "BEGIN:VEVENT")).hasSize(3);
        }
    }
}
