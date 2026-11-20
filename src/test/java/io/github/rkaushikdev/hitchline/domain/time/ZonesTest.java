package io.github.rkaushikdev.hitchline.domain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ZonesTest {

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static ClockTime t(String text) {
        return ClockTime.parse(text);
    }

    private static Instant at(String text) {
        return Zones.parseInstant(text);
    }

    @Nested
    class ZoneIds {

        @Test
        void acceptsZonesThePlatformKnows() {
            assertThat(Zones.isKnownZone("Europe/Oslo")).isTrue();
            assertThat(Zones.isKnownZone("America/St_Johns")).isTrue();
            assertThat(Zones.isKnownZone("UTC")).isTrue();
        }

        @Test
        void rejectsInventedZones() {
            assertThat(Zones.isKnownZone("Sea/Platform_Alpha")).isFalse();
            assertThatThrownBy(() -> Zones.zoneId("Sea/Platform_Alpha")).hasMessageContaining("not a timezone");
        }
    }

    @Nested
    class Instants {

        @Test
        void parsesAndFormatsAnIsoTimestamp() {
            assertThat(Zones.formatInstant(at("2025-03-30T00:30:00Z"))).isEqualTo("2025-03-30T00:30:00.000Z");
        }

        @Test
        void refusesATimestampItCannotRead() {
            assertThatThrownBy(() -> at("half past march")).hasMessageContaining("iso timestamp");
        }

        @Test
        void refusesANonFiniteEpoch() {
            assertThatThrownBy(() -> Zones.instant(Double.POSITIVE_INFINITY)).hasMessageContaining("finite number");
        }
    }

    @Nested
    class Offsets {

        @Test
        void readsAWholeHourOffset() {
            assertThat(Zones.offsetMinutesAt("Europe/Oslo", at("2025-01-15T12:00:00Z")))
                    .isEqualTo(60);
            assertThat(Zones.offsetMinutesAt("Europe/Oslo", at("2025-07-15T12:00:00Z")))
                    .isEqualTo(120);
        }

        @Test
        void readsAHalfHourOffset() {
            assertThat(Zones.offsetMinutesAt("Asia/Kolkata", at("2025-01-15T12:00:00Z")))
                    .isEqualTo(330);
        }

        @Test
        void readsANegativeOffset() {
            assertThat(Zones.offsetMinutesAt("America/Halifax", at("2025-01-15T12:00:00Z")))
                    .isEqualTo(-240);
        }

        @Test
        void readsTheFortyFiveMinuteOffsetInKathmandu() {
            assertThat(Zones.offsetMinutesAt("Asia/Kathmandu", at("2025-01-15T12:00:00Z")))
                    .isEqualTo(345);
        }
    }

    @Nested
    class ReadingAWallClockInAZone {

        @Test
        void givesTheLocalDateWhichCanDifferFromTheUtcOne() {
            Instant moment = at("2025-01-15T23:30:00Z");
            assertThat(Zones.civilDateIn("Europe/Oslo", moment)).isEqualTo(d("2025-01-16"));
            assertThat(Zones.civilDateIn("America/Halifax", moment)).isEqualTo(d("2025-01-15"));
        }

        @Test
        void givesTheLocalTime() {
            Instant moment = at("2025-06-01T12:00:00Z");
            assertThat(Zones.clockTimeIn("Europe/Oslo", moment)).isEqualTo(t("14:00"));
            assertThat(Zones.clockTimeIn("UTC", moment)).isEqualTo(t("12:00"));
        }

        @Test
        void describesAnInstantWithItsOffset() {
            assertThat(Zones.describeZoned("Asia/Kolkata", at("2025-01-15T12:00:00Z")))
                    .isEqualTo("2025-01-15 17:30+05:30");
            assertThat(Zones.describeZoned("America/Halifax", at("2025-01-15T12:00:00Z")))
                    .isEqualTo("2025-01-15 08:00-04:00");
        }
    }

    @Nested
    class ResolvingAWallClockReadingBackToAnInstant {

        @Test
        void isExactOnAnOrdinaryDay() {
            ZonedResolution resolved = Zones.resolveZoned("Europe/Oslo", d("2025-06-01"), t("07:00"));
            assertThat(resolved.outcome()).isEqualTo(ZonedOutcome.EXACT);
            assertThat(Zones.formatInstant(resolved.instant())).isEqualTo("2025-06-01T05:00:00.000Z");
            assertThat(resolved.offsetMinutes()).isEqualTo(120);
        }

        @Test
        void slidesATimeInsideTheSpringGapForwardPastIt() {
            ZonedResolution resolved = Zones.resolveZoned("Europe/London", d("2025-03-30"), t("01:30"));
            assertThat(resolved.outcome()).isEqualTo(ZonedOutcome.SKIPPED);
            assertThat(Zones.formatInstant(resolved.instant())).isEqualTo("2025-03-30T01:30:00.000Z");
            assertThat(Zones.clockTimeIn("Europe/London", resolved.instant())).isEqualTo(t("02:30"));
        }

        @Test
        void takesTheFirstPassThroughAnAutumnOverlap() {
            ZonedResolution resolved = Zones.resolveZoned("Europe/London", d("2025-10-26"), t("01:30"));
            assertThat(resolved.outcome()).isEqualTo(ZonedOutcome.AMBIGUOUS);
            assertThat(Zones.formatInstant(resolved.instant())).isEqualTo("2025-10-26T00:30:00.000Z");
            assertThat(resolved.offsetMinutes()).isEqualTo(60);
        }

        @Test
        void isExactEitherSideOfATransition() {
            assertThat(Zones.resolveZoned("Europe/London", d("2025-03-30"), t("00:30"))
                            .outcome())
                    .isEqualTo(ZonedOutcome.EXACT);
            assertThat(Zones.resolveZoned("Europe/London", d("2025-03-30"), t("03:30"))
                            .outcome())
                    .isEqualTo(ZonedOutcome.EXACT);
        }

        @Test
        void handlesAZoneThatNeverChangesOffset() {
            ZonedResolution resolved = Zones.resolveZoned("Asia/Kolkata", d("2025-03-30"), t("01:30"));
            assertThat(resolved.outcome()).isEqualTo(ZonedOutcome.EXACT);
            assertThat(Zones.formatInstant(resolved.instant())).isEqualTo("2025-03-29T20:00:00.000Z");
        }

        @Test
        void roundTripsThroughTheZoneItCameFrom() {
            Instant moment = Zones.zonedInstant("America/Halifax", d("2025-08-08"), t("06:15"));
            assertThat(Zones.civilDateIn("America/Halifax", moment)).isEqualTo(d("2025-08-08"));
            assertThat(Zones.clockTimeIn("America/Halifax", moment)).isEqualTo(t("06:15"));
        }
    }

    @Nested
    class StartOfDayIn {

        @Test
        void landsOnLocalMidnight() {
            Instant moment = Zones.startOfDayIn("Europe/Oslo", d("2025-06-01"));
            assertThat(Zones.formatInstant(moment)).isEqualTo("2025-05-31T22:00:00.000Z");
            assertThat(Zones.clockTimeIn("Europe/Oslo", moment)).isEqualTo(t("00:00"));
        }

        @Test
        void copesWithAZoneWhoseDayStartsInsideATransition() {
            Instant moment = Zones.startOfDayIn("America/Santiago", d("2025-09-07"));
            assertThat(Zones.civilDateIn("America/Santiago", moment)).isEqualTo(d("2025-09-07"));
        }
    }

    @Nested
    class TheLengthOfALocalDay {

        @Test
        void isTwentyFourHoursOnAnOrdinaryDay() {
            assertThat(Zones.dayLengthMinutes("Europe/London", d("2025-06-01"))).isEqualTo(1440);
            assertThat(Zones.hasTransition("Europe/London", d("2025-06-01"))).isFalse();
        }

        @Test
        void isTwentyThreeHoursWhenTheClocksGoForward() {
            assertThat(Zones.dayLengthMinutes("Europe/London", d("2025-03-30"))).isEqualTo(1380);
            assertThat(Zones.hasTransition("Europe/London", d("2025-03-30"))).isTrue();
        }

        @Test
        void isTwentyFiveHoursWhenTheClocksGoBack() {
            assertThat(Zones.dayLengthMinutes("Europe/London", d("2025-10-26"))).isEqualTo(1500);
        }

        @Test
        void isTwentyFourHoursEverywhereInAZoneThatDoesNotChange() {
            assertThat(Zones.dayLengthMinutes("Asia/Kolkata", d("2025-03-30"))).isEqualTo(1440);
            assertThat(Zones.dayLengthMinutes("Asia/Kolkata", d("2025-10-26"))).isEqualTo(1440);
        }

        @Test
        void handlesAZoneWhoseDayStartsInTheGap() {
            // Santiago moves the clocks at midnight, so the seventh has no 00:00.
            assertThat(Zones.dayLengthMinutes("America/Santiago", d("2025-09-07")))
                    .isEqualTo(1380);
        }
    }

    @Nested
    class DayBoundsInAZone {

        @Test
        void areHalfOpenSoTheNextDayStartsWhereThisOneEnds() {
            DayBounds first = Zones.dayBoundsIn("Europe/Oslo", d("2025-06-01"));
            DayBounds second = Zones.dayBoundsIn("Europe/Oslo", d("2025-06-02"));
            assertThat(first.to()).isEqualTo(second.from());
        }

        @Test
        void holdAnInstantInsideTheDayAndNotTheOneAfter() {
            DayBounds bounds = Zones.dayBoundsIn("Europe/Oslo", d("2025-06-01"));
            assertThat(bounds.contains(at("2025-06-01T12:00:00Z"))).isTrue();
            assertThat(bounds.contains(bounds.to())).isFalse();
            assertThat(bounds.contains(at("2025-05-31T21:00:00Z"))).isFalse();
        }

        @Test
        void coverTheShortDayWithoutAGap() {
            DayBounds shortDay = Zones.dayBoundsIn("Europe/London", d("2025-03-30"));
            DayBounds after = Zones.dayBoundsIn("Europe/London", d("2025-03-31"));
            assertThat(shortDay.to()).isEqualTo(after.from());
        }
    }
}
