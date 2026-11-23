package io.github.rkaushikdev.hitchline.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Cadence;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitHistory;
import io.github.rkaushikdev.hitchline.domain.habit.RevisionInput;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExpansionTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static final Roster PLAIN = Roster.of(MEMBER);

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    private static List<Obligation> expand(Roster roster, Habit habit, String window) {
        return Expansion.of(roster, habit, r(window)).expand();
    }

    private static List<String> windows(List<Obligation> entries) {
        return entries.stream().map(entry -> entry.window().toString()).toList();
    }

    private static Roster heldOver() {
        return Roster.of(
                MEMBER,
                List.of(RosterOverride.of(
                        MEMBER.id(),
                        r("2025-01-20..2025-01-22"),
                        DayRole.ONSHIFT,
                        OverrideReason.HELD_OVER,
                        Zones.parseInstant("2025-01-18T10:00:00Z"))));
    }

    @Nested
    class AWeeklyCadence {

        @Test
        void asksOncePerCalendarWeekFromMondayToSunday() {
            List<Obligation> entries = expand(
                    PLAIN, Fixtures.aHabit(o -> o.cadence("2/week").startsOn("2025-01-01")), "2025-01-08..2025-01-21");
            assertThat(windows(entries))
                    .containsExactly("2025-01-06..2025-01-12", "2025-01-13..2025-01-19", "2025-01-20..2025-01-26");
            assertThat(entries).allMatch(entry -> entry.scope() == ObligationScope.WEEK && entry.times() == 2);
        }

        @Test
        void clipsTheFirstWeekToTheDayTheHabitStarted() {
            List<Obligation> entries = expand(
                    PLAIN, Fixtures.aHabit(o -> o.cadence("1/week").startsOn("2025-01-09")), "2025-01-06..2025-01-12");
            assertThat(windows(entries)).containsExactly("2025-01-09..2025-01-12");
        }

        @Test
        void asksTheSameWhetherOnShiftOrAtHome() {
            List<Obligation> entries = expand(
                    PLAIN, Fixtures.aHabit(o -> o.cadence("1/week").startsOn("2025-01-01")), "2025-01-06..2025-02-02");
            assertThat(entries).hasSize(4);
            assertThat(entries.get(2).key()).isEqualTo("hab_0123456789ABCDEF:week:2025-01-26");
        }
    }

    @Nested
    class ACalendarCadence {

        @Test
        void asksOnceOnEveryDayOfTheWindow() {
            List<Obligation> entries =
                    expand(PLAIN, Fixtures.aHabit(o -> o.cadence("1/day")), "2025-01-06..2025-01-09");
            assertThat(entries).hasSize(4);
            assertThat(entries.stream().map(Obligation::key))
                    .containsExactly(
                            "hab_0123456789ABCDEF:day:2025-01-06",
                            "hab_0123456789ABCDEF:day:2025-01-07",
                            "hab_0123456789ABCDEF:day:2025-01-08",
                            "hab_0123456789ABCDEF:day:2025-01-09");
        }

        @Test
        void carriesTheCountThrough() {
            List<Obligation> entries =
                    expand(PLAIN, Fixtures.aHabit(o -> o.cadence("3/day")), "2025-01-06..2025-01-07");
            assertThat(entries).allMatch(entry -> entry.times() == 3);
        }

        @Test
        void stopsAtTheHabitStartAndEndDates() {
            Habit bounded = Fixtures.aHabit(
                    o -> o.cadence("1/day").startsOn("2025-01-08").endsOn("2025-01-09"));
            assertThat(windows(expand(PLAIN, bounded, "2025-01-06..2025-01-12")))
                    .containsExactly("2025-01-08..2025-01-08", "2025-01-09..2025-01-09");
        }
    }

    @Nested
    class ARoleDayCadence {

        @Test
        void asksOnlyOnShiftDays() {
            assertThat(windows(
                            expand(PLAIN, Fixtures.aHabit(o -> o.cadence("1/onshift-day")), "2025-01-17..2025-01-22")))
                    .containsExactly("2025-01-17..2025-01-17", "2025-01-18..2025-01-18", "2025-01-19..2025-01-19");
        }

        @Test
        void asksForNothingOverAFortnightAtHome() {
            assertThat(expand(PLAIN, Fixtures.aHabit(o -> o.cadence("1/onshift-day")), "2025-01-20..2025-02-02"))
                    .isEmpty();
        }

        @Test
        void followsAnOverrideRatherThanThePattern() {
            assertThat(expand(heldOver(), Fixtures.aHabit(o -> o.cadence("1/onshift-day")), "2025-01-20..2025-01-24"))
                    .hasSize(3);
        }

        @Test
        void readsSeveralRolesAtOnce() {
            CrewMember withTravel = Fixtures.aMember(o -> o.pattern("1travel 13on 1travel 13off"));
            assertThat(expand(
                            Roster.of(withTravel),
                            Fixtures.aHabit(o -> o.cadence("1/onshift+travel-day")),
                            "2025-01-06..2025-01-08"))
                    .hasSize(3);
        }
    }

    @Nested
    class GraceDays {

        @Test
        void widensTheWindowACheckInMayLandIn() {
            Habit graced = Fixtures.aHabit(o -> o.cadence("1/day").graceDays(2));
            assertThat(windows(expand(PLAIN, graced, "2025-01-06..2025-01-07")))
                    .containsExactly("2025-01-06..2025-01-08", "2025-01-07..2025-01-09");
        }

        @Test
        void stillReportsTheDayItWasOwedOn() {
            Habit graced = Fixtures.aHabit(o -> o.cadence("1/day").graceDays(2));
            assertThat(expand(PLAIN, graced, "2025-01-06..2025-01-06").get(0).key())
                    .isEqualTo("hab_0123456789ABCDEF:day:2025-01-08");
        }
    }

    @Nested
    class ExpandAll {

        @Test
        void mergesSeveralHabitsIntoOneSortedList() {
            List<Obligation> entries = Expansion.expandAll(
                    PLAIN,
                    List.of(Fixtures.aHabit(o -> o.cadence("1/day")), Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                            .cadence("1/onshift-day"))),
                    r("2025-01-19..2025-01-20"));
            assertThat(entries.stream().map(Obligation::key))
                    .containsExactly(
                            "hab_0123456789ABCDEF:day:2025-01-19",
                            "hab_FEDCBA9876543210:day:2025-01-19",
                            "hab_0123456789ABCDEF:day:2025-01-20");
        }
    }

    @Nested
    class HabitWindow {

        @Test
        void clipsToTheLifeOfTheHabit() {
            Habit entry = Fixtures.aHabit(o -> o.startsOn("2025-01-08").endsOn("2025-01-20"));
            assertThat(Expansion.habitWindow(entry, r("2025-01-01..2025-01-31")).map(DateRange::toString))
                    .hasValue("2025-01-08..2025-01-20");
        }

        @Test
        void runsToTheEndOfTheWindowWhenTheHabitHasNoEnd() {
            Habit entry = Fixtures.aHabit(o -> o.startsOn("2025-01-08"));
            assertThat(Expansion.habitWindow(entry, r("2025-01-01..2025-01-31")).map(DateRange::toString))
                    .hasValue("2025-01-08..2025-01-31");
        }

        @Test
        void returnsNothingWhenTheHabitIsOverBeforeTheWindowOpens() {
            Habit entry = Fixtures.aHabit(o -> o.startsOn("2024-01-01").endsOn("2024-06-30"));
            assertThat(Expansion.habitWindow(entry, r("2025-01-01..2025-01-31")))
                    .isEmpty();
        }
    }

    @Nested
    class AHitchCadence {

        @Test
        void asksOnceForEachTurnOfTheRotation() {
            List<Obligation> entries =
                    expand(PLAIN, Fixtures.aHabit(o -> o.cadence("3/hitch")), "2025-01-06..2025-03-02");
            assertThat(windows(entries)).containsExactly("2025-01-06..2025-02-02", "2025-02-03..2025-03-02");
            assertThat(entries).allMatch(entry -> entry.times() == 3);
        }

        @Test
        void includesTheHitchAShortWindowSitsInside() {
            List<Obligation> entries =
                    expand(PLAIN, Fixtures.aHabit(o -> o.cadence("3/hitch")), "2025-01-20..2025-01-21");
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).window().toString()).isEqualTo("2025-01-06..2025-02-02");
        }

        @Test
        void clipsTheFirstHitchToTheDayTheHabitStarted() {
            Habit late = Fixtures.aHabit(o -> o.cadence("2/hitch").startsOn("2025-01-15"));
            assertThat(expand(PLAIN, late, "2025-01-06..2025-02-02")
                            .get(0)
                            .window()
                            .toString())
                    .isEqualTo("2025-01-15..2025-02-02");
        }

        @Test
        void isScopedAsAHitch() {
            List<Obligation> entries =
                    expand(PLAIN, Fixtures.aHabit(o -> o.cadence("2/hitch")), "2025-01-06..2025-01-20");
            assertThat(entries.get(0).scope()).isEqualTo(ObligationScope.HITCH);
            assertThat(entries.get(0).key()).isEqualTo("hab_0123456789ABCDEF:hitch:2025-02-02");
        }
    }

    @Nested
    class ABlockCadence {

        @Test
        void asksOnceForEachRunOfShiftDays() {
            assertThat(windows(expand(
                            PLAIN, Fixtures.aHabit(o -> o.cadence("1/onshift-block")), "2025-01-06..2025-03-02")))
                    .containsExactly("2025-01-06..2025-01-19", "2025-02-03..2025-02-16");
        }

        @Test
        void returnsAWholeBlockThatStartedBeforeTheWindow() {
            List<Obligation> entries =
                    expand(PLAIN, Fixtures.aHabit(o -> o.cadence("1/onshift-block")), "2025-01-15..2025-01-16");
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).window().toString()).isEqualTo("2025-01-06..2025-01-19");
        }

        @Test
        void followsAnOverrideThatExtendsTheBlock() {
            List<Obligation> entries =
                    expand(heldOver(), Fixtures.aHabit(o -> o.cadence("1/onshift-block")), "2025-01-06..2025-01-25");
            assertThat(entries.get(0).window().toString()).isEqualTo("2025-01-06..2025-01-22");
        }

        @Test
        void asksForAnArrivalBriefingInTheFirstDayOfEachTrip() {
            List<Obligation> entries = expand(
                    PLAIN, Fixtures.aHabit(o -> o.cadence("1/onshift-block within 1d")), "2025-01-06..2025-03-02");
            assertThat(windows(entries)).containsExactly("2025-01-06..2025-01-06", "2025-02-03..2025-02-03");
            assertThat(entries).allMatch(entry -> entry.scope() == ObligationScope.BLOCK);
        }

        @Test
        void keepsAnEarlyWindowInsideARunShorterThanIt() {
            Roster cutShort = Roster.of(
                    MEMBER,
                    List.of(RosterOverride.of(
                            MEMBER.id(),
                            r("2025-01-08..2025-01-19"),
                            DayRole.OFFSHIFT,
                            OverrideReason.MEDICAL,
                            Zones.parseInstant("2025-01-07T10:00:00Z"))));
            List<Obligation> entries = expand(
                    cutShort, Fixtures.aHabit(o -> o.cadence("1/onshift-block within 5d")), "2025-01-06..2025-01-07");
            assertThat(windows(entries)).containsExactly("2025-01-06..2025-01-07");
        }

        @Test
        void addsTheGraceDaysAfterTheEarlyWindow() {
            List<Obligation> entries = expand(
                    PLAIN,
                    Fixtures.aHabit(o -> o.cadence("1/onshift-block within 2d").graceDays(1)),
                    "2025-01-06..2025-01-19");
            assertThat(windows(entries)).containsExactly("2025-01-06..2025-01-08");
        }

        @Test
        void canBeAskedAboutTheOffShiftBlocksInstead() {
            List<Obligation> entries =
                    expand(PLAIN, Fixtures.aHabit(o -> o.cadence("2/offshift-block")), "2025-01-20..2025-02-02");
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).times()).isEqualTo(2);
            assertThat(entries.get(0).scope()).isEqualTo(ObligationScope.BLOCK);
        }
    }

    @Nested
    class ARollingWindowCadence {

        @Test
        void tilesFortnightsFromTheDayTheHabitStarted() {
            List<Obligation> entries = expand(
                    PLAIN, Fixtures.aHabit(o -> o.cadence("5/14d").startsOn("2025-01-06")), "2025-01-06..2025-02-16");
            assertThat(windows(entries))
                    .containsExactly("2025-01-06..2025-01-19", "2025-01-20..2025-02-02", "2025-02-03..2025-02-16");
            assertThat(entries).allMatch(entry -> entry.times() == 5);
        }

        @Test
        void doesNotSlideTheTilesToFitTheQuery() {
            assertThat(windows(expand(
                            PLAIN,
                            Fixtures.aHabit(o -> o.cadence("5/14d").startsOn("2025-01-06")),
                            "2025-01-15..2025-01-25")))
                    .containsExactly("2025-01-06..2025-01-19", "2025-01-20..2025-02-02");
        }

        @Test
        void ignoresTilesFromBeforeTheHabitExisted() {
            assertThat(windows(expand(
                            PLAIN,
                            Fixtures.aHabit(o -> o.cadence("2/7d").startsOn("2025-01-06")),
                            "2024-12-01..2025-01-12")))
                    .containsExactly("2025-01-06..2025-01-12");
        }

        @Test
        void clipsTheLastTileToTheDayTheHabitEnds() {
            assertThat(windows(expand(
                            PLAIN,
                            Fixtures.aHabit(o ->
                                    o.cadence("2/7d").startsOn("2025-01-06").endsOn("2025-01-16")),
                            "2025-01-06..2025-01-31")))
                    .containsExactly("2025-01-06..2025-01-12", "2025-01-13..2025-01-16");
        }

        @Test
        void isScopedAsAWindow() {
            List<Obligation> entries = expand(
                    PLAIN, Fixtures.aHabit(o -> o.cadence("2/7d").startsOn("2025-01-06")), "2025-01-06..2025-01-12");
            assertThat(entries.get(0).key()).isEqualTo("hab_0123456789ABCDEF:window:2025-01-12");
        }
    }

    @Nested
    class ExpandingAcrossRevisions {

        @Test
        void behavesAsBeforeWhenNoHistoryIsGiven() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-06"));
            assertThat(expand(PLAIN, habit, "2025-01-06..2025-01-08")).hasSize(3);
        }

        @Test
        void splitsTheWindowAtTheDayARevisionStarts() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-06"));
            HabitHistory history = HabitHistory.of(habit)
                    .revise(RevisionInput.of(r("2025-01-08..2025-01-08").from(), Cadence.parse("1/onshift-day")));
            Roster shortSwing =
                    Roster.of(Fixtures.aMember(o -> o.pattern("2on 2off").anchor("2025-01-06")));
            List<Obligation> entries = Expansion.of(shortSwing, habit, r("2025-01-06..2025-01-11"), history)
                    .expand();
            assertThat(windows(entries))
                    .containsExactly(
                            "2025-01-06..2025-01-06",
                            "2025-01-07..2025-01-07",
                            "2025-01-10..2025-01-10",
                            "2025-01-11..2025-01-11");
        }

        @Test
        void usesTheLaterRevisionForAHitchThatStraddlesTheChange() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("2/hitch").startsOn("2025-01-06"));
            HabitHistory history = HabitHistory.of(habit)
                    .revise(RevisionInput.of(r("2025-01-20..2025-01-20").from(), Cadence.parse("5/hitch")));
            List<Obligation> entries = Expansion.of(PLAIN, habit, r("2025-01-06..2025-02-02"), history)
                    .expand();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).times()).isEqualTo(5);
        }

        @Test
        void carriesATargetThatOnlyTheLaterRevisionHas() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-06"));
            HabitHistory history = HabitHistory.of(habit)
                    .revise(RevisionInput.of(r("2025-01-08..2025-01-08").from(), Cadence.parse("1/day"))
                            .withGraceDays(2));
            List<Obligation> entries = Expansion.of(PLAIN, habit, r("2025-01-06..2025-01-09"), history)
                    .expand();
            assertThat(windows(entries))
                    .containsExactly(
                            "2025-01-06..2025-01-06",
                            "2025-01-07..2025-01-07",
                            "2025-01-08..2025-01-10",
                            "2025-01-09..2025-01-11");
        }
    }

    @Nested
    class RevisionSegments {

        @Test
        void clipsEachStretchToTheWindowAskedAbout() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-06"));
            HabitHistory history = HabitHistory.of(habit)
                    .revise(RevisionInput.of(r("2025-02-01..2025-02-01").from(), Cadence.parse("1/day")));
            assertThat(Expansion.revisionSegments(history, r("2025-01-20..2025-02-10")).stream()
                            .map(DateRange::toString))
                    .containsExactly("2025-01-20..2025-01-31", "2025-02-01..2025-02-10");
        }

        @Test
        void dropsAStretchTheWindowMisses() {
            Habit habit = Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-06"));
            HabitHistory history = HabitHistory.of(habit)
                    .revise(RevisionInput.of(r("2025-02-01..2025-02-01").from(), Cadence.parse("1/day")));
            assertThat(Expansion.revisionSegments(history, r("2025-02-05..2025-02-10")))
                    .hasSize(1);
        }
    }
}
