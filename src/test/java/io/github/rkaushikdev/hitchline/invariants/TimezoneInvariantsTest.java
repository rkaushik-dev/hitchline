package io.github.rkaushikdev.hitchline.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationDay;
import io.github.rkaushikdev.hitchline.domain.rotation.Zoning;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.ClockTime;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.ZonedResolution;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Builders;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The zones that break naive date handling, checked all the way through rather than only at the
 * bottom. Every bug this file guards against was found by somebody offshore, not by a unit test:
 * the clocks move while a crew is on a hitch and the readings have to stay honest across it.
 */
class TimezoneInvariantsTest {

    private record Tricky(String zone, String spring, String autumn) {}

    private static final List<Tricky> TRICKY = List.of(
            new Tricky("Europe/London", "2025-03-30", "2025-10-26"),
            new Tricky("Europe/Oslo", "2025-03-30", "2025-10-26"),
            new Tricky("America/Halifax", "2025-03-09", "2025-11-02"),
            new Tricky("America/Santiago", "2025-09-07", "2025-04-05"),
            new Tricky("Australia/Lord_Howe", "2025-10-05", "2025-04-06"));

    private static CivilDate d(String value) {
        return CivilDate.parse(value);
    }

    private static ClockTime t(String value) {
        return ClockTime.parse(value);
    }

    @Nested
    class TheDaysTheClocksMove {

        @Test
        void areShorterInSpringAndLongerInAutumn() {
            for (Tricky entry : TRICKY) {
                int springLength = Zones.dayLengthMinutes(entry.zone(), d(entry.spring()));
                int autumnLength = Zones.dayLengthMinutes(entry.zone(), d(entry.autumn()));
                assertThat(springLength).isLessThan(1440);
                assertThat(autumnLength).isGreaterThan(1440);
                assertThat(Zones.hasTransition(entry.zone(), d(entry.spring()))).isTrue();
            }
        }

        @Test
        void stillResolveEveryWallClockReadingToSomeInstant() {
            for (Tricky entry : TRICKY) {
                for (int hour = 0; hour < 24; hour += 1) {
                    ClockTime time = t(Texts.padStart(Integer.toString(hour), 2, '0') + ":30");
                    ZonedResolution resolved = Zones.resolveZoned(entry.zone(), d(entry.spring()), time);
                    assertThat(resolved.instant()).isNotNull();
                    assertThat(resolved.outcome().wire()).isIn("exact", "skipped", "ambiguous");
                }
            }
        }

        @Test
        void neverResolveAReadingOntoTheDayBefore() {
            for (Tricky entry : TRICKY) {
                for (String day : List.of(entry.spring(), entry.autumn())) {
                    for (String hour : List.of("00:30", "01:30", "02:30", "03:30", "23:30")) {
                        Instant at = Zones.zonedInstant(entry.zone(), d(day), t(hour));
                        String read = Zones.civilDateIn(entry.zone(), at).toString();
                        assertThat(read).isGreaterThanOrEqualTo(day);
                    }
                }
            }
        }

        @Test
        void keepMidnightOnTheDayItBelongsTo() {
            for (Tricky entry : TRICKY) {
                for (String day : List.of(entry.spring(), entry.autumn())) {
                    Instant at = Zones.zonedInstant(entry.zone(), d(day), t("00:00"));
                    assertThat(Zones.civilDateIn(entry.zone(), at)).hasToString(day);
                }
            }
        }
    }

    @Nested
    class ARotationAcrossATransition {

        @Test
        void putsACheckInOnTheShiftDayItWasMadeOn() {
            // The clocks move in Oslo overnight on the thirtieth of March, and this rota has that
            // day as a shift day, so it is measured on the site clock.
            CrewMember onShift = Fixtures.aMember(
                    o -> o.homeZone("Europe/Lisbon").siteZone("Europe/Oslo").anchor("2025-03-24"));
            RotationDay day = Zoning.rotationDayAt(onShift, Zones.parseInstant("2025-03-30T05:00:00Z"));
            assertThat(day.date()).hasToString("2025-03-30");
            assertThat(day.zone()).isEqualTo("Europe/Oslo");
        }

        @Test
        void readsTheSameInstantDifferentlyOnTheTwoClocks() {
            Instant at = Zones.parseInstant("2025-03-29T23:30:00Z");
            assertThat(Zones.civilDateIn("Europe/Oslo", at)).hasToString("2025-03-30");
            assertThat(Zones.civilDateIn("Europe/Lisbon", at)).hasToString("2025-03-29");
        }

        @Test
        void scoresAFortnightOverATransitionWithoutLosingOrDoublingADay() {
            Builders.resetCheckInIds();
            CrewMember member = Fixtures.aMember(o -> o.siteZone("Europe/Oslo").anchor("2025-03-24"));
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-03-24"));
            DateRange window = DateRange.parse("2025-03-24..2025-04-06");
            List<Obligation> obligations =
                    Expansion.of(Roster.of(member), habit, window).expand();
            assertThat(obligations).hasSize(14);

            List<CheckIn> entries = obligations.stream()
                    .map(entry -> Builders.aCheckIn(entry.dueOn() + "T09:00:00Z"))
                    .toList();
            List<ObligationResult> results = ObligationResult.evaluateAll(
                    habit, obligations, ResolvedCheckIn.resolveAll(member, entries), d("2025-04-07"));
            assertThat(results.stream().filter(result -> result.status() == AdherenceStatus.MET))
                    .hasSize(14);
        }
    }

    @Nested
    class TheHalfHourAndThreeQuarterHourZones {

        private static final List<String> ODD =
                List.of("Asia/Kolkata", "Asia/Kathmandu", "Australia/Adelaide", "Pacific/Chatham");

        @Test
        void roundTripAWallClockReading() {
            for (String zone : ODD) {
                Instant at = Zones.zonedInstant(zone, d("2025-06-15"), t("07:30"));
                assertThat(Zones.civilDateIn(zone, at)).hasToString("2025-06-15");
            }
        }

        @Test
        void putALateCheckInOnTheRightRotationDay() {
            for (String zone : ODD) {
                CrewMember member = Fixtures.aMember(o -> o.homeZone(zone).siteZone(zone));
                Instant at = Zones.zonedInstant(zone, d("2025-06-15"), t("23:45"));
                assertThat(Zoning.rotationDayAt(member, at).date()).hasToString("2025-06-15");
            }
        }
    }

    @Nested
    class ACrewThatFliesBetweenHemispheres {

        @Test
        void keepsTheShiftDayOnTheSiteClockAndTheRestDayAtHome() {
            CrewMember member =
                    Fixtures.aMember(o -> o.homeZone("Pacific/Auckland").siteZone("America/Anchorage"));
            RotationDay onShift = Zoning.rotationDayAt(member, Zones.parseInstant("2025-01-10T02:00:00Z"));
            assertThat(onShift.zone()).isEqualTo("America/Anchorage");
            assertThat(onShift.date()).hasToString("2025-01-09");

            RotationDay atHome = Zoning.rotationDayAt(member, Zones.parseInstant("2025-01-25T02:00:00Z"));
            assertThat(atHome.zone()).isEqualTo("Pacific/Auckland");
            assertThat(atHome.date()).hasToString("2025-01-25");
        }
    }
}
