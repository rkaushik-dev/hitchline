package io.github.rkaushikdev.hitchline.domain.habit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HabitHistoryTest {

    private static final Habit HABIT =
            Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-01-06"));

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static RevisionInput from(String day, String cadence) {
        return RevisionInput.of(d(day), Cadence.parse(cadence));
    }

    private static HabitRevision latest(HabitHistory history) {
        return history.latest().orElseThrow();
    }

    @Nested
    class TheFirstRevision {

        @Test
        void startsOnTheDayTheHabitDid() {
            HabitHistory history = HabitHistory.of(HABIT);
            assertThat(history.versionCount()).isEqualTo(1);
            assertThat(latest(history).from()).isEqualTo(d("2025-01-06"));
            assertThat(latest(history).version()).isEqualTo(1);
        }

        @Test
        void carriesTheCadenceTheHabitWasMadeWith() {
            assertThat(latest(HabitHistory.of(HABIT)).cadence().format()).isEqualTo("1/onshift-day");
        }
    }

    @Nested
    class Revising {

        private final HabitHistory history = HabitHistory.of(HABIT);

        @Test
        void addsAVersion() {
            HabitHistory revised = history.revise(from("2025-03-01", "3/hitch"));
            assertThat(revised.versionCount()).isEqualTo(2);
            assertThat(latest(revised).version()).isEqualTo(2);
        }

        @Test
        void leavesTheHistoryItWasGivenAlone() {
            history.revise(from("2025-03-01", "3/hitch"));
            assertThat(history.versionCount()).isEqualTo(1);
        }

        @Test
        void carriesTheGraceWindowForwardWhenNoneIsGiven() {
            HabitHistory withGrace =
                    HabitHistory.of(Fixtures.aHabit(o -> o.graceDays(2).startsOn("2025-01-06")));
            assertThat(latest(withGrace.revise(from("2025-03-01", "3/hitch"))).graceDays())
                    .isEqualTo(2);
        }

        @Test
        void refusesARevisionThatStartsBeforeTheOneInForce() {
            assertThatThrownBy(() -> history.revise(from("2024-12-01", "3/hitch")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesTwoRevisionsStartingOnTheSameDay() {
            assertThatThrownBy(() -> history.revise(from("2025-01-06", "3/hitch")))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void boundsTheNoteAndTheGraceWindow() {
            assertThatThrownBy(
                            () -> history.revise(from("2025-03-01", "3/hitch").withNote("x".repeat(241))))
                    .hasMessageContaining("at most 240");
            assertThatThrownBy(
                            () -> history.revise(from("2025-03-01", "3/hitch").withGraceDays(9)))
                    .hasMessageContaining("between 0 and 7");
        }
    }

    @Nested
    class ReadingAHistoryBack {

        private final HabitHistory history = HabitHistory.of(HABIT)
                .revise(from("2025-03-01", "3/hitch"))
                .revise(from("2025-06-01", "1/day").withTarget(HabitTarget.parse("at least 20 minutes")));

        @Test
        void findsNothingBeforeTheHabitStarted() {
            assertThat(history.effectiveOn(d("2025-01-05"))).isEmpty();
        }

        @Test
        void findsTheFirstRevisionOnTheDayItStarted() {
            assertThat(history.effectiveOn(d("2025-01-06")).map(HabitRevision::version))
                    .hasValue(1);
        }

        @Test
        void holdsARevisionUntilTheNextOneStarts() {
            assertThat(history.effectiveOn(d("2025-02-28")).map(HabitRevision::version))
                    .hasValue(1);
            assertThat(history.effectiveOn(d("2025-03-01")).map(HabitRevision::version))
                    .hasValue(2);
            assertThat(history.effectiveOn(d("2025-05-31")).map(HabitRevision::version))
                    .hasValue(2);
            assertThat(history.effectiveOn(d("2025-06-01")).map(HabitRevision::version))
                    .hasValue(3);
        }

        @Test
        void holdsTheLastRevisionForeverAfter() {
            assertThat(history.effectiveOn(d("2030-01-01")).map(HabitRevision::version))
                    .hasValue(3);
        }
    }

    @Nested
    class ScoringAgainstADate {

        private final HabitHistory history = HabitHistory.of(HABIT)
                .revise(from("2025-03-01", "3/hitch").withTarget(HabitTarget.parse("at least 20 minutes")));

        @Test
        void readsTheCadenceInForceThatDay() {
            assertThat(history.asOf(HABIT, d("2025-02-01")).cadence().format()).isEqualTo("1/onshift-day");
            assertThat(history.asOf(HABIT, d("2025-03-02")).cadence().format()).isEqualTo("3/hitch");
        }

        @Test
        void readsTheTargetInForceThatDay() {
            assertThat(history.asOf(HABIT, d("2025-02-01")).target()).isEmpty();
            assertThat(history.asOf(HABIT, d("2025-03-02")).target().map(HabitTarget::amount))
                    .hasValue(20.0);
        }

        @Test
        void leavesTheHabitAloneBeforeAnyRevisionApplies() {
            assertThat(history.asOf(HABIT, d("2024-12-01"))).isSameAs(HABIT);
        }

        @Test
        void keepsEverythingElseAboutTheHabit() {
            Habit scored = history.asOf(HABIT, d("2025-03-02"));
            assertThat(scored.id()).isEqualTo(HABIT.id());
            assertThat(scored.name()).isEqualTo(HABIT.name());
            assertThat(scored.startsOn()).isEqualTo(HABIT.startsOn());
        }
    }

    @Nested
    class DescribingARevision {

        @Test
        void readsBackWithItsVersionAndCadence() {
            assertThat(latest(HabitHistory.of(HABIT)).describe())
                    .isEqualTo("v1 from 2025-01-06: 1/onshift-day (as created)");
        }

        @Test
        void includesATargetWhenThereIsOne() {
            HabitHistory history = HabitHistory.of(HABIT)
                    .revise(from("2025-03-01", "1/day").withTarget(HabitTarget.parse("at least 20 minutes")));
            assertThat(latest(history).describe()).isEqualTo("v2 from 2025-03-01: 1/day, at least 20 minutes");
        }
    }
}
