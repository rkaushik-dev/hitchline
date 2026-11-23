package io.github.rkaushikdev.hitchline.domain.adherence;

import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MET;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MISSED;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PARTIAL;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Builders;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AnalyticsTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static int next;

    private static CheckIn at(String iso) {
        next += 1;
        return CheckIn.builder(
                        Ids.checkInId("chk_" + Texts.padStart(Integer.toString(next), 16, '0')),
                        TREADMILL,
                        ADA,
                        Zones.parseInstant(iso),
                        Zones.parseInstant(iso),
                        CheckInSource.APP)
                .build();
    }

    private static List<ResolvedCheckIn> resolve(CheckIn... entries) {
        return ResolvedCheckIn.resolveAll(Fixtures.aMember(), List.of(entries));
    }

    private static PeriodSummary summary(double rate) {
        return summary(rate, 10);
    }

    private static PeriodSummary summary(double rate, int owed) {
        int met = (int) Numbers.round(rate * owed);
        return new PeriodSummary(DateRange.parse("2025-01-06..2025-01-19"), owed, met, 0, owed - met, 0, 0, rate);
    }

    @Nested
    class HourOfDay {

        @Test
        void hasABucketForEveryHour() {
            assertThat(Analytics.byHourOfDay(List.of())).hasSize(24);
            assertThat(Analytics.byHourOfDay(List.of())).allMatch(bucket -> bucket.count() == 0);
        }

        @Test
        void countsOnTheClockOfTheDayTheCheckInLandedOn() {
            // 06:00 UTC is 07:00 on the Oslo shift day and 07:00 in Lisbon off shift.
            assertThat(Analytics.byHourOfDay(resolve(at("2025-01-10T06:00:00Z")))
                            .get(7)
                            .count())
                    .isEqualTo(1);
        }

        @Test
        void readsAnOffShiftCheckInOnTheHomeClock() {
            assertThat(Analytics.byHourOfDay(resolve(at("2025-01-25T06:00:00Z")))
                            .get(6)
                            .count())
                    .isEqualTo(1);
        }

        @Test
        void findsTheBusiestHour() {
            List<ResolvedCheckIn> resolved =
                    resolve(at("2025-01-10T06:00:00Z"), at("2025-01-11T06:10:00Z"), at("2025-01-12T17:00:00Z"));
            assertThat(Analytics.busiestHour(resolved)).hasValue(new HourBucket(7, 2));
        }

        @Test
        void hasNoBusiestHourWhenNothingWasRecorded() {
            assertThat(Analytics.busiestHour(List.of())).isEmpty();
        }
    }

    @Nested
    class ByRole {

        private final List<ObligationResult> results = List.of(
                Builders.aResult("2025-01-06", MET),
                Builders.aResult("2025-01-07", MET),
                Builders.aResult("2025-01-20", MISSED),
                Builders.aResult("2025-01-21", MET));
        private final Function<ObligationResult, DayRole> roleOf =
                entry -> entry.obligation().dueOn().day() < 15 ? DayRole.ONSHIFT : DayRole.OFFSHIFT;

        @Test
        void splitsTheResultsByTheRoleOfTheDay() {
            List<RoleSplit> split = Analytics.byRole(results, roleOf);
            assertThat(split.stream().map(RoleSplit::role)).containsExactly(DayRole.ONSHIFT, DayRole.OFFSHIFT);
            assertThat(split.get(0).rate()).isEqualTo(1.0);
            assertThat(split.get(1).rate()).isEqualTo(0.5);
        }

        @Test
        void countsThePartialsAndMissesOnEachRole() {
            List<ObligationResult> mixed = List.of(
                    Builders.aResult("2025-01-06", PARTIAL),
                    Builders.aResult("2025-01-07", MET),
                    Builders.aResult("2025-01-20", MISSED));
            List<RoleSplit> split = Analytics.byRole(mixed, roleOf);
            assertThat(split.get(0).partial()).isEqualTo(1);
            assertThat(split.get(0).missed()).isZero();
            assertThat(split.get(1).missed()).isEqualTo(1);
            assertThat(split.get(1).owed()).isEqualTo(1);
        }

        @Test
        void leavesOutARoleNothingFellOn() {
            assertThat(Analytics.byRole(results, entry -> DayRole.ONSHIFT)).hasSize(1);
        }

        @Test
        void readsAnEmptyRunAsNothingAtAll() {
            assertThat(Analytics.byRole(List.of(), roleOf)).isEmpty();
        }
    }

    @Nested
    class TrendOf {

        @Test
        void needsThreeWindowsBeforeItSaysAnything() {
            assertThat(Analytics.trendOf(List.of(summary(0.5), summary(0.9)))).isEqualTo(Trend.UNKNOWN);
            assertThat(Analytics.trendOf(List.of())).isEqualTo(Trend.UNKNOWN);
        }

        @Test
        void readsAJumpAsRising() {
            assertThat(Analytics.trendOf(List.of(summary(0.5), summary(0.5), summary(0.9))))
                    .isEqualTo(Trend.RISING);
        }

        @Test
        void readsADropAsFalling() {
            assertThat(Analytics.trendOf(List.of(summary(0.9), summary(0.9), summary(0.4))))
                    .isEqualTo(Trend.FALLING);
        }

        @Test
        void readsAWobbleAsSteady() {
            assertThat(Analytics.trendOf(List.of(summary(0.7), summary(0.72), summary(0.71))))
                    .isEqualTo(Trend.STEADY);
        }

        @Test
        void ignoresAWindowThatOwedNothing() {
            assertThat(Analytics.trendOf(List.of(summary(0.5), summary(0.5), summary(1, 0), summary(0.9))))
                    .isEqualTo(Trend.RISING);
        }

        @Test
        void takesATolerance() {
            assertThat(Analytics.trendOf(List.of(summary(0.7), summary(0.7), summary(0.74)), 0.01))
                    .isEqualTo(Trend.RISING);
        }

        @Test
        void saysWhatItMeansInWords() {
            assertThat(Trend.RISING.describe()).contains("better");
            assertThat(Trend.UNKNOWN.describe()).contains("not enough");
        }
    }
}
