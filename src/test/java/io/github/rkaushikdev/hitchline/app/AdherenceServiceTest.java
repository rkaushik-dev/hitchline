package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.hitchline.app.AdherenceService.AdherenceReport;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.FreezeBudget;
import io.github.rkaushikdev.hitchline.domain.adherence.FrozenStreak;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.Quota;
import io.github.rkaushikdev.hitchline.domain.adherence.QuotaReading;
import io.github.rkaushikdev.hitchline.domain.adherence.RestBreach;
import io.github.rkaushikdev.hitchline.domain.adherence.RestHoursRule;
import io.github.rkaushikdev.hitchline.domain.adherence.RestRule;
import io.github.rkaushikdev.hitchline.domain.adherence.RoleSplit;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.Trend;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.MutableClock;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AdherenceServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private MutableClock clock;
    private MemoryStore store;
    private AdherenceService service;
    private CheckInsService checkIns;
    private RosterService rosters;

    @BeforeEach
    void create() {
        clock = Clocks.fixedClock("2025-01-20T09:00:00Z");
        store = MemoryStore.create(clock, IdSources.sequentialIds());
        service = new AdherenceService(store);
        checkIns = new CheckInsService(store);
        rosters = new RosterService(store);
        store.members().save(Fixtures.aMember());
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-01-06")));
    }

    private void checkInOn(String day) {
        checkIns.record(RecordCheckInCommand.of(TREADMILL).withAt(day + "T09:00:00Z"));
    }

    private void holdOver() {
        rosters.hold(new HoldCommand(
                ADA, "2025-01-20..2025-01-22", DayRole.ONSHIFT, OverrideReason.HELD_OVER, Optional.empty()));
    }

    private void shieldLeave() {
        shieldLeave("2025-01-13..2025-01-15");
    }

    private void shieldLeave(String range) {
        rosters.shieldDays(
                new ShieldCommand(ADA, range, ShieldReason.ANNUAL_LEAVE, Optional.empty(), Optional.empty()));
    }

    @Nested
    class AReportOverAHitch {

        @Test
        void owesOneObligationForEachShiftDay() {
            assertThat(service.report(ADA, "2025-01-06..2025-01-19").summary().owed())
                    .isEqualTo(14);
        }

        @Test
        void owesNothingOverAFortnightAtHome() {
            assertThat(service.report(ADA, "2025-01-20..2025-02-02").summary().owed())
                    .isZero();
        }

        @Test
        void countsWhatWasDone() {
            checkInOn("2025-01-06");
            checkInOn("2025-01-07");
            AdherenceReport report = service.report(ADA, "2025-01-06..2025-01-19");
            assertThat(report.summary().met()).isEqualTo(2);
            assertThat(report.summary().missed()).isEqualTo(12);
        }

        @Test
        void ratesTheSettledDays() {
            checkInOn("2025-01-06");
            assertThat(service.report(ADA, "2025-01-06..2025-01-19").summary().rate())
                    .isCloseTo(1.0 / 14, within(1e-9));
        }

        @Test
        void reportsTheWindowAndTheDayItWasRun() {
            AdherenceReport report = service.report(ADA, "2025-01-06..2025-01-19");
            assertThat(report.window().toString()).isEqualTo("2025-01-06..2025-01-19");
            assertThat(report.asOf().day()).isEqualTo(20);
        }

        @Test
        void splitsTheWindowByHabitAndByHitch() {
            store.habits()
                    .save(Fixtures.aHabit(
                            o -> o.id("hab_FEDCBA9876543210").cadence("1/hitch").startsOn("2025-01-06")));
            AdherenceReport report = service.report(ADA, "2025-01-06..2025-02-02");
            assertThat(report.byHabit()).hasSize(2);
            assertThat(report.byHitch()).hasSize(1);
        }

        @Test
        void complainsAboutAMemberItDoesNotHold() {
            assertThatThrownBy(() -> service.report(Ids.crewMemberId("mem_9999999999999999"), "2025-01-06..2025-01-19"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class OverridesAndShieldsFeedTheReport {

        @Test
        void addsTheDaysAnOverrideHeldSomebodyOver() {
            holdOver();
            assertThat(service.report(ADA, "2025-01-20..2025-02-02").summary().owed())
                    .isEqualTo(3);
        }

        @Test
        void holdsNothingMissedOnLeaveAgainstTheRate() {
            for (String day : List.of("2025-01-06", "2025-01-07", "2025-01-08")) {
                checkInOn(day);
            }
            AdherenceReport before = service.report(ADA, "2025-01-06..2025-01-15");
            shieldLeave();
            AdherenceReport after = service.report(ADA, "2025-01-06..2025-01-15");
            assertThat(before.summary().owed()).isEqualTo(10);
            assertThat(after.summary().owed()).isEqualTo(7);
            assertThat(after.summary().missed()).isEqualTo(before.summary().missed() - 3);
            assertThat(after.byRole().get(0).owed()).isEqualTo(7);
            assertThat(after.results()).hasSize(10);
        }

        @Test
        void listsNothingOpenOnADayOfLeave() {
            rosters.shieldDays(new ShieldCommand(
                    ADA, "2025-01-20..2025-01-31", ShieldReason.SICK, Optional.empty(), Optional.empty()));
            holdOver();
            assertThat(service.open(ADA, "2025-01-20..2025-01-31")).isEmpty();
        }

        @Test
        void asksForLessOverAHitchPartlySpentOnLeave() {
            store.habits()
                    .save(Fixtures.aHabit(
                            o -> o.id("hab_FEDCBA9876543210").cadence("3/hitch").startsOn("2025-01-06")));
            shieldLeave("2025-01-20..2025-02-02");
            List<ObligationResult> gym = service.report(ADA, "2025-01-06..2025-02-02").results().stream()
                    .filter(result -> result.obligation().habitId().equals(Ids.habitId("hab_FEDCBA9876543210")))
                    .toList();
            assertThat(gym).hasSize(1);
            assertThat(gym.get(0).obligation().times()).isEqualTo(2);
        }

        @Test
        void countsShieldedDaysInTheSummary() {
            shieldLeave();
            assertThat(service.report(ADA, "2025-01-06..2025-01-19").summary().shieldedDays())
                    .isEqualTo(3);
        }
    }

    @Nested
    class TheEndOfHitchNote {

        @Test
        void writesTheNoteForAHitchWithTheRunsGoingOnIt() {
            for (String day : List.of("2025-01-16", "2025-01-17", "2025-01-18", "2025-01-19")) {
                checkInOn(day);
            }
            Digest note = service.digest(ADA, "2025-01-06..2025-02-02");
            assertThat(note.headline()).isEqualTo("Ada Vasquez, you did 4 of 14 over 2025-01-06..2025-02-02.");
            assertThat(note.habitLines()).extracting(Digest.HabitLine::streak).containsExactly(4);
            assertThat(note.text()).contains("Longest run this hitch: Twenty minutes on the treadmill at 4.");
            assertThat(note.nextTripStarts()).contains(CivilDate.parse("2025-02-03"));
        }

        @Test
        void leavesDaysOfLeaveOutOfTheNote() {
            shieldLeave("2025-01-06..2025-01-12");
            Digest note = service.digest(ADA, "2025-01-06..2025-01-19");
            assertThat(note.summary().owed()).isEqualTo(7);
            assertThat(note.habitLines().get(0).owed()).isEqualTo(7);
            assertThat(note.text()).contains("7 days were shielded");
        }
    }

    @Nested
    class Quotas {

        @Test
        void saysWhenMoreSlippedThanTheQuotaAllows() {
            checkInOn("2025-01-06");
            QuotaReading reading = service.quota(ADA, "2025-01-06..2025-01-12", Quota.of("weekly", 2, 7));
            assertThat(reading.isBreached()).isTrue();
            assertThat(reading.worst()).isEqualTo(6);
            assertThat(reading.breaches().get(0).firstOn().toString()).isEqualTo("2025-01-07");
        }

        @Test
        void holdsNothingOnLeaveAgainstTheQuota() {
            for (String day : List.of("2025-01-06", "2025-01-07", "2025-01-08", "2025-01-09", "2025-01-10")) {
                checkInOn(day);
            }
            Quota weekly = Quota.of("weekly", 1, 7);
            assertThat(service.quota(ADA, "2025-01-06..2025-01-12", weekly).isBreached())
                    .isTrue();
            shieldLeave("2025-01-11..2025-01-12");
            QuotaReading reading = service.quota(ADA, "2025-01-06..2025-01-12", weekly);
            assertThat(reading.isBreached()).isFalse();
            assertThat(reading.headroom()).isEqualTo(1);
        }
    }

    @Nested
    class RestHours {

        private static final String REST = Ids.habitId("hab_FEDCBA9876543210");

        @BeforeEach
        void addARestLog() {
            store.habits().save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                    .name("Log the rest hours")
                    .cadence("1/day")
                    .target("at least 10 hours")
                    .startsOn("2025-01-06")));
        }

        private void logRest(String at, double hours) {
            checkIns.record(RecordCheckInCommand.of(REST).withAt(at).withReading(hours));
        }

        @Test
        void listsTheDaysThatBreakTheRules() {
            logRest("2025-01-14T05:00:00Z", 6);
            logRest("2025-01-14T18:00:00Z", 2);
            logRest("2025-01-15T05:00:00Z", 11);
            List<RestBreach> breaches = service.restHours(ADA, REST, "2025-01-13..2025-01-19", RestHoursRule.DEFAULT);
            assertThat(breaches).extracting(RestBreach::rule).containsExactly(RestRule.SHORT_DAY);
            assertThat(breaches.get(0).range().from().toString()).isEqualTo("2025-01-14");
        }

        @Test
        void readsOnlyTheRestHabitsOwnLog() {
            checkInOn("2025-01-14");
            assertThat(service.restHours(ADA, REST, "2025-01-13..2025-01-19", RestHoursRule.DEFAULT))
                    .isEmpty();
        }

        @Test
        void refusesAHabitThatDoesNotLogHours() {
            assertThatThrownBy(() -> service.restHours(ADA, TREADMILL, "2025-01-13..2025-01-19", RestHoursRule.DEFAULT))
                    .hasMessageContaining("does not log rest in hours");
        }
    }

    @Nested
    class RepeatedTaps {

        @Test
        void scoresATapSentTwiceAsOneCheckIn() {
            store.habits().save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                    .cadence("2/onshift-day")
                    .startsOn("2025-01-06")));
            String twice = Ids.habitId("hab_FEDCBA9876543210");
            checkIns.record(RecordCheckInCommand.of(twice).withAt("2025-01-08T09:00:00Z"));
            checkIns.record(RecordCheckInCommand.of(twice).withAt("2025-01-08T09:00:40Z"));
            List<ObligationResult> day = service.report(ADA, "2025-01-08..2025-01-08").results().stream()
                    .filter(result -> result.obligation().habitId().equals(twice))
                    .toList();
            assertThat(day.get(0).count()).isEqualTo(1);
            assertThat(day.get(0).status()).isEqualTo(AdherenceStatus.PARTIAL);
            checkIns.record(RecordCheckInCommand.of(twice).withAt("2025-01-08T19:00:00Z"));
            assertThat(service.report(ADA, "2025-01-08..2025-01-08").summary().met())
                    .isEqualTo(1);
        }
    }

    @Nested
    class Streaks {

        @Test
        void countsAnUnbrokenRunOfShiftDays() {
            for (String day : List.of("2025-01-06", "2025-01-07", "2025-01-08")) {
                checkInOn(day);
            }
            assertThat(service.streak(ADA, TREADMILL, "2025-01-06..2025-01-08").current())
                    .isEqualTo(3);
        }

        @Test
        void survivesAShieldedGap() {
            for (String day : List.of("2025-01-12", "2025-01-16")) {
                checkInOn(day);
            }
            shieldLeave();
            StreakSummary summary = service.streak(ADA, TREADMILL, "2025-01-12..2025-01-16");
            assertThat(summary.current()).isEqualTo(2);
            assertThat(summary.shielded()).isEqualTo(3);
        }

        @Test
        void carriesAMissWhenGivenAFreezeBudget() {
            for (String day : List.of("2025-01-06", "2025-01-07", "2025-01-09")) {
                checkInOn(day);
            }
            assertThat(service.streak(ADA, TREADMILL, "2025-01-06..2025-01-09").current())
                    .isEqualTo(1);
            FrozenStreak carried = service.frozenStreak(ADA, TREADMILL, "2025-01-06..2025-01-09", FreezeBudget.DEFAULT);
            assertThat(carried.current()).isEqualTo(3);
            assertThat(carried.frozen()).isEqualTo(1);
            assertThat(carried.freezesLeft()).isZero();
            assertThat(service.frozenStreak(ADA, TREADMILL, "2025-01-06..2025-01-09", FreezeBudget.of(0, 28))
                            .current())
                    .isEqualTo(1);
        }

        @Test
        void complainsAboutAHabitTheMemberDoesNotHave() {
            assertThatThrownBy(() -> service.streak(ADA, Ids.habitId("hab_9999999999999999"), "2025-01-06..2025-01-19"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class WhatIsStillOpen {

        @Test
        void listsOnlyThePendingObligationsWithNothingDone() {
            clock.set(store.clock().now());
            assertThat(service.open(ADA, "2025-01-06..2025-01-31"))
                    .allMatch(result -> result.status() == AdherenceStatus.PENDING);
        }

        @Test
        void dropsOneThatHasBeenDone() {
            List<ObligationResult> before = service.open(ADA, "2025-01-20..2025-01-31");
            holdOver();
            List<ObligationResult> after = service.open(ADA, "2025-01-20..2025-01-31");
            assertThat(after.size()).isGreaterThan(before.size());
        }
    }

    @Nested
    class TheShapeOfAReport {

        @Test
        void splitsTheResultsByTheRoleOfTheDay() {
            checkInOn("2025-01-06");
            AdherenceReport report = service.report(ADA, "2025-01-06..2025-01-19");
            assertThat(report.byRole().stream().map(RoleSplit::role)).containsExactly(DayRole.ONSHIFT);
            assertThat(report.byRole().get(0).owed()).isEqualTo(14);
        }

        @Test
        void saysItCannotReadATrendFromOneHitch() {
            assertThat(service.report(ADA, "2025-01-06..2025-01-19").trend()).isEqualTo(Trend.UNKNOWN);
        }

        @Test
        void namesTheBusiestHourOnceSomethingHasBeenRecorded() {
            checkInOn("2025-01-06");
            checkInOn("2025-01-07");
            assertThat(service.report(ADA, "2025-01-06..2025-01-19").busiestHour())
                    .hasValue(10);
        }

        @Test
        void hasNoBusiestHourWhenNothingHasBeenRecorded() {
            assertThat(service.report(ADA, "2025-01-06..2025-01-19").busiestHour())
                    .isEmpty();
        }
    }
}
