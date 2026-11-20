package io.github.rkaushikdev.hitchline.domain.habit;

import static io.github.rkaushikdev.hitchline.domain.habit.TargetUnit.COUNT;
import static io.github.rkaushikdev.hitchline.domain.habit.TargetUnit.GRAMS;
import static io.github.rkaushikdev.hitchline.domain.habit.TargetUnit.KILOMETRES;
import static io.github.rkaushikdev.hitchline.domain.habit.TargetUnit.LITRES;
import static io.github.rkaushikdev.hitchline.domain.habit.TargetUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HabitTargetTest {

    @Nested
    class Units {

        @Test
        void recognisesTheUnitsItTracks() {
            assertThat(TargetUnit.isTargetUnit("minutes")).isTrue();
            assertThat(TargetUnit.isTargetUnit("furlongs")).isFalse();
        }

        @Test
        void readsTheShortWordsPeopleType() {
            assertThat(TargetUnit.unitFor("min")).isEqualTo(MINUTES);
            assertThat(TargetUnit.unitFor("KM")).isEqualTo(KILOMETRES);
            assertThat(TargetUnit.unitFor(" times ")).isEqualTo(COUNT);
        }

        @Test
        void refusesAUnitItDoesNotTrack() {
            assertThatThrownBy(() -> TargetUnit.unitFor("furlongs")).hasMessageContaining("not a unit this tracks");
        }

        @Test
        void keepsWholeNumbersForCountingUnits() {
            assertThat(COUNT.round(3.4)).isEqualTo(3.0);
            assertThat(MINUTES.round(30.6)).isEqualTo(31.0);
        }

        @Test
        void keepsTwoPlacesForMeasuredUnits() {
            assertThat(KILOMETRES.round(5.238)).isEqualTo(5.24);
            assertThat(LITRES.round(1.005)).isEqualTo(1.01);
        }
    }

    @Nested
    class BuildingATarget {

        @Test
        void defaultsToAtLeast() {
            assertThat(HabitTarget.of(MINUTES, 30)).isEqualTo(new HabitTarget(MINUTES, 30, TargetComparison.AT_LEAST));
        }

        @Test
        void refusesAZeroOrNegativeAmount() {
            assertThatThrownBy(() -> HabitTarget.of(MINUTES, 0)).hasMessageContaining("positive number");
            assertThatThrownBy(() -> HabitTarget.of(MINUTES, -5)).hasMessageContaining("positive number");
        }

        @Test
        void refusesAnAmountThatRoundsAwayToNothing() {
            assertThatThrownBy(() -> HabitTarget.of(COUNT, 0.2)).hasMessageContaining("rounds to nothing");
        }

        @Test
        void refusesAnAbsurdAmount() {
            assertThatThrownBy(() -> HabitTarget.of(GRAMS, 100_001)).hasMessageContaining("at most 100000");
        }
    }

    @Nested
    class Parsing {

        @Test
        void readsAnAtLeastTarget() {
            assertThat(HabitTarget.parse("at least 30 minutes")).isEqualTo(HabitTarget.of(MINUTES, 30));
        }

        @Test
        void readsAnAtMostTarget() {
            assertThat(HabitTarget.parse("at most 2 litres"))
                    .isEqualTo(HabitTarget.of(LITRES, 2, TargetComparison.AT_MOST));
        }

        @Test
        void readsADecimalAmount() {
            assertThat(HabitTarget.parse("at least 5.5 km").amount()).isEqualTo(5.5);
        }

        @Test
        void refusesATargetItCannotRead() {
            assertThatThrownBy(() -> HabitTarget.parse("30 minutes"))
                    .hasMessageContaining("must look like at least 30 minutes");
            assertThatThrownBy(() -> HabitTarget.parse("roughly 30 minutes")).hasMessageContaining("must look like");
        }

        @Test
        void roundTrips() {
            assertThat(HabitTarget.parse("at most 2 litres").format()).isEqualTo("at most 2 litres");
            assertThat(HabitTarget.parse("at least 30 min").format()).isEqualTo("at least 30 minutes");
        }
    }

    @Nested
    class MeetingATarget {

        private final HabitTarget half = HabitTarget.of(MINUTES, 30);
        private final HabitTarget limit = HabitTarget.of(LITRES, 2, TargetComparison.AT_MOST);

        @Test
        void isMetAtTheBoundary() {
            assertThat(half.isMetBy(30)).isTrue();
            assertThat(limit.isMetBy(2)).isTrue();
        }

        @Test
        void isNotMetBelowAnAtLeastTarget() {
            assertThat(half.isMetBy(29)).isFalse();
        }

        @Test
        void isNotMetAboveAnAtMostTarget() {
            assertThat(limit.isMetBy(2.01)).isFalse();
        }

        @Test
        void roundsTheReadingBeforeComparing() {
            assertThat(half.isMetBy(29.6)).isTrue();
            assertThat(limit.isMetBy(2.004)).isTrue();
        }
    }

    @Nested
    class Progress {

        @Test
        void runsFromZeroToOneForAnAtLeastTarget() {
            HabitTarget target = HabitTarget.of(MINUTES, 40);
            assertThat(target.progress(0)).isEqualTo(0.0);
            assertThat(target.progress(10)).isEqualTo(0.25);
            assertThat(target.progress(40)).isEqualTo(1.0);
        }

        @Test
        void doesNotGoPastOne() {
            assertThat(HabitTarget.of(MINUTES, 40).progress(400)).isEqualTo(1.0);
        }

        @Test
        void isAllOrNothingForAnAtMostTarget() {
            HabitTarget target = HabitTarget.of(LITRES, 2, TargetComparison.AT_MOST);
            assertThat(target.progress(1)).isEqualTo(1.0);
            assertThat(target.progress(3)).isEqualTo(0.0);
        }
    }

    @Nested
    class SummingReadings {

        @Test
        void addsAndRoundsOnceAtTheEnd() {
            assertThat(KILOMETRES.sum(List.of(1.234, 2.345, 0.001))).isEqualTo(3.58);
        }

        @Test
        void sumsNothingToZero() {
            assertThat(MINUTES.sum(List.of())).isEqualTo(0.0);
        }
    }
}
