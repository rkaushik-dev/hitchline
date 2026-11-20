package io.github.rkaushikdev.hitchline.domain.habit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HabitTest {

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static Habit.Builder input() {
        return Habit.builder(
                Ids.habitId("hab_0123456789ABCDEF"),
                Ids.crewMemberId("mem_0123456789ABCDEF"),
                "Twenty minutes on the treadmill",
                Cadence.parse("1/onshift-day"),
                d("2025-01-06"),
                Zones.parseInstant("2025-01-04T09:00:00Z"));
    }

    private static Habit habit() {
        return input().build();
    }

    @Nested
    class NamesAndTags {

        @Test
        void squeezesWhitespaceInAName() {
            assertThat(Habit.checkName("  Twenty   minutes ")).isEqualTo("Twenty minutes");
        }

        @Test
        void rejectsANameThatIsTooShortOrTooLong() {
            assertThatThrownBy(() -> Habit.checkName("go")).hasMessageContaining("at least 3 characters");
            assertThatThrownBy(() -> Habit.checkName("x".repeat(101))).hasMessageContaining("at most 100 characters");
        }

        @Test
        void lowercasesSortsAndDedupesTags() {
            assertThat(Habit.checkTags(List.of("Fitness", "sleep", "fitness"))).containsExactly("fitness", "sleep");
        }

        @Test
        void rejectsATagWithSpacesOrPunctuation() {
            assertThatThrownBy(() -> Habit.checkTags(List.of("off shift"))).hasMessageContaining("unusable tag");
            assertThatThrownBy(() -> Habit.checkTags(List.of("-leading"))).hasMessageContaining("unusable tag");
        }

        @Test
        void rejectsTooManyTags() {
            assertThatThrownBy(() -> Habit.checkTags(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")))
                    .hasMessageContaining("at most 8");
        }
    }

    @Nested
    class CreatingAHabit {

        @Test
        void defaultsToActiveWithNoTargetAndNoGrace() {
            Habit entry = habit();
            assertThat(entry.status()).isEqualTo(HabitStatus.ACTIVE);
            assertThat(entry.target()).isEmpty();
            assertThat(entry.graceDays()).isZero();
            assertThat(entry.tags()).isEmpty();
        }

        @Test
        void keepsTheTargetWhenOneIsGiven() {
            Habit entry =
                    input().target(HabitTarget.parse("at least 20 minutes")).build();
            assertThat(entry.target().map(HabitTarget::amount)).hasValue(20.0);
        }

        @Test
        void rejectsAnEndDateBeforeTheStart() {
            assertThatThrownBy(() -> input().endsOn(d("2025-01-01")).build())
                    .hasMessageContaining("before the habit starts");
        }

        @Test
        void rejectsAGraceWindowOutsideTheAllowedRange() {
            assertThatThrownBy(() -> input().graceDays(8).build()).hasMessageContaining("between 0 and 7");
            assertThatThrownBy(() -> input().graceDays(-1).build()).hasMessageContaining("between 0 and 7");
        }
    }

    @Nested
    class WhenAHabitIsRunning {

        @Test
        void isNotRunningBeforeItStarts() {
            assertThat(habit().isRunningOn(d("2025-01-05"))).isFalse();
            assertThat(habit().isRunningOn(d("2025-01-06"))).isTrue();
        }

        @Test
        void stopsAfterTheEndDate() {
            Habit bounded = input().endsOn(d("2025-03-31")).build();
            assertThat(bounded.isRunningOn(d("2025-03-31"))).isTrue();
            assertThat(bounded.isRunningOn(d("2025-04-01"))).isFalse();
        }

        @Test
        void runsForeverWhenNoEndDateIsSet() {
            assertThat(habit().isRunningOn(d("2030-01-01"))).isTrue();
        }

        @Test
        void isNotRunningWhilePausedOrArchived() {
            Habit paused = habit().moveTo(HabitStatus.PAUSED);
            assertThat(paused.isRunningOn(d("2025-02-01"))).isFalse();
            assertThat(paused.moveTo(HabitStatus.ARCHIVED).isRunningOn(d("2025-02-01")))
                    .isFalse();
        }
    }

    @Nested
    class StatusMoves {

        private final Habit entry = habit();

        @Test
        void pausesAndResumes() {
            Habit paused = entry.moveTo(HabitStatus.PAUSED);
            assertThat(paused.status()).isEqualTo(HabitStatus.PAUSED);
            assertThat(paused.moveTo(HabitStatus.ACTIVE).status()).isEqualTo(HabitStatus.ACTIVE);
        }

        @Test
        void treatsAMoveToTheSameStatusAsANoOp() {
            assertThat(entry.moveTo(HabitStatus.ACTIVE)).isSameAs(entry);
        }

        @Test
        void refusesToBringBackAnArchivedHabit() {
            Habit archived = entry.moveTo(HabitStatus.ARCHIVED);
            assertThatThrownBy(() -> archived.moveTo(HabitStatus.ACTIVE))
                    .hasMessageContaining("cannot go from archived to active");
        }
    }

    @Nested
    class Edits {

        private final Habit entry = habit();

        @Test
        void renames() {
            assertThat(entry.rename("Thirty minutes on the bike").name()).isEqualTo("Thirty minutes on the bike");
        }

        @Test
        void changesTheTargetAndClearsIt() {
            Habit targeted = entry.retarget(Optional.of(HabitTarget.parse("at least 30 minutes")));
            assertThat(targeted.target().map(HabitTarget::amount)).hasValue(30.0);
            assertThat(targeted.retarget(Optional.empty()).target()).isEmpty();
        }

        @Test
        void changesTheCadence() {
            assertThat(entry.recadence(Cadence.parse("3/hitch")).cadence()).isEqualTo(Cadence.parse("3/hitch"));
        }

        @Test
        void refusesEditsToAnArchivedHabit() {
            Habit archived = entry.moveTo(HabitStatus.ARCHIVED);
            assertThatThrownBy(() -> archived.recadence(Cadence.parse("3/hitch")))
                    .hasMessageContaining("archived habit");
            assertThatThrownBy(() -> archived.retarget(Optional.empty())).hasMessageContaining("archived habit");
        }

        @Test
        void retagsAndReadsTagsBack() {
            Habit tagged = entry.retag(List.of("Fitness", "cardio"));
            assertThat(tagged.tags()).containsExactly("cardio", "fitness");
            assertThat(tagged.hasTag("Fitness")).isTrue();
            assertThat(tagged.hasTag("sleep")).isFalse();
        }
    }

    @Nested
    class DescribingAHabit {

        @Test
        void readsBackTheCadenceAndTarget() {
            Habit entry =
                    input().target(HabitTarget.parse("at least 20 minutes")).build();
            assertThat(entry.describe())
                    .isEqualTo("Twenty minutes on the treadmill 1/onshift-day, at least 20 minutes");
        }

        @Test
        void leavesTheTargetOutWhenThereIsNone() {
            assertThat(habit().describe()).isEqualTo("Twenty minutes on the treadmill 1/onshift-day");
        }
    }
}
