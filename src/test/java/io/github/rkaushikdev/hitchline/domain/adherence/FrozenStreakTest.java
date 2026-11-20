package io.github.rkaushikdev.hitchline.domain.adherence;

import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MET;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MISSED;
import static io.github.rkaushikdev.hitchline.support.Builders.ADA;
import static io.github.rkaushikdev.hitchline.support.Builders.aRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FrozenStreakTest {

    private static FrozenStreak frozen(List<AdherenceStatus> statuses) {
        return FrozenStreak.compute(aRun("2025-06-02", statuses), List.of(), ADA);
    }

    private static FrozenStreak frozen(List<AdherenceStatus> statuses, FreezeBudget budget) {
        return FrozenStreak.compute(aRun("2025-06-02", statuses), List.of(), ADA, budget);
    }

    @Nested
    class TheBudget {

        @Test
        void allowsOneMissAMonthByDefault() {
            assertThat(FreezeBudget.DEFAULT).isEqualTo(new FreezeBudget(1, 28));
            assertThat(FreezeBudget.of(1, 28)).isEqualTo(FreezeBudget.DEFAULT);
        }

        @Test
        void refusesANonsenseAllowanceOrWindow() {
            assertThatThrownBy(() -> FreezeBudget.DEFAULT.withAllowance(-1)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> FreezeBudget.DEFAULT.withWindowDays(0)).hasMessageContaining("from 1 to 365");
            assertThatThrownBy(() -> FreezeBudget.DEFAULT.withAllowance(11)).hasMessageContaining("from 0 to 10");
        }
    }

    @Nested
    class CarryingARunOverAMiss {

        @Test
        void absorbsOneMissAndKeepsCounting() {
            FrozenStreak summary = frozen(List.of(MET, MET, MISSED, MET));
            assertThat(summary.current()).isEqualTo(3);
            assertThat(summary.frozen()).isEqualTo(1);
            assertThat(summary.freezesLeft()).isZero();
        }

        @Test
        void breaksOnTheSecondMissInsideTheWindow() {
            FrozenStreak summary = frozen(List.of(MET, MISSED, MET, MISSED, MET));
            assertThat(summary.current()).isEqualTo(1);
            assertThat(summary.frozen()).isEqualTo(1);
            assertThat(summary.brokenOn().map(CivilDate::toString)).hasValue("2025-06-05");
        }

        @Test
        void givesTheFreezeBackOnceTheWindowHasPassed() {
            List<AdherenceStatus> statuses = new ArrayList<>(List.of(MET, MISSED));
            for (int day = 0; day < 30; day += 1) {
                statuses.add(MET);
            }
            statuses.addAll(List.of(MISSED, MET));
            FrozenStreak summary = frozen(statuses);
            assertThat(summary.frozen()).isEqualTo(2);
            assertThat(summary.current()).isGreaterThan(30);
        }

        @Test
        void willNotFreezeTheFirstThingInARun() {
            FrozenStreak summary = frozen(List.of(MISSED, MET, MET));
            assertThat(summary.frozen()).isZero();
            assertThat(summary.current()).isEqualTo(2);
        }

        @Test
        void takesAWiderAllowance() {
            FrozenStreak summary = frozen(List.of(MET, MISSED, MISSED, MET), FreezeBudget.DEFAULT.withAllowance(2));
            assertThat(summary.current()).isEqualTo(2);
            assertThat(summary.frozen()).isEqualTo(2);
        }

        @Test
        void breaksEveryTimeWithNoAllowanceAtAll() {
            FrozenStreak summary = frozen(List.of(MET, MISSED, MET), FreezeBudget.DEFAULT.withAllowance(0));
            assertThat(summary.current()).isEqualTo(1);
            assertThat(summary.frozen()).isZero();
        }
    }

    @Nested
    class ShieldsAndFreezesTogether {

        @Test
        void spendsNothingOnADayThatWasShielded() {
            Shield leave = Shield.of(
                    ADA,
                    DateRange.parse("2025-06-04..2025-06-04"),
                    ShieldReason.ANNUAL_LEAVE,
                    Zones.parseInstant("2025-06-01T09:00:00Z"));
            FrozenStreak summary =
                    FrozenStreak.compute(aRun("2025-06-02", List.of(MET, MET, MISSED, MET)), List.of(leave), ADA);
            assertThat(summary.shielded()).isEqualTo(1);
            assertThat(summary.frozen()).isZero();
            assertThat(summary.current()).isEqualTo(3);
            assertThat(summary.freezesLeft()).isEqualTo(1);
        }
    }

    @Nested
    class ReadingItBack {

        @Test
        void saysWhatIsInHand() {
            assertThat(frozen(List.of(MET)).describe()).isEqualTo("1 freezes in hand");
            assertThat(frozen(List.of(MET, MISSED, MET)).describe()).isEqualTo("one day carried, 0 freezes in hand");
        }

        @Test
        void labelsTheLastDaySomethingWasMet() {
            FrozenStreak summary = frozen(List.of(MET));
            assertThat(summary.lastMetLabel()).isEqualTo("2025-06-02");
            assertThat(summary.withLastMetOn(Optional.empty()).lastMetLabel()).isEqualTo("never");
        }
    }

    @Nested
    class TheWindowTheFreezesAreCountedOver {

        @Test
        void isMeasuredInWholeDaysTheSameWayEverythingElseIs() {
            FreezeBudget budget = FreezeBudget.of(1, 3);
            FrozenStreak inside = frozen(List.of(MET, MISSED, MET, MISSED, MET), budget);
            assertThat(inside.current()).isEqualTo(1);

            FrozenStreak outside = frozen(List.of(MET, MISSED, MET, MET, MET, MISSED, MET), budget);
            // Both misses were carried, because the first freeze came back before the second one
            // was needed, so the run never broke.
            assertThat(outside.frozen()).isEqualTo(2);
            assertThat(outside.current()).isEqualTo(5);
        }

        @Test
        void countsAFreezeSpentOnTheBoundaryDayAsStillLive() {
            FrozenStreak summary = frozen(List.of(MET, MISSED, MISSED), FreezeBudget.of(1, 2));
            assertThat(summary.frozen()).isEqualTo(1);
            assertThat(summary.current()).isZero();
        }
    }
}
