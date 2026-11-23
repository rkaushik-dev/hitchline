package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.nudge.PlannedNudge;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NudgesServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String NOBODY = Ids.crewMemberId("mem_9999999999999999");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private MemoryStore store;
    private NudgesService service;
    private CheckInsService checkIns;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-10T06:00:00Z"), IdSources.sequentialIds());
        service = new NudgesService(store);
        checkIns = new CheckInsService(store);
        store.members().save(Fixtures.aMember());
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-01-06")));
    }

    private void checkInAt(String at) {
        checkIns.record(RecordCheckInCommand.of(TREADMILL).withAt(at));
    }

    @Nested
    class Policies {

        @Test
        void fallsBackToTheDefaultWhenNoneIsStored() {
            assertThat(service.policyFor(ADA)).isEqualTo(NudgePolicy.DEFAULT_POLICY);
        }

        @Test
        void storesOneAndReadsItBack() {
            service.setPolicy(ADA, NudgePolicy.builder().maxPerDay(1).quietHours("23:00-07:00"));
            NudgePolicy stored = service.policyFor(ADA);
            assertThat(stored.maxPerDay()).isEqualTo(1);
            assertThat(stored.quietHours().to().hour()).isEqualTo(7);
        }

        @Test
        void validatesOnTheWayIn() {
            assertThatThrownBy(
                            () -> service.setPolicy(ADA, NudgePolicy.builder().maxPerDay(99)))
                    .hasMessageContaining("between 0 and 12");
        }

        @Test
        void complainsAboutAMemberItDoesNotHold() {
            assertThatThrownBy(() -> service.setPolicy(NOBODY, NudgePolicy.builder()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Planning {

        @Test
        void sendsNothingAboutADayOfLeave() {
            new RosterService(store)
                    .shieldDays(new ShieldCommand(
                            ADA,
                            "2025-01-10..2025-01-11",
                            ShieldReason.ANNUAL_LEAVE,
                            Optional.empty(),
                            Optional.empty()));
            assertThat(service.plan(ADA, "2025-01-10..2025-01-10")).isEmpty();
            assertThat(service.plan(ADA, "2025-01-10..2025-01-12"))
                    .extracting(PlannedNudge::onDate)
                    .extracting(Object::toString)
                    .containsExactly("2025-01-12");
        }

        @Test
        void plansOneNudgeForTheOpenDay() {
            List<PlannedNudge> plan = service.plan(ADA, "2025-01-10..2025-01-10");
            assertThat(plan).hasSize(1);
            assertThat(Zones.formatInstant(plan.get(0).at())).isEqualTo("2025-01-10T19:00:00.000Z");
        }

        @Test
        void plansNothingOnceTheDayIsDone() {
            checkInAt("2025-01-10T05:00:00Z");
            assertThat(service.plan(ADA, "2025-01-10..2025-01-10")).isEmpty();
        }

        @Test
        void plansNothingForAFortnightAtHome() {
            assertThat(service.plan(ADA, "2025-01-20..2025-02-02")).isEmpty();
        }

        @Test
        void honoursAStoredPolicy() {
            service.setPolicy(ADA, NudgePolicy.builder().maxPerDay(0));
            assertThat(service.plan(ADA, "2025-01-10..2025-01-19")).isEmpty();
        }

        @Test
        void capsARunOfDaysAtTheDailyLimit() {
            service.setPolicy(ADA, NudgePolicy.builder().maxPerDay(1).leadMinutes(240));
            assertThat(service.plan(ADA, "2025-01-10..2025-01-12")).hasSize(3);
        }

        @Test
        void complainsAboutAMemberItDoesNotHold() {
            assertThatThrownBy(() -> service.plan(NOBODY, "2025-01-10..2025-01-10"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Risk {

        @Test
        void isNotAtRiskWithNoRunGoing() {
            assertThat(service.atRisk(ADA, "2025-01-06..2025-01-19")).isFalse();
        }

        @Test
        void isAtRiskOnceARunIsAliveAndTodayIsOpen() {
            checkInAt("2025-01-08T09:00:00Z");
            checkInAt("2025-01-09T09:00:00Z");
            assertThat(service.atRisk(ADA, "2025-01-08..2025-01-10")).isTrue();
        }

        @Test
        void isNotAtRiskOnceTodayIsDone() {
            checkInAt("2025-01-08T09:00:00Z");
            checkInAt("2025-01-09T09:00:00Z");
            checkInAt("2025-01-10T05:00:00Z");
            assertThat(service.atRisk(ADA, "2025-01-08..2025-01-10")).isFalse();
        }
    }
}
