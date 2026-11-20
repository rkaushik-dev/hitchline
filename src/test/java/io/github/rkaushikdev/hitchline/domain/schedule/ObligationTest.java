package io.github.rkaushikdev.hitchline.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.support.Signatures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ObligationTest {

    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String STRETCHES = Ids.habitId("hab_FEDCBA9876543210");
    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static Obligation an() {
        return day("2025-01-10");
    }

    private static Obligation day(String date) {
        return new Obligation(TREADMILL, ADA, ObligationScope.DAY, DateRange.parse(date + ".." + date), 1);
    }

    private static Obligation times(int count) {
        return new Obligation(TREADMILL, ADA, ObligationScope.DAY, DateRange.parse("2025-01-10..2025-01-10"), count);
    }

    private static Obligation perHitch() {
        return new Obligation(TREADMILL, ADA, ObligationScope.HITCH, DateRange.parse("2025-01-06..2025-02-02"), 3);
    }

    @Nested
    class CreatingAnObligation {

        @Test
        void takesTheDueDateFromTheEndOfTheWindow() {
            assertThat(perHitch().dueOn().toString()).isEqualTo("2025-02-02");
        }

        @Test
        void refusesACountBelowOne() {
            assertThatThrownBy(() -> times(0)).hasMessageContaining("above zero");
            // A count of 1.5 cannot be written: times is an int.
            assertThat(Signatures.parameterTypes(Obligation.class, "<init>"))
                    .containsExactly(
                            List.of(String.class, String.class, ObligationScope.class, DateRange.class, int.class));
        }
    }

    @Nested
    class Identity {

        @Test
        void keysOnTheHabitTheScopeAndTheDueDay() {
            assertThat(an().key()).isEqualTo("hab_0123456789ABCDEF:day:2025-01-10");
        }

        @Test
        void separatesTwoScopesThatFallDueOnTheSameDay() {
            assertThat(day("2025-02-02").key()).isNotEqualTo(perHitch().key());
        }
    }

    @Nested
    class TheWindow {

        @Test
        void acceptsACheckInAnywhereInsideIt() {
            assertThat(perHitch().acceptsCheckInOn(d("2025-01-06"))).isTrue();
            assertThat(perHitch().acceptsCheckInOn(d("2025-01-20"))).isTrue();
            assertThat(perHitch().acceptsCheckInOn(d("2025-02-02"))).isTrue();
        }

        @Test
        void turnsOneAwayFromOutsideIt() {
            assertThat(perHitch().acceptsCheckInOn(d("2025-01-05"))).isFalse();
            assertThat(perHitch().acceptsCheckInOn(d("2025-02-03"))).isFalse();
        }

        @Test
        void measuresHowMuchRoomThereWas() {
            assertThat(perHitch().span()).isEqualTo(28);
            assertThat(an().span()).isEqualTo(1);
        }

        @Test
        void isOverdueOnceTheWindowHasPassed() {
            assertThat(perHitch().isOverdueOn(d("2025-02-02"))).isFalse();
            assertThat(perHitch().isOverdueOn(d("2025-02-03"))).isTrue();
        }
    }

    @Nested
    class Collections {

        private final List<Obligation> entries = List.of(
                day("2025-01-12"),
                new Obligation(STRETCHES, ADA, ObligationScope.DAY, DateRange.parse("2025-01-10..2025-01-10"), 1),
                day("2025-01-10"));

        @Test
        void sortsByWindowThenByHabit() {
            assertThat(Obligation.sort(entries).stream().map(Obligation::key))
                    .containsExactly(
                            "hab_0123456789ABCDEF:day:2025-01-10",
                            "hab_FEDCBA9876543210:day:2025-01-10",
                            "hab_0123456789ABCDEF:day:2025-01-12");
        }

        @Test
        void filtersToOneHabit() {
            assertThat(Obligation.forHabit(entries, STRETCHES)).hasSize(1);
        }

        @Test
        void totalsWhatIsOwed() {
            assertThat(Obligation.totalTimes(entries)).isEqualTo(3);
            assertThat(Obligation.totalTimes(List.of(times(2), times(3)))).isEqualTo(5);
        }
    }

    @Nested
    class DescribingAnObligation {

        @Test
        void readsOnceForASingleTime() {
            assertThat(an().describe()).isEqualTo("once per day over 2025-01-10..2025-01-10");
        }

        @Test
        void countsUpForMore() {
            assertThat(perHitch().describe()).isEqualTo("3 times per hitch over 2025-01-06..2025-02-02");
        }
    }
}
