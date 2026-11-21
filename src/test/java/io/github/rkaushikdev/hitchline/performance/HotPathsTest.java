package io.github.rkaushikdev.hitchline.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.ClockTime;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.infra.Measure;
import io.github.rkaushikdev.hitchline.infra.Timing;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Timing budgets for the paths that grow with a crew. The class is tagged {@code performance}, so
 * it runs with {@code ./gradlew performanceTest} and stays out of the default build.
 */
@Tag("performance")
class HotPathsTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static final Roster TARGET = Roster.of(MEMBER);
    private static final DateRange YEAR = DateRange.of(CivilDate.parse("2025-01-01"), CivilDate.parse("2025-12-31"));

    private static List<ResolvedCheckIn> aYearOfCheckIns() {
        List<CheckIn> entries = new ArrayList<>();
        for (int day = 0; day < 365; day += 1) {
            CivilDate date = CivilDate.parse("2025-01-01").plusDays(day);
            Instant at = Zones.zonedInstant("Europe/Oslo", date, ClockTime.parse("07:30"));
            entries.add(CheckIn.builder(
                            Ids.checkInId("chk_" + Texts.padStart(Integer.toString(day + 1), 16, '0')),
                            Ids.habitId("hab_0123456789ABCDEF"),
                            Ids.crewMemberId("mem_0123456789ABCDEF"),
                            at,
                            at,
                            CheckInSource.APP)
                    .build());
        }
        return ResolvedCheckIn.resolveAll(MEMBER, entries);
    }

    @Nested
    class TheStopwatch {

        @Test
        void readsAMedianAndAWorstCase() {
            int[] tick = {0};
            double[] readings = {0, 5, 0, 1, 0, 9, 0, 3, 0, 2, 0, 4};
            DoubleSupplier now = () -> {
                int index = tick[0]++;
                return index < readings.length ? readings[index] : 0;
            };
            Timing timing = Measure.measure("fake", () -> {}, 5, 1, now);
            assertThat(timing.runs()).isEqualTo(5);
            assertThat(timing.medianMs()).isGreaterThan(0);
            assertThat(timing.worstMs()).isGreaterThanOrEqualTo(timing.medianMs());
        }

        @Test
        void refusesToRunNothing() {
            assertThatThrownBy(() -> Measure.measure("none", () -> {}, 0, 1, () -> System.nanoTime() / 1_000_000.0))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void readsBackInOneLine() {
            Timing timing = new Timing("expand", 5, 12.5, 20, 70);
            assertThat(timing.describe()).isEqualTo("expand: 12.5ms median over 5 runs, worst 20ms");
        }

        @Test
        void comparesAgainstABudget() {
            Timing timing = new Timing("expand", 5, 12.5, 20, 70);
            assertThat(timing.isSlowerThan(10)).isTrue();
            assertThat(timing.isSlowerThan(50)).isFalse();
        }
    }

    @Nested
    class ThePathsThatGrowWithACrew {

        @Test
        void expandsAYearOfDailyObligationsWellInsideTheBudget() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-01"));
            Timing timing = Measure.measure(
                    "expand a year", () -> Expansion.of(TARGET, habit, YEAR).expand());
            assertThat(timing.isSlowerThan(250)).isFalse();
        }

        @Test
        void expandsAYearOfShiftDayObligationsWellInsideTheBudget() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-01-01"));
            Timing timing = Measure.measure("expand a year of shift days", () -> Expansion.of(TARGET, habit, YEAR)
                    .expand());
            assertThat(timing.isSlowerThan(250)).isFalse();
        }

        @Test
        void scoresAYearOfObligationsAgainstAYearOfCheckIns() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-01"));
            List<Obligation> obligations = Expansion.of(TARGET, habit, YEAR).expand();
            List<ResolvedCheckIn> resolved = aYearOfCheckIns();
            Timing timing = Measure.measure(
                    "score a year",
                    () -> ObligationResult.evaluateAll(habit, obligations, resolved, CivilDate.parse("2026-01-01")));
            assertThat(obligations).hasSize(365);
            assertThat(timing.isSlowerThan(400)).isFalse();
        }

        @Test
        void walksAYearOfResultsForAStreak() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-01"));
            List<Obligation> obligations = Expansion.of(TARGET, habit, YEAR).expand();
            List<ObligationResult> results =
                    ObligationResult.evaluateAll(habit, obligations, aYearOfCheckIns(), CivilDate.parse("2026-01-01"));
            Timing timing =
                    Measure.measure("streak over a year", () -> StreakSummary.compute(results, List.of(), MEMBER.id()));
            assertThat(timing.isSlowerThan(100)).isFalse();
        }

        @Test
        void expandsAHitchCadenceOverAYearWithoutWalkingEveryDay() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("3/hitch").startsOn("2025-01-01"));
            Timing timing = Measure.measure(
                    "expand hitches", () -> Expansion.of(TARGET, habit, DateRange.parse("2025-01-01..2025-12-31"))
                            .expand());
            assertThat(timing.isSlowerThan(100)).isFalse();
        }
    }
}
