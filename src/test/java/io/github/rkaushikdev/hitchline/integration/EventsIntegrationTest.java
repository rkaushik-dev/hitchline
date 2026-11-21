package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.AuditLog;
import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.DomainEvent;
import io.github.rkaushikdev.hitchline.app.EventBus;
import io.github.rkaushikdev.hitchline.app.EventName;
import io.github.rkaushikdev.hitchline.app.HabitsService;
import io.github.rkaushikdev.hitchline.app.HabitsService.CreateHabitCommand;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventsIntegrationTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");

    private MemoryStore store;
    private Ports ports;
    private List<DomainEvent> heard;
    private AuditLog log;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-05-16T09:00:00Z"), IdSources.sequentialIds());
        EventBus bus = EventBus.create();
        heard = new ArrayList<>();
        bus.onEvery(heard::add);
        log = AuditLog.create();
        AuditLog.auditEverything(bus, log);
        ports = store.withEvents(bus);
        store.members().save(Fixtures.aMember());
    }

    private String aNewHabit() {
        Habit created = new HabitsService(ports)
                .create(CreateHabitCommand.of(ADA, "Twenty minutes on the treadmill", "1/day")
                        .withStartsOn("2025-05-01"));
        return created.id();
    }

    private List<EventName> names() {
        return heard.stream().map(DomainEvent::name).toList();
    }

    @Nested
    class Habits {

        @Test
        void announcesANewOne() {
            aNewHabit();
            assertThat(names()).containsExactly(EventName.HABIT_CREATED);
            assertThat(heard.get(0).detail()).isEqualTo("Twenty minutes on the treadmill");
        }

        @Test
        void announcesAChangeWithWhatChanged() {
            String id = aNewHabit();
            heard.clear();
            new HabitsService(ports).changeCadence(id, "3/hitch");
            assertThat(heard.get(0).name()).isEqualTo(EventName.HABIT_CHANGED);
            assertThat(heard.get(0).detail()).isEqualTo("cadence 3/hitch");
        }

        @Test
        void announcesAnArchiveApartFromAnOrdinaryChange() {
            String id = aNewHabit();
            heard.clear();
            new HabitsService(ports).moveTo(id, HabitStatus.ARCHIVED);
            assertThat(heard.get(0).name()).isEqualTo(EventName.HABIT_ARCHIVED);
        }
    }

    @Nested
    class CheckIns {

        @Test
        void announcesOneThatLands() {
            String id = aNewHabit();
            heard.clear();
            new CheckInsService(ports).record(RecordCheckInCommand.of(id).withSource(CheckInSource.APP));
            assertThat(heard.get(0).name()).isEqualTo(EventName.CHECKIN_RECORDED);
            assertThat(heard.get(0).checkInId()).isPresent();
        }

        @Test
        void saysNothingWhenAnIdempotencyKeyRepeats() {
            String id = aNewHabit();
            RecordCheckInCommand command = RecordCheckInCommand.of(id).withIdempotencyKey("watch-0516");
            new CheckInsService(ports).record(command);
            heard.clear();
            new CheckInsService(ports).record(command);
            assertThat(heard).isEmpty();
        }

        @Test
        void announcesAVoid() {
            String id = aNewHabit();
            CheckIn entry = new CheckInsService(ports)
                    .record(RecordCheckInCommand.of(id))
                    .entry();
            heard.clear();
            new CheckInsService(ports).voidEntry(entry.id());
            assertThat(heard.get(0).name()).isEqualTo(EventName.CHECKIN_VOIDED);
        }
    }

    @Nested
    class TheRoster {

        @Test
        void announcesAHoldAndAShield() {
            new RosterService(ports)
                    .hold(new HoldCommand(
                            ADA,
                            "2025-05-20..2025-05-22",
                            DayRole.ONSHIFT,
                            OverrideReason.HELD_OVER,
                            Optional.empty()));
            new RosterService(ports)
                    .shieldDays(new ShieldCommand(
                            ADA,
                            "2025-06-02..2025-06-06",
                            ShieldReason.ANNUAL_LEAVE,
                            Optional.empty(),
                            Optional.empty()));
            assertThat(names()).containsExactly(EventName.ROSTER_HELD, EventName.SHIELD_GRANTED);
        }
    }

    @Nested
    class TheAuditLog {

        @Test
        void collectsEverythingTheServicesAnnounced() {
            String id = aNewHabit();
            new CheckInsService(ports).record(RecordCheckInCommand.of(id));
            assertThat(log.size()).isEqualTo(2);
            assertThat(log.forMember(ADA)).hasSize(2);
        }
    }

    @Nested
    class AStoreWithNoBus {

        @Test
        void worksExactlyAsBefore() {
            Habit created = new HabitsService(store).create(CreateHabitCommand.of(ADA, "Row the ergometer", "1/day"));
            assertThat(created.id()).startsWith("hab_");
            assertThat(heard).isEmpty();
        }
    }
}
