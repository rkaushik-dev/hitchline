package io.github.rkaushikdev.hitchline.domain.adherence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.adherence.RestHoursRule.RestDay;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.support.Builders;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RestHoursRuleTest {

    private static final CrewMember ADA = Fixtures.aMember();
    private static final DateRange WEEK = DateRange.parse("2025-01-06..2025-01-12");

    /** One rest period logged at noon UTC on {@code day}, which is the same day on the Oslo clock. */
    private static ResolvedCheckIn rest(String day, double hours) {
        return ResolvedCheckIn.resolve(ADA, Builders.aCheckIn(day + "T12:00:00Z", o -> o.reading(hours)));
    }

    private static List<ResolvedCheckIn> everyDay(String from, int days, double hours) {
        List<ResolvedCheckIn> log = new ArrayList<>();
        for (int day = 0; day < days; day += 1) {
            log.add(rest(CivilDate.parse(from).plusDays(day).toString(), hours));
        }
        return log;
    }

    @Nested
    class ADayOfRest {

        @Test
        void passesTenAndAHalfHoursInTwoPeriods() {
            List<ResolvedCheckIn> log = List.of(rest("2025-01-08", 7), rest("2025-01-08", 3.5));
            assertThat(RestHoursRule.DEFAULT.check(log, WEEK)).isEmpty();
        }

        @Test
        void flagsADayUnderTenHours() {
            List<RestBreach> breaches = RestHoursRule.DEFAULT.check(List.of(rest("2025-01-08", 8.5)), WEEK);
            assertThat(breaches)
                    .containsExactly(
                            new RestBreach(RestRule.SHORT_DAY, DateRange.parse("2025-01-08..2025-01-08"), 8.5, 1));
            assertThat(breaches.get(0).describe()).isEqualTo("short_day 2025-01-08..2025-01-08: 8.5 hours in 1 period");
        }

        @Test
        void flagsRestCutIntoTooManyPiecesWithNoneLongEnoughToSleepIn() {
            List<ResolvedCheckIn> log = List.of(rest("2025-01-08", 4), rest("2025-01-08", 4), rest("2025-01-08", 4));
            assertThat(RestHoursRule.DEFAULT.check(log, WEEK))
                    .extracting(RestBreach::rule)
                    .containsExactly(RestRule.TOO_MANY_PERIODS, RestRule.NO_LONG_PERIOD);
        }

        @Test
        void flagsTwoPeriodsWhenNeitherIsSixHours() {
            List<ResolvedCheckIn> log = List.of(rest("2025-01-08", 5.5), rest("2025-01-08", 5.5));
            List<RestBreach> breaches = RestHoursRule.DEFAULT.check(log, WEEK);
            assertThat(breaches).extracting(RestBreach::rule).containsExactly(RestRule.NO_LONG_PERIOD);
            assertThat(breaches.get(0).describe()).endsWith("11 hours in 2 periods");
        }

        @Test
        void addsUpTheDayAndKeepsTheLongestPiece() {
            List<RestDay> days = RestHoursRule.days(List.of(rest("2025-01-08", 6.25), rest("2025-01-08", 4.5)), WEEK);
            assertThat(days).containsExactly(new RestDay(CivilDate.parse("2025-01-08"), 10.8, 2, 6.25));
        }
    }

    @Nested
    class AWeekOfRest {

        @Test
        void flagsSevenDaysOfTenHoursAsShortOfSeventySeven() {
            List<RestBreach> breaches = RestHoursRule.DEFAULT.check(everyDay("2025-01-06", 7, 10), WEEK);
            assertThat(breaches).containsExactly(new RestBreach(RestRule.SHORT_WEEK, WEEK, 70, 7));
        }

        @Test
        void passesSevenDaysOfElevenHours() {
            assertThat(RestHoursRule.DEFAULT.check(everyDay("2025-01-06", 7, 11), WEEK))
                    .isEmpty();
        }

        @Test
        void reportsARunOfShortWeeksOnceFromTheFirst() {
            DateRange fortnight = DateRange.parse("2025-01-06..2025-01-19");
            List<RestBreach> breaches = RestHoursRule.DEFAULT.check(everyDay("2025-01-06", 14, 10.5), fortnight);
            assertThat(breaches)
                    .extracting(RestBreach::range)
                    .containsExactly(WEEK, DateRange.parse("2025-01-13..2025-01-19"));
        }

        @Test
        void readsTheDaysBeforeTheWindowToJudgeItsFirstWeek() {
            List<RestBreach> breaches = RestHoursRule.DEFAULT.check(
                    everyDay("2025-01-06", 7, 10), DateRange.parse("2025-01-12..2025-01-12"));
            assertThat(breaches).extracting(RestBreach::range).containsExactly(WEEK);
        }

        @Test
        void leavesAWeekWithAnUnloggedDayUnjudged() {
            assertThat(RestHoursRule.DEFAULT.check(everyDay("2025-01-06", 6, 10), WEEK))
                    .isEmpty();
        }
    }

    @Nested
    class TheRuleItself {

        @Test
        void refusesNumbersNoRotaCouldMeet() {
            assertThatThrownBy(() -> new RestHoursRule(0, 77, 2, 6)).hasMessageContaining("minDayHours");
            assertThatThrownBy(() -> new RestHoursRule(10, 200, 2, 6)).hasMessageContaining("minWeekHours");
            assertThatThrownBy(() -> new RestHoursRule(10, 77, 0, 6)).hasMessageContaining("maxPeriods");
            assertThatThrownBy(() -> new RestHoursRule(10, 77, 2, 11)).hasMessageContaining("minLongestHours");
        }

        @Test
        void readsTheRulesByTheirWireWords() {
            assertThat(RestRule.fromWire("short_week")).contains(RestRule.SHORT_WEEK);
            assertThat(RestRule.NO_LONG_PERIOD.toString()).isEqualTo("no_long_period");
        }
    }
}
