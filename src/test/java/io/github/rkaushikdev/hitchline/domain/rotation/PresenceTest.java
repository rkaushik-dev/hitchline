package io.github.rkaushikdev.hitchline.domain.rotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.Presence.ZoneDays;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PresenceTest {

    private static final CrewMember ADA = Fixtures.aMember();

    @Nested
    class AFortnightEachWay {

        @Test
        void splitsAWholeCycleEvenlyBetweenSiteAndHome() {
            Presence presence = Presence.over(Roster.of(ADA), DateRange.parse("2025-01-06..2025-02-02"));
            assertThat(presence.zones())
                    .containsExactly(new ZoneDays("Europe/Lisbon", 14, 0.5), new ZoneDays("Europe/Oslo", 14, 0.5));
        }

        @Test
        void putsTheClockWithMostDaysFirst() {
            Presence presence = Presence.over(Roster.of(ADA), DateRange.parse("2025-01-06..2025-01-25"));
            assertThat(presence.zones()).extracting(ZoneDays::zone).containsExactly("Europe/Oslo", "Europe/Lisbon");
            assertThat(presence.daysIn("Europe/Oslo")).isEqualTo(14);
            assertThat(presence.zones().get(0).share()).isCloseTo(0.7, within(1e-9));
            assertThat(presence.daysIn("Asia/Tokyo")).isZero();
        }

        @Test
        void countsATaxYearDayByDay() {
            Presence year = Presence.over(Roster.of(ADA), DateRange.parse("2025-04-06..2026-04-05"));
            assertThat(year.daysIn("Europe/Oslo") + year.daysIn("Europe/Lisbon"))
                    .isEqualTo(365);
            assertThat(year.window().length()).isEqualTo(365);
        }
    }

    @Nested
    class TravelAndHolds {

        @Test
        void sendsEachTravelDayWhereItLeads() {
            CrewMember flyer = Fixtures.aMember(o -> o.pattern("1travel 13on 1travel 13off"));
            Presence presence = Presence.over(Roster.of(flyer), DateRange.parse("2025-01-06..2025-02-02"));
            assertThat(presence.daysIn("Europe/Oslo")).isEqualTo(14);
            assertThat(presence.daysIn("Europe/Lisbon")).isEqualTo(14);
        }

        @Test
        void addsTheDaysSomebodyWasHeldOffshore() {
            Roster held = Roster.of(
                    ADA,
                    List.of(RosterOverride.of(
                            ADA.id(),
                            DateRange.parse("2025-01-20..2025-01-23"),
                            DayRole.ONSHIFT,
                            OverrideReason.WEATHER,
                            Zones.parseInstant("2025-01-19T00:00:00Z"))));
            Presence presence = Presence.over(held, DateRange.parse("2025-01-06..2025-02-02"));
            assertThat(presence.daysIn("Europe/Oslo")).isEqualTo(18);
            assertThat(presence.daysIn("Europe/Lisbon")).isEqualTo(10);
        }
    }
}
