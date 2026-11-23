package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.Digest.HabitLine;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DigestTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String SLEEP = Ids.habitId("hab_FEDCBA9876543210");
    private static final DateRange WINDOW = DateRange.parse("2025-06-02..2025-06-15");
    private static final PeriodSummary SUMMARY = new PeriodSummary(WINDOW, 4, 3, 0, 1, 0, 0, 0.75);

    private static ObligationResult result(String habit, String day, AdherenceStatus status) {
        boolean met = status == AdherenceStatus.MET;
        return new ObligationResult(
                new Obligation(habit, ADA, ObligationScope.DAY, DateRange.parse(day + ".." + day), 1),
                met ? 1 : 0,
                OptionalDouble.empty(),
                status,
                met ? 1 : 0);
    }

    private static Map<String, StreakSummary> streaks(int treadmill, int sleep) {
        Map<String, StreakSummary> map = new LinkedHashMap<>();
        map.put(TREADMILL, new StreakSummary(treadmill, treadmill, Optional.empty(), 0, Optional.empty()));
        map.put(SLEEP, new StreakSummary(sleep, sleep, Optional.empty(), 0, Optional.empty()));
        return map;
    }

    private static List<ObligationResult> results() {
        return List.of(
                result(TREADMILL, "2025-06-02", AdherenceStatus.MET),
                result(TREADMILL, "2025-06-03", AdherenceStatus.MET),
                result(SLEEP, "2025-06-02", AdherenceStatus.MET),
                result(SLEEP, "2025-06-03", AdherenceStatus.MISSED));
    }

    private static Digest anInput() {
        return anInput(SUMMARY, results(), streaks(4, 0));
    }

    private static Digest anInput(PeriodSummary summary) {
        return anInput(summary, results(), streaks(4, 0));
    }

    private static Digest anInput(List<ObligationResult> results) {
        return anInput(SUMMARY, results, streaks(4, 0));
    }

    private static Digest anInput(
            PeriodSummary summary, List<ObligationResult> results, Map<String, StreakSummary> streaks) {
        return new Digest(
                Fixtures.aMember(),
                summary,
                List.of(Fixtures.aHabit(), Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                        .name("Bank the sleep hours"))),
                results,
                streaks);
    }

    private static PeriodSummary summary(int owed, int met, int missed, int pending, int shieldedDays, double rate) {
        return new PeriodSummary(WINDOW, owed, met, 0, missed, pending, shieldedDays, rate);
    }

    @Nested
    class PartDoneDays {

        private final List<ObligationResult> half = List.of(
                result(TREADMILL, "2025-06-02", AdherenceStatus.MET),
                result(TREADMILL, "2025-06-03", AdherenceStatus.PARTIAL),
                result(TREADMILL, "2025-06-04", AdherenceStatus.PARTIAL),
                result(TREADMILL, "2025-06-05", AdherenceStatus.PENDING));

        @Test
        void countsThemOnTheHabitLine() {
            HabitLine line = anInput(half).habitLines().get(0);
            assertThat(line.owed()).isEqualTo(3);
            assertThat(line.met()).isEqualTo(1);
            assertThat(line.partial()).isEqualTo(2);
        }

        @Test
        void saysHowManyWereDoneInPart() {
            assertThat(anInput(half).text())
                    .contains("- Twenty minutes on the treadmill: 1 of 3, 2 part done, 4 in a row");
            assertThat(anInput().text()).doesNotContain("part done");
        }
    }

    @Nested
    class TheHeadline {

        @Test
        void saysWhatWasDoneOutOfWhatWasOwed() {
            assertThat(anInput().headline()).isEqualTo("Ada Vasquez, you did 3 of 4 over 2025-06-02..2025-06-15.");
        }

        @Test
        void saysSoWhenEverythingWasCleared() {
            assertThat(anInput(summary(4, 4, 0, 0, 0, 1)).headline()).contains("you cleared everything");
        }

        @Test
        void saysSoWhenNothingWasOwed() {
            assertThat(anInput(summary(0, 0, 0, 0, 0, 1)).headline()).contains("nothing was owed");
        }

        @Test
        void leavesPendingObligationsOutOfTheDenominator() {
            assertThat(anInput(summary(6, 3, 1, 2, 0, 0.75)).headline()).contains("3 of 4");
        }
    }

    @Nested
    class TheHabitLines {

        @Test
        void givesOneLinePerHabitThatOwedSomething() {
            assertThat(anInput().habitLines().stream().map(HabitLine::name))
                    .containsExactly("Twenty minutes on the treadmill", "Bank the sleep hours");
        }

        @Test
        void leavesOutAHabitThatOwedNothing() {
            assertThat(anInput(List.of(result(TREADMILL, "2025-06-02", AdherenceStatus.MET)))
                            .habitLines())
                    .hasSize(1);
        }

        @Test
        void leavesPendingObligationsOutOfTheCount() {
            Digest open = anInput(List.of(
                    result(TREADMILL, "2025-06-02", AdherenceStatus.MET),
                    result(TREADMILL, "2025-06-04", AdherenceStatus.PENDING)));
            assertThat(open.habitLines().get(0).owed()).isEqualTo(1);
        }

        @Test
        void carriesTheRunFromTheStreaksItWasGiven() {
            assertThat(anInput().habitLines().get(0).streak()).isEqualTo(4);
        }
    }

    @Nested
    class TheNextTrip {

        @Test
        void namesTheFirstDayOfTheNextTripAfterTheWindow() {
            // A fortnight each way from 6 January puts a trip on 26 May and the next on 23 June.
            assertThat(anInput().nextTripStarts()).contains(CivilDate.parse("2025-06-23"));
            assertThat(anInput().text()).endsWith("\n\nNext trip starts 2025-06-23.\n");
        }

        @Test
        void startsTheNextTripOnItsTravelDay() {
            Digest flyer = new Digest(
                    Fixtures.aMember(o -> o.pattern("1travel 13on 14off")),
                    SUMMARY,
                    List.of(Fixtures.aHabit()),
                    results(),
                    streaks(0, 0));
            assertThat(flyer.nextTripStarts()).contains(CivilDate.parse("2025-06-23"));
        }

        @Test
        void describesEachHabitLineOnItsOwn() {
            assertThat(new Digest.HabitLine("Call home", 2, 1, 0, 3).describe())
                    .isEqualTo("- Call home: 1 of 2, 3 in a row");
            assertThat(new Digest.HabitLine("Call home", 0, 0, 0, 0).describe())
                    .isEqualTo("- Call home: nothing was owed");
        }

        @Test
        void picksTheFirstListedHabitWhenTwoRunsTie() {
            String text = anInput(SUMMARY, results(), streaks(5, 5)).text();
            assertThat(text).contains("Longest run this hitch: Twenty minutes on the treadmill at 5.");
        }
    }

    @Nested
    class TheWholeDigest {

        @Test
        void opensWithTheHeadlineAndListsTheHabits() {
            List<String> lines = Texts.split(anInput().text(), "\n");
            assertThat(lines.get(0)).contains("Ada Vasquez");
            assertThat(lines.get(2)).isEqualTo("- Twenty minutes on the treadmill: 2 of 2, 4 in a row");
            assertThat(lines.get(3)).isEqualTo("- Bank the sleep hours: 1 of 2");
        }

        @Test
        void mentionsShieldedDaysWhenThereWereAny() {
            assertThat(anInput(summary(4, 3, 1, 0, 3, 0.75)).text()).contains("3 days were shielded");
        }

        @Test
        void saysNothingAboutShieldedDaysWhenThereWereNone() {
            assertThat(anInput().text()).doesNotContain("shielded");
        }

        @Test
        void callsOutTheLongestRunWhenItIsWorthMentioning() {
            assertThat(anInput().text()).contains("Longest run this hitch: Twenty minutes on the treadmill at 4.");
        }

        @Test
        void saysNothingAboutARunOfOneOrTwo() {
            assertThat(anInput(SUMMARY, results(), streaks(2, 0)).text()).doesNotContain("Longest run");
        }

        @Test
        void endsWithExactlyOneNewline() {
            String text = anInput().text();
            assertThat(text).endsWith("\n");
            assertThat(text).doesNotEndWith("\n\n");
        }
    }

    @Nested
    class TheSubjectLine {

        @Test
        void namesTheWindowAndThePerson() {
            assertThat(anInput().subjectLine()).isEqualTo("hitchline: 2025-06-02..2025-06-15 for Ada Vasquez");
        }
    }
}
