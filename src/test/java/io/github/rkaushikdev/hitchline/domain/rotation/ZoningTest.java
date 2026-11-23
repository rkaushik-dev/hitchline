package io.github.rkaushikdev.hitchline.domain.rotation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ZoningTest {

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    @Nested
    class AHeldOverRoster {

        private final CrewMember ada = Fixtures.aMember();
        private final Roster held = Roster.of(
                ada,
                List.of(RosterOverride.of(
                        ada.id(),
                        DateRange.parse("2025-01-20..2025-01-22"),
                        DayRole.ONSHIFT,
                        OverrideReason.HELD_OVER,
                        Zones.parseInstant("2025-01-18T10:00:00Z"))));

        @Test
        void measuresADayHeldOverOnTheSiteClock() {
            assertThat(Zoning.zoneOn(held, d("2025-01-21"))).isEqualTo(new ZoneChoice("Europe/Oslo", ZoneReason.SITE));
            assertThat(Zoning.zoneOn(ada, d("2025-01-21"))).isEqualTo(new ZoneChoice("Europe/Lisbon", ZoneReason.HOME));
        }

        @Test
        void putsALateCheckInOnAHeldOverDayOnTheDayItHappenedOffshore() {
            // 23:30 in Oslo is 22:30 in Lisbon on the same date, so only the role and the clock differ.
            RotationDay day = Zoning.rotationDayAt(held, Zones.parseInstant("2025-01-21T22:30:00Z"));
            assertThat(day.date()).isEqualTo(d("2025-01-21"));
            assertThat(day.role()).isEqualTo(DayRole.ONSHIFT);
            assertThat(day.zone()).isEqualTo("Europe/Oslo");
            assertThat(day.reason()).isEqualTo(ZoneReason.SITE);
        }

        @Test
        void readsTheDayAfterTheHoldBackOnTheHomeClock() {
            assertThat(Zoning.rotationDayOn(held, d("2025-01-23")).zone()).isEqualTo("Europe/Lisbon");
            assertThat(Zoning.rotationDayOn(held, d("2025-01-22")).role()).isEqualTo(DayRole.ONSHIFT);
        }

        @Test
        void readsATravelDayTheClerkMovedTowardsWhereItLeads() {
            Roster flying = Roster.of(
                    ada,
                    List.of(RosterOverride.of(
                            ada.id(),
                            DateRange.parse("2025-02-02..2025-02-02"),
                            DayRole.TRAVEL,
                            OverrideReason.SWAPPED,
                            Zones.parseInstant("2025-01-18T10:00:00Z"))));
            assertThat(Zoning.zoneOn(flying, d("2025-02-02")))
                    .isEqualTo(new ZoneChoice("Europe/Oslo", ZoneReason.OUTBOUND));
        }
    }

    @Nested
    class ZoneOn {

        private final CrewMember member =
                Fixtures.aMember(o -> o.pattern("1travel 13on 1travel 13off").anchor("2025-01-06"));

        @Test
        void usesTheSiteClockOnShift() {
            assertThat(Zoning.zoneOn(member, d("2025-01-07")))
                    .isEqualTo(new ZoneChoice("Europe/Oslo", ZoneReason.SITE));
        }

        @Test
        void usesTheHomeClockOffShift() {
            assertThat(Zoning.zoneOn(member, d("2025-01-21")))
                    .isEqualTo(new ZoneChoice("Europe/Lisbon", ZoneReason.HOME));
        }

        @Test
        void sendsAnOutboundTravelDayToTheSiteClock() {
            assertThat(Zoning.zoneOn(member, d("2025-01-06")))
                    .isEqualTo(new ZoneChoice("Europe/Oslo", ZoneReason.OUTBOUND));
        }

        @Test
        void sendsAnInboundTravelDayToTheHomeClock() {
            assertThat(Zoning.zoneOn(member, d("2025-01-20")))
                    .isEqualTo(new ZoneChoice("Europe/Lisbon", ZoneReason.INBOUND));
        }

        @Test
        void looksPastASecondTravelDayBeforeDeciding() {
            CrewMember slow = Fixtures.aMember(
                    o -> o.pattern("2travel 12on 2travel 12off").anchor("2025-01-06"));
            assertThat(Zoning.zoneOn(slow, d("2025-01-06")).reason()).isEqualTo(ZoneReason.OUTBOUND);
            assertThat(Zoning.zoneOn(slow, d("2025-01-07")).reason()).isEqualTo(ZoneReason.OUTBOUND);
            assertThat(Zoning.zoneOn(slow, d("2025-01-20")).reason()).isEqualTo(ZoneReason.INBOUND);
            assertThat(Zoning.zoneOn(slow, d("2025-01-21")).reason()).isEqualTo(ZoneReason.INBOUND);
        }

        @Test
        void treatsStandbyAsTimeAtHome() {
            CrewMember standby =
                    Fixtures.aMember(o -> o.pattern("7on 3standby 4off").anchor("2025-01-06"));
            assertThat(Zoning.zoneOn(standby, d("2025-01-14")))
                    .isEqualTo(new ZoneChoice("Europe/Lisbon", ZoneReason.HOME));
        }
    }

    @Nested
    class RotationDayOn {

        @Test
        void answersForADateWithoutNeedingAClock() {
            RotationDay day = Zoning.rotationDayOn(Fixtures.aMember(), d("2025-01-10"));
            assertThat(day.role()).isEqualTo(DayRole.ONSHIFT);
            assertThat(day.zone()).isEqualTo("Europe/Oslo");
            assertThat(day.straddled()).isFalse();
        }
    }

    @Nested
    class RotationDayAt {

        private final CrewMember member = Fixtures.aMember();

        @Test
        void placesAnOrdinaryOnShiftInstantOnTheSiteDay() {
            RotationDay day = Zoning.rotationDayAt(member, Zones.parseInstant("2025-01-10T09:00:00Z"));
            assertThat(day.date().toString()).isEqualTo("2025-01-10");
            assertThat(day.zone()).isEqualTo("Europe/Oslo");
            assertThat(day.straddled()).isFalse();
        }

        @Test
        void placesAnOffShiftInstantOnTheHomeDay() {
            RotationDay day = Zoning.rotationDayAt(member, Zones.parseInstant("2025-01-25T09:00:00Z"));
            assertThat(day.date().toString()).isEqualTo("2025-01-25");
            assertThat(day.zone()).isEqualTo("Europe/Lisbon");
            assertThat(day.reason()).isEqualTo(ZoneReason.HOME);
        }

        @Test
        void marksADayTheTwoClocksDisagreeAbout() {
            // 23:30 UTC is already the 26th in Oslo but still the 25th in Lisbon, and the 25th is
            // an off shift day, so the home clock settles it.
            RotationDay day = Zoning.rotationDayAt(member, Zones.parseInstant("2025-01-25T23:30:00Z"));
            assertThat(day.date().toString()).isEqualTo("2025-01-25");
            assertThat(day.zone()).isEqualTo("Europe/Lisbon");
            assertThat(day.straddled()).isTrue();
        }

        @Test
        void letsTheSiteClockWinWhenTheReadingsPullApart() {
            // Late on the last night of the hitch, Oslo has already turned over into the first off
            // shift day while Lisbon is still on the last shift day.
            RotationDay day = Zoning.rotationDayAt(member, Zones.parseInstant("2025-01-19T23:30:00Z"));
            assertThat(day.date().toString()).isEqualTo("2025-01-20");
            assertThat(day.zone()).isEqualTo("Europe/Oslo");
            assertThat(day.reason()).isEqualTo(ZoneReason.SITE);
            assertThat(day.straddled()).isTrue();
        }

        @Test
        void isUnaffectedWhenHomeAndSiteShareAClock() {
            CrewMember local = Fixtures.aMember(o -> o.homeZone("Europe/Oslo").siteZone("Europe/Oslo"));
            RotationDay day = Zoning.rotationDayAt(local, Zones.parseInstant("2025-01-25T23:30:00Z"));
            assertThat(day.date().toString()).isEqualTo("2025-01-26");
            assertThat(day.straddled()).isFalse();
        }

        @Test
        void handlesAWideZoneGap() {
            CrewMember far =
                    Fixtures.aMember(o -> o.homeZone("Pacific/Auckland").siteZone("America/Anchorage"));
            RotationDay day = Zoning.rotationDayAt(far, Zones.parseInstant("2025-01-10T02:00:00Z"));
            assertThat(day.date().toString()).isEqualTo("2025-01-09");
            assertThat(day.zone()).isEqualTo("America/Anchorage");
        }

        @Test
        void describesItselfForAReportLine() {
            RotationDay day = Zoning.rotationDayAt(member, Zones.parseInstant("2025-01-10T09:00:00Z"));
            assertThat(day.describe()).isEqualTo("2025-01-10 onshift on Europe/Oslo as site");
        }
    }
}
