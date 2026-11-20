package io.github.rkaushikdev.hitchline.domain.nudge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NudgePlannerTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static final Roster PLAIN = Roster.of(MEMBER);
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String STRETCHES = Ids.habitId("hab_FEDCBA9876543210");
    private static final Instant AS_OF = Zones.parseInstant("2025-01-10T06:00:00Z");
    private static final NudgePolicy POLICY =
            NudgePolicy.builder().leadMinutes(240).quietHours("22:00-06:00").build();

    private static ObligationResult pending(String window) {
        return pending(window, 0, 1, TREADMILL);
    }

    private static ObligationResult pending(String window, int count, int times, String habit) {
        Obligation owed = new Obligation(habit, MEMBER.id(), ObligationScope.DAY, DateRange.parse(window), times);
        return new ObligationResult(owed, count, OptionalDouble.empty(), AdherenceStatus.PENDING, 0);
    }

    private static List<PlannedNudge> plan(ObligationResult... results) {
        return NudgePlanner.plan(PLAIN, POLICY, List.of(results), AS_OF);
    }

    private static PlannedNudge first(ObligationResult... results) {
        return plan(results).get(0);
    }

    @Nested
    class PlanningANudge {

        @Test
        void landsFourHoursBeforeTheEndOfTheLocalDay() {
            assertThat(Zones.formatInstant(
                            first(pending("2025-01-10..2025-01-10")).at()))
                    .isEqualTo("2025-01-10T19:00:00.000Z");
        }

        @Test
        void usesTheHomeClockOnAnOffShiftDay() {
            assertThat(Zones.formatInstant(
                            first(pending("2025-01-25..2025-01-25")).at()))
                    .isEqualTo("2025-01-25T20:00:00.000Z");
        }

        @Test
        void saysNothingHasBeenDoneYet() {
            assertThat(first(pending("2025-01-10..2025-01-10")).reason()).isEqualTo(NudgeReason.NOTHING_YET);
        }

        @Test
        void saysPartWayWhenSomethingIsAlreadyIn() {
            assertThat(first(pending("2025-01-10..2025-01-10", 1, 2, TREADMILL)).reason())
                    .isEqualTo(NudgeReason.PART_WAY);
        }

        @Test
        void saysLastChanceWhenTheWindowWasLongerThanADay() {
            assertThat(first(pending("2025-01-06..2025-01-19")).reason()).isEqualTo(NudgeReason.LAST_CHANCE);
        }
    }

    @Nested
    class WhatIsLeftOut {

        @Test
        void leavesOutAnythingAlreadySettled() {
            ObligationResult open = pending("2025-01-10..2025-01-10");
            ObligationResult met =
                    new ObligationResult(open.obligation(), 1, open.total(), AdherenceStatus.MET, open.progress());
            assertThat(plan(met)).isEmpty();
        }

        @Test
        void leavesOutAnObligationAlreadyDoneEnoughTimes() {
            assertThat(plan(pending("2025-01-10..2025-01-10", 2, 2, TREADMILL))).isEmpty();
        }

        @Test
        void leavesOutANudgeWhoseMomentHasPassed() {
            Instant late = Zones.parseInstant("2025-01-10T20:00:00Z");
            assertThat(NudgePlanner.plan(PLAIN, POLICY, List.of(pending("2025-01-10..2025-01-10")), late))
                    .isEmpty();
        }

        @Test
        void leavesOutADayThePolicyDoesNotNudgeOn() {
            Roster flying = Roster.of(Fixtures.aMember(o -> o.pattern("1travel 13on 1travel 13off")));
            assertThat(NudgePlanner.plan(flying, POLICY, List.of(pending("2025-01-06..2025-01-06")), AS_OF))
                    .isEmpty();
        }

        @Test
        void plansNothingAtAllForASilentPolicy() {
            NudgePolicy silent = NudgePolicy.builder().maxPerDay(0).build();
            assertThat(NudgePlanner.plan(PLAIN, silent, List.of(pending("2025-01-10..2025-01-10")), AS_OF))
                    .isEmpty();
        }

        @Test
        void dropsANudgeThatQuietHoursWouldPushPastTheWindow() {
            NudgePolicy nocturnal = NudgePolicy.builder()
                    .leadMinutes(60)
                    .quietHours("22:00-06:00")
                    .build();
            assertThat(NudgePlanner.plan(PLAIN, nocturnal, List.of(pending("2025-01-10..2025-01-10")), AS_OF))
                    .isEmpty();
        }
    }

    @Nested
    class Capping {

        private final CivilDate day = CivilDate.parse("2025-01-10");

        private PlannedNudge planned(int hour) {
            return new PlannedNudge(
                    TREADMILL,
                    TREADMILL + ":day:2025-01-10",
                    Zones.parseInstant("2025-01-10T" + Texts.padStart(Integer.toString(hour), 2, '0') + ":00:00Z"),
                    day,
                    NudgeReason.NOTHING_YET);
        }

        @Test
        void keepsTheEarliestFew() {
            List<PlannedNudge> capped = NudgePlanner.capPerDay(
                    List.of(planned(6), planned(10), planned(14), planned(18)),
                    NudgePolicy.builder().maxPerDay(2).minGapMinutes(60).build());
            assertThat(capped).hasSize(2);
        }

        @Test
        void dropsAnythingTooCloseBehindTheLastOne() {
            List<PlannedNudge> capped = NudgePlanner.capPerDay(
                    List.of(planned(6), planned(7)),
                    NudgePolicy.builder().maxPerDay(3).minGapMinutes(180).build());
            assertThat(capped).hasSize(1);
        }

        @Test
        void keepsANudgeFarEnoughBehind() {
            List<PlannedNudge> capped = NudgePlanner.capPerDay(
                    List.of(planned(6), planned(10)),
                    NudgePolicy.builder().maxPerDay(3).minGapMinutes(180).build());
            assertThat(capped).hasSize(2);
        }
    }

    @Nested
    class ReadingAPlan {

        @Test
        void filtersToOneHabit() {
            List<PlannedNudge> plan = NudgePlanner.plan(
                    PLAIN,
                    NudgePolicy.builder()
                            .leadMinutes(240)
                            .maxPerDay(5)
                            .minGapMinutes(1)
                            .build(),
                    List.of(pending("2025-01-10..2025-01-10"), pending("2025-01-10..2025-01-10", 0, 1, STRETCHES)),
                    AS_OF);
            assertThat(NudgePlanner.forHabit(plan, STRETCHES)).hasSize(1);
        }

        @Test
        void readsBackAsALine() {
            assertThat(first(pending("2025-01-10..2025-01-10")).describe())
                    .isEqualTo("nothing_yet at 2025-01-10T19:00:00.000Z");
        }
    }

    @Nested
    class TwoHabitsDueAtTheSameMinute {

        @Test
        void rideAlongTogetherRatherThanDroppingOne() {
            List<PlannedNudge> plan =
                    plan(pending("2025-01-10..2025-01-10"), pending("2025-01-10..2025-01-10", 0, 1, STRETCHES));
            assertThat(plan).hasSize(2);
            assertThat(plan.get(0).at()).isEqualTo(plan.get(1).at());
        }

        @Test
        void stillCountsAsOneAgainstTheDailyCap() {
            List<PlannedNudge> plan = NudgePlanner.plan(
                    PLAIN,
                    NudgePolicy.builder().leadMinutes(240).maxPerDay(1).build(),
                    List.of(pending("2025-01-10..2025-01-10"), pending("2025-01-10..2025-01-10", 0, 1, STRETCHES)),
                    AS_OF);
            assertThat(plan).hasSize(2);
        }
    }
}
