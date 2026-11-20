package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AuditLogTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private static DomainEvent anEvent(EventName name, String at) {
        return anEvent(name, at, ADA);
    }

    private static DomainEvent anEvent(EventName name, String at, String memberId) {
        return DomainEvent.of(name, Zones.parseInstant(at), memberId).withHabit(TREADMILL);
    }

    @Nested
    class Recording {

        @Test
        void numbersEntriesFromOne() {
            AuditLog log = AuditLog.create();
            AuditEntry first = log.record(anEvent(EventName.HABIT_CREATED, "2025-05-14T09:00:00Z"));
            AuditEntry second = log.record(anEvent(EventName.HABIT_CHANGED, "2025-05-14T10:00:00Z"));
            assertThat(first.sequence()).isEqualTo(1);
            assertThat(second.sequence()).isEqualTo(2);
            assertThat(log.size()).isEqualTo(2);
        }

        @Test
        void keepsEverythingTheEventCarried() {
            AuditEntry entry = AuditLog.create().record(anEvent(EventName.HABIT_CREATED, "2025-05-14T09:00:00Z"));
            assertThat(entry.habitId()).hasValue(TREADMILL);
            assertThat(entry.memberId()).isEqualTo(ADA);
        }

        @Test
        void dropsTheOldestOnceItIsFull() {
            AuditLog log = AuditLog.create(3);
            for (int index = 0; index < 5; index += 1) {
                log.record(anEvent(EventName.HABIT_CHANGED, "2025-05-14T09:00:00Z"));
            }
            assertThat(log.size()).isEqualTo(3);
            assertThat(log.all().get(0).sequence()).isEqualTo(3);
        }
    }

    @Nested
    class ReadingItBack {

        private final AuditLog log = AuditLog.create();

        ReadingItBack() {
            log.record(anEvent(EventName.HABIT_CREATED, "2025-05-14T09:00:00Z"));
            log.record(anEvent(EventName.CHECKIN_RECORDED, "2025-05-15T09:00:00Z"));
            log.record(anEvent(EventName.HABIT_CHANGED, "2025-06-01T09:00:00Z", BO));
        }

        @Test
        void filtersToOneMember() {
            assertThat(log.forMember(ADA)).hasSize(2);
            assertThat(log.forMember(BO)).hasSize(1);
        }

        @Test
        void filtersToOneKindOfEvent() {
            assertThat(log.named(EventName.CHECKIN_RECORDED)).hasSize(1);
            assertThat(log.named(EventName.CREW_ENROLLED)).isEmpty();
        }

        @Test
        void filtersToAWindowInAZone() {
            assertThat(log.inWindow(DateRange.parse("2025-05-01..2025-05-31"), "UTC"))
                    .hasSize(2);
        }

        @Test
        void readsTheWindowInTheZoneItWasGiven() {
            AuditLog late = AuditLog.create();
            late.record(anEvent(EventName.HABIT_CREATED, "2025-05-31T23:30:00Z"));
            assertThat(late.inWindow(DateRange.parse("2025-05-01..2025-05-31"), "UTC"))
                    .hasSize(1);
            assertThat(late.inWindow(DateRange.parse("2025-05-01..2025-05-31"), "Europe/Oslo"))
                    .isEmpty();
        }
    }

    @Nested
    class WiringItToTheBus {

        @Test
        void recordsEverythingThatIsPublished() {
            EventBus bus = EventBus.create();
            AuditLog log = AuditLog.create();
            AuditLog.auditEverything(bus, log);
            bus.publish(anEvent(EventName.HABIT_CREATED, "2025-05-14T09:00:00Z"));
            bus.publish(anEvent(EventName.CHECKIN_RECORDED, "2025-05-14T10:00:00Z"));
            assertThat(log.size()).isEqualTo(2);
        }

        @Test
        void stopsRecordingOnceUnsubscribed() {
            EventBus bus = EventBus.create();
            AuditLog log = AuditLog.create();
            Runnable off = AuditLog.auditEverything(bus, log);
            off.run();
            bus.publish(anEvent(EventName.HABIT_CREATED, "2025-05-14T09:00:00Z"));
            assertThat(log.size()).isZero();
        }
    }
}
